package com.m57.hermescontrol.career

import android.content.Context
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.util.Base64
import java.util.UUID

/** A local entry, never a server thread ID. The context stays fixed across retries. */
@Serializable
internal data class CareerNewConversation(
    val id: String = UUID.randomUUID().toString(),
    val companyId: String? = null,
    val offerId: String? = null,
    val handoffId: String? = null,
    val target: CareerTarget? = null,
) {
    val threadId: String get() = "draft:" + encode(Json.encodeToString(this))
    val registryKey: String get() = "new-conversation/" + encode(Json.encodeToString(copy(id = "")))

    companion object {
        private fun encode(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        fun fromThreadId(value: String): CareerNewConversation? =
            if (value.startsWith("draft:")) {
                runCatching {
                    Json.decodeFromString<CareerNewConversation>(
                        String(Base64.getUrlDecoder().decode(value.removePrefix("draft:")), Charsets.UTF_8),
                    )
                }.getOrNull()
            } else {
                null
            }
    }
}

@Serializable
internal data class CareerNewConversationReceipt(
    val operation: JsonObject,
    val thread: CareerThread? = null,
)

/** Only an index of recoverable local routes; removing an index never removes their data. */
internal object CareerNewConversationRecovery {
    private val mutex = Mutex()
    private const val KEY = "new-conversation-recovery"

    suspend fun entries(registry: CareerLocalStore): List<CareerNewConversation> =
        registry.uiValue(KEY)?.let { Json.decodeFromString(it) } ?: emptyList()

    suspend fun remember(
        registry: CareerLocalStore,
        entry: CareerNewConversation,
    ) = mutex.withLock {
        registry.saveUiValue(KEY, Json.encodeToString((entries(registry) + entry).distinctBy { it.id }))
    }

    suspend fun forget(
        registry: CareerLocalStore,
        entry: CareerNewConversation,
    ) = mutex.withLock {
        registry.saveUiValue(KEY, Json.encodeToString(entries(registry).filterNot { it.id == entry.id }))
    }
}

/** Lets the existing coach persist a first send under the real thread after creation. */
internal interface CareerCoachLocalRepository {
    suspend fun localStore(): CareerCoachStore

    suspend fun acknowledged() {}
}

/** Creating a screen only reads local state. A first explicit send creates the metadata. */
internal class CareerNewConversationRepository(
    private val entry: CareerNewConversation,
    private val profile: String,
    private val registry: CareerLocalStore,
    private val draftLocal: CareerLocalStore,
    private val threadLocal: (String) -> CareerLocalStore,
    private val repository: (String) -> CareerCoachRepository,
    private val create: suspend (JsonObject) -> CareerThread,
    private val scope: String? = null,
) : CareerCoachRepository,
    CareerCoachLocalRepository {
    private val mutex = Mutex()
    private var loaded = false
    private var receipt: CareerNewConversationReceipt? = null

    private suspend fun restore() {
        if (loaded) return
        receipt = draftLocal.uiValue("new-conversation-receipt")?.let { Json.decodeFromString(it) }
        loaded = true
    }

    override suspend fun localStore(): CareerCoachStore =
        mutex.withLock {
            restore()
            val id = receipt?.thread?.id
            CareerCoachStore(id?.let(threadLocal) ?: draftLocal, scope?.let { "$it/thread/${id ?: entry.threadId}" })
        }

    private suspend fun current(): CareerCoachRepository {
        val identity =
            mutex.withLock {
                restore()
                receipt?.thread?.id
            }
        return repository(identity ?: "global")
    }

    override suspend fun capabilities(refresh: Boolean): CareerCapabilities = current().capabilities(refresh)

    override suspend fun dailySettings(): CareerDailySettings = current().dailySettings()

    override suspend fun conversation(before: Long?): CareerCoachSnapshot {
        val identity =
            mutex.withLock {
                restore()
                receipt?.thread?.id
            }
        return identity?.let { repository(it).conversation(before) }
            ?: CareerCoachSnapshot(profile = profile, thread_id = entry.threadId)
    }

    override suspend fun events(after: Long): CareerCoachEvents {
        val identity =
            mutex.withLock {
                restore()
                receipt?.thread?.id
            }
        return identity?.let { repository(it).events(after) } ?: CareerCoachEvents(thread_id = entry.threadId)
    }

    override suspend fun mutate(
        action: String,
        payload: JsonObject,
    ): JsonObject {
        if (action == "daily-settings") return repository("global").mutate(action, payload)
        val identity =
            mutex.withLock {
                restore()
                if (receipt?.thread == null) {
                    check(action in setOf("message", "research")) { "相談の本文を送ってから操作してください。" }
                    check(!payload.text("text").isNullOrBlank()) { "相談したい内容を入力してください。" }
                    val capabilities = repository("global").capabilities()
                    check(
                        "conversation_threads" in capabilities.features && "company_threads" in capabilities.features,
                    ) {
                        "新しい相談に対応するサーバーへ更新・再接続してください。入力は保持しています。"
                    }
                    if (receipt == null) {
                        check(registry.uiValue(entry.registryKey) == Json.encodeToString(entry)) {
                            "この下書きは別の接続先で作成されました。相談一覧から開き直してください。入力は保持しています。"
                        }
                        val operation =
                            buildJsonObject {
                                put("operation_id", UUID.randomUUID().toString())
                                put("title", careerConversationTitle(payload.text("text").orEmpty()))
                                entry.companyId?.let { put("company_id", it) }
                                entry.offerId?.let { put("offer_id", it) }
                            }
                        val pending = CareerNewConversationReceipt(operation)
                        draftLocal.saveUiValue("new-conversation-receipt", Json.encodeToString(pending))
                        receipt = pending
                    }
                    val pending = checkNotNull(receipt)
                    val thread =
                        try {
                            create(pending.operation)
                        } catch (failure: Exception) {
                            if (failure is HttpException && failure.code() in setOf(400, 404, 410, 422)) {
                                retainRecoveryAndReleaseRegistry()
                            }
                            throw failure
                        }
                    if (thread.archived) {
                        retainRecoveryAndReleaseRegistry()
                        error("この相談はアーカイブ済みです。一覧で復元してから再送してください。入力は保持しています。")
                    }
                    val related =
                        thread.id.startsWith("thread:") &&
                            (entry.companyId == null || thread.company_id == entry.companyId) &&
                            (entry.offerId == null || thread.offer_id == entry.offerId) &&
                            (entry.offerId != null || thread.offer_id == null) &&
                            (entry.companyId != null || entry.offerId != null || thread.company_id == null)
                    if (!related) {
                        retainRecoveryAndReleaseRegistry()
                        error("作成された相談の関連先を確認できません。入力は端末の下書きに保持しています。")
                    }
                    // The existing VM persisted the exact first-send operation before reaching this point.
                    // Copy it before publishing the receipt, so opening the real thread can recover it too.
                    val cache = checkNotNull(draftLocal.coach()) { "送信内容を端末から読めません。送信は保留しています。" }
                    val target = threadLocal(thread.id)
                    val storageKey = scope?.let { "$it/thread/${thread.id}" }
                    CareerCoachStorage.locked(storageKey) {
                        val existing = target.coach()
                        val vacant =
                            existing == null ||
                                (
                                    existing.pending == null && existing.draft.isBlank() &&
                                        existing.revisionDrafts.isEmpty() && existing.target == null &&
                                        existing.answerContext == null
                                )
                        val sameRequest =
                            existing != null && existing.pending != null &&
                                existing.pending == cache.pending && existing.draft == cache.draft &&
                                existing.target == cache.target && existing.answerContext == cache.answerContext &&
                                existing.revisionDrafts == cache.revisionDrafts
                        if (!((!CareerCoachStorage.hasEdits(storageKey) && vacant) || sameRequest)) {
                            // Keep both caches and the uncertain receipt. Only release the starter slot,
                            // so a later New action cannot trap the user in this conflicted draft.
                            retainRecoveryAndReleaseRegistry()
                            error("作成済みの相談に別の下書きがあります。一覧からそちらを確認してください。今回の入力も保持しています。")
                        }
                        target.saveCoach(cache.copy(snapshot = existing?.snapshot))
                        CareerCoachStorage.migrated(storageKey)
                    }
                    val resolved = pending.copy(thread = thread)
                    draftLocal.saveUiValue("new-conversation-receipt", Json.encodeToString(resolved))
                    receipt = resolved
                }
                checkNotNull(receipt?.thread).id
            }
        return repository(identity).mutate(action, payload)
    }

    override suspend fun acknowledged() {
        complete()
    }

    private suspend fun releaseRegistry() {
        if (registry.uiValue(entry.registryKey) == Json.encodeToString(entry)) {
            registry.saveUiValue(entry.registryKey, null)
        }
    }

    private suspend fun retainRecoveryAndReleaseRegistry() {
        CareerNewConversationRecovery.remember(registry, entry)
        releaseRegistry()
    }

    /** Retain the receipt for old routes, but the next new consultation gets a fresh draft. */
    suspend fun complete(): CareerThread? =
        mutex.withLock {
            restore()
            val thread = receipt?.thread ?: return@withLock null
            releaseRegistry()
            CareerNewConversationRecovery.forget(registry, entry)
            thread
        }
}

internal fun careerNewConversationRepository(
    entry: CareerNewConversation,
    context: Context,
    scope: String,
): CareerNewConversationRepository {
    val profile = AuthManager.activeProfileId.value ?: "default"
    val api = ApiClient.hermesApi
    return CareerNewConversationRepository(
        entry,
        profile,
        CareerLocalStore(context, scope),
        CareerLocalStore(context, "$scope/thread/${entry.threadId}"),
        { identity -> CareerLocalStore(context, "$scope/thread/$identity") },
        { identity -> DashboardCareerCoachRepository(scope, identity) },
        { payload ->
            check(scope == careerScope() && AuthManager.isGatedMode()) { "接続先を確認してから開き直してください。" }
            api.careerCreateThread(profile, payload).also {
                check(scope == careerScope() && AuthManager.isGatedMode()) { "接続先が変わりました。開き直してください。" }
            }
        },
        scope = scope,
    )
}
