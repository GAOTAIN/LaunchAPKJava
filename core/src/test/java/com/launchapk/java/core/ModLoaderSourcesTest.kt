package com.launchapk.java.core

import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModLoaderSourcesTest {
    private fun xml(vararg versions: String) = ("<metadata><versioning><versions>" +
        versions.joinToString("") { "<version>$it</version>" } + "</versions></versioning></metadata>").toByteArray()

    @Test fun forgeFiltersExactMinecraftAndSortsNumericallyWithRecommendationsFirst() {
        val releases = parseMavenLoaders(xml("1.20.1-47.4.9", "1.20.1-47.4.28", "1.20.10-99.0.0", "1.20.1-47.4.10"), ModLoader.FORGE, "1.20.1", "47.4.10")
        assertEquals(listOf("47.4.10", "47.4.28", "47.4.9"), releases.map { it.displayVersion })
        assertTrue(releases.first().recommended)
        assertEquals("https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-installer.jar", releases.first().downloadSpec().url)
    }

    @Test fun neoForgeHasSeparateLegacyCoordinatesAndDoesNotMixPatchVersions() {
        val legacy = parseMavenLoaders(xml("1.20.1-47.1.106", "1.20.1-47.1.9"), ModLoader.NEOFORGE, "1.20.1")
        assertTrue(legacy.first().downloadSpec().url.contains("/net/neoforged/forge/1.20.1-47.1.106/"))
        val versions = xml("21.1.9", "21.1.200", "21.10.1", "21.0.1-beta", "21.1.201-beta")
        assertEquals(listOf("21.1.201-beta", "21.1.200", "21.1.9"), parseMavenLoaders(versions, ModLoader.NEOFORGE, "1.21.1").map { it.version })
        assertFalse(parseMavenLoaders(versions, ModLoader.NEOFORGE, "1.21.1").first().stable)
        assertEquals("1.21", neoMinecraftVersion("21.0.1-beta"))
    }

    @Test fun neoForgeNewFourComponentSchemeDistinguishesHotfixesAndRejectsSnapshots() {
        val versions = xml("26.1.0.10-beta", "26.1.2.94", "26.1.0.11-alpha.1+snapshot-1", "26.2.0.1-beta")
        assertEquals(listOf("26.1.0.10-beta"), parseMavenLoaders(versions, ModLoader.NEOFORGE, "26.1").map { it.version })
        assertEquals(listOf("26.1.2.94"), parseMavenLoaders(versions, ModLoader.NEOFORGE, "26.1.2").map { it.version })
        assertNull(neoMinecraftVersion("26.1.0.11-alpha.1+snapshot-1"))
    }

    @Test fun fabricKeepsStableFlagsAndRequiresMatchingIntermediaryVersion() {
        val releases = parseFabricLoaders("""[
            {"loader":{"version":"0.16.9","stable":true},"intermediary":{"version":"1.20.1"}},
            {"loader":{"version":"0.16.14","stable":false},"intermediary":{"version":"1.20.1"}},
            {"loader":{"version":"0.99.0","stable":true},"intermediary":{"version":"1.21.1"}}
        ]""".toByteArray(), "1.20.1")
        assertEquals(listOf("0.16.14", "0.16.9"), releases.map { it.version })
        assertFalse(releases.first().stable)
        assertTrue(releases.first().downloadSpec().url.endsWith("/1.20.1/0.16.14/profile/json"))
    }

    @Test fun unsafeCoordinatesAndXmlEntitiesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LoaderRelease(ModLoader.FABRIC, "../outside", "0.16.14").downloadSpec() }
        assertThrows(IllegalArgumentException::class.java) { parseMavenLoaders(xml("1.20.1-../../outside"), ModLoader.FORGE, "1.20.1") }
        assertThrows(IllegalArgumentException::class.java) { parseMavenLoaders("<!DOCTYPE metadata SYSTEM 'file:///secret'><metadata/>".toByteArray(), ModLoader.FORGE, "1.20.1") }
        assertThrows(IllegalArgumentException::class.java) { LoaderRelease(ModLoader.NEOFORGE, "1.21.1", "21.10.1").downloadSpec() }
    }

    @Test fun unavailableForgeRecommendationsDoNotHideActualVersions() = runBlocking {
        val source = object : HttpSource {
            override fun open(url: String): HttpBody {
                if (url.endsWith("promotions_slim.json")) throw IOException("unavailable")
                val bytes = xml("1.20.1-47.4.10")
                return HttpBody(ByteArrayInputStream(bytes), bytes.size.toLong())
            }
        }
        assertEquals("47.4.10", ModLoaderSources(source).versions(ModLoader.FORGE, "1.20.1").single().displayVersion)
    }

    @Test fun oldDownloadPlansRemainReadableAndLoaderPlansRejectAlteredTargets() {
        val old = Gson().fromJson("""{"kind":"game","label":"Minecraft","url":"https://piston-meta.mojang.com/example","path":"","size":-1,"sha1":"a","sha512":"","version":"1.20.1","source":"Mojang"}""", DownloadSpec::class.java)
        assertNull(old.loader)
        val spec = LoaderRelease(ModLoader.NEOFORGE, "1.20.1", "1.20.1-47.1.106").downloadSpec()
        assertEquals(spec.loader, validateLoaderSpec(Gson().fromJson(Gson().toJson(spec), DownloadSpec::class.java)))
        assertThrows(IllegalArgumentException::class.java) { validateLoaderSpec(spec.copy(url = "https://other.test/file.jar")) }
        assertThrows(IllegalArgumentException::class.java) { validateLoaderSpec(spec.copy(path = "minecraft/versions/other.json")) }
    }
}
