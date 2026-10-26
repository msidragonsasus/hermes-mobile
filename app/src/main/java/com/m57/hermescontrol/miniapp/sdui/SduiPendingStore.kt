package com.m57.hermescontrol.miniapp.sdui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * The only thing kept across process death for the one write: which operation id was sent, and which
 * release it NAMED. No screen, name, URL, token or credential is ever part of it.
 */
internal data class SduiPendingCheck(
    val operationId: String,
    val releaseId: String,
)

internal sealed interface SduiPendingRead {
    /** Nothing is unresolved for this connection, profile and company. */
    data object None : SduiPendingRead

    data class Found(
        val pending: SduiPendingCheck,
    ) : SduiPendingRead

    /** A record exists but is not a complete, valid pair. It must not become a new write. */
    data object Invalid : SduiPendingRead

    /** The record could not be read, so whether one exists is unknown. */
    data object Unreadable : SduiPendingRead
}

/**
 * Durable record of the unresolved operation, already scoped to one captured connection, profile and
 * company by whoever built it. Failures are reported as results; nothing here throws or carries
 * exception text.
 */
internal interface SduiPendingStore {
    suspend fun read(): SduiPendingRead

    /** True only once the record is durably written. The first request must not be sent otherwise. */
    suspend fun commit(pending: SduiPendingCheck): Boolean

    /** True once nothing is recorded any more. */
    suspend fun clear(): Boolean
}

/** Ids are opaque, but never blank, over-long, or containing whitespace or control characters. */
internal fun isPlausibleSduiId(value: String): Boolean =
    value.isNotEmpty() &&
        value.length <= SduiContract.MAX_ID &&
        value.none { it.isWhitespace() || it.isISOControl() }

/**
 * Stable name for one connection + profile + company. Hashed, so the file name carries no host or
 * company id, and length-prefixed, so no two different scopes can concatenate to the same input.
 */
internal fun sduiPendingScopeKey(
    scope: String,
    companyId: String,
): String {
    val material = "${scope.length}:$scope|${companyId.length}:$companyId"
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { "%02x".format(it) }
}

/** [SduiPendingStore] on a private SharedPreferences file of its own per scope. */
internal class SharedPreferencesSduiPendingStore(
    private val open: () -> SharedPreferences,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SduiPendingStore {
    override suspend fun read(): SduiPendingRead =
        withContext(io) {
            try {
                val prefs = open()
                val operation = prefs.getString(KEY_OPERATION, null)
                val release = prefs.getString(KEY_RELEASE, null)
                when {
                    operation == null && release == null -> {
                        SduiPendingRead.None
                    }

                    // A UUID without its release cannot be resent as the same request.
                    operation != null &&
                        release != null &&
                        isPlausibleSduiId(operation) &&
                        isPlausibleSduiId(release) -> {
                        SduiPendingRead.Found(SduiPendingCheck(operation, release))
                    }

                    else -> {
                        SduiPendingRead.Invalid
                    }
                }
            } catch (_: ClassCastException) {
                SduiPendingRead.Invalid
            } catch (_: Exception) {
                SduiPendingRead.Unreadable
            }
        }

    override suspend fun commit(pending: SduiPendingCheck): Boolean =
        withContext(io) {
            val written =
                try {
                    // commit(), not apply(): the caller sends only after the bytes are on disk.
                    open()
                        .edit()
                        .putString(KEY_OPERATION, pending.operationId)
                        .putString(KEY_RELEASE, pending.releaseId)
                        .commit()
                } catch (_: Exception) {
                    false
                }
            if (!written) {
                // A half-written record must not be mistaken for a sent request later.
                try {
                    open().edit().clear().commit()
                } catch (_: Exception) {
                    // Nothing more can be done; the caller does not send.
                }
            }
            written
        }

    override suspend fun clear(): Boolean =
        withContext(io) {
            try {
                open().edit().clear().commit()
            } catch (_: Exception) {
                false
            }
        }

    companion object {
        private const val KEY_OPERATION = "operation_id"
        private const val KEY_RELEASE = "release_id"

        fun forScope(
            context: Context,
            scope: String,
            companyId: String,
        ): SharedPreferencesSduiPendingStore {
            val app = context.applicationContext
            val name = "sdui_pending_" + sduiPendingScopeKey(scope, companyId)
            return SharedPreferencesSduiPendingStore({ app.getSharedPreferences(name, Context.MODE_PRIVATE) })
        }
    }
}
