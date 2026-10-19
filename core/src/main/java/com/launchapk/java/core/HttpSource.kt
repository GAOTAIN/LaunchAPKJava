package com.launchapk.java.core

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

class HttpBody(val stream: InputStream, val size: Long, val closeConnection: () -> Unit = {}) : Closeable {
    override fun close() { try { stream.close() } finally { closeConnection() } }
}
interface HttpSource { fun open(url: String): HttpBody }
class SourceHttpException(val status: Int) : IOException("网络请求失败 HTTP $status；请稍后重试或在原站查看")
class PublicHttpsSource : HttpSource {
    override fun open(url: String): HttpBody {
        var current = url
        repeat(6) {
            val uri = httpsUri(current)
            val addresses = InetAddress.getAllByName(uri.host)
            require(addresses.isNotEmpty() && addresses.none { a ->
                a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress ||
                    (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)
            }) { "下载地址不能指向本地网络" }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "LaunchAPKJava/0.1 (Android; user-requested downloads)")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "下载跳转缺少地址" }
                current = uri.resolve(location).toString()
            } else {
                if (status !in 200..299) {
                    connection.disconnect()
                    throw SourceHttpException(status)
                }
                return HttpBody(connection.inputStream, connection.contentLengthLong) { connection.disconnect() }
            }
        }
        throw IOException("下载跳转次数过多")
    }
}
suspend fun HttpSource.readBytes(url: String, limit: Int = 16 * 1024 * 1024): ByteArray = open(url).use { body ->
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(32768)
    while (true) {
        coroutineContext.ensureActive()
        val length = body.stream.read(buffer)
        if (length < 0) break
        require(output.size().toLong() + length <= limit) { "资料响应过大" }
        output.write(buffer, 0, length)
    }
    output.toByteArray()
}
fun hashBytes(bytes: ByteArray, algorithm: String): String = MessageDigest.getInstance(algorithm).digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
fun matchesFile(file: File, size: Long, sha1: String, sha512: String): Boolean {
    if (!file.isFile || size >= 0 && file.length() != size || sha1.isEmpty() && sha512.isEmpty()) return false
    val algorithm = if (sha512.isNotEmpty()) "SHA-512" else "SHA-1"
    val expected = sha512.ifEmpty { sha1 }
    val digest = MessageDigest.getInstance(algorithm)
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }.equals(expected, true)
}
suspend fun transfer(source: HttpSource, spec: DownloadSpec, root: File, maxBytes: Long = 8L * 1024 * 1024 * 1024,
    validate: (File) -> Unit = {}, progress: suspend (Long, Long) -> Unit): File {
    coroutineContext.ensureActive()
    val target = containedFile(root, spec.path)
    if (matchesFile(target, spec.size, spec.sha1, spec.sha512)) {
        require(target.length() <= maxBytes) { "文件超过大小上限" }
        validate(target)
        return target
    }
    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建下载目录" }
    val part = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
    try {
        source.open(spec.url).use { body ->
            val expected = if (spec.size >= 0) spec.size else body.size
            require(expected <= maxBytes) { "文件超过大小上限" }
            val digest = MessageDigest.getInstance(if (spec.sha512.isNotEmpty()) "SHA-512" else "SHA-1")
            var count = 0L
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    coroutineContext.ensureActive()
                    val length = body.stream.read(buffer)
                    if (length < 0) break
                    count += length
                    require(count <= maxBytes && (expected < 0 || count <= expected)) { "下载文件大小异常" }
                    output.write(buffer, 0, length)
                    digest.update(buffer, 0, length)
                    progress(count, expected)
                }
                output.fd.sync()
            }
            if (expected >= 0 && expected != count) throw IOException("下载不完整：$count / $expected")
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val hash = spec.sha512.ifEmpty { spec.sha1 }
            if (hash.isNotEmpty() && !actual.equals(hash, true)) throw IOException("文件哈希校验失败")
            if (spec.kind == "content") {
                java.util.zip.ZipFile(part).use { /* Verify the central directory without extracting files. */ }
                val signature = part.inputStream().use { input ->
                    val bytes = ByteArray(4)
                    var offset = 0
                    while (offset < 4) { val countRead = input.read(bytes, offset, 4 - offset); if (countRead < 0) break; offset += countRead }
                    bytes.copyOf(offset)
                }
                require(signature.size == 4 && signature[0] == 80.toByte() && signature[1] == 75.toByte() &&
                    (signature[2] == 3.toByte() || signature[2] == 5.toByte() || signature[2] == 7.toByte())) { "返回内容不是有效的 ZIP/JAR 包，可能需要在原站操作" }
            }
        }
        validate(part)
        coroutineContext.ensureActive()
        try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        return target
    } finally { part.delete() }
}
