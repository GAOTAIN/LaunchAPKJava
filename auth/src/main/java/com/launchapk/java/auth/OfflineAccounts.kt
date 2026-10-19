package com.launchapk.java.auth

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/** A local identity only. Microsoft credentials and entitlements belong to a separate future flow. */
data class OfflineAccount(val name: String, val uuid: String) {
    fun validated(): OfflineAccount {
        require(NAME.matches(name)) { "昵称须为 3–16 位英文字母、数字或下划线" }
        require(uuid == offlineUuid(name)) { "昵称与离线 UUID 不一致" }
        return this
    }

    companion object {
        private val NAME = Regex("[A-Za-z0-9_]{3,16}")
        fun create(name: String): OfflineAccount {
            val trimmed = name.trim()
            return OfflineAccount(trimmed, offlineUuid(trimmed)).validated()
        }
        private fun offlineUuid(name: String): String = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + name).toByteArray(Charsets.UTF_8),
        ).toString()
    }
}

data class OfflineAccounts(
    val accounts: List<OfflineAccount> = emptyList(),
    val selectedUuid: String? = null,
) {
    val selected: OfflineAccount? get() = accounts.firstOrNull { it.uuid == selectedUuid }

    fun validated(): OfflineAccounts {
        require(accounts.size <= 32) { "最多保存 32 个离线账户" }
        accounts.forEach { it.validated() }
        require(accounts.map { it.name.lowercase(Locale.ROOT) }.distinct().size == accounts.size) { "昵称已存在" }
        require(if (accounts.isEmpty()) selectedUuid == null else selected != null) { "所选账户不存在" }
        return this
    }

    fun add(name: String): OfflineAccounts {
        val account = OfflineAccount.create(name)
        return copy(accounts = accounts + account, selectedUuid = account.uuid).validated()
    }

    fun select(uuid: String): OfflineAccounts = copy(selectedUuid = uuid).validated()

    fun remove(uuid: String): OfflineAccounts {
        require(accounts.any { it.uuid == uuid }) { "账户不存在" }
        val remaining = accounts.filterNot { it.uuid == uuid }
        return copy(accounts = remaining, selectedUuid = if (selectedUuid == uuid) remaining.firstOrNull()?.uuid else selectedUuid).validated()
    }
}

object OfflineAccountsJson {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    fun encode(state: OfflineAccounts): String {
        state.validated()
        val root = JsonObject()
        root.addProperty("schemaVersion", 1)
        root.addProperty("selectedUuid", state.selectedUuid)
        root.add("accounts", JsonArray().apply {
            state.accounts.forEach { account ->
                add(JsonObject().apply {
                    addProperty("type", "offline")
                    addProperty("name", account.name)
                    addProperty("uuid", account.uuid)
                })
            }
        })
        return gson.toJson(root)
    }

    fun decode(text: String): OfflineAccounts {
        require(text.length <= 64 * 1024) { "账户文件过大" }
        val root = JsonParser.parseString(text).asJsonObject
        require(root["schemaVersion"]?.asInt == 1) { "不支持的账户文件版本" }
        val entries = root.getAsJsonArray("accounts") ?: error("缺少账户列表")
        val accounts = entries.map { entry ->
            val account = entry.asJsonObject
            require(account["type"]?.asString == "offline") { "不支持的账户类型" }
            OfflineAccount(account["name"].asString, account["uuid"].asString).validated()
        }
        val selected = root["selectedUuid"]?.takeUnless { it.isJsonNull }?.asString
        return OfflineAccounts(accounts, selected).validated()
    }
}

/** Construct with context.filesDir; fixed filenames never come from a player name or imported JSON. */
class OfflineAccountStore(filesDir: File) {
    private val directory = File(filesDir, "accounts")
    val file = File(directory, "offline.json")

    @Synchronized
    fun load(): OfflineAccounts {
        if (!file.exists()) return OfflineAccounts()
        require(file.length() <= 64 * 1024) { "账户文件过大" }
        return OfflineAccountsJson.decode(file.readText(Charsets.UTF_8))
    }

    @Synchronized
    fun update(transform: (OfflineAccounts) -> OfflineAccounts): OfflineAccounts {
        val next = transform(load()).validated()
        val json = OfflineAccountsJson.encode(next)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建账户目录" }
        val temporary = Files.createTempFile(directory.toPath(), ".offline-", ".tmp").toFile()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        return next
    }
}
