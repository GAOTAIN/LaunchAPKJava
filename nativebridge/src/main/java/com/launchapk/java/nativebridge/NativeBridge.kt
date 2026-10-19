package com.launchapk.java.nativebridge

import android.view.Surface

object NativeBridge {
    init { System.loadLibrary("launchapk_bridge") }
    /** Execute only in the dedicated :jvm process, never the launcher UI process. */
    external fun diagnoseJvm(home: String, jvm: String, cache: String, log: String): String
    /** Blocking Java main invocation. Use a fresh dedicated process and never pass ART handles to HotSpot. */
    external fun launchJvm(home: String, jvm: String, cache: String, log: String, workingDirectory: String,
        options: Array<String>, mainClass: String, arguments: Array<String>): Int
    /** Actual EGL window and GL4ES immediate-mode triangle, not a Compose mock. */
    external fun renderProbe(surface: Surface, library: String): String
    /** The following entry points belong to the Activity's dedicated :game process. */
    external fun configureGame(gl4es: String)
    external fun attachGameSurface(surface: Surface, width: Int, height: Int)
    external fun detachGameSurface()
    external fun closeGame()
    external fun sendKey(keyCode: Int, pressed: Boolean)
    external fun sendPointer(id: Int, x: Float, y: Float, action: Int)
    external fun sendMouseButton(button: Int, pressed: Boolean)
    external fun sendFocus(focused: Boolean)
    external fun sendScroll(x: Float, y: Float)
    external fun sendCharacter(codepoint: Int)
    external fun gameStatus(): String
}
