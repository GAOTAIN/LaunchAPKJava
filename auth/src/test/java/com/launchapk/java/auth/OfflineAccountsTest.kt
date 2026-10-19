package com.launchapk.java.auth

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineAccountsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun identityIsStableAndCaseSensitive() {
        val notch = OfflineAccount.create(" Notch ")
        assertEquals("Notch", notch.name)
        assertEquals("b50ad385-829d-3141-a216-7e7d7539ba7f", notch.uuid)
        assertEquals("5627dd98-e6be-3c21-b8a8-e92344183641", OfflineAccount.create("Steve").uuid)
        assertNotEquals(notch.uuid, OfflineAccount.create("notch").uuid)
    }

    @Test fun rejectsInvalidNamesAndForgedIdentity() {
        listOf("", "ab", "a".repeat(17), "../Steve", "two words", "玩家", "a\nb").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { OfflineAccount.create(name) }
        }
        assertThrows(IllegalArgumentException::class.java) { OfflineAccount("Steve", "random-id").validated() }
    }

    @Test fun rejectsDuplicateNamesAndUnknownSelection() {
        val state = OfflineAccounts().add("Steve")
        assertThrows(IllegalArgumentException::class.java) { state.add("steve") }
        assertThrows(IllegalArgumentException::class.java) { state.select("missing") }
        assertThrows(IllegalArgumentException::class.java) { state.copy(selectedUuid = null).validated() }
    }

    @Test fun removingSelectedFallsBackAndThenClearsSelection() {
        val state = OfflineAccounts().add("Steve").add("Alex")
        val remaining = state.remove(state.selectedUuid!!)
        assertEquals("Steve", remaining.selected?.name)
        assertEquals(OfflineAccounts(), remaining.remove(remaining.selectedUuid!!))
    }

    @Test fun selectionAndNamesSurviveNewStoreInstance() {
        val directory = temporary.newFolder()
        val store = OfflineAccountStore(directory)
        assertEquals(OfflineAccounts(), store.load())
        val first = store.update { it.add("Steve") }
        store.update { it.add("Alex") }
        store.update { it.select(first.selectedUuid!!) }
        val restored = OfflineAccountStore(directory).load()
        assertEquals(listOf("Steve", "Alex"), restored.accounts.map { it.name })
        assertEquals(first.selectedUuid, restored.selectedUuid)
        assertEquals(listOf("offline.json"), store.file.parentFile!!.list()!!.toList())
    }

    @Test fun invalidUpdatePreservesSavedFile() {
        val store = OfflineAccountStore(temporary.newFolder())
        val state = store.update { it.add("Steve") }
        val before = store.file.readText()
        assertThrows(IllegalArgumentException::class.java) { store.update { it.add("STEVE") } }
        assertEquals(before, store.file.readText())
        assertEquals(state, store.load())
    }

    @Test fun corruptedFileIsNotSilentlyReplaced() {
        val store = OfflineAccountStore(temporary.newFolder())
        store.file.parentFile!!.mkdirs()
        store.file.writeText("broken json")
        assertThrows(Exception::class.java) { store.load() }
        assertThrows(Exception::class.java) { store.update { it.add("Steve") } }
        assertEquals("broken json", store.file.readText())
    }

    @Test fun jsonRejectsUnsupportedSchemaTypeAndSelection() {
        val valid = OfflineAccountsJson.encode(OfflineAccounts().add("Steve"))
        assertEquals(OfflineAccounts().add("Steve"), OfflineAccountsJson.decode(valid))
        assertEquals(OfflineAccounts(), OfflineAccountsJson.decode(OfflineAccountsJson.encode(OfflineAccounts())))
        assertThrows(IllegalArgumentException::class.java) { OfflineAccountsJson.decode(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")) }
        assertThrows(IllegalArgumentException::class.java) { OfflineAccountsJson.decode(valid.replace("offline", "microsoft")) }
        val unknown = valid.replace("\"selectedUuid\": \"5627dd98-e6be-3c21-b8a8-e92344183641\"", "\"selectedUuid\": \"missing\"")
        assertThrows(IllegalArgumentException::class.java) { OfflineAccountsJson.decode(unknown) }
    }
}
