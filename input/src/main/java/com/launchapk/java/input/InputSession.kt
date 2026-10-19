package com.launchapk.java.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.sqrt

data class StickPosition(val x: Float, val y: Float)
data class InputVisualState(val activeIds: Set<String> = emptySet(), val sticks: Map<String, StickPosition> = emptyMap())

// Single UI-thread owner. Aggregate held keys across all fingers/controls so one
// finger releasing W cannot release W still held by another finger or joystick.
class InputSession(private val bridge: InputBridge) {
    private data class Touch(val controlId: String?, val codes: Set<Int> = emptySet(),
        val stick: StickPosition? = null, val look: StickPosition? = null)
    private val touches = mutableMapOf<Int, Touch>()
    private val visual = MutableStateFlow(InputVisualState())
    val state = visual.asStateFlow()

    private fun held(): Set<Int> = touches.values.flatMap { it.codes }.toSet()
    private fun send(code: Int, pressed: Boolean) {
        if (code == -1 || code == -2) bridge.sendMouseButton(-code - 1, pressed)
        else bridge.sendKey(code, pressed)
    }
    private fun mutate(block: () -> Unit) {
        val before = held()
        block()
        val after = held()
        (before - after).sorted().forEach { send(it, false) }
        (after - before).sorted().forEach { send(it, true) }
        visual.value = InputVisualState(touches.values.mapNotNull { it.controlId }.toSet(),
            touches.values.filter { it.stick != null }.associate { it.controlId!! to it.stick!! })
    }

    fun pressButton(id: Int, button: ControlButton) {
        release(id)
        mutate { touches[id] = Touch(button.id, setOf(button.keyCode)) }
    }
    fun pressJoystick(id: Int, joystick: ControlJoystick, x: Float, y: Float) {
        release(id)
        mutate { touches[id] = Touch(joystick.id, stick = StickPosition(0f, 0f)) }
        moveJoystick(id, joystick, x, y)
    }
    fun moveJoystick(id: Int, joystick: ControlJoystick, x: Float, y: Float) {
        if (touches[id]?.controlId != joystick.id || touches[id]?.stick == null) return
        val divisor = max(1f, sqrt(x * x + y * y))
        val position = StickPosition(x / divisor, y / divisor)
        val keys = buildSet {
            if (position.x < -.25f) add(65)
            if (position.x > .25f) add(68)
            if (position.y < -.25f) add(87)
            if (position.y > .25f) add(83)
        }
        mutate { touches[id] = Touch(joystick.id, keys, stick = position) }
    }
    fun beginLook(id: Int, x: Float, y: Float) {
        release(id)
        touches[id] = Touch(null, look = StickPosition(x, y))
        bridge.sendPointer(id, x, y, PointerAction.DOWN)
    }
    fun moveLook(id: Int, x: Float, y: Float) {
        val previous = touches[id]?.look ?: return
        if (previous.x == x && previous.y == y) return
        touches[id] = Touch(null, look = StickPosition(x, y))
        bridge.sendPointer(id, x, y, PointerAction.MOVE)
    }
    fun release(id: Int, cancelled: Boolean = false) {
        val touch = touches[id] ?: return
        touch.look?.let { bridge.sendPointer(id, it.x, it.y, if (cancelled) PointerAction.CANCEL else PointerAction.UP) }
        mutate { touches.remove(id) }
    }
    fun releaseAll() { touches.keys.toList().forEach { release(it, cancelled = true) } }
}
