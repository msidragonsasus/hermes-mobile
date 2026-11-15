package com.m57.hermescontrol.worker

import android.content.Context
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.StorageStartup
import com.m57.hermescontrol.data.local.StorageStartupState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.ZoneId

@Serializable
internal data class TodayInboxCache(
    val date: String,
    val sinceSeq: Long = 0L,
    val items: List<InboxItem> = emptyList(),
)

internal object WorkerPreferences {
    private const val KEY_INBOX_SEQ = "worker_inbox_seq"
    private const val KEY_TODAY_CACHE = "worker_today_inbox_cache"
    private const val KEY_PUSH_TOKEN = "worker_push_token"
    private val tokyoZone = ZoneId.of("Asia/Tokyo")

    fun readLastInboxSeq(scope: String = AuthManager.uiPreferenceScope()): Long =
        AuthManager.readUiPreference(scope, KEY_INBOX_SEQ)?.toLongOrNull() ?: 0L

    fun advanceInboxSeq(
        seq: Long,
        scope: String = AuthManager.uiPreferenceScope(),
    ) {
        if (seq <= 0L) return
        val current = readLastInboxSeq(scope)
        if (seq > current) {
            runCatching {
                AuthManager.writeUiPreference(scope, KEY_INBOX_SEQ, seq.toString())
            }
        }
    }

    fun readCachedPushToken(): String? =
        runCatching { AuthManager.readUiPreference("worker_global", KEY_PUSH_TOKEN) }.getOrNull()

    fun saveCachedPushToken(token: String?) {
        runCatching {
            AuthManager.writeUiPreference("worker_global", KEY_PUSH_TOKEN, token)
        }
    }

    fun readTodayCache(
        scope: String = AuthManager.uiPreferenceScope(),
        today: LocalDate = LocalDate.now(tokyoZone),
    ): TodayInboxCache {
        val raw = AuthManager.readUiPreference(scope, KEY_TODAY_CACHE)
        val decoded =
            raw?.let {
                runCatching { WorkerJson.codec.decodeFromString(TodayInboxCache.serializer(), it) }.getOrNull()
            }
        val todayStr = today.toString()
        if (decoded == null || decoded.date != todayStr) {
            return TodayInboxCache(date = todayStr, sinceSeq = decoded?.sinceSeq ?: 0L, items = emptyList())
        }
        return decoded
    }

    fun writeTodayCache(
        cache: TodayInboxCache,
        scope: String = AuthManager.uiPreferenceScope(),
    ) {
        runCatching {
            AuthManager.writeUiPreference(
                scope,
                KEY_TODAY_CACHE,
                WorkerJson.codec.encodeToString(cache),
            )
        }
    }
}

/**
 * Ensures [StorageStartup] has finished initializing [AuthManager] and encrypted storage
 * when woken in a background worker, FCM service, or broadcast receiver.
 */
internal object WorkerAuthBootstrap {
    suspend fun ensureReady(context: Context): Boolean {
        val current = StorageStartup.state.value
        if (current is StorageStartupState.Ready) return true
        if (current is StorageStartupState.Blocked) return false
        // Wait for HermesControlApp's StorageStartup.start() if already running, or start it cleanly.
        val settled =
            withTimeoutOrNull(5_000L) {
                StorageStartup.state.first { it !is StorageStartupState.Loading }
            }
        if (settled is StorageStartupState.Ready) return true
        if (settled is StorageStartupState.Blocked) return false
        return runCatching {
            AuthManager.init(context.applicationContext)
            true
        }.getOrDefault(false)
    }

    fun isAuthenticated(): Boolean = AuthManager.isGatedMode() || !AuthManager.getToken().isNullOrBlank()
}
