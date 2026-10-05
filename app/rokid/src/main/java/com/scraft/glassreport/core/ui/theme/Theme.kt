package com.scraft.glassreport.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val GlassColorScheme =
    darkColorScheme(
        primary = GlassGreen,
        onPrimary = GlassBlack,
        secondary = GlassGreenDim,
        background = GlassBlack,
        onBackground = GlassGreen,
        surface = GlassBlack,
        onSurface = GlassGreen,
    )

// 低解像度・単色のグラス向けに、大きめ・高コントラストの字面を既定にする。
private val GlassTypography =
    Typography(
        displayLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
        headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
        bodyLarge = TextStyle(fontSize = 20.sp),
        bodyMedium = TextStyle(fontSize = 16.sp),
    )

@Composable
fun ScraftGlassTheme(content: @Composable () -> Unit) {
    // グラスは単色ディスプレイのため常時ダーク固定。
    MaterialTheme(
        colorScheme = GlassColorScheme,
        typography = GlassTypography,
        content = content,
    )
}
