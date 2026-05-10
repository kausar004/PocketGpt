package com.example.pocketgpt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = DarkGreen,
    secondary = AccentTeal,
    tertiary = SoftPurple,
    background = Background,
    surface = CardBackground,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun PocketGptTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}