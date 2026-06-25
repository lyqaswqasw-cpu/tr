package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import com.example.ui.MainViewModel

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerView(
    streamUrl: String,
    title: String,
    isLive: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    autoLandscape: Boolean = false,
    userAgent: String = "",
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    
    // Handle back button to close the video player instead of exiting the App
    BackHandler(enabled = true) {
        onClose()
    }
    val activity = remember(context) { context.findActivity() }

    // State indicators
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var progressMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferMs by remember { mutableLongStateOf(0L) }

    var isChatOpen by remember { mutableStateOf(false) }
    val isChatEnabledOnline by viewModel.isChatEnabledOnline.collectAsState()

    val channelId = remember(title) {
        title.replace("[^a-zA-Z0-9أ-ي]".toRegex(), "_").lowercase().ifEmpty { "default_channel" }
    }

    LaunchedEffect(isChatEnabledOnline) {
        if (!isChatEnabledOnline) {
            isChatOpen = false
        }
    }

    LaunchedEffect(channelId, isChatOpen) {
        if (isChatOpen) {
            while (true) {
                viewModel.fetchChatMessages(channelId)
                delay(3000L)
            }
        }
    }
    
    // Live extension format: "ts", "m3u8", "normal" (Default: ts)
    val forcedFormat by viewModel.forcedFormat.collectAsState()
    var currentFormat by remember { mutableStateOf(forcedFormat) }
    
    LaunchedEffect(forcedFormat) {
        currentFormat = forcedFormat
    }
    
    // Format the URL dynamically based on currentFormat selection
    val parsedStreamUrl = remember(streamUrl, currentFormat) {
        if (streamUrl.isEmpty()) return@remember streamUrl
        
        // Handle Xtream-style URLs with output parameter
        if (streamUrl.contains("output=")) {
            val updated = streamUrl.replace("output=ts", "output=$currentFormat")
                .replace("output=m3u8", "output=$currentFormat")
            if (updated != streamUrl) return@remember updated
        }
        
        val lastSlash = streamUrl.lastIndexOf('/')
        val dotIndex = streamUrl.lastIndexOf('.')
        
        if (dotIndex > lastSlash) {
            val base = streamUrl.substring(0, dotIndex)
            when (currentFormat) {
                "ts" -> "$base.ts"
                "m3u8" -> "$base.m3u8"
                "normal" -> base
                else -> streamUrl
            }
        } else {
            when (currentFormat) {
                "ts" -> if (streamUrl.contains("?")) streamUrl.replace("?", ".ts?") else "$streamUrl.ts"
                "m3u8" -> if (streamUrl.contains("?")) streamUrl.replace("?", ".m3u8?") else "$streamUrl.m3u8"
                "normal" -> streamUrl
                else -> streamUrl
            }
        }
    }

    // Aspect Ratio options: "16_9", "fill"
    var playerAspectRatioMode by remember { mutableStateOf("16_9") }
    var isMuted by remember { mutableStateOf(false) }

    // Lock Screen to Landscape for Player view and hide system bars
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = if (autoLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            activity?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Initialize ExoPlayer with dynamic userAgent
    val exoPlayer = remember(userAgent, parsedStreamUrl) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        if (userAgent.isNotEmpty()) {
            httpDataSourceFactory.setUserAgent(userAgent)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500,   // minBufferMs (default is 15000)
                5000,   // maxBufferMs (default is 50000)
                500,    // bufferForPlaybackMs (default is 2500)
                1000    // bufferForPlaybackAfterRebufferMs (default is 5000)
            )
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    var isPlayerReleased by remember { mutableStateOf(false) }

    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    // React to stream format or stream input changes
    LaunchedEffect(parsedStreamUrl) {
        try {
            if (!isPlayerReleased) {
                Log.d("VideoPlayerView", "Streaming: $parsedStreamUrl | format: $currentFormat")
                val mediaItem = MediaItem.fromUri(Uri.parse(parsedStreamUrl))
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                playbackError = null
                isBuffering = true
            }
        } catch (e: Exception) {
            playbackError = "فشل في تشغيل هذا البث المباشر"
        }
    }

    // Auto-reconnect and format switching logic
    var retryCount by remember { mutableStateOf(0) }
    
    // Attach Player Custom Listeners
    DisposableEffect(exoPlayer) {
        playerView.player = exoPlayer
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    retryCount = 0 // Reset retry count on success
                    playbackError = null
                    try {
                        durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    } catch (e: Exception) {
                        Log.e("VideoPlayerView", "Error updating duration", e)
                    }
                }
                
                // Auto-reconnect if the stream ends (common with some IPTV links that time out)
                if (state == Player.STATE_ENDED && !isPlayerReleased) {
                    Log.d("VideoPlayerView", "Stream ended unexpectedly, auto-switching format and reconnecting...")
                    retryCount++
                    // Toggle format between ts and m3u8
                    currentFormat = if (currentFormat == "ts") "m3u8" else "ts"
                    
                    isBuffering = true
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
            }

            override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
                isPlaying = isPlayingChanged
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("VideoPlayerView", "ExoPlayer Error Code: ${error.errorCode}", error)
                
                // Auto-retry for source/network related errors
                val isSourceError = error.errorCode in 2000..2005 || error.errorCode == 1002
                if (isSourceError && !isPlayerReleased && retryCount < 5) {
                    Log.d("VideoPlayerView", "Source error detected, auto-switching format and retrying...")
                    retryCount++
                    // Toggle format
                    currentFormat = if (currentFormat == "ts") "m3u8" else "ts"
                    
                    isBuffering = true
                    playbackError = null
                    exoPlayer.prepare()
                    exoPlayer.play()
                } else {
                    playbackError = "خطأ في تشغيل البث المباشر: تأكّد من صيغة البث والقنوات."
                    isBuffering = false
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            isPlayerReleased = true
            playerView.player = null
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Continuously monitor playback progress times
    LaunchedEffect(isPlaying) {
        while (isPlaying && !isPlayerReleased) {
            try {
                progressMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                bufferMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            } catch (e: Exception) {
                break
            }
            delay(1000L)
        }
    }

    // Auto-hide controls timer
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5000L)
            showControls = false
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Player Container Component
        Box(
            modifier = Modifier
                .weight(if (isChatOpen) 3f else 1f)
                .fillMaxHeight()
                .background(Color.Black)
                .testTag("cinematic_video_player")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { playerView },
                    update = { view ->
                        view.resizeMode = when (playerAspectRatioMode) {
                            "fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier
                        .then(
                            if (playerAspectRatioMode == "16_9") {
                                Modifier.aspectRatio(16f / 9f)
                            } else {
                                Modifier.fillMaxSize()
                            }
                        )
                )
            }

            // Backdrop visual gradients to highlight controls when visible
            androidx.compose.animation.AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(250))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.8f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.8f)
                                )
                            )
                        )
                )
            }

            // Buffer Indicator Spinner
            if (isBuffering) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp,
                    modifier = Modifier
                        .size(54.dp)
                        .align(Alignment.Center)
                )
            }

            // Playback Error alert card
            if (playbackError != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .widthIn(max = 400.dp)
                        .padding(16.dp)
                        .align(Alignment.Center)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "تحذير",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = playbackError ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                try {
                                    if (!isPlayerReleased) {
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                    }
                                } catch (e: Exception) {
                                    Log.e("VideoPlayerView", "Error on retry", e)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("إعادة المحاولة", color = Color.White)
                        }
                    }
                }
            }

            // Overlay Interactive controls HUD
            androidx.compose.animation.AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .testTag("player_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "رجوع",
                                tint = Color.White
                            )
                        }

                        if (isChatEnabledOnline) {
                            IconButton(
                                onClick = { isChatOpen = !isChatOpen },
                                modifier = Modifier
                                    .background(if (isChatOpen) Color(0xFFE50914) else Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Chat,
                                    contentDescription = "دردشة البث المباشر",
                                    tint = Color.White
                                )
                            }
                        }

                        // Share Button
                        IconButton(
                            onClick = {
                                viewModel.generateShareLink(title, streamUrl) { link ->
                                    if (link.isNotEmpty()) {
                                        val sendIntent: Intent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, "شاهد $title على تطبيق 25 Live: $link")
                                            type = "text/plain"
                                        }
                                        val shareIntent = Intent.createChooser(sendIntent, null)
                                        context.startActivity(shareIntent)
                                    } else {
                                        Toast.makeText(context, "فشل إنشاء رابط المشاركة", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "مشاركة",
                                tint = Color.White
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isLive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color.Red, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "بث مباشر (LIVE)",
                                    color = Color.Red,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Controls HUD
            androidx.compose.animation.AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Sizing and scaling, forward and rewind buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                    ) {
                        // 1. Aspect Ratio Controller (ONLY 16:9 & Fill)
                        IconButton(
                            onClick = {
                                playerAspectRatioMode = if (playerAspectRatioMode == "16_9") "fill" else "16_9"
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (playerAspectRatioMode == "16_9") Icons.Default.Tv else Icons.Default.Fullscreen,
                                contentDescription = if (playerAspectRatioMode == "16_9") "نسبة 16:9" else "تعبئة شاشة",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 2. Format Selector
                        Box {
                            var showFormatMenu by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showFormatMenu = true },
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SettingsInputComponent,
                                    contentDescription = "صيغة البث",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showFormatMenu,
                                onDismissRequest = { showFormatMenu = false },
                                modifier = Modifier.background(Color(0xFF1E1E1E))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("بث مباشر (TS)", color = Color.White, fontSize = 12.sp) },
                                    onClick = {
                                        currentFormat = "ts"
                                        showFormatMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("بث مباشر (M3U8)", color = Color.White, fontSize = 12.sp) },
                                    onClick = {
                                        currentFormat = "m3u8"
                                        showFormatMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("بث مباشر (عادي)", color = Color.White, fontSize = 12.sp) },
                                    onClick = {
                                        currentFormat = "normal"
                                        showFormatMenu = false
                                    }
                                )
                            }
                        }

                        // 3. Fast Rewind
                        IconButton(
                            onClick = {
                                try {
                                    if (!isPlayerReleased) {
                                        val currentPos = exoPlayer.currentPosition
                                        exoPlayer.seekTo((currentPos - 10000).coerceAtLeast(0L))
                                    }
                                } catch (e: Exception) {
                                    Log.e("VideoPlayerView", "Error seeking backward", e)
                                }
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = "تراجع 10 ثوان",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 4. Play/Pause Toggle
                        IconButton(
                            onClick = {
                                try {
                                    if (!isPlayerReleased) {
                                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    }
                                } catch (e: Exception) {
                                    Log.e("VideoPlayerView", "Error on play/pause toggle", e)
                                }
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .testTag("player_play_pause")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "تشغيل/إيقاف مؤقت",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // 5. Fast Forward
                        IconButton(
                            onClick = {
                                try {
                                    if (!isPlayerReleased) {
                                        val currentPos = exoPlayer.currentPosition
                                        exoPlayer.seekTo((currentPos + 10000).coerceAtMost(durationMs))
                                    }
                                } catch (e: Exception) {
                                    Log.e("VideoPlayerView", "Error seeking forward", e)
                                }
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "تقديم 10 ثوان",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 6. Volume Mute
                        IconButton(
                            onClick = {
                                try {
                                    if (!isPlayerReleased) {
                                        val currVol = exoPlayer.volume
                                        val nextVol = if (currVol > 0f) 0f else 1f
                                        exoPlayer.volume = nextVol
                                        isMuted = nextVol == 0f
                                    }
                                } catch (e: Exception) {
                                    Log.e("VideoPlayerView", "Error toggling volume", e)
                                }
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "كتم الصوت",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Progress Bar tracker (Only draw if NOT live streaming!)
                    if (!isLive && durationMs > 0) {
                        Column {
                            Slider(
                                value = progressMs.toFloat(),
                                onValueChange = { floatVal ->
                                    progressMs = floatVal.toLong()
                                    try {
                                        if (!isPlayerReleased) {
                                            exoPlayer.seekTo(progressMs)
                                        }
                                    } catch (e: Exception) {
                                        Log.e("VideoPlayerView", "Error from slider seek", e)
                                    }
                                },
                                valueRange = 0f..durationMs.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatTime(progressMs),
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = formatTime(durationMs),
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = isChatOpen && isChatEnabledOnline,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            ChatPanel(viewModel = viewModel, channelId = channelId)
        }
    }
}

@Composable
fun ChatPanel(viewModel: MainViewModel, channelId: String) {
    val context = LocalContext.current
    val chatMessages by viewModel.chatMessages.collectAsState()
    
    val prefs = remember(context) { context.getSharedPreferences("loop_live_prefs", Context.MODE_PRIVATE) }
    var senderName by remember { mutableStateOf(prefs.getString("chat_sender_name", "متابع") ?: "متابع") }
    var typedMessage by remember { mutableStateOf("") }
    
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    
    // Auto scroll to bottom when new messages arrive
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            lazyListState.animateScrollToItem(chatMessages.size - 1)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(12.dp)
    ) {
        // Chat Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    tint = Color(0xFFE50914) // LoopRed style
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "دردشة البث المباشر",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Connected indicator dot
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color.Green)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("متصل", color = Color.Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // Sender Name Setting Row (Tiny elegant input)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.03f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("اسمك:", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Spacer(modifier = Modifier.width(6.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = senderName,
                onValueChange = { 
                    val sanitized = it.take(15)
                    senderName = sanitized
                    prefs.edit().putString("chat_sender_name", sanitized).apply()
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color(0xFFFFD700), // Gold Color for name
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                ),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Message list (scrollable)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (chatMessages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.QuestionAnswer,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "لا توجد رسائل حالياً.",
                        color = Color.White.copy(alpha = 0.3f),
                        fontSize = 10.sp
                    )
                    Text(
                        text = "كن أول من يشارك في الدردشة!",
                        color = Color.White.copy(alpha = 0.3f),
                        fontSize = 9.sp
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(chatMessages.size) { index ->
                        val msg = chatMessages[index]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.02f))
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = msg.sender,
                                    color = Color(0xFFFFD700), // Gold
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                // Tiny relative time or timestamp
                                val timeStr = remember(msg.timestamp) {
                                    val sdf = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                                    sdf.format(java.util.Date(msg.timestamp))
                                }
                                Text(
                                    text = timeStr,
                                    color = Color.White.copy(alpha = 0.25f),
                                    fontSize = 8.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = msg.text,
                                color = Color.White,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        // Quick Emojis Selection Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val quickEmojis = listOf("🔥", "⚽", "👏", "😍", "😂", "👑", "👍")
            quickEmojis.forEach { emoji ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            typedMessage += emoji
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = emoji, fontSize = 14.sp)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        // Input text box and Send Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = typedMessage,
                onValueChange = { typedMessage = it },
                placeholder = { Text("اكتب رسالة...", fontSize = 11.sp, color = Color.White.copy(alpha = 0.3f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFE50914),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedContainerColor = Color(0xFF151515),
                    unfocusedContainerColor = Color(0xFF151515),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            )
            
            IconButton(
                onClick = {
                    if (typedMessage.trim().isNotEmpty()) {
                        viewModel.sendChatMessage(channelId, senderName, typedMessage.trim())
                        typedMessage = ""
                    }
                },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE50914))
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "إرسال",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSecs = ms / 1000L
    val hours = totalSecs / 3600L
    val minutes = (totalSecs % 3600L) / 60L
    val seconds = totalSecs % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
