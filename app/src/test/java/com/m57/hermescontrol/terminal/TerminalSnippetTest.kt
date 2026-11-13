package com.m57.hermescontrol.terminal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture values only. Never a real password or server. */
private const val FIXTURE_SECRET = "fixture-not-a-password-7Q"
private const val SCOPE_A = "https://server-a.example.test/\nprofile-a\nprofile-a"
private const val SCOPE_B = "https://server-b.example.test/\nprofile-a\nprofile-a"

private class MemoryStorage : TerminalSecretStorage {
    val values = mutableMapOf<String, String>()
    var failReads = false
    var failWrites = false
    var writes = 0

    override fun read(key: String): String? {
        if (failReads) throw java.security.KeyStoreException("key unavailable $FIXTURE_SECRET")
        return values[key]
    }

    override fun write(
        key: String,
        value: String?,
    ) {
        if (failWrites) throw java.io.IOException("disk $FIXTURE_SECRET")
        writes++
        if (value == null) values.remove(key) else values[key] = value
    }
}

class TerminalSnippetTest {
    private fun keiya(value: String = FIXTURE_SECRET) =
        TerminalSnippet(id = "k", label = "keiya のパスワード", value = value, secret = true, role = KEIYA_PASSWORD_ROLE)

    @Test
    fun savedEntriesSurviveANewStoreInstanceForTheSameScope() {
        val storage = MemoryStorage()
        val first = TerminalSnippetStore(storage, SCOPE_A)
        first.upsert(TerminalSnippet("1", "tmux", "tmux new -As mobile"))
        first.upsert(keiya())
        val restarted = TerminalSnippetStore(storage, SCOPE_A).load()
        assertEquals(listOf("tmux", "keiya のパスワード"), restarted.map { it.label })
        assertEquals(FIXTURE_SECRET, restarted.keiyaPassword()?.value)
    }

    @Test
    fun entriesAreBoundToExactServerAndProfile() {
        val storage = MemoryStorage()
        TerminalSnippetStore(storage, SCOPE_A).upsert(keiya())
        assertEquals(emptyList<TerminalSnippet>(), TerminalSnippetStore(storage, SCOPE_B).load())
        assertEquals(
            emptyList<TerminalSnippet>(),
            TerminalSnippetStore(storage, SCOPE_A.replace("profile-a\nprofile-a", "profile-b\nprofile-b")).load(),
        )
        assertTrue(
            "storage keys never contain the raw server",
            storage.values.keys.none { it.contains("example.test") },
        )
    }

    @Test
    fun staleScopeCannotReadOrWrite() {
        val storage = MemoryStorage()
        TerminalSnippetStore(storage, SCOPE_A).upsert(keiya())
        val writes = storage.writes
        val stale = TerminalSnippetStore(storage, SCOPE_A) { false }
        assertThrows(TerminalSnippetException::class.java) { stale.load() }
        assertThrows(TerminalSnippetException::class.java) { stale.upsert(TerminalSnippet("2", "x", "y")) }
        assertThrows(TerminalSnippetException::class.java) { stale.delete("k") }
        assertEquals(writes, storage.writes)
    }

    @Test
    fun corruptDataFailsClosedWithoutOverwrite() {
        val storage = MemoryStorage()
        val store = TerminalSnippetStore(storage, SCOPE_A)
        store.upsert(keiya())
        val key = storage.values.keys.single()
        storage.values[key] = "{not json"
        assertThrows(TerminalSnippetException::class.java) { store.load() }
        assertThrows(TerminalSnippetException::class.java) { store.upsert(TerminalSnippet("2", "x", "y")) }
        assertThrows(TerminalSnippetException::class.java) { store.delete("k") }
        assertEquals("{not json", storage.values[key])
    }

    @Test
    fun unavailableKeyFailsClosedAndErrorsNeverCarryValues() {
        val storage = MemoryStorage()
        val store = TerminalSnippetStore(storage, SCOPE_A)
        store.upsert(keiya())
        storage.failReads = true
        val error = assertThrows(TerminalSnippetException::class.java) { store.load() }
        assertFalse(error.toString().contains(FIXTURE_SECRET))
        assertNull(error.cause)
        val writes = storage.writes
        assertThrows(TerminalSnippetException::class.java) { store.upsert(TerminalSnippet("2", "x", "y")) }
        assertEquals(writes, storage.writes)
        storage.failReads = false
        storage.failWrites = true
        val writeError = assertThrows(TerminalSnippetException::class.java) { store.upsert(keiya("other-fixture")) }
        assertFalse(writeError.toString().contains(FIXTURE_SECRET))
        assertFalse(writeError.toString().contains("other-fixture"))
    }

    @Test
    fun editReplacesAndDeleteRemoves() {
        val storage = MemoryStorage()
        val store = TerminalSnippetStore(storage, SCOPE_A)
        store.upsert(TerminalSnippet("1", "ls", "ls -la"))
        store.upsert(TerminalSnippet("1", "list", "ls -lah"))
        assertEquals(listOf(TerminalSnippet("1", "list", "ls -lah")), store.load())
        store.upsert(keiya())
        assertEquals(listOf("1"), store.delete("k").map { it.id })
        assertEquals(listOf("1"), TerminalSnippetStore(storage, SCOPE_A).load().map { it.id })
        assertEquals(emptyList<TerminalSnippet>(), store.delete("1"))
    }

    @Test
    fun invalidEntriesAreRejectedAndKeiyaPasswordIsUniqueAndSecret() {
        val store = TerminalSnippetStore(MemoryStorage(), SCOPE_A)
        assertThrows(TerminalSnippetException::class.java) { store.upsert(TerminalSnippet("1", " ", "x")) }
        assertThrows(TerminalSnippetException::class.java) { store.upsert(TerminalSnippet("1", "x", "")) }
        assertThrows(TerminalSnippetException::class.java) { store.upsert(TerminalSnippet("1", "x", "a".repeat(4097))) }
        assertThrows(TerminalSnippetException::class.java) { store.upsert(keiya().copy(secret = false)) }
        store.upsert(keiya())
        store.upsert(keiya("second-fixture").copy(id = "k2"))
        val saved = store.load()
        assertEquals(1, saved.count { it.role == KEIYA_PASSWORD_ROLE })
        assertEquals("second-fixture", saved.keiyaPassword()?.value)
    }

    @Test
    fun toStringNeverRevealsValues() {
        assertFalse(keiya().toString().contains(FIXTURE_SECRET))
        assertFalse(TerminalSnippet("1", "label", "visible-command").toString().contains("visible-command"))
        assertFalse(TerminalDraft(FIXTURE_SECRET, secret = true).toString().contains(FIXTURE_SECRET))
        assertFalse(TerminalSnippetState(items = listOf(keiya())).toString().contains(FIXTURE_SECRET))
    }

    @Test
    fun insertingAppendsToTheDraftAndMasksOnlyWhenASecretIsPresent() {
        val command = TerminalDraft().edit("echo ").insert(TerminalSnippet("1", "x", "hello"))
        assertEquals(TerminalDraft("echo hello", secret = false), command)
        val password = TerminalDraft().insert(keiya())
        assertEquals(TerminalDraft(FIXTURE_SECRET, secret = true), password)
        assertTrue(password.edit(FIXTURE_SECRET + "x").secret)
        assertFalse("clearing the field ends masking", password.edit("").secret)
        assertTrue(TerminalDraft("sudo -k; ").insert(keiya()).secret)
    }

    @Test
    fun modelLoadsSavesAndDeletesWithoutSending() =
        runBlocking {
            val storage = MemoryStorage()
            val model = TerminalSnippetsModel(TerminalSnippetStore(storage, SCOPE_A))
            model.load()
            assertTrue(model.state.value.loaded)
            assertTrue(model.save(keiya()))
            assertNotNull(
                model.state.value.items
                    .keiyaPassword(),
            )
            assertTrue(model.delete("k"))
            assertEquals(emptyList<TerminalSnippet>(), model.state.value.items)
            storage.failReads = true
            model.load()
            assertFalse(model.state.value.loaded)
            assertNotNull(model.state.value.error)
            assertFalse(model.save(keiya()))
            assertFalse(
                model.state.value.error!!
                    .contains(FIXTURE_SECRET),
            )
        }

    @Test
    fun snapshotParsingClampsOffsetsAndKeepsTheVisibleTop() {
        val pressed = parseTerminalSnapshot("{\"text\":\"ab\\ncd\",\"offset\":4,\"top\":3}", word = true)
        assertEquals(TerminalSelection("ab\ncd", 4, top = 3, word = true), pressed)
        assertEquals(
            TerminalSelection("ab", 2, top = 2, word = false),
            parseTerminalSnapshot("{\"text\":\"ab\",\"offset\":99,\"top\":99}", word = false),
        )
        assertEquals(
            TerminalSelection("ab", 0, top = 0, word = false),
            parseTerminalSnapshot("{\"text\":\"ab\",\"offset\":-3,\"top\":-5}", word = false),
        )
        assertNull(parseTerminalSnapshot("not json", word = true))
    }
}
