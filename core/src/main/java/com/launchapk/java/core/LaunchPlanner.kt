package com.launchapk.java.core

import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

data class LocalLaunchIdentity(val name: String, val uuid: String) {
    internal fun validate() {
        require(name.matches(Regex("[A-Za-z0-9_]{3,16}"))) { "请选择有效的本地离线档案" }
        require(uuid == UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString()) { "离线 UUID 与昵称不一致" }
    }
}

class LaunchPlan(val versionId: String, val baseId: String, val mainClass: String, val requiredJava: Int,
    val classpath: List<File>, val gameDirectory: File, val nativeDirectory: File,
    val jvmArguments: List<String>, val gameArguments: List<String>, val assetCount: Int) {
    override fun toString() = "LaunchPlan(version=$versionId, main=$mainClass, args=redacted)"
}

/** Produces Android argv; it neither runs a shell nor creates Microsoft credentials. */
class LaunchPlanner(private val filesDir: File) {
    fun prepare(id: String, identity: LocalLaunchIdentity, abi: String, width: Int, height: Int, memoryMb: Int = 1024): LaunchPlan {
        identity.validate()
        require(abi in setOf("arm64-v8a", "x86_64")) { "启动计划仅支持 64 位 Android" }
        require(width in 320..16384 && height in 240..16384 && memoryMb in 256..4096) { "窗口尺寸或内存设置无效" }
        val game = InstalledVersions(filesDir).resolve(id)
        val arch = if (abi == "arm64-v8a") "aarch64" else "x86_64"
        val features = mapOf("is_demo_user" to false, "has_custom_resolution" to true)
        val client = checked(game.client, "minecraft/versions/${game.jarId}/${game.jarId}.jar")
        val libraries = game.libraries.filter { launchRuleAllowed(it.getAsJsonArray("rules"), arch, features) }
            .filterNot { it["name"].asString.split(':').drop(3).any { part -> part.startsWith("natives-") } }
            .map { library ->
                val descriptor = library.getAsJsonObject("downloads")?.getAsJsonObject("artifact")
                    ?: error("库 ${library["name"].asString} 缺少 artifact，请重新安装版本")
                checked(descriptor, "minecraft/libraries/" + descriptor["path"].asString)
            }.distinct()
        val classpath = libraries + client
        val classEntry = game.mainClass.replace('.', '/') + ".class"
        require(classpath.any { file -> ZipFile(file).use { it.getEntry(classEntry) != null } }) { "所选版本的 classpath 中没有主类 ${game.mainClass}" }
        val indexId = safeId(game.assetIndex["id"].asString)
        val indexFile = checked(game.assetIndex, "minecraft/assets/indexes/$indexId.json")
        require(indexFile.length() <= 16 * 1024 * 1024) { "资源索引过大" }
        val assets = jsonObject(indexFile.readBytes())
        require(assets["virtual"]?.asBoolean != true && assets["map_to_resources"]?.asBoolean != true) { "该旧版资源目录布局尚未支持" }
        val objects = assetArtifacts(assets)
        val missing = objects.filter { entry ->
            val file = containedFile(filesDir, entry.path)
            !file.isFile || file.length() != entry.size
        }
        require(missing.isEmpty()) { "资源缺失或大小异常：${missing.size} 个，请重新下载原版；例如 ${missing.firstOrNull()?.path}" }
        val directory = containedFile(filesDir, "minecraft/instances/${game.id}")
        val natives = containedFile(filesDir, "minecraft/natives/$abi")
        val classpathText = classpath.joinToString(":") { it.absolutePath }
        val replacements = mapOf(
            "auth_player_name" to identity.name, "auth_uuid" to identity.uuid.replace("-", ""),
            "auth_access_token" to "0", "auth_session" to "-", "auth_xuid" to "", "clientid" to "",
            "user_type" to "legacy", "user_properties" to "{}", "version_name" to game.id, "version_type" to game.type,
            "game_directory" to directory.absolutePath, "assets_root" to containedFile(filesDir, "minecraft/assets").absolutePath,
            "assets_index_name" to indexId, "game_assets" to containedFile(filesDir, "minecraft/assets").absolutePath,
            "natives_directory" to natives.absolutePath, "library_directory" to containedFile(filesDir, "minecraft/libraries").absolutePath,
            "classpath" to classpathText, "classpath_separator" to ":", "launcher_name" to "LaunchAPKJava", "launcher_version" to "0.1.0",
            "resolution_width" to width.toString(), "resolution_height" to height.toString())
        require(game.gameArguments.isNotEmpty() || game.legacyArguments != null) { "版本缺少游戏启动参数" }
        val rawGame = game.legacyArguments?.let(::legacyTokens).orEmpty() + argumentValues(game.gameArguments, arch, features)
        val gameArguments = rawGame.map { substitute(it, replacements) }
        val rawJvm = argumentValues(game.jvmArguments, arch, features).map { substitute(it, replacements) }
        val vm = mutableListOf("-Xms128m", "-Xmx${memoryMb}m", "-Djava.class.path=$classpathText", "-Duser.dir=${directory.absolutePath}",
            "-Djava.library.path=${natives.absolutePath}")
        var index = 0
        while (index < rawJvm.size) {
            val option = rawJvm[index++]
            if (option in setOf("-cp", "-classpath", "--class-path")) {
                require(index < rawJvm.size && rawJvm[index++] == classpathText) { "版本提供了不匹配的 classpath" }
                continue
            }
            require(option.startsWith('-') && !option.startsWith("@")) { "无效 JVM 参数" }
            require(!option.startsWith("-javaagent") && !option.startsWith("-agent") && !option.startsWith("-Xbootclasspath") &&
                !option.startsWith("-XX:On") && !option.startsWith("-Djava.class.path=") && !option.startsWith("-Djava.home=") &&
                !option.startsWith("-Duser.dir=") && !option.startsWith("-Djava.system.class.loader=")) { "该 JVM 参数需要单独适配：${option.substringBefore('=')}" }
            if (option.startsWith("-Djava.library.path=")) {
                require(option == "-Djava.library.path=${natives.absolutePath}") { "版本 native 路径不匹配" }
            } else if (!option.startsWith("-Xm")) vm += option
        }
        game.logging?.let { logging ->
            val descriptor = logging.getAsJsonObject("file") ?: error("日志配置缺少文件")
            val file = checked(descriptor, "minecraft/assets/log_configs/" + safeFilename(descriptor["id"].asString))
            val option = substitute(logging["argument"].asString, mapOf("path" to file.absolutePath))
            require(option == "-Dlog4j.configurationFile=${file.absolutePath}") { "该日志参数需要单独适配" }
            vm += option
        }
        require(vm.size <= 256 && gameArguments.size <= 512) { "启动参数数量异常" }
        return LaunchPlan(game.id, game.baseId, game.mainClass, game.javaMajor, classpath, directory, natives, vm, gameArguments, objects.size)
    }

    private fun checked(descriptor: com.google.gson.JsonObject, path: String): File {
        val artifact = artifact(descriptor, path)
        val file = containedFile(filesDir, artifact.path)
        require(matchesFile(file, artifact.size, artifact.sha1, "")) { "文件缺失或 SHA-1 校验失败：$path" }
        return file
    }
}
