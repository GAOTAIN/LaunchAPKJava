package com.launchapk.java.input

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.launchapk.java.ui.GlassCard
import com.launchapk.java.ui.LocalGlassTokens
import kotlin.math.roundToInt

@Composable
fun ControlOverlay(
    layout: ControlLayout,
    editing: Boolean,
    session: InputSession,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onLayoutChange: (ControlLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestLayout by rememberUpdatedState(layout)
    val latestChange by rememberUpdatedState(onLayoutChange)
    val latestSelect by rememberUpdatedState(onSelect)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var editingPressed by remember { mutableStateOf(emptySet<String>()) }
    val visual by session.state.collectAsState()
    val density = LocalDensity.current
    val tokens = LocalGlassTokens.current
    DisposableEffect(session) { onDispose { session.releaseAll() } }
    Box(
        modifier = modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(editing, size, session) {
                if (size.width == 0 || size.height == 0) return@pointerInput
                val viewport = ControlViewport(size.width.toFloat(), size.height.toFloat())
                val targets = mutableMapOf<Int, String>()
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            var working = latestLayout
                            event.changes.forEach { change ->
                                val id = change.id.value.toInt()
                                val position = change.position
                                if (change.pressed && !change.previousPressed && !change.isConsumed) {
                                    val current = working
                                    val button = current.buttons.asReversed().firstOrNull {
                                        position.x >= it.x * size.width && position.x <= (it.x + it.width) * size.width &&
                                            position.y >= it.y * size.height && position.y <= (it.y + it.height) * size.height
                                    }
                                    val stick = if (button == null) current.joysticks.asReversed().firstOrNull {
                                        (position - stickCenter(it, viewport)).getDistance() <= it.radius * viewport.shortEdge
                                    } else null
                                    val target = button?.id ?: stick?.id
                                    if (target != null) targets[id] = target
                                    if (editing) latestSelect(target)
                                    else when {
                                        button != null -> session.pressButton(id, button)
                                        stick != null -> {
                                            val delta = (position - stickCenter(stick, viewport)) / (stick.radius * viewport.shortEdge)
                                            session.pressJoystick(id, stick, delta.x, delta.y)
                                        }
                                        else -> session.beginLook(id, position.x, position.y)
                                    }
                                }
                                if (change.pressed && change.previousPressed) {
                                    val delta = position - change.previousPosition
                                    if (editing) {
                                        targets[id]?.let { target ->
                                            working = working.moveControl(target, delta.x / size.width, delta.y / size.height, viewport)
                                            latestChange(working)
                                        }
                                    } else {
                                        latestLayout.joysticks.firstOrNull { it.id == targets[id] }?.let { stick ->
                                            val vector = (position - stickCenter(stick, viewport)) / (stick.radius * viewport.shortEdge)
                                            session.moveJoystick(id, stick, vector.x, vector.y)
                                        }
                                        session.moveLook(id, position.x, position.y)
                                    }
                                }
                                if (!change.pressed) {
                                    if (!editing) {
                                        session.moveLook(id, position.x, position.y)
                                        session.release(id)
                                    }
                                    targets.remove(id)
                                }
                                if (change.pressed || change.previousPressed) change.consume()
                            }
                            editingPressed = if (editing) targets.values.toSet() else emptySet()
                        }
                    }
                } finally {
                    session.releaseAll()
                    editingPressed = emptySet()
                }
            },
    ) {
        if (size.width > 0 && size.height > 0) {
            val viewport = ControlViewport(size.width.toFloat(), size.height.toFloat())
            layout.joysticks.forEach { joystick ->
                val radius = joystick.radius * viewport.shortEdge
                val center = stickCenter(joystick, viewport)
                val pressed = joystick.id in visual.activeIds || joystick.id in editingPressed
                val position = visual.sticks[joystick.id] ?: StickPosition(0f, 0f)
                GlassCard(
                    modifier = Modifier.offset { IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()) }
                        .size(with(density) { (radius * 2).toDp() })
                        .graphicsLayer { scaleX = if (pressed) .97f else 1f; scaleY = scaleX },
                    active = pressed || selectedId == joystick.id && editing,
                    shape = CircleShape,
                    contentPadding = PaddingValues(),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(tokens.secondaryText.copy(alpha = .22f), radius = this.size.minDimension * .35f)
                        drawCircle(if (pressed) tokens.accent else tokens.primaryText.copy(alpha = .55f),
                            radius = this.size.minDimension * .17f,
                            center = this.center + Offset(position.x, position.y) * (this.size.minDimension * .25f))
                    }
                }
            }
            layout.buttons.forEach { button ->
                val pressed = button.id in visual.activeIds || button.id in editingPressed
                val scale by animateFloatAsState(if (pressed) .97f else 1f, label = "controlPress")
                GlassCard(
                    modifier = Modifier.offset { IntOffset((button.x * size.width).roundToInt(), (button.y * size.height).roundToInt()) }
                        .size(with(density) { (button.width * size.width).toDp() }, with(density) { (button.height * size.height).toDp() })
                        .graphicsLayer { alpha = button.alpha; scaleX = scale; scaleY = scale },
                    active = pressed || selectedId == button.id && editing,
                    contentPadding = PaddingValues(),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(button.label, color = tokens.primaryText, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun stickCenter(stick: ControlJoystick, viewport: ControlViewport): Offset {
    val radius = stick.radius * viewport.shortEdge
    return Offset((stick.x * viewport.width).coerceIn(radius, viewport.width - radius),
        (stick.y * viewport.height).coerceIn(radius, viewport.height - radius))
}
