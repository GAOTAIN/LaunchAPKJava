package com.launchapk.java.launcher

import com.google.gson.JsonParser
import java.net.URI

data class RuntimeArchive(val url: String, val size: Long, val sha256: String)
data class RuntimePackage(val major: Int, val abi: String, val version: String, val archives: List<RuntimeArchive>) {
    val downloadBytes: Long get() = archives.sumOf { it.size }
    fun validate(): RuntimePackage {
        require(major in setOf(8, 17, 21) && abi in setOf("arm64-v8a", "x86_64")) { "没有适配此 Java/设备架构的运行时" }
        require(runtimeJavaMajor(version) == major && archives.size == 2) { "运行时版本或分包配置无效" }
        archives.forEach { part ->
            val uri = URI(part.url)
            require(uri.scheme == "https" && uri.host == "raw.githubusercontent.com" && uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null) { "运行时下载源无效" }
            require(uri.path.matches(Regex("/FCL-Team/FoldCraftLauncher/[a-f0-9]{40}/FCL/src/main/jreAssets/app_runtime/java/jre$major/(universal|bin-arm64|bin-x86_64)\\.tar\\.xz"))) { "运行时来源不在固定发布目录" }
            require(part.size in 1..(512L * 1024 * 1024) && part.sha256.matches(Regex("[a-f0-9]{64}"))) { "运行时大小或 SHA-256 无效" }
        }
        require(archives[0].url.endsWith("/universal.tar.xz") && archives[1].url.endsWith(if (abi == "arm64-v8a") "/bin-arm64.tar.xz" else "/bin-x86_64.tar.xz")) { "运行时分包与设备架构不匹配" }
        require(archives.map { it.url.substringBeforeLast('/') }.distinct().size == 1) { "不能混用不同来源版本的分包" }
        return this
    }
}

fun runtimeJavaMajor(version: String): Int? = if (version.startsWith("1.8.")) 8
    else version.substringBefore('.').substringBefore('-').substringBefore('+').toIntOrNull()

class RuntimeCatalog private constructor(val packages: List<RuntimePackage>) {
    fun select(major: Int, abi: String): RuntimePackage = packages.singleOrNull { it.major == major && it.abi == abi }
        ?: error("暂未提供 Java $major / $abi 的 Android 运行时")
    companion object {
        fun decode(text: String): RuntimeCatalog {
            require(text.length <= 65536) { "运行时清单过大" }
            val root = JsonParser.parseString(text).asJsonObject
            require(root["schemaVersion"].asInt == 1)
            val packages = root.getAsJsonArray("packages").map { value ->
                val item = value.asJsonObject
                RuntimePackage(item["major"].asInt, item["abi"].asString, item["version"].asString,
                    item.getAsJsonArray("archives").map { entry -> entry.asJsonObject.let { RuntimeArchive(it["url"].asString, it["size"].asLong, it["sha256"].asString) } }).validate()
            }
            require(packages.size == 6 && packages.map { it.major to it.abi }.distinct().size == 6) { "运行时清单缺项或重复" }
            return RuntimeCatalog(packages)
        }
    }
}
