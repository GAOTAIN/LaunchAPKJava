package com.launchapk.java.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MicrosoftSessionStore(filesDir: File, private val key: () -> SecretKey) {
    val file = File(filesDir, "accounts/microsoft.enc")
    @Synchronized fun load(): MicrosoftSession? {
        if (!file.exists()) return null
        require(file.length() in 30..131072) { "登录记录无效，请退出后重新登录" }
        val bytes = file.readBytes()
        require(bytes[0] == 1.toByte()) { "登录记录版本无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val json = JsonParser.parseString(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)).asJsonObject
        require(json["schemaVersion"].asInt == 1)
        return MicrosoftSession(validMicrosoftClientId(json["clientId"].asString), json["name"].asString,
            json["uuid"].asString, json["accessToken"].asString, json["refreshToken"].asString, json["expiresAt"].asLong)
    }
    @Synchronized fun save(session: MicrosoftSession) {
        val json = JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("clientId", session.clientId)
            addProperty("name", session.name); addProperty("uuid", session.uuid)
            addProperty("accessToken", session.accessToken); addProperty("refreshToken", session.refreshToken)
            addProperty("expiresAt", session.expiresAt)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = Files.createTempFile(file.parentFile!!.toPath(), ".microsoft-", ".tmp").toFile()
        try {
            java.io.FileOutputStream(temporary).use { it.write(encrypted); it.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
    @Synchronized fun clear() { check(!file.exists() || file.delete()) { "退出账户失败" } }
}
