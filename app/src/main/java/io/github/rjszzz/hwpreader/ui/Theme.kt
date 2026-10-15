package io.github.rjszzz.hwpreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 흰 바탕, 시스템 글꼴, 강조색 1개 (PRD 9장 디자인 원칙)
private val Accent = Color(0xFF2F5BD3)
private val AccentDark = Color(0xFF8FB0FF)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFE3E9FA),
    background = Color.White,
    surface = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF4F4F6),
)

private val Dark = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF0E1A3D),
    secondaryContainer = Color(0xFF26324F),
    background = Color(0xFF121214),
    surface = Color(0xFF121214),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1C1C1F),
)

@Composable
fun HwpReaderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
