package com.launchapk.java

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.launchapk.java.ui.GlassPreferences

// Appearance settings only; control-layout JSON belongs in filesDir/controls in a later increment.
internal class AppearancePreferences(context: Context) {
    private val store: SharedPreferences = context.applicationContext
        .getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val defaultBlur = Build.VERSION.SDK_INT >= 31 &&
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == false

    fun load(): GlassPreferences = GlassPreferences(
        blurEnabled = store.getBoolean("blur_enabled", defaultBlur),
        reduceTransparency = store.getBoolean("reduce_transparency", false),
    )

    fun save(preferences: GlassPreferences) {
        store.edit()
            .putBoolean("blur_enabled", preferences.blurEnabled)
            .putBoolean("reduce_transparency", preferences.reduceTransparency)
            .apply()
    }
}
