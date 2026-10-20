package com.launchapk.java.launcher

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RuntimeCatalogTest {
    private fun catalog() = RuntimeCatalog.decode(File(requireNotNull(System.getProperty("launchapk.runtimeCatalog"))).readText())
    @Test fun providesPinnedAndroidPackagesForEverySupportedJavaAndAbi() {
        val catalog=catalog()
        for (major in listOf(8,17,21)) for(abi in listOf("arm64-v8a","x86_64")) {
            val spec=catalog.select(major,abi);assertEquals(major,runtimeJavaMajor(spec.version));assertTrue(spec.downloadBytes>10000000)
            assertEquals(2,spec.archives.size);assertEquals(64,spec.archives.first().sha256.length)
        }
    }
    @Test fun rejectsUnsupportedRuntimeAndUnpinnedSources() {
        val catalog=catalog();assertThrows(IllegalStateException::class.java) { catalog.select(25,"arm64-v8a") }
        assertThrows(IllegalStateException::class.java) { catalog.select(17,"armeabi-v7a") }
        val spec=catalog.select(17,"arm64-v8a")
        for(url in listOf("http://raw.githubusercontent.com/test","https://127.0.0.1/file","https://raw.githubusercontent.com/FCL-Team/FoldCraftLauncher/main/file")) {
            assertThrows(IllegalArgumentException::class.java) { spec.copy(archives=listOf(spec.archives[0].copy(url=url),spec.archives[1])).validate() }
        }
    }
    @Test fun rejectsMixedArchitecturesMissingHashesAndDuplicateCatalogEntries() {
        val spec=catalog().select(17,"arm64-v8a")
        assertThrows(IllegalArgumentException::class.java) { spec.copy(abi="x86_64").validate() }
        assertThrows(IllegalArgumentException::class.java) { spec.copy(archives=listOf(spec.archives[0].copy(sha256=""),spec.archives[1])).validate() }
        val text=File(requireNotNull(System.getProperty("launchapk.runtimeCatalog"))).readText()
        assertThrows(IllegalArgumentException::class.java) { RuntimeCatalog.decode(text.replace("\"schemaVersion\": 1","\"schemaVersion\": 2")) }
    }
}
