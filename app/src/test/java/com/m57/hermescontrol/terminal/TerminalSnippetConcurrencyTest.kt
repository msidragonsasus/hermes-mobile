package com.m57.hermescontrol.terminal

import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Fixture values only. Never a real password or server. */
private const val SCOPE = "https://server-a.example.test/\nprofile-a\nprofile-a"
private const val FIXTURE_SECRET = "fixture-not-a-password-9K"

/** Holds the first armed read open until released, and reports whether a second read started meanwhile. */
private class GatedStorage : TerminalSecretStorage {
    val values = ConcurrentHashMap<String, String>()
    val firstReadStarted = CountDownLatch(1)
    val releaseFirstRead = CountDownLatch(1)
    val secondReadStarted = CountDownLatch(1)
    private val reads = AtomicInteger()

    @Volatile var armed = false

    override fun read(key: String): String? {
        if (armed) {
            when (reads.incrementAndGet()) {
                1 -> {
                    firstReadStarted.countDown()
                    check(releaseFirstRead.await(5, TimeUnit.SECONDS))
                }

                2 -> {
                    secondReadStarted.countDown()
                }
            }
        }
        return values[key]
    }

    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** The first write blocks until released; [fail] makes writes throw. */
private class SlowWriteStorage : TerminalSecretStorage {
    val values = ConcurrentHashMap<String, String>()
    val writeStarted = CountDownLatch(1)
    val release = CountDownLatch(1)
    val writes = AtomicInteger()

    @Volatile var fail = false

    override fun read(key: String): String? = values[key]

    override fun write(
        key: String,
        value: String?,
    ) {
        if (fail) throw java.io.IOException("disk")
        if (writes.incrementAndGet() == 1) {
            writeStarted.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        if (value == null) values.remove(key) else values[key] = value
    }
}

class TerminalSnippetConcurrencyTest {
    private val keiya =
        TerminalSnippet("k", "keiyaのパスワード", FIXTURE_SECRET, secret = true, role = KEIYA_PASSWORD_ROLE)

    /** Runs [first] until it is inside its read, starts [second] on another store, then releases. */
    private fun race(
        storage: GatedStorage,
        first: () -> Unit,
        second: () -> Unit,
    ): Boolean {
        val failure = AtomicReference<Throwable?>(null)
        storage.armed = true
        val a = thread { runCatching(first).onFailure { failure.set(it) } }
        assertTrue(storage.firstReadStarted.await(5, TimeUnit.SECONDS))
        val b = thread { runCatching(second).onFailure { failure.set(it) } }
        val overlapped = storage.secondReadStarted.await(300, TimeUnit.MILLISECONDS)
        storage.releaseFirstRead.countDown()
        a.join(5000)
        b.join(5000)
        storage.armed = false
        assertNull(failure.get())
        return overlapped
    }

    @Test
    fun concurrentSavesFromTwoStoresKeepBothEntries() {
        val storage = GatedStorage()
        val overlapped =
            race(
                storage,
                { TerminalSnippetStore(storage, SCOPE).upsert(TerminalSnippet("1", "one", "echo 1")) },
                { TerminalSnippetStore(storage, SCOPE).upsert(TerminalSnippet("2", "two", "echo 2")) },
            )
        assertFalse("the second transaction must wait for the first", overlapped)
        assertEquals(setOf("1", "2"), TerminalSnippetStore(storage, SCOPE).load().map { it.id }.toSet())
    }

    @Test
    fun deletedPasswordIsNotResurrectedByAConcurrentSave() {
        val storage = GatedStorage()
        TerminalSnippetStore(storage, SCOPE).upsert(keiya)
        val overlapped =
            race(
                storage,
                { TerminalSnippetStore(storage, SCOPE).upsert(TerminalSnippet("2", "two", "echo 2")) },
                { TerminalSnippetStore(storage, SCOPE).delete("k") },
            )
        assertFalse(overlapped)
        assertEquals(listOf("2"), TerminalSnippetStore(storage, SCOPE).load().map { it.id })
    }

    @Test
    fun overlappingSaveOrDeleteIsRejectedWhilePendingAndFailureReenables() =
        runBlocking {
            val storage = SlowWriteStorage()
            val model = TerminalSnippetsModel(TerminalSnippetStore(storage, SCOPE))
            model.load()
            val first = async(Dispatchers.Default) { model.save(TerminalSnippet("1", "one", "echo 1")) }
            assertTrue(storage.writeStarted.await(5, TimeUnit.SECONDS))
            assertTrue(model.state.value.busy)
            assertFalse("no duplicate save while pending", model.save(TerminalSnippet("1", "one", "echo 1")))
            assertFalse(model.save(TerminalSnippet("2", "two", "echo 2")))
            assertFalse(model.delete("1"))
            storage.release.countDown()
            assertTrue(first.await())
            assertFalse(model.state.value.busy)
            assertEquals(
                listOf("1"),
                model.state.value.items
                    .map { it.id },
            )
            assertEquals(1, storage.writes.get())

            storage.fail = true
            assertFalse(model.save(TerminalSnippet("2", "two", "echo 2")))
            assertFalse("failure restores the enabled state", model.state.value.busy)
            assertTrue(model.state.value.error != null)
            storage.fail = false
            assertTrue(model.save(TerminalSnippet("2", "two", "echo 2")))
            assertEquals(
                listOf("1", "2"),
                model.state.value.items
                    .map { it.id },
            )
        }

    @Test
    fun snippetEditorDialogIsExcludedFromScreenshotsAndRecents() {
        assertEquals(SecureFlagPolicy.SecureOn, terminalSnippetEditorProperties().securePolicy)
    }
}
