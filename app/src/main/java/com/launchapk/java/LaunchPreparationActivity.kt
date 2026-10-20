package com.launchapk.java

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.launchapk.java.auth.OfflineAccountStore
import com.launchapk.java.core.*
import com.launchapk.java.launcher.RuntimeInstaller
import com.launchapk.java.launcher.GameRuntimeOptions
import com.launchapk.java.launcher.RuntimeInstallRepository
import com.launchapk.java.launcher.runtimeJavaMajor
import com.launchapk.java.ui.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class LaunchPreparationActivity : ComponentActivity() {
    private var recentGame by mutableStateOf("")
    private var runtimeRevision by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val offline = intent.getBooleanExtra("offline", false)
        setContent { GlassTheme(AppearancePreferences(this).load()) { LaunchPreparationScreen(offline, recentGame, runtimeRevision) { finish() } } }
    }
    override fun onResume() {
        super.onResume()
        runtimeRevision++
        recentGame = try {
            val record = File(filesDir, "logs/last-game.properties")
            if (!record.isFile) "尚无实际游戏运行记录" else {
                val props = java.util.Properties().apply { record.inputStream().use(::load) }
                val name = props.getProperty("log", "")
                val state = props.getProperty("state", "unknown")
                val detail = if (state in setOf("preparing", "starting_jvm", "java_main")) "游戏进程未记录正常退出，请检查下面的异常日志。" else "最近状态：$state"
                val log = if (name.matches(Regex("game-[0-9]+\\.log"))) File(filesDir, "logs/$name") else null
                val tail = if (log?.isFile == true) java.io.RandomAccessFile(log, "r").use { input ->
                    val length = input.length(); input.seek((length - 16000).coerceAtLeast(0))
                    val bytes = ByteArray((length.coerceAtMost(16000)).toInt()); input.readFully(bytes); String(bytes, Charsets.UTF_8)
                } else "没有可读日志"
                "$detail\n日志：$name\n$tail"
            }
        } catch (failure: Exception) { "读取运行日志失败：${failure.message}" }
    }
}

private data class PreparationResult(val details: String, val issues: List<String>, val log: String, val neededJava: Int?)

@Composable
private fun LaunchPreparationScreen(offline: Boolean, recentGame: String, runtimeRevision: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val tokens = LocalGlassTokens.current
    val preferences = remember { context.getSharedPreferences("game_launch", 0) }
    var versions by remember { mutableStateOf(emptyList<InstalledGame>()) }
    var selected by remember { mutableStateOf(preferences.getString("version", "").orEmpty()) }
    var refresh by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf("等待检查…") }
    var blockers by remember { mutableStateOf(emptyList<String>()) }
    var logPath by remember { mutableStateOf("") }
    var requestId by remember { mutableIntStateOf(0) }
    var neededJava by remember { mutableStateOf<Int?>(null) }
    var runtimeRequest by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val abi = remember { Build.SUPPORTED_64_BIT_ABIS.firstOrNull().orEmpty() }
    fun launchVersion(id: String) {
        if (launching) return
        launching = true
        scope.launch {
            try {
                check(RuntimeInstallRepository(context).current()?.active != true) { "运行时安装任务尚未结束，请等待完成再启动。" }
                context.startActivity(Intent(context, GameActivity::class.java).putExtra("version", id).putExtra("offline", offline))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { blockers = listOf(failure.message ?: "无法启动游戏"); launching = false }
        }
    }
    LaunchedEffect(runtimeRevision) { launching = false }
    val width = (configuration.screenWidthDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(320)
    val height = (configuration.screenHeightDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(240)
    LaunchedEffect(refresh) {
        try {
            versions = withContext(Dispatchers.IO) { InstalledVersions(context.filesDir).list() }
            if (versions.none { it.id == selected }) selected = versions.firstOrNull { it.problem == null }?.id.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { summary = "读取已下载版本失败：${failure.message}" }
    }
    LaunchedEffect(selected, refresh, width, height, runtimeRevision) {
        val request = ++requestId
        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                val issues = mutableListOf<String>()
                var missingJava: Int? = null
                var details = "尚未选择已下载版本"
                if (selected.isBlank()) issues += "请先在下载中心完成原版游戏下载。"
                val runtime = try { RuntimeInstaller(context.filesDir, context.cacheDir).inspect(abi)
                } catch (_: IllegalArgumentException) { null
                } catch (failure: Exception) { issues += "JRE 检查失败：${failure.message}"; null }
                if (!offline) issues += "本轮检查使用本地离线档案，请在首页选择“离线”；微软登录配置保持原状。"
                if (selected.isNotBlank() && offline) {
                    try {
                        val account = OfflineAccountStore(context.filesDir).load().selected ?: error("请先添加并选用离线档案")
                        val plan = LaunchPlanner(context.filesDir).prepare(selected, LocalLaunchIdentity(account.name, account.uuid), abi, width, height)
                        GameRuntimeOptions.classpath(plan.classpath, emptyList())
                        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
                        listOf("libglfw.so", "libgl4es.so", "liblwjgl.so", "liblwjgl_opengl.so", "liblwjgl_stb.so", "libopenal.so", "libjnidispatch.so").forEach {
                            check(File(nativeDir, it).isFile) { "APK 缺少 Android 原生库 $it" }
                        }
                        check(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) == 4096L) { "本次 LWJGL/JNA 依赖仅支持 4KB 内存页设备，16KB 设备需重建依赖。" }
                        details = "版本：${plan.versionId}\n继承原版：${plan.baseId}\n档案：${account.name}（本地离线）\n主类：${plan.mainClass}\nJava 要求：${plan.requiredJava}\nclasspath：${plan.classpath.size} 个 JAR\n资源对象：${plan.assetCount} 个\n启动参数：${plan.jvmArguments.size} 个 JVM 参数，${plan.gameArguments.size} 个游戏参数\n实例目录：${plan.gameDirectory.relativeTo(context.filesDir).invariantSeparatorsPath}"
                        if (runtime == null || runtimeJavaMajor(runtime.version) != plan.requiredJava) {
                            val packageInfo = RuntimeInstallRepository(context).catalog().select(plan.requiredJava, abi)
                            missingJava = plan.requiredJava
                            details += "\n需要补齐 Android Java ${packageInfo.version}（约 ${packageInfo.downloadBytes / 1048576} MB）；点击启动按钮后自动下载并安装。"
                        }
                    } catch (failure: Exception) { issues += "启动计划检查失败：${failure.message}" }
                }
                val logs = File(context.filesDir, "logs").also { check(it.isDirectory || it.mkdirs()) }
                val log = File(logs, "launch-check-${System.currentTimeMillis()}.log")
                log.writeText("LaunchAPK Java 启动准备检查\n$details\n\n未满足条件：\n" + issues.joinToString("\n"), Charsets.UTF_8)
                PreparationResult(details, issues.toList(), log.relativeTo(context.filesDir).invariantSeparatorsPath, missingJava)
            }
            summary = result.details; blockers = result.issues; logPath = result.log; neededJava = result.neededJava
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { summary = "启动检查失败：${failure.message}"; blockers = listOf(summary); logPath = ""
        } finally { if (request == requestId) busy = false }
    }
    GlassBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton("返回", onExit, emphasized = false)
                Text("选择版本与启动", color = tokens.primaryText, style = MaterialTheme.typography.headlineSmall)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("已下载版本", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                Box {
                    GlassButton(selected.ifBlank { "没有已下载版本" }, { expanded = true }, enabled = versions.isNotEmpty() && !busy && !downloading && !launching && runtimeRequest == null, emphasized = false)
                    DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                        versions.take(200).forEach { version ->
                            DropdownMenuItem(text = { Text(version.id + if (version.problem != null) " · 配置异常" else "") }, onClick = {
                                selected = version.id; expanded = false
                                preferences.edit().putString("version", version.id).apply()
                            })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(if (neededJava != null) "补齐 Java ${neededJava} 并启动" else "启动游戏", {
                        val major = neededJava
                        if (major == null) launchVersion(selected) else runtimeRequest = selected to major
                    }, enabled = selected.isNotBlank() && !busy && !downloading && !launching && runtimeRequest == null && blockers.isEmpty())
                    GlassButton("重新检查", { refresh++ }, enabled = !busy && !downloading && !launching && runtimeRequest == null)
                    GlassButton("运行环境 / Java main 自检", { context.startActivity(Intent(context, RuntimeActivity::class.java)) }, emphasized = false)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            (neededJava ?: runtimeRequest?.second)?.let { major ->
                RuntimeAutoInstallCard(major, abi, enabled = !busy, autoStart = runtimeRequest != null,
                    onInstalled = {
                        val pending = runtimeRequest
                        runtimeRequest = null; downloading = false
                        if (pending != null && pending.first == selected && pending.second == major) launchVersion(selected) else refresh++
                    }, onFailure = { runtimeRequest = null; downloading = false; refresh++ },
                    onActiveChanged = { downloading = it })
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("启动计划", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                Text(summary, color = tokens.secondaryText)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text(if (blockers.isEmpty() && !busy) "可以尝试启动" else "需要处理", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                blockers.forEach { Text("• $it", color = tokens.secondaryText) }
                if (logPath.isNotEmpty()) Text("检查日志（应用私有目录）：$logPath", color = tokens.secondaryText)
                Text("游戏在独立进程中运行，画面和虚拟按键位于同一 Activity。GL4ES 提供 OpenGL 2.1，较新版本或模组可能要求更高图形能力，失败时请查看运行日志。", color = tokens.secondaryText)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("最近游戏运行日志", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                Text(recentGame, color = tokens.secondaryText)
            }
        }
    }
}
