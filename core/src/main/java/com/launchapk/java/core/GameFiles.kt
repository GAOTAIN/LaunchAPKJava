package com.launchapk.java.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject

fun libraryAllowed(rules: JsonArray?, arch: String = "aarch64"): Boolean {
    if (rules == null) return true
    var allowed = false
    rules.forEach { entry ->
        val rule = entry.asJsonObject
        val os = rule.getAsJsonObject("os")
        val features = rule.getAsJsonObject("features")
        val matches = (os == null ||
            (os["name"]?.asString?.let { it == "linux" } ?: true) &&
            (os["arch"]?.asString?.let { Regex(it).containsMatchIn(arch) } ?: true) &&
            (os["version"]?.asString?.let { Regex(it).containsMatchIn(System.getProperty("os.version").orEmpty()) } ?: true)) &&
            (features == null || features.entrySet().all { !it.value.asBoolean })
        if (matches) allowed = rule["action"].asString == "allow"
    }
    return allowed
}
fun artifact(value: JsonObject, path: String): Artifact {
    val hash = value["sha1"].asString
    require(hash.matches(Regex("[a-f0-9]{40}"))) { "官方文件缺少有效校验值" }
    val size = value["size"].asLong
    require(size in 0..8L * 1024 * 1024 * 1024) { "官方文件大小无效" }
    return Artifact(path, value["url"].asString.also(::httpsUri), size, hash)
}
fun gameArtifacts(version: JsonObject, id: String, arch: String = "aarch64"): List<Artifact> {
    require(version["id"].asString == safeId(id) && !version.has("inheritsFrom")) { "版本清单不匹配或为非独立版本" }
    val files = mutableListOf(artifact(version.getAsJsonObject("downloads").getAsJsonObject("client"), "minecraft/versions/$id/$id.jar"))
    version.getAsJsonArray("libraries").forEach { element ->
        val library = element.asJsonObject
        if (libraryAllowed(library.getAsJsonArray("rules"), arch)) {
            val file = library.getAsJsonObject("downloads")?.getAsJsonObject("artifact")
            // Desktop classifiers are replaced by Android-compatible runtime components later.
            if (file != null && !library["name"].asString.substringAfterLast(':').startsWith("natives-")) {
                val path = "minecraft/libraries/" + file["path"].asString
                containedFile(java.io.File("."), path) // Reject traversal without touching disk.
                files += artifact(file, path)
            }
        }
    }
    version.getAsJsonObject("logging")?.getAsJsonObject("client")?.getAsJsonObject("file")?.let {
        files += artifact(it, "minecraft/assets/log_configs/" + safeFilename(it["id"].asString))
    }
    return files.distinctBy { it.path }
}
fun assetArtifacts(index: JsonObject): List<Artifact> = index.getAsJsonObject("objects").entrySet().map { (_, entry) ->
    val value = entry.asJsonObject
    val hash = value["hash"].asString
    require(hash.matches(Regex("[a-f0-9]{40}"))) { "资源哈希无效" }
    require(value["size"].asLong in 0..8L * 1024 * 1024 * 1024) { "资源大小无效" }
    Artifact("minecraft/assets/objects/${hash.take(2)}/$hash", "https://resources.download.minecraft.net/${hash.take(2)}/$hash", value["size"].asLong, hash)
}.distinctBy { it.path }.also { require(it.size <= 100000) { "资源清单过大" } }
