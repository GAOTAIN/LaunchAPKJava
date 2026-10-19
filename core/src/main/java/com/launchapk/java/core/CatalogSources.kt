package com.launchapk.java.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun jsonObject(bytes: ByteArray): JsonObject = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
fun mcmodPage(url: String): String {
    val uri = httpsUri(url)
    require(uri.host == "www.mcmod.cn" && uri.rawQuery == null && uri.path.matches(Regex("""/(class|modpack)/[0-9]+\.html"""))) { "请提供 MC百科 /class/数字.html 或 /modpack/数字.html 资料页" }
    return "https://www.mcmod.cn${uri.path}"
}
fun robotsAllows(text: String, path: String): Boolean {
    data class Group(val agents: MutableList<String> = mutableListOf(), val rules: MutableList<Pair<String, Boolean>> = mutableListOf(), var readingRules: Boolean = false)
    val groups = mutableListOf<Group>()
    var group = Group()
    text.lineSequence().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        val key = line.substringBefore(':').lowercase()
        val value = line.substringAfter(':', "").trim()
        if (key == "user-agent") {
            if (group.readingRules) { groups += group; group = Group() }
            group.agents += value.lowercase()
        } else if (key in listOf("allow", "disallow")) { group.readingRules = true; if (value.isNotEmpty()) group.rules += value to (key == "allow") }
    }
    groups += group
    fun specificity(g: Group): Int = g.agents.filter { it == "*" || "launchapkjava".contains(it) }.maxOfOrNull { if (it == "*") 0 else it.length } ?: -1
    val best = groups.maxOfOrNull(::specificity) ?: -1
    val rules = groups.filter { specificity(it) == best && best >= 0 }.flatMap { it.rules }
    return rules.filter { (rule, _) ->
        val pattern = rule.removeSuffix("$").split('*').joinToString(".*") { Regex.escape(it) }
        Regex("^" + pattern + if (rule.endsWith('$')) "$" else "").containsMatchIn(path)
    }.maxWithOrNull(compareBy<Pair<String, Boolean>> { it.first.length }.thenBy { it.second })?.second ?: true
}
fun parseMcmod(html: String, url: String): McmodEntry {
    val page = mcmodPage(url)
    val document = Jsoup.parse(html, page)
    val links = document.select(".common-link-frame a[href]").mapNotNull { anchor ->
        runCatching {
            var target = URI(page).resolve(anchor.attr("href")).toString()
            val uri = httpsUri(target)
            // This is a publicly published target, not a request to a jump endpoint.
            if (uri.host == "link.mcmod.cn" && uri.path.startsWith("/target/")) {
                target = String(Base64.getDecoder().decode(uri.path.removePrefix("/target/")), Charsets.UTF_8)
            }
            val final = httpsUri(target)
            if (final.host in listOf("modrinth.com", "www.modrinth.com", "www.curseforge.com", "curseforge.com")) target else null
        }.getOrNull()
    }.distinct().take(20)
    return McmodEntry(document.title().substringBefore(" - MC百科").take(160).ifBlank { "MC百科资料" }, page, links)
}
fun modrinthProject(url: String): String? {
    val uri = runCatching { httpsUri(url) }.getOrNull() ?: return null
    if (uri.host !in listOf("modrinth.com", "www.modrinth.com")) return null
    val parts = uri.path.trim('/').split('/')
    return if (parts.size == 2 && parts[0] in listOf("mod", "modpack", "shader", "resourcepack")) runCatching { safeId(parts[1]) }.getOrNull() else null
}
fun parseVersions(bytes: ByteArray): List<GameVersion> = jsonObject(bytes).getAsJsonArray("versions").map {
    val value = it.asJsonObject
    GameVersion(safeId(value["id"].asString), value["type"].asString, value["url"].asString, value["sha1"].asString)
}
fun parseContentFiles(bytes: ByteArray, project: String, kind: ContentKind): List<ContentFile> =
    JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonArray.flatMap { item ->
        val version = item.asJsonObject
        version.getAsJsonArray("files").mapNotNull { value ->
            val file = value.asJsonObject
            val name = file["filename"].asString
            val sha = file.getAsJsonObject("hashes")["sha512"]?.asString.orEmpty()
            if (!kind.accepts(name) || !sha.matches(Regex("[a-fA-F0-9]{128}"))) null else ContentFile(
                version["name"].asString.take(160), safeFilename(name), file["url"].asString, file["size"].asLong, sha,
                safeId(version["id"].asString), project, version.getAsJsonArray("game_versions").joinToString { it.asString },
                version.getAsJsonArray("loaders").joinToString { it.asString })
        }
    }.take(100)

class CatalogSources(private val source: HttpSource = PublicHttpsSource()) {
    private val lock = Mutex()
    private val pages = mutableMapOf<String, McmodEntry>()
    private var lastRequest = 0L
    suspend fun gameVersions(): List<GameVersion> = parseVersions(source.readBytes("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    suspend fun mcmod(url: String): McmodEntry = lock.withLock {
        val page = mcmodPage(url)
        pages[page]?.let { return@withLock it }
        delay((1500 - (System.currentTimeMillis() - lastRequest)).coerceAtLeast(0))
        val robots = source.readBytes("https://www.mcmod.cn/robots.txt", 128 * 1024).toString(Charsets.UTF_8)
        require(robotsAllows(robots, URI(page).path)) { "网站规则目前不允许自动读取该资料页，请使用原网页" }
        lastRequest = System.currentTimeMillis()
        parseMcmod(source.readBytes(page, 4 * 1024 * 1024).toString(Charsets.UTF_8), page).also {
            if (pages.size >= 30) pages.clear()
            pages[page] = it
        }
    }
    suspend fun contentFiles(project: String, kind: ContentKind, gameVersion: String, loader: String): List<ContentFile> {
        val id = safeId(project)
        val info = jsonObject(source.readBytes("https://api.modrinth.com/v2/project/$id"))
        require(info["project_type"].asString == kind.projectType) { "该来源类型为 ${info["project_type"].asString}，请选择相应分类" }
        fun encoded(value: String) = URLEncoder.encode(JsonArray().also { it.add(value) }.toString(), "UTF-8")
        val query = buildList {
            add("include_changelog=false")
            if (gameVersion.isNotBlank()) add("game_versions=" + encoded(gameVersion.trim()))
            if (loader.isNotBlank()) add("loaders=" + encoded(loader.trim()))
        }.joinToString("&")
        return parseContentFiles(source.readBytes("https://api.modrinth.com/v2/project/$id/version?$query"), id, kind)
    }
}
