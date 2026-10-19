package com.launchapk.java.launcher

import android.app.Service
import android.content.Intent
import android.os.*
import com.launchapk.java.nativebridge.NativeBridge
import java.io.File
import java.util.Properties
import java.util.UUID

/** Runs HotSpot in the manifest's dedicated :jvm process, separate from the launcher UI. */
class JvmDiagnosticService : Service() {
    private var started = false
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what !in setOf(1, 3) || message.replyTo == null || started) return@Handler true
        started = true
        val mainProbe = message.what == 3
        val reply = message.replyTo
        Thread({
            val logs = File(filesDir, "logs").apply { mkdirs() }
            val log = File(logs, "jvm-${if (mainProbe) "main" else "diagnostic"}-${System.currentTimeMillis()}.log")
            val result = Bundle()
            try {
                val abi = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: error("需要 64 位 Android 设备")
                val runtime = RuntimeInstaller(filesDir, cacheDir).inspect(abi) ?: error("请先导入 Android JRE")
                val temporary = File(cacheDir, "jvm").apply { mkdirs() }
                val description = if (mainProbe) {
                    val jar = File(temporary, "launchapk-smoke.jar")
                    assets.open("jvm/launchapk-smoke.jar").use { input -> jar.outputStream().use { input.copyTo(it) } }
                    val receipt = File(temporary, "main-${UUID.randomUUID()}.properties")
                    try {
                        val exit = NativeBridge.launchJvm(runtime.home.absolutePath, runtime.jvm.absolutePath,
                            temporary.absolutePath, log.absolutePath, temporary.absolutePath,
                            arrayOf("-Xms32m", "-Xmx128m", "-Djava.class.path=${jar.absolutePath}", "-Duser.dir=${temporary.absolutePath}"),
                            "com.launchapk.java.launcher.smoke.Main", arrayOf("LaunchAPKJava", "含空格 参数 🌍", receipt.absolutePath))
                        check(exit == 0 && receipt.isFile && receipt.length() in 1..65536) { "Java main 没有产生有效结果" }
                        val properties = Properties().also { result -> receipt.inputStream().use(result::load) }
                        check(properties.getProperty("marker") == "LaunchAPKJava.main.v1" && properties.getProperty("argument") == "含空格 参数 🌍") { "Java main 参数验证失败" }
                        "Java main 已执行并正常退出\n空格、中文与补充 Unicode 参数验证通过\njava.version=${properties.getProperty("java.version")}\njava.vm.name=${properties.getProperty("java.vm.name")}"
                    } finally { receipt.delete() }
                } else NativeBridge.diagnoseJvm(runtime.home.absolutePath, runtime.jvm.absolutePath, temporary.absolutePath, log.absolutePath)
                runCatching { log.appendText(description + "\n") }
                result.putString("message", description)
                result.putBoolean("success", true)
            } catch (error: Throwable) {
                val description = "JVM 自检失败：${error.message ?: error.javaClass.simpleName}"
                runCatching { log.appendText(description + "\n") }
                result.putString("message", description)
            }
            result.putString("log", log.absolutePath)
            try { reply.send(Message.obtain().apply { what = 2; data = result }) } catch (_: RemoteException) { }
        }, "launchapk-jvm-probe").start()
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean {
        // Android explicitly assigns this non-exported service to :jvm. A VM cannot be safely unloaded/recreated.
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }
}
