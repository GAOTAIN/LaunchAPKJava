package com.launchapk.java.input

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LogcatInputBridge : InputBridge {
    private val message = MutableStateFlow("等待触摸；日志标签 LaunchAPK.Input")
    val lastEvent = message.asStateFlow()
    private fun emit(value: String) {
        message.value = value
        Log.i("LaunchAPK.Input", value)
    }
    override fun sendKey(keyCode: Int, pressed: Boolean) = emit("KEY code=$keyCode pressed=$pressed")
    override fun sendMouseButton(button: Int, pressed: Boolean) = emit("MOUSE button=$button pressed=$pressed")
    override fun sendPointer(id: Int, x: Float, y: Float, action: Int) = emit("POINTER id=$id x=$x y=$y action=$action")
}
