package com.launchapk.java.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "LaunchAPK.Downloads"
private const val CHANNEL = "launchapk_downloads"
private val gson = Gson()

data class DownloadTask(val id: UUID, val label: String, val state: String, val done: Long, val total: Long,
    val message: String, val path: String)

class DownloadRepository(context: Context) {
    private val app = context.applicationContext
    private val work = WorkManager.getInstance(app)
    suspend fun enqueue(spec: DownloadSpec): UUID = withContext(Dispatchers.IO) {
        httpsUri(spec.url)
        if (spec.kind == "loader") validateLoaderSpec(spec)
        if (spec.kind == "game") safeId(spec.version) else containedFile(app.filesDir, spec.path)
        val key = "download_" + digestText(spec.kind + ":" + spec.path.ifBlank { spec.version })
        work.getWorkInfosForUniqueWork(key).get().firstOrNull { !it.state.isFinished }?.let { return@withContext it.id }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).addTag("title:" + spec.label.take(120)).build()
        val directory = File(app.filesDir, "downloads/plans").also { check(it.isDirectory || it.mkdirs()) }
        File(directory, "${request.id}.json").writeText(gson.toJson(spec), Charsets.UTF_8)
        work.enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, request).result.get()
        request.id
    }
    suspend fun tasks(): List<DownloadTask> = withContext(Dispatchers.IO) {
        work.getWorkInfosByTag(TAG).get().sortedBy { it.state.isFinished }.take(50).map { info ->
            DownloadTask(info.id, info.tags.firstOrNull { it.startsWith("title:") }?.removePrefix("title:") ?: "下载任务",
                info.state.name, info.progress.getLong("done", 0), info.progress.getLong("total", 0),
                info.outputData.getString("message") ?: info.progress.getString("message").orEmpty(), info.outputData.getString("path").orEmpty())
        }
    }
    fun cancel(id: UUID) { work.cancelWorkById(id) }
    suspend fun retry(id: UUID): UUID = withContext(Dispatchers.IO) {
        val file = File(app.filesDir, "downloads/plans/$id.json")
        enqueue(gson.fromJson(file.readText(Charsets.UTF_8), DownloadSpec::class.java))
    }
}

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private var label = "文件下载"
    private var lastUpdate = 0L
    private fun notification(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "文件下载", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(label.take(80))
            .setContentText(text.take(120)).setOngoing(true).setOnlyAlertOnce(true)
        applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let {
            notification.setContentIntent(PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        val number = id.hashCode() and Int.MAX_VALUE
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(number, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(number, notification.build())
    }
    override suspend fun getForegroundInfo(): ForegroundInfo = notification("准备下载")
    private suspend fun report(done: Long, total: Long, message: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUpdate < 1000) return
        lastUpdate = now
        setProgress(workDataOf("done" to done, "total" to total, "message" to message.take(200)))
        setForeground(notification(message))
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val file = File(applicationContext.filesDir, "downloads/plans/$id.json")
            require(file.length() in 1..32000) { "下载任务资料丢失" }
            val spec = gson.fromJson(file.readText(Charsets.UTF_8), DownloadSpec::class.java)
            label = spec.label
            setForeground(notification("连接下载源"))
            val source = PublicHttpsSource()
            val target = if (spec.kind == "game") installGame(spec, source) else if (spec.kind == "loader") {
                LoaderFiles(source, applicationContext.filesDir).download(spec) { done, total, text -> report(done, total, text) }
            } else {
                require(spec.kind == "content" && (spec.path.endsWith(".jar", true) || spec.path.endsWith(".zip", true) || spec.path.endsWith(".mrpack", true))) { "文件类型不受支持" }
                transfer(source, spec, applicationContext.filesDir) { done, total -> report(done, total, if (total > 0) "下载 ${(done * 100 / total)}%" else "已下载 ${done / 1024} KB") }
            }
            Result.success(workDataOf("path" to target.relativeTo(applicationContext.filesDir).invariantSeparatorsPath,
                "message" to when {
                    spec.kind == "game" -> "官方游戏文件已下载；游戏启动适配待完成"
                    spec.kind == "loader" && spec.loader?.loader == ModLoader.FABRIC -> "Fabric 配置与依赖库已保存并校验；游戏启动适配待完成"
                    spec.kind == "loader" -> "官方安装器已下载，SHA-1 与包结构校验通过；补丁安装待接入，尚未安装"
                    spec.sha512.isNotEmpty() -> "下载完成，SHA-512 校验通过"
                    else -> "下载完成，源未提供哈希；ZIP/JAR 包结构检查通过"
                }))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            if (failure is IOException && !(failure is SourceHttpException && failure.status in listOf(401, 403, 404, 429)) && runAttemptCount < 2) {
                report(0, 0, "连接失败，等待自动重试：${failure.message}", true)
                Result.retry()
            } else Result.failure(workDataOf("message" to (failure.message ?: "下载失败").take(400)))
        }
    }
    private suspend fun installGame(spec: DownloadSpec, source: HttpSource): File {
        val versionId = safeId(spec.version)
        val uri = httpsUri(spec.url)
        require(uri.host in listOf("piston-meta.mojang.com", "launchermeta.mojang.com") && spec.sha1.matches(Regex("[a-f0-9]{40}"))) { "不是有效的官方版本清单" }
        val bytes = source.readBytes(spec.url)
        require(hashBytes(bytes, "SHA-1") == spec.sha1) { "版本清单校验失败" }
        val version = jsonObject(bytes)
        val index = version.getAsJsonObject("assetIndex") ?: error("该旧版本缺少资源索引，暂不支持")
        val indexId = safeId(index["id"].asString)
        val descriptor = artifact(index, "minecraft/assets/indexes/$indexId.json")
        require(descriptor.size <= 16 * 1024 * 1024) { "资源索引过大" }
        val indexFile = transfer(source, DownloadSpec("index", label, descriptor.url, descriptor.path, descriptor.size, descriptor.sha1), applicationContext.filesDir) { _, _ -> }
        val assets = jsonObject(indexFile.readBytes())
        require(assets["virtual"]?.asBoolean != true && assets["map_to_resources"]?.asBoolean != true) { "该旧版使用传统资源目录，待接入后再下载" }
        val artifacts = (gameArtifacts(version, versionId, System.getProperty("os.arch") ?: "aarch64") + assetArtifacts(assets)).distinctBy { it.path }
        var count = 0L
        for (entry in artifacts) {
            transfer(source, DownloadSpec("gamefile", label, entry.url, entry.path, entry.size, entry.sha1), applicationContext.filesDir) { _, _ ->
                report(count, artifacts.size.toLong(), "文件 $count/${artifacts.size} · ${entry.path.substringAfterLast('/')}")
            }
            count++
            report(count, artifacts.size.toLong(), "已校验文件 $count/${artifacts.size}")
        }
        // Commit the version descriptor only after every artifact is verified.
        val metadata = spec.copy(kind = "metadata", path = "minecraft/versions/$versionId/$versionId.json", size = bytes.size.toLong())
        val memorySource = object : HttpSource {
            override fun open(url: String) = HttpBody(java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        return transfer(memorySource, metadata, applicationContext.filesDir) { _, _ -> }
    }
}
