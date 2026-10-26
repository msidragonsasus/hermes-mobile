package com.m57.hermescontrol.data.local

import android.os.Bundle
import androidx.sqlite.SQLiteConnection
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Compares operator-staged encrypted backup copies; never opens the live database. */
class InstalledDatabasePreservationReadTest {
    @Test
    fun stagedBackupRowsAreIdentical() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "google-connect-preservation")
        assumeTrue(
            "Operator-staged backups are not present",
            listOf("before", "after").all { label ->
                File(root, "$label/hermes_control.db").let { it.isFile && it.length() > 0 }
            },
        )
        AuthManager.init(context)
        System.loadLibrary("sqlcipher")
        val key = AuthManager.getDatabasePassword(existingDatabase = true)
        try {
            val snapshots =
                listOf("before", "after").map { label ->
                    val file = File(root, "$label/hermes_control.db")
                    check(file.isFile && file.length() > 0)
                    SQLCipherDriver(key.copyOf(), null, null).open(file.path).use(::snapshot)
                }
            assertTrue("Staged backup contents differ", snapshots[0] == snapshots[1])
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("installed_database_all_rows_identical", "true")
                    putString("installed_database_integrity", "ok")
                    putString("database_key_exported", "false")
                },
            )
        } finally {
            key.fill(0)
        }
    }

    private fun snapshot(connection: SQLiteConnection): List<List<String>> {
        connection.prepare("PRAGMA quick_check").use {
            check(it.step() && it.getText(0) == "ok")
        }
        val tables = mutableListOf<String>()
        connection.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use {
            while (it.step()) tables.add(it.getText(0))
        }
        return tables.map { table ->
            val rows = mutableListOf<String>()
            connection.prepare("SELECT * FROM \"${table.replace("\"", "\"\"")}\"").use { statement ->
                while (statement.step()) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    for (column in 0 until statement.getColumnCount()) {
                        val type = statement.getColumnType(column)
                        val bytes =
                            when (type) {
                                1 -> {
                                    statement.getLong(column).toString().toByteArray()
                                }

                                2 -> {
                                    statement
                                        .getDouble(column)
                                        .toBits()
                                        .toString()
                                        .toByteArray()
                                }

                                3 -> {
                                    statement.getText(column).toByteArray(Charsets.UTF_8)
                                }

                                4 -> {
                                    statement.getBlob(column)
                                }

                                // SQLCipher 4.18 delegates getColumnType to Android Cursor.
                                0 -> {
                                    byteArrayOf()
                                }

                                else -> {
                                    error("Unexpected SQLite type")
                                }
                            }
                        digest.update("$type:${bytes.size}:".toByteArray())
                        digest.update(bytes)
                    }
                    rows.add(digest.digest().joinToString("") { "%02x".format(it) })
                }
            }
            listOf(table) + rows.sorted()
        }
    }
}
