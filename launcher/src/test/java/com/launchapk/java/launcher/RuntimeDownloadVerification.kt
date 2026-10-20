package com.launchapk.java.launcher

import com.google.gson.GsonBuilder
import com.launchapk.java.core.HttpBody
import com.launchapk.java.core.HttpSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Explicit host-side integration task. It installs real pinned packages but does not execute Android ELF. */
object RuntimeDownloadVerification {
    @JvmStatic fun main(arguments: Array<String>) = runBlocking {
        val root=File(arguments.single()).canonicalFile
        check(root.resolve("settings.gradle.kts").isFile)
        val catalog=RuntimeCatalog.decode(root.resolve("launcher/src/main/assets/runtime-catalog.json").readText())
        val archives=root.resolve(".tmp/runtime-catalog")
        val temporary=root.resolve(".tmp/runtime-validation-${UUID.randomUUID()}")
        check(temporary.mkdirs())
        val results=mutableListOf<Map<String,Any>>()
        try {
            for(spec in catalog.packages) {
                val directory=temporary.resolve("${spec.major}-${spec.abi}")
                val source=object:HttpSource {
                    override fun open(url:String):HttpBody {
                        val file=archives.resolve("${spec.major}-${url.substringAfterLast('/')}")
                        check(file.isFile) { "先运行 scripts/verify-runtime-downloads.py 准备固定分包" }
                        return HttpBody(file.inputStream(),file.length())
                    }
                }
                val installed=AutomaticRuntimeInstaller(directory.resolve("files"),directory.resolve("cache"),source).install(spec)
                check(installed.version==spec.version && installed.abi==spec.abi)
                check(installed.home.walkTopDown().filter { it.isFile }.none { java.nio.file.Files.isSymbolicLink(it.toPath()) })
                val license=if(spec.major==8)installed.home.resolve("LICENSE") else installed.home.resolve("legal/java.base/LICENSE")
                check(license.isFile && license.readText().contains("GNU General Public License"))
                results+=mapOf("major" to spec.major,"abi" to spec.abi,"version" to installed.version,"bytes" to spec.downloadBytes,"licenseRetained" to true,"androidExecutionVerified" to false)
                println("Verified real Android Java ${installed.version} / ${spec.abi}")
            }
            val report=root.resolve("build-reports/automatic-runtime-validation.json")
            requireNotNull(report.parentFile).mkdirs();report.writeText(GsonBuilder().setPrettyPrinting().create().toJson(results)+"\n")
        } finally { temporary.deleteRecursively() }
    }
}
