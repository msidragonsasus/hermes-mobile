package com.m57.hermescontrol.ui.bots.group

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant

/** A deliberately small projection. Never serialize the conversation/archive for diagnostics. */
internal data class GroupSendDiagnostic(
    val bot: String,
    val runtimeId: String?,
    val storedId: String?,
    val delivery: GroupDelivery,
    val messageRecordedAt: String?,
    val endpoint: GroupDiagnosticEndpoint?,
) {
    fun copyText(): String =
        Json { prettyPrint = true }.encodeToString(
            buildJsonObject {
                put("schema", 1)
                put("bot", bot)
                put("runtime_session_id", runtimeId ?: NOT_RECORDED)
                put("stored_session_id", storedId ?: NOT_RECORDED)
                put("delivery", delivery.name)
                put("message_recorded_at_utc", messageRecordedAt ?: NOT_RECORDED)
                // v31 did not journal these. Message time/delivery must not impersonate an ACK.
                put("turn_saved_at", NOT_RECORDED)
                put("recorded_submit_ack", NOT_RECORDED)
                put("endpoint_provenance", "archive_configuration_not_captured_request")
                if (endpoint == null) {
                    put("endpoint", NOT_RECORDED)
                } else {
                    put(
                        "endpoint",
                        buildJsonObject {
                            put("host", endpoint.host)
                            put("port", endpoint.port)
                            put("base_path", endpoint.path ?: NOT_RECORDED)
                        },
                    )
                }
            },
        )

    private companion object {
        const val NOT_RECORDED = "not_recorded"
    }
}

internal data class GroupDiagnosticEndpoint(
    val host: String,
    val port: Int,
    val path: String?,
)

internal data class GroupDiagnosticChoice(
    val turnId: String,
    val diagnostic: GroupSendDiagnostic,
)

internal data class GroupDiagnosticSnapshot(
    val scopeTag: String,
    val choices: List<GroupDiagnosticChoice>,
)

/** Only URL components can escape. Query, fragment, userinfo and arbitrary raw errors never do. */
internal fun groupDiagnosticEndpoint(raw: String?): GroupDiagnosticEndpoint? {
    val url = raw?.toHttpUrlOrNull() ?: return null
    val sensitive = Regex("token|ticket|password|secret|authorization|cookie|api[_-]?key", RegexOption.IGNORE_CASE)
    val unsafePath = url.pathSegments.any { sensitive.containsMatchIn(it) || it.any(Char::isISOControl) }
    return GroupDiagnosticEndpoint(url.host, url.port, url.encodedPath.takeUnless { unsafePath })
}

internal fun groupDiagnosticSnapshot(
    conversation: GroupConversation,
    userMessageId: String,
    archiveScope: String,
): GroupDiagnosticSnapshot? {
    val message =
        conversation.messages.singleOrNull { it.id == userMessageId && it.isUser && !it.isSystem }
            ?: return null
    val endpoint = groupDiagnosticEndpoint(archiveScope.substringBefore('\u0000'))
    val recordedAt = runCatching { Instant.ofEpochMilli(message.timestamp).toString() }.getOrNull()
    val turns = conversation.turns.filter { it.userMessageId == userMessageId }
    // Ambiguous turn identities must not select another bot's metadata.
    val choices =
        turns.filter { selected -> conversation.turns.count { it.id == selected.id } == 1 }.map { turn ->
            GroupDiagnosticChoice(
                turn.id,
                GroupSendDiagnostic(
                    bot = turn.member,
                    runtimeId = diagnosticId(turn.runtimeSessionId),
                    storedId = diagnosticId(turn.storedSessionId),
                    delivery = turn.delivery,
                    messageRecordedAt = recordedAt,
                    endpoint = endpoint,
                ),
            )
        }
    return GroupDiagnosticSnapshot(
        groupPromptHash("$archiveScope\u0000${conversation.id}\u0000$userMessageId"),
        choices,
    )
}

private fun diagnosticId(value: String?): String? =
    value?.takeIf { it.matches(Regex("[a-zA-Z0-9_./:-]{1,256}")) && ".." !in it }
