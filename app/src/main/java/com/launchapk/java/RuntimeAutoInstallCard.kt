package com.launchapk.java

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.launchapk.java.launcher.RuntimeInstallRepository
import com.launchapk.java.launcher.RuntimeInstallTask
import com.launchapk.java.ui.*
import kotlinx.coroutines.*
import java.util.UUID

@Composable
internal fun RuntimeAutoInstallCard(major: Int, abi: String, enabled: Boolean = true, autoStart: Boolean = false,
    onInstalled: () -> Unit, onFailure: () -> Unit = {}, onActiveChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    val repository = remember(context) { RuntimeInstallRepository(context) }
    val scope = rememberCoroutineScope()
    val tokens = LocalGlassTokens.current
    val latestInstalled by rememberUpdatedState(onInstalled)
    val latestFailure by rememberUpdatedState(onFailure)
    val latestEnabled by rememberUpdatedState(enabled)
    var task by remember { mutableStateOf<RuntimeInstallTask?>(null) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var requested by remember { mutableStateOf<UUID?>(null) }
    val spec = remember(major, abi) { runCatching { repository.catalog().select(major, abi) }.getOrNull() }
    fun start() {
        if (starting || !enabled || spec == null) return
        starting = true; error = ""
        scope.launch {
            try { requested = repository.enqueue(major, abi); task = repository.current()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { error = failure.message ?: "无法创建安装任务"; latestFailure()
            } finally { starting = false }
        }
    }
    LaunchedEffect(autoStart, enabled) { if (autoStart && enabled && requested == null) start() }
    LaunchedEffect(repository, major, abi) {
        var observed: UUID? = null
        while (true) {
            try {
                val current = repository.current(); task = current
                if (current != null && current.major == major && current.abi == abi) {
                    if (current.active) observed = current.id
                    if (current.id == requested || current.id == observed) {
                        if (current.state == "SUCCEEDED" && latestEnabled && owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
                            requested = null; observed = null; latestInstalled()
                        } else if (current.state in setOf("FAILED", "CANCELLED")) {
                            requested = null; observed = null; latestFailure()
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { error = failure.message ?: "读取安装进度失败" }
            delay(1000)
        }
    }
    val active = starting || task?.active == true
    LaunchedEffect(active) { onActiveChanged(active) }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("自动补齐 Android Java $major", color = tokens.primaryText)
        Text(spec?.let { "版本 ${it.version} · $abi · 约 ${"%.1f".format(it.downloadBytes / 1048576.0)} MB" }
            ?: "暂未提供该 Java 版本或设备架构的下载", color = tokens.secondaryText)
        Text("来源：FCL Android OpenJDK 固定版本。自动校验 SHA-256、解压并保留原始许可证；无需填写哈希。", color = tokens.secondaryText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (starting) "创建任务…" else "下载并安装 Java $major", ::start, enabled = enabled && !active && spec != null)
            task?.takeIf { it.active }?.let { current -> GlassButton("取消下载", { repository.cancel(current.id) }, emphasized = false) }
        }
        task?.let { current ->
            Text("Java ${current.major} · ${current.message}", color = tokens.secondaryText)
            if (current.active) {
                if (current.total > 0) LinearProgressIndicator(progress = { (current.done.toFloat() / current.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (error.isNotEmpty()) Text(error, color = tokens.secondaryText)
    }
}
