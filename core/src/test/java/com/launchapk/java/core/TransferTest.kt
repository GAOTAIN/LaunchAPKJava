package com.launchapk.java.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private class MemorySource(val bytes: ByteArray, val declaredSize: Long = bytes.size.toLong()) : HttpSource {
        var calls = 0
        override fun open(url: String): HttpBody { calls++; return HttpBody(ByteArrayInputStream(bytes), declaredSize) }
    }
    @Test fun verifiedTransferIsCommittedAndASecondRequestReusesIt() = runBlocking {
        val directory = temporary.newFolder()
        val bytes = "synthetic test data".toByteArray()
        val source = MemorySource(bytes)
        val spec = DownloadSpec("gamefile", "test", "https://example.test/file", "managed/file.bin", bytes.size.toLong(), hashBytes(bytes, "SHA-1"))
        val file = transfer(source, spec, directory) { _, _ -> }
        assertArrayEquals(bytes, file.readBytes())
        transfer(source, spec, directory) { _, _ -> }
        assertEquals(1, source.calls)
        assertFalse(file.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
    }
    @Test fun hashMismatchDoesNotReplaceThePreviousGoodFile() = runBlocking {
        val directory = temporary.newFolder()
        val existing = directory.resolve("file.bin").also { it.writeText("old good file") }
        val bytes = "new bad file".toByteArray()
        try {
            transfer(MemorySource(bytes), DownloadSpec("gamefile", "test", "https://example.test/file", "file.bin", bytes.size.toLong(), "a".repeat(40)), directory) { _, _ -> }
            fail("Expected hash mismatch")
        } catch (_: java.io.IOException) { }
        assertEquals("old good file", existing.readText())
        assertEquals(1, directory.listFiles()!!.size)
    }
    @Test fun truncatedResponsesNeverPublishACompletedFile() = runBlocking {
        val directory = temporary.newFolder()
        try {
            transfer(MemorySource(byteArrayOf(1, 2), 10), DownloadSpec("gamefile", "test", "https://example.test/file", "file.bin"), directory) { _, _ -> }
            fail("Expected incomplete transfer")
        } catch (_: java.io.IOException) { }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun cancellationRemovesPartialFile() = runBlocking {
        val directory = temporary.newFolder()
        try {
            transfer(MemorySource(ByteArray(80000)), DownloadSpec("gamefile", "test", "https://example.test/file", "file.bin"), directory) { _, _ -> throw CancellationException("cancelled") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun rawWebsiteDownloadRequiresAnActualZipStructure() = runBlocking {
        val directory = temporary.newFolder()
        try {
            transfer(MemorySource("PK03not-a-real-zip".toByteArray()), DownloadSpec("content", "test", "https://example.test/file", "file.zip"), directory) { _, _ -> }
            fail("Expected archive rejection")
        } catch (_: Exception) { }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun sha512AndUnknownLengthZipDownloadAreSupported() = runBlocking {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("test.txt")); zip.write("synthetic".toByteArray()); zip.closeEntry() }
        val bytes = output.toByteArray()
        val directory = temporary.newFolder()
        val result = transfer(MemorySource(bytes, -1), DownloadSpec("content", "test", "https://example.test/file", "file.zip", sha512 = hashBytes(bytes, "SHA-512")), directory) { _, _ -> }
        assertArrayEquals(bytes, result.readBytes())
    }
    @Test fun unknownLengthDownloadsCannotExceedTheCallerLimit() = runBlocking {
        val directory = temporary.newFolder()
        try {
            transfer(MemorySource(ByteArray(80000), -1), DownloadSpec("gamefile", "test", "https://example.test/file", "file.bin"),
                directory, maxBytes = 70000) { _, _ -> }
            fail("Expected size rejection")
        } catch (_: IllegalArgumentException) { }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun callerValidationRunsBeforeReplacingThePreviousFile() = runBlocking {
        val directory = temporary.newFolder()
        val existing = directory.resolve("file.bin").also { it.writeText("previous file") }
        val bytes = "new verified bytes".toByteArray()
        try {
            transfer(MemorySource(bytes), DownloadSpec("gamefile", "test", "https://example.test/file", "file.bin",
                bytes.size.toLong(), hashBytes(bytes, "SHA-1")), directory,
                validate = { throw IllegalArgumentException("wrong Minecraft version") }) { _, _ -> }
            fail("Expected semantic validation failure")
        } catch (_: IllegalArgumentException) { }
        assertEquals("previous file", existing.readText())
        assertEquals(1, directory.listFiles()!!.size)
    }
}
