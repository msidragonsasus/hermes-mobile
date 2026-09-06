package com.m57.hermescontrol.notification

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.OutgoingDraftStore
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.transport.HermesSubmissionTransport
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.DraftState
import com.m57.hermescontrol.domain.hermes.OutgoingDraft
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import com.m57.hermescontrol.domain.hermes.draftTargetKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID

enum class ReplyOutcome { CONFIRMED, DRAFT, UNKNOWN, STORAGE_UNAVAILABLE, TARGET_UNAVAILABLE }

/** A notification is a new explicit submission, never a background reconnect job. */
class NotificationReplySender(
    private val drafts: OutgoingDraftStore,
    private val transport: HermesSubmissionTransport,
    private val messages: ChatMessageDao,
) {
    suspend fun send(
        originalScope: ConnectionScope?,
        savedSessionId: String,
        text: String,
    ): ReplyOutcome {
        if (originalScope == null || originalScope.serverIdentity.isBlank()) return ReplyOutcome.TARGET_UNAVAILABLE
        var outcome = ReplyOutcome.STORAGE_UNAVAILABLE
        return try {
            withTimeout(8_000L) {
                val current = transport.configuredScope()
                val scope = current?.takeIf { it.draftTargetKey() == originalScope.draftTargetKey() } ?: originalScope
                var draft =
                    OutgoingDraft(
                        id = UUID.randomUUID().toString(),
                        scope = scope,
                        savedSessionId = savedSessionId,
                        runtimeSessionId = null,
                        text = text,
                        attachments = emptyList(),
                        createdAt = System.currentTimeMillis(),
                    )
                drafts.save(draft)
                outcome = ReplyOutcome.DRAFT
                if (scope != current || !transport.isReady(scope) || !messages.sessionExists(savedSessionId)) {
                    return@withTimeout outcome
                }
                // Always resolve this saved conversation in the captured scope. ActiveSessionHolder
                // has no scope and cannot establish ownership of a notification's runtime ID.
                val resumed = transport.resume(scope, savedSessionId)
                if (resumed !is SubmissionResult.Confirmed) return@withTimeout outcome
                val session = resumed.value
                if (session.savedId != savedSessionId || !transport.isReady(scope)) return@withTimeout outcome
                ActiveSessionHolder.rememberNotificationSession(scope, session.runtimeId, savedSessionId)
                draft = draft.copy(runtimeSessionId = session.runtimeId)
                drafts.save(draft.copy(state = DraftState.UNKNOWN))
                outcome = ReplyOutcome.UNKNOWN
                when (transport.submitPrompt(scope, session, text)) {
                    is SubmissionResult.Confirmed -> {
                        messages.upsert(
                            ChatMessageEntity(
                                id = draft.id,
                                sessionId = savedSessionId,
                                role = "USER",
                                content = text,
                                timestamp = draft.createdAt,
                            ),
                        )
                        drafts.delete(scope, draft.id)
                        outcome = ReplyOutcome.CONFIRMED
                    }

                    SubmissionResult.NotSent -> {
                        drafts.save(draft)
                        outcome = ReplyOutcome.DRAFT
                    }

                    SubmissionResult.Unknown -> {
                        Unit
                    }
                }
                outcome
            }
        } catch (_: TimeoutCancellationException) {
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The durable state describes what may have crossed the wire. Never log reply/error payloads.
            outcome
        }
    }
}
