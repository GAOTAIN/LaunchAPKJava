package com.launchapk.java.launcher

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream

data class InstalledRuntime(val home: File, val jvm: File, val version: String, val abi: String)

/** ZIPs must contain an Android/Bionic OpenJDK, with links resolved to regular files. */
class RuntimeInstaller(private val filesDir: File, private val cacheDir: File) {
    val home = File(filesDir, "runtimes/current")
    fun inspect(abi: String): InstalledRuntime? = if (home.exists()) inspectHome(home, abi) else null
    fun install(source: InputStream, expectedSha256: String, abi: String): InstalledRuntime {
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "请填写来源可信的运行时 ZIP SHA-256" }
        require(abi in setOf("arm64-v8a", "x86_64")) { "当前只支持 64 位 Android 运行时" }
        check(cacheDir.isDirectory || cacheDir.mkdirs())
        val archive = File(cacheDir, "runtime-${UUID.randomUUID()}.zip")
        val parent = home.parentFile!!
        check(parent.isDirectory || parent.mkdirs())
        val stage = File(parent, ".install-${UUID.randomUUID()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            archive.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    val n = source.read(buffer); if (n < 0) break
                    total += n; require(total <= 1024L * 1024 * 1024) { "运行时压缩包过大" }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedSha256, true)) { "运行时 SHA-256 不匹配" }
            check(stage.mkdir())
            var count = 0; var expanded = 0L
            val seen = mutableSetOf<String>()
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 20000) { "运行时文件数过多" }
                    val path = entry.name
                    require(path.isNotBlank() && path.length <= 512 && !path.startsWith("/") && !path.contains("//") && !path.contains('\\') && !path.contains(':') && path.split('/').none { it == ".." || it == "." }) { "运行时包含无效路径" }
                    require(seen.add(path.trimEnd('/'))) { "运行时包含重复文件路径" }
                    val target = File(stage, path).canonicalFile
                    require(target.toPath().startsWith(stage.canonicalFile.toPath())) { "运行时路径越界" }
                    if (entry.isDirectory) check(target.isDirectory || target.mkdirs())
                    else {
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        var fileSize = 0L
                        target.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = zip.read(buffer); if (n < 0) break
                                fileSize += n; expanded += n
                                require(fileSize <= 512L * 1024 * 1024 && expanded <= 2L * 1024 * 1024 * 1024) { "运行时解压体积超限" }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val candidates = (listOf(stage) + stage.listFiles().orEmpty().filter { it.isDirectory })
                .filter { File(it, "release").isFile }
            require(candidates.size == 1) { "ZIP 根目录或唯一顶层目录必须包含 JRE release 文件" }
            val extracted = candidates.single()
            inspectHome(extracted, abi)
            File(extracted, "launchapk-runtime.properties").writeText("archive.sha256=${expectedSha256.lowercase()}\nabi=$abi\n")
            val backup = File(parent, ".previous-${UUID.randomUUID()}")
            val previous = home.exists()
            if (previous) check(home.renameTo(backup)) { "不能替换现有运行时" }
            try {
                check(extracted.renameTo(home)) { "不能提交运行时安装" }
            } catch (e: Exception) {
                if (previous) check(backup.renameTo(home)) { "安装恢复失败，原运行时保留在 ${backup.name}" }
                throw e
            }
            if (previous) backup.deleteRecursively()
            return inspectHome(home, abi)
        } finally {
            archive.delete()
            if (stage.exists()) stage.deleteRecursively()
            // Existing runtime is untouched until extraction and validation have succeeded.
        }
    }
    private fun inspectHome(directory: File, abi: String): InstalledRuntime {
        val properties = Properties()
        val release = File(directory, "release")
        require(release.length() in 1..65536) { "缺少有效的 JRE release 文件" }
        release.reader(Charsets.UTF_8).use(properties::load)
        val version = properties.getProperty("JAVA_VERSION")?.trim('"') ?: error("缺少 Java 版本")
        val major = if (version.startsWith("1.8.")) 8 else version.substringBefore('.').substringBefore('-').toIntOrNull()
        require(major in setOf(8, 17, 21)) { "本轮支持 Android Java 8、17、21 运行时" }
        val libraries = listOf("lib/server/libjvm.so", "lib/aarch64/server/libjvm.so", "lib/amd64/server/libjvm.so", "jre/lib/aarch64/server/libjvm.so", "jre/lib/amd64/server/libjvm.so")
        val jvm = libraries.map { File(directory, it) }.firstOrNull { it.isFile } ?: error("没有找到 Android libjvm.so")
        jvm.inputStream().use { input ->
            val header = ByteArray(20)
            require(input.read(header) == 20 && header.copyOfRange(0, 4).contentEquals(byteArrayOf(127, 69, 76, 70)) && header[4] == 2.toByte() && header[5] == 1.toByte()) { "libjvm.so 不是 64 位小端 ELF" }
            val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
            require(machine == if (abi == "arm64-v8a") 183 else if (abi == "x86_64") 62 else -1) { "JRE 架构与应用进程不匹配" }
            var previous = ""; val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                val text = previous + String(buffer, 0, n, Charsets.ISO_8859_1)
                require(!text.contains("libc.so.6") && !text.contains("ld-linux")) { "检测到桌面 Linux/glibc JRE，请使用 Android/Bionic 产物" }
                previous = text.takeLast(32)
            }
        }
        require(File(directory, "legal").isDirectory || File(directory, "LICENSE").isFile) { "运行时须保留原始 legal 或 LICENSE 声明" }
        return InstalledRuntime(directory, jvm, version, abi)
    }
}
