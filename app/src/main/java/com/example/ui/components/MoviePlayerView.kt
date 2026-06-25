package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerView(
    streamUrl: String,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    autoLandscape: Boolean = false
) {
    val context = LocalContext.current
    
    // Handle physical back button
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
    var playerAspectRatioMode by remember { mutableStateOf("fit") }
    var isMuted by remember { mutableStateOf(false) }

    // Silent autodection tracker: 0 = mkv, 1 = mp4, 2 = original fallback
    var attemptState by remember { mutableStateOf(0) }
    
    // Derive base URL (strip existing .mkv, .mp4, etc if any)
    val baseUrl = remember(streamUrl) {
        val lastSlash = streamUrl.lastIndexOf('/')
        val dotIndex = streamUrl.lastIndexOf('.')
        if (dotIndex > lastSlash) {
            streamUrl.substring(0, dotIndex)
        } else {
            streamUrl
        }
    }

    // Format current URL dynamically depending on attempt state
    val activeUrl = remember(baseUrl, attemptState) {
        when (attemptState) {
            0 -> "$baseUrl.mkv"
            1 -> "$baseUrl.mp4"
            else -> streamUrl
        }
    }

    // Lock screen orientation to landscape
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

    // Initialize ExoPlayer
    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500,   // minBufferMs (default is 15000)
                5000,   // maxBufferMs (default is 50000)
                500,    // bufferForPlaybackMs (default is 2500)
                1000    // bufferForPlaybackAfterRebufferMs (default is 5000)
            )
            .build()

        ExoPlayer.Builder(context)
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

    // React to active format URLs
    LaunchedEffect(activeUrl) {
        try {
            if (!isPlayerReleased) {
                Log.d("MoviePlayerView", "Loading format ($attemptState): $activeUrl")
                val mediaItem = MediaItem.fromUri(Uri.parse(activeUrl))
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                playbackError = null
                isBuffering = true
            }
        } catch (e: Exception) {
            // Silently transition or wait
            if (attemptState < 2) {
                attemptState++
            } else {
                playbackError = "فشل تشغيل فيلم/مسلسل بجميع الصيغ"
            }
        }
    }

    // Watch buffer stuck timeout to safely and silently transition formats
    LaunchedEffect(attemptState, isBuffering) {
        if (isBuffering && attemptState < 2) {
            delay(1800L) // Under 1.8 seconds, if still buffering, swap format silently
            if (isBuffering) {
                Log.d("MoviePlayerView", "Stuck buffering. Silently moving to next format...")
                attemptState++
            }
        }
    }

    // Listen to player states and errors
    DisposableEffect(exoPlayer) {
        playerView.player = exoPlayer
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    try {
                        durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    } catch (e: Exception) {
                        Log.e("MoviePlayerView", "Error updating duration", e)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
                isPlaying = isPlayingChanged
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("MoviePlayerView", "Media3 error code ${error.errorCode}, message: ${error.message}")
                if (attemptState < 2) {
                    // Silently ignore error and swap to next format
                    attemptState++
                } else {
                    playbackError = "عذراً، هذا الملف غير متاح حالياً أو يتطلب كوداً مفعلاً."
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

    // Progress updates
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

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5000L)
            showControls = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("movie_video_player")
    ) {
        // Player Container Component
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
                        "fit" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
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

        // Gradients background
        AnimatedVisibility(
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
                                Color.Black.copy(alpha = 0.85f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            )
        }

        // Buffer Indicator Spinner (Only shows loading spinner, no text as requested)
        if (isBuffering && playbackError == null) {
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
                        contentDescription = "خطأ",
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
                            attemptState = 0
                            playbackError = null
                            try {
                                if (!isPlayerReleased) {
                                    exoPlayer.prepare()
                                    exoPlayer.play()
                                }
                            } catch (e: Exception) {
                                Log.e("MoviePlayerView", "Error on retry", e)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("إعادة المحاولة", color = Color.White)
                    }
                }
            }
        }

        // Header controls (Includes the Video Path indicator!)
        AnimatedVisibility(
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
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "رجوع",
                        tint = Color.White
                    )
                }

                // Video Pathway Display
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "المكتبة",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = ">",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (streamUrl.contains("/movie/")) "الأفلام" else "المسلسلات",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = ">",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Bottom Controls HUD (Backward & Forward 10s specific to this Player)
        AnimatedVisibility(
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Aspect ratio controller
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                playerAspectRatioMode = when (playerAspectRatioMode) {
                                    "fit" -> "16_9"
                                    "16_9" -> "fill"
                                    else -> "fit"
                                }
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = when (playerAspectRatioMode) {
                                    "fit" -> Icons.Default.AspectRatio
                                    "16_9" -> Icons.Default.Tv
                                    else -> Icons.Default.Fullscreen
                                },
                                contentDescription = "أبعاد الفيديو",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (playerAspectRatioMode) {
                                "fit" -> "ملء شاشة"
                                "16_9" -> "نسبة 16:9"
                                else -> "تعبئة بالكامل"
                            },
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(32.dp))

                    // Rewind 10 Seconds Button (Required)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                try {
                                    if (!isPlayerReleased) {
                                        val currentPos = exoPlayer.currentPosition
                                        exoPlayer.seekTo((currentPos - 10000).coerceAtLeast(0L))
                                    }
                                } catch (e: Exception) {
                                    Log.e("MoviePlayerView", "Error seeking backward", e)
                                }
                            },
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                                .size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "تراجع 10 ثوان",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "تراجع 10ث",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(28.dp))

                    // Play/Pause Button
                    IconButton(
                        onClick = {
                            try {
                                if (!isPlayerReleased) {
                                    if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                                }
                            } catch (e: Exception) {
                                Log.e("MoviePlayerView", "Error playing", e)
                            }
                        },
                        modifier = Modifier
                            .size(60.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "تشغيل/إيقاف",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(28.dp))

                    // Forward 10 Seconds Button (Required)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                try {
                                    if (!isPlayerReleased) {
                                        val currentPos = exoPlayer.currentPosition
                                        exoPlayer.seekTo((currentPos + 10000).coerceAtMost(durationMs))
                                    }
                                } catch (e: Exception) {
                                    Log.e("MoviePlayerView", "Error seeking forward", e)
                                }
                            },
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                                .size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "تقديم 10 ثوان",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "تقديم 10ث",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(32.dp))

                    // Mute/Audio toggle
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
                                Log.e("MoviePlayerView", "Error volume change", e)
                            }
                        },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = "كتم الصوت",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Progress Bar tracker (Continuous)
                if (durationMs > 0) {
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
                                    Log.e("MoviePlayerView", "Error slider change", e)
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
}

// Format digital clock
private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
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

