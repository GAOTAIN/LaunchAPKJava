package com.launchapk.java

import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.launchapk.java.launcher.*
import com.launchapk.java.nativebridge.NativeBridge
import com.launchapk.java.renderer.RendererBackend
import com.launchapk.java.ui.*
import kotlinx.coroutines.*
import java.io.File

class RuntimeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GlassTheme(AppearancePreferences(this).load()) { RuntimeScreen { finish() } } }
    }
}

@Composable
private fun RuntimeScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val installer = remember { RuntimeInstaller(context.filesDir, context.cacheDir) }
    val abi = remember { Build.SUPPORTED_64_BIT_ABIS.firstOrNull().orEmpty() }
    val scope = rememberCoroutineScope()
    var hash by rememberSaveable { mutableStateOf("") }
    var installed by remember { mutableStateOf<InstalledRuntime?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("导入适用于 $abi 的 Android/Bionic JRE；保留包内的 LICENSE 或 legal 目录。") }
    var diagnostic by remember { mutableStateOf<JvmDiagnosticClient?>(null) }
    LaunchedEffect(installer) {
        try { installed = withContext(Dispatchers.IO) { installer.inspect(abi) }
        } catch (e: Exception) { message = "现有运行时检查失败：${e.message}" }
    }
    DisposableEffect(Unit) { onDispose { diagnostic?.close() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val expected = hash.trim()
            busy = true
            scope.launch {
                try {
                    message = "正在校验、解压与检查运行时…"
                    installed = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { installer.install(it, expected, abi) }
                            ?: error("无法读取所选文件")
                    }
                    message = "Java ${installed!!.version} 已安装；请运行 JVM 自检验证手机兼容性。"
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { message = "运行时导入失败：${e.message}"
                } finally { busy = false }
            }
        }
    }
    GlassBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton("返回", onExit, emphasized = false)
                Text("运行环境", color = tokens.primaryText, style = MaterialTheme.typography.headlineSmall)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Android JRE", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                Text(installed?.let { "Java ${it.version} · ${it.abi}" } ?: "尚未安装运行时", color = tokens.primaryText)
                Text("支持 Android Java 8 / 17 / 21 的 ZIP 包，不支持桌面 Windows、Linux 或 macOS JRE。ZIP 中的符号链接须预先展开为普通文件。", color = tokens.secondaryText)
                OutlinedTextField(hash, { hash = it.take(64) }, label = { Text("运行时 ZIP 的 SHA-256") },
                    supportingText = { Text("与可信来源给出的校验值核对后导入；不随应用附带 JRE。") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton("导入 Android JRE ZIP", { picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = !busy && hash.matches(Regex("[a-fA-F0-9]{64}")) && abi.isNotEmpty())
                    GlassButton("运行 JVM 自检", {
                        busy = true; message = "正在独立进程创建 JVM 并读取 Java 系统属性…"
                        diagnostic = JvmDiagnosticClient(context) { success, result ->
                            busy = false
                            message = (if (success) "JVM 自检成功\n" else "") + result
                        }.also { it.run() }
                    }, enabled = !busy && installed != null, emphasized = false)
                    GlassButton("执行 Java main 自检", {
                        busy = true; message = "正在独立进程调用 Java main，验证 classpath 与 Unicode 参数…"
                        diagnostic = JvmDiagnosticClient(context, mainProbe = true) { success, result ->
                            busy = false
                            message = (if (success) "Java main 自检成功\n" else "") + result
                        }.also { it.run() }
                    }, enabled = !busy && installed != null, emphasized = false)
                }
                Text(message, color = tokens.secondaryText)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("渲染兼容层", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RendererBackend.entries.forEach { backend ->
                        GlassButton(backend.title + if (backend.available) " · 默认" else " · 预留", {},
                            enabled = backend.available, emphasized = backend.available)
                    }
                }
                Text("GL4ES 已从锁定源码构建；此测试在真实 Surface 上通过 EGL 显示 OpenGL 三角形。游戏仍需 Android LWJGL/GLFW 适配与版本启动参数。", color = tokens.secondaryText)
                GlassButton(if (preview) "关闭渲染自检" else "运行 GL4ES 渲染自检", { preview = !preview }, emphasized = false, enabled = !busy)
                if (preview) Gl4esPreview()
            }
        }
    }
}

@Composable
private fun Gl4esPreview() {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("等待 Surface…") }
    var generation by remember { mutableIntStateOf(0) }
    AndroidView(modifier = Modifier.fillMaxWidth().height(200.dp), factory = { viewContext ->
        SurfaceView(viewContext).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    val current = ++generation
                    scope.launch {
                        val description = try {
                            withContext(Dispatchers.Default) {
                                NativeBridge.renderProbe(holder.surface, File(context.applicationInfo.nativeLibraryDir, "libgl4es.so").absolutePath)
                            }
                        } catch (e: CancellationException) { throw e
                        } catch (e: Throwable) { "GL4ES 自检失败：${e.message}" }
                        if (generation == current) result = description
                    }
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) { generation++ }
            })
        }
    })
    Text(result, color = tokens.secondaryText, style = MaterialTheme.typography.bodySmall)
}
