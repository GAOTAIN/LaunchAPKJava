package com.launchapk.java.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class GlassPreferences(
    val blurEnabled: Boolean = true,
    val reduceTransparency: Boolean = false,
)

@Immutable
data class GlassTokens(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val card: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val onAccent: Color,
    val highlight: Color,
    val reflection: Color,
    val shadow: Color,
)

fun glassTokens(darkTheme: Boolean, reduceTransparency: Boolean): GlassTokens =
    if (darkTheme) {
        GlassTokens(
            backgroundTop = Color.Black,
            backgroundBottom = Color(0xFF18181C),
            card = Color.Black.copy(alpha = if (reduceTransparency) 0.96f else 0.55f),
            primaryText = Color(0xFFF5F5F7),
            secondaryText = Color(0xFFA1A1A6),
            accent = Color(0xFF0A84FF),
            onAccent = Color(0xFF071321),
            highlight = Color.White.copy(alpha = 0.24f),
            reflection = Color.White.copy(alpha = if (reduceTransparency) 0.02f else 0.05f),
            shadow = Color.Black.copy(alpha = 0.20f),
        )
    } else {
        GlassTokens(
            backgroundTop = Color.White,
            backgroundBottom = Color(0xFFEDEEF2),
            card = Color.White.copy(alpha = if (reduceTransparency) 0.96f else 0.68f),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF6A6A70),
            accent = Color(0xFF0066D6),
            onAccent = Color.White,
            highlight = Color.White.copy(alpha = 0.92f),
            reflection = Color.White.copy(alpha = if (reduceTransparency) 0.04f else 0.28f),
            shadow = Color(0xFF6E788A).copy(alpha = 0.10f),
        )
    }

// Keep the API gate independent of Android so fallback behavior can be unit tested.
fun isGlassBlurEnabled(apiLevel: Int, preferences: GlassPreferences): Boolean =
    apiLevel >= 31 && preferences.blurEnabled && !preferences.reduceTransparency
