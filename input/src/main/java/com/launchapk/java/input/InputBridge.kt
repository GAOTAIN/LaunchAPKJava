package com.launchapk.java.input

interface InputBridge {
    fun sendKey(keyCode: Int, pressed: Boolean)
    fun sendPointer(id: Int, x: Float, y: Float, action: Int)
    fun sendMouseButton(button: Int, pressed: Boolean)
}

object PointerAction {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val CANCEL = 3
}
