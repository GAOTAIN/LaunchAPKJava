package com.launchapk.java.core

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

enum class ModLoader(val title: String) {
    FORGE("Forge"), FABRIC("Fabric"), NEOFORGE("NeoForge")
}

data class LoaderRelease(val loader: ModLoader, val gameVersion: String, val version: String,
    val stable: Boolean = true, val recommended: Boolean = false) {
    val displayVersion: String get() = version.removePrefix("$gameVersion-")

    fun downloadSpec(): DownloadSpec {
        loaderId(gameVersion)
        loaderId(version)
        val url: String
        val path: String
        if (loader == ModLoader.FABRIC) {
            url = "$FABRIC_META/versions/loader/$gameVersion/$version/profile/json"
            val id = "fabric-loader-$version-$gameVersion"
            safeId(id)
            path = "minecraft/versions/$id/$id.json"
        } else {
            val legacy = loader == ModLoader.FORGE || gameVersion == "1.20.1"
            require(if (legacy) version.startsWith("$gameVersion-") else neoMinecraftVersion(version) == gameVersion) {
                "加载器与 Minecraft 版本不匹配"
            }
            val artifact = if (legacy) "forge" else "neoforge"
            val root = if (loader == ModLoader.FORGE) FORGE_MAVEN else "$NEO_MAVEN/$artifact"
            val filename = "$artifact-$version-installer.jar"
            url = "$root/$version/$filename"
            path = "loaders/${loader.name.lowercase()}/$gameVersion/$version/$filename"
        }
        return DownloadSpec("loader", "${loader.title} $displayVersion · $gameVersion", url, path,
            version = gameVersion, source = loader.title, loader = this)
    }
}

internal const val FABRIC_META = "https://meta.fabricmc.net/v2"
internal const val FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge"
internal const val NEO_MAVEN = "https://maven.neoforged.net/releases/net/neoforged"
private const val FORGE_PROMOTIONS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"

internal fun loaderId(value: String): String {
    require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,99}")) && !value.contains("..")) { "加载器版本 ID 无效" }
    return value
}

/** NeoForged's 20/21 series and 26+ four-component version conventions. */
internal fun neoMinecraftVersion(version: String): String? {
    val match = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-(?:beta|alpha|rc)(?:\\.\\d+)?)?").matchEntire(version) ?: return null
    val (major, minor, patch, build) = match.destructured
    val number = major.toIntOrNull() ?: return null
    return when {
        number in 20..21 && build.isEmpty() -> "1.$major" + if (minor == "0") "" else ".$minor"
        number >= 26 && build.isNotEmpty() -> "$major.$minor" + if (patch == "0") "" else ".$patch"
        else -> null
    }
}

private fun mavenVersions(bytes: ByteArray): List<String> {
    val xml = bytes.toString(Charsets.UTF_8)
    require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "加载器清单包含不支持的 XML 声明" }
    val document = Jsoup.parse(xml, "", Parser.xmlParser())
    require(document.selectFirst("metadata > versioning > versions") != null) { "官方 Maven 版本清单格式无效" }
    val versions = document.select("metadata > versioning > versions > version").map { loaderId(it.text()) }.distinct()
    require(versions.size <= 100000) { "加载器版本清单过大" }
    return versions
}

internal fun compareLoaderVersions(left: String, right: String): Int {
    val parts = Regex("[0-9]+|[^0-9]+")
    val a = parts.findAll(left).map { it.value }.toList()
    val b = parts.findAll(right).map { it.value }.toList()
    for (index in 0 until minOf(a.size, b.size)) {
        val x = a[index]
        val y = b[index]
        val result = if (x.all(Char::isDigit) && y.all(Char::isDigit)) {
            val nx = x.trimStart('0').ifEmpty { "0" }
            val ny = y.trimStart('0').ifEmpty { "0" }
            nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
        } else x.compareTo(y)
        if (result != 0) return result
    }
    // The unsuffixed release sorts after its prerelease.
    return b.size.compareTo(a.size)
}

internal fun parseMavenLoaders(bytes: ByteArray, loader: ModLoader, game: String, recommended: String? = null): List<LoaderRelease> {
    loaderId(game)
    require(loader != ModLoader.FABRIC)
    return mavenVersions(bytes).filter { version ->
        if (loader == ModLoader.FORGE || game == "1.20.1") version.startsWith("$game-")
        else neoMinecraftVersion(version) == game
    }.map { version ->
        LoaderRelease(loader, game, version, !version.contains(Regex("(?i)(alpha|beta|rc|snapshot)")),
            recommended != null && version.removePrefix("$game-") == recommended)
    }.sortedWith(compareByDescending<LoaderRelease> { it.recommended }.thenComparator { a, b -> compareLoaderVersions(b.version, a.version) })
}

internal fun parseFabricLoaders(bytes: ByteArray, game: String): List<LoaderRelease> {
    loaderId(game)
    val array = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonArray
    require(array.size() <= 10000) { "Fabric 版本清单过大" }
    return array.mapNotNull { item ->
        val value = item.asJsonObject
        if (value.getAsJsonObject("intermediary")["version"].asString != game) null else {
            val loader = value.getAsJsonObject("loader")
            LoaderRelease(ModLoader.FABRIC, game, loaderId(loader["version"].asString), loader["stable"].asBoolean)
        }
    }.distinctBy { it.version }.sortedWith { a, b -> compareLoaderVersions(b.version, a.version) }
}

class ModLoaderSources(private val source: HttpSource = PublicHttpsSource()) {
    suspend fun versions(loader: ModLoader, game: String): List<LoaderRelease> {
        loaderId(game)
        return when (loader) {
            ModLoader.FABRIC -> parseFabricLoaders(source.readBytes("$FABRIC_META/versions/loader/$game"), game)
            ModLoader.FORGE -> {
                // An unavailable recommendations service must not hide the Maven catalog.
                val recommended = try {
                    jsonObject(source.readBytes(FORGE_PROMOTIONS)).getAsJsonObject("promos")["$game-recommended"]?.asString
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { null }
                parseMavenLoaders(source.readBytes("$FORGE_MAVEN/maven-metadata.xml"), loader, game, recommended)
            }
            ModLoader.NEOFORGE -> {
                val artifact = if (game == "1.20.1") "forge" else "neoforge"
                parseMavenLoaders(source.readBytes("$NEO_MAVEN/$artifact/maven-metadata.xml"), loader, game)
            }
        }
    }
}
