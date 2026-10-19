package com.launchapk.java.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LocalGlassTokens = staticCompositionLocalOf { glassTokens(false, false) }
val LocalGlassPreferences = staticCompositionLocalOf { GlassPreferences() }

@Composable
fun GlassTheme(
    preferences: GlassPreferences,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = remember(darkTheme, preferences.reduceTransparency) {
        glassTokens(darkTheme, preferences.reduceTransparency)
    }
    val scheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(
        LocalGlassTokens provides tokens,
        LocalGlassPreferences provides preferences,
    ) {
        MaterialTheme(
            colorScheme = scheme.copy(
                primary = tokens.accent,
                onPrimary = tokens.onAccent,
                secondary = tokens.accent,
                background = tokens.backgroundTop,
                onBackground = tokens.primaryText,
                surface = tokens.card.copy(alpha = 1f),
                onSurface = tokens.primaryText,
                onSurfaceVariant = tokens.secondaryText,
            ),
            content = content,
        )
    }
}

@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val blurEnabled = isGlassBlurEnabled(Build.VERSION.SDK_INT, LocalGlassPreferences.current)
    Box(
        modifier = modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(tokens.backgroundTop, tokens.backgroundBottom)),
        ),
    ) {
        // Only decoration is blurred. Never blur the content, labels, or touch targets.
        val decoration = Modifier.matchParentSize().then(
            if (blurEnabled) Modifier.blur(28.dp, BlurredEdgeTreatment.Unbounded) else Modifier,
        )
        Canvas(modifier = decoration) {
            val radius = size.minDimension * 0.75f
            val center = Offset(size.width * 0.85f, size.height * 0.24f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(tokens.highlight.copy(alpha = 0.12f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        content()
    }
}
