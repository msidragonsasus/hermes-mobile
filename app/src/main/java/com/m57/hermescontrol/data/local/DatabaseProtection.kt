package com.m57.hermescontrol.data.local

import android.content.SharedPreferences
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.Base64

enum class StorageFailure {
    UNREADABLE_DATABASE,
    PLAINTEXT_DATABASE,
    INCOMPLETE_DATABASE,
    MISSING_KEY,
    INVALID_KEY,
    UNAVAILABLE_KEY,
    KEY_SAVE_FAILED,
    OPEN_OR_MIGRATION_FAILED,
}

/** PR03 / R06: safe diagnostics contain no file paths, keys or exception details. */
class StorageUnavailableException(
    val reason: StorageFailure,
) : IllegalStateException(reason.name)

internal object DatabaseProtection {
    /** Returns whether a database exists; this check never deletes or rewrites any file. */
    fun inspect(file: File): Boolean {
        try {
            val attributes =
                try {
                    Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    // A surviving journal is not permission to create a replacement database.
                    for (suffix in listOf("-wal", "-shm", "-journal")) {
                        if (Files.exists(File(file.path + suffix).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            throw StorageUnavailableException(StorageFailure.INCOMPLETE_DATABASE)
                        }
                    }
                    return false
                }
            if (!attributes.isRegularFile || attributes.size() < 16) {
                throw StorageUnavailableException(StorageFailure.INCOMPLETE_DATABASE)
            }
            val header = ByteArray(16)
            DataInputStream(file.inputStream()).use { it.readFully(header) }
            if (header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.UTF_8))) {
                throw StorageUnavailableException(StorageFailure.PLAINTEXT_DATABASE)
            }
            // A non-plaintext header is only a candidate. SQLCipher + Room validate it on open.
            return true
        } catch (e: StorageUnavailableException) {
            throw e
        } catch (_: Exception) {
            throw StorageUnavailableException(StorageFailure.UNREADABLE_DATABASE)
        }
    }
}

internal object DatabaseKeyStore {
    @Synchronized
    fun load(
        prefs: SharedPreferences,
        existingDatabase: Boolean,
    ): ByteArray {
        val encoded =
            try {
                prefs.getString("db_password", null)
            } catch (_: Exception) {
                throw StorageUnavailableException(StorageFailure.UNAVAILABLE_KEY)
            }
        if (encoded != null) {
            val key =
                try {
                    Base64.getDecoder().decode(encoded)
                } catch (_: IllegalArgumentException) {
                    throw StorageUnavailableException(StorageFailure.INVALID_KEY)
                }
            if (key.size != 32) {
                key.fill(0)
                throw StorageUnavailableException(StorageFailure.INVALID_KEY)
            }
            return key
        }
        if (existingDatabase) throw StorageUnavailableException(StorageFailure.MISSING_KEY)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val saved =
            try {
                prefs.edit().putString("db_password", Base64.getEncoder().encodeToString(key)).commit()
            } catch (_: Exception) {
                false
            }
        if (!saved) {
            key.fill(0)
            throw StorageUnavailableException(StorageFailure.KEY_SAVE_FAILED)
        }
        return key
    }
}
