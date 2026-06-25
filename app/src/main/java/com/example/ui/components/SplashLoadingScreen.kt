package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LoopRed
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.SoftGreyText
import kotlinx.coroutines.delay

@Composable
fun SplashLoadingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("جاري استدعاء قنوات البث الحية...") }

    // Smoothly animate progress from 0.0 to 1.0 over 2 seconds
    LaunchedEffect(Unit) {
        val duration = 2000L
        val steps = 100
        val delayPerStep = duration / steps
        for (i in 1..steps) {
            delay(delayPerStep)
            progress = i / 100f
            when {
                i < 30 -> statusText = "جاري تهيئة البوابة المشفرة الآمنة..."
                i < 65 -> statusText = "تحميل مكتبة الأقسام والوسائط الذكية..."
                i < 90 -> statusText = "تنشيط مشغل الفيديو عالي الدقة (U_H_D)..."
                else -> statusText = "مرحباً بك! تفتح الواجهة الفاخرة..."
            }
        }
        delay(150)
        onFinished()
    }

    // Logo pulsing scale
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_splash")
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_scale"
    )

    // Spin circle rotation
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rotation"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBlack),
        contentAlignment = Alignment.Center
    ) {
        // High quality faint red ambient background spotlight glow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(LoopRed.copy(alpha = 0.15f), Color.Transparent),
                        radius = 1200f
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            // Animated Premium Logo Emblem
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .graphicsLayer(scaleX = logoScale, scaleY = logoScale),
                contentAlignment = Alignment.Center
            ) {
                // outer spinning glowing arc
                Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(rotationZ = rotation)) {
                    drawArc(
                        color = LoopRed,
                        startAngle = 0f,
                        sweepAngle = 280f,
                        useCenter = false,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.1f),
                        startAngle = 280f,
                        sweepAngle = 80f,
                        useCenter = false,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Inner secure logo container
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(1.dp, LoopRed.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircleFilled,
                        contentDescription = "Logo",
                        tint = LoopRed,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // App branding name
            Text(
                text = "LOOP LIVE IPTV",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text = "نظام البث الذكي فائق السرعة",
                color = SoftGreyText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Percentage center-piece
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(30.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = LoopRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Professional Horizontal Loading slider track
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.06f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(LoopRed.copy(alpha = 0.6f), LoopRed)
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status message text
            Text(
                text = statusText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Secure Shield indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.alpha(0.5f)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = SoftGreyText,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "تشفير سحابي آمن",
                    color = SoftGreyText,
                    fontSize = 9.sp
                )
            }
        }
    }
}
