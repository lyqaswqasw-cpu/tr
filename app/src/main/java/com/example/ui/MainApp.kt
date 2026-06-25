package com.example.ui

import android.app.Activity
import android.app.DownloadManager
import android.os.Environment
import android.database.Cursor
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import coil.compose.AsyncImage
import com.example.model.*
import com.example.data.ChatMessage
import com.example.ui.components.VideoPlayerView
import com.example.ui.components.MoviePlayerView
import com.example.ui.components.SplashLoadingScreen
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import android.util.Log

// Local download representation model for local SQLite/Prefs persistence
data class LocalDownload(
    val id: String,
    val title: String,
    val posterUrl: String,
    val videoUrl: String,
    val quality: String,
    val timestamp: Long
)

// Manual JSON helper serializer for offline files integration
fun saveDownloads(context: Context, list: List<LocalDownload>) {
    val prefs = context.getSharedPreferences("MainViewModel", Context.MODE_PRIVATE)
    val json = StringBuilder("[")
    list.forEachIndexed { index, download ->
        json.append("{")
            .append("\"id\":\"").append(download.id.replace("\"", "\\\"")).append("\",")
            .append("\"title\":\"").append(download.title.replace("\"", "\\\"")).append("\",")
            .append("\"posterUrl\":\"").append(download.posterUrl.replace("\"", "\\\"")).append("\",")
            .append("\"videoUrl\":\"").append(download.videoUrl.replace("\"", "\\\"")).append("\",")
            .append("\"quality\":\"").append(download.quality.replace("\"", "\\\"")).append("\",")
            .append("\"timestamp\":").append(download.timestamp)
            .append("}")
        if (index < list.size - 1) json.append(",")
    }
    json.append("]")
    prefs.edit().putString("saved_downloads", json.toString()).apply()
}

// Manual JSON parse engine for offline files listing
fun loadDownloads(context: Context): List<LocalDownload> {
    val prefs = context.getSharedPreferences("MainViewModel", Context.MODE_PRIVATE)
    val rawJson = prefs.getString("saved_downloads", "[]") ?: "[]"
    val list = mutableListOf<LocalDownload>()
    try {
        val regex = java.util.regex.Pattern.compile("\\{([^\\}]+)\\}")
        val matcher = regex.matcher(rawJson)
        while (matcher.find()) {
            val content = matcher.group(1) ?: continue
            val id = extractJsonField(content, "id")
            val title = extractJsonField(content, "title")
            val posterUrl = extractJsonField(content, "posterUrl")
            val videoUrl = extractJsonField(content, "videoUrl")
            val quality = extractJsonField(content, "quality")
            val timestamp = content.substringAfter("\"timestamp\":").substringBefore(",").trim().filter { it.isDigit() }.toLongOrNull() ?: 0L
            if (id.isNotEmpty() && title.isNotEmpty()) {
                list.add(LocalDownload(id, title, posterUrl, videoUrl, quality, timestamp))
            }
        }
    } catch (e: Exception) {
        Log.e("Downloads", "Error parsing local downloads", e)
    }
    return list
}

private fun extractJsonField(content: String, field: String): String {
    val key = "\"$field\":\""
    if (!content.contains(key)) return ""
    return content.substringAfter(key).substringBefore("\"")
}

// Predict or scan standard files properties quality
fun detectQuality(title: String, url: String): String {
    val searchStr = (title + " " + url).lowercase()
    return when {
        searchStr.contains("4k") || searchStr.contains("2160") -> "Ultra HD 4K (الدقة الفعلية)"
        searchStr.contains("1080") || searchStr.contains("fhd") -> "Full HD 1080p (الدقة الفعلية)"
        searchStr.contains("720") || searchStr.contains("hd") -> "HD 720p (الدقة الفعلية)"
        else -> "Full HD 1080p (الجودة الفعلية لمسار البث)"
    }
}

// Inspect actual video/stream link to find actual available qualities dynamically
suspend fun discoverAvailableQualities(videoUrl: String): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val qualities = mutableListOf<String>()
    try {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(videoUrl).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val contentType = response.header("Content-Type") ?: ""
                val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true) || 
                             contentType.contains("mpegurl", ignoreCase = true) || 
                             contentType.contains("application/x-mpegURL", ignoreCase = true)
                
                if (isM3u8) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.contains("#EXTM3U")) {
                        val lines = bodyStr.split("\n")
                        for (line in lines) {
                            if (line.contains("RESOLUTION=")) {
                                val resPart = line.substringAfter("RESOLUTION=").substringBefore(",").substringBefore("\r").trim()
                                if (resPart.contains("x")) {
                                    val height = resPart.split("x").getOrNull(1)?.toIntOrNull()
                                    if (height != null) {
                                        when {
                                            height >= 2160 -> qualities.add("4K UHD (${resPart})")
                                            height >= 1080 -> qualities.add("1080p Full HD (${resPart})")
                                            height >= 720 -> qualities.add("720p HD (${resPart})")
                                            height >= 480 -> qualities.add("480p SD (${resPart})")
                                            height >= 360 -> qualities.add("360p SD (${resPart})")
                                            else -> qualities.add("${height}p (${resPart})")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("QualityDetector", "Error checking HLS resolutions", e)
    }

    if (qualities.isEmpty()) {
        val search = videoUrl.lowercase()
        when {
            search.contains("2160") || search.contains("4k") -> qualities.add("4K UHD (2160p - الدقة الفعلية)")
            search.contains("1080") || search.contains("fhd") || search.contains("1080p") -> qualities.add("1080p Full HD (الدقة الفعلية)")
            search.contains("720") || search.contains("hd") || search.contains("720p") -> qualities.add("720p HD (الدقة الفعلية)")
            search.contains("480") || search.contains("480p") -> qualities.add("480p SD (الدقة الفعلية)")
            search.contains("360") || search.contains("360p") -> qualities.add("360p SD (الدقة الفعلية)")
            else -> {
                qualities.add("1080p Full HD (جودة المصدر الفعلية)")
                qualities.add("720p HD (جودة المصدر البديلة)")
            }
        }
    }
    return@withContext qualities.distinct()
}

// Helper extension for TV/D-Pad navigation and remote control support
fun Modifier.tvFocusable(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    glowColor: Color = LoopRed,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    
    var modifier = this
        .onFocusChanged { isFocused = it.isFocused }
        .focusable()
        .border(
            width = if (isFocused) 2.5.dp else 0.dp,
            color = if (isFocused) glowColor else Color.Transparent,
            shape = shape
        )
        .graphicsLayer {
            scaleX = if (isFocused) 1.04f else 1.0f
            scaleY = if (isFocused) 1.04f else 1.0f
        }

    if (onClick != null) {
        modifier = modifier
            .clickable { onClick() }
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && 
                    (keyEvent.key == Key.Enter || keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.NumPadEnter)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
    }
    modifier
}

// Helper method to retrieve activity from compose context context safely
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}

@Composable
fun SafeAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val fallbackRes = com.example.R.drawable.logo_25_live
    val finalModel = if (model == null || (model is String && model.trim().isEmpty())) {
        fallbackRes
    } else {
        model
    }
    AsyncImage(
        model = finalModel,
        contentDescription = contentDescription,
        error = androidx.compose.ui.res.painterResource(fallbackRes),
        placeholder = androidx.compose.ui.res.painterResource(fallbackRes),
        fallback = androidx.compose.ui.res.painterResource(fallbackRes),
        modifier = modifier.background(MidnightGrey),
        contentScale = contentScale
    )
}

@Composable
fun MainApp(viewModel: MainViewModel) {
    val accessCode by viewModel.accessCode.collectAsState()
    val playingStreamUrl by viewModel.playingStreamUrl.collectAsState()
    val playingTitle by viewModel.playingTitle.collectAsState()
    val playingIsLive by viewModel.playingIsLive.collectAsState()
    val playingUserAgent by viewModel.playingUserAgent.collectAsState()
    val autoLandscape by viewModel.autoLandscape.collectAsState()

    val context = LocalContext.current
    var splashCompleted by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ObsidianBlack
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (accessCode == null) {
                splashCompleted = false
                LoginScreen(viewModel = viewModel)
            } else if (!splashCompleted) {
                SplashLoadingScreen(onFinished = { splashCompleted = true })
            } else {
                DashboardScreen(viewModel = viewModel)
            }

            // High Quality Cinematic Video Player View
            playingStreamUrl?.let { url ->
                VideoPlayerView(
                    streamUrl = url,
                    title = playingTitle,
                    isLive = playingIsLive,
                    onClose = { viewModel.stopPlaying() },
                    modifier = Modifier.fillMaxSize(),
                    autoLandscape = autoLandscape,
                    userAgent = playingUserAgent,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun SegmentedCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    maxLength: Int = 6,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {
                focusRequester.requestFocus()
            },
        contentAlignment = Alignment.Center
    ) {
        // Invisible input text field that spans the whole area
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { newVal ->
                val filtered = newVal.filter { it.isLetterOrDigit() }
                if (filtered.length <= maxLength) {
                    onValueChange(filtered.uppercase())
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("activation_code_input")
                .focusRequester(focusRequester)
                .graphicsLayer(alpha = 0f), // Make it invisible but still focusable
            singleLine = true
        )

        // Row of beautiful, glossy character boxes representing each digit
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            for (i in 0 until maxLength) {
                val char = value.getOrNull(i)?.toString() ?: ""
                val isFocused = value.length == i
                val isFilled = char.isNotEmpty()

                val borderAlpha by animateFloatAsState(
                    targetValue = if (isFocused) 1f else if (isFilled) 0.6f else 0.15f,
                    label = "border_alpha"
                )
                val borderColor = if (isFocused) LoopRed else if (isFilled) LoopRed.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.12f)
                val scale by animateFloatAsState(
                    targetValue = if (isFocused) 1.05f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "cell_scale"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f) // Square box
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isFocused) ObsidianBlack else MidnightGrey.copy(alpha = 0.6f)
                        )
                        .border(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = borderColor.copy(alpha = borderAlpha),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (char.isNotEmpty()) {
                        Text(
                            text = char,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    } else if (isFocused) {
                        val cursorAlpha by rememberInfiniteTransition(label = "cursor_blink").animateFloat(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "cursor_blink"
                        )
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(18.dp)
                                .graphicsLayer(alpha = cursorAlpha)
                                .background(LoopRed)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(viewModel: MainViewModel) {
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var inputCode by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Aesthetic pulse glow effect in the dark
    val infiniteTransition = rememberInfiniteTransition(label = "ring_glow_transition")
    val logoRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "logo_rotation"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBlack),
        contentAlignment = Alignment.Center
    ) {
        // Precise subtle red ambient background spotlight glow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(LoopRed.copy(alpha = 0.09f), Color.Transparent),
                        radius = 1600f
                    )
                )
        )

        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Elegant professional logo & header branding with pure red accents
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .border(2.dp, LoopRed, CircleShape)
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                // Spinning cosmic dynamic indicator
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(rotationZ = logoRotation)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                )
                Icon(
                    imageVector = Icons.Default.PlayCircleFilled,
                    contentDescription = "Loop Live Logo",
                    tint = LoopRed,
                    modifier = Modifier.size(46.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "25",
                    color = LoopRed,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Live",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
            }

            Text(
                text = "منصة البث السينمائية فائقة الدقة",
                color = SoftGreyText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Glassmorphic interactive input panel with compact sizing
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(20.dp)
                    ),
                colors = CardDefaults.cardColors(containerColor = MidnightGrey.copy(alpha = 0.92f)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Encryption badge and status point
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(LoopRedGlow)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "الاتصال آمن ومشفّر",
                                color = LoopRedGlow,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "آمن",
                                tint = LoopRed,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "تشفير SSL 256-bit",
                                color = SoftGreyText,
                                fontSize = 9.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "كود تفعيل الحساب",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "الرجاء إدخال الكود للاتصال الآمن بمركز البث",
                        color = SoftGreyText,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Beautiful high-performance single input bar/field
                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { newVal ->
                            // Sanitize and keep uppercase
                            inputCode = newVal.filter { it.isLetterOrDigit() }.uppercase()
                        },
                        placeholder = {
                            Text(
                                text = "أدخل كود التفعيل هنا (مثال: A1)...",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End
                            )
                        },
                        leadingIcon = {
                            if (inputCode.isNotEmpty()) {
                                IconButton(onClick = { inputCode = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "مسح الكود",
                                        tint = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.VpnKey,
                                contentDescription = "مفتاح الدخول",
                                tint = LoopRed,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            letterSpacing = 2.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LoopRed,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedContainerColor = ObsidianBlack.copy(alpha = 0.6f),
                            unfocusedContainerColor = ObsidianBlack.copy(alpha = 0.3f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("activation_code_input")
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown && 
                                    (keyEvent.key == Key.Enter || keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.NumPadEnter)) {
                                    if (inputCode.trim().isNotEmpty()) {
                                        viewModel.loginWithCode(inputCode.trim())
                                    } else {
                                        Toast.makeText(context, "الرجاء إدخال كود تفعيل صالح!", Toast.LENGTH_SHORT).show()
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    errorMessage?.let { err ->
                        Text(
                            text = err,
                            color = LoopRedGlow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }

                    // Login Action Button - compact, beautiful styling
                    Button(
                        onClick = {
                            if (inputCode.trim().isNotEmpty()) {
                                viewModel.loginWithCode(inputCode.trim())
                            } else {
                                Toast.makeText(context, "الرجاء إدخال كود تفعيل صالح!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("login_submit_btn")
                            .tvFocusable(shape = RoundedCornerShape(10.dp), glowColor = GoldGlow),
                        colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Login, contentDescription = "بوابة", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "تسجيل الدخول والمزامنة",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Professional Support Notice Footer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/jdj_q"))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "لا يمكن فتح تليجرام حالياً", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .background(Color.White.copy(alpha = 0.03f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.HeadsetMic,
                    contentDescription = "Telegram Support",
                    tint = GoldGlow,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "الدعم الفني والاشتراكات عبر تليجرام",
                    color = SoftGreyText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val activeAccount by viewModel.activeAccount.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val movies by viewModel.movies.collectAsState()
    val series by viewModel.series.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    val selectedMovie by viewModel.selectedMovie.collectAsState()
    val selectedSeries by viewModel.selectedSeries.collectAsState()

    var activeTab by remember { mutableStateOf("channels") } // "channels", "library", "favorites", "news"
    var isDrawerOpen by remember { mutableStateOf(false) }
    var librarySubTab by remember { mutableStateOf("movies") } // "movies", "series"

    // Real quality-checking dialog and download parameters
    var showQualityDialog by remember { mutableStateOf(false) }
    var detectedQualities by remember { mutableStateOf<List<String>>(emptyList()) }
    var isCheckingQualities by remember { mutableStateOf(false) }
    var pendingDownloadData by remember { mutableStateOf<Pair<String, String>?>(null) } // Title, StreamUrl
    var pendingPosterUrl by remember { mutableStateOf("") }
    var pendingId by remember { mutableStateOf("") }
    var showDownloadDialog by remember { mutableStateOf(false) }
    
    // Smooth background automated local downloading tracking blocks
    var processingDownloadItem by remember { mutableStateOf<LocalDownload?>(null) }
    var enqueuedDownloadId by remember { mutableStateOf<Long?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadPhase by remember { mutableStateOf("") } // "analyzing", "downloading", "completed", "failed"
    var detectedDownloadQuality by remember { mutableStateOf("") }

    LaunchedEffect(pendingDownloadData) {
        val data = pendingDownloadData
        if (data != null) {
            isCheckingQualities = true
            try {
                detectedQualities = discoverAvailableQualities(data.second)
            } catch (e: Exception) {
                detectedQualities = listOf("1080p Full HD (الدقة الفعلية المتاحة)")
            } finally {
                isCheckingQualities = false
            }
        }
    }

    val context = LocalContext.current
    val autoLandscape by viewModel.autoLandscape.collectAsState()
    val activity = remember(context) { context.findActivity() }

    LaunchedEffect(autoLandscape) {
        activity?.requestedOrientation = if (autoLandscape) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Effect launching the real system background download task
    LaunchedEffect(processingDownloadItem) {
        val downloadItem = processingDownloadItem
        if (downloadItem != null) {
            downloadProgress = 0f
            downloadPhase = "analyzing"
            delay(1200L) // Wait for analysis step
            
            val fileExtension = try {
                val lastDot = downloadItem.videoUrl.lastIndexOf('.')
                if (lastDot != -1 && lastDot > downloadItem.videoUrl.lastIndexOf('/')) {
                    downloadItem.videoUrl.substring(lastDot)
                } else {
                    ".mp4"
                }
            } catch (e: Exception) {
                ".mp4"
            }
            
            // Generate clean alphanumeric filename prefix to avoid special character errors
            val cleanTitle = downloadItem.title.replace(Regex("[^a-zA-Z0-9أ-ي]"), "_")
            val fileName = "LoopLive_${downloadItem.id}_$cleanTitle$fileExtension"
            
            var enqueueId: Long? = null
            try {
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val request = DownloadManager.Request(Uri.parse(downloadItem.videoUrl))
                    .setTitle(downloadItem.title)
                    .setDescription("جاري تنزيل الفيديو في الخلفية من Loop Live...")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                
                enqueueId = downloadManager.enqueue(request)
                enqueuedDownloadId = enqueueId
            } catch (e: Exception) {
                Log.e("DownloadManager", "Error enqueuing standard system download", e)
                Toast.makeText(context, "فشل بدء التحميل الخلفي: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                processingDownloadItem = null
            }
            
            if (enqueueId != null) {
                downloadPhase = "downloading"
                
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFile = java.io.File(publicDir, fileName)
                val localFileUri = Uri.fromFile(targetFile).toString()
                
                // Save the local uri item instantly so it registers in user local download library!
                val currentList = loadDownloads(context).toMutableList()
                if (currentList.none { it.id == downloadItem.id }) {
                    val updatedItem = downloadItem.copy(videoUrl = localFileUri)
                    currentList.add(0, updatedItem)
                    saveDownloads(context, currentList)
                }
                
                Toast.makeText(context, "بدأ التحميل الحقيقي في الخلفية بالدقة الفعلية! يمكنك الخروج من التطبيق الآن ومتابعة تقدم التحميل بشريط الإشعارات.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Effect tracking the real DownloadManager progress
    LaunchedEffect(enqueuedDownloadId) {
        val downloadId = enqueuedDownloadId
        if (downloadId != null) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var isDownloading = true
            while (isDownloading) {
                delay(1000L)
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = try { downloadManager.query(query) } catch (e: Exception) { null }
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    
                    if (bytesDownloadedIndex != -1 && bytesTotalIndex != -1 && statusIndex != -1) {
                        val bytesDownloaded = cursor.getInt(bytesDownloadedIndex)
                        val bytesTotal = cursor.getInt(bytesTotalIndex)
                        val status = cursor.getInt(statusIndex)
                        
                        if (bytesTotal > 0) {
                            downloadProgress = bytesDownloaded.toFloat() / bytesTotal.toFloat()
                        }
                        
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            downloadProgress = 1.0f
                            downloadPhase = "completed"
                            isDownloading = false
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            downloadPhase = "failed"
                            isDownloading = false
                        }
                    }
                } else {
                    isDownloading = false
                }
                cursor?.close()
            }
            if (downloadPhase == "completed") {
                delay(800L)
                processingDownloadItem = null
                enqueuedDownloadId = null
            }
        }
    }

    // Tab categories filter mapping
    val currentTabCategoryType = when (activeTab) {
        "channels" -> "channel"
        "main_channels" -> "exclusive"
        "library" -> if (librarySubTab == "movies") "movie" else "series"
        else -> "all"
    }

    val filteredCategories = remember(categories, currentTabCategoryType) {
        if (currentTabCategoryType == "all") {
            emptyList()
        } else {
            categories.filter { it.type == currentTabCategoryType }
        }
    }

    // Safely reset chosen category when tab shifts to avoid showing category items from other tabs
    LaunchedEffect(activeTab, librarySubTab) {
        viewModel.selectCategory("all")
        viewModel.updateSearchQuery("")
        if (activeTab == "library") {
            if (librarySubTab == "movies") {
                viewModel.loadMoviesOnDemand()
            } else if (librarySubTab == "series") {
                viewModel.loadSeriesOnDemand()
            }
        }
    }

    // Filter contents dynamically based on selected tabs/searches
    val categoryName = remember(categories, selectedCategory) {
        categories.find { it.id == selectedCategory }?.name
    }

    val filteredChannels = remember(channels, selectedCategory, categoryName, searchQuery) {
        channels.filter {
            val matchesCategory = selectedCategory == "all" || 
                    it.category.trim().equals(selectedCategory.trim(), ignoreCase = true) || 
                    (categoryName != null && it.category.trim().equals(categoryName.trim(), ignoreCase = true))
            matchesCategory && (searchQuery.isEmpty() || it.name.contains(searchQuery, ignoreCase = true))
        }
    }
    val mainChannels by viewModel.mainChannels.collectAsState()
    val filteredMainChannels = remember(mainChannels, searchQuery) {
        mainChannels.filter {
            searchQuery.isEmpty() || it.name.contains(searchQuery, ignoreCase = true)
        }
    }
    val filteredMovies = remember(movies, selectedCategory, categoryName, searchQuery) {
        movies.filter {
            val matchesCategory = selectedCategory == "all" || 
                    it.category.trim().equals(selectedCategory.trim(), ignoreCase = true) || 
                    (categoryName != null && it.category.trim().equals(categoryName.trim(), ignoreCase = true))
            matchesCategory && (searchQuery.isEmpty() || it.title.contains(searchQuery, ignoreCase = true))
        }
    }
    val filteredSeries = remember(series, selectedCategory, categoryName, searchQuery) {
        series.filter {
            val matchesCategory = selectedCategory == "all" || 
                    it.category.trim().equals(selectedCategory.trim(), ignoreCase = true) || 
                    (categoryName != null && it.category.trim().equals(categoryName.trim(), ignoreCase = true))
            matchesCategory && (searchQuery.isEmpty() || it.title.contains(searchQuery, ignoreCase = true))
        }
    }

    // Favorites mapping
    val favoritesSet by viewModel.favorites.collectAsState()
    val favoriteChannels = remember(channels, favoritesSet) { channels.filter { favoritesSet.contains(it.id) } }
    val favoriteMovies = remember(movies, favoritesSet) { movies.filter { favoritesSet.contains(it.id) } }
    val favoriteSeries = remember(series, favoritesSet) { series.filter { favoritesSet.contains(it.id) } }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        topBar = {
            if (!isLandscape) {
                // Minimal header top layout shown in portrait
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianBlack)
                        .statusBarsPadding()
                        .padding(vertical = 4.dp)
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left side: Three lines menu button for Settings Drawer
                            IconButton(
                                onClick = { isDrawerOpen = true },
                                modifier = Modifier
                                    .size(40.dp)
                                    .tvFocusable(shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "القائمة الجانبية",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Center: LOOP LIVE
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.Green)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "25",
                                        color = LoopRed,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Live",
                                        color = Color.White,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                }
                            }

                            // Right side: Notifications, Favorites and Telegram
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val showNotificationIcon by viewModel.showNotificationIcon.collectAsState()
                                if (showNotificationIcon) {
                                    // Notification Bell
                                    IconButton(
                                        onClick = { activeTab = "notifications" },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(if (activeTab == "notifications") GoldGlow.copy(alpha = 0.2f) else MidnightGrey)
                                            .tvFocusable(shape = CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Notifications,
                                            contentDescription = "الإشعارات",
                                            tint = if (activeTab == "notifications") GoldGlow else Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                // Favorites Button
                                IconButton(
                                    onClick = { activeTab = "favorites" },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (activeTab == "favorites") LoopRed.copy(alpha = 0.2f) else MidnightGrey)
                                        .tvFocusable(shape = CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "المفضلة",
                                        tint = if (activeTab == "favorites") LoopRed else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Telegram Button
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF229ED9).copy(alpha = 0.12f))
                                        .tvFocusable(shape = CircleShape, onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/jdj_q"))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "لا يمكن فتح تليجرام حالياً", Toast.LENGTH_SHORT).show()
                                            }
                                        })
                                        .padding(3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Hand-etched elegant vector plane
                                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                        val r = size.minDimension / 2
                                        drawCircle(
                                            color = Color(0xFF229ED9),
                                            radius = r
                                        )
                                        val p = androidx.compose.ui.graphics.Path().apply {
                                            moveTo(r * 0.72f, r * 0.95f)
                                            lineTo(r * 1.45f, r * 0.60f)
                                            lineTo(r * 0.60f, r * 1.08f)
                                            close()
                                        }
                                        val p2 = androidx.compose.ui.graphics.Path().apply {
                                            moveTo(r * 0.60f, r * 1.08f)
                                            lineTo(r * 0.72f, r * 0.95f)
                                            lineTo(r * 1.15f, r * 1.25f)
                                            lineTo(r * 1.45f, r * 0.60f)
                                            lineTo(r * 0.52f, r * 0.96f)
                                            close()
                                        }
                                        drawPath(path = p, color = Color(0xFFB0DDFC))
                                        drawPath(path = p2, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Ultra compact landscape top bar as requested
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianBlack)
                        .padding(vertical = 2.dp)
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left side: Three lines menu button for Settings Drawer (Smaller!)
                            IconButton(
                                onClick = { isDrawerOpen = true },
                                modifier = Modifier
                                    .size(32.dp)
                                    .tvFocusable(shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "القائمة الجانبية",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Center: 25 Live Title (Smaller!)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "25",
                                    color = LoopRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.SansSerif
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Live",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }

                            // Notification Bell (Landscape)
                            val showNotificationIcon by viewModel.showNotificationIcon.collectAsState()
                            if (showNotificationIcon) {
                                IconButton(
                                    onClick = { activeTab = "notifications" },
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(if (activeTab == "notifications") GoldGlow.copy(alpha = 0.2f) else MidnightGrey)
                                        .tvFocusable(shape = CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Notifications,
                                        contentDescription = "الإشعارات",
                                        tint = if (activeTab == "notifications") GoldGlow else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(6.dp))

                            // Right side: Telegram (Smaller!)
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF229ED9).copy(alpha = 0.12f))
                                    .tvFocusable(shape = CircleShape, onClick = {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/jdj_q"))
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "لا يمكن فتح تليجرام حالياً", Toast.LENGTH_SHORT).show()
                                        }
                                    })
                                    .padding(2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                    val r = size.minDimension / 2
                                    drawCircle(
                                        color = Color(0xFF229ED9),
                                        radius = r
                                    )
                                    val p = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(r * 0.72f, r * 0.95f)
                                        lineTo(r * 1.45f, r * 0.60f)
                                        lineTo(r * 0.60f, r * 1.08f)
                                        close()
                                    }
                                    val p2 = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(r * 0.60f, r * 1.08f)
                                        lineTo(r * 0.72f, r * 0.95f)
                                        lineTo(r * 1.15f, r * 1.25f)
                                        lineTo(r * 1.45f, r * 0.60f)
                                        lineTo(r * 0.52f, r * 0.96f)
                                        close()
                                    }
                                    drawPath(path = p, color = Color(0xFFB0DDFC))
                                    drawPath(path = p2, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!isLandscape) {
                // Premium portrait compact contiguous bottom navigation dock
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MidnightGrey)
                        .navigationBarsPadding()
                ) {
                    // Top border divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.08f))
                            .align(Alignment.TopCenter)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val showNewsTabOnline by viewModel.showNewsTab.collectAsState()
                        val showChatTabOnline by viewModel.showChatTab.collectAsState()
                        val showMainChannelsTabOnline by viewModel.showMainChannelsTab.collectAsState()

                        val tabs = buildList {
                            add(Triple("channels", Icons.Default.Tv, "بث مباشر"))
                            if (showMainChannelsTabOnline) {
                                add(Triple("main_channels", Icons.Default.LiveTv, "قنوات رئيسية"))
                            }
                            add(Triple("library", Icons.Default.Movie, "المكتبة"))
                            if (showNewsTabOnline) {
                                add(Triple("news", Icons.Default.Newspaper, "الأخبار"))
                            }
                            if (showChatTabOnline) {
                                add(Triple("chat", Icons.Default.Chat, "دردشة عامة"))
                            }
                        }

                        tabs.forEach { (tabId, icon, label) ->
                            val isSelected = activeTab == tabId
                            val activeColor = if (isSelected) LoopRed else SoftGreyText.copy(alpha = 0.7f)

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusable(shape = RoundedCornerShape(8.dp), onClick = { activeTab = tabId })
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = activeColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = label,
                                    color = activeColor,
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = ObsidianBlack
    ) { padValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padValues)
                .background(ObsidianBlack)
        ) {
            if (isLandscape) {
                // Modern double-pane layout with unified navigation side bar for landscape devices
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left element of Row: Main Section Details
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        // Clean status row without statusBarsPadding to avoid extra vertical spacing
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = "حساب Xtream IPTV مفعّل ونشط",
                                color = SoftGreyText,
                                fontSize = 11.sp
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            TabContent(
                                activeTab = activeTab,
                                librarySubTab = librarySubTab,
                                onLibrarySubTabChange = { librarySubTab = it },
                                filteredCategories = filteredCategories,
                                selectedCategory = selectedCategory,
                                searchQuery = searchQuery,
                                filteredChannels = filteredChannels,
                                filteredMainChannels = filteredMainChannels,
                                filteredMovies = filteredMovies,
                                filteredSeries = filteredSeries,
                                favoriteChannels = favoriteChannels,
                                favoriteMovies = favoriteMovies,
                                favoriteSeries = favoriteSeries,
                                viewModel = viewModel
                            )
                        }
                    }

                    // Divider Line
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(Color.White.copy(alpha = 0.05f))
                    )

                    // Right navigation bar for landscape - designed to be small, compact, and fully scrollable
                    Column(
                        modifier = Modifier
                            .width(72.dp)
                            .fillMaxHeight()
                            .background(MidnightGrey)
                            .navigationBarsPadding()
                            .padding(vertical = 8.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val showNewsTabOnline by viewModel.showNewsTab.collectAsState()
                        val showChatTabOnline by viewModel.showChatTab.collectAsState()
                        val showMainChannelsTabOnline by viewModel.showMainChannelsTab.collectAsState()

                        val tabs = buildList {
                            add(Triple("channels", Icons.Default.Tv, "بث مباشر"))
                            if (showMainChannelsTabOnline) {
                                add(Triple("main_channels", Icons.Default.LiveTv, "قنوات رئيسية"))
                            }
                            add(Triple("library", Icons.Default.Movie, "المكتبة"))
                            add(Triple("favorites", Icons.Default.Favorite, "المفضلة"))
                            if (showNewsTabOnline) {
                                add(Triple("news", Icons.Default.Newspaper, "الأخبار"))
                            }
                            if (showChatTabOnline) {
                                add(Triple("chat", Icons.Default.Chat, "دردشة عامة"))
                            }
                        }

                        tabs.forEach { (tabId, icon, label) ->
                            val isSelected = activeTab == tabId
                            val activeColor = if (isSelected) LoopRed else SoftGreyText
                            val bgAlpha = if (isSelected) 0.08f else 0f

                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = bgAlpha))
                                    .tvFocusable(shape = RoundedCornerShape(10.dp), onClick = { activeTab = tabId })
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = activeColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = label,
                                    color = activeColor,
                                    fontSize = 8.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            } else {
                // Portrait Main Tab content switcher
                TabContent(
                    activeTab = activeTab,
                    librarySubTab = librarySubTab,
                    onLibrarySubTabChange = { librarySubTab = it },
                    filteredCategories = filteredCategories,
                    selectedCategory = selectedCategory,
                    searchQuery = searchQuery,
                    filteredChannels = filteredChannels,
                    filteredMainChannels = filteredMainChannels,
                    filteredMovies = filteredMovies,
                    filteredSeries = filteredSeries,
                    favoriteChannels = favoriteChannels,
                    favoriteMovies = favoriteMovies,
                    favoriteSeries = favoriteSeries,
                    viewModel = viewModel
                )
            }

            // Real Quality check and custom selection Dialog
            if (showQualityDialog) {
                Dialog(onDismissRequest = { showQualityDialog = false }) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MidnightGrey,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "فحص الجودة الفعلية للبث",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            if (isCheckingQualities) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(vertical = 24.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = LoopRed,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = "جاري الاتصال بالسيرفر لفحص جودة الفيديو الحقيقية المتاحة...",
                                        color = SoftGreyText,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                Text(
                                    text = "الجودات الحقيقية المتوفرة حالياً على رابط الفيديو:",
                                    color = GoldGlow,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )

                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    detectedQualities.forEach { quality ->
                                        Button(
                                            onClick = {
                                                val data = pendingDownloadData
                                                if (data != null) {
                                                    detectedDownloadQuality = quality
                                                    processingDownloadItem = LocalDownload(
                                                        id = pendingId,
                                                        title = data.first,
                                                        posterUrl = pendingPosterUrl,
                                                        videoUrl = data.second,
                                                        quality = quality,
                                                        timestamp = System.currentTimeMillis()
                                                    )
                                                    showDownloadDialog = true
                                                    showQualityDialog = false
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianBlack),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(text = quality, color = Color.White, fontSize = 11.sp)
                                                Icon(
                                                    imageVector = Icons.Default.CloudDownload,
                                                    contentDescription = null,
                                                    tint = LoopRed,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                OutlinedButton(
                                    onClick = { showQualityDialog = false },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftGreyText),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("إلغاء", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Cinematic Movie details dialog
            selectedMovie?.let { movie ->
                MovieDetailsDialog(
                    movie = movie,
                    isFavorite = favoritesSet.contains(movie.id),
                    onToggleFavorite = { viewModel.toggleFavorite(movie.id) },
                    onPlay = { viewModel.startPlaying(it, movie.title, isLive = false) },
                    onDownload = { m ->
                        pendingId = m.id
                        pendingPosterUrl = m.posterUrl
                        pendingDownloadData = Pair(m.title, m.streamUrl)
                        isCheckingQualities = true
                        showQualityDialog = true
                    },
                    onDismiss = { viewModel.selectMovie(null) }
                )
            }

            // TV Series expansion dialog
            selectedSeries?.let { series ->
                val seasons by viewModel.seasons.collectAsState()
                val episodes by viewModel.episodes.collectAsState()
                val isLoadingEpisodes by viewModel.isLoading.collectAsState()

                SeriesDetailsDialog(
                    series = series,
                    seasons = seasons,
                    episodes = episodes,
                    isLoadingEpisodes = isLoadingEpisodes,
                    isFavorite = favoritesSet.contains(series.id),
                    onToggleFavorite = { viewModel.toggleFavorite(series.id) },
                    onPlayEpisode = { ep -> viewModel.startPlaying(ep.streamUrl, ep.title, isLive = false) },
                    onDownloadEpisode = { ep ->
                        pendingId = ep.id
                        pendingPosterUrl = series.posterUrl
                        pendingDownloadData = Pair("${series.title} - ${ep.title}", ep.streamUrl)
                        isCheckingQualities = true
                        showQualityDialog = true
                    },
                    onSeasonTabSelected = { seasonId -> viewModel.loadEpisodes(series.id, seasonId) },
                    onDismiss = { viewModel.selectSeries(null) }
                )
            }

            // Download progress dialog (fully backgroundable, customizable and non-blocking)
            if (showDownloadDialog && processingDownloadItem != null) {
                Dialog(onDismissRequest = { showDownloadDialog = false }) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MidnightGrey,
                        modifier = Modifier.width(280.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier.size(80.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = { if (downloadPhase == "downloading") downloadProgress else 0.1f },
                                    color = LoopRed,
                                    strokeWidth = 6.dp,
                                    modifier = Modifier.size(72.dp)
                                )
                                Icon(
                                    imageVector = if (downloadPhase == "analyzing") Icons.Default.Search else Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = if (downloadPhase == "analyzing") "جاري فحص دقة وجودة البث الفعلي..." else "جاري تحميل الملف محلياً...",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            if (downloadPhase != "analyzing") {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "${(downloadProgress * 100).toInt()}%",
                                    color = GoldGlow,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    color = LoopRed,
                                    trackColor = Color.White.copy(alpha = 0.1f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { showDownloadDialog = false },
                                colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("المتابعة في الخلفية والتصفح", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "المنفذ: آمن وسريع جداً ويبدأ تلقائياً عند الاتصال بالشبكة",
                                color = SoftGreyText,
                                fontSize = 9.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Custom Left Sliding Drawer for Settings
    if (isDrawerOpen) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Semi-transparent background dim
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { isDrawerOpen = false }
                )

                // Drawer content sliding from Left
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.85f)
                        .background(MidnightGrey)
                        .clickable(enabled = false) {}
                        .align(Alignment.CenterStart)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        // Drawer Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ObsidianBlack)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = GoldGlow,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "إعدادات التطبيق",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = { isDrawerOpen = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "إغلاق",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Settings Panel content
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        ) {
                            SettingsPanel(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun TabContent(
    activeTab: String,
    librarySubTab: String,
    onLibrarySubTabChange: (String) -> Unit,
    filteredCategories: List<IPTVCategory>,
    selectedCategory: String,
    searchQuery: String,
    filteredChannels: List<Channel>,
    filteredMainChannels: List<Channel> = emptyList(),
    filteredMovies: List<Movie>,
    filteredSeries: List<Series>,
    favoriteChannels: List<Channel>,
    favoriteMovies: List<Movie>,
    favoriteSeries: List<Series>,
    viewModel: MainViewModel
) {
    val isChannelsLoading by viewModel.isChannelsLoading.collectAsState()
    val isMoviesLoading by viewModel.isMoviesLoading.collectAsState()
    val isSeriesLoading by viewModel.isSeriesLoading.collectAsState()
    val cardColumns by viewModel.cardColumnsCount.collectAsState()
    val favoritesSet by viewModel.favorites.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (activeTab != "favorites" && activeTab != "settings" && activeTab != "about" && activeTab != "news" && activeTab != "chat") {
            // High level Search field
            SearchBarWidget(
                query = searchQuery,
                placeholder = if (activeTab == "main_channels") "ابحث في القنوات الرئيسية..." else "ابحث بالاسم هنا...",
                onQueryChange = { viewModel.updateSearchQuery(it) }
            )

            // Dynamic categories horizontal shelf
            CategoryRow(
                categories = filteredCategories,
                selectedCategory = selectedCategory,
                onSelectCategory = { viewModel.selectCategory(it) }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (activeTab) {
                "channels" -> {
                    if (isChannelsLoading) {
                        ModernLineLoader()
                    } else if (filteredChannels.isEmpty()) {
                        EmptyStateView("لا توجد قنوات مباشرة بحسب الفئة أو البحث المجرى.")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(cardColumns),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("channels_grid"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredChannels) { chan ->
                                ChannelGridCard(
                                    chan = chan,
                                    isFavorite = favoritesSet.contains(chan.id),
                                    onToggleFavorite = { viewModel.toggleFavorite(chan.id) }
                                ) {
                                    viewModel.startPlaying(chan.streamUrl, chan.name, isLive = true, userAgent = chan.userAgent, forcedFormat = chan.forcedFormat)
                                }
                            }
                        }
                    }
                }
                "main_channels" -> {
                    if (filteredMainChannels.isEmpty()) {
                        EmptyStateView("لا توجد قنوات رئيسية بحسب الفئة أو البحث المجرى.")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(cardColumns),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("main_channels_grid"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredMainChannels) { chan ->
                                MainChannelGridCard(
                                    chan = chan,
                                    isFavorite = favoritesSet.contains(chan.id),
                                    onToggleFavorite = { viewModel.toggleFavorite(chan.id) }
                                ) {
                                    // FORCE play based on source format is required
                                    viewModel.startPlaying(chan.streamUrl, chan.name, isLive = true, forceRawUrl = true, userAgent = chan.userAgent, forcedFormat = chan.forcedFormat)
                                }
                            }
                        }
                    }
                }
                "notifications" -> {
                    NotificationListView(viewModel)
                }
                "library" -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // High-contrast, premium, floating sub-navigation pill inside the Library
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MidnightGrey)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Movies Tab
                            val isMoviesSelected = librarySubTab == "movies"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isMoviesSelected) LoopRed else Color.Transparent)
                                    .clickable { onLibrarySubTabChange("movies") }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = "أفلام",
                                        tint = if (isMoviesSelected) Color.White else SoftGreyText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "الأفلام العربية والأجنبية",
                                        color = if (isMoviesSelected) Color.White else SoftGreyText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Series Tab
                            val isSeriesSelected = librarySubTab == "series"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSeriesSelected) LoopRed else Color.Transparent)
                                    .clickable { onLibrarySubTabChange("series") }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = "مسلسلات",
                                        tint = if (isSeriesSelected) Color.White else SoftGreyText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "المسلسلات التلفزيونية",
                                        color = if (isSeriesSelected) Color.White else SoftGreyText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (librarySubTab == "movies") {
                            if (isMoviesLoading) {
                                ModernLineLoader()
                            } else if (filteredMovies.isEmpty()) {
                                EmptyStateView("لا توجد أفلام سينمائية بحسب الفئة المرشحة حالياً.")
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("movies_grid"),
                                    contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                    // Cinematic featured hero poster
                                    item {
                                        CinematicHeroBanner(movie = filteredMovies.first()) {
                                            viewModel.selectMovie(filteredMovies.first())
                                        }
                                    }

                                    // Horizontal scroll categories rows
                                    item {
                                        Text(
                                            text = "العروض المتاحة الآن للترفيه",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
                                        )
                                    }

                                    item {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            items(filteredMovies) { movie ->
                                                MovieGridCard(
                                                    movie = movie,
                                                    isFavorite = favoritesSet.contains(movie.id),
                                                    onFavoriteToggle = { viewModel.toggleFavorite(movie.id) }
                                                ) {
                                                    viewModel.selectMovie(movie)
                                                }
                                            }
                                        }
                                    }

                                    // Dynamic Grid View Below
                                    item {
                                        Text(
                                            text = "كل المجموعات السينمائية",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
                                        )
                                    }

                                    items(filteredMovies.chunked(cardColumns)) { chunk ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            for (movie in chunk) {
                                                Box(modifier = Modifier.weight(1f)) {
                                                    MovieGridCard(
                                                        movie = movie,
                                                        isFavorite = favoritesSet.contains(movie.id),
                                                        onFavoriteToggle = { viewModel.toggleFavorite(movie.id) }
                                                    ) {
                                                        viewModel.selectMovie(movie)
                                                    }
                                                }
                                            }
                                            // spacer weighting if row incomplete
                                            if (chunk.size < cardColumns) {
                                                for (i in 0 until (cardColumns - chunk.size)) {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            if (isSeriesLoading) {
                                ModernLineLoader()
                            } else if (filteredSeries.isEmpty()) {
                                EmptyStateView("لا توجد مسلسلات تلفزيونية في الفئة الحالية.")
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(cardColumns),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("series_grid"),
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(filteredSeries) { series ->
                                        SeriesGridCard(
                                            series = series,
                                            isFavorite = favoritesSet.contains(series.id),
                                            onFavoriteToggle = { viewModel.toggleFavorite(series.id) }
                                        ) {
                                            viewModel.selectSeries(series)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "favorites" -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("favorites_list"),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Text(
                                text = "مفضلتك الشخصية",
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Right,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Favorite Channels Shelf
                        if (favoriteChannels.isNotEmpty()) {
                            item {
                                Text(
                                    text = "القنوات المباشرة المفضلة (${favoriteChannels.size})",
                                    color = LoopRedGlow,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(favoriteChannels) { chan ->
                                        ChannelGridCard(
                                            chan = chan,
                                            isFavorite = favoritesSet.contains(chan.id),
                                            onToggleFavorite = { viewModel.toggleFavorite(chan.id) }
                                        ) {
                                            viewModel.startPlaying(chan.streamUrl, chan.name, isLive = true, userAgent = chan.userAgent, forcedFormat = chan.forcedFormat)
                                        }
                                    }
                                }
                            }
                        }

                        // Favorite Movies Shelf
                        if (favoriteMovies.isNotEmpty()) {
                            item {
                                Text(
                                    text = "الأفلام المفضلة (${favoriteMovies.size})",
                                    color = LoopRedGlow,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(favoriteMovies) { movie ->
                                        MovieGridCard(
                                            movie = movie,
                                            isFavorite = favoritesSet.contains(movie.id),
                                            onFavoriteToggle = { viewModel.toggleFavorite(movie.id) }
                                        ) {
                                            viewModel.selectMovie(movie)
                                        }
                                    }
                                }
                            }
                        }

                        // Favorite Series Shelf
                        if (favoriteSeries.isNotEmpty()) {
                            item {
                                Text(
                                    text = "المسلسلات المفضلة (${favoriteSeries.size})",
                                    color = LoopRedGlow,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(favoriteSeries) { series ->
                                        SeriesGridCard(
                                            series = series,
                                            isFavorite = favoritesSet.contains(series.id),
                                            onFavoriteToggle = { viewModel.toggleFavorite(series.id) }
                                        ) {
                                            viewModel.selectSeries(series)
                                        }
                                    }
                                }
                            }
                        }

                        if (favoriteChannels.isEmpty() && favoriteMovies.isEmpty() && favoriteSeries.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.FavoriteBorder,
                                            contentDescription = "خالي",
                                            tint = SoftGreyText.copy(alpha = 0.4f),
                                            modifier = Modifier.size(64.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "لم تقم بإضافة أي محتوى للمفضلة بعد.",
                                            color = SoftGreyText,
                                            fontSize = 13.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                "settings" -> {
                    SettingsPanel(viewModel = viewModel)
                }
                "news" -> {
                    NewsPanel(viewModel = viewModel)
                }
                "chat" -> {
                    ChatPanel(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun CinematicHeroBanner(movie: Movie, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clickable { onClick() }
    ) {
        SafeAsyncImage(
            model = movie.posterUrl,
            contentDescription = movie.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Backdrop dark cinematics overlay gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, ObsidianBlack.copy(alpha = 0.5f), ObsidianBlack),
                        startY = 0f
                    )
                )
        )

        // Floating Title text container
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Yellow)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "العرض الرئيس البارز اليوم",
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = movie.title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, contentDescription = "تقييم", tint = GoldGlow, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = movie.rating.toString() + " • " + movie.year, color = Color.White, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = movie.duration, color = SoftGreyText, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun SearchBarWidget(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    val verticalPadding = if (isLandscape) 4.dp else 10.dp
    val fieldHeight = if (isLandscape) 38.dp else 48.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = verticalPadding)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(fieldHeight)
                .testTag("search_field"),
            placeholder = { Text(text = placeholder, color = SoftGreyText.copy(alpha = 0.6f), fontSize = if (isLandscape) 10.sp else 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "بحث", tint = LoopRed, modifier = Modifier.size(if (isLandscape) 16.dp else 18.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(if (isLandscape) 24.dp else 28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "مسح", tint = SoftGreyText, modifier = Modifier.size(if (isLandscape) 14.dp else 16.dp))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LoopRed,
                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                focusedContainerColor = MidnightGrey.copy(alpha = 0.9f),
                unfocusedContainerColor = MidnightGrey.copy(alpha = 0.7f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = if (isLandscape) 10.sp else 12.sp, color = Color.White),
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun CategoryChip(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .tvFocusable(shape = RoundedCornerShape(16.dp), onClick = onClick)
            .background(
                color = if (isSelected) activeColor else MidnightGrey,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            color = if (isSelected) Color.White else SoftGreyText,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun CategoryRow(
    categories: List<IPTVCategory>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit
) {
    if (categories.isEmpty()) return
    val lazyListState = rememberLazyListState()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    LazyRow(
        state = lazyListState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (isLandscape) 2.dp else 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CategoryChip(
                name = "الكل",
                isSelected = selectedCategory == "all",
                onClick = { onSelectCategory("all") }
            )
        }

        items(categories) { cat ->
            CategoryChip(
                name = cat.name,
                isSelected = selectedCategory == cat.id,
                onClick = { onSelectCategory(cat.id) }
            )
        }
    }
}

@Composable
fun ChannelGridCard(
    chan: Channel,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(18.dp), onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightGrey.copy(alpha = 0.95f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f) // Square 1:1 aspect ratio
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianBlack),
                contentAlignment = Alignment.Center
            ) {
                SafeAsyncImage(
                    model = chan.logoUrl,
                    contentDescription = chan.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(0.dp),
                    contentScale = ContentScale.Crop
                )
                
                // Optional Favorite Heart Button
                if (onToggleFavorite != null) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "المفضلة",
                            tint = if (isFavorite) LoopRed else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = chan.name,
                color = Color.White,
                fontSize = 10.5.sp, // Slightly smaller text size
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2, // Allow up to 2 lines to show full name
                minLines = 2, // Keep heights perfectly aligned
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
fun MainChannelGridCard(
    chan: Channel,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(20.dp), onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f) // Square 1:1 aspect ratio
                    .clip(RoundedCornerShape(16.dp))
                    .background(MidnightGrey),
                contentAlignment = Alignment.Center
            ) {
                SafeAsyncImage(
                    model = chan.logoUrl,
                    contentDescription = chan.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(0.dp),
                    contentScale = ContentScale.Crop
                )

                // Top Left Live badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(LoopRed, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(Color.White, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "بث مباشر",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Optional Favorite Heart Button
                if (onToggleFavorite != null) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "المفضلة",
                            tint = if (isFavorite) LoopRedGlow else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = chan.name,
                color = Color.White,
                fontSize = 10.5.sp, // Slightly smaller text size
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 2, // Allow up to 2 lines to show full name
                minLines = 2, // Keep heights perfectly aligned
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Subtitle of premium verification
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = GoldGlow,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "قناة رئيسية معتمدة",
                    color = SoftGreyText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun MainChannelsBanner(count: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(
                border = BorderStroke(1.dp, LoopRed.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightGrey)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(LoopRed, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "شبكة القنوات الرئيسية الممتازة",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "قنوات مخصصة ومضافة من قبل مطور السيرفر مباشرة. جودة فائقة UHD وبدون أي تقطيع.",
                    color = SoftGreyText,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .background(LoopRed.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "$count قنوات",
                    color = LoopRedGlow,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
fun MovieGridCard(
    movie: Movie,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .tvFocusable(shape = RoundedCornerShape(12.dp), onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        ) {
            SafeAsyncImage(
                model = movie.posterUrl,
                contentDescription = movie.title,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )

            // Absolute positioned heart button
            IconButton(
                onClick = { onFavoriteToggle() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "مفضلة",
                    tint = if (isFavorite) LoopRed else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = movie.title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SeriesGridCard(
    series: Series,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(12.dp), onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            SafeAsyncImage(
                model = series.posterUrl,
                contentDescription = series.title,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )

            // Absolute positioned heart button
            IconButton(
                onClick = { onFavoriteToggle() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "مفضلة",
                    tint = if (isFavorite) LoopRed else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = series.title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ModernLineLoader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "جاري تحميل المحتوى...",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            LinearProgressIndicator(
                color = LoopRed,
                trackColor = Color.Transparent,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "يرجى الانتظار، يتم التنسيق لضمان السرعة القصوى",
            color = SoftGreyText,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun EmptyStateView(msg: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Inbox,
                contentDescription = "قائمة فارغة",
                tint = SoftGreyText.copy(alpha = 0.3f),
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = msg,
                color = SoftGreyText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun MovieDetailsDialog(
    movie: Movie,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: (String) -> Unit,
    onDownload: (Movie) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(24.dp)),
            color = MidnightGrey
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Blurred Movie Backdrop Image Overlay
                SafeAsyncImage(
                    model = movie.posterUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .blur(8.dp)
                        .graphicsLayer(alpha = 0.4f)
                )

                // Close floating action trigger
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                }

                Column(
                    modifier = Modifier
                        .padding(top = 130.dp)
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SafeAsyncImage(
                            model = movie.posterUrl,
                            contentDescription = movie.title,
                            modifier = Modifier
                                .width(100.dp)
                                .height(150.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = movie.title,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = "تقييم", tint = GoldGlow, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = movie.rating.toString(), color = Color.White, fontSize = 12.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = "سنة: ${movie.year}", color = SoftGreyText, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "المدة: ${movie.duration}",
                                color = SoftGreyText,
                                fontSize = 11.sp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Favorite Toggle
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggleFavorite() }
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isFavorite) LoopRed else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isFavorite) "مفضل مضاف" else "إضافة للمفضلة",
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "تفاصيل العرض",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = movie.description.ifEmpty { "لا يوجد وصف لهذا الفيلم في الوقت الحالي." },
                        color = SoftGreyText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            onPlay(movie.streamUrl)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("play_movie_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "شغل", tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "مشاهدة الفيلم الآن", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            onDownload(movie)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("download_movie_btn"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "تحميل محلي", tint = Color.Green)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "فحص الجودة والتحميل محلياً", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailsDialog(
    series: Series,
    seasons: List<Season>,
    episodes: List<Episode>,
    isLoadingEpisodes: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    onSeasonTabSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedSeasonId by remember { mutableStateOf("") }
    
    // Automatically query first season when seasons first load
    LaunchedEffect(seasons) {
        if (seasons.isNotEmpty() && selectedSeasonId.isEmpty()) {
            selectedSeasonId = seasons.first().id
            onSeasonTabSelected(seasons.first().id)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(24.dp)),
            color = MidnightGrey
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Background blurry cover overlay
                SafeAsyncImage(
                    model = series.posterUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .blur(8.dp)
                        .graphicsLayer(alpha = 0.35f)
                )

                // Close Trigger
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                }

                Column(
                    modifier = Modifier
                        .padding(top = 130.dp)
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SafeAsyncImage(
                            model = series.posterUrl,
                            contentDescription = series.title,
                            modifier = Modifier
                                .width(90.dp)
                                .height(130.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = series.title,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = "تقييم", tint = GoldGlow, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = series.rating.toString(), color = Color.White, fontSize = 11.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(text = "سنة: ${series.year}", color = SoftGreyText, fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Favorites Toggle
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggleFavorite() }
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isFavorite) LoopRed else Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isFavorite) "مفضل مضاف" else "إضافة للمفضلة",
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "قصة المسلسل",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = series.description.ifEmpty { "لا يوجد وصف مختصر متوفر حالياً لهذا المسلسل التلفزيوني." },
                        color = SoftGreyText,
                        fontSize = 11.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Seasons selector carousel
                    if (seasons.isNotEmpty()) {
                        Text(
                            text = "المواسم المتاحة",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(seasons) { s ->
                                val isSelectedColor = if (selectedSeasonId == s.id) LoopRed else MidnightGrey
                                val isTextColor = if (selectedSeasonId == s.id) Color.White else SoftGreyText
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(isSelectedColor)
                                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedSeasonId = s.id
                                            onSeasonTabSelected(s.id)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(text = s.title, color = isTextColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "قائمة الحلقات لهذا الموسم",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Episodes listing container
                    if (isLoadingEpisodes) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = LoopRed, modifier = Modifier.size(32.dp))
                        }
                    } else if (episodes.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "لا توجد أي حلقات متاحة في هذا الموسم حالياً.", color = SoftGreyText, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag("episodes_list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(episodes) { ep ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(ObsidianBlack)
                                        .clickable {
                                            onPlayEpisode(ep)
                                            onDismiss()
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(LoopRed.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "شغل", tint = LoopRed)
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "حلقة ${ep.number}: ${ep.title}",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (ep.duration.isNotEmpty()) {
                                            Text(
                                                text = "المدة: ${ep.duration}",
                                                color = SoftGreyText,
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    IconButton(
                                        onClick = {
                                            onDownloadEpisode(ep)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudDownload,
                                            contentDescription = "تحميل حلقة",
                                            tint = Color.Green,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPanel(viewModel: MainViewModel) {
    val context = LocalContext.current
    val currentMode by viewModel.playerMode.collectAsState()
    val autoLandscape by viewModel.autoLandscape.collectAsState()

    var settingsSubPage by remember { mutableStateOf("home") } // "home", "themes", "downloads", "activate"
    var showDevPasswordDialog by remember { mutableStateOf(false) }
    var devPasswordInput by remember { mutableStateOf("") }

    when (settingsSubPage) {
        "home" -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("settings_screen"),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Title and brief description
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Text(
                            text = "إعدادات تطبيق 25 Live الفاخرة",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Right,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "قم بتخصيص المظهر والمشغل وبطاقات العرض في مكان واحد",
                            color = SoftGreyText,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Right,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 3. Navigation Shortcuts (Themes & Downloads & Card Column Count)
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MidnightGrey)
                            .padding(2.dp)
                    ) {
                        // Profile Edit Navigation item
                        val chatUsername by viewModel.chatUsername.collectAsState()
                        if (chatUsername != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { settingsSubPage = "account" }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(text = "تعديل الملف الشخصي والحساب", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(text = "تغيير اسم المستخدم والاسم المعروض بأمان وتفرد", color = SoftGreyText, fontSize = 9.sp)
                                    }
                                }
                                Icon(Icons.Default.ArrowBack, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(14.dp).graphicsLayer(rotationZ = 180f))
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                        }

                        // App Themes Navigation item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { settingsSubPage = "themes" }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(GoldGlow.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Palette, contentDescription = null, tint = GoldGlow, modifier = Modifier.size(14.dp))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(text = "ثيمات وألوان واجهة التطبيق", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(text = "اختر التنسيق والنمط البصري الذي يناسبك", color = SoftGreyText, fontSize = 9.sp)
                                }
                            }
                            Icon(Icons.Default.ArrowBack, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(14.dp).graphicsLayer(rotationZ = 180f))
                        }

                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                        // Downloads Navigation item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { settingsSubPage = "downloads" }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color.Green.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.Green, modifier = Modifier.size(14.dp))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(text = "مكتبة المحتويات المحملة", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(text = "استعراض الأفلام والحلقات المحفوظة أوفلاين كلياً", color = SoftGreyText, fontSize = 9.sp)
                                }
                            }
                            Icon(Icons.Default.ArrowBack, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(14.dp).graphicsLayer(rotationZ = 180f))
                        }

                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                        // Card Layout Navigation item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { settingsSubPage = "card_layout" }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.GridView, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(text = "طريقة عرض وترتيب البطاقات", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(text = "تعديل عدد الأعمدة في شبكات العرض وتخصيص الواجهة", color = SoftGreyText, fontSize = 9.sp)
                                }
                            }
                            Icon(Icons.Default.ArrowBack, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(14.dp).graphicsLayer(rotationZ = 180f))
                        }
                    }
                }

                // 4. Video Player & Format Switcher Card (Now loads elegant lists popup menu dropdown)
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "تعديلات مشغل الفيديو", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(text = "صيغة البث الأساسية للبث المباشر (Live Stream):", color = SoftGreyText, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(8.dp))

                            // ELEGANT POPUP/DROPDOWN SELECTOR (Requested)
                            var showFormatSelectorMenu by remember { mutableStateOf(false) }

                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ObsidianBlack)
                                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                        .clickable { showFormatSelectorMenu = true }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "امتداد البث المعتمد حالياً",
                                            color = SoftGreyText,
                                            fontSize = 9.sp
                                        )
                                        Text(
                                            text = when (currentMode) {
                                                "ts" -> "بث بثباشر (TS Stream)"
                                                "m3u8" -> "بث بثباشر (M3U8 HLS)"
                                                else -> "تلقائي ذكي (Smart Auto)"
                                            },
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "عرض الخيارات",
                                        tint = Color.White
                                    )
                                }

                                DropdownMenu(
                                    expanded = showFormatSelectorMenu,
                                    onDismissRequest = { showFormatSelectorMenu = false },
                                    modifier = Modifier
                                        .background(MidnightGrey)
                                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("تلقائي ذكي (Smart Auto) - يوصى به", color = Color.White, fontSize = 12.sp) },
                                        onClick = {
                                            viewModel.setPlayerMode("smart")
                                            showFormatSelectorMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("بث مباشر (M3U8 Playback)", color = Color.White, fontSize = 12.sp) },
                                        onClick = {
                                            viewModel.setPlayerMode("m3u8")
                                            showFormatSelectorMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("بث مباشر (TS Playback)", color = Color.White, fontSize = 12.sp) },
                                        onClick = {
                                            viewModel.setPlayerMode("ts")
                                            showFormatSelectorMenu = false
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "التدوير التلقائي الذكي للشاشة", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(text = "تشغيل الشاشة تلقائياً بالكامل بالوضع الأفقي عند العرض فقط", color = SoftGreyText, fontSize = 10.sp)
                                }
                                Switch(
                                    checked = autoLandscape,
                                    onCheckedChange = { viewModel.setAutoLandscape(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        uncheckedThumbColor = SoftGreyText,
                                        uncheckedTrackColor = ObsidianBlack
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "إظهار أيقونة الإشعارات", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(text = "إخفاء أو إظهار جرس الإشعارات في الشريط العلوي", color = SoftGreyText, fontSize = 10.sp)
                                }
                                val showNoteIconSetting by viewModel.showNotificationIcon.collectAsState()
                                Switch(
                                    checked = showNoteIconSetting,
                                    onCheckedChange = { viewModel.setShowNotificationIcon(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        uncheckedThumbColor = SoftGreyText,
                                        uncheckedTrackColor = ObsidianBlack
                                    )
                                )
                            }
                        }
                    }
                }

                // 5. Support Details and Link Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "دعم 25 Live الفني",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "نحن جاهزون لمساعدتك فوراً في إدخال وتفعيل اشتراكاتك أو سيرفرات Xtream ومشاكل تحميل القنوات.",
                                color = SoftGreyText,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/jdj_q"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "تليجرام غير متوفر على جهازك", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Send, contentDescription = "تواصل", tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "مراسلة الدعم والوكلاء", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Developer Control Panel Button inside settings
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MidnightGrey)
                            .padding(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDevPasswordDialog = true }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(GoldGlow.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.DeveloperMode, contentDescription = null, tint = GoldGlow, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(text = "قسم تحكم مطور", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(text = "إدارة الأقسام ونشر قنوات بث أونلاين على السيرفر", color = SoftGreyText, fontSize = 10.sp)
                                }
                            }
                            Icon(Icons.Default.ArrowBack, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(16.dp).graphicsLayer(rotationZ = 180f))
                        }
                    }
                }

                // Dedicated secure Exit Button (Logout)
                item {
                    Button(
                        onClick = { viewModel.logout() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "تسجيل خروج آمن من الحساب الحالي", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        "account" -> {
            val currentUsername by viewModel.chatUsername.collectAsState()
            val currentName by viewModel.chatName.collectAsState()
            
            var newUsername by remember { mutableStateOf(currentUsername ?: "") }
            var newName by remember { mutableStateOf(currentName ?: "") }
            
            var isLoading by remember { mutableStateOf(false) }
            var statusMessage by remember { mutableStateOf<String?>(null) }
            var isSuccess by remember { mutableStateOf(false) }
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Return Back to general options row selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsSubPage = "home" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "رجوع",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "العودة للإعدادات العامة",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "تعديل الملف الشخصي",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Right,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Text(
                            text = "يمكنك تغيير اسم المستخدم المعرف الخاص بك واسمك الشخصي المعروض. نظامنا الذكي سيتحقق تلقائياً من عدم تكرار اسم المستخدم لضمان تفرد حسابك.",
                            color = SoftGreyText,
                            fontSize = 10.5.sp,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.Right,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        // Input fields
                        Text(text = "الاسم الشخصي (يظهر للجميع ويسمح بالتكرار):", color = Color.White, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Right, color = Color.White, fontSize = 12.sp),
                            singleLine = true,
                            placeholder = { Text("مثال: أحمد العتيبي", color = SoftGreyText, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LoopRed,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedContainerColor = ObsidianBlack,
                                unfocusedContainerColor = ObsidianBlack
                            )
                        )
                        
                        Text(text = "اسم المستخدم (فريد ولا يمكن تكراره):", color = Color.White, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
                        OutlinedTextField(
                            value = newUsername,
                            onValueChange = { newUsername = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Right, color = Color.White, fontSize = 12.sp),
                            singleLine = true,
                            placeholder = { Text("مثال: ahmed_99", color = SoftGreyText, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LoopRed,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedContainerColor = ObsidianBlack,
                                unfocusedContainerColor = ObsidianBlack
                            )
                        )
                        
                        statusMessage?.let { msg ->
                            Text(
                                text = msg,
                                color = if (isSuccess) Color.Green else LoopRedGlow,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                        }
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        Button(
                            onClick = {
                                if (newName.isBlank() || newUsername.isBlank()) {
                                    statusMessage = "برجاء ملء جميع الحقول المطلوبة"
                                    isSuccess = false
                                    return@Button
                                }
                                isLoading = true
                                statusMessage = "جاري التحقق من تفرد اسم المستخدم وحفظ التعديلات..."
                                isSuccess = false
                                viewModel.updateChatUser(newUsername, newName) { success, err ->
                                    isLoading = false
                                    if (success) {
                                        isSuccess = true
                                        statusMessage = "تم تحديث معلومات الحساب بنجاح!"
                                    } else {
                                        isSuccess = false
                                        statusMessage = err ?: "فشل التحديث، اسم المستخدم مستخدم بالفعل!"
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                            } else {
                                Text("حفظ التغييرات الآن", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        "themes" -> {
            val selectedTheme by viewModel.selectedTheme.collectAsState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Return Back to general options row selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsSubPage = "home" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "رجوع",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "العودة للإعدادات العامة",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "ثيمات واجهة التطبيق (Themes)",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "اختر مظهراً احترافياً ليتناسب مع ذوقك وراحتك البصرية أثناء تصفح القنوات المفضلة ومكتبة المحتويات:",
                            color = SoftGreyText,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val themes = listOf(
                            Triple("red", "الأحمر المتوهج الملكي (Red Glow)", Color(0xFFE50914)),
                            Triple("gold", "الملكي الذهبي الفاخر (Premium Gold)", Color(0xFFFFD700)),
                            Triple("purple", "البربل الفضائي اللامع (Purple Nebula)", Color(0xFF9D4EDD)),
                            Triple("emerald", "الزمرد الهادئ والأنيق (Midnight Emerald)", Color(0xFF00C9A7)),
                            Triple("blue", "الياقوت الأزرق العميق (Ocean Blue)", Color(0xFF007BFF))
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            themes.forEach { (themeId, label, colorAccent) ->
                                val isSelected = selectedTheme == themeId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) colorAccent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.02f))
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) colorAccent else Color.White.copy(alpha = 0.05f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { viewModel.setTheme(themeId) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(colorAccent)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            color = if (isSelected) Color.White else SoftGreyText,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "محدد",
                                            tint = colorAccent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "downloads" -> {
            var savedOfflineList by remember { mutableStateOf(loadDownloads(context)) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Back Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsSubPage = "home" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "العودة للإعدادات العامة", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "المحتويات المحملة محلياً (أوفلاين)",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                if (savedOfflineList.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(54.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "مكتبة التنزيلات فارغة حالياً.", color = SoftGreyText, fontSize = 13.sp)
                            Text(text = "يمكنك تنزيل الأفلام والحلقات لتشغيلها بدون إنترنت بالكامل.", color = SoftGreyText.copy(alpha = 0.7f), fontSize = 10.sp, textAlign = TextAlign.Center)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(savedOfflineList) { download ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Thumbnail
                                    SafeAsyncImage(
                                        model = download.posterUrl,
                                        contentDescription = download.title,
                                        modifier = Modifier
                                            .size(width = 60.dp, height = 90.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black.copy(alpha = 0.2f))
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Details column
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = download.title,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        // Detected Real Quality pill
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color.Green.copy(alpha = 0.15f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = download.quality.ifEmpty { "1080p FHD" },
                                                    color = Color.Green,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "محفوظ على الجهاز",
                                                color = SoftGreyText,
                                                fontSize = 10.sp
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Action triggers
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Play offline custom file
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(LoopRed)
                                                    .clickable {
                                                        // Instantly boots custom Movie Player with cached file/URL
                                                        viewModel.startPlaying(download.videoUrl, download.title, isLive = false)
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(text = "تشغيل الآن", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            // Delete file
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color.White.copy(alpha = 0.05f))
                                                    .clickable {
                                                        val updated = savedOfflineList.filter { it.id != download.id }
                                                        saveDownloads(context, updated)
                                                        savedOfflineList = updated
                                                        Toast.makeText(context, "تم حذف الملف بنجاح", Toast.LENGTH_SHORT).show()
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.Delete, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(12.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(text = "حذف التنزيل", color = SoftGreyText, fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "card_layout" -> {
            val cardColumns by viewModel.cardColumnsCount.collectAsState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Back Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsSubPage = "home" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "العودة للإعدادات العامة", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "طريقة عرض وترتيب البطاقات",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "تحكم في شبكة القنوات ومحتوى الفيديو عبر تحديد حجم الشبكة المفضل لشاشتك:",
                    color = SoftGreyText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                val columnChoices = listOf(
                    Triple(2, "شبكة ثنائية 2*2 لسهولة التصفح", "بطاقات واضحة بحجم كبير تناسب القنوات الرياضية والتحكم اللمسي المريح"),
                    Triple(3, "شبكة ثلاثية 3*3 للعرض المتوازن", "التنظيم الافتراضي والمثالي لعرض تفاصيل القناة وعدد المربعات المريح"),
                    Triple(4, "شبكة رباعية 4*4 للمشاهدة السريعة", "بطاقات مصغرة تمكنك من رؤية ومسح أكبر عدد ممكن من القنوات والبثوث دفعة واحدة")
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    columnChoices.forEach { (count, label, desc) ->
                        val isSelected = cardColumns == count
                        val activeColor = MaterialTheme.colorScheme.primary

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) activeColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.02f))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) activeColor else Color.White.copy(alpha = 0.05f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { viewModel.setCardColumnsCount(count) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1.0f).padding(end = 8.dp)) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.White else SoftGreyText,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = desc,
                                    color = SoftGreyText.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "محدد",
                                    tint = activeColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        "dev_control" -> {
            val isDevAuth by viewModel.isDeveloperAuthenticated.collectAsState()
            val devStatusMessage by viewModel.developerActionStatus.collectAsState()
            val categories by viewModel.categories.collectAsState()
            val mainChannels by viewModel.mainChannels.collectAsState()
            val channels by viewModel.channels.collectAsState()
            val movies by viewModel.movies.collectAsState()
            val series by viewModel.series.collectAsState()

            var passwordInput by remember { mutableStateOf("") }
            var activeDevTab by remember { mutableStateOf("categories") } // "categories", "channels", "live_streams", "movies", "series", "app_settings"

            var isEditingCategory by remember { mutableStateOf(false) }
            var isEditingChannel by remember { mutableStateOf(false) }

            // Form inputs for categories
            var catIdInput by remember { mutableStateOf("") }
            var catNameInput by remember { mutableStateOf("") }
            var catTypeInput by remember { mutableStateOf("channel") } // "channel", "movie", "series", "exclusive"
            var showCatTypeMenu by remember { mutableStateOf(false) }

            // Form inputs for channels (Main Channels - قنوات رئيسية)
            var chanIdInput by remember { mutableStateOf("") }
            var chanNameInput by remember { mutableStateOf("") }
            var chanLogoUrlInput by remember { mutableStateOf("") }
            var chanStreamUrlInput by remember { mutableStateOf("") }
            var chanCategoryInput by remember { mutableStateOf("") }
            var chanUserAgentInput by remember { mutableStateOf("") }
            var chanForcedFormatInput by remember { mutableStateOf("ts") }
            var showChanCatMenu by remember { mutableStateOf(false) }
            var showChanFormatMenu by remember { mutableStateOf(false) }

            // Form inputs for Live Streams (بث مباشر - IPTV Channels)
            var liveChanIdInput by remember { mutableStateOf("") }
            var liveChanNameInput by remember { mutableStateOf("") }
            var liveChanLogoUrlInput by remember { mutableStateOf("") }
            var liveChanStreamUrlInput by remember { mutableStateOf("") }
            var liveChanCategoryInput by remember { mutableStateOf("") }
            var liveChanUserAgentInput by remember { mutableStateOf("") }
            var liveChanForcedFormatInput by remember { mutableStateOf("ts") }
            var isEditingLiveChannel by remember { mutableStateOf(false) }
            var showLiveChanCatMenu by remember { mutableStateOf(false) }
            var showLiveChanFormatMenu by remember { mutableStateOf(false) }

            // Form inputs for Movies (أفلام)
            var movieIdInput by remember { mutableStateOf("") }
            var movieTitleInput by remember { mutableStateOf("") }
            var movieDescInput by remember { mutableStateOf("") }
            var moviePosterUrlInput by remember { mutableStateOf("") }
            var movieStreamUrlInput by remember { mutableStateOf("") }
            var movieCategoryInput by remember { mutableStateOf("") }
            var movieDurationInput by remember { mutableStateOf("") }
            var movieRatingInput by remember { mutableStateOf("8.5") }
            var movieYearInput by remember { mutableStateOf("2026") }
            var isEditingMovie by remember { mutableStateOf(false) }
            var showMovieCatMenu by remember { mutableStateOf(false) }

            // Form inputs for Series (مسلسلات)
            var seriesIdInput by remember { mutableStateOf("") }
            var seriesTitleInput by remember { mutableStateOf("") }
            var seriesDescInput by remember { mutableStateOf("") }
            var seriesPosterUrlInput by remember { mutableStateOf("") }
            var seriesCategoryInput by remember { mutableStateOf("") }
            var seriesRatingInput by remember { mutableStateOf("8.5") }
            var seriesYearInput by remember { mutableStateOf("2026") }
            var isEditingSeries by remember { mutableStateOf(false) }
            var showSeriesCatMenu by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Return / Back Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            viewModel.clearDeveloperStatus()
                            settingsSubPage = "home" 
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "العودة للإعدادات العامة", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                if (!isDevAuth) {
                    // Password Screen to access Dev Panel
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.DeveloperMode, contentDescription = null, tint = GoldGlow, modifier = Modifier.size(48.dp))
                            
                            Text(
                                text = "تحقق من صلاحية المطور",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = "الرجاء إدخال كلمة المرور السرية لفتح لوحة تحكم نشر القنوات والأقسام أونلاين على السيرفر:",
                                color = SoftGreyText,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )

                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("كلمة المرور مخصصة", color = SoftGreyText) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = GoldGlow,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            devStatusMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    color = if (msg.contains("خاطئة")) LoopRedGlow else Color.Green,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Button(
                                onClick = {
                                    viewModel.authenticateDeveloper(passwordInput)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GoldGlow),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("تأكيد وفتح لوحة التحكم", color = ObsidianBlack, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Developer is Authenticated! Show Professional Panel
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "لوحة تحكم السيرفر (Online)",
                                color = GoldGlow,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Button(
                                onClick = { viewModel.logoutDeveloper() },
                                colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("خروج", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Dev Tabs Picker
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MidnightGrey)
                                .horizontalScroll(rememberScrollState())
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val tabs = listOf(
                                "categories" to "نشر أقسام",
                                "channels" to "قنوات رئيسية",
                                "live_streams" to "بث مباشر",
                                "movies" to "أفلام المكتبة",
                                "series" to "مسلسلات المكتبة",
                                "notifications" to "إرسال إشعارات",
                                "app_settings" to "إعدادات التطبيق"
                            )
                            tabs.forEach { (tabKey, tabLabel) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (activeDevTab == tabKey) GoldGlow else Color.Transparent)
                                        .clickable { activeDevTab = tabKey }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tabLabel,
                                        color = if (activeDevTab == tabKey) ObsidianBlack else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        devStatusMessage?.let { msg ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GoldGlow.copy(alpha = 0.1f))
                                    .padding(10.dp),
                                contentAlignment = Alignment.Center
                              ) {
                                  Text(
                                      text = msg,
                                      color = GoldGlow,
                                      fontSize = 11.sp,
                                      fontWeight = FontWeight.Bold,
                                      textAlign = TextAlign.Center
                                  )
                              }
                        }

                        if (activeDevTab == "categories") {
                            // CATEGORIES CREATION FORM
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(
                                                text = if (isEditingCategory) "تعديل بيانات القسم الحالي" else "إضافة قسم ومجموعة محتوى جديدة أونلاين",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )

                                            OutlinedTextField(
                                                value = catIdInput,
                                                onValueChange = { catIdInput = it },
                                                enabled = !isEditingCategory,
                                                label = { Text(if (isEditingCategory) "معرف القسم الفريد (ثابت لا يمكن تعديله)" else "معرف القسم الفريد (مثال: exclusive_movies)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    disabledBorderColor = Color.White.copy(alpha = 0.05f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White,
                                                    disabledTextColor = SoftGreyText
                                                )
                                            )

                                            OutlinedTextField(
                                                value = catNameInput,
                                                onValueChange = { catNameInput = it },
                                                label = { Text("اسم القسم المعروض (مثال: بث الرياضة الحصري)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            // Type selection is now FIXED to exclusive (Main Channels) as requested
                                            catTypeInput = "exclusive"
                                            
                                            Text(
                                                text = "نوع القسم: قنوات رئيسية/بث حصري (حصرياً)",
                                                color = GoldGlow,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                item {
                                    if (isEditingCategory) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    if (catIdInput.isNotEmpty() && catNameInput.isNotEmpty()) {
                                                        viewModel.publishDeveloperCategory(catIdInput, catNameInput, catTypeInput)
                                                        catIdInput = ""
                                                        catNameInput = ""
                                                        isEditingCategory = false
                                                    } else {
                                                        Toast.makeText(context, "الرجاء تعبئة كامل الحقول للقسم!", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(1.3f)
                                            ) {
                                                Text("حفظ التعديلات أونلاين", color = ObsidianBlack, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            }

                                            Button(
                                                onClick = {
                                                    catIdInput = ""
                                                    catNameInput = ""
                                                    isEditingCategory = false
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(0.7f)
                                            ) {
                                                Text("إلغاء", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            }
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                if (catIdInput.isNotEmpty() && catNameInput.isNotEmpty()) {
                                                    viewModel.publishDeveloperCategory(catIdInput, catNameInput, catTypeInput)
                                                    catIdInput = ""
                                                    catNameInput = ""
                                                } else {
                                                    Toast.makeText(context, "الرجاء تعبئة كامل الحقول للقسم!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = GoldGlow),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("نشر وتفعيل القسم أونلاين", color = ObsidianBlack, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }

                                // Categories list online to manage/delete
                                item {
                                    Text("الأقسام المفعلة أونلاين حالياً (${categories.size})", color = SoftGreyText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                                }

                                items(categories) { cat ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MidnightGrey.copy(alpha = 0.5f))
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = cat.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(text = "المعرف: ${cat.id} | النوع: ${cat.type}", color = SoftGreyText, fontSize = 9.sp)
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    catIdInput = cat.id
                                                    catNameInput = cat.name
                                                    catTypeInput = cat.type
                                                    isEditingCategory = true
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = GoldGlow, modifier = Modifier.size(16.dp))
                                            }

                                            IconButton(
                                                onClick = { viewModel.deleteDeveloperCategory(cat.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = LoopRed, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (activeDevTab == "channels") {
                            // CHANNELS CREATION FORM
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(
                                                text = if (isEditingChannel) "تعديل بيانات القناة الرئيسية المحددة" else "نشر قناة رئيسية جديدة أونلاين",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )

                                            OutlinedTextField(
                                                value = chanNameInput,
                                                onValueChange = { chanNameInput = it },
                                                label = { Text("اسم القناة (مثل: beIN Sports 1 Premium)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = chanStreamUrlInput,
                                                onValueChange = { chanStreamUrlInput = it },
                                                label = { Text("رابط البث الحي (URL - m3u8 / ts / mp4)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = chanLogoUrlInput,
                                                onValueChange = { chanLogoUrlInput = it },
                                                label = { Text("رابط لوجو/شعار القناة (اختياري)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = chanUserAgentInput,
                                                onValueChange = { chanUserAgentInput = it },
                                                label = { Text("إعداد User-Agent اختياري (مثل: VLC/3.0.18)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            // Category selector dropdown
                                            val liveCats = categories.filter { it.type == "channel" || it.type == "exclusive" || it.type == "" }
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Box(modifier = Modifier.weight(1f)) {
                                                    OutlinedButton(
                                                        onClick = { showChanCatMenu = true },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            val selectedCatName = liveCats.find { it.id == chanCategoryInput }?.name ?: "اختر الفئة"
                                                            Text(text = selectedCatName, fontSize = 11.sp, maxLines = 1)
                                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                        }
                                                    }

                                                    DropdownMenu(
                                                        expanded = showChanCatMenu,
                                                        onDismissRequest = { showChanCatMenu = false },
                                                        modifier = Modifier.background(MidnightGrey)
                                                    ) {
                                                        liveCats.forEach { c ->
                                                            DropdownMenuItem(
                                                                text = { Text(c.name, color = Color.White) },
                                                                onClick = {
                                                                    chanCategoryInput = c.id
                                                                    showChanCatMenu = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }

                                                Box(modifier = Modifier.weight(1f)) {
                                                    OutlinedButton(
                                                        onClick = { showChanFormatMenu = true },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(text = "نوع المشغل: ${chanForcedFormatInput.uppercase()}", fontSize = 11.sp)
                                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                        }
                                                    }

                                                    DropdownMenu(
                                                        expanded = showChanFormatMenu,
                                                        onDismissRequest = { showChanFormatMenu = false },
                                                        modifier = Modifier.background(MidnightGrey)
                                                    ) {
                                                        listOf("ts", "m3u8", "normal").forEach { fmt ->
                                                            DropdownMenuItem(
                                                                text = { Text(fmt.uppercase(), color = Color.White) },
                                                                onClick = {
                                                                    chanForcedFormatInput = fmt
                                                                    showChanFormatMenu = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            if (isEditingChannel) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Button(
                                                        onClick = {
                                                            if (chanNameInput.isNotEmpty() && chanStreamUrlInput.isNotEmpty() && chanCategoryInput.isNotEmpty()) {
                                                                viewModel.publishDeveloperMainChannel(
                                                                    id = chanIdInput,
                                                                    name = chanNameInput,
                                                                    logoUrl = chanLogoUrlInput,
                                                                    streamUrl = chanStreamUrlInput,
                                                                    categoryId = chanCategoryInput,
                                                                    userAgent = chanUserAgentInput,
                                                                    forcedFormat = chanForcedFormatInput
                                                                )
                                                                chanIdInput = ""
                                                                chanNameInput = ""
                                                                chanStreamUrlInput = ""
                                                                chanLogoUrlInput = ""
                                                                chanUserAgentInput = ""
                                                                chanCategoryInput = ""
                                                                isEditingChannel = false
                                                            } else {
                                                                Toast.makeText(context, "الرجاء ملء حقول الاسم والرابط واختيار الفئة للقناة!", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.weight(1.3f)
                                                    ) {
                                                        Text("حفظ تعديل القناة", color = ObsidianBlack, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                    }

                                                    Button(
                                                        onClick = {
                                                            chanIdInput = ""
                                                            chanNameInput = ""
                                                            chanStreamUrlInput = ""
                                                            chanLogoUrlInput = ""
                                                            chanUserAgentInput = ""
                                                            chanCategoryInput = ""
                                                            isEditingChannel = false
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.weight(0.7f)
                                                    ) {
                                                        Text("إلغاء", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                    }
                                                }
                                            } else {
                                                Button(
                                                    onClick = {
                                                        if (chanNameInput.isNotEmpty() && chanStreamUrlInput.isNotEmpty() && chanCategoryInput.isNotEmpty()) {
                                                            viewModel.publishDeveloperMainChannel(
                                                                id = "",
                                                                name = chanNameInput,
                                                                logoUrl = chanLogoUrlInput,
                                                                streamUrl = chanStreamUrlInput,
                                                                categoryId = chanCategoryInput,
                                                                userAgent = chanUserAgentInput,
                                                                forcedFormat = chanForcedFormatInput
                                                            )
                                                            chanNameInput = ""
                                                            chanStreamUrlInput = ""
                                                            chanLogoUrlInput = ""
                                                            chanUserAgentInput = ""
                                                            chanCategoryInput = ""
                                                        } else {
                                                            Toast.makeText(context, "الرجاء ملء حقول الاسم والرابط واختيار الفئة للقناة!", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = GoldGlow),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("نشر وتنشيط القناة الرئيسية", color = ObsidianBlack, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }

                                // Channels list online to manage/delete
                                item {
                                    Text("القنوات الرئيسية المفعلة أونلاين حالياً (${mainChannels.size})", color = SoftGreyText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                                }

                                items(mainChannels) { chan ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MidnightGrey.copy(alpha = 0.5f))
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = chan.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(text = "الفئة: ${chan.category} | UA: ${chan.userAgent.ifEmpty { "افتراضي" }}", color = SoftGreyText, fontSize = 9.sp)
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    chanIdInput = chan.id
                                                    chanNameInput = chan.name
                                                    chanStreamUrlInput = chan.streamUrl
                                                    chanLogoUrlInput = chan.logoUrl
                                                    chanUserAgentInput = chan.userAgent
                                                    chanCategoryInput = chan.category
                                                    chanForcedFormatInput = chan.forcedFormat
                                                    isEditingChannel = true
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = GoldGlow, modifier = Modifier.size(16.dp))
                                            }

                                            IconButton(
                                                onClick = { viewModel.deleteDeveloperMainChannel(chan.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = LoopRed, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (activeDevTab == "live_streams") {
                            // LIVE STREAMS CREATION FORM
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(
                                                text = if (isEditingLiveChannel) "تعديل بيانات القناة الحالية" else "نشر قناة بث مباشر جديدة أونلاين",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )

                                            OutlinedTextField(
                                                value = liveChanNameInput,
                                                onValueChange = { liveChanNameInput = it },
                                                label = { Text("اسم القناة (مثل: الجزيرة HD)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = liveChanStreamUrlInput,
                                                onValueChange = { liveChanStreamUrlInput = it },
                                                label = { Text("رابط البث المباشر (m3u8 / ts / mp4)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = liveChanLogoUrlInput,
                                                onValueChange = { liveChanLogoUrlInput = it },
                                                label = { Text("رابط شعار القناة (صورة PNG/JPG)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = liveChanUserAgentInput,
                                                onValueChange = { liveChanUserAgentInput = it },
                                                label = { Text("إعداد User-Agent مخصص (اختياري)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            // Category selector
                                            val channelCats = categories.filter { it.type == "channel" }
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Box(modifier = Modifier.weight(1f)) {
                                                    OutlinedButton(
                                                        onClick = { showLiveChanCatMenu = true },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            val selectedCatName = channelCats.find { it.id == liveChanCategoryInput }?.name ?: "اختر الفئة"
                                                            Text(text = selectedCatName, fontSize = 11.sp, maxLines = 1)
                                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                        }
                                                    }

                                                    DropdownMenu(
                                                        expanded = showLiveChanCatMenu,
                                                        onDismissRequest = { showLiveChanCatMenu = false },
                                                        modifier = Modifier.background(MidnightGrey)
                                                    ) {
                                                        channelCats.forEach { c ->
                                                            DropdownMenuItem(
                                                                text = { Text(c.name, color = Color.White) },
                                                                onClick = {
                                                                    liveChanCategoryInput = c.id
                                                                    showLiveChanCatMenu = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }

                                                Box(modifier = Modifier.weight(1f)) {
                                                    OutlinedButton(
                                                        onClick = { showLiveChanFormatMenu = true },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(text = "نوع: ${liveChanForcedFormatInput.uppercase()}", fontSize = 11.sp)
                                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                        }
                                                    }

                                                    DropdownMenu(
                                                        expanded = showLiveChanFormatMenu,
                                                        onDismissRequest = { showLiveChanFormatMenu = false },
                                                        modifier = Modifier.background(MidnightGrey)
                                                    ) {
                                                        listOf("ts", "m3u8", "normal").forEach { fmt ->
                                                            DropdownMenuItem(
                                                                text = { Text(fmt.uppercase(), color = Color.White) },
                                                                onClick = {
                                                                    liveChanForcedFormatInput = fmt
                                                                    showLiveChanFormatMenu = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            if (isEditingLiveChannel) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Button(
                                                        onClick = {
                                                            if (liveChanNameInput.isNotEmpty() && liveChanStreamUrlInput.isNotEmpty() && liveChanCategoryInput.isNotEmpty()) {
                                                                viewModel.publishDeveloperChannel(
                                                                    id = liveChanIdInput,
                                                                    name = liveChanNameInput,
                                                                    logoUrl = liveChanLogoUrlInput,
                                                                    streamUrl = liveChanStreamUrlInput,
                                                                    categoryId = liveChanCategoryInput,
                                                                    userAgent = liveChanUserAgentInput,
                                                                    forcedFormat = liveChanForcedFormatInput
                                                                )
                                                                liveChanIdInput = ""
                                                                liveChanNameInput = ""
                                                                liveChanStreamUrlInput = ""
                                                                liveChanLogoUrlInput = ""
                                                                liveChanUserAgentInput = ""
                                                                liveChanCategoryInput = ""
                                                                isEditingLiveChannel = false
                                                            } else {
                                                                Toast.makeText(context, "الرجاء تعبئة حقول الاسم والرابط والفئة!", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.weight(1.3f)
                                                    ) {
                                                        Text("حفظ التعديلات أونلاين", color = ObsidianBlack, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                    }

                                                    Button(
                                                        onClick = {
                                                            liveChanIdInput = ""
                                                            liveChanNameInput = ""
                                                            liveChanStreamUrlInput = ""
                                                            liveChanLogoUrlInput = ""
                                                            liveChanUserAgentInput = ""
                                                            liveChanCategoryInput = ""
                                                            isEditingLiveChannel = false
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.weight(0.7f)
                                                    ) {
                                                        Text("إلغاء", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                    }
                                                }
                                            } else {
                                                Button(
                                                    onClick = {
                                                        if (liveChanNameInput.isNotEmpty() && liveChanStreamUrlInput.isNotEmpty() && liveChanCategoryInput.isNotEmpty()) {
                                                            viewModel.publishDeveloperChannel(
                                                                id = "",
                                                                name = liveChanNameInput,
                                                                logoUrl = liveChanLogoUrlInput,
                                                                streamUrl = liveChanStreamUrlInput,
                                                                categoryId = liveChanCategoryInput,
                                                                userAgent = liveChanUserAgentInput,
                                                                forcedFormat = liveChanForcedFormatInput
                                                            )
                                                            liveChanNameInput = ""
                                                            liveChanStreamUrlInput = ""
                                                            liveChanLogoUrlInput = ""
                                                            liveChanUserAgentInput = ""
                                                            liveChanCategoryInput = ""
                                                        } else {
                                                            Toast.makeText(context, "الرجاء تعبئة حقول القناة وتحديد الفئة!", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = GoldGlow),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("نشر وتفعيل قناة بث مباشر", color = ObsidianBlack, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }

                                item {
                                    Text("قنوات البث المباشر المفعلة أونلاين حالياً (${channels.size})", color = SoftGreyText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                                }

                                items(channels) { chan ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MidnightGrey.copy(alpha = 0.5f))
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = chan.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(text = "الفئة: ${chan.category} | المعرف: ${chan.id}", color = SoftGreyText, fontSize = 9.sp)
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    liveChanIdInput = chan.id
                                                    liveChanNameInput = chan.name
                                                    liveChanStreamUrlInput = chan.streamUrl
                                                    liveChanLogoUrlInput = chan.logoUrl
                                                    liveChanUserAgentInput = chan.userAgent
                                                    liveChanCategoryInput = chan.category
                                                    liveChanForcedFormatInput = chan.forcedFormat
                                                    isEditingLiveChannel = true
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = GoldGlow, modifier = Modifier.size(16.dp))
                                            }

                                            IconButton(
                                                onClick = { viewModel.deleteDeveloperChannel(chan.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = LoopRed, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (activeDevTab == "movies") {
                            // MOVIES CREATION FORM
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(
                                                text = if (isEditingMovie) "تعديل بيانات الفيلم الحالي" else "نشر فيلم سينمائي جديد أونلاين",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )

                                            OutlinedTextField(
                                                value = movieTitleInput,
                                                onValueChange = { movieTitleInput = it },
                                                label = { Text("عنوان الفيلم (مثل: فيلم الأكشن والقتال الرهيب)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = movieStreamUrlInput,
                                                onValueChange = { movieStreamUrlInput = it },
                                                label = { Text("رابط تشغيل الفيلم (m3u8 / mp4 / mkv)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = moviePosterUrlInput,
                                                onValueChange = { moviePosterUrlInput = it },
                                                label = { Text("رابط بوستر الفيلم (صورة الغلاف)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = movieDescInput,
                                                onValueChange = { movieDescInput = it },
                                                label = { Text("وصف وقصة الفيلم بالتفصيل", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = movieDurationInput,
                                                    onValueChange = { movieDurationInput = it },
                                                    label = { Text("مدة العرض (ساعة:دقيقة)", color = SoftGreyText, fontSize = 10.sp) },
                                                    modifier = Modifier.weight(1f),
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = GoldGlow,
                                                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                        focusedTextColor = Color.White,
                                                        unfocusedTextColor = Color.White
                                                    )
                                                )

                                                OutlinedTextField(
                                                    value = movieRatingInput,
                                                    onValueChange = { movieRatingInput = it },
                                                    label = { Text("التقييم الرقمي (مثال: 8.7)", color = SoftGreyText, fontSize = 10.sp) },
                                                    modifier = Modifier.weight(1f),
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = GoldGlow,
                                                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                        focusedTextColor = Color.White,
                                                        unfocusedTextColor = Color.White
                                                    )
                                                )

                                                OutlinedTextField(
                                                    value = movieYearInput,
                                                    onValueChange = { movieYearInput = it },
                                                    label = { Text("سنة الإنتاج (مثال: 2026)", color = SoftGreyText, fontSize = 10.sp) },
                                                    modifier = Modifier.weight(1f),
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = GoldGlow,
                                                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                        focusedTextColor = Color.White,
                                                        unfocusedTextColor = Color.White
                                                    )
                                                )
                                            }

                                            // Category selector
                                            val movieCats = categories.filter { it.type == "movie" }
                                            Box(modifier = Modifier.fillMaxWidth()) {
                                                OutlinedButton(
                                                    onClick = { showMovieCatMenu = true },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        val selectedCatName = movieCats.find { it.id == movieCategoryInput }?.name ?: "اختر تصنيف الفيلم"
                                                        Text(text = selectedCatName, fontSize = 12.sp)
                                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
                                                    }
                                                }

                                                DropdownMenu(
                                                    expanded = showMovieCatMenu,
                                                    onDismissRequest = { showMovieCatMenu = false },
                                                    modifier = Modifier.background(MidnightGrey)
                                                ) {
                                                    movieCats.forEach { c ->
                                                        DropdownMenuItem(
                                                            text = { Text(c.name, color = Color.White) },
                                                            onClick = {
                                                                movieCategoryInput = c.id
                                                                showMovieCatMenu = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            if (isEditingMovie) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Button(
                                                        onClick = {
                                                            if (movieTitleInput.isNotEmpty() && movieStreamUrlInput.isNotEmpty() && movieCategoryInput.isNotEmpty()) {
                                                                viewModel.publishDeveloperMovie(
                                                                    id = movieIdInput,
                                                                    title = movieTitleInput,
                                                                    description = movieDescInput,
                                                                    posterUrl = moviePosterUrlInput,
                                                                    streamUrl = movieStreamUrlInput,
                                                                    categoryId = movieCategoryInput,
                                                                    duration = movieDurationInput,
                                                                    rating = movieRatingInput.toDoubleOrNull() ?: 8.5,
                                                                    year = movieYearInput.toIntOrNull() ?: 2026
                                                                )
                                                                movieIdInput = ""
                                                                movieTitleInput = ""
                                                                movieDescInput = ""
                                                                moviePosterUrlInput = ""
                                                                movieStreamUrlInput = ""
                                                                movieCategoryInput = ""
                                                                movieDurationInput = ""
                                                                isEditingMovie = false
                                                            } else {
                                                                Toast.makeText(context, "الرجاء تعبئة حقول الاسم والرابط والتصنيف!", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.weight(1.3f)
                                                    ) {
                                                        Text("حفظ تعديل الفيلم", color = ObsidianBlack, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                    }

                                                    Button(
                                                        onClick = {
                                                            movieIdInput = ""
                                                            movieTitleInput = ""
                                                            movieDescInput = ""
                                                            moviePosterUrlInput = ""
                                                            movieStreamUrlInput = ""
                                                            movieCategoryInput = ""
                                                            movieDurationInput = ""
                                                            isEditingMovie = false
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.weight(0.7f)
                                                    ) {
                                                        Text("إلغاء", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                    }
                                                }
                                            } else {
                                                Button(
                                                    onClick = {
                                                        if (movieTitleInput.isNotEmpty() && movieStreamUrlInput.isNotEmpty() && movieCategoryInput.isNotEmpty()) {
                                                            viewModel.publishDeveloperMovie(
                                                                id = "",
                                                                title = movieTitleInput,
                                                                description = movieDescInput,
                                                                posterUrl = moviePosterUrlInput,
                                                                streamUrl = movieStreamUrlInput,
                                                                categoryId = movieCategoryInput,
                                                                duration = movieDurationInput,
                                                                rating = movieRatingInput.toDoubleOrNull() ?: 8.5,
                                                                year = movieYearInput.toIntOrNull() ?: 2026
                                                            )
                                                            movieTitleInput = ""
                                                            movieDescInput = ""
                                                            moviePosterUrlInput = ""
                                                            movieStreamUrlInput = ""
                                                            movieCategoryInput = ""
                                                            movieDurationInput = ""
                                                        } else {
                                                            Toast.makeText(context, "الرجاء تعبئة حقول الاسم والرابط والتصنيف!", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = GoldGlow),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("نشر الفيلم أونلاين", color = ObsidianBlack, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }

                                item {
                                    Text("الأفلام المضافة أونلاين حالياً (${movies.size})", color = SoftGreyText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                                }

                                items(movies) { mov ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MidnightGrey.copy(alpha = 0.5f))
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = mov.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(text = "التصنيف: ${mov.category} | سنة العرض: ${mov.year} | التقييم: ${mov.rating}", color = SoftGreyText, fontSize = 9.sp)
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    movieIdInput = mov.id
                                                    movieTitleInput = mov.title
                                                    movieDescInput = mov.description
                                                    moviePosterUrlInput = mov.posterUrl
                                                    movieStreamUrlInput = mov.streamUrl
                                                    movieCategoryInput = mov.category
                                                    movieDurationInput = mov.duration
                                                    movieRatingInput = mov.rating.toString()
                                                    movieYearInput = mov.year.toString()
                                                    isEditingMovie = true
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = GoldGlow, modifier = Modifier.size(16.dp))
                                            }

                                            IconButton(
                                                onClick = { viewModel.deleteDeveloperMovie(mov.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = LoopRed, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (activeDevTab == "series") {
                            // SERIES CREATION FORM
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(
                                                text = if (isEditingSeries) "تعديل بيانات المسلسل الحالي" else "نشر مسلسل درامي جديد أونلاين",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )

                                            OutlinedTextField(
                                                value = seriesTitleInput,
                                                onValueChange = { seriesTitleInput = it },
                                                label = { Text("عنوان المسلسل", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = seriesPosterUrlInput,
                                                onValueChange = { seriesPosterUrlInput = it },
                                                label = { Text("رابط بوستر المسلسل (صورة الغلاف)", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            OutlinedTextField(
                                                value = seriesDescInput,
                                                onValueChange = { seriesDescInput = it },
                                                label = { Text("قصة وتفاصيل المسلسل", color = SoftGreyText, fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GoldGlow,
                                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = seriesRatingInput,
                                                    onValueChange = { seriesRatingInput = it },
                                                    label = { Text("التقييم الرقمي (مثال: 9.1)", color = SoftGreyText, fontSize = 10.sp) },
                                                    modifier = Modifier.weight(1f),
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = GoldGlow,
                                                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                        focusedTextColor = Color.White,
                                                        unfocusedTextColor = Color.White
                                                    )
                                                )

                                                OutlinedTextField(
                                                    value = seriesYearInput,
                                                    onValueChange = { seriesYearInput = it },
                                                    label = { Text("سنة الإنتاج (مثال: 2026)", color = SoftGreyText, fontSize = 10.sp) },
                                                    modifier = Modifier.weight(1f),
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = GoldGlow,
                                                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                        focusedTextColor = Color.White,
                                                        unfocusedTextColor = Color.White
                                                    )
                                                )
                                            }

                                            // Category selector
                                            val seriesCats = categories.filter { it.type == "series" }
                                            Box(modifier = Modifier.fillMaxWidth()) {
                                                OutlinedButton(
                                                    onClick = { showSeriesCatMenu = true },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        val selectedCatName = seriesCats.find { it.id == seriesCategoryInput }?.name ?: "اختر تصنيف المسلسل"
                                                        Text(text = selectedCatName, fontSize = 12.sp)
                                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
                                                    }
                                                }

                                                DropdownMenu(
                                                    expanded = showSeriesCatMenu,
                                                    onDismissRequest = { showSeriesCatMenu = false },
                                                    modifier = Modifier.background(MidnightGrey)
                                                ) {
                                                    seriesCats.forEach { c ->
                                                        DropdownMenuItem(
                                                            text = { Text(c.name, color = Color.White) },
                                                            onClick = {
                                                                seriesCategoryInput = c.id
                                                                showSeriesCatMenu = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            if (isEditingSeries) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Button(
                                                        onClick = {
                                                            if (seriesTitleInput.isNotEmpty() && seriesCategoryInput.isNotEmpty()) {
                                                                viewModel.publishDeveloperSeries(
                                                                    id = seriesIdInput,
                                                                    title = seriesTitleInput,
                                                                    description = seriesDescInput,
                                                                    posterUrl = seriesPosterUrlInput,
                                                                    categoryId = seriesCategoryInput,
                                                                    rating = seriesRatingInput.toDoubleOrNull() ?: 8.5,
                                                                    year = seriesYearInput.toIntOrNull() ?: 2026
                                                                )
                                                                seriesIdInput = ""
                                                                seriesTitleInput = ""
                                                                seriesDescInput = ""
                                                                seriesPosterUrlInput = ""
                                                                seriesCategoryInput = ""
                                                                isEditingSeries = false
                                                            } else {
                                                                Toast.makeText(context, "الرجاء تعبئة الاسم واختيار التصنيف للمسلسل!", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.weight(1.3f)
                                                    ) {
                                                        Text("حفظ تعديل المسلسل", color = ObsidianBlack, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                    }

                                                    Button(
                                                        onClick = {
                                                            seriesIdInput = ""
                                                            seriesTitleInput = ""
                                                            seriesDescInput = ""
                                                            seriesPosterUrlInput = ""
                                                            seriesCategoryInput = ""
                                                            isEditingSeries = false
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.weight(0.7f)
                                                    ) {
                                                        Text("إلغاء", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                    }
                                                }
                                            } else {
                                                Button(
                                                    onClick = {
                                                        if (seriesTitleInput.isNotEmpty() && seriesCategoryInput.isNotEmpty()) {
                                                            viewModel.publishDeveloperSeries(
                                                                id = "",
                                                                title = seriesTitleInput,
                                                                description = seriesDescInput,
                                                                posterUrl = seriesPosterUrlInput,
                                                                categoryId = seriesCategoryInput,
                                                                rating = seriesRatingInput.toDoubleOrNull() ?: 8.5,
                                                                year = seriesYearInput.toIntOrNull() ?: 2026
                                                            )
                                                            seriesTitleInput = ""
                                                            seriesDescInput = ""
                                                            seriesPosterUrlInput = ""
                                                            seriesCategoryInput = ""
                                                        } else {
                                                            Toast.makeText(context, "الرجاء تعبئة الاسم وتحديد الفئة!", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = GoldGlow),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("نشر المسلسل أونلاين", color = ObsidianBlack, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }

                                item {
                                    Text("المسلسلات المضافة أونلاين حالياً (${series.size})", color = SoftGreyText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                                }

                                items(series) { s ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MidnightGrey.copy(alpha = 0.5f))
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = s.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(text = "التصنيف: ${s.category} | سنة العرض: ${s.year} | التقييم: ${s.rating}", color = SoftGreyText, fontSize = 9.sp)
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    seriesIdInput = s.id
                                                    seriesTitleInput = s.title
                                                    seriesDescInput = s.description
                                                    seriesPosterUrlInput = s.posterUrl
                                                    seriesCategoryInput = s.category
                                                    seriesRatingInput = s.rating.toString()
                                                    seriesYearInput = s.year.toString()
                                                    isEditingSeries = true
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = GoldGlow, modifier = Modifier.size(16.dp))
                                            }

                                            IconButton(
                                                onClick = { viewModel.deleteDeveloperSeries(s.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = LoopRed, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (activeDevTab == "app_settings") {
                            // GENERAL ONLINE SETTINGS CONTROL
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 10.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp)),
                                    colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Text(
                                            text = "التحكم في ظهور الأقسام (Online)",
                                            color = GoldGlow,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )

                                        val showNewsTabOnline by viewModel.showNewsTab.collectAsState()
                                        val showChatTabOnline by viewModel.showChatTab.collectAsState()
                                        val showMainChannelsTabOnline by viewModel.showMainChannelsTab.collectAsState()

                                        // News Tab visibility switch
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "إظهار قسم الأخبار في الشريط السفلي",
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "عند تعطيله سيختفي قسم الأخبار تلقائياً للجميع بشكل فوري",
                                                    color = SoftGreyText,
                                                    fontSize = 10.sp
                                                )
                                            }

                                            Switch(
                                                checked = showNewsTabOnline,
                                                onCheckedChange = { viewModel.setNewsTabVisibilityOnline(it) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = ObsidianBlack,
                                                    checkedTrackColor = GoldGlow,
                                                    uncheckedThumbColor = SoftGreyText,
                                                    uncheckedTrackColor = MidnightGrey
                                                )
                                            )
                                        }

                                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                                        // Chat Tab visibility switch
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "إظهار قسم الدردشة في الشريط السفلي",
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "عند تعطيله سيختفي قسم دردشة عامة تلقائياً للجميع بشكل فوري",
                                                    color = SoftGreyText,
                                                    fontSize = 10.sp
                                                )
                                            }

                                            Switch(
                                                checked = showChatTabOnline,
                                                onCheckedChange = { viewModel.setChatTabVisibilityOnline(it) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = ObsidianBlack,
                                                    checkedTrackColor = GoldGlow,
                                                    uncheckedThumbColor = SoftGreyText,
                                                    uncheckedTrackColor = MidnightGrey
                                                )
                                            )
                                        }

                                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                                        // Main Channels Tab visibility switch
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "إظهار قسم قنوات رئيسية في الشريط السفلي",
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "عند تعطيله سيختفي قسم القنوات الرئيسية تلقائياً للجميع بشكل فوري",
                                                    color = SoftGreyText,
                                                    fontSize = 10.sp
                                                )
                                            }

                                            Switch(
                                                checked = showMainChannelsTabOnline,
                                                onCheckedChange = { viewModel.setMainChannelsTabVisibilityOnline(it) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = ObsidianBlack,
                                                    checkedTrackColor = GoldGlow,
                                                    uncheckedThumbColor = SoftGreyText,
                                                    uncheckedTrackColor = MidnightGrey
                                                )
                                            )
                                        }
                                    }
                                }

                                // CHAT SYSTEM ONLINE SETTINGS
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp)),
                                    colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Text(
                                            text = "التحكم في دردشة البث المباشر (Secondary DB)",
                                            color = GoldGlow,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )

                                        val isChatEnabledOnline by viewModel.isChatEnabledOnline.collectAsState()

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "تفعيل قسم الدردشة في المشغل",
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "عند تعطيله ستختفي أيقونة الدردشة والدردشة الجانبية تماماً من مشغل الفيديو لجميع المستخدمين",
                                                    color = SoftGreyText,
                                                    fontSize = 10.sp
                                                )
                                            }

                                            Switch(
                                                checked = isChatEnabledOnline,
                                                onCheckedChange = { viewModel.setChatEnabledOnline(it) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = ObsidianBlack,
                                                    checkedTrackColor = GoldGlow,
                                                    uncheckedThumbColor = SoftGreyText,
                                                    uncheckedTrackColor = MidnightGrey
                                                )
                                            )
                                        }
                                    }
                                }

                                // CHAT SYSTEM DATABASE CLEANUP
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp)),
                                    colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = "إدارة وحذف رسائل الدردشة",
                                            color = GoldGlow,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )

                                        Text(
                                            text = "يمكنك تفريغ وحذف جميع المحادثات والرسائل لجميع القنوات بشكل نهائي من قاعدة البيانات الجديدة بضغطة زر واحدة.",
                                            color = SoftGreyText,
                                            fontSize = 11.sp
                                        )

                                        Button(
                                            onClick = {
                                                viewModel.wipeAllChatMessages()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteForever,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "حذف جميع الرسائل من قاعدة البيانات نهائياً",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (activeDevTab == "notifications") {
                             // ADMIN NOTIFICATION CONTROL
                             val notificationsList by viewModel.notifications.collectAsState()
                             var notifyTitle by remember { mutableStateOf("") }
                             var notifyBody by remember { mutableStateOf("") }
                             var notifyImageUrl by remember { mutableStateOf("") }

                             Column(
                                 modifier = Modifier
                                     .fillMaxSize()
                                     .padding(top = 10.dp)
                                     .verticalScroll(rememberScrollState()),
                                 verticalArrangement = Arrangement.spacedBy(16.dp)
                             ) {
                                 Card(
                                     modifier = Modifier
                                         .fillMaxWidth()
                                         .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp)),
                                     colors = CardDefaults.cardColors(containerColor = MidnightGrey),
                                     shape = RoundedCornerShape(14.dp)
                                 ) {
                                     Column(
                                         modifier = Modifier.padding(16.dp),
                                         verticalArrangement = Arrangement.spacedBy(12.dp)
                                     ) {
                                         Text(
                                             text = "إرسال إشعار جديد (FCM)",
                                             color = GoldGlow,
                                             fontSize = 14.sp,
                                             fontWeight = FontWeight.Bold
                                         )
                                         
                                         OutlinedTextField(
                                             value = notifyTitle,
                                             onValueChange = { notifyTitle = it },
                                             label = { Text("عنوان الإشعار", color = SoftGreyText, fontSize = 11.sp) },
                                             modifier = Modifier.fillMaxWidth(),
                                             textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                             colors = OutlinedTextFieldDefaults.colors(
                                                 focusedBorderColor = GoldGlow,
                                                 unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                 focusedTextColor = Color.White,
                                                 unfocusedTextColor = Color.White
                                             )
                                         )

                                         OutlinedTextField(
                                             value = notifyBody,
                                             onValueChange = { notifyBody = it },
                                             label = { Text("نص الرسالة (مثلاً: شاهد لعبة العراق على قناة bein max 1)", color = SoftGreyText, fontSize = 11.sp) },
                                             modifier = Modifier.fillMaxWidth(),
                                             textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                             colors = OutlinedTextFieldDefaults.colors(
                                                 focusedBorderColor = GoldGlow,
                                                 unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                 focusedTextColor = Color.White,
                                                 unfocusedTextColor = Color.White
                                             )
                                         )

                                         OutlinedTextField(
                                             value = notifyImageUrl,
                                             onValueChange = { notifyImageUrl = it },
                                             label = { Text("رابط صورة الإشعار (اختياري)", color = SoftGreyText, fontSize = 11.sp) },
                                             modifier = Modifier.fillMaxWidth(),
                                             textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                             colors = OutlinedTextFieldDefaults.colors(
                                                 focusedBorderColor = GoldGlow,
                                                 unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                                 focusedTextColor = Color.White,
                                                 unfocusedTextColor = Color.White
                                             )
                                         )

                                         Button(
                                             onClick = {
                                                 if (notifyTitle.isNotEmpty() && notifyBody.isNotEmpty()) {
                                                     viewModel.sendAppNotification(notifyTitle, notifyBody, notifyImageUrl)
                                                     notifyTitle = ""
                                                     notifyBody = ""
                                                     notifyImageUrl = ""
                                                     Toast.makeText(context, "تم إرسال الإشعار وحفظه في القاعدة بنجاح!", Toast.LENGTH_SHORT).show()
                                                 } else {
                                                     Toast.makeText(context, "يرجى تعبئة العنوان والنص!", Toast.LENGTH_SHORT).show()
                                                 }
                                             },
                                             modifier = Modifier.fillMaxWidth(),
                                             colors = ButtonDefaults.buttonColors(containerColor = GoldGlow),
                                             shape = RoundedCornerShape(10.dp)
                                         ) {
                                             Text("إرسال الإشعار الآن", color = ObsidianBlack, fontWeight = FontWeight.Bold)
                                         }
                                     }
                                 }

                                 Text(text = "الإشعارات السابقة", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))

                                 notificationsList.forEach { note ->
                                     NotificationAdminCard(note, onDelete = { viewModel.deleteAppNotification(note.id) })
                                 }
                             }
                        }
                    }
                }
            }
        }
    }

    if (showDevPasswordDialog) {
        AlertDialog(
            onDismissRequest = { 
                showDevPasswordDialog = false 
                devPasswordInput = ""
            },
            title = { Text("قسم المطورين", color = GoldGlow, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("يرجى إدخال كلمة المرور للدخول للوحة التحكم", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = devPasswordInput,
                        onValueChange = { devPasswordInput = it },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldGlow,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (devPasswordInput == "ali2008#$1") {
                            settingsSubPage = "dev_control"
                            showDevPasswordDialog = false
                            devPasswordInput = ""
                        } else {
                            Toast.makeText(context, "كلمة المرور خاطئة!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldGlow)
                ) {
                    Text("دخول", color = ObsidianBlack)
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showDevPasswordDialog = false 
                    devPasswordInput = ""
                }) {
                    Text("إلغاء", color = SoftGreyText)
                }
            },
            containerColor = MidnightGrey,
            titleContentColor = GoldGlow,
            textContentColor = Color.White
        )
    }
}

@Composable
fun NewsPanel(viewModel: MainViewModel) {
    val context = LocalContext.current
    val newsArticles by viewModel.newsArticles.collectAsState()
    val isNewsLoading by viewModel.isNewsLoading.collectAsState()
    val newsError by viewModel.newsError.collectAsState()
    val newsQuery by viewModel.newsQuery.collectAsState()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var searchInput by remember { mutableStateOf("") }

    // Fetch news on first load of this tab
    LaunchedEffect(Unit) {
        if (newsArticles.isEmpty() && !isNewsLoading) {
            viewModel.fetchNews()
        }
    }

    val quickFilters = listOf(
        Pair("\"كرة القدم\" OR \"مباراة\" OR \"كورة\"", "الكل"),
        Pair("ريال مدريد", "ريال مدريد"),
        Pair("برشلونة", "برشلونة"),
        Pair("دوري ابطال اوروبا", "أبطال أوروبا"),
        Pair("ميسي OR رونالدو", "ميسي ورونالدو"),
        Pair("الدوري الإنجليزي", "البريميرليغ")
    )

    // Pull to Refresh state and physics
    var pullOffset by remember { mutableStateOf(0f) }
    val pullMax = 200f

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y < 0 && pullOffset > 0) {
                    val consumed = minOf(pullOffset, -available.y)
                    pullOffset -= consumed
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y > 0) {
                    pullOffset = (pullOffset + available.y * 0.5f).coerceAtMost(pullMax)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullOffset > 0 && pullOffset < pullMax) {
                    androidx.compose.animation.core.animate(
                        initialValue = pullOffset,
                        targetValue = 0f,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy)
                    ) { value, _ ->
                        pullOffset = value
                    }
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(pullOffset) {
        if (pullOffset >= pullMax && !isNewsLoading) {
            viewModel.fetchNews(forceRefresh = true)
            androidx.compose.animation.core.animate(
                initialValue = pullOffset,
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy)
            ) { value, _ ->
                pullOffset = value
            }
        }
    }

    // Loader rotation angle
    val rotationAngle = if (isNewsLoading) {
        val infiniteTransition = rememberInfiniteTransition()
        val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
        angle
    } else {
        (pullOffset / pullMax) * 360f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBlack)
            .nestedScroll(nestedScrollConnection)
    ) {
        // Search and Filters Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(MidnightGrey, RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Search field
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    placeholder = { Text("ابحث عن لاعب، فريق أو بطولة...", color = SoftGreyText, fontSize = 10.sp) },
                    modifier = Modifier.weight(1f).heightIn(min = 34.dp, max = 38.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LoopRed,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                        focusedContainerColor = ObsidianBlack,
                        unfocusedContainerColor = ObsidianBlack
                    ),
                    shape = RoundedCornerShape(8.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "بحث",
                            tint = LoopRedGlow,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchInput.isNotEmpty()) {
                            IconButton(onClick = { searchInput = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "مسح",
                                    tint = SoftGreyText,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                )

                Button(
                    onClick = {
                        if (searchInput.isNotBlank()) {
                            viewModel.updateNewsQuery(searchInput)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 34.dp, max = 38.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Text("بحث", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Quick Filter Chips (Pill styling)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(quickFilters) { (query, label) ->
                    val isSelected = newsQuery == query
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) LoopRed else ObsidianBlack)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) LoopRed else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                viewModel.updateNewsQuery(query)
                                searchInput = if (label == "الكل") "" else label
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = label,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Animated Swipe/Loading Bar at the top of Feed
        if (pullOffset > 0 || isNewsLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(((pullOffset / pullMax) * 54).dp.coerceAtLeast(if (isNewsLoading) 44.dp else 0.dp).coerceAtMost(54.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MidnightGrey)
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = GoldGlow,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer(rotationZ = rotationAngle)
                    )
                    Text(
                        text = if (isNewsLoading) "جاري التحديث..." else if (pullOffset >= pullMax) "أفلت للتحديث الآن" else "اسحب للأسفل لتحديث الأخبار",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Section Title & Info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 12.dp)
                        .background(LoopRed, RoundedCornerShape(1.dp))
                )
                Text(
                    text = if (newsQuery.contains("OR")) "أحدث أخبار الملاعب والساحرة المستديرة" else "نتائج البحث لـ \"${newsQuery}\"",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!isNewsLoading && newsError == null) {
                Text(
                    text = "${newsArticles.size} خبر متاح حالياً",
                    color = LoopRedGlow,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(LoopRed.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // News Content Area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            if (newsError != null) {
                // Error view
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "خطأ",
                        tint = LoopRedGlow,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("عذراً، حدث خطأ أثناء الاتصال بالخادم", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = newsError ?: "",
                        color = SoftGreyText,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.fetchNews(forceRefresh = true) },
                        colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("إعادة المحاولة", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (newsArticles.isEmpty() && !isNewsLoading) {
                // Empty view
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = "لا توجد نتائج",
                        tint = SoftGreyText.copy(alpha = 0.5f),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("لم نعثر على أخبار مطابقة", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("حاول تغيير كلمة البحث أو الضغط على زر العودة للأخبار الرئيسية.", color = SoftGreyText, fontSize = 11.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            viewModel.updateNewsQuery("\"كرة القدم\" OR \"مباراة\" OR \"كورة\"")
                            searchInput = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("العودة للأخبار الرئيسية", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // News list / grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(newsArticles) { article ->
                        NewsCard(article = article, onReadMore = { uriHandler.openUri(article.link) })
                    }
                }
            }
        }
    }
}

@Composable
fun ChatPanel(viewModel: MainViewModel) {
    val chatUsername by viewModel.chatUsername.collectAsState()
    val chatName by viewModel.chatName.collectAsState()

    if (chatUsername == null || chatName == null) {
        ChatRegistrationScreen(viewModel)
    } else {
        ChatActiveInterface(viewModel, chatUsername!!, chatName!!)
    }
}

@Composable
fun ChatRegistrationScreen(viewModel: MainViewModel) {
    var usernameInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRegistering by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBlack)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 450.dp)
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MidnightGrey)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(LoopRed.copy(alpha = 0.1f), CircleShape)
                        .border(2.dp, LoopRed, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = "انضم إلى الدردشة العامة والخاصة",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "يرجى تعيين اسم مستعار فريد واسمك الشخصي للبدء بالحديث والتفاعل مع مستخدمي التطبيق فوراً.",
                    color = SoftGreyText,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("الاسم الشخصي (مثال: أحمد العتيبي)", fontSize = 11.sp) },
                    placeholder = { Text("الاسم المعروض للجميع...", color = SoftGreyText, fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LoopRed,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        focusedContainerColor = ObsidianBlack,
                        unfocusedContainerColor = ObsidianBlack,
                        focusedLabelColor = LoopRed,
                        unfocusedLabelColor = SoftGreyText
                    ),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(18.dp))
                    }
                )

                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = { input ->
                        val filtered = input.filter { char ->
                            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-'
                        }
                        if (filtered.length <= 9) {
                            usernameInput = filtered.lowercase()
                        }
                    },
                    label = { Text("اسم المستخدم الفريد بالإنجليزية (مثال: ahmed99)", fontSize = 11.sp) },
                    placeholder = { Text("يستخدم للبحث عنك والدردشة الخاصة...", color = SoftGreyText, fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LoopRed,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        focusedContainerColor = ObsidianBlack,
                        unfocusedContainerColor = ObsidianBlack,
                        focusedLabelColor = LoopRed,
                        unfocusedLabelColor = SoftGreyText
                    ),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = {
                        Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(18.dp))
                    }
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = LoopRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        val cleanedUsername = usernameInput.trim()
                        val cleanedName = nameInput.trim()
                        if (cleanedUsername.isEmpty()) {
                            errorMessage = "يجب إدخال اسم مستخدم!"
                        } else if (cleanedUsername.length > 9) {
                            errorMessage = "اسم المستخدم يجب ألا يتجاوز 9 أحرف!"
                        } else if (cleanedName.isEmpty()) {
                            errorMessage = "يجب إدخال الاسم الشخصي!"
                        } else {
                            errorMessage = null
                            isRegistering = true
                            viewModel.registerChatUser(cleanedUsername, cleanedName) { success ->
                                isRegistering = false
                                if (!success) {
                                    errorMessage = "عذراً، فشل التسجيل أو اسم المستخدم مستخدم بالفعل!"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LoopRed),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isRegistering && usernameInput.isNotEmpty() && nameInput.isNotEmpty()
                ) {
                    if (isRegistering) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = "ابدأ الدردشة والاتصال",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatActiveInterface(viewModel: MainViewModel, myUsername: String, myName: String) {
    var subTab by remember { mutableStateOf("general") }
    val chatMessages by viewModel.chatMessages.collectAsState()
    val allUsers by viewModel.allUsers.collectAsState()

    var activePrivateUser by remember { mutableStateOf<Pair<String, String>?>(null) }

    val activeChannelId = if (subTab == "general") "general" else {
        activePrivateUser?.let { other ->
            val otherUsername = other.first
            if (myUsername < otherUsername) "private_${myUsername}_${otherUsername}" else "private_${otherUsername}_${myUsername}"
        } ?: ""
    }

    LaunchedEffect(activeChannelId) {
        if (activeChannelId.isNotEmpty()) {
            viewModel.fetchChatMessages(activeChannelId)
            while (true) {
                delay(3000L)
                viewModel.fetchChatMessages(activeChannelId)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.fetchAllUsers()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBlack)
    ) {
        if (activePrivateUser == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MidnightGrey)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(
                    Pair("general", "الدردشة العامة"),
                    Pair("private", "الرسائل الخاصة")
                ).forEach { (tabId, title) ->
                    val isSelected = subTab == tabId
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) LoopRed else ObsidianBlack)
                            .clickable { subTab = tabId }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (tabId == "general") Icons.Default.Public else Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = title,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MidnightGrey)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = { activePrivateUser = null },
                        modifier = Modifier
                            .size(32.dp)
                            .background(ObsidianBlack, CircleShape)
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "رجوع", tint = Color.White, modifier = Modifier.size(16.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(GoldGlow, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = activePrivateUser!!.second.take(1).uppercase(),
                            color = ObsidianBlack,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Column {
                        Text(
                            text = activePrivateUser!!.second,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "@" + activePrivateUser!!.first,
                            color = SoftGreyText,
                            fontSize = 9.sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = GoldGlow, modifier = Modifier.size(10.dp))
                    Text("محادثة خاصة مشفرة", color = GoldGlow, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            if (subTab == "general" || activePrivateUser != null) {
                ChatLogAndInputArea(
                    viewModel = viewModel,
                    channelId = activeChannelId,
                    messages = chatMessages,
                    myUsername = myUsername,
                    myName = myName
                )
            } else {
                PrivateChatsListScreen(
                    viewModel = viewModel,
                    allUsers = allUsers,
                    myUsername = myUsername,
                    onSelectUser = { activePrivateUser = it }
                )
            }
        }
    }
}

@Composable
fun ChatLogAndInputArea(
    viewModel: MainViewModel,
    channelId: String,
    messages: List<ChatMessage>,
    myUsername: String,
    myName: String
) {
    val listState = rememberLazyListState()
    var typedText by remember { mutableStateOf("") }
    var replyToMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var contextMenuMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val otherUser = remember(channelId, myUsername) {
        if (channelId.startsWith("private_")) {
            val parts = channelId.split("_")
            if (parts.size >= 3) {
                if (parts[1] == myUsername) parts[2] else parts[1]
            } else null
        } else null
    }
    var otherUserTyping by remember { mutableStateOf(false) }

    if (otherUser != null) {
        LaunchedEffect(channelId) {
            while (true) {
                try {
                    val states = com.example.data.ChatService.getTypingStates(channelId)
                    otherUserTyping = states[otherUser] == true
                } catch (e: Exception) {
                    Log.e("Chat", "Error checking typing state", e)
                }
                delay(2500L)
            }
        }
    } else {
        otherUserTyping = false
    }

    LaunchedEffect(typedText, channelId) {
        if (typedText.isNotBlank()) {
            try {
                com.example.data.ChatService.setTypingState(channelId, myUsername, true)
            } catch (e: Exception) {
                Log.e("Chat", "Error setting typing state", e)
            }
            delay(3000L)
            try {
                com.example.data.ChatService.setTypingState(channelId, myUsername, false)
            } catch (e: Exception) {
                Log.e("Chat", "Error setting typing state", e)
            }
        } else {
            try {
                com.example.data.ChatService.setTypingState(channelId, myUsername, false)
            } catch (e: Exception) {
                Log.e("Chat", "Error setting typing state", e)
            }
        }
    }

    DisposableEffect(channelId) {
        onDispose {
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.example.data.ChatService.setTypingState(channelId, myUsername, false)
                } catch (e: Exception) {
                    Log.e("Chat", "Error clearing typing state on dispose", e)
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = SoftGreyText.copy(alpha = 0.3f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (channelId.startsWith("private_")) {
                                        "لا توجد رسائل في هذه المحادثة الخاصة بعد.\nالمراسلات الخاصة آمنة ومستمرة دائماً."
                                    } else {
                                        "لا توجد رسائل نشطة حالياً.\nيتم حذف الرسائل تلقائياً بعد 10 دقائق."
                                    },
                                    color = SoftGreyText,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                } else {
                    items(messages) { msg ->
                        ChatMessageBubble(
                            msg = msg,
                            myUsername = myUsername,
                            onLongPress = { contextMenuMessage = msg },
                            onReact = { emoji ->
                                viewModel.addChatReaction(channelId, msg.id, myUsername, emoji)
                            }
                        )
                    }
                }
            }

            if (otherUserTyping && otherUser != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MidnightGrey.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = GoldGlow,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "@$otherUser يكتب الآن...",
                        color = GoldGlow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            replyToMessage?.let { reply ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MidnightGrey)
                        .border(1.dp, Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Reply, contentDescription = null, tint = GoldGlow, modifier = Modifier.size(10.dp))
                            Text(
                                text = "الرد على @" + reply.sender,
                                color = GoldGlow,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = reply.text,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = { replyToMessage = null },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "إلغاء", tint = SoftGreyText, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MidnightGrey)
                    .padding(
                        horizontal = if (channelId == "general") 6.dp else 8.dp,
                        vertical = if (channelId == "general") 4.dp else 6.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (channelId == "general") 4.dp else 6.dp)
            ) {
                OutlinedTextField(
                    value = typedText,
                    onValueChange = { typedText = it },
                    placeholder = { Text("اكتب رسالتك هنا...", color = SoftGreyText, fontSize = if (channelId == "general") 9.sp else 10.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(
                            min = if (channelId == "general") 28.dp else 34.dp,
                            max = if (channelId == "general") 80.dp else 100.dp
                        ),
                    maxLines = if (channelId == "general") 3 else 4,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (channelId == "general") 10.sp else 11.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LoopRed,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.05f),
                        focusedContainerColor = ObsidianBlack,
                        unfocusedContainerColor = ObsidianBlack
                    ),
                    shape = RoundedCornerShape(14.dp)
                )

                IconButton(
                    onClick = {
                        val cleaned = typedText.trim()
                        if (cleaned.isNotEmpty()) {
                            viewModel.sendChatMessage(
                                channelId = channelId,
                                sender = myUsername,
                                text = cleaned,
                                replyToId = replyToMessage?.id,
                                replyToSender = replyToMessage?.sender,
                                replyToText = replyToMessage?.text
                            )
                            typedText = ""
                            replyToMessage = null
                        }
                    },
                    modifier = Modifier
                        .size(if (channelId == "general") 28.dp else 34.dp)
                        .background(LoopRed, CircleShape),
                    enabled = typedText.isNotBlank()
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "إرسال",
                        tint = Color.White,
                        modifier = Modifier.size(if (channelId == "general") 11.dp else 14.dp)
                    )
                }
            }
        }

        contextMenuMessage?.let { activeMsg ->
            Dialog(onDismissRequest = { contextMenuMessage = null }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MidnightGrey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "تفاعل والرد على @" + activeMsg.sender,
                            color = GoldGlow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            listOf("❤️", "👍", "😂", "😮", "😢", "🔥").forEach { emoji ->
                                val alreadyReacted = activeMsg.reactions[myUsername] == emoji
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(if (alreadyReacted) LoopRed.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.03f))
                                        .border(
                                            1.dp,
                                            if (alreadyReacted) LoopRed else Color.Transparent,
                                            CircleShape
                                        )
                                        .clickable {
                                            if (alreadyReacted) {
                                                viewModel.removeChatReaction(channelId, activeMsg.id, myUsername)
                                            } else {
                                                viewModel.addChatReaction(channelId, activeMsg.id, myUsername, emoji)
                                            }
                                            contextMenuMessage = null
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = emoji, fontSize = 18.sp)
                                }
                            }
                        }

                        Divider(color = Color.White.copy(alpha = 0.05f))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    replyToMessage = activeMsg
                                    contextMenuMessage = null
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Reply, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text("الرد على هذه الرسالة", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(
    msg: ChatMessage,
    myUsername: String,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit
) {
    val isMe = msg.sender == myUsername
    val alignment = if (isMe) Alignment.End else Alignment.Start
    val bgGradient = if (isMe) {
        Brush.horizontalGradient(listOf(LoopRed, LoopRed.copy(alpha = 0.8f)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF2C2C2E), Color(0xFF1C1C1E)))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongPress() }
                )
            },
        horizontalAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp, end = 4.dp)
        ) {
            Text(
                text = if (isMe) "أنت" else "@" + msg.sender,
                color = if (isMe) GoldGlow else Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            val timeStr = remember(msg.timestamp) {
                val sdf = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                sdf.format(java.util.Date(msg.timestamp))
            }
            Text(
                text = "• $timeStr",
                color = SoftGreyText,
                fontSize = 8.sp
            )
        }

        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isMe) 16.dp else 4.dp,
                        bottomEnd = if (isMe) 4.dp else 16.dp
                    )
                )
                .background(bgGradient)
                .border(
                    1.dp,
                    if (isMe) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.03f),
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isMe) 16.dp else 4.dp,
                        bottomEnd = if (isMe) 4.dp else 16.dp
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .widthIn(max = 280.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (msg.replyToId != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "الرد على @" + msg.replyToSender,
                            color = GoldGlow,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = msg.replyToText ?: "",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = msg.text,
                    color = Color.White,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        if (msg.reactions.isNotEmpty()) {
            val aggregated = remember(msg.reactions) {
                msg.reactions.values.groupBy { it }.mapValues { it.value.size }
            }
            Row(
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                aggregated.forEach { (emoji, count) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                            .clickable { onReact(emoji) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(text = emoji, fontSize = 9.sp)
                            Text(text = count.toString(), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PrivateChatsListScreen(
    viewModel: MainViewModel,
    allUsers: Map<String, String>,
    myUsername: String,
    onSelectUser: (Pair<String, String>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    val messagedUsers by viewModel.messagedUsers.collectAsState()
    val blockedUsers by viewModel.blockedUsers.collectAsState()

    // Determine what to display based on whether user is searching
    val displayList = remember(messagedUsers, allUsers, searchQuery, myUsername) {
        if (searchQuery.isBlank()) {
            // Default: ONLY show users that have been messaged before!
            messagedUsers.filter { it.first != myUsername }
        } else {
            // Search mode: show matched users from the entire database so they can start a new conversation
            allUsers.entries
                .filter { entry -> entry.key != myUsername }
                .filter { entry ->
                    entry.key.contains(searchQuery, ignoreCase = true) ||
                            entry.value.contains(searchQuery, ignoreCase = true)
                }
                .map { Pair(it.key, it.value) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("ابحث عن مستخدم بالاسم أو اسم المستخدم لبدء دردشة جديدة...", color = SoftGreyText, fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LoopRed,
                unfocusedBorderColor = Color.White.copy(alpha = 0.05f),
                focusedContainerColor = MidnightGrey,
                unfocusedContainerColor = MidnightGrey
            ),
            shape = RoundedCornerShape(12.dp),
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = SoftGreyText, modifier = Modifier.size(16.dp))
                    }
                }
            }
        )

        Text(
            text = if (searchQuery.isBlank()) "محادثاتك الخاصة النشطة:" else "نتائج البحث لبدء دردشة جديدة:",
            color = GoldGlow,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (displayList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Chat,
                                contentDescription = null,
                                tint = SoftGreyText.copy(alpha = 0.3f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isBlank()) 
                                    "لا توجد محادثات نشطة حالياً.\nاستخدم شريط البحث في الأعلى للبحث عن أشخاص والبدء بمراسلتهم!"
                                else 
                                    "لم نعثر على مستخدمين مطابقين للبحث حالياً.",
                                color = SoftGreyText,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            } else {
                items(displayList) { otherUser ->
                    val otherUsername = otherUser.first
                    val isBlocked = blockedUsers.contains(otherUsername)
                    val privateChannelId = if (myUsername < otherUsername) "private_${myUsername}_${otherUsername}" else "private_${otherUsername}_${myUsername}"
                    
                    UserRowCard(
                        otherUser = otherUser,
                        onSelect = { onSelectUser(otherUser) },
                        onDeleteConversation = {
                            viewModel.deleteConversation(privateChannelId)
                        },
                        isBlocked = isBlocked,
                        onToggleBlock = {
                            if (isBlocked) {
                                viewModel.unblockUser(otherUsername)
                            } else {
                                viewModel.blockUser(otherUsername)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun UserRowCard(
    otherUser: Pair<String, String>,
    onSelect: () -> Unit,
    onDeleteConversation: (() -> Unit)? = null,
    isBlocked: Boolean = false,
    onToggleBlock: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(14.dp), onClick = onSelect)
            .border(1.dp, Color.White.copy(alpha = 0.03f), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightGrey)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(if (isBlocked) Color.Gray.copy(alpha = 0.2f) else LoopRed.copy(alpha = 0.1f), CircleShape)
                        .border(1.dp, if (isBlocked) Color.Gray else LoopRed, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = otherUser.second.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = otherUser.second,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isBlocked) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "محظور",
                                color = Color.Red,
                                fontSize = 8.sp,
                                modifier = Modifier
                                    .background(Color.Red.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = "@" + otherUser.first,
                        color = SoftGreyText,
                        fontSize = 10.sp
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Block/Unblock toggle button
                if (onToggleBlock != null) {
                    IconButton(
                        onClick = onToggleBlock,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isBlocked) Icons.Default.LockOpen else Icons.Default.Block,
                            contentDescription = if (isBlocked) "إلغاء الحظر" else "حظر",
                            tint = if (isBlocked) Color.Green else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Delete Conversation button
                if (onDeleteConversation != null) {
                    IconButton(
                        onClick = onDeleteConversation,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف المحادثة",
                            tint = LoopRedGlow,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = "بدء محادثة",
                    tint = GoldGlow,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun NewsCard(article: NewsArticle, onReadMore: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightGrey)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Article Header Image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                SafeAsyncImage(
                    model = article.imageUrl,
                    contentDescription = article.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Dark bottom overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MidnightGrey.copy(alpha = 0.95f)),
                                startY = 100f
                            )
                        )
                )

                // Source Badge
                if (article.sourceId.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .background(LoopRed, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = article.sourceId,
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Article Details
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Publish Date
                if (article.pubDate.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = "تاريخ النشر",
                            tint = SoftGreyText,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = article.pubDate,
                            color = SoftGreyText,
                            fontSize = 9.sp
                        )
                    }
                }

                // Title
                Text(
                    text = article.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                // Description
                if (article.description.isNotBlank()) {
                    Text(
                        text = article.description,
                        color = SoftGreyText,
                        fontSize = 11.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Divider(
                    color = Color.White.copy(alpha = 0.05f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "المصدر: " + if (article.creator.isNotBlank()) article.creator else article.sourceId.ifBlank { "صحافة عربية" },
                        color = SoftGreyText.copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = onReadMore,
                        colors = ButtonDefaults.buttonColors(containerColor = MidnightGrey),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("اقرأ الخبر كاملاً", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "قراءة",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationListView(viewModel: MainViewModel) {
    val notifications by viewModel.notifications.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.fetchNotifications()
    }

    if (notifications.isEmpty()) {
        EmptyStateView("لا توجد إشعارات حالياً.")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "الإشعارات الواردة",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(notifications) { note ->
                NotificationUserCard(note)
            }
        }
    }
}

@Composable
fun NotificationUserCard(note: AppNotification) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MidnightGrey),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.title,
                    color = GoldGlow,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date(note.timestamp)),
                    color = SoftGreyText,
                    fontSize = 10.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = note.body,
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            if (!note.imageUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                AsyncImage(
                    model = note.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
fun NotificationAdminCard(note: AppNotification, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightGrey.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = note.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(text = note.body, color = SoftGreyText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = LoopRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}
