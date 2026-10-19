package com.launchapk.java.launcher

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameBootstrapTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun invoke(main: String, vararg args: String): Pair<Int, Properties> {
        val receipt = temporary.newFile("session.properties").apply { writeText("state=preparing\nversion=fixture\nlog=fixture.log\n") }
        val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val classpath = System.getProperty("launchapk.smokeJar") + File.pathSeparator +
            File(GameMainFixture::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
        val output = temporary.newFile("child.log")
        val process = ProcessBuilder(listOf(java.absolutePath, "-XX:-UsePerfData", "-cp", classpath,
            "com.launchapk.java.launcher.game.GameMain", receipt.absolutePath, main) + args)
            .redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Game bootstrap timed out") }
        val record = Properties().apply { receipt.inputStream().use(::load) }
        return process.exitValue() to record
    }
    @Test fun invokesSelectedMainAndPassesArgvWithoutShellSplitting() {
        val result = File(temporary.newFolder("with spaces"), "result.txt")
        val (exit, receipt) = invoke("com.launchapk.java.launcher.GameMainFixture", result.absolutePath, "argument with spaces")
        assertEquals(0, exit)
        assertEquals("argument with spaces", result.readText())
        assertEquals("vm_shutdown", receipt.getProperty("state"))
        assertEquals("fixture", receipt.getProperty("version"))
    }
    @Test fun loadingFailureRemainsAnErrorAfterShutdownHook() {
        val (exit, receipt) = invoke("com.launchapk.java.missing.Game")
        assertNotEquals(0, exit)
        assertEquals("error", receipt.getProperty("state"))
    }
}
