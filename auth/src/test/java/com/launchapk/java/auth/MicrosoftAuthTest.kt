package com.launchapk.java.auth

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class MicrosoftAuthTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun response(text: String, code: Int = 200) = AuthResponse(code, JsonParser.parseString(text).asJsonObject)
    private class Fake(val replies: MutableList<AuthResponse>) : AuthTransport {
        val calls = mutableListOf<List<String?>>()
        override fun request(url: String, body: String?, form: Boolean, bearer: String?): AuthResponse {
            calls += listOf(url, body, bearer)
            return replies.removeAt(0)
        }
    }
    private fun success(owned: Boolean = true) = mutableListOf(
        response("""{"access_token":"msa-secret","refresh_token":"refresh-secret"}"""),
        response("""{"Token":"user-secret","DisplayClaims":{"xui":[{"uhs":"hash"}]}}"""),
        response("""{"Token":"xsts-secret","DisplayClaims":{"xui":[{"uhs":"hash"}]}}"""),
        response("""{"access_token":"minecraft-secret","expires_in":3600}"""),
        response(if (owned) """{"items":[{"name":"game_minecraft"}]}""" else """{"items":[]}"""),
        response("""{"id":"0123456789abcdef0123456789abcdef","name":"TestPlayer"}"""),
    )
    private fun challenge(end: Long = 100000) = DeviceChallenge("device-secret", "ABCD-EFGH", "https://microsoft.com/devicelogin", end, 5)

    @Test fun pollsAtServerIntervalThenVerifiesOwnershipAndProfile() = runBlocking {
        var clock = 0L; val delays = mutableListOf<Long>()
        val fake = Fake((mutableListOf(response("""{"error":"authorization_pending"}""",400), response("""{"error":"slow_down"}""",400)) + success()).toMutableList())
        val client = MicrosoftAuthClient(fake, { clock }, { delays += it; clock += it })
        val session = client.complete(id, challenge())
        assertEquals(listOf(5000L,5000L,10000L), delays)
        assertEquals("TestPlayer", session.name)
        assertEquals("minecraft-secret", session.accessToken)
        assertTrue(fake.calls.any { it[0]!!.contains("/entitlements/license?requestId=") && it[2] == "minecraft-secret" })
        assertEquals(0, fake.replies.size)
        assertFalse(session.toString().contains("secret"))
        assertFalse(challenge().toString().contains("secret"))
    }
    @Test fun refusesAccountWithoutLicense() = runBlocking {
        val fake = Fake(success(false))
        try { MicrosoftAuthClient(fake, { 0 }, {}).complete(id, challenge()); fail("license required")
        } catch (e: AuthFailure) { assertTrue(e.message!!.contains("游戏许可")) }
        assertFalse(fake.calls.any { it[0]!!.endsWith("minecraft/profile") })
    }
    @Test fun stopsAfterDeclineAndExpiresWithoutSendingLatePoll() = runBlocking {
        val fake = Fake(mutableListOf(response("""{"error":"authorization_declined"}""",400)))
        try { MicrosoftAuthClient(fake, { 0 }, {}).complete(id, challenge()); fail()
        } catch (e: AuthFailure) { assertTrue(e.message!!.contains("拒绝")) }
        var clock = 0L
        val expired = Fake(mutableListOf())
        try { MicrosoftAuthClient(expired, { clock }, { clock += it }).complete(id, challenge(1000)); fail()
        } catch (e: AuthFailure) { assertTrue(e.message!!.contains("过期")) }
        assertTrue(expired.calls.isEmpty())
    }
    @Test fun validatesClientAndDoesNotExposeServerErrorBody() {
        val fake = Fake(mutableListOf(response("""{"error":"invalid_client","error_description":"secret-value"}""",400)))
        assertThrows(IllegalArgumentException::class.java) { MicrosoftAuthClient(fake).begin("bad") }
        assertTrue(fake.calls.isEmpty())
        val error = assertThrows(AuthFailure::class.java) { MicrosoftAuthClient(fake).begin(id) }
        assertFalse(error.message!!.contains("secret-value"))
        assertTrue(error.message!!.contains("client ID"))
    }
    @Test fun refreshRevalidatesLicenseAndPreservesRotatedToken() {
        val fake = Fake(success())
        val session = MicrosoftSession(id,"TestPlayer","0123456789abcdef0123456789abcdef","old-access","old-refresh",0)
        val result = MicrosoftAuthClient(fake).refresh(session)
        assertEquals("refresh-secret", result.refreshToken)
        assertTrue(URLDecoder.decode(fake.calls.first()[1], "UTF-8").contains("refresh_token=old-refresh"))
        assertTrue(fake.calls.any { it[0]!!.contains("/entitlements/license?requestId=") })
    }
    @Test fun onlyOpensOfficialVerificationHost() {
        val fake = Fake(mutableListOf(response("""{"device_code":"secret","user_code":"code","expires_in":900,"verification_uri":"https://evil.invalid/login"}""")))
        assertThrows(IllegalArgumentException::class.java) { MicrosoftAuthClient(fake).begin(id) }
        assertThrows(IllegalArgumentException::class.java) { MicrosoftHttpsTransport().request("https://evil.invalid",null,false,null) }
    }
    @Test fun cancellationStopsPollingBeforeAnyTokenRequest() = runBlocking {
        val fake = Fake(mutableListOf())
        val client = MicrosoftAuthClient(fake)
        val task = launch { client.complete(id, challenge(Long.MAX_VALUE)) }
        yield()
        task.cancelAndJoin()
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun rejectsMismatchedXboxIdentity() = runBlocking {
        val replies = success()
        replies[2] = response("""{"Token":"xsts-secret","DisplayClaims":{"xui":[{"uhs":"other-user"}]}}""")
        val fake = Fake(replies)
        try { MicrosoftAuthClient(fake, { 0 }, {}).complete(id, challenge()); fail()
        } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("身份不一致")) }
        assertFalse(fake.calls.any { it[0]!!.contains("minecraftservices") })
    }

}
