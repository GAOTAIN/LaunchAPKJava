package com.launchapk.java.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTokensTest {
    @Test
    fun readableTextInBothThemesAndTransparencyModes() {
        for (dark in listOf(false, true)) {
            for (reduced in listOf(false, true)) {
                val tokens = glassTokens(dark, reduced)
                for (background in listOf(tokens.backgroundTop, tokens.backgroundBottom)) {
                    val card = tokens.card.compositeOver(background)
                    val highlighted = tokens.reflection.compositeOver(card)
                    for (surface in listOf(background, card, highlighted)) {
                        assertTrue("Primary text contrast", contrast(tokens.primaryText, surface) >= 4.5f)
                        assertTrue("Secondary text contrast", contrast(tokens.secondaryText, surface) >= 4.5f)
                    }
                }
                assertTrue("Button text contrast", contrast(tokens.onAccent, tokens.accent) >= 4.5f)
            }
        }
    }

    @Test
    fun reducedTransparencyMakesCardsNearlyOpaque() {
        for (dark in listOf(false, true)) {
            val regular = glassTokens(dark, false)
            val reduced = glassTokens(dark, true)
            assertTrue(reduced.card.alpha >= 0.95f)
            assertTrue(reduced.card.alpha > regular.card.alpha)
        }
    }

    @Test
    fun blurRespectsApiLevelAndBothPerformanceSwitches() {
        val regular = GlassPreferences()
        assertFalse(isGlassBlurEnabled(26, regular))
        assertFalse(isGlassBlurEnabled(30, regular))
        assertTrue(isGlassBlurEnabled(31, regular))
        assertFalse(isGlassBlurEnabled(35, regular.copy(blurEnabled = false)))
        assertFalse(isGlassBlurEnabled(35, regular.copy(reduceTransparency = true)))
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }
}
