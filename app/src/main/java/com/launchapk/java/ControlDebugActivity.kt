package com.launchapk.java

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.launchapk.java.input.ControlDebugScreen
import com.launchapk.java.input.InputSession
import com.launchapk.java.input.LogcatInputBridge
import com.launchapk.java.ui.GlassTheme

class ControlDebugActivity : ComponentActivity() {
    private val bridge = LogcatInputBridge()
    private val session = InputSession(bridge)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val preferences = AppearancePreferences(this).load()
        setContent {
            val event by bridge.lastEvent.collectAsState()
            GlassTheme(preferences) { ControlDebugScreen(session, event, onExit = { finish() }) }
        }
    }
    override fun onPause() {
        session.releaseAll()
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) session.releaseAll()
    }
}
