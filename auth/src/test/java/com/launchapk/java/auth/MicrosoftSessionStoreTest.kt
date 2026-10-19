package com.launchapk.java.auth

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class MicrosoftSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val session = MicrosoftSession("12345678-1234-1234-1234-123456789abc", "TestPlayer",
        "0123456789abcdef0123456789abcdef", "access-secret", "refresh-secret", 12345)
    @Test fun persistsEncryptedAndRemovesCredentialsOnLogout() {
        val directory = temporary.newFolder(); val key = key()
        val store = MicrosoftSessionStore(directory) { key }
        assertNull(store.load()); store.save(session)
        val bytes = store.file.readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("secret"))
        assertEquals(session.refreshToken, MicrosoftSessionStore(directory) { key }.load()!!.refreshToken)
        store.save(session)
        assertFalse(bytes.contentEquals(store.file.readBytes())) // New nonce on every write.
        store.clear(); assertNull(store.load())
    }
    @Test fun wrongKeyAndModifiedCiphertextFailWithoutOverwritingRecord() {
        val directory = temporary.newFolder(); val key = key()
        val store = MicrosoftSessionStore(directory) { key }; store.save(session)
        val original = store.file.readBytes()
        assertThrows(Exception::class.java) { MicrosoftSessionStore(directory) { key() }.load() }
        assertArrayEquals(original, store.file.readBytes())
        val modified = original.clone(); modified[modified.lastIndex] = (modified.last().toInt() xor 1).toByte()
        store.file.writeBytes(modified)
        assertThrows(Exception::class.java) { store.load() }
        assertArrayEquals(modified, store.file.readBytes())
    }
}
