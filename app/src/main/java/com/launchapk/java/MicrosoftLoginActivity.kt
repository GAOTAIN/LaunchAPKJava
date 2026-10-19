package com.launchapk.java

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.launchapk.java.auth.*
import com.launchapk.java.ui.*
import kotlinx.coroutines.*

class MicrosoftLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GlassTheme(AppearancePreferences(this).load()) { MicrosoftLoginScreen { finish() } } }
    }
}

@Composable
private fun MicrosoftLoginScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val config = remember { context.getSharedPreferences("microsoft_auth", 0) }
    val store = remember { AndroidMicrosoftAccounts.store(context.applicationContext) }
    val api = remember { MicrosoftAuthClient() }
    val scope = rememberCoroutineScope()
    var savedId by remember { mutableStateOf(config.getString("client_id", "").orEmpty()) }
    var clientId by rememberSaveable { mutableStateOf(savedId) }
    var session by remember { mutableStateOf<MicrosoftSession?>(null) }
    var challenge by remember { mutableStateOf<DeviceChallenge?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("填写你自己的应用 client ID 后，通过微软官方网页授权。") }
    fun errorMessage(error: Exception): String = if (error is AuthFailure) error.message.orEmpty() else "登录或存储失败，请检查网络和应用配置。"
    LaunchedEffect(store) {
        try { session = withContext(Dispatchers.IO) { store.load() }
        } catch (_: Exception) { status = "登录记录无法读取或解密。可退出此记录后重新授权。"
        } finally { loaded = true }
    }
    fun start(action: suspend () -> Unit) {
        if (busy || !loaded) return
        busy = true
        job = scope.launch {
            try { action()
            } catch (e: CancellationException) { status = "本次操作已取消"; throw e
            } catch (e: Exception) { status = errorMessage(e)
            } finally { busy = false; challenge = null }
        }
    }
    GlassBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton("返回", onExit, emphasized = false)
                Text("微软正版账号", color = tokens.primaryText, style = MaterialTheme.typography.headlineSmall)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("应用配置", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                Text("在 Microsoft Entra 注册支持个人微软账户的应用，启用公共客户端流程，并申请所需的 Xbox/Minecraft API 访问。这里不需要 client secret。", color = tokens.secondaryText)
                OutlinedTextField(clientId, { clientId = it.take(64) }, singleLine = true, enabled = !busy && loaded,
                    label = { Text("Application（client）ID") }, modifier = Modifier.fillMaxWidth())
                GlassButton(if (session == null) "保存配置" else "保存配置（更换 ID 将退出旧账户）", {
                    try {
                        val valid = validMicrosoftClientId(clientId)
                        start {
                            if (savedId != valid) { withContext(Dispatchers.IO) { store.clear() }; session = null }
                            check(withContext(Dispatchers.IO) { config.edit().putString("client_id", valid).commit() })
                            savedId = valid; clientId = valid; status = "应用配置已保存，可开始微软授权"
                        }
                    } catch (e: IllegalArgumentException) { status = e.message.orEmpty() }
                }, enabled = !busy && loaded)
                Text("应用 ID 未获权限时，服务可能拒绝登录；请在自己的应用注册中完成配置。", color = tokens.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text(session?.let { "${it.name} · 微软账号" } ?: "登录与游戏许可", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                Text(status, color = tokens.secondaryText)
                if (session != null) Text(if (session!!.expiresAt > System.currentTimeMillis()) "已通过 Minecraft Java 游戏许可与角色资料检查" else "登录已过期，请刷新验证", color = tokens.secondaryText)
                val current = challenge
                if (current != null) {
                    Text("在微软网页输入以下授权码", color = tokens.primaryText)
                    SelectionContainer { Text(current.userCode, color = tokens.accent, style = MaterialTheme.typography.headlineMedium) }
                    GlassButton("打开微软授权页面", {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.verificationUri)))
                        } catch (_: Exception) { status = "无法打开浏览器，请在浏览器访问 microsoft.com/devicelogin 并输入上面的授权码" }
                    })
                    Text("完成网页授权后返回本页；正在按微软规定的间隔等待结果。授权码仅供你本次登录使用。", color = tokens.secondaryText)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton("微软登录", {
                        start {
                            val code = withContext(Dispatchers.IO) { api.begin(savedId) }
                            challenge = code; status = "等待你在微软官方网页确认授权"
                            val result = withContext(Dispatchers.IO) { api.complete(savedId, code) }
                            withContext(Dispatchers.IO) { store.save(result) }
                            session = result; status = "登录成功；游戏许可与角色资料已验证"
                        }
                    }, enabled = loaded && !busy && savedId.isNotBlank() && clientId.trim().equals(savedId, true))
                    if (busy) GlassButton("取消", { job?.cancel() }, emphasized = false)
                    if (session != null) GlassButton("刷新登录", {
                        val old = session!!
                        start {
                            val refreshed = withContext(Dispatchers.IO) { api.refresh(old) }
                            withContext(Dispatchers.IO) { store.save(refreshed) }
                            session = refreshed; status = "登录已刷新，游戏许可已重新检查"
                        }
                    }, enabled = !busy && loaded, emphasized = false)
                    GlassButton("退出本地登录", {
                        start { withContext(Dispatchers.IO) { store.clear() }; session = null; status = "本机登录记录已移除" }
                    }, enabled = !busy && loaded, emphasized = false)
                }
                Text("令牌通过 Android Keystore 的 AES-GCM 密钥加密保存在应用私有目录；密码只在微软网页输入。", color = tokens.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
