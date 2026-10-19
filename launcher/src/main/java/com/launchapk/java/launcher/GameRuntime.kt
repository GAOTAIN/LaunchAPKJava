package com.launchapk.java.launcher

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

/** LWJGL Java jars run inside HotSpot, never in ART's application class loader. */
class GameRuntime(private val context: Context) {
    fun install(): List<File> {
        // The selected upstream prebuilt JNI libraries have 4KB data segments.
        // Refuse clearly before dlopen on 16KB devices, rather than crashing their process.
        require(Os.sysconf(OsConstants._SC_PAGESIZE) == 4096L) {
            "本次 LWJGL/JNA 原生库仅支持 4KB 内存页设备；16KB 设备需要重新构建这些依赖。"
        }
        val hashes = Properties().apply { context.assets.open("game-runtime/hashes.properties").use(::load) }
        val directory = File(context.filesDir, "game-runtime/3.3.3").apply { check(isDirectory || mkdirs()) }
        return hashes.stringPropertyNames().sorted().map { name ->
            require(name.matches(Regex("[A-Za-z0-9.-]+\\.jar")))
            val file = File(directory, name)
            fun valid() = file.isFile && file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { "%02x".format(it) } == hashes.getProperty(name)
            }
            if (!valid()) {
                val temporary = File(directory, "$name.part")
                try {
                    context.assets.open("game-runtime/$name").use { source -> temporary.outputStream().use(source::copyTo) }
                    java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    check(valid()) { "运行库校验失败：$name" }
                } finally { temporary.delete() }
            }
            file
        }
    }
}

/** Pure policy, tested independently of Android/JNI. Original downloaded jars remain intact. */
object GameRuntimeOptions {
    fun classpath(original: List<File>, runtime: List<File>): List<File> {
        require(original.none { file -> ZipFile(file).use { it.getEntry("org/lwjgl/opengl/Display.class") != null } }) {
            "该版本使用 LWJGL 2；当前 Android 窗口适配支持 LWJGL 3。请选择 1.13–1.20.4 的 LWJGL 3 版本。"
        }
        require(original.any { file -> file.name.startsWith("lwjgl-glfw-") }) { "版本没有 GLFW/LWJGL 3 库" }
        require(original.none { it.name.matches(Regex("lwjgl-3\\.[4-9].*\\.jar")) }) {
            "此版本需要更新的 LWJGL；当前原生绑定固定为 3.3.3。"
        }
        return runtime + original.filterNot { it.name.matches(Regex("lwjgl(?:-(?:glfw|opengl|openal|stb|tinyfd))?-[0-9].*\\.jar|jna-[0-9].*\\.jar")) }
    }

    fun options(original: List<String>, classpath: List<File>, nativeDirectory: File, userHome: File): List<String> {
        require(nativeDirectory.isDirectory)
        val filtered = original.filterNot { it.startsWith("-Djava.class.path=") || it.startsWith("-Djava.library.path=") || it.startsWith("-Duser.home=") ||
            it.startsWith("-Dorg.lwjgl.") || it.startsWith("-Djna.") }
        return filtered + listOf(
            "-Djava.class.path=" + classpath.joinToString(":") { it.absolutePath },
            "-Duser.home=${userHome.absolutePath}",
            "-Djava.library.path=${nativeDirectory.absolutePath}",
            "-Dorg.lwjgl.librarypath=${nativeDirectory.absolutePath}",
            "-Dorg.lwjgl.glfw.libname=${File(nativeDirectory, "libglfw.so").absolutePath}",
            "-Dorg.lwjgl.opengl.libname=${File(nativeDirectory, "libgl4es.so").absolutePath}",
            "-Dorg.lwjgl.openal.libname=${File(nativeDirectory, "libopenal.so").absolutePath}",
            "-Dorg.lwjgl.system.allocator=system",
            "-Dorg.lwjgl.system.stackSize=1024",
            "-Djna.boot.library.path=${nativeDirectory.absolutePath}", "-Djna.nosys=true", "-Djna.nounpack=true",
            "-Djava.awt.headless=true"
        )
    }
}
