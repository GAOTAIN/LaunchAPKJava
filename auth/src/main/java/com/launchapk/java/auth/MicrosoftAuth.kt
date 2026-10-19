package com.launchapk.java.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class AuthFailure(message: String) : Exception(message)
class DeviceChallenge(val deviceCode: String, val userCode: String, val verificationUri: String,
    val expiresAt: Long, val intervalSeconds: Long) {
    override fun toString() = "DeviceChallenge(redacted)"
}
class MicrosoftSession(val clientId: String, val name: String, val uuid: String, val accessToken: String,
    val refreshToken: String, val expiresAt: Long) {
    override fun toString() = "MicrosoftSession(redacted)"
}
class AuthResponse(val status: Int, val json: JsonObject) {
    override fun toString() = "AuthResponse(status=$status, body=redacted)"
}
fun interface AuthTransport {
    fun request(url: String, body: String?, form: Boolean, bearer: String?): AuthResponse
}

fun validMicrosoftClientId(value: String): String {
    val trimmed = value.trim()
    require(trimmed.matches(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) && UUID.fromString(trimmed) != UUID(0, 0)) {
        "请填写有效的 Microsoft Application（client）ID"
    }
    return trimmed.lowercase()
}

class MicrosoftAuthClient(
    private val transport: AuthTransport = MicrosoftHttpsTransport(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    companion object {
        private const val AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0/"
        private const val SCOPE = "XboxLive.SignIn XboxLive.offline_access"
    }
    private fun form(values: Map<String, String>) = values.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }
    fun begin(clientId: String): DeviceChallenge {
        val id = validMicrosoftClientId(clientId)
        val response = transport.request(AUTH + "devicecode", form(mapOf("client_id" to id, "scope" to SCOPE)), true, null)
        val json = successful(response, "微软设备码")
        val seconds = json["expires_in"].asLong.also { require(it in 1..3600) }
        val interval = (json["interval"]?.asLong ?: 5).coerceAtLeast(5).also { require(it <= 300) }
        val uri = URI(text(json, "verification_uri"))
        require(uri.scheme == "https" && uri.host in setOf("microsoft.com", "www.microsoft.com", "login.microsoftonline.com") && uri.userInfo == null && uri.port == -1) { "微软授权地址无效" }
        return DeviceChallenge(text(json, "device_code"), text(json, "user_code"), uri.toString(), now() + seconds * 1000, interval)
    }
    suspend fun complete(clientId: String, challenge: DeviceChallenge): MicrosoftSession {
        val id = validMicrosoftClientId(clientId)
        var interval = challenge.intervalSeconds
        while (now() < challenge.expiresAt) {
            pause(interval * 1000)
            if (now() >= challenge.expiresAt) break
            val response = transport.request(AUTH + "token", form(mapOf("client_id" to id,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to challenge.deviceCode)), true, null)
            if (response.status == 200) return exchange(id, response.json)
            when (response.json["error"]?.asString) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "authorization_declined", "access_denied" -> throw AuthFailure("你已拒绝本次授权")
                "expired_token" -> throw AuthFailure("授权码已过期，请重新登录")
                else -> successful(response, "微软授权")
            }
        }
        throw AuthFailure("授权码已过期，请重新登录")
    }
    fun refresh(session: MicrosoftSession): MicrosoftSession {
        val response = transport.request(AUTH + "token", form(mapOf("client_id" to validMicrosoftClientId(session.clientId),
            "grant_type" to "refresh_token", "refresh_token" to session.refreshToken, "scope" to SCOPE)), true, null)
        val token = successful(response, "刷新微软登录")
        if (!token.has("refresh_token")) token.addProperty("refresh_token", session.refreshToken)
        val result = exchange(session.clientId, token)
        require(result.uuid == session.uuid) { "刷新后的账号身份不一致，请重新登录" }
        return result
    }
    private fun exchange(clientId: String, oauth: JsonObject): MicrosoftSession {
        val msa = text(oauth, "access_token")
        val refresh = text(oauth, "refresh_token")
        fun post(url: String, body: String, label: String) = successful(transport.request(url, body, false, null), label)
        val user = post("https://user.auth.xboxlive.com/user/authenticate", JsonObject().apply {
            add("Properties", JsonObject().apply {
                addProperty("AuthMethod", "RPS"); addProperty("SiteName", "user.auth.xboxlive.com"); addProperty("RpsTicket", "d=$msa")
            }); addProperty("RelyingParty", "http://auth.xboxlive.com"); addProperty("TokenType", "JWT")
        }.toString(), "Xbox 登录")
        val userHash = text(user.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")[0].asJsonObject, "uhs")
        val xsts = post("https://xsts.auth.xboxlive.com/xsts/authorize", JsonObject().apply {
            add("Properties", JsonObject().apply {
                addProperty("SandboxId", "RETAIL")
                add("UserTokens", com.google.gson.JsonArray().apply { add(text(user, "Token")) })
            }); addProperty("RelyingParty", "rp://api.minecraftservices.com/"); addProperty("TokenType", "JWT")
        }.toString(), "Xbox 游戏授权")
        require(text(xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")[0].asJsonObject, "uhs") == userHash) { "Xbox 身份不一致" }
        val minecraft = post("https://api.minecraftservices.com/authentication/login_with_xbox", JsonObject().apply {
            addProperty("identityToken", "XBL3.0 x=$userHash;${text(xsts, "Token")}")
        }.toString(), "Minecraft 登录")
        val access = text(minecraft, "access_token")
        val lifetime = minecraft["expires_in"].asLong.also { require(it in 1..172800) }
        val ownership = successful(transport.request("https://api.minecraftservices.com/entitlements/license?requestId=${UUID.randomUUID()}", null, false, access), "Minecraft 游戏许可")
        val items = ownership.getAsJsonArray("items") ?: throw AuthFailure("服务器未返回游戏许可")
        if (items.none { it.asJsonObject["name"]?.asString in setOf("game_minecraft", "product_minecraft") }) {
            throw AuthFailure("此账号没有可用的 Minecraft Java 游戏许可")
        }
        val profile = successful(transport.request("https://api.minecraftservices.com/minecraft/profile", null, false, access), "Minecraft 角色资料")
        val uuid = text(profile, "id").also { require(it.matches(Regex("[a-fA-F0-9]{32}"))) { "角色 UUID 无效" } }
        val name = text(profile, "name").also { require(it.matches(Regex("[A-Za-z0-9_]{3,16}"))) { "角色昵称无效" } }
        return MicrosoftSession(clientId, name, uuid.lowercase(), access, refresh, now() + lifetime * 1000)
    }
    private fun text(json: JsonObject, key: String): String = json[key]?.asString?.takeIf { it.isNotBlank() && it.length <= 32768 }
        ?: throw AuthFailure("授权服务返回的数据不完整")
    private fun successful(response: AuthResponse, service: String): JsonObject {
        if (response.status in 200..299) return response.json
        val hint = when (response.json["error"]?.asString) {
            "invalid_client", "unauthorized_client" -> "请检查 client ID、个人账户支持和公共客户端配置"
            "invalid_grant" -> "登录已失效，请重新授权"
            "invalid_scope" -> "应用未获所需 Xbox 授权范围"
            else -> when (response.json["XErr"]?.asLong) {
                2148916233L -> "请先在 Xbox 官网建立游戏档案"
                2148916238L -> "请由家庭组织者检查未成年账号权限"
                else -> "请检查网络或应用的 Xbox/Minecraft API 访问权限"
            }
        }
        // Never include response bodies, tokens or a service's arbitrary error_description.
        throw AuthFailure("$service 失败（HTTP ${response.status}）：$hint")
    }
}

class MicrosoftHttpsTransport : AuthTransport {
    override fun request(url: String, body: String?, form: Boolean, bearer: String?): AuthResponse {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host in setOf("login.microsoftonline.com", "user.auth.xboxlive.com", "xsts.auth.xboxlive.com", "api.minecraftservices.com") && uri.port == -1 && uri.userInfo == null)
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 20000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-xbl-contract-version", "1")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytesLimited(128 * 1024) } ?: byteArrayOf()
            val json = try { JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject } catch (_: Exception) { JsonObject() }
            return AuthResponse(status, json)
        } finally { connection.disconnect() }
    }
}
private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer); if (count < 0) break
        if (output.size() + count > limit) throw AuthFailure("授权响应过大")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
