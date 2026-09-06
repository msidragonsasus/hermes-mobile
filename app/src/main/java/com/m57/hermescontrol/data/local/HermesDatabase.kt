package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

@Database(
    entities = [ChatMessageEntity::class, OutgoingDraftEntity::class],
    version = 7,
    exportSchema = true,
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao

    abstract fun outgoingDraftDao(): OutgoingDraftDao

    companion object {
        @Volatile
        private var instance: HermesDatabase? = null

        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id_timestamp` " +
                            "ON `chat_messages` (`session_id`, `timestamp`)",
                    )
                }
            }

        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `reasoning_text` TEXT NOT NULL DEFAULT ''",
                    )
                }
            }

        val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // Issue #842: tool rows now carry the gateway's
                    // tool call id (`call_00_...`) so REST transcript
                    // rows can be matched 1:1 against their live WS
                    // bubbles instead of fragile result-content
                    // canonicalization.
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `tool_call_id` TEXT NOT NULL DEFAULT ''",
                    )
                }
            }

        val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // Issue #904: timeline markers (display_kind) keep
                    // their tag through the Room cache so a cached
                    // marker never degrades back into a user bubble.
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `display_kind` TEXT",
                    )
                }
            }

        val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `outgoing_drafts` " +
                            "(`id` TEXT NOT NULL, `target_key` TEXT NOT NULL, " +
                            "`created_at` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))",
                    )
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_outgoing_drafts_target_key_created_at` " +
                            "ON `outgoing_drafts` (`target_key`, `created_at`)",
                    )
                }
            }

        fun get(context: Context): HermesDatabase =
            instance ?: synchronized(this) {
                instance?.let { return@synchronized it }
                // PR03 / R06: refuse unsupported storage without deleting the original.
                val dbFile = context.getDatabasePath("hermes_control.db")
                val existingDatabase = DatabaseProtection.inspect(dbFile)

                // Load SQLCipher native library before creating the driver
                System.loadLibrary("sqlcipher")
                val driver =
                    SQLCipherDriver(
                        AuthManager.getDatabasePassword(existingDatabase),
                        null,
                        null,
                    )

                build(context, "hermes_control.db", driver).also { instance = it }
            }

        internal fun build(
            context: Context,
            name: String,
            driver: SQLCipherDriver,
        ): HermesDatabase {
            // Reject unsupported versions before Room can change journal mode or migrate.
            val file = context.getDatabasePath(name)
            if (DatabaseProtection.inspect(file)) {
                driver.open(file.path).use { connection ->
                    connection.prepare("PRAGMA user_version").use { statement ->
                        if (!statement.step() || statement.getLong(0) !in 2L..7L) {
                            throw StorageUnavailableException(StorageFailure.OPEN_OR_MIGRATION_FAILED)
                        }
                    }
                }
            }
            return Room
                .databaseBuilder(
                    context.applicationContext,
                    HermesDatabase::class.java,
                    name,
                ).setDriver(driver)
                .addMigrations(
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                ).build()
        }

        /** For testing — inject a custom instance. */
        fun setForTest(db: HermesDatabase?) {
            instance = db
        }
    }
}
