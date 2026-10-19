package com.launchapk.java

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.launchapk.java.auth.AndroidMicrosoftAccounts
import com.launchapk.java.ui.GlassButton
import com.launchapk.java.ui.LocalGlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MicrosoftAccountPanel() {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    var epoch by remember { mutableIntStateOf(0) }
    var description by remember { mutableStateOf("读取登录状态…") }
    DisposableEffect(context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) epoch++ }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(epoch) {
        description = try {
            withContext(Dispatchers.IO) {
                AndroidMicrosoftAccounts.store(context).load()?.let {
                    if (it.expiresAt > System.currentTimeMillis()) "${it.name} · 微软登录已验证" else "${it.name} · 登录已过期，请刷新"
                } ?: "尚未登录；可先配置应用 client ID"
            }
        } catch (_: Exception) { "登录记录无法读取，请在账号页重新登录" }
    }
    Text("微软正版账号", color = tokens.primaryText, style = MaterialTheme.typography.titleMedium)
    Text(description, color = tokens.secondaryText, style = MaterialTheme.typography.bodySmall)
    GlassButton("配置与微软登录", { context.startActivity(Intent(context, MicrosoftLoginActivity::class.java)) }, emphasized = false)
}
