package com.m57.hermescontrol.home

import android.content.Context
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

fun homeVersion(value: String): String =
    MessageDigest
        .getInstance(
            "SHA-256",
        ).digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

class HomeRepository(
    context: Context,
    private val sessionTitle: (String) -> String? = { null },
) {
    val dao = HermesDatabase.get(context).homeDao()
    val json = Json { ignoreUnknownKeys = true }
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    suspend fun markRead(
        scope: String,
        item: HomeItem,
    ) {
        if (scope != AuthManager.uiPreferenceScope() || item.category != "result") return
        try {
            dao.saveRead(HomeRead(scope, item.provider + ":" + item.id, item.version))
            mutableError.value = null
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableError.value = "既読を保存できませんでした。結果はホームに残しています。"
        }
    }

    suspend fun markContentRead(
        scope: String,
        id: String,
        content: String,
    ) {
        if (scope != AuthManager.uiPreferenceScope() || content.isBlank()) return
        try {
            dao.saveRead(HomeRead(scope, "career:$id", "content:" + homeVersion(content)))
            mutableError.value = null
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableError.value = "既読を保存できませんでした。結果はホームに残しています。"
        }
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            HermesWsClient.scopedEvents.collect { envelope ->
                val event = envelope.event as? WsEvent.MessageComplete ?: return@collect
                val session = event.storedSessionId?.takeIf { it.isNotBlank() } ?: return@collect
                if (!HermesWsClient.matchesCurrentScope(envelope.scope) || event.text.isBlank()) return@collect
                val destination = AuthManager.uiPreferenceScope()
                val item =
                    HomeItem(
                        id = session,
                        version = homeVersion(event.text),
                        provider = "chat",
                        category = "result",
                        title = sessionTitle(session)?.let { "$it：回答が届きました" } ?: "会話に回答が届きました",
                        target = HomeTarget(kind = "chat", session_id = session),
                        updated_at = Instant.now().toString(),
                    )
                try {
                    val old =
                        dao
                            .item(
                                destination,
                                "chat",
                                session,
                            )?.let { json.decodeFromString<HomeItem>(it.payload) }
                    if (old?.version != item.version) {
                        dao.save(HomeRow(destination, "chat", session, json.encodeToString(item)))
                    }
                    mutableError.value = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutableError.value = "回答のホーム保存に失敗しました。元の会話で確認してください。"
                }
            }
        }
    }
}
