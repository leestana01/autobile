package com.autobile.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6B57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F1DC),
    onPrimaryContainer = Color(0xFF002118),
    secondary = Color(0xFF4C635B),
    background = Color(0xFFF7F7F2),
    surface = Color(0xFFFFFBF6),
    surfaceVariant = Color(0xFFE7ECE7),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD7C3),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005140),
    secondary = Color(0xFFB3CCC1),
    background = Color(0xFF101413),
    surface = Color(0xFF181C1A),
    surfaceVariant = Color(0xFF3F4945),
)

@Composable
fun AutobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
