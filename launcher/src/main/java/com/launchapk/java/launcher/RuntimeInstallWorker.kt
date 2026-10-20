package com.launchapk.java.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.*
import com.launchapk.java.core.PublicHttpsSource
import com.launchapk.java.core.SourceHttpException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val WORK_NAME = "launchapk.runtime.install"
private const val CHANNEL = "launchapk_runtime"

data class RuntimeInstallTask(val id: UUID, val major: Int, val abi: String, val state: String,
    val done: Long, val total: Long, val message: String) {
    val active: Boolean get() = state !in setOf("SUCCEEDED", "FAILED", "CANCELLED")
}

class RuntimeInstallRepository(context: Context) {
    private val app = context.applicationContext
    private val work = WorkManager.getInstance(app)
    fun catalog(): RuntimeCatalog = app.assets.open("runtime-catalog.json").bufferedReader().use { RuntimeCatalog.decode(it.readText()) }
    suspend fun current(): RuntimeInstallTask? = withContext(Dispatchers.IO) {
        val infos = work.getWorkInfosForUniqueWork(WORK_NAME).get()
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.maxByOrNull { entry ->
            entry.tags.firstOrNull { it.startsWith("created:") }?.substringAfter(':')?.toLongOrNull() ?: 0
        } ?: return@withContext null
        RuntimeInstallTask(info.id, info.tags.firstOrNull { it.startsWith("java:") }?.substringAfter(':')?.toIntOrNull() ?: 0,
            info.tags.firstOrNull { it.startsWith("abi:") }?.substringAfter(':').orEmpty(), info.state.name,
            info.progress.getLong("done", 0), info.progress.getLong("total", 0),
            when (info.state) {
                WorkInfo.State.CANCELLED -> "已取消；未完成的安装不会替换原有运行时"
                WorkInfo.State.FAILED -> info.outputData.getString("message") ?: "运行时安装失败，请重试"
                else -> info.outputData.getString("message") ?: info.progress.getString("message") ?:
                    if (info.state == WorkInfo.State.ENQUEUED) "等待网络或自动重试" else "准备运行时"
            })
    }
    suspend fun enqueue(major: Int, abi: String): UUID = withContext(Dispatchers.IO) {
        catalog().select(major, abi)
        current()?.takeIf { it.active }?.let { existing ->
            require(existing.major == major && existing.abi == abi) { "正在安装其他 Java 版本，请等待完成或先取消" }
            return@withContext existing.id
        }
        val request = OneTimeWorkRequestBuilder<RuntimeInstallWorker>()
            .setInputData(workDataOf("major" to major, "abi" to abi))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("java:$major").addTag("abi:$abi").addTag("created:${System.currentTimeMillis()}").build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request).result.get()
        // KEEP can choose another simultaneous request: return the task WorkManager actually retained.
        val retained = current() ?: error("运行时任务未入队")
        require(retained.major == major && retained.abi == abi) { "已有其他运行时安装任务" }
        retained.id
    }
    fun cancel(id: UUID) { work.cancelWorkById(id) }
}

class RuntimeInstallWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val major = inputData.getInt("major", 0)
    private var lastReport = 0L
    private fun notification(message: String): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Java 运行环境", NotificationManager.IMPORTANCE_LOW))
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = Notification.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("安装 Android Java $major").setContentText(message.take(120)).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode() and Int.MAX_VALUE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode() and Int.MAX_VALUE, notification)
    }
    override suspend fun getForegroundInfo(): ForegroundInfo = notification("准备下载")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val abi = inputData.getString("abi").orEmpty()
            val spec = RuntimeInstallRepository(applicationContext).catalog().select(major, abi)
            setForeground(notification("连接固定发布源"))
            val runtime = AutomaticRuntimeInstaller(applicationContext.filesDir, applicationContext.cacheDir, PublicHttpsSource()).install(spec) { done, total, message ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport >= 1000 || done == total) {
                    lastReport = now
                    setProgress(workDataOf("done" to done, "total" to total, "message" to message))
                    setForeground(notification(message))
                }
            }
            Result.success(workDataOf("message" to "Android Java ${runtime.version} 已安装，SHA-256、架构与版本检查通过"))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            if (failure is IOException && !(failure is SourceHttpException && failure.status in setOf(401, 403, 404, 429)) && runAttemptCount < 2) {
                setProgress(workDataOf("message" to "网络中断，等待自动重试：${failure.message}".take(300)))
                Result.retry()
            } else Result.failure(workDataOf("message" to (failure.message ?: "运行时安装失败").take(400)))
        }
    }
}
