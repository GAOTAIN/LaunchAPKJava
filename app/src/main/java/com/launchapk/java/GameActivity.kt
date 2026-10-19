package com.launchapk.java

import android.os.*
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.launchapk.java.auth.OfflineAccountStore
import com.launchapk.java.core.LaunchPlanner
import com.launchapk.java.core.LocalLaunchIdentity
import com.launchapk.java.input.*
import com.launchapk.java.launcher.GameRuntime
import com.launchapk.java.launcher.GameRuntimeOptions
import com.launchapk.java.launcher.RuntimeInstaller
import com.launchapk.java.nativebridge.NativeBridge
import com.launchapk.java.ui.*
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/** Surface, ART overlay and HotSpot game share only C data in the dedicated :game process. */
class GameActivity : ComponentActivity(), SurfaceHolder.Callback {
    private val session by lazy { InputSession(object : InputBridge {
        override fun sendKey(keyCode: Int, pressed: Boolean) = NativeBridge.sendKey(keyCode, pressed)
        override fun sendPointer(id: Int, x: Float, y: Float, action: Int) = NativeBridge.sendPointer(id, x, y, action)
        override fun sendMouseButton(button: Int, pressed: Boolean) = NativeBridge.sendMouseButton(button, pressed)
    }) }
    private val started = AtomicBoolean()
    private val stopping = AtomicBoolean()
    private var status by mutableStateOf("等待游戏 Surface…")
    private var error by mutableStateOf<String?>(null)
    private var editing = false
    private var log: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val receipt get() = File(filesDir, "logs/last-game.properties")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        NativeBridge.configureGame(File(applicationInfo.nativeLibraryDir, "libgl4es.so").absolutePath)
        setContent {
            GlassTheme(AppearancePreferences(this).load()) {
                ControlDebugScreen(session, status, ::stopGame,
                    gameContent = {
                        AndroidView(factory = { context ->
                            SurfaceView(context).apply { holder.addCallback(this@GameActivity) }
                        }, modifier = Modifier.fillMaxSize())
                    }, onEditingChanged = {
                        editing = it; session.releaseAll(); NativeBridge.sendFocus(!it && hasWindowFocus())
                    })
                error?.let { message ->
                    Dialog(onDismissRequest = ::stopGame) {
                        GlassCard(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("游戏未能继续运行", color = LocalGlassTokens.current.primaryText)
                                Text(message, color = LocalGlassTokens.current.secondaryText)
                                Text("日志：${log?.name ?: "尚未创建"}\n可返回启动页查看最近运行日志。", color = LocalGlassTokens.current.secondaryText)
                                GlassButton("返回启动器", ::stopGame)
                            }
                        }
                    }
                }
            }
        }
        handler.post(object : Runnable {
            override fun run() {
                if (!stopping.get() && error == null) {
                    if (started.get()) status = NativeBridge.gameStatus()
                    handler.postDelayed(this, 1000)
                }
            }
        })
    }

    override fun surfaceCreated(holder: SurfaceHolder) { /* wait for the actual pixel dimensions */ }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width < 1 || height < 1 || stopping.get()) return
        NativeBridge.attachGameSurface(holder.surface, width, height)
        if (started.compareAndSet(false, true)) {
            Thread({ launch(width, height) }, "LaunchAPK-Minecraft-main").start()
        }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        session.releaseAll(); NativeBridge.detachGameSurface()
        if (started.get() && !isFinishing) stopGame()
    }

    private fun launch(width: Int, height: Int) {
        try {
            val version = intent.getStringExtra("version") ?: error("没有选择游戏版本")
            val logs = File(filesDir, "logs").apply { check(isDirectory || mkdirs()) }
            val output = File(logs, "game-${System.currentTimeMillis()}.log").also { log = it }
            output.writeText("LaunchAPK Java / Android game session\nVersion: $version\n", Charsets.UTF_8)
            record("preparing", output.name)
            check(intent.getBooleanExtra("offline", false)) { "当前游戏执行入口使用本地离线档案；微软会话启动尚需单独接入。" }
            val abi = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: error("需要 64 位 Android")
            val account = OfflineAccountStore(filesDir).load().selected ?: error("请先选择离线档案")
            val plan = LaunchPlanner(filesDir).prepare(version, LocalLaunchIdentity(account.name, account.uuid), abi,
                width.coerceAtLeast(320), height.coerceAtLeast(240))
            val runtime = RuntimeInstaller(filesDir, cacheDir).inspect(abi) ?: error("请先导入 Android/Bionic JRE")
            val major = runtime.version.removePrefix("1.").substringBefore('.').substringBefore('-').substringBefore('+').toIntOrNull()
            check(major == plan.requiredJava) { "版本要求 Java ${plan.requiredJava}，当前运行时为 ${runtime.version}" }
            val bundled = GameRuntime(this).install()
            val gameJars = GameRuntimeOptions.classpath(plan.classpath, bundled)
            val temporary = File(cacheDir, "game").apply { check(isDirectory || mkdirs()) }
            val bootstrap = File(temporary, "launchapk-bootstrap.jar")
            assets.open("jvm/launchapk-smoke.jar").use { source -> bootstrap.outputStream().use { source.copyTo(it) } }
            val natives = File(applicationInfo.nativeLibraryDir)
            val options = GameRuntimeOptions.options(plan.jvmArguments, listOf(bootstrap) + gameJars, natives, filesDir)
            check(plan.gameDirectory.isDirectory || plan.gameDirectory.mkdirs())
            record("starting_jvm", output.name)
            runOnUiThread { status = "正在创建 JVM 并调用 ${plan.mainClass}…" }
            val result = NativeBridge.launchJvm(runtime.home.absolutePath, runtime.jvm.absolutePath,
                temporary.absolutePath, output.absolutePath, plan.gameDirectory.absolutePath, options.toTypedArray(),
                "com.launchapk.java.launcher.game.GameMain",
                (listOf(receipt.absolutePath, plan.mainClass) + plan.gameArguments).toTypedArray())
            check(result == 0) { "JVM 退出码：$result" }
            record("exited", output.name)
            runOnUiThread { stopGame() }
        } catch (failure: Throwable) {
            val message = failure.message ?: failure.javaClass.simpleName
            runCatching { log?.appendText("\n[LaunchAPK] $message\n"); record("error", log?.name.orEmpty()) }
            runOnUiThread { error = message; session.releaseAll(); NativeBridge.sendFocus(false) }
        }
    }

    @Synchronized private fun record(state: String, name: String) {
        receipt.parentFile?.mkdirs()
        val value = Properties().apply {
            setProperty("state", state); setProperty("log", name)
            setProperty("version", intent.getStringExtra("version").orEmpty())
        }
        val temporary = File(receipt.parentFile, ".last-game.android.tmp")
        temporary.outputStream().use { value.store(it, "LaunchAPK game session") }
        java.nio.file.Files.move(temporary.toPath(), receipt.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun stopGame() {
        if (!stopping.compareAndSet(false, true)) return
        session.releaseAll(); NativeBridge.closeGame()
        status = "正在退出游戏…"
        // HotSpot cannot be recreated. Only terminate our own dedicated Activity process.
        handler.postDelayed({
            if (error == null) record("stopped", log?.name.orEmpty())
            finish()
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 2000)
    }

    override fun onPause() { session.releaseAll(); NativeBridge.sendFocus(false); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) session.releaseAll()
        NativeBridge.sendFocus(hasFocus && !editing)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            NativeBridge.sendKey(256, event.action != KeyEvent.ACTION_UP); return true
        }
        val glfw = when (event.keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 65 + event.keyCode - KeyEvent.KEYCODE_A
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 48 + event.keyCode - KeyEvent.KEYCODE_0
            KeyEvent.KEYCODE_SPACE -> 32; KeyEvent.KEYCODE_ENTER -> 257; KeyEvent.KEYCODE_DEL -> 259
            KeyEvent.KEYCODE_TAB -> 258; KeyEvent.KEYCODE_ESCAPE -> 256
            KeyEvent.KEYCODE_SHIFT_LEFT -> 340; KeyEvent.KEYCODE_SHIFT_RIGHT -> 344
            KeyEvent.KEYCODE_CTRL_LEFT -> 341; KeyEvent.KEYCODE_CTRL_RIGHT -> 345
            KeyEvent.KEYCODE_ALT_LEFT -> 342; KeyEvent.KEYCODE_ALT_RIGHT -> 346
            KeyEvent.KEYCODE_DPAD_UP -> 265; KeyEvent.KEYCODE_DPAD_DOWN -> 264
            KeyEvent.KEYCODE_DPAD_LEFT -> 263; KeyEvent.KEYCODE_DPAD_RIGHT -> 262
            else -> null
        }
        if (!editing && glfw != null) {
            NativeBridge.sendKey(glfw, event.action != KeyEvent.ACTION_UP)
            if (event.action == KeyEvent.ACTION_DOWN && event.unicodeChar != 0) NativeBridge.sendCharacter(event.unicodeChar)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
