package com.m57.hermescontrol.data.local

import android.content.ContextWrapper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.sqlite.execSQL
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** PR03: real SQLCipher files + the production Room migration builder, synthetic data only. */
class DatabasePreservationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val key = ByteArray(32) { (it + 1).toByte() }

    private fun fixture(version: Int): File {
        System.loadLibrary("sqlcipher")
        val file = File(context.cacheDir, "preservation-${UUID.randomUUID()}.db")
        val schema =
            Json
                .parseToJsonElement(
                    instrumentation.context.assets
                        .open(
                            "schemas/$version.json",
                        ).bufferedReader(Charsets.UTF_8)
                        .use { it.readText() },
                ).jsonObject
                .getValue("database")
                .jsonObject
        SQLCipherDriver(key, null, null).open(file.path).use { connection ->
            for (entity in schema.getValue("entities").jsonArray) {
                val table = entity.jsonObject
                connection.execSQL(
                    table.getValue("createSql").jsonPrimitive.content.replace(
                        "\${TABLE_NAME}",
                        table.getValue("tableName").jsonPrimitive.content,
                    ),
                )
                // Schema v2 predates the index and omits this optional export field.
                for (index in table["indices"]?.jsonArray.orEmpty()) {
                    connection.execSQL(
                        index.jsonObject.getValue("createSql").jsonPrimitive.content.replace(
                            "\${TABLE_NAME}",
                            table.getValue("tableName").jsonPrimitive.content,
                        ),
                    )
                }
            }
            for (query in schema.getValue("setupQueries").jsonArray) {
                connection.execSQL(query.jsonPrimitive.content)
            }
            connection.execSQL("PRAGMA user_version = $version")
            val extraColumns =
                (if (version >= 4) ",reasoning_text" else "") +
                    (if (version >= 5) ",tool_call_id" else "") +
                    (if (version >= 6) ",display_kind" else "")
            val extraValues =
                (if (version >= 4) ",'SYNTHETIC_PRIVATE_reasoning'" else "") +
                    (if (version >= 5) ",'call_synthetic'" else "") +
                    (if (version >= 6) ",'timeline_marker'" else "")
            connection.execSQL(
                "INSERT INTO chat_messages " +
                    "(id,session_id,role,content,timestamp,tool_name,tool_status,is_streaming$extraColumns) " +
                    "VALUES ('row','session','assistant','SYNTHETIC_PRIVATE_本文',123456,'tool','done',0$extraValues)",
            )
        }
        return file
    }

    @Test
    fun versionsTwoThroughSixPreserveExistingRows() =
        runBlocking {
            for (version in 2..6) {
                val file = fixture(version)
                val database = HermesDatabase.build(context, file.path, SQLCipherDriver(key, null, null))
                try {
                    val row = database.chatMessageDao().getMessagesForSession("session").single()
                    assertEquals("row", row.id)
                    assertEquals("SYNTHETIC_PRIVATE_本文", row.content)
                    assertEquals(123456L, row.timestamp)
                    assertEquals("tool", row.toolName)
                    assertEquals("done", row.toolStatus)
                    assertEquals(if (version >= 4) "SYNTHETIC_PRIVATE_reasoning" else "", row.reasoningText)
                    assertEquals(if (version >= 5) "call_synthetic" else "", row.toolCallId)
                    assertEquals(if (version >= 6) "timeline_marker" else null, row.displayKind)
                } finally {
                    database.close()
                }
            }
        }

    @Test
    fun wrongKeyAndUnknownVersionStopWithoutReplacingData() {
        for (unknownVersion in listOf(false, true)) {
            val file = fixture(6)
            if (unknownVersion) {
                SQLCipherDriver(key, null, null).open(file.path).use { it.execSQL("PRAGMA user_version = 99") }
            }
            val original = file.readBytes()
            val candidateKey = if (unknownVersion) key else ByteArray(32) { 99 }
            assertThrows(Exception::class.java) {
                val database = HermesDatabase.build(context, file.path, SQLCipherDriver(candidateKey, null, null))
                try {
                    runBlocking { database.chatMessageDao().getMessagesForSession("session") }
                } finally {
                    database.close()
                }
            }
            assertArrayEquals(original, file.readBytes())
            SQLCipherDriver(key, null, null).open(file.path).use { connection ->
                connection.prepare("SELECT content FROM chat_messages WHERE id = 'row'").use {
                    it.step()
                    assertEquals("SYNTHETIC_PRIVATE_本文", it.getText(0))
                }
            }
        }
    }

    @Test
    fun plaintextStorageBlocksStartupAndShowsPreservationMessage() =
        runBlocking {
            withTimeout(10_000) { StorageStartup.state.first { it != StorageStartupState.Loading } }
            val file = File(context.cacheDir, "plaintext-${UUID.randomUUID()}.db")
            val original = "SQLite format 3\u0000SYNTHETIC_PRIVATE_saved_history".toByteArray(Charsets.UTF_8)
            file.writeBytes(original)
            val fixtureContext =
                object : ContextWrapper(context) {
                    override fun getDatabasePath(name: String): File = file
                }
            val started = AtomicBoolean(false)
            StorageStartup.start(fixtureContext) { started.set(true) }
            val blocked = withTimeout(10_000) { StorageStartup.state.first { it is StorageStartupState.Blocked } }
            assertEquals(StorageFailure.PLAINTEXT_DATABASE, (blocked as StorageStartupState.Blocked).reason)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText(context.getString(R.string.storage_startup_blocked)).assertIsDisplayed()
            }
            assertFalse(started.get())
            assertArrayEquals(original, file.readBytes())
        }
}
