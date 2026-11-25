package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.ExecutionState
import com.m57.hermescontrol.domain.hermes.HermesObservation
import com.m57.hermescontrol.domain.hermes.KNOWN_APPROVAL_CHOICES
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.domain.hermes.ObservationKind
import com.m57.hermescontrol.domain.hermes.PromptAcceptance
import com.m57.hermescontrol.domain.hermes.ReadFailure
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SessionRead
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.SessionSummary
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import com.m57.hermescontrol.domain.hermes.TransportConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Gateway-minted ids of open server->client requests (`srq-<hex>`), never client RPC ids. */
internal const val SERVER_REQUEST_ID_PREFIX = "srq-"

class DashboardRpcTransport :
    HermesTransport,
    HermesSubmissionTransport,
    HermesAttentionTransport,
    HermesAttentionSource {
    override val attentionSignals = normalizeDashboardAttention(HermesWsClient.scopedEvents)

    override suspend fun respondApproval(
        ref: RequestRef,
        choice: String,
    ): SubmissionResult<Boolean> {
        if (choice !in KNOWN_APPROVAL_CHOICES || ref.requestId.isBlank() || ref.questionId != null) {
            return SubmissionResult.NotSent
        }
        if (ref.executionGeneration != null) {
            return respondCanonical(ref, WsMethods.APPROVAL_RESPOND, "choice", choice)
        }
        return execute(
            ref.scope,
            WsMethods.APPROVAL_RESPOND,
            mapOf(
                "session_id" to ref.session.runtimeId,
                "request_id" to ref.requestId,
                "choice" to choice,
                "all" to false,
            ),
        ) { body ->
            (body["resolved"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { it == 1 }
        }
    }

    override suspend fun acknowledgeApproval(ref: RequestRef): SubmissionResult<Boolean> {
        if (ref.requestId.isBlank()) return SubmissionResult.NotSent
        if (ref.executionGeneration != null) return SubmissionResult.NotSent
        return execute(
            ref.scope,
            WsMethods.APPROVAL_RECEIVED,
            mapOf("session_id" to ref.session.runtimeId, "request_id" to ref.requestId),
        ) { body -> true.takeIf { body.isTrue("acknowledged") } }
    }

    override suspend fun respondClarification(
        ref: RequestRef,
        answer: String?,
    ): SubmissionResult<Boolean> {
        if (ref.requestId.isBlank() || ref.questionId?.isBlank() == true ||
            (ref.serverRequestId != null && ref.serverRequestId != ref.requestId)
        ) {
            return SubmissionResult.NotSent
        }
        // Explicit modern batches require the typed lock API and its known-qid validation.
        if (ref.serverRequestId != null && ref.questionId != null) return SubmissionResult.NotSent
        val result =
            if (ref.executionGeneration != null) {
                respondCanonical(
                    ref,
                    WsMethods.CLARIFY_RESPOND,
                    "answer",
                    answer ?: com.m57.hermescontrol.domain.hermes.CLARIFICATION_DECLINED_ANSWER,
                )
            } else if (ref.serverRequestId != null || ref.requestId.startsWith(SERVER_REQUEST_ID_PREFIX)) {
                respondServerClarification(ref, answer)
            } else {
                execute(
                    ref.scope,
                    WsMethods.CLARIFY_RESPOND,
                    buildMap {
                        put("session_id", ref.session.runtimeId)
                        put("request_id", ref.requestId)
                        put("clarify_id", ref.requestId)
                        ref.questionId?.let { put("question_id", it) }
                        put("answer", answer ?: com.m57.hermescontrol.domain.hermes.CLARIFICATION_DECLINED_ANSWER)
                    },
                    decode = ::clarificationStatus,
                )
            }
        return when (result) {
            is SubmissionResult.Confirmed -> {
                if (result.value) result else SubmissionResult.Expired
            }

            SubmissionResult.NotSent -> {
                SubmissionResult.NotSent
            }

            SubmissionResult.Expired -> {
                SubmissionResult.Expired
            }

            SubmissionResult.Unknown -> {
                SubmissionResult.Unknown
            }
        }
    }

    private suspend fun respondCanonical(
        ref: RequestRef,
        method: String,
        responseField: String,
        response: String,
    ): SubmissionResult<Boolean> =
        execute(
            ref.scope,
            method,
            mapOf(
                "session_id" to ref.session.runtimeId,
                "prompt_id" to ref.requestId,
                "execution_generation" to ref.executionGeneration,
                responseField to response,
            ),
        ) { body ->
            when (body.string("status")) {
                "resolved" -> true
                "already_resolved" -> false
                else -> null
            }
        }.let { result ->
            if (result is SubmissionResult.Confirmed && !result.value) SubmissionResult.Expired else result
        }

    /** Compatibility for incoming srq refs; newly parsed batches always use lockClarification. */
    private suspend fun respondServerClarification(
        ref: RequestRef,
        answer: String?,
    ): SubmissionResult<Boolean> =
        if (ref.questionId != null) {
            execute(
                ref.scope,
                WsMethods.CLARIFY_LOCK,
                mapOf(
                    "session_id" to ref.session.runtimeId,
                    "request_id" to ref.requestId,
                    "question_id" to ref.questionId,
                    "answer" to answer,
                ),
                decode = ::clarificationStatus,
            )
        } else {
            execute(
                ref.scope,
                WsMethods.REQUEST_ANSWER,
                mapOf(
                    "session_id" to ref.session.runtimeId,
                    "id" to ref.requestId,
                    "result" to mapOf("answer" to answer),
                ),
                decode = ::clarificationStatus,
            )
        }

    private fun clarificationStatus(body: JsonObject): Boolean? =
        when (body.string("status")) {
            "ok" -> true
            "expired" -> false
            else -> null
        }

    override suspend fun lockClarification(
        ref: RequestRef,
        answer: String?,
        knownQuestionIds: Set<String>,
    ): SubmissionResult<ClarificationResult> {
        val outer = ref.serverRequestId ?: return SubmissionResult.NotSent
        val qid = ref.questionId ?: return SubmissionResult.NotSent
        if (outer.isBlank() || ref.requestId != outer || qid !in knownQuestionIds ||
            knownQuestionIds.any(String::isBlank)
        ) {
            return SubmissionResult.NotSent
        }
        return execute(
            ref.scope,
            WsMethods.CLARIFY_LOCK,
            mapOf(
                "session_id" to ref.session.runtimeId,
                "request_id" to outer,
                "question_id" to qid,
                "answer" to answer,
            ),
        ) { body ->
            when (body.string("status")) {
                "expired" -> {
                    ClarificationResult.Expired
                }

                "ok" -> {
                    val raw = body["remaining"] as? JsonArray
                    val remaining = raw?.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
                    if (remaining == null || remaining.any { it == null || it !in knownQuestionIds || it == qid } ||
                        remaining.distinct().size != remaining.size
                    ) {
                        null
                    } else {
                        ClarificationResult.Locked(remaining.filterNotNull())
                    }
                }

                else -> {
                    null
                }
            }
        }
    }

    override fun configuredScope(): ConnectionScope? = HermesWsClient.configuredScope()

    override fun isReady(scope: ConnectionScope): Boolean =
        HermesWsClient.matchesCurrentScope(scope) && HermesWsClient.isConnected

    override suspend fun clientSurfaceSupport(
        scope: ConnectionScope,
        surface: String,
    ): Boolean? = HermesWsClient.clientSurfaceSupport(scope, surface)

    override suspend fun submitPrompt(
        scope: ConnectionScope,
        session: SessionRef,
        text: String,
        redirect: Boolean,
        queued: Boolean,
        surface: String?,
    ): SubmissionResult<PromptAcceptance> {
        if (surface != null &&
            (redirect || !HermesWsClient.supportsClientSurface(scope, surface))
        ) {
            return SubmissionResult.NotSent
        }
        if (redirect && (text.isBlank() || queued)) return SubmissionResult.NotSent
        return execute(
            scope,
            if (redirect) WsMethods.SESSION_REDIRECT else WsMethods.PROMPT_SUBMIT,
            buildMap {
                put("session_id", session.runtimeId)
                put("text", text)
                if (queued) put("queued", true)
                surface?.let { put("surface", it) }
            },
            terminalSession = session.takeUnless { redirect || queued },
            terminalValue = PromptAcceptance.STREAMING,
        ) { result ->
            when (result.string("status")) {
                "streaming" -> PromptAcceptance.STREAMING.takeUnless { redirect }
                "queued" -> PromptAcceptance.QUEUED
                "redirected" -> PromptAcceptance.REDIRECTED
                "steered" -> PromptAcceptance.STEERED.takeUnless { redirect }
                else -> null
            }
        }
    }

    override suspend fun steerPrompt(
        scope: ConnectionScope,
        session: SessionRef,
        text: String,
    ): SubmissionResult<PromptAcceptance> {
        if (text.isBlank()) return SubmissionResult.NotSent
        return execute(
            scope,
            WsMethods.SESSION_STEER,
            mapOf("session_id" to session.runtimeId, "text" to text),
        ) { body ->
            when (body.string("status")) {
                "queued" -> {
                    if (body.string("delivery") == "next_turn") PromptAcceptance.QUEUED else PromptAcceptance.STEERED
                }

                else -> {
                    null
                }
            }
        }
    }

    override suspend fun attach(
        scope: ConnectionScope,
        session: SessionRef,
        attachment: Attachment,
        dataUrl: String,
    ): SubmissionResult<String?> =
        if (attachment.isImage) {
            when (
                val result =
                    execute(
                        scope,
                        WsMethods.IMAGE_ATTACH_BYTES,
                        mapOf(
                            "session_id" to session.runtimeId,
                            "content_base64" to dataUrl,
                            "filename" to attachment.name,
                            "ext" to attachment.fileExtension,
                        ),
                    ) { body -> true.takeIf { body.isTrue("attached") } }
            ) {
                is SubmissionResult.Confirmed -> SubmissionResult.Confirmed(result.requestId, null)
                SubmissionResult.NotSent -> SubmissionResult.NotSent
                SubmissionResult.Expired -> SubmissionResult.Expired
                SubmissionResult.Unknown -> SubmissionResult.Unknown
            }
        } else {
            execute(
                scope,
                WsMethods.FILE_ATTACH,
                mapOf("session_id" to session.runtimeId, "data_url" to dataUrl, "name" to attachment.name),
            ) { body -> body.string("ref_text")?.takeIf { it.isNotBlank() && body.isTrue("attached") } }
        }

    override suspend fun resume(
        scope: ConnectionScope,
        savedSessionId: String,
    ): SubmissionResult<SessionRef> =
        execute(scope, WsMethods.SESSION_RESUME, mapOf("session_id" to savedSessionId, "omit_messages" to true)) {
            it.string("session_id")?.takeIf(String::isNotBlank)?.let { runtime -> SessionRef(runtime, savedSessionId) }
        }

    private suspend fun <T : Any> execute(
        scope: ConnectionScope,
        method: String,
        params: Map<String, Any?>,
        terminalSession: SessionRef? = null,
        terminalValue: T? = null,
        decode: (JsonObject) -> T?,
    ): SubmissionResult<T> {
        val call =
            if (terminalSession != null) {
                HermesWsClient.writePromptScoped(scope, terminalSession, params)
            } else {
                HermesWsClient.writeScoped(scope, method, params)
            }
        if (!call.accepted) return SubmissionResult.NotSent
        return try {
            val outcome =
                kotlinx.coroutines.selects.select<Any?> {
                    call.response.onAwait { it }
                    call.terminalProof?.onAwait { it }
                }
            if (outcome is com.m57.hermescontrol.data.ws.TerminalAcceptanceProof) {
                if (terminalValue == null || !outcome.isCurrent() || !HermesWsClient.matchesCurrentScope(scope)) {
                    return SubmissionResult.Unknown
                }
                return SubmissionResult.Confirmed(
                    requireNotNull(call.requestId),
                    terminalValue,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(outcome.receipt.originUserRowId),
                    com.m57.hermescontrol.domain.hermes.SubmissionProofSource.TERMINAL_RECEIPT,
                    outcome.observation,
                    outcome.authenticationEpoch,
                )
            }
            val response = outcome as? JsonObject
            if (method == WsMethods.SESSION_STEER && response?.string("status") == "rejected" &&
                HermesWsClient.matchesCurrentScope(scope)
            ) {
                return SubmissionResult.NotSent
            }
            val decoded = response?.let(decode)
            if (decoded != null && HermesWsClient.matchesCurrentScope(scope)) {
                val evidence =
                    if (method == WsMethods.PROMPT_SUBMIT || method == WsMethods.SESSION_REDIRECT) {
                        val row = response["user_row_id"] as? kotlinx.serialization.json.JsonPrimitive
                        val rowId =
                            row
                                ?.takeUnless { it.isString }
                                ?.content
                                ?.toLongOrNull()
                                ?.takeIf { it > 0 }
                        val submission =
                            (response["submission_id"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.takeIf { it.isString }
                                ?.content
                                ?.takeIf { it.isNotBlank() }
                        com.m57.hermescontrol.domain.hermes
                            .PromptEvidence(rowId, submission)
                            .takeIf { rowId != null || submission != null }
                    } else {
                        null
                    }
                SubmissionResult.Confirmed(requireNotNull(call.requestId), decoded, evidence)
            } else {
                SubmissionResult.Unknown
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesWsClient.HermesRpcException) {
            if (method == WsMethods.SESSION_STEER &&
                (e.code in setOf(4010, -32601) || (e.code == 4001 && e.message == "invalid_params"))
            ) {
                SubmissionResult.NotSent
            } else {
                SubmissionResult.Unknown
            }
        } finally {
            call.release?.invoke()
        }
    }

    override val connection =
        combine(HermesWsClient.connectionScope, HermesWsClient.connectionStatus) { scope, status ->
            TransportConnection(
                scope.takeIf { status == ConnectionStatus.CONNECTED },
                when (status) {
                    ConnectionStatus.CONNECTED -> LinkState.CONNECTED
                    ConnectionStatus.CONNECTING -> LinkState.CONNECTING
                    ConnectionStatus.RECONNECTING -> LinkState.RECONNECTING
                    ConnectionStatus.AUTH_EXPIRED -> LinkState.AUTH_REQUIRED
                    else -> LinkState.OFFLINE
                },
            )
        }

    override val observations = normalizeDashboardEvents(HermesWsClient.scopedEvents)

    override suspend fun readSessions(scope: ConnectionScope): SessionRead {
        if (!HermesWsClient.matchesCurrentScope(scope)) return SessionRead.Unavailable(ReadFailure.SCOPE_CHANGED)
        if (HermesWsClient.connectionStatus.value !=
            ConnectionStatus.CONNECTED
        ) {
            return SessionRead.Unavailable(ReadFailure.OFFLINE)
        }
        return try {
            val result = HermesWsClient.readSessions(scope).await()
            if (!HermesWsClient.matchesCurrentScope(scope)) {
                SessionRead.Unavailable(ReadFailure.SCOPE_CHANGED)
            } else {
                decodeDashboardSessions(scope, result as? JsonElement)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesWsClient.HermesRpcException) {
            SessionRead.Unavailable(
                when (e.code) {
                    -32601 -> ReadFailure.UNSUPPORTED
                    401, 4401 -> ReadFailure.AUTH_REQUIRED
                    else -> ReadFailure.TIMEOUT_OR_UNAVAILABLE
                },
            )
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.isTrue(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true

internal fun decodeDashboardSessions(
    scope: ConnectionScope,
    result: JsonElement?,
): SessionRead {
    val rows =
        (result as? JsonObject)?.get("sessions") as? JsonArray ?: return SessionRead.Unavailable(ReadFailure.MALFORMED)
    val sessions = mutableListOf<SessionSummary>()
    val ids = mutableSetOf<String>()
    for (row in rows) {
        val item = row as? JsonObject ?: return SessionRead.Unavailable(ReadFailure.MALFORMED)
        val id = item["id"] as? JsonPrimitive
        if (id == null || !id.isString || id.content.isBlank() ||
            !ids.add(id.content)
        ) {
            return SessionRead.Unavailable(ReadFailure.MALFORMED)
        }
        val title = item["title"] as? JsonPrimitive
        sessions.add(SessionSummary(id.content, title?.takeIf { it.isString }?.content))
    }
    return SessionRead.Available(scope, sessions)
}

internal fun normalizeDashboardEvents(events: Flow<ScopedWsEvent>): Flow<HermesObservation> =
    flow {
        var previous: ScopedWsEvent? = null
        var replayEpoch: String? = null
        events.collect { envelope ->
            val prior = previous
            val epoch = (envelope.event as? WsEvent.GatewayReady)?.data?.get("replay_epoch") as? String
            val epochChanged = !epoch.isNullOrBlank() && replayEpoch != null && replayEpoch != epoch
            if (!epoch.isNullOrBlank()) replayEpoch = epoch
            if (epochChanged ||
                (prior != null && (prior.scope != envelope.scope || envelope.ordinal != prior.ordinal + 1))
            ) {
                emit(
                    HermesObservation(
                        envelope.scope,
                        envelope.ordinal,
                        null,
                        ObservationKind.GAP,
                        ExecutionState.UNKNOWN,
                    ),
                )
            }
            previous = envelope
            normalizeDashboardEvent(envelope)?.let { emit(it) }
        }
    }

internal fun normalizeDashboardEvent(envelope: ScopedWsEvent): HermesObservation? {
    var requestId: String? = null
    var questionId: String? = null
    var serverRequestId: String? = null
    var savedId: String? = null
    val (sessionId, kind, state) =
        when (val event = envelope.event) {
            is WsEvent.MessageStart -> {
                Triple(event.sessionId, ObservationKind.STARTED, ExecutionState.RUNNING)
            }

            is WsEvent.MessageComplete -> {
                savedId = event.storedSessionId
                Triple(event.sessionId, ObservationKind.COMPLETED, ExecutionState.COMPLETED)
            }

            is WsEvent.ToolStart -> {
                Triple(event.sessionId, ObservationKind.TOOL_STARTED, ExecutionState.RUNNING)
            }

            is WsEvent.ToolComplete -> {
                Triple(event.sessionId, ObservationKind.TOOL_COMPLETED, ExecutionState.RUNNING)
            }

            is WsEvent.ApprovalRequest -> {
                requestId = event.requestId
                serverRequestId = event.serverRequestId
                Triple(event.sessionId, ObservationKind.APPROVAL, ExecutionState.WAITING_APPROVAL)
            }

            is WsEvent.ClarifyRequest -> {
                requestId = event.clarifyId
                serverRequestId = event.serverRequestId
                questionId = event.questionId
                Triple(event.sessionId, ObservationKind.QUESTION, ExecutionState.NEEDS_INPUT)
            }

            is WsEvent.ClarifyExpire -> {
                requestId = event.clarifyId
                Triple(event.sessionId, ObservationKind.QUESTION_EXPIRED, ExecutionState.UNKNOWN)
            }

            else -> {
                if (envelope.gap) {
                    Triple(
                        envelope.gapSessionId,
                        ObservationKind.GAP,
                        ExecutionState.UNKNOWN,
                    )
                } else {
                    return null
                }
            }
        }
    val session = sessionId?.takeIf(String::isNotBlank)?.let { SessionRef(it, savedId) }
    val request =
        requestId?.takeIf(String::isNotBlank)?.let { id ->
            session?.let { RequestRef(envelope.scope, it, id, questionId, serverRequestId) }
        }
    return HermesObservation(envelope.scope, envelope.ordinal, session, kind, state, request)
}
