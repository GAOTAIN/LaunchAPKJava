package com.launchapk.java.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LaunchPlannerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val name = "TestPlayer"
    private val identity get() = LocalLaunchIdentity(name, UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()).toString())
    private fun jar(vararg entries: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { zip.putNextEntry(ZipEntry(it)); zip.write("synthetic class fixture".toByteArray()); zip.closeEntry() } }
        return output.toByteArray()
    }
    private fun artifact(root: File, path: String, bytes: ByteArray): JsonObject {
        containedFile(root, path).also { it.parentFile!!.mkdirs(); it.writeBytes(bytes) }
        return JsonObject().apply {
            addProperty("url", "https://example.test/fixture")
            addProperty("sha1", hashBytes(bytes, "SHA-1")); addProperty("size", bytes.size)
        }
    }
    private fun library(root: File, version: String, main: String? = null): JsonObject {
        val path = "org/example/demo/$version/demo-$version.jar"
        val file = artifact(root, "minecraft/libraries/$path", jar(main ?: "example/Library.class")).also { it.addProperty("path", path) }
        return JsonObject().apply { addProperty("name", "org.example:demo:$version"); add("downloads", JsonObject().also { it.add("artifact", file) }) }
    }
    private fun save(root: File, id: String, json: JsonObject) {
        containedFile(root, "minecraft/versions/$id/$id.json").also { it.parentFile!!.mkdirs(); it.writeText(json.toString()) }
    }
    private fun base(root: File, id: String = "1.20.1"): JsonObject {
        val client = artifact(root, "minecraft/versions/$id/$id.jar", jar("net/minecraft/client/main/Main.class"))
        val assets = artifact(root, "minecraft/assets/indexes/test.json", """{"objects":{}}""".toByteArray()).also { it.addProperty("id", "test") }
        return jsonObject("""{"id":"$id","type":"release","mainClass":"net.minecraft.client.main.Main","javaVersion":{"majorVersion":17},"arguments":{"jvm":[],"game":[]}}""".toByteArray()).apply {
            val args = getAsJsonObject("arguments")
            listOf("-cp", "\${classpath}", "-Djava.library.path=\${natives_directory}").forEach { args.getAsJsonArray("jvm").add(it) }
            listOf("--username", "\${auth_player_name}", "--uuid", "\${auth_uuid}", "--accessToken", "\${auth_access_token}", "--gameDir", "\${game_directory}")
                .forEach { args.getAsJsonArray("game").add(it) }
            val resolution = jsonObject("""{"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],"value":[]}""".toByteArray())
            listOf("--width", "\${resolution_width}", "--height", "\${resolution_height}").forEach { resolution.getAsJsonArray("value").add(it) }
            args.getAsJsonArray("game").add(resolution)
            add("downloads", JsonObject().also { it.add("client", client) }); add("assetIndex", assets)
            add("libraries", JsonArray().also { it.add(library(root, "1.0")) })
            save(root, id, this)
        }
    }
    private fun prepare(root: File, id: String = "1.20.1") = LaunchPlanner(root).prepare(id, identity, "arm64-v8a", 1280, 720)

    @Test fun modernPlanUsesAndroidClasspathAndKeepsSpaceContainingPathsInSingleArguments() {
        val root = temporary.newFolder("files with spaces")
        base(root)
        val plan = prepare(root)
        assertEquals(17, plan.requiredJava)
        assertEquals(2, plan.classpath.size)
        assertFalse("-cp" in plan.jvmArguments)
        assertTrue(plan.jvmArguments.any { it.startsWith("-Djava.class.path=") })
        assertEquals(plan.gameDirectory.absolutePath, plan.gameArguments[plan.gameArguments.indexOf("--gameDir") + 1])
        assertEquals("0", plan.gameArguments[plan.gameArguments.indexOf("--accessToken") + 1])
        assertTrue("--width" in plan.gameArguments)
        assertTrue(plan.toString().contains("redacted"))
    }
    @Test fun inheritedProfileOverridesLibraryVersionAndAppendsArguments() {
        val root = temporary.newFolder(); base(root)
        val child = jsonObject("""{"id":"fabric-test","inheritsFrom":"1.20.1","mainClass":"example.ChildMain","arguments":{"game":["--child","yes"]}}""".toByteArray())
        child.add("libraries", JsonArray().also { it.add(library(root, "2.0", "example/ChildMain.class")) })
        save(root, "fabric-test", child)
        val plan = prepare(root, "fabric-test")
        assertEquals("1.20.1", plan.baseId)
        assertEquals("example.ChildMain", plan.mainClass)
        assertEquals(2, plan.classpath.size)
        assertTrue(plan.classpath.first().name.contains("2.0"))
        assertEquals(listOf("--child", "yes"), plan.gameArguments.takeLast(2))
    }
    @Test fun missingAndCorruptedLibrariesBlockPlanCreation() {
        val root = temporary.newFolder(); base(root)
        val file = containedFile(root, "minecraft/libraries/org/example/demo/1.0/demo-1.0.jar")
        file.writeText("corrupt")
        assertThrows(IllegalArgumentException::class.java) { prepare(root) }
        file.delete()
        assertThrows(IllegalArgumentException::class.java) { prepare(root) }
    }
    @Test fun resourceIndexAndObjectsAreCheckedWithoutTreatingDownloadsAsReady() {
        val root = temporary.newFolder(); val json = base(root)
        val hash = "a".repeat(40)
        val assets = artifact(root, "minecraft/assets/indexes/test.json", """{"objects":{"test":{"hash":"$hash","size":3}}}""".toByteArray()).also { it.addProperty("id", "test") }
        json.add("assetIndex", assets); save(root, "1.20.1", json)
        assertThrows(IllegalArgumentException::class.java) { prepare(root) }
        containedFile(root, "minecraft/assets/objects/aa/$hash").also { it.parentFile!!.mkdirs(); it.writeText("abc") }
        assertEquals(1, prepare(root).assetCount)
    }
    @Test fun wrongMainClassAndUnknownPlaceholdersAreRejected() {
        val root = temporary.newFolder(); val json = base(root)
        json.addProperty("mainClass", "example.Missing"); save(root, "1.20.1", json)
        assertThrows(IllegalArgumentException::class.java) { prepare(root) }
        json.addProperty("mainClass", "net.minecraft.client.main.Main")
        json.getAsJsonObject("arguments").getAsJsonArray("game").add("\${unsupported_value}")
        save(root, "1.20.1", json)
        assertThrows(IllegalStateException::class.java) { prepare(root) }
    }
    @Test fun classpathTraversalAndUnsafeVmHooksAreRejected() {
        val root = temporary.newFolder(); val json = base(root)
        val descriptor = json.getAsJsonArray("libraries")[0].asJsonObject.getAsJsonObject("downloads").getAsJsonObject("artifact")
        val old = descriptor["path"].asString
        descriptor.addProperty("path", "../outside.jar"); save(root, "1.20.1", json)
        assertThrows(IllegalArgumentException::class.java) { prepare(root) }
        descriptor.addProperty("path", old)
        json.getAsJsonObject("arguments").getAsJsonArray("jvm").add("-XX:OnError=some-command")
        save(root, "1.20.1", json)
        assertThrows(IllegalArgumentException::class.java) { prepare(root) }
    }
    @Test fun cyclesAndMissingParentsAreReportedAndBrokenVersionsRemainVisible() {
        val root = temporary.newFolder()
        save(root, "a", jsonObject("""{"id":"a","inheritsFrom":"b"}""".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { InstalledVersions(root).resolve("a") }
        save(root, "b", jsonObject("""{"id":"b","inheritsFrom":"a"}""".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { InstalledVersions(root).resolve("a") }
        containedFile(root, "minecraft/versions/b/b.json").writeText("broken")
        assertNotNull(InstalledVersions(root).list().first { it.id == "b" }.problem)
    }
    @Test fun offlineIdentityCannotBeMixedWithAnotherUuid() {
        val root = temporary.newFolder(); base(root)
        assertThrows(IllegalArgumentException::class.java) {
            LaunchPlanner(root).prepare("1.20.1", LocalLaunchIdentity(name, UUID.randomUUID().toString()), "arm64-v8a", 1280, 720)
        }
    }
    @Test fun legacyQuotesAreParsedBeforePathSubstitution() {
        val root = temporary.newFolder("legacy files"); val json = base(root)
        json.remove("arguments")
        json.addProperty("minecraftArguments", "--username \${auth_player_name} --gameDir \"\${game_directory}\" --userProperties {}")
        save(root, "1.20.1", json)
        val plan = prepare(root)
        assertEquals(listOf("--username", name, "--gameDir", plan.gameDirectory.absolutePath, "--userProperties", "{}"), plan.gameArguments)
        assertThrows(IllegalArgumentException::class.java) { legacyTokens("--bad \"unclosed") }
    }
    @Test fun modernChildArgumentsPreserveLegacyParentArguments() {
        val root = temporary.newFolder(); val json = base(root)
        json.remove("arguments")
        json.addProperty("minecraftArguments", "--username \${auth_player_name}")
        save(root, "1.20.1", json)
        save(root, "mixed", jsonObject("""{"id":"mixed","inheritsFrom":"1.20.1","arguments":{"game":["--child","yes"]}}""".toByteArray()))
        assertEquals(listOf("--username", name, "--child", "yes"), prepare(root, "mixed").gameArguments)
    }
    @Test fun platformFeaturesAndNativeClassifiersAreFilteredBeforeCheckingFiles() {
        val root = temporary.newFolder(); val json = base(root)
        val libraries = json.getAsJsonArray("libraries")
        libraries.add(jsonObject("""{"name":"example:windows:1","rules":[{"action":"allow","os":{"name":"windows"}}]}""".toByteArray()))
        libraries.add(jsonObject("""{"name":"org.lwjgl:lwjgl:3:natives-linux"}""".toByteArray()))
        save(root, "1.20.1", json)
        assertEquals(2, prepare(root).classpath.size)
        val rules = jsonObject("""{"rules":[{"action":"allow","features":{"is_demo_user":true}}]}""".toByteArray()).getAsJsonArray("rules")
        assertFalse(launchRuleAllowed(rules, "aarch64", mapOf("is_demo_user" to false)))
    }
}
