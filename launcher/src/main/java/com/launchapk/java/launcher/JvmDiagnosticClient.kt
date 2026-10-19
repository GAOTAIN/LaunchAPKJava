package com.launchapk.java.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*

class JvmDiagnosticClient(private val context: Context, private val mainProbe: Boolean = false,
    private val onResult: (Boolean, String) -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var bound = false
    private var completed = false
    private val timeout = Runnable { finish(false, "JVM 自检超时，运行进程已停止。请检查运行时和应用私有 logs 目录。") }
    private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == 2) finish(message.data.getBoolean("success"),
            message.data.getString("message").orEmpty() + "\n日志：" + message.data.getString("log").orEmpty())
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (completed) return
            try { Messenger(service).send(Message.obtain().apply { what = if (mainProbe) 3 else 1; replyTo = reply })
            } catch (_: RemoteException) { finish(false, "JVM 进程连接失败，请检查运行时与 logs 目录") }
        }
        override fun onServiceDisconnected(name: ComponentName) { finish(false, "JVM 进程退出或崩溃，请检查应用私有 logs 目录") }
        override fun onNullBinding(name: ComponentName) { finish(false, "JVM 进程没有提供测试接口") }
        override fun onBindingDied(name: ComponentName) { finish(false, "JVM 进程连接已失效") }
    }
    fun run() {
        check(!bound && !completed)
        bound = context.bindService(Intent(context, JvmDiagnosticService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) finish(false, "无法建立 JVM 自检进程") else handler.postDelayed(timeout, 30000)
    }
    private fun finish(success: Boolean, message: String) {
        if (completed) return
        completed = true
        close()
        onResult(success, message)
    }
    override fun close() {
        completed = true
        handler.removeCallbacks(timeout)
        if (bound) {
            bound = false
            try { context.unbindService(connection) } catch (_: IllegalArgumentException) { }
        }
    }
}
