package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = LoopRed,
    secondary = GoldGlow,
    tertiary = LoopRedGlow,
    background = ObsidianBlack,
    surface = MidnightGrey,
    onPrimary = CleanWhite,
    onSecondary = ObsidianBlack,
    onBackground = CleanWhite,
    onSurface = CleanWhite
)

@Composable
fun MyApplicationTheme(
    themeName: String = "red",
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeName) {
        "gold" -> darkColorScheme(
            primary = Color(0xFFFFD700),
            secondary = Color(0xFFFFC107),
            tertiary = Color(0xFFFFE082),
            background = Color(0xFF070707),
            surface = Color(0xFF141414),
            onPrimary = Color(0xFF070707),
            onSecondary = Color(0xFF070707),
            onBackground = Color.White,
            onSurface = Color.White
        )
        "purple" -> darkColorScheme(
            primary = Color(0xFF9D4EDD),
            secondary = Color(0xFFC77DFF),
            tertiary = Color(0xFFE0AAFF),
            background = Color(0xFF05030B),
            surface = Color(0xFF120E1F),
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        )
        "emerald" -> darkColorScheme(
            primary = Color(0xFF00C9A7),
            secondary = Color(0xFF4D96FF),
            tertiary = Color(0xFF38ef7d),
            background = Color(0xFF030A09),
            surface = Color(0xFF0E1A17),
            onPrimary = Color(0xFF030A09),
            onSecondary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        )
        "blue" -> darkColorScheme(
            primary = Color(0xFF007BFF),
            secondary = Color(0xFF00D2FF),
            tertiary = Color(0xFF3333FF),
            background = Color(0xFF020710),
            surface = Color(0xFF0B1323),
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        )
        else -> DarkColorScheme // Classic Red
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
