package com.launchapk.java.launcher

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameRuntimeOptionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun jar(name: String, entry: String = "marker"): File = temporary.newFile(name).also { file ->
        ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry(entry)); it.write(byteArrayOf(1)); it.closeEntry() }
    }
    @Test fun androidBindingsPrecedeGameWithoutDeletingDownloadedFiles() {
        val glfw = jar("lwjgl-glfw-3.2.2.jar")
        val jna = jar("jna-5.12.1.jar")
        val platform = jar("jna-platform-5.12.1.jar")
        val game = jar("client.jar")
        val runtime = jar("lwjgl-glfw-3.3.3.jar")
        assertEquals(listOf(runtime, platform, game), GameRuntimeOptions.classpath(listOf(glfw, jna, platform, game), listOf(runtime)))
        assertTrue(glfw.isFile && jna.isFile)
    }
    @Test fun rejectLwjgl2RatherThanSendingItToGlfw3() {
        val old = jar("lwjgl-2.9.4.jar", "org/lwjgl/opengl/Display.class")
        assertThrows(IllegalArgumentException::class.java) { GameRuntimeOptions.classpath(listOf(old), emptyList()) }
    }
    @Test fun rejectNewerBindingsWithIncompatibleNativeAbi() {
        val glfw = jar("lwjgl-glfw-3.4.1.jar")
        val core = jar("lwjgl-3.4.1.jar")
        assertThrows(IllegalArgumentException::class.java) { GameRuntimeOptions.classpath(listOf(glfw, core), emptyList()) }
    }
    @Test fun overridesDesktopLoaderAndAllocatorProperties() {
        val natives = temporary.newFolder("android-native")
        val game = jar("client.jar")
        val result = GameRuntimeOptions.options(listOf("-Xmx1024m", "-Djava.class.path=desktop", "-Djava.library.path=wrong",
            "-Dorg.lwjgl.glfw.libname=desktop", "-Djna.boot.library.path=wrong", "-Duser.home=wrong"), listOf(game), natives, temporary.root)
        assertEquals(1, result.count { it.startsWith("-Djava.class.path=") })
        assertEquals(1, result.count { it.startsWith("-Djava.library.path=") })
        assertTrue(result.contains("-Dorg.lwjgl.glfw.libname=${File(natives, "libglfw.so").absolutePath}"))
        assertTrue(result.contains("-Dorg.lwjgl.system.allocator=system"))
        assertTrue(result.contains("-Xmx1024m"))
        assertTrue(result.contains("-Duser.home=${temporary.root.absolutePath}"))
        assertTrue(result.none { it.contains("=desktop") || it.endsWith("=wrong") })
    }
}
