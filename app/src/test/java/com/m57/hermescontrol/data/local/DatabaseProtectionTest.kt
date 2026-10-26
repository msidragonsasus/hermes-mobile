package com.m57.hermescontrol.data.local

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class DatabaseProtectionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `plaintext and partial databases are preserved byte for byte`() {
        for ((bytes, reason) in listOf(
            "SQLite format 3\u0000SYNTHETIC_PRIVATE_history".toByteArray() to StorageFailure.PLAINTEXT_DATABASE,
            "partial".toByteArray() to StorageFailure.INCOMPLETE_DATABASE,
        )) {
            val file = temporary.newFile()
            file.writeBytes(bytes)
            assertEquals(
                reason,
                assertThrows(StorageUnavailableException::class.java) { DatabaseProtection.inspect(file) }.reason,
            )
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun `missing database with surviving journal is never replaced`() {
        val file = File(temporary.root, "missing.db")
        val journal = File(file.path + "-wal")
        val bytes = "SYNTHETIC_PRIVATE_journal".toByteArray()
        journal.writeBytes(bytes)
        assertEquals(
            StorageFailure.INCOMPLETE_DATABASE,
            assertThrows(StorageUnavailableException::class.java) { DatabaseProtection.inspect(file) }.reason,
        )
        assertFalse(file.exists())
        assertArrayEquals(bytes, journal.readBytes())
    }

    @Test
    fun `new database and encrypted candidate are distinguished without writes`() {
        val file = File(temporary.root, "new.db")
        assertFalse(DatabaseProtection.inspect(file))
        assertFalse(file.exists())
        val bytes = ByteArray(128) { it.toByte() }
        file.writeBytes(bytes)
        assertTrue(DatabaseProtection.inspect(file))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `non-file is blocked without replacing it`() {
        val directory = temporary.newFolder()
        assertThrows(StorageUnavailableException::class.java) { DatabaseProtection.inspect(directory) }
        assertTrue(directory.isDirectory)
    }

    @Test
    fun `missing existing database key never edits preferences`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString("db_password", null) } returns null
        assertEquals(
            StorageFailure.MISSING_KEY,
            assertThrows(StorageUnavailableException::class.java) { DatabaseKeyStore.load(prefs, true) }.reason,
        )
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `invalid or unreadable key never generates a replacement`() {
        val prefs = mockk<SharedPreferences>()
        for (encoded in listOf("invalid!", Base64.getEncoder().encodeToString(ByteArray(4)))) {
            every { prefs.getString("db_password", null) } returns encoded
            assertEquals(
                StorageFailure.INVALID_KEY,
                assertThrows(StorageUnavailableException::class.java) { DatabaseKeyStore.load(prefs, true) }.reason,
            )
        }
        every { prefs.getString("db_password", null) } throws IllegalStateException("SYNTHETIC_PRIVATE_failure")
        assertEquals(
            StorageFailure.UNAVAILABLE_KEY,
            assertThrows(StorageUnavailableException::class.java) { DatabaseKeyStore.load(prefs, true) }.reason,
        )
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `existing key is returned unchanged and is not rewritten`() {
        val prefs = mockk<SharedPreferences>()
        val key = ByteArray(32) { it.toByte() }
        every { prefs.getString("db_password", null) } returns Base64.getEncoder().encodeToString(key)
        assertArrayEquals(key, DatabaseKeyStore.load(prefs, true))
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `new key must be persisted before it can open a database`() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        var saved: String? = null
        every { prefs.getString("db_password", null) } answers { saved }
        every { prefs.edit() } returns editor
        every { editor.putString("db_password", any()) } answers {
            saved = secondArg()
            editor
        }
        every { editor.commit() } returns true
        val key = DatabaseKeyStore.load(prefs, false)
        assertEquals(32, key.size)
        assertArrayEquals(Base64.getDecoder().decode(saved), key)
        assertArrayEquals(key, DatabaseKeyStore.load(prefs, true))
        verify(exactly = 1) { editor.commit() }
        verify(exactly = 0) { editor.apply() }
    }

    @Test
    fun `failed key persistence blocks database creation`() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getString("db_password", null) } returns null
        every { prefs.edit() } returns editor
        every { editor.putString("db_password", any()) } returns editor
        every { editor.commit() } returns false
        assertEquals(
            StorageFailure.KEY_SAVE_FAILED,
            assertThrows(StorageUnavailableException::class.java) { DatabaseKeyStore.load(prefs, false) }.reason,
        )
    }
}
