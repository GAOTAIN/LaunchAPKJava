package com.launchapk.java

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.launchapk.java.core.*
import com.launchapk.java.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun ModLoaderPanel(loader: ModLoader, versions: List<GameVersion>, preferredGame: String,
    onDownload: (DownloadSpec) -> Unit) {
    val tokens = LocalGlassTokens.current
    val source = remember { ModLoaderSources() }
    var game by rememberSaveable { mutableStateOf(preferredGame.trim().ifBlank { "1.20.1" }) }
    var previews by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(30) }
    var releases by remember { mutableStateOf(emptyList<LoaderRelease>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var requestId by remember { mutableIntStateOf(0) }
    val selected = versions.firstOrNull { it.id == game.trim() }
    LaunchedEffect(loader, game.trim(), selected, refresh) {
        val request = ++requestId
        releases = emptyList()
        limit = 30
        busy = false
        if (selected == null) {
            message = if (versions.isEmpty()) "等待原版版本清单；加载失败时可返回原版分类刷新。" else "请输入完整的 Minecraft 版本号，例如 1.20.1。"
            return@LaunchedEffect
        }
        busy = true
        message = "读取 ${loader.title} 官方版本清单…"
        try {
            delay(350)
            releases = withContext(Dispatchers.IO) { source.versions(loader, selected.id) }
            message = if (releases.isEmpty()) "官方清单中没有匹配 Minecraft ${selected.id} 的 ${loader.title} 版本。" else "找到 ${releases.size} 个版本 · Minecraft ${selected.id}"
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { message = "读取失败：${failure.message}，可点击刷新重试。"
        } finally { if (request == requestId) busy = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text("${loader.title} · 模组加载器", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
            Text(if (loader == ModLoader.FABRIC) "先下载对应原版，再准备 Fabric 配置与依赖库。Fabric API 属于模组，需要另行下载。"
                else "下载官方安装器并校验。补丁安装处理尚未接入，下载后不会标记为已安装。", color = tokens.secondaryText)
            if (loader == ModLoader.NEOFORGE && game.trim() == "1.20.1") {
                Text("1.20.1 使用 NeoForge 早期版本；该版本官方建议优先使用 Forge。", color = tokens.secondaryText)
            }
            OutlinedTextField(game, { game = it.take(100) }, label = { Text("Minecraft 完整版本号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val suggestions = versions.filter { it.type == "release" && it.id.startsWith(game.trim()) && it.id != game.trim() }.take(5)
            if (suggestions.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { version -> GlassButton(version.id, { game = version.id }, emphasized = false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(if (previews) "已显示测试版" else "显示测试版", { previews = !previews }, emphasized = false)
                GlassButton("刷新", { refresh++ }, enabled = !busy && selected != null)
                if (selected != null) GlassButton("先下载原版", {
                    onDownload(DownloadSpec("game", "Minecraft ${selected.id}", selected.url, sha1 = selected.sha1, version = selected.id, source = "Mojang"))
                }, emphasized = false)
            }
            Text(message, color = tokens.secondaryText)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val visible = releases.filter { it.gameVersion == game.trim() && (previews || it.stable) }
        if (!busy && releases.isNotEmpty() && visible.isEmpty()) Text("该版本只有测试版，可开启“显示测试版”查看。", color = tokens.secondaryText)
        visible.take(limit).forEach { release ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text("${loader.title} ${release.displayVersion}" + when {
                    release.recommended -> " · 官方推荐"
                    !release.stable -> " · 测试版"
                    else -> ""
                }, color = tokens.primaryText, style = MaterialTheme.typography.titleMedium)
                Text("Minecraft ${release.gameVersion} · " + when (loader) {
                    ModLoader.FABRIC -> "Fabric 官方 Meta / Maven"
                    ModLoader.FORGE -> "Forge 官方 Maven"
                    ModLoader.NEOFORGE -> "NeoForged 官方 Maven"
                }, color = tokens.secondaryText)
                GlassButton(if (loader == ModLoader.FABRIC) "准备配置与依赖库" else "下载官方安装器", { onDownload(release.downloadSpec()) })
            }
        }
        if (visible.size > limit) GlassButton("加载更多（${visible.size - limit}）", { limit += 30 }, emphasized = false)
    }
}
