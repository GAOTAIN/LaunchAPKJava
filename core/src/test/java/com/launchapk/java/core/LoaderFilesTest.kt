package com.launchapk.java.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoaderFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private class MemorySource : HttpSource {
        val files = mutableMapOf<String, ByteArray>()
        val requests = mutableListOf<String>()
        override fun open(url: String): HttpBody {
            requests += url
            val bytes = files[url] ?: throw IOException("Missing fixture: $url")
            return HttpBody(ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }
    private fun zip(name: String, text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry(name)); it.write(text.toByteArray()); it.closeEntry() }
        return bytes.toByteArray()
    }
    private suspend fun rejected(block: suspend () -> Unit): Exception {
        try { block() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected rejection")
    }
    private fun installer(source: MemorySource, release: LoaderRelease, game: String = release.gameVersion): DownloadSpec {
        val spec = release.downloadSpec()
        val bytes = zip("install_profile.json", """{"minecraft":"$game"}""")
        val root = spec.url.substringBeforeLast('/').substringBeforeLast('/')
        source.files["$root/maven-metadata.xml"] = "<metadata><versioning><versions><version>${release.version}</version></versions></versioning></metadata>".toByteArray()
        source.files[spec.url] = bytes
        source.files[spec.url + ".sha1"] = hashBytes(bytes, "SHA-1").toByteArray()
        return spec
    }
    @Test fun officialForgeAndNeoForgeInstallersAreVerifiedWithoutExecutingOrInstallingThem() = runBlocking {
        for (release in listOf(LoaderRelease(ModLoader.FORGE, "1.20.1", "1.20.1-47.4.10"),
            LoaderRelease(ModLoader.NEOFORGE, "1.20.1", "1.20.1-47.1.106"), LoaderRelease(ModLoader.NEOFORGE, "1.21.1", "21.1.200"))) {
            val root = temporary.newFolder()
            val source = MemorySource()
            val spec = installer(source, release)
            val file = LoaderFiles(source, root).download(spec) { _, _, _ -> }
            assertArrayEquals(source.files[spec.url], file.readBytes())
            assertFalse(root.resolve("minecraft").exists())
            assertEquals(1, root.walkTopDown().count { it.isFile })
        }
    }
    @Test fun installerHashMismatchPreservesAnExistingFileAndRemovesThePartialFile() = runBlocking {
        val root = temporary.newFolder()
        val source = MemorySource()
        val spec = installer(source, LoaderRelease(ModLoader.NEOFORGE, "1.21.1", "21.1.200"))
        source.files[spec.url + ".sha1"] = "a".repeat(40).toByteArray()
        val existing = containedFile(root, spec.path).also { it.parentFile!!.mkdirs(); it.writeText("previous") }
        assertTrue(rejected { LoaderFiles(source, root).download(spec) { _, _, _ -> } } is IOException)
        assertEquals("previous", existing.readText())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
    }
    @Test fun installerForAnotherMinecraftVersionIsNotPublishedEvenWithAValidHash() = runBlocking {
        val root = temporary.newFolder()
        val source = MemorySource()
        val spec = installer(source, LoaderRelease(ModLoader.NEOFORGE, "1.21.1", "21.1.200"), "1.21.10")
        rejected { LoaderFiles(source, root).download(spec) { _, _, _ -> } }
        assertFalse(containedFile(root, spec.path).exists())
        assertFalse(root.walkTopDown().any { it.isFile })
    }
    private data class FabricFixture(val root: File, val source: MemorySource, val spec: DownloadSpec, val profile: JsonObject)
    private fun fabric(): FabricFixture {
        val root = temporary.newFolder()
        val source = MemorySource()
        val release = LoaderRelease(ModLoader.FABRIC, "1.20.1", "0.16.14")
        val spec = release.downloadSpec()
        val baseJar = zip("test.txt", "Synthetic base, not Minecraft")
        val base = containedFile(root, "minecraft/versions/1.20.1/1.20.1.json")
        base.parentFile!!.mkdirs()
        base.writeText("""{"id":"1.20.1","downloads":{"client":{"url":"https://example.test/base","sha1":"${hashBytes(baseJar, "SHA-1")}","size":${baseJar.size}}}}""")
        base.parentFile!!.resolve("1.20.1.jar").writeBytes(baseJar)
        source.files["$FABRIC_META/versions/loader/1.20.1"] = """[{"loader":{"version":"0.16.14","stable":true},"intermediary":{"version":"1.20.1"}}]""".toByteArray()
        val profile = jsonObject("""{"id":"fabric-loader-0.16.14-1.20.1","inheritsFrom":"1.20.1","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient","arguments":{"game":[],"jvm":[]}}""".toByteArray())
        val libraries = JsonArray()
        for ((artifact, version) in listOf("fabric-loader" to "0.16.14", "intermediary" to "1.20.1")) {
            val path = "net/fabricmc/$artifact/$version/$artifact-$version.jar"
            val bytes = zip("test.txt", "Synthetic $artifact")
            source.files["https://maven.fabricmc.net/$path"] = bytes
            source.files["https://maven.fabricmc.net/$path.sha1"] = hashBytes(bytes, "SHA-1").toByteArray()
            libraries.add(JsonObject().also { it.addProperty("name", "net.fabricmc:$artifact:$version"); it.addProperty("url", "https://maven.fabricmc.net/") })
        }
        profile.add("libraries", libraries)
        source.files[spec.url] = profile.toString().toByteArray()
        return FabricFixture(root, source, spec, profile)
    }
    @Test fun fabricDependenciesAreVerifiedBeforeTheInheritedProfileIsCommitted() = runBlocking {
        val f = fabric()
        val file = LoaderFiles(f.source, f.root).download(f.spec) { _, _, text ->
            if (text.startsWith("校验 Fabric 库")) assertFalse(containedFile(f.root, f.spec.path).exists())
        }
        val profile = jsonObject(file.readBytes())
        assertEquals("1.20.1", profile["inheritsFrom"].asString)
        assertEquals(2, profile.getAsJsonArray("libraries").size())
        for (item in profile.getAsJsonArray("libraries")) {
            val artifact = item.asJsonObject.getAsJsonObject("downloads").getAsJsonObject("artifact")
            assertTrue(matchesFile(containedFile(f.root, "minecraft/libraries/" + artifact["path"].asString), artifact["size"].asLong, artifact["sha1"].asString, ""))
        }
    }
    @Test fun failedFabricDependencyLeavesPreviousProfileIntact() = runBlocking {
        val f = fabric()
        val old = containedFile(f.root, f.spec.path).also { it.parentFile!!.mkdirs(); it.writeText("previous valid profile") }
        val hashUrl = f.source.files.keys.first { it.endsWith(".sha1") }
        f.source.files[hashUrl] = "b".repeat(40).toByteArray()
        rejected { LoaderFiles(f.source, f.root).download(f.spec) { _, _, _ -> } }
        assertEquals("previous valid profile", old.readText())
        assertFalse(f.root.walkTopDown().any { it.name.endsWith(".part") })
    }
    @Test fun missingOrCorruptedBaseIsReportedBeforeAnyFabricLibraryDownload() = runBlocking {
        val f = fabric()
        containedFile(f.root, "minecraft/versions/1.20.1/1.20.1.jar").writeText("corrupt")
        assertTrue(rejected { LoaderFiles(f.source, f.root).download(f.spec) { _, _, _ -> } }.message!!.contains("原版"))
        assertFalse(f.source.requests.any { it.startsWith("https://maven.fabricmc.net/") })
    }
    @Test fun cancellationDoesNotPublishTheFabricProfile() = runBlocking {
        val f = fabric()
        assertTrue(rejected { LoaderFiles(f.source, f.root).download(f.spec) { _, _, _ -> throw CancellationException("test") } } is CancellationException)
        assertFalse(containedFile(f.root, f.spec.path).exists())
        assertFalse(f.root.walkTopDown().any { it.name.endsWith(".part") })
    }
    @Test fun fabricRejectsMismatchedProfileUnsafeRepositoriesAndRepeatedCoordinates() {
        val f = fabric()
        val release = f.spec.loader!!
        f.profile.addProperty("inheritsFrom", "1.21.1")
        assertThrows(IllegalArgumentException::class.java) { fabricLibraries(f.profile, release) }
        f.profile.addProperty("inheritsFrom", "1.20.1")
        val first = f.profile.getAsJsonArray("libraries")[0].asJsonObject
        first.addProperty("url", "https://other.test/")
        assertThrows(IllegalArgumentException::class.java) { fabricLibraries(f.profile, release) }
        first.addProperty("url", "https://maven.fabricmc.net/")
        f.profile.getAsJsonArray("libraries").add(first.deepCopy())
        assertThrows(IllegalArgumentException::class.java) { fabricLibraries(f.profile, release) }
    }
}
