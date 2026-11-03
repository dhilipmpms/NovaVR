/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

object NovaColors {
    val Background = Color(0xFF0B0D12)
    val Surface = Color(0xFF141821)
    val SurfaceHigh = Color(0xFF1C2130)
    val Outline = Color(0xFF2A3142)
    val Accent = Color(0xFF6C8CFF)
    val AccentDim = Color(0xFF2B3766)
    val TextPrimary = Color(0xFFE8ECF4)
    val TextSecondary = Color(0xFF8A94A8)
    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFF87171)
}

private val ColorScheme = darkColorScheme(
    primary = NovaColors.Accent,
    onPrimary = Color(0xFF0B0D12),
    primaryContainer = NovaColors.AccentDim,
    onPrimaryContainer = NovaColors.TextPrimary,
    secondary = NovaColors.Accent,
    onSecondary = Color(0xFF0B0D12),
    background = NovaColors.Background,
    onBackground = NovaColors.TextPrimary,
    surface = NovaColors.Surface,
    onSurface = NovaColors.TextPrimary,
    surfaceVariant = NovaColors.SurfaceHigh,
    onSurfaceVariant = NovaColors.TextSecondary,
    surfaceContainer = NovaColors.Surface,
    surfaceContainerHigh = NovaColors.SurfaceHigh,
    surfaceContainerHighest = NovaColors.SurfaceHigh,
    outline = NovaColors.Outline,
    outlineVariant = NovaColors.Outline,
    error = NovaColors.Error,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
    ),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun NovaVRTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
