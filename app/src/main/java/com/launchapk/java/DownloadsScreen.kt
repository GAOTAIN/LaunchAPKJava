package com.launchapk.java

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.launchapk.java.core.*
import com.launchapk.java.ui.*
import java.net.URLEncoder
import kotlinx.coroutines.*

@Composable
internal fun DownloadsScreen(onExit: () -> Unit, requestNotifications: () -> Unit, embedded: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val api = remember { CatalogSources() }
    val repository = remember(context) { DownloadRepository(context) }
    val scope = rememberCoroutineScope()
    val loaderStates = rememberSaveableStateHolder()
    val tokens = LocalGlassTokens.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var pageUrl by rememberSaveable { mutableStateOf("") }
    var gameFilter by rememberSaveable { mutableStateOf("") }
    var loaderFilter by rememberSaveable { mutableStateOf("") }
    var snapshots by rememberSaveable { mutableStateOf(false) }
    var gameCategory by rememberSaveable { mutableStateOf("vanilla") }
    var versions by remember { mutableStateOf(emptyList<GameVersion>()) }
    var entry by remember { mutableStateOf<McmodEntry?>(null) }
    var content by remember { mutableStateOf(emptyList<ContentFile>()) }
    var tasks by remember { mutableStateOf(emptyList<DownloadTask>()) }
    var browser by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("游戏从 Mojang 官方源下载；扩展内容从 MC百科资料中的公开作者源下载") }
    var pending by remember { mutableStateOf<DownloadSpec?>(null) }
    var loaderJob by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0) }
    val kind = ContentKind.entries[(tab - 1).coerceIn(0, 3)]
    fun load(action: suspend () -> Unit) {
        generation++
        val current = generation
        loaderJob?.cancel()
        busy = true
        loaderJob = scope.launch {
            try { action()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (current == generation) message = e.message ?: "读取失败"
            } finally { if (current == generation) busy = false }
        }
    }
    fun readPage(url: String) {
        pageUrl = url
        entry = null
        content = emptyList()
        browser = null
        val selectedKind = kind
        val game = gameFilter
        val loader = loaderFilter
        load {
            val result = withContext(Dispatchers.IO) { api.mcmod(url.trim()) }
            entry = result
            content = emptyList()
            val projects = result.links.mapNotNull(::modrinthProject).distinct().take(5)
            val files = mutableListOf<ContentFile>()
            var error = ""
            for (project in projects) {
                try {
                    files += withContext(Dispatchers.IO) { api.contentFiles(project, selectedKind, game, loader) }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { error = e.message.orEmpty() }
            }
            content = files.distinctBy { it.url }
            message = if (files.isNotEmpty()) "已读取 ${result.title}；下载文件来自作者的 Modrinth 项目" else
                "未找到匹配文件。${error} 可调整分类/筛选，或使用下方原站入口。"
        }
    }
    fun enqueue(spec: DownloadSpec) {
        requestNotifications()
        browser = null
        tab = 5
        scope.launch {
            try { repository.enqueue(spec); message = "已加入下载任务"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message = "建立任务失败：${e.message}" }
        }
    }
    LaunchedEffect(Unit) {
        try { versions = withContext(Dispatchers.IO) { api.gameVersions() }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { message = "版本列表加载失败：${e.message}" }
    }
    LaunchedEffect(repository) {
        while (isActive) {
            try { tasks = repository.tasks()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { }
            delay(2000)
        }
    }
    if (browser != null) {
        ContentBrowser(browser!!, kind, onClose = { browser = null }, onReadPage = ::readPage, onDownload = ::enqueue)
        return
    }
    GlassBackground {
        LazyColumn(Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.safeDrawingPadding().padding(16.dp)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!embedded) item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton("返回", onExit, emphasized = false)
                    Text("下载中心", color = tokens.primaryText, style = MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("游戏版本") + ContentKind.entries.map { it.title } + "任务").forEachIndexed { index, title ->
                        GlassButton(title, {
                            generation++; loaderJob?.cancel(); busy = false
                            tab = index; entry = null; content = emptyList()
                        }, emphasized = tab == index)
                    }
                }
            }
            item { Text(message, color = tokens.secondaryText) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (tab == 0) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("原版", { gameCategory = "vanilla" }, emphasized = gameCategory == "vanilla")
                        ModLoader.entries.forEach { loader ->
                            GlassButton(loader.title, { gameCategory = loader.name }, emphasized = gameCategory == loader.name)
                        }
                    }
                }
                if (gameCategory != "vanilla") {
                    item {
                        loaderStates.SaveableStateProvider(gameCategory) {
                            ModLoaderPanel(ModLoader.valueOf(gameCategory), versions, query, onDownload = { pending = it })
                        }
                    }
                } else {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("官方原版文件", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                        Text("下载客户端、适用的 Java 库与资源。运行仍需 Android JRE、JVM 启动桥与渲染兼容层。旧版传统资源目录暂不支持。", color = tokens.secondaryText)
                        OutlinedTextField(query, { query = it }, label = { Text("版本号筛选") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassButton(if (snapshots) "已显示快照" else "显示快照", { snapshots = !snapshots }, emphasized = false)
                            GlassButton("刷新", { load { versions = withContext(Dispatchers.IO) { api.gameVersions() } } }, enabled = !busy)
                        }
                    }
                }
                items(versions.filter { (snapshots || it.type == "release") && it.id.contains(query.trim(), true) }.take(150), key = { it.id }) { version ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(version.id + " · " + version.type, color = tokens.primaryText)
                        GlassButton("下载游戏文件", { pending = DownloadSpec("game", "Minecraft ${version.id}", version.url, sha1 = version.sha1, version = version.id, source = "Mojang") })
                    }
                }
                }
            } else if (tab in 1..4) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("MC百科 · ${kind.title}", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(query, { query = it }, label = { Text("搜索关键词") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        GlassButton("在应用内搜索 MC百科", {
                            browser = "https://search.mcmod.cn/s?key=" + URLEncoder.encode(query.ifBlank { kind.title }, "UTF-8")
                        })
                        Text("搜索由你在原网页操作；打开资料页后点“读取当前资料页”，可筛选公开下载文件。", color = tokens.secondaryText)
                        OutlinedTextField(pageUrl, { pageUrl = it }, label = { Text("或粘贴 MC百科资料页链接") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(gameFilter, { gameFilter = it }, label = { Text("Minecraft 版本（可留空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(loaderFilter, { loaderFilter = it }, label = { Text("加载器，例如 fabric / forge / iris（可留空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        GlassButton("读取资料与文件列表", { readPage(pageUrl) }, enabled = !busy && pageUrl.isNotBlank())
                        Text("扩展内容保存为原文件；加载器请前往“游戏版本”分类。整合包与模组依赖不会自动安装，光影还取决于 Android 渲染兼容性。", color = tokens.secondaryText)
                    }
                }
                entry?.let { info ->
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text(info.title, color = tokens.primaryText)
                            Text("资料来源：${info.sourceUrl}", color = tokens.secondaryText)
                            GlassButton("打开资料原页", { browser = info.sourceUrl }, emphasized = false)
                            info.links.forEach { link -> GlassButton("原站：${httpsUri(link).host} / ${link.substringAfterLast('/')}", { browser = link }, emphasized = false) }
                        }
                    }
                }
                items(content, key = { it.url }) { file ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(file.title, color = tokens.primaryText)
                        Text("${file.filename} · ${file.size / 1024} KB\n版本：${file.versions}\n加载器：${file.loaders}", color = tokens.secondaryText)
                        GlassButton("下载并校验", {
                            pending = DownloadSpec("content", file.filename, file.url,
                                "content/${kind.directory}/${file.project}/${file.versionId}/${file.filename}", file.size, sha512 = file.sha512, source = entry?.sourceUrl.orEmpty())
                        })
                    }
                }
            } else {
                if (tasks.isEmpty()) item { Text("还没有下载任务", color = tokens.secondaryText) }
                items(tasks, key = { it.id }) { task ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(task.label, color = tokens.primaryText)
                        Text(mapOf("RUNNING" to "下载中", "ENQUEUED" to "排队 / 等待网络 / 重试", "SUCCEEDED" to "已完成", "FAILED" to "失败", "CANCELLED" to "已取消")[task.state] ?: task.state, color = tokens.secondaryText)
                        if (task.total > 0) LinearProgressIndicator(progress = { (task.done.toFloat() / task.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        if (task.message.isNotBlank()) Text(task.message, color = tokens.secondaryText)
                        if (task.path.isNotBlank()) Text("应用私有目录：${task.path}", color = tokens.secondaryText)
                        if (task.state in listOf("RUNNING", "ENQUEUED", "BLOCKED")) GlassButton("取消", { repository.cancel(task.id) }, emphasized = false)
                        if (task.state in listOf("FAILED", "CANCELLED")) GlassButton("重试", { scope.launch {
                            try { repository.retry(task.id) } catch (e: Exception) { message = "重试失败：${e.message}" }
                        } })
                    }
                }
            }
        }
    }
    pending?.let { spec ->
        Dialog(onDismissRequest = { pending = null }) {
            GlassCard {
                Text("下载 ${spec.label}？", color = tokens.primaryText)
                Text(when {
                    spec.kind == "game" -> "从 Mojang 获取游戏文件，可能占用较多流量和空间。下载完成不代表已具备启动环境。"
                    spec.kind == "loader" && spec.loader?.loader == ModLoader.FABRIC -> "从 Fabric 官方源获取客户端配置与依赖库并校验。需先下载对应原版；游戏启动适配尚未完成。"
                    spec.kind == "loader" -> "来源 ${httpsUri(spec.url).host}；下载时读取官方 SHA-1 并校验安装器。补丁安装尚未接入，完成后仍未安装加载器。"
                    spec.sha512.isNotEmpty() -> "来源 ${httpsUri(spec.url).host}；下载后校验 SHA-512，仅保存原文件。"
                    else -> "来源 ${httpsUri(spec.url).host}；源未提供哈希，将检查 ZIP/JAR 结构，仅保存原文件。"
                }, color = tokens.secondaryText)
                GlassButton("开始下载", { pending = null; enqueue(spec) })
                GlassButton("取消", { pending = null }, emphasized = false)
            }
        }
    }
}
