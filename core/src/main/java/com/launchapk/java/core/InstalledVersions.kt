package com.launchapk.java.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File

data class InstalledGame(val id: String, val parent: String?, val problem: String? = null)

internal data class ResolvedGame(val id: String, val baseId: String, val jarId: String, val mainClass: String,
    val type: String, val javaMajor: Int, val assetIndex: JsonObject, val client: JsonObject,
    val libraries: List<JsonObject>, val gameArguments: List<JsonElement>, val jvmArguments: List<JsonElement>,
    val legacyArguments: String?, val logging: JsonObject?)

class InstalledVersions(private val filesDir: File) {
    fun list(): List<InstalledGame> {
        val directory = containedFile(filesDir, "minecraft/versions")
        return directory.listFiles().orEmpty().filter { it.isDirectory }.take(2000).mapNotNull { entry ->
            val id = entry.name
            if (runCatching { safeId(id) }.isFailure) return@mapNotNull null
            val file = containedFile(filesDir, "minecraft/versions/$id/$id.json")
            if (!file.isFile) return@mapNotNull null
            try {
                val json = read(id)
                InstalledGame(id, json["inheritsFrom"]?.asString)
            } catch (failure: Exception) { InstalledGame(id, null, failure.message ?: "版本配置损坏") }
        }.sortedByDescending { it.id }
    }

    private fun read(id: String): JsonObject {
        safeId(id)
        val file = containedFile(filesDir, "minecraft/versions/$id/$id.json")
        require(file.isFile && file.length() in 1..(16 * 1024 * 1024)) { "版本 $id 的 JSON 缺失或大小异常" }
        return jsonObject(file.readBytes()).also { require(it["id"]?.asString == id) { "版本目录与 JSON id 不一致：$id" } }
    }

    internal fun resolve(id: String): ResolvedGame {
        val visiting = linkedSetOf<String>()
        fun chain(current: String): List<JsonObject> {
            require(visiting.size < 16 && visiting.add(current)) { "版本继承循环或超过 16 层" }
            val value = read(current)
            val parent = value["inheritsFrom"]?.asString
            return (if (parent != null) chain(safeId(parent)) else emptyList()) + value
        }
        val hierarchy = chain(safeId(id))
        fun value(name: String): JsonElement? = hierarchy.asReversed().firstNotNullOfOrNull { it[name] }
        val libraries = linkedMapOf<String, JsonObject>()
        for (version in hierarchy) {
            version.getAsJsonArray("libraries")?.forEach { element ->
                val library = element.asJsonObject
                val coordinate = library["name"].asString.split(':')
                require(coordinate.size in 3..4 && coordinate.all { it.isNotBlank() }) { "库坐标无效" }
                // A child replaces the parent's version, preserving classifier identity.
                val key = coordinate[0] + ":" + coordinate[1] + ":" + coordinate.getOrElse(3) { "" }
                libraries[key] = library
            }
        }
        fun arguments(kind: String) = hierarchy.flatMap { it.getAsJsonObject("arguments")?.getAsJsonArray(kind)?.toList().orEmpty() }
        val main = value("mainClass")?.asString ?: error("版本缺少 mainClass")
        require(main.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) { "主类名称无效" }
        val root = hierarchy.first()
        val java = value("javaVersion")?.asJsonObject?.get("majorVersion")?.asInt ?: 8
        require(java in 8..99) { "Java 版本要求无效" }
        val baseId = root["id"].asString
        val jarId = safeId(value("jar")?.asString ?: baseId)
        // Another jar id must itself be an installed independent version.
        val client = if (jarId == baseId) root.getAsJsonObject("downloads")?.getAsJsonObject("client")
            else read(jarId).also { require(!it.has("inheritsFrom")) { "jar 引用必须指向独立版本" } }.getAsJsonObject("downloads")?.getAsJsonObject("client")
        return ResolvedGame(id, baseId, jarId, main, value("type")?.asString ?: "release", java,
            value("assetIndex")?.asJsonObject ?: error("版本缺少资产索引"), client ?: error("版本缺少客户端描述"),
            libraries.values.toList(), arguments("game"), arguments("jvm"), value("minecraftArguments")?.asString,
            value("logging")?.asJsonObject?.getAsJsonObject("client"))
    }
}
