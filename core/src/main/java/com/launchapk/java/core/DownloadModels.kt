package com.launchapk.java.core

import java.io.File
import java.net.URI
import java.security.MessageDigest

enum class ContentKind(val title: String, val directory: String, val projectType: String) {
    MOD("模组", "mods", "mod"), MODPACK("整合包", "modpacks", "modpack"),
    SHADER("光影", "shaderpacks", "shader"), RESOURCE("材质包", "resourcepacks", "resourcepack");
    fun accepts(filename: String): Boolean = when (this) {
        MOD -> filename.endsWith(".jar", true)
        MODPACK -> filename.endsWith(".mrpack", true) || filename.endsWith(".zip", true)
        else -> filename.endsWith(".zip", true)
    }
}
data class GameVersion(val id: String, val type: String, val url: String, val sha1: String)
data class McmodEntry(val title: String, val sourceUrl: String, val links: List<String>)
data class ContentFile(val title: String, val filename: String, val url: String, val size: Long,
    val sha512: String, val versionId: String, val project: String, val versions: String, val loaders: String)
data class DownloadSpec(val kind: String, val label: String, val url: String, val path: String = "",
    val size: Long = -1, val sha1: String = "", val sha512: String = "", val version: String = "", val source: String = "",
    val loader: LoaderRelease? = null)
data class Artifact(val path: String, val url: String, val size: Long, val sha1: String)

fun httpsUri(value: String): URI {
    require(value.length <= 4096) { "链接过长" }
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "仅支持标准 HTTPS 链接" }
    require(uri.host.lowercase() !in setOf("localhost", "localhost.localdomain") && !uri.host.endsWith(".local")) { "不支持本地地址" }
    return uri
}
fun safeId(value: String): String {
    require(value.matches(Regex("[A-Za-z0-9._ -]{1,100}")) && !value.contains("..") && value == value.trim() && value != ".") { "版本或项目 ID 无效" }
    return value
}
fun safeFilename(value: String): String {
    require(value.length in 1..180 && value.none { it == '/' || it == '\\' || it == ':' || it.code < 32 } && value != "." && !value.contains("..")) { "文件名无效" }
    return value
}
fun containedFile(root: File, path: String): File {
    require(path.isNotBlank() && !path.contains('\\') && !path.contains(':') && !path.startsWith('/')) { "保存路径无效" }
    require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "保存路径越界" }
    val base = root.canonicalFile
    val target = File(base, path).canonicalFile
    require(target.toPath().startsWith(base.toPath()) && target != base) { "保存路径越界" }
    return target
}
fun digestText(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 255) }
