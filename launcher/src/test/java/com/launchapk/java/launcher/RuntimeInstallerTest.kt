package com.launchapk.java.launcher

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(machine: Int = 183, extra: String? = null): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val elf = ByteArray(64); byteArrayOf(127,69,76,70,2,1).copyInto(elf); elf[18] = machine.toByte(); elf[19]=(machine shr 8).toByte()
            val entries = linkedMapOf("jdk/release" to "JAVA_VERSION=\"17.0.1\"".toByteArray(), "jdk/lib/server/libjvm.so" to elf, "jdk/LICENSE" to "license retained".toByteArray())
            if (extra != null) entries[extra] = "untrusted".toByteArray()
            for ((name, bytes) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return output.toByteArray()
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun installsAndInspectsWithArchiveHash() {
        val root = temporary.newFolder(); val installer = RuntimeInstaller(File(root,"files"),File(root,"cache"))
        val zip = archive(); val result = installer.install(ByteArrayInputStream(zip),hash(zip),"arm64-v8a")
        assertEquals("17.0.1",result.version)
        assertEquals(result,RuntimeInstaller(File(root,"files"),File(root,"cache")).inspect("arm64-v8a"))
        assertTrue(File(root,"cache").listFiles()!!.isEmpty())
    }
    @Test fun hashMismatchPreservesPreviousRuntime() {
        val root=temporary.newFolder();val installer=RuntimeInstaller(File(root,"files"),File(root,"cache"))
        val zip=archive();installer.install(ByteArrayInputStream(zip),hash(zip),"arm64-v8a")
        val previous=installer.home.resolve("release").readText()
        assertThrows(IllegalArgumentException::class.java) { installer.install(ByteArrayInputStream(zip),"0".repeat(64),"arm64-v8a") }
        assertEquals(previous,installer.home.resolve("release").readText())
    }
    @Test fun rejectsTraversalAndWrongArchitecture() {
        val root=temporary.newFolder();val installer=RuntimeInstaller(File(root,"files"),File(root,"cache"))
        for (zip in listOf(archive(extra="../escape"),archive(machine=62))) {
            assertThrows(IllegalArgumentException::class.java) { installer.install(ByteArrayInputStream(zip),hash(zip),"arm64-v8a") }
        }
        assertFalse(installer.home.exists());assertFalse(File(root,"files/runtimes/escape").exists())
    }
}
