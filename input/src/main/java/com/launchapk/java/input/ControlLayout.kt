package com.launchapk.java.input

import kotlin.math.min

// Positions and button dimensions are fractions of the viewport. Joystick radius
// is a fraction of its shorter edge; joystick x/y describe its center.
data class ControlLayout(
    val schemaVersion: Int = 1,
    val buttons: List<ControlButton> = emptyList(),
    val joysticks: List<ControlJoystick> = emptyList(),
)
data class ControlButton(
    val id: String, val label: String, val keyCode: Int,
    val x: Float, val y: Float, val width: Float, val height: Float, val alpha: Float = 0.85f,
)
data class ControlJoystick(val id: String, val x: Float, val y: Float, val radius: Float)

data class ControlViewport(val width: Float, val height: Float) {
    init { require(width > 0 && height > 0) }
    val shortEdge: Float get() = min(width, height)
}

fun ControlLayout.validated(): ControlLayout {
    require(schemaVersion == 1) { "不支持的布局版本" }
    require(buttons.size <= 64 && joysticks.size <= 8) { "控件数量超出限制" }
    val ids = buttons.map { it.id } + joysticks.map { it.id }
    require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }) { "控件 ID 无效或重复" }
    buttons.forEach {
        require(it.label.isNotBlank() && it.label.length <= 32) { "按键名称无效" }
        require(it.keyCode in 0..348 || it.keyCode in -2..-1) { "键值无效（鼠标左键 -1，右键 -2）" }
        require(listOf(it.x, it.y, it.width, it.height, it.alpha).all(Float::isFinite)) { "按键坐标必须为有限数值" }
        require(it.width in 0.04f..0.5f && it.height in 0.06f..0.5f && it.alpha in 0.15f..1f) { "按键大小或透明度无效" }
        require(it.x >= 0f && it.y >= 0f && it.x + it.width <= 1.00001f && it.y + it.height <= 1.00001f) { "按键超出画布" }
    }
    joysticks.forEach {
        require(listOf(it.x, it.y, it.radius).all(Float::isFinite)) { "摇杆坐标必须为有限数值" }
        require(it.x in 0f..1f && it.y in 0f..1f && it.radius in 0.06f..0.3f) { "摇杆位置或大小无效" }
    }
    return this
}

fun ControlLayout.moveControl(id: String, dx: Float, dy: Float, viewport: ControlViewport): ControlLayout = copy(
    buttons = buttons.map { if (it.id == id) it.copy(x = (it.x + dx).coerceIn(0f, 1f - it.width), y = (it.y + dy).coerceIn(0f, 1f - it.height)) else it },
    joysticks = joysticks.map {
        if (it.id != id) it else {
            val rx = it.radius * viewport.shortEdge / viewport.width
            val ry = it.radius * viewport.shortEdge / viewport.height
            it.copy(x = (it.x + dx).coerceIn(rx, 1f - rx), y = (it.y + dy).coerceIn(ry, 1f - ry))
        }
    },
)

fun presetControlLayout(): ControlLayout = ControlLayout(
    buttons = listOf(
        ControlButton("w", "W", 87, .09f, .19f, .08f, .14f),
        ControlButton("a", "A", 65, .01f, .35f, .08f, .14f),
        ControlButton("s", "S", 83, .10f, .35f, .08f, .14f),
        ControlButton("d", "D", 68, .19f, .35f, .08f, .14f),
        ControlButton("space", "空格", 32, .78f, .74f, .18f, .17f),
        ControlButton("shift", "Shift", 340, .62f, .79f, .13f, .13f),
        ControlButton("left", "左键", -1, .76f, .38f, .10f, .18f),
        ControlButton("right", "右键", -2, .88f, .38f, .10f, .18f),
        ControlButton("escape", "Esc", 256, .86f, .16f, .10f, .13f),
    ),
    joysticks = listOf(ControlJoystick("move", .16f, .75f, .16f)),
).validated()
