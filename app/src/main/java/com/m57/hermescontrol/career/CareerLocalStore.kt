package com.m57.hermescontrol.career

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class CareerPendingOperation(
    val action: String,
    val payload: JsonObject,
)

/** One explicit pending operation per server/profile. Never auto-flushed on reconnect. */
class CareerLocalStore(
    context: Context,
    private val scope: String,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            "hermes_career_private",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun uiValue(key: String): String? = withContext(Dispatchers.IO) { prefs.getString("$scope/ui/$key", null) }

    suspend fun saveUiValue(
        key: String,
        value: String?,
    ) = withContext(Dispatchers.IO) {
        check(prefs.edit().putString("$scope/ui/$key", value).commit()) { "端末に保存できません。入力は保持しています。" }
    }

    suspend fun hasPendingThread(threadId: String): Boolean =
        withContext(Dispatchers.IO) {
            val mainKey = if (threadId == "global") "$scope/coach" else "$scope/thread/$threadId/coach"
            val legacyPrefix =
                threadId
                    .removePrefix("offer:")
                    .takeIf {
                        threadId.startsWith("offer:")
                    }?.let { "$scope/es-conversation:$it:" }
            prefs.all.keys
                .filter {
                    it == mainKey ||
                        (legacyPrefix != null && it.startsWith(legacyPrefix) && it.endsWith("/coach"))
                }.any { key ->
                    prefs.getString(key, null)?.let { json.decodeFromString<CareerCoachCache>(it).pending != null } ==
                        true
                }
        }

    suspend fun legacyDraftThreads(): List<String> =
        withContext(Dispatchers.IO) {
            val prefix = "$scope/thread/"
            prefs.all.keys.filter { it.startsWith(prefix) && it.endsWith("/coach") }.mapNotNull { key ->
                val id = key.removePrefix(prefix).removeSuffix("/coach")
                if (!id.startsWith("company:") && !id.startsWith("offer:")) return@mapNotNull null
                val cache = prefs.getString(key, null)?.let { json.decodeFromString<CareerCoachCache>(it) }
                id.takeIf { cache?.draft?.isNotBlank() == true || cache?.pending != null }
            }
        }

    suspend fun pending(): CareerPendingOperation? =
        withContext(Dispatchers.IO) {
            prefs.getString("$scope/pending", null)?.let { json.decodeFromString<CareerPendingOperation>(it) }
        }

    suspend fun savePending(value: CareerPendingOperation?) =
        withContext(Dispatchers.IO) {
            val editor = prefs.edit()
            if (value ==
                null
            ) {
                editor.remove("$scope/pending")
            } else {
                editor.putString("$scope/pending", json.encodeToString(value))
            }
            check(editor.commit()) { "端末内に保存できません。操作は送信していません。" }
        }

    suspend fun cache(value: CareerSnapshot) =
        withContext(Dispatchers.IO) {
            check(prefs.edit().putString("$scope/cache", json.encodeToString(value)).commit()) { "キャッシュを保存できません。" }
        }

    suspend fun cached(): CareerSnapshot? =
        withContext(Dispatchers.IO) {
            prefs.getString("$scope/cache", null)?.let { json.decodeFromString<CareerSnapshot>(it) }
        }

    internal suspend fun importedLoginAccounts(): List<ShukatsuLoginAccount> =
        withContext(Dispatchers.IO) {
            prefs
                .getString("$scope/imported-login-accounts", null)
                ?.let { json.decodeFromString<List<ShukatsuLoginAccount>>(it) }
                .orEmpty()
        }

    internal suspend fun saveImportedLoginAccounts(value: List<ShukatsuLoginAccount>) =
        withContext(Dispatchers.IO) {
            check(
                prefs
                    .edit()
                    .putString("$scope/imported-login-accounts", json.encodeToString(value))
                    .commit(),
            ) { "マイページIDを端末内に保存できません。" }
        }

    internal suspend fun companyLoginAccounts(companyId: String): List<ShukatsuLoginAccount> =
        uiValue("company-logins/$companyId")?.let { json.decodeFromString<List<ShukatsuLoginAccount>>(it) }
            ?: importedLoginAccounts().filter { companyId == "shukatsu:Company:" + it.companySourceId }

    internal suspend fun saveCompanyLoginAccounts(
        companyId: String,
        accounts: List<ShukatsuLoginAccount>,
    ) {
        saveUiValue("company-logins/$companyId", json.encodeToString(accounts))
    }

    suspend fun enabled(): Boolean = withContext(Dispatchers.IO) { prefs.getBoolean("$scope/enabled", false) }

    suspend fun companyCache(id: String?): String? =
        withContext(Dispatchers.IO) {
            prefs.getString("$scope/company/${id ?: "list"}", null)
        }

    suspend fun saveCompanyCache(
        id: String?,
        value: String,
    ) = withContext(Dispatchers.IO) {
        check(prefs.edit().putString("$scope/company/${id ?: "list"}", value).commit()) { "企業情報を端末に保存できません。" }
    }

    suspend fun invalidateCompanyCache(id: String) =
        withContext(Dispatchers.IO) {
            check(
                prefs
                    .edit()
                    .remove("$scope/company/$id")
                    .remove("$scope/company/list")
                    .commit(),
            ) {
                "企業一覧の端末保存を更新できません。"
            }
        }

    suspend fun noticeCursor(): Long = withContext(Dispatchers.IO) { prefs.getLong("$scope/notice_cursor", 0) }

    suspend fun noticePolicyReady(): Boolean =
        withContext(Dispatchers.IO) {
            prefs.getBoolean("$scope/notice_policy_v2", false)
        }

    suspend fun noticeDeliveredCursor(company: String): Long =
        withContext(Dispatchers.IO) {
            prefs.getLong("$scope/notice-delivered/$company", 0)
        }

    suspend fun saveNoticeDeliveredCursor(
        company: String,
        cursor: Long,
    ) = withContext(Dispatchers.IO) {
        check(prefs.edit().putLong("$scope/notice-delivered/$company", cursor).commit())
    }

    suspend fun initializeNoticePolicy(cursor: Long) =
        withContext(Dispatchers.IO) {
            check(
                prefs
                    .edit()
                    .putLong("$scope/notice_cursor", cursor)
                    .putBoolean("$scope/notice_policy_v2", true)
                    .commit(),
            )
        }

    suspend fun noticeGroup(company: String): List<CareerNotice> =
        withContext(Dispatchers.IO) {
            prefs
                .getString(
                    "$scope/notice-group/$company",
                    null,
                )?.let { json.decodeFromString<List<CareerNotice>>(it) }
                .orEmpty()
        }

    suspend fun saveNoticeGroup(
        company: String,
        notices: List<CareerNotice>,
    ) = withContext(Dispatchers.IO) {
        check(prefs.edit().putString("$scope/notice-group/$company", json.encodeToString(notices)).commit())
    }

    suspend fun saveNoticeCursor(value: Long) =
        withContext(Dispatchers.IO) {
            check(prefs.edit().putLong("$scope/notice_cursor", value).commit())
        }

    suspend fun notificationsEnabled(): Boolean =
        withContext(Dispatchers.IO) {
            prefs.getBoolean("$scope/notifications", false)
        }

    suspend fun setNotificationsEnabled(value: Boolean) =
        withContext(Dispatchers.IO) {
            check(prefs.edit().putBoolean("$scope/notifications", value).commit())
        }

    suspend fun gmailAccountEmail(): String? =
        withContext(Dispatchers.IO) {
            prefs.getString("$scope/gmail-account-email", null)?.takeIf { it.isNotBlank() }
        }

    internal suspend fun gmailCheckpoint(key: String): CareerGmailCheckpoint =
        withContext(Dispatchers.IO) {
            prefs
                .getString("$scope/gmail-checkpoint/$key", null)
                ?.let { json.decodeFromString<CareerGmailCheckpoint>(it) } ?: CareerGmailCheckpoint()
        }

    internal suspend fun saveGmailCheckpoint(
        key: String,
        value: CareerGmailCheckpoint,
    ) = withContext(Dispatchers.IO) {
        check(prefs.edit().putString("$scope/gmail-checkpoint/$key", json.encodeToString(value)).commit()) {
            "再開位置を端末に保存できません。取得済みメールは保持しています。"
        }
    }

    suspend fun setGmailAccountEmail(value: String?) =
        withContext(Dispatchers.IO) {
            val editor = prefs.edit()
            if (value.isNullOrBlank()) {
                editor.remove("$scope/gmail-account-email")
            } else {
                editor.putString("$scope/gmail-account-email", value)
            }
            check(editor.commit()) { "Googleアカウント情報を端末内に保存できません。" }
        }

    suspend fun notificationRoute(notice: CareerNotice): String = notificationGroupRoute(listOf(notice))

    suspend fun notificationGroupRoute(notices: List<CareerNotice>): String =
        withContext(Dispatchers.IO) {
            val notice = notices.first()
            val key = "$scope/notice-route/company/${notice.company_id}"
            val token = prefs.getString(key, null) ?: UUID.randomUUID().toString()
            val route =
                buildJsonObject {
                    put("scope", scope)
                    put("company_id", notice.company_id)
                    put("notices", json.encodeToJsonElement(notices))
                    if (notices.size == 1) {
                        put("notice_id", notice.id)
                        put("kind", notice.source_ref?.kind.orEmpty())
                    }
                }
            check(
                prefs
                    .edit()
                    .putString(key, token)
                    .putString("notice-route:$token", route.toString())
                    .commit(),
            )
            token
        }

    suspend fun resolveNotificationDestination(token: String): JsonObject? =
        withContext(Dispatchers.IO) {
            if (!Regex("[a-f0-9-]{36}").matches(token)) return@withContext null
            prefs.getString("notice-route:$token", null)?.let { json.decodeFromString<JsonObject>(it) }?.takeIf {
                it.text("scope") ==
                    scope
            }
        }

    suspend fun resolveNotificationRoute(token: String): String? =
        withContext(Dispatchers.IO) {
            if (!Regex("[a-f0-9-]{36}").matches(token)) return@withContext null
            val route = prefs.getString("notice-route:$token", null)?.let { json.decodeFromString<JsonObject>(it) }
            route?.takeIf { it.text("scope") == scope }?.text("company_id")
        }

    suspend fun coach(): CareerCoachCache? =
        withContext(Dispatchers.IO) {
            prefs.getString("$scope/coach", null)?.let { json.decodeFromString<CareerCoachCache>(it) }
        }

    suspend fun saveCoach(value: CareerCoachCache) =
        withContext(Dispatchers.IO) {
            check(prefs.edit().putString("$scope/coach", json.encodeToString(value)).commit()) {
                "会話の操作を端末内に保存できません。"
            }
        }

    suspend fun setEnabled(value: Boolean) =
        withContext(Dispatchers.IO) {
            check(prefs.edit().putBoolean("$scope/enabled", value).commit())
        }

    suspend fun esCache(offerId: String): String? =
        withContext(Dispatchers.IO) {
            prefs.getString("$scope/es/$offerId", null)
        }

    suspend fun saveEsCache(
        offerId: String,
        value: String,
    ) = withContext(Dispatchers.IO) {
        check(prefs.edit().putString("$scope/es/$offerId", value).commit()) { "回答を端末に保存できません。" }
    }
}
