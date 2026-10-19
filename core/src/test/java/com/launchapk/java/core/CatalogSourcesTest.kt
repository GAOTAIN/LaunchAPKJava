package com.launchapk.java.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class CatalogSourcesTest {
    @Test fun robotsUsesSpecificAgentsAndLongestRule() {
        val rules = "User-agent: *\nDisallow: /class/\nAllow: /class/123.html\nDisallow: /jump/"
        assertFalse(robotsAllows(rules, "/class/456.html"))
        assertTrue(robotsAllows(rules, "/class/123.html"))
        assertFalse(robotsAllows(rules, "/jump/test"))
        val specific = "User-agent: *\nDisallow:\nUser-agent: LaunchAPKJava\nDisallow: /class/"
        assertFalse(robotsAllows(specific, "/class/123.html"))
    }
    @Test fun parserReadsOnlyPublishedRelatedSourcesWithoutRequestingRedirects() {
        val target = "https://modrinth.com/mod/example"
        val encoded = Base64.getEncoder().encodeToString(target.toByteArray())
        val html = """<title>示例 - MC百科</title><div class="common-link-frame"><a href="//link.mcmod.cn/target/$encoded">Modrinth</a><a href="https://www.curseforge.com/minecraft/mc-mods/example">CurseForge</a><a href="file:///secret">bad</a></div><a href="https://modrinth.com/mod/unrelated">comment</a>"""
        val entry = parseMcmod(html, "https://www.mcmod.cn/class/123.html")
        assertEquals("示例", entry.title)
        assertEquals(2, entry.links.size)
        assertEquals(target, entry.links.first())
        assertEquals("example", modrinthProject(target))
        assertNull(modrinthProject("https://evil.test/mod/example"))
    }
    @Test fun historicalVersionsWithSpacesDoNotBreakTheOfficialList() {
        val body = """{"versions":[{"id":"1.14 Pre-Release 1","type":"snapshot","url":"https://piston-meta.mojang.com/example.json","sha1":"${"a".repeat(40)}"},{"id":"3D Shareware v1.34","type":"snapshot","url":"https://piston-meta.mojang.com/second.json","sha1":"${"b".repeat(40)}"}]}"""
        assertEquals(2, parseVersions(body.toByteArray()).size)
        assertThrows(IllegalArgumentException::class.java) { safeId("../escape") }
    }
    @Test fun nativeMcmodSourceRefusesSearchAndEditingPaths() {
        for (url in listOf("https://search.mcmod.cn/s?key=test", "https://www.mcmod.cn/class/add/", "https://www.mcmod.cn/jump/123", "https://evil.test/class/1.html")) {
            assertThrows(IllegalArgumentException::class.java) { mcmodPage(url) }
        }
        assertEquals("https://www.mcmod.cn/modpack/1.html", mcmodPage("https://www.mcmod.cn/modpack/1.html"))
    }
    @Test fun contentTypesAndHashesAreValidated() {
        val json = """[{"id":"version1","name":"Release","game_versions":["1.20.1"],"loaders":["fabric"],"files":[{"filename":"example.jar","url":"https://cdn.modrinth.com/file.jar","size":8,"hashes":{"sha512":"${"a".repeat(128)}"}},{"filename":"bad.jar","url":"https://cdn.modrinth.com/bad.jar","size":8,"hashes":{"sha512":"bad"}}]}]"""
        assertEquals(1, parseContentFiles(json.toByteArray(), "example", ContentKind.MOD).size)
        assertTrue(parseContentFiles(json.toByteArray(), "example", ContentKind.RESOURCE).isEmpty())
        assertTrue(ContentKind.MODPACK.accepts("pack.mrpack"))
    }
    @Test fun libraryRulesSelectLinuxArmJavaLibrariesAndExcludeDesktopNatives() {
        val rules = jsonObject("""{"rules":[{"action":"allow"},{"action":"disallow","os":{"name":"windows"}}]}""".toByteArray()).getAsJsonArray("rules")
        assertTrue(libraryAllowed(rules))
        val root = jsonObject("""{"id":"test","downloads":{"client":{"url":"https://example.test/client.jar","sha1":"${"a".repeat(40)}","size":5}},"libraries":[{"name":"org:test:1:natives-windows","downloads":{"artifact":{"path":"org/native.jar","url":"https://example.test/native.jar","sha1":"${"b".repeat(40)}","size":4}}}]}""".toByteArray())
        val files = gameArtifacts(root, "test")
        assertEquals(1, files.size)
        assertTrue(files.first().path.endsWith("test.jar"))
    }
    @Test fun sharedAssetsAreDeduplicatedAndInvalidSizesRejected() {
        val hash = "a".repeat(40)
        val index = jsonObject("""{"objects":{"first":{"hash":"$hash","size":3},"second":{"hash":"$hash","size":3}}}""".toByteArray())
        assertEquals(1, assetArtifacts(index).size)
        assertEquals("https://resources.download.minecraft.net/aa/$hash", assetArtifacts(index).first().url)
        assertThrows(IllegalArgumentException::class.java) { assetArtifacts(jsonObject("""{"objects":{"bad":{"hash":"$hash","size":-1}}}""".toByteArray())) }
    }
    @Test fun pathsAndNetworkSchemesCannotEscapeTheManagedDirectory() {
        for (path in listOf("../escape", "/absolute", "a/../../escape", "a\\escape", "a:C")) {
            assertThrows(IllegalArgumentException::class.java) { containedFile(File("."), path) }
        }
        for (url in listOf("file:///secret", "http://example.test/file", "https://user:password@example.test/file", "https://localhost/file")) {
            assertThrows(IllegalArgumentException::class.java) { httpsUri(url) }
        }
    }
}
