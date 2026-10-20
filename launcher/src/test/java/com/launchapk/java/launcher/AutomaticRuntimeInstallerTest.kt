package com.launchapk.java.launcher

import com.launchapk.java.core.HttpBody
import com.launchapk.java.core.HttpSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import kotlinx.coroutines.*

class AutomaticRuntimeInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Entry(val name: String, val data: ByteArray = byteArrayOf(), val link: String? = null, val hard: Boolean = false)
    private fun pack(entries: List<Entry>): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(XZOutputStream(output, LZMA2Options())).use { tar ->
            entries.forEach { value ->
                val entry = if (value.link != null) TarArchiveEntry(value.name, if (value.hard) TarConstants.LF_LINK else TarConstants.LF_SYMLINK).apply { linkName = value.link }
                    else TarArchiveEntry(value.name).apply { size = value.data.size.toLong() }
                tar.putArchiveEntry(entry)
                if (value.link == null) tar.write(value.data)
                tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }
    private fun elf(machine: Int = 183) = ByteArray(64).apply { byteArrayOf(127,69,76,70,2,1).copyInto(this);this[18]=machine.toByte();this[19]=(machine shr 8).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun common(extra: List<Entry> = emptyList()) = pack(listOf(Entry("LICENSE", "original test license".toByteArray()),Entry("./lib/modules", "common files".toByteArray())) + extra)
    private fun binary(machine: Int = 183, version: String = "17.0.10") = pack(listOf(Entry("./release", "JAVA_VERSION=\"$version\"".toByteArray()),Entry("./lib/server/libjvm.so", elf(machine))))
    private fun recipe(common: ByteArray, binary: ByteArray): RuntimePackage {
        val base="https://raw.githubusercontent.com/FCL-Team/FoldCraftLauncher/"+"a".repeat(40)+"/FCL/src/main/jreAssets/app_runtime/java/jre17/"
        return RuntimePackage(17,"arm64-v8a","17.0.10",listOf(RuntimeArchive(base+"universal.tar.xz",common.size.toLong(),hash(common)),RuntimeArchive(base+"bin-arm64.tar.xz",binary.size.toLong(),hash(binary))))
    }
    private fun source(spec: RuntimePackage, common: ByteArray, binary: ByteArray) = object : HttpSource {
        override fun open(url: String): HttpBody { val bytes=if(url==spec.archives[0].url)common else binary;return HttpBody(bytes.inputStream(),-1) }
    }
    private fun previous(root: File): File = File(root,"files/runtimes/current").apply {
        resolve("lib/server").mkdirs();resolve("release").writeText("JAVA_VERSION=\"1.8.0_442\"")
        resolve("LICENSE").writeText("previous license");resolve("lib/server/libjvm.so").writeBytes(elf());resolve("marker").writeText("previous installation")
    }
    private fun assertUnchanged(root: File) {
        assertEquals("previous installation",File(root,"files/runtimes/current/marker").readText())
        assertTrue(File(root,"files/runtimes").listFiles().orEmpty().none { it.name.startsWith(".auto-") })
        assertTrue(File(root,"cache/runtime-downloads").listFiles().orEmpty().isEmpty())
    }
    @Test fun mergesVerifiedPartsAndMaterializesInternalLinks() = runBlocking {
        val root=temporary.newFolder();val common=common(listOf(Entry("legal/copy",link="../LICENSE"),Entry("legal/hard",link="LICENSE",hard=true)))
        val binary=binary();val spec=recipe(common,binary)
        val result=AutomaticRuntimeInstaller(File(root,"files"),File(root,"cache"),source(spec,common,binary)).install(spec)
        assertEquals("17.0.10",result.version)
        assertEquals("original test license",result.home.resolve("legal/copy").readText())
        assertEquals("original test license",result.home.resolve("legal/hard").readText())
        assertFalse(java.nio.file.Files.isSymbolicLink(result.home.resolve("legal/copy").toPath()))
        assertTrue(result.home.resolve("launchapk-runtime.properties").readText().contains(spec.archives[0].sha256))
    }
    @Test fun matchingInstalledMajorDoesNotDownloadAgain() = runBlocking {
        val root=temporary.newFolder();val common=common();val binary=binary();val spec=recipe(common,binary)
        AutomaticRuntimeInstaller(File(root,"files"),File(root,"cache"),source(spec,common,binary)).install(spec)
        val noNetwork=object:HttpSource { override fun open(url:String):HttpBody=error("Network must not be used") }
        assertEquals("17.0.10",AutomaticRuntimeInstaller(File(root,"files"),File(root,"cache"),noNetwork).install(spec).version)
    }
    @Test fun hashMismatchAndTruncationPreservePreviousInstallation() {
        for (truncated in listOf(false,true)) {
            val root=temporary.newFolder();previous(root);val common=common();val binary=binary();val spec=recipe(common,binary)
            val changed=if(truncated)common.copyOf(common.size/2) else common.copyOf().apply { this[10]=(this[10].toInt() xor 1).toByte() }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { AutomaticRuntimeInstaller(File(root,"files"),File(root,"cache"),source(spec,changed,binary)).install(spec) } }
            assertUnchanged(root)
        }
    }
    @Test fun rejectsTraversalEscapingDanglingAndCyclicLinks() {
        val invalid=listOf(listOf(Entry("../escape")),listOf(Entry("bad",link="../../escape")),listOf(Entry("bad",link="missing")),
            listOf(Entry("a",link="b"),Entry("b",link="a")),listOf(Entry("bad",link="../escape",hard=true)),listOf(Entry("LICENSE", "duplicate".toByteArray())))
        for (extra in invalid) {
            val root=temporary.newFolder();previous(root);val common=common(extra);val binary=binary();val spec=recipe(common,binary)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { AutomaticRuntimeInstaller(File(root,"files"),File(root,"cache"),source(spec,common,binary)).install(spec) } }
            assertUnchanged(root);assertFalse(File(root,"files/runtimes/escape").exists())
        }
    }
    @Test fun wrongArchitectureOrJavaVersionDoesNotReplaceExistingRuntime() {
        for (binary in listOf(binary(machine=62),binary(version="21.0.1"))) {
            val root=temporary.newFolder();previous(root);val common=common();val spec=recipe(common,binary)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { AutomaticRuntimeInstaller(File(root,"files"),File(root,"cache"),source(spec,common,binary)).install(spec) } }
            assertUnchanged(root)
        }
    }
    @Test fun cancellationDuringDownloadAndBeforeExtractionKeepsPreviousRuntime() {
        for (duringExtract in listOf(false,true)) {
            val root=temporary.newFolder();previous(root);val common=common();val binary=binary();val spec=recipe(common,binary)
            assertThrows(CancellationException::class.java) { runBlocking {
                AutomaticRuntimeInstaller(File(root,"files"),File(root,"cache"),source(spec,common,binary)).install(spec) { _,_,message ->
                    if (!duringExtract) throw CancellationException("cancel download")
                    if (message.startsWith("SHA-256")) currentCoroutineContext().cancel()
                }
            } }
            assertUnchanged(root)
        }
    }
    @Test fun recoversRuntimeAfterInterruptedRenameAndSerializesInstallers() {
        val root=temporary.newFolder();val old=previous(root);val backup=File(old.parentFile,".previous-test");assertTrue(old.renameTo(backup))
        val installer=RuntimeInstaller(File(root,"files"),File(root,"cache"))
        assertEquals("1.8.0_442",installer.inspect("arm64-v8a")?.version)
        installer.exclusive { assertThrows(IllegalStateException::class.java) { installer.inspect("arm64-v8a") } }
        assertEquals("previous installation",installer.home.resolve("marker").readText())
    }
}
