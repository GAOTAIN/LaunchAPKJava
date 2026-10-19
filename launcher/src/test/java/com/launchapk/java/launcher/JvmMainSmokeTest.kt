package com.launchapk.java.launcher

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.net.URLClassLoader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JvmMainSmokeTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun run(vararg arguments: String): Pair<Int, String> {
        val executable = File(requireNotNull(System.getProperty("java.home")), "bin/" + if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java")
        val process = ProcessBuilder(listOf(executable.absolutePath, "-XX:-UsePerfData", "-cp", requireNotNull(System.getProperty("launchapk.smokeJar")),
            "com.launchapk.java.launcher.smoke.Main") + arguments).redirectErrorStream(true).start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Smoke JVM timed out") }
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }
    @Test fun packagedJava8ProbeRunsRealMainAndPreservesUnicodeArguments() {
        val jar = File(requireNotNull(System.getProperty("launchapk.smokeJar")))
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry("com/launchapk/java/launcher/smoke/Main.class")
            val header = zip.getInputStream(entry).use { input -> ByteArray(8).also { assertEquals(8, input.read(it)) } }
            assertEquals(52, (header[6].toInt() and 255) * 256 + (header[7].toInt() and 255))
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("net/minecraft/") })
        }
        val receipt = temporary.newFolder("with spaces").resolve("main.properties")
        // Exercise JVM UTF-16 argv directly, matching JNI NewString. Windows' GBK launcher
        // replaces supplementary characters before Java main, so it cannot test this contract.
        URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { loader ->
            val main = Class.forName("com.launchapk.java.launcher.smoke.Main", true, loader)
                .getMethod("main", Array<String>::class.java)
            main.invoke(null, arrayOf("LaunchAPKJava", "含空格 参数 🌍", receipt.absolutePath))
        }
        val result = Properties().also { receipt.inputStream().use(it::load) }
        assertEquals("LaunchAPKJava.main.v1", result.getProperty("marker"))
        assertEquals("含空格 参数 🌍", result.getProperty("argument"))
        assertEquals(System.getProperty("java.version"), result.getProperty("java.version"))
    }
    @Test fun invalidArgvFailsRatherThanProducingASuccessReceipt() {
        val receipt = temporary.newFolder().resolve("not-created.properties")
        val (exit, output) = run("LaunchAPKJava", "wrong argument", receipt.absolutePath)
        assertNotEquals(output, 0, exit)
        assertTrue(output.contains("argv verification failed"))
        assertFalse(receipt.exists())
    }
}
