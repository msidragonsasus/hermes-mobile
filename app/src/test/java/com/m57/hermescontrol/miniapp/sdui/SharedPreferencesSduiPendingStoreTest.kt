package com.m57.hermescontrol.miniapp.sdui

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedPreferencesSduiPendingStoreTest {
    /** A commit-on-demand SharedPreferences over a map; [commitResult] and [failure] script the disk. */
    private class Disk {
        val data = mutableMapOf<String, Any?>()
        private val staged = mutableMapOf<String, String?>()
        private var staleClear = false
        var commitResult = true
        var failure: Exception? = null

        val prefs: SharedPreferences = mockk()

        init {
            val editor = mockk<SharedPreferences.Editor>()
            every { prefs.getString(any(), any()) } answers {
                failure?.let { throw it }
                val value = data[firstArg<String>()]
                if (value == null) secondArg<String?>() else value as String
            }
            every { prefs.edit() } returns editor
            every { editor.putString(any(), any()) } answers {
                staged[firstArg<String>()] = secondArg<String?>()
                editor
            }
            every { editor.clear() } answers {
                staleClear = true
                editor
            }
            every { editor.commit() } answers {
                failure?.let { throw it }
                if (commitResult) {
                    if (staleClear) data.clear()
                    staged.forEach { (key, value) -> data[key] = value }
                }
                staged.clear()
                staleClear = false
                commitResult
            }
        }
    }

    private val disk = Disk()

    private fun store(
        testScheduler: TestCoroutineScheduler,
        prefs: SharedPreferences = disk.prefs,
    ) = SharedPreferencesSduiPendingStore({ prefs }, UnconfinedTestDispatcher(testScheduler))

    private val pending = SduiPendingCheck("0b1f6c0e-2f9d-4c43-9a55-1d6a8f6a1111", "bundled-1")

    // ── scope ───────────────────────────────────────────────────────────

    @Test
    fun scopeKeyIsStableHashedAndDistinguishesHostProfileAndCompany() {
        val base = sduiPendingScopeKey("https://hermes.test\nselected\nprofile-a", "company-1")
        assertEquals(base, sduiPendingScopeKey("https://hermes.test\nselected\nprofile-a", "company-1"))
        assertTrue(Regex("[0-9a-f]{32}").matches(base))
        assertFalse("hermes" in base || "company" in base)

        val others =
            listOf(
                sduiPendingScopeKey("https://hermes.test\nselected\nprofile-a", "company-2"),
                sduiPendingScopeKey("https://hermes.test\nselected\nprofile-b", "company-1"),
                sduiPendingScopeKey("https://hermes.test\nother\nprofile-a", "company-1"),
                sduiPendingScopeKey("https://other.test\nselected\nprofile-a", "company-1"),
            )
        others.forEach { assertNotEquals(base, it) }
        assertEquals(others.size, others.toSet().size)
    }

    @Test
    fun scopeAndCompanyCannotBeShiftedIntoEachOther() {
        assertNotEquals(sduiPendingScopeKey("a", "b|1:c"), sduiPendingScopeKey("a|1:b", "c"))
        assertNotEquals(sduiPendingScopeKey("ab", "c"), sduiPendingScopeKey("a", "bc"))
    }

    // ── round trip ──────────────────────────────────────────────────────

    @Test
    fun emptyDiskReadsAsNothingPending() =
        runTest {
            assertEquals(SduiPendingRead.None, store(testScheduler).read())
        }

    @Test
    fun committedRecordIsReadBackAndHoldsOnlyTheIdAndTheRelease() =
        runTest {
            assertTrue(store(testScheduler).commit(pending))
            assertEquals(setOf("operation_id", "release_id"), disk.data.keys)
            assertEquals(SduiPendingRead.Found(pending), store(testScheduler).read())
        }

    @Test
    fun clearRemovesTheRecord() =
        runTest {
            store(testScheduler).commit(pending)
            assertTrue(store(testScheduler).clear())
            assertEquals(SduiPendingRead.None, store(testScheduler).read())
        }

    // ── invalid records ─────────────────────────────────────────────────

    @Test
    fun anIdWithoutItsReleaseIsInvalidNotNothing() =
        runTest {
            disk.data["operation_id"] = pending.operationId
            assertEquals(SduiPendingRead.Invalid, store(testScheduler).read())

            disk.data.clear()
            disk.data["release_id"] = "bundled-1"
            assertEquals(SduiPendingRead.Invalid, store(testScheduler).read())
        }

    @Test
    fun blankOversizeOrControlValuesAreInvalid() =
        runTest {
            val tooLong = "x".repeat(SduiContract.MAX_ID + 1)
            listOf("", "  ", "has space", "tab\tid", "line\nbreak", tooLong).forEach { bad ->
                disk.data.clear()
                disk.data["operation_id"] = bad
                disk.data["release_id"] = "bundled-1"
                assertEquals(bad, SduiPendingRead.Invalid, store(testScheduler).read())

                disk.data["operation_id"] = pending.operationId
                disk.data["release_id"] = bad
                assertEquals(bad, SduiPendingRead.Invalid, store(testScheduler).read())
            }
        }

    @Test
    fun aValueOfTheWrongTypeIsInvalid() =
        runTest {
            val prefs = mockk<SharedPreferences>()
            every { prefs.getString(any(), any()) } throws ClassCastException("Integer cannot be cast to String")
            assertEquals(SduiPendingRead.Invalid, store(testScheduler, prefs).read())
        }

    // ── disk errors ─────────────────────────────────────────────────────

    @Test
    fun readFailureIsUnreadableAndCarriesNoExceptionText() =
        runTest {
            disk.failure = IllegalStateException("/data/secret-path token=abc")
            val result = store(testScheduler).read()
            assertEquals(SduiPendingRead.Unreadable, result)
            assertFalse("secret" in result.toString())
        }

    @Test
    fun aFailedCommitReportsFalseAndLeavesNothingBehind() =
        runTest {
            disk.commitResult = false
            assertFalse(store(testScheduler).commit(pending))
            assertTrue(disk.data.isEmpty())
            assertEquals(SduiPendingRead.None, store(testScheduler).read())
        }

    @Test
    fun aThrowingDiskIsAFailedCommitAndAFailedClear() =
        runTest {
            disk.failure = IllegalStateException("disk full: secret")
            assertFalse(store(testScheduler).commit(pending))
            assertFalse(store(testScheduler).clear())
        }
}
