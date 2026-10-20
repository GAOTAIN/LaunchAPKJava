package com.launchapk.java.launcher

import com.launchapk.java.core.HttpSource
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** All archive links are materialized as regular files; none are created on disk. */
internal object RuntimeTarExtractor {
    fun extract(archives: List<File>, root: File, checkpoint: () -> Unit = {}) {
        data class Link(val target: File, val source: File)
        val base = root.canonicalFile.toPath()
        val paths = mutableSetOf<String>()
        val links = mutableMapOf<String, Link>()
        var count = 0
        var expanded = 0L
        fun copy(input: InputStream, target: File) {
            var size = 0L
            target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkpoint()
                    val length = input.read(buffer); if (length < 0) break
                    size += length; expanded += length
                    require(size <= 512L * 1024 * 1024 && expanded <= 2L * 1024 * 1024 * 1024) { "运行时解压体积超限" }
                    output.write(buffer, 0, length)
                }
            }
        }
        fun name(raw: String): String {
            var value = raw
            while (value.startsWith("./")) value = value.removePrefix("./")
            value = value.trimEnd('/')
            require(value.length <= 512 && !value.startsWith('/') && !value.contains('\\') && !value.contains(':') && !value.contains('\u0000') &&
                (value.isEmpty() || value.split('/').none { it.isEmpty() || it == "." || it == ".." })) { "运行时包含无效路径" }
            return value
        }
        for (archive in archives) {
            archive.inputStream().buffered().use { compressed ->
                XZInputStream(compressed, 65536).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        while (true) {
                            checkpoint()
                            val entry = tar.nextEntry ?: break
                            require(++count <= 20000 && tar.canReadEntryData(entry) && !entry.isSparse) { "运行时文件数或 TAR 类型不受支持" }
                            val relative = name(entry.name)
                            if (relative.isEmpty()) { require(entry.isDirectory); continue }
                            val target = root.resolve(relative).canonicalFile
                            require(target.toPath().startsWith(base)) { "运行时路径越界" }
                            if (entry.isDirectory) {
                                require(relative !in paths); check(target.isDirectory || target.mkdirs()); continue
                            }
                            require(paths.add(relative)) { "运行时包含重复文件路径" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                            when {
                                entry.isSymbolicLink || entry.isLink -> {
                                    val raw = entry.linkName
                                    require(raw.isNotBlank() && raw.length <= 512 && !raw.startsWith('/') && !raw.contains('\\') && !raw.contains(':') && !raw.contains('\u0000')) { "运行时包含无效链接" }
                                    val source = (if (entry.isSymbolicLink) target.parentFile!!.resolve(raw) else root.resolve(raw)).canonicalFile
                                    require(source.toPath().startsWith(base) && source != target) { "运行时链接越界或指向自身" }
                                    links[relative] = Link(target, source)
                                }
                                entry.isFile -> {
                                    require(entry.size in 0..(512L * 1024 * 1024)) { "运行时文件过大" }
                                    copy(tar, target)
                                    if (entry.mode and 0b001001001 != 0) target.setExecutable(true, true)
                                }
                                else -> error("运行时包含设备、FIFO 或其他不支持的条目")
                            }
                        }
                    }
                }
            }
        }
        val visiting = mutableSetOf<String>()
        fun materialize(relative: String, depth: Int = 0) {
            val link = links[relative] ?: return
            require(depth < 32 && visiting.add(relative)) { "运行时包含循环链接" }
            if (!link.source.isFile) materialize(link.source.relativeTo(root).invariantSeparatorsPath, depth + 1)
            require(link.source.isFile) { "运行时包含悬空链接：$relative" }
            link.source.inputStream().use { copy(it, link.target) }
            visiting.remove(relative); links.remove(relative)
        }
        while (links.isNotEmpty()) materialize(links.keys.first())
    }
}

class AutomaticRuntimeInstaller(private val filesDir: File, private val cacheDir: File, private val source: HttpSource) {
    suspend fun install(spec: RuntimePackage, progress: suspend (Long, Long, String) -> Unit = { _, _, _ -> }): InstalledRuntime {
        spec.validate()
        val installer = RuntimeInstaller(filesDir, cacheDir)
        val current = try { installer.inspect(spec.abi) } catch (_: IllegalArgumentException) { null }
        if (current != null && runtimeJavaMajor(current.version) == spec.major) return current
        val temporary = File(cacheDir, "runtime-downloads/${UUID.randomUUID()}")
        check(temporary.isDirectory || temporary.mkdirs())
        val context = coroutineContext
        var downloaded = 0L
        try {
            val archives = spec.archives.mapIndexed { index, part ->
                val target = File(temporary, "$index.tar.xz")
                source.open(part.url).use { body ->
                    require(body.size < 0 || body.size == part.size) { "运行时下载大小与固定清单不一致" }
                    val hash = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    target.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            context.ensureActive()
                            val length = body.stream.read(buffer); if (length < 0) break
                            count += length; require(count <= part.size) { "运行时下载超过预期大小" }
                            hash.update(buffer, 0, length); output.write(buffer, 0, length)
                            progress(downloaded + count, spec.downloadBytes, "下载 Java ${spec.major}：${(downloaded + count) * 100 / spec.downloadBytes}%")
                        }
                    }
                    require(count == part.size) { "运行时下载不完整" }
                    require(hash.digest().joinToString("") { "%02x".format(it) } == part.sha256) { "运行时 SHA-256 不匹配，已拒绝安装" }
                }
                downloaded += part.size
                target
            }
            progress(downloaded, spec.downloadBytes, "SHA-256 已通过，正在解压、检查架构与 Java 版本…")
            return installer.exclusive {
                val stage = File(installer.home.parentFile, ".auto-${UUID.randomUUID()}")
                check(stage.mkdir())
                try {
                    RuntimeTarExtractor.extract(archives, stage) { context.ensureActive() }
                    File(stage, "launchapk-runtime.properties").writeText("java.major=${spec.major}\nabi=${spec.abi}\n" + spec.archives.mapIndexed { index, part ->
                        "archive.$index.sha256=${part.sha256}\narchive.$index.url=${part.url}\n"
                    }.joinToString(""))
                    context.ensureActive()
                    installer.publishPrepared(stage, spec.abi, spec)
                } finally { if (stage.exists()) stage.deleteRecursively() }
            }
        } finally { temporary.deleteRecursively() }
    }
}
