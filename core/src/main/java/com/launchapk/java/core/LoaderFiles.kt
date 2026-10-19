package com.launchapk.java.core

import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

private fun sha1Text(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).trim().lowercase().also {
    require(it.matches(Regex("[a-f0-9]{40}"))) { "官方源未提供有效 SHA-1，已停止下载" }
}

internal fun validateLoaderSpec(spec: DownloadSpec): LoaderRelease {
    val release = requireNotNull(spec.loader) { "加载器任务资料缺失，请重新选择版本" }
    val expected = release.downloadSpec()
    require(spec.kind == expected.kind && spec.url == expected.url && spec.path == expected.path &&
        spec.version == expected.version && spec.source == expected.source) { "加载器任务与官方坐标不匹配" }
    return release
}

internal fun verifyInstaller(file: File, release: LoaderRelease) {
    ZipFile(file).use { zip ->
        val entry = zip.getEntry("install_profile.json") ?: error("该文件不是受支持的加载器安装器")
        require(entry.size in 1..(2 * 1024 * 1024)) { "安装器清单大小异常" }
        val output = java.io.ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 2 * 1024 * 1024) { "安装器清单过大" }
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        val json = jsonObject(bytes)
        val minecraft = json["minecraft"]?.asString
            ?: json.getAsJsonObject("install")?.get("minecraft")?.asString
            ?: json.getAsJsonObject("versionInfo")?.get("inheritsFrom")?.asString
        require(minecraft == release.gameVersion) { "安装器的 Minecraft 版本不匹配，已停止保存" }
    }
}

/** No installer code or processor is executed by this downloader. */
class LoaderFiles(private val source: HttpSource, private val root: File) {
    suspend fun download(spec: DownloadSpec, progress: suspend (Long, Long, String) -> Unit): File {
        val release = validateLoaderSpec(spec)
        require(ModLoaderSources(source).versions(release.loader, release.gameVersion).any { it.version == release.version }) {
            "官方清单中已无此加载器版本，请刷新列表"
        }
        return if (release.loader == ModLoader.FABRIC) prepareFabric(release, spec, progress) else {
            val sha1 = sha1Text(source.readBytes(spec.url + ".sha1", 1024))
            transfer(source, spec.copy(kind = "content", sha1 = sha1, sha512 = "", size = -1), root, maxBytes = 128L * 1024 * 1024,
                validate = { verifyInstaller(it, release) }) { done, total ->
                progress(done, total, if (total > 0) "下载安装器 ${done * 100 / total}%" else "已下载 ${done / 1024} KB")
            }
        }
    }

    private suspend fun prepareFabric(release: LoaderRelease, spec: DownloadSpec, progress: suspend (Long, Long, String) -> Unit): File {
        val baseId = safeId(release.gameVersion)
        val baseFile = containedFile(root, "minecraft/versions/$baseId/$baseId.json")
        require(baseFile.isFile && baseFile.length() in 1..(16 * 1024 * 1024)) { "请先在原版分类下载 Minecraft $baseId，再准备 Fabric" }
        val base = jsonObject(baseFile.readBytes())
        require(base["id"]?.asString == baseId && !base.has("inheritsFrom")) { "原版配置无效，请重新下载原版" }
        val client = artifact(base.getAsJsonObject("downloads").getAsJsonObject("client"), "minecraft/versions/$baseId/$baseId.jar")
        require(matchesFile(containedFile(root, client.path), client.size, client.sha1, "")) { "原版客户端缺失或校验失败，请重新下载原版" }

        val profile = jsonObject(source.readBytes(spec.url, 2 * 1024 * 1024))
        val libraries = fabricLibraries(profile, release)
        for ((index, item) in libraries.withIndex()) {
            coroutineContext.ensureActive()
            val library = item.first
            var dependency = item.second
            if (dependency.sha1.isEmpty() && dependency.sha512.isEmpty()) {
                dependency = dependency.copy(sha1 = sha1Text(source.readBytes(dependency.url + ".sha1", 1024)))
            }
            val file = transfer(source, dependency, root, maxBytes = 64L * 1024 * 1024) { _, _ ->
                progress(index.toLong(), libraries.size.toLong(), "校验 Fabric 库 ${index + 1}/${libraries.size} · ${fileName(dependency)}")
            }
            // Add the standard artifact descriptor for the future inherited-version resolver.
            // The metadata itself is delivered over HTTPS; every dependency has a published hash.
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val sha1 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (dependency.sha1.isNotEmpty()) require(sha1.equals(dependency.sha1, true)) { "Fabric 库 SHA-1 不匹配" }
            library.add("downloads", JsonObject().also { downloads ->
                downloads.add("artifact", JsonObject().also {
                    it.addProperty("path", dependency.path.removePrefix("minecraft/libraries/"))
                    it.addProperty("url", dependency.url)
                    it.addProperty("size", file.length())
                    it.addProperty("sha1", sha1)
                })
            })
        }
        coroutineContext.ensureActive()
        val bytes = profile.toString().toByteArray(Charsets.UTF_8)
        val memory = object : HttpSource {
            override fun open(url: String) = HttpBody(ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        val result = transfer(memory, spec.copy(kind = "metadata", size = bytes.size.toLong(), sha1 = hashBytes(bytes, "SHA-1"), sha512 = ""), root) { _, _ -> }
        progress(libraries.size.toLong(), libraries.size.toLong(), "Fabric 配置与依赖已保存；游戏启动适配待完成")
        return result
    }
}

private fun fileName(spec: DownloadSpec): String = spec.path.substringAfterLast('/')

internal fun fabricLibraries(profile: JsonObject, release: LoaderRelease): List<Pair<JsonObject, DownloadSpec>> {
    require(profile["id"]?.asString == "fabric-loader-${release.version}-${release.gameVersion}" &&
        profile["inheritsFrom"]?.asString == release.gameVersion) { "Fabric 配置与所选版本不匹配" }
    require(profile["mainClass"]?.asString in setOf("net.fabricmc.loader.impl.launch.knot.KnotClient", "net.fabricmc.loader.launch.knot.KnotClient")) {
        "不是受支持的 Fabric 客户端配置"
    }
    val libraries = profile.getAsJsonArray("libraries") ?: error("Fabric 配置缺少依赖库")
    require(libraries.size() in 1..128) { "Fabric 依赖数量异常" }
    val names = libraries.map { it.asJsonObject["name"].asString }
    require("net.fabricmc:fabric-loader:${release.version}" in names && "net.fabricmc:intermediary:${release.gameVersion}" in names) {
        "Fabric 配置缺少匹配的加载器或映射库"
    }
    val result = libraries.map { element ->
        val library = element.asJsonObject
        val parts = library["name"].asString.split(':')
        require(parts.size in 3..4 && parts[0].matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))) { "Fabric Maven 坐标无效" }
        parts.drop(1).forEach(::loaderId)
        val (group, artifact, version) = parts
        val classifier = if (parts.size == 4) "-${parts[3]}" else ""
        val path = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version$classifier.jar"
        val repository = library["url"]?.asString ?: error("Fabric 库缺少官方仓库")
        require(repository in setOf("https://maven.fabricmc.net/", "https://libraries.minecraft.net/")) { "Fabric 库仓库不受支持" }
        val sha1 = library["sha1"]?.asString.orEmpty()
        val sha512 = library["sha512"]?.asString.orEmpty()
        require(sha1.isEmpty() || sha1.matches(Regex("[a-fA-F0-9]{40}"))) { "Fabric SHA-1 格式错误" }
        require(sha512.isEmpty() || sha512.matches(Regex("[a-fA-F0-9]{128}"))) { "Fabric SHA-512 格式错误" }
        val size = library["size"]?.asLong ?: -1
        require(size == -1L || size in 0..(64L * 1024 * 1024)) { "Fabric 库大小异常" }
        library to DownloadSpec("content", "Fabric 依赖", repository + path, "minecraft/libraries/$path", size, sha1, sha512)
    }
    require(result.map { it.second.path }.distinct().size == result.size) { "Fabric 配置包含重复依赖" }
    return result
}
