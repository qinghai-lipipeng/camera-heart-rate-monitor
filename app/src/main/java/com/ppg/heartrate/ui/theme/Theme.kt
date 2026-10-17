package com.ppg.heartrate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 深色配色。测量时手指压住屏幕，深色背景可降低屏幕自身漏光，
 * 减少外界光线经手指边缘缝隙射入镜头造成的信号干扰。
 */
private val PpgColors = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF00391A),
    secondary = Color(0xFF7DD3FC),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF121821),
    onSurface = Color(0xFFE5E7EB),
    error = Color(0xFFF87171)
)

/**
 * 应用主题。固定深色，不跟随系统：浅色主题下的白色界面会明显影响指尖透光信号。
 */
@Composable
fun PpgHeartRateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PpgColors,
        typography = Typography(),
        content = content
    )
}