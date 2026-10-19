package com.launchapk.java.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    shape: Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val onePixel = with(LocalDensity.current) { 1f.toDp() }
    Box(
        modifier = modifier
            .shadow(16.dp, shape, clip = false, ambientColor = tokens.shadow, spotColor = tokens.shadow)
            .clip(shape)
            .background(tokens.card)
            .background(Brush.verticalGradient(listOf(if (active) tokens.reflection.copy(alpha = 0.30f) else tokens.reflection, Color.Transparent)))
            .border(BorderStroke(onePixel, Brush.verticalGradient(if (active) listOf(tokens.accent, tokens.accent) else listOf(tokens.highlight, Color.Transparent))), shape),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = true,
) {
    val tokens = LocalGlassTokens.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(120),
        label = "glassButtonPress",
    )
    val shape = RoundedCornerShape(percent = 50)
    val fill = when {
        !enabled -> tokens.secondaryText.copy(alpha = 0.10f)
        emphasized -> tokens.accent
        else -> tokens.card
    }
    val contentColor = when {
        !enabled -> tokens.secondaryText
        emphasized -> tokens.onAccent
        else -> tokens.primaryText
    }
    val onePixel = with(LocalDensity.current) { 1f.toDp() }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(shape)
            .background(fill)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (pressed) 0.18f else 0.06f), Color.Transparent)))
            .border(
                BorderStroke(onePixel, Brush.verticalGradient(listOf(if (pressed) tokens.accent else tokens.highlight, Color.Transparent))),
                shape,
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactions,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun GlassDialog(
    title: String,
    message: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            Text(title, color = tokens.primaryText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(message, color = tokens.secondaryText, style = MaterialTheme.typography.bodyMedium)
            GlassButton(text = dismissLabel, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun GlassReveal(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.98f),
        exit = fadeOut(tween(120)),
    ) { content() }
}
