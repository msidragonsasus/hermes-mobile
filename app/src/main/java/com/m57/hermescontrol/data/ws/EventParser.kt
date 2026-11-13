package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.domain.hermes.allowedApprovalChoices
import com.m57.hermescontrol.ui.chat.extractTodosFromMap

/**
 * Converts raw [JsonRpcResponse] objects into typed [WsEvent] instances.
 *
 * The Hermes TUI gateway sends events as JSON-RPC **notifications** (no `id`
 * field). The `method` is always `"event"` and the event type lives in
 * `params.type`. The event payload is in `params.payload`.
 *
 * Regular RPC responses have an `id` and either a `result` or `error`.
 */
object EventParser {
    private const val TAG = "EventParser"

    fun parse(
        response: JsonRpcResponse,
        rawJson: String = "",
    ): WsEvent {
        // Server→client requests also have ids. Decode these BEFORE RPC results.
        if (response.id != null && response.method != null) {
            return parseServerRequest(response.id, response.method, response.params?.toAny() as? Map<*, *>)
        }

        // ── RPC response (has id) ────────────────────────────────────────
        val id = response.id
        if (id != null) {
            return if (response.error != null) {
                WsEvent.RpcError(id, response.error)
            } else {
                WsEvent.RpcResult(id, response.result?.toAny())
            }
        }

        // ── Notification / event (no id, has method) ─────────────────────
        @Suppress("UNCHECKED_CAST")
        val params = response.params?.toAny() as? Map<String, Any?> ?: return WsEvent.Unknown(rawJson)
        return parseParams(params, rawJson)
    }

    /**
     * Parses an event object (bare params map containing `type`, `session_id`, `seq`, `payload`).
     * Used both for live notifications and for event lists returned by `session.events.since`.
     */
    fun parseParams(
        params: Map<String, Any?>,
        rawJson: String = "",
    ): WsEvent {
        val eventType = params["type"] as? String ?: return WsEvent.Unknown(rawJson)

        @Suppress("UNCHECKED_CAST")
        val payload = params["payload"] as? Map<String, Any?>

        // B7 (Jun 21 2026, kanban t_240): extract session_id from params first, fallback to payload
        val sessionId = params["session_id"] as? String ?: payload?.get("session_id") as? String

        return when (eventType) {
            "request.cancel" -> {
                val id = payload?.get("id") as? String
                if (payload?.get("method") != "clarify") {
                    WsEvent.ServerRequestCancel(id, sessionId)
                } else if (payload["reason"] == "resolved") {
                    WsEvent.ClarifyResolved(id, sessionId)
                } else {
                    WsEvent.ClarifyExpire(id, sessionId)
                }
            }

            "gateway.ready" -> {
                WsEvent.GatewayReady(payload)
            }

            "session.info" -> {
                WsEvent.SessionInfo(payload)
            }

            "message.start" -> {
                WsEvent.MessageStart(sessionId)
            }

            "message.token", "message.delta" -> {
                val text = payload?.get("text")
                if (eventType == "message.delta" && payload?.containsKey("text") == true && text == null) {
                    WsEvent.MessageSegmentEnd(sessionId)
                } else {
                    WsEvent.MessageToken(text as? String ?: "", sessionId)
                }
            }

            "message.interim" -> {
                WsEvent.MessageInterim(
                    text = payload?.get("text") as? String ?: "",
                    alreadyStreamed = payload?.get("already_streamed") as? Boolean ?: false,
                    sessionId = sessionId,
                )
            }

            "thinking.delta" -> {
                val token = payload?.get("text") as? String ?: ""
                WsEvent.ThinkingDelta(token, sessionId)
            }

            "reasoning.delta" -> {
                val token = payload?.get("text") as? String ?: ""
                WsEvent.ReasoningDelta(token, sessionId)
            }

            "reasoning.available" -> {
                val text = payload?.get("text") as? String
                WsEvent.ReasoningAvailable(sessionId, text)
            }

            "message.complete" -> {
                val content = payload?.get("content")
                val value =
                    if ((content is List<*> && content.isNotEmpty()) || content is Map<*, *>) {
                        content
                    } else {
                        payload?.get("text")?.takeUnless { it is String && it.isBlank() } ?: content
                    }
                val text =
                    com.m57.hermescontrol.data.model
                        .messageContentText(value?.toJsonElement())
                val reasoning = payload?.get("reasoning") as? String
                WsEvent.MessageComplete(text, sessionId, reasoning)
            }

            "message.done" -> {
                WsEvent.MessageDone(sessionId)
            }

            "tool.start" -> {
                val name = payload?.get("name") as? String
                WsEvent.ToolStart(name, payload, sessionId)
            }

            "tool.complete" -> {
                val name = payload?.get("name") as? String
                WsEvent.ToolComplete(name, payload, sessionId)
            }

            "tool.progress" -> {
                val name = payload?.get("name") as? String
                val preview = payload?.get("preview") as? String
                WsEvent.ToolProgress(name, preview, sessionId)
            }

            "tool.generating" -> {
                val name = payload?.get("name") as? String
                WsEvent.ToolGenerating(name, sessionId)
            }

            "subagent.spawn_requested", "subagent.start", "subagent.progress", "subagent.complete" -> {
                WsEvent.SubagentEvent(eventType, payload, sessionId)
            }

            "tool.output_risk" -> {
                val toolId = payload?.get("tool_id") as? String ?: ""
                val name = payload?.get("name") as? String ?: ""
                val risk = (payload?.get("risk") as? String)?.lowercase() ?: "low"
                val redacted = payload?.get("redacted") as? Boolean ?: false

                @Suppress("UNCHECKED_CAST")
                val findings = (payload?.get("findings") as? List<*>)?.filterIsInstance<String>() ?: emptyList()

                WsEvent.ToolOutputRisk(toolId, name, risk, findings, redacted, sessionId)
            }

            "clarify.request" -> {
                // Gateway sends "question"/"choices" — fall back to "text"/"options" for any
                // older client or test that still uses the legacy field names. (Issue #206)
                // Batch clarify (issue #18450): gateway sends "questions" array of {qid, question, choices, multi_select}.
                val rawQuestions = payload?.get("questions") as? List<*>
                val clarifyId = payload?.get("clarify_id") as? String ?: payload?.get("request_id") as? String

                val parsedQuestions =
                    if (rawQuestions != null && rawQuestions.isNotEmpty()) {
                        rawQuestions.mapIndexedNotNull { index, item ->
                            val map = item as? Map<*, *> ?: return@mapIndexedNotNull null
                            val qText = map["question"] as? String ?: return@mapIndexedNotNull null
                            val qid = map["qid"] as? String ?: "q$index"

                            @Suppress("UNCHECKED_CAST")
                            val qChoices = (map["choices"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                            val qMulti = map["multi_select"] as? Boolean ?: false
                            WsEvent.ClarifyQuestion(
                                qid = qid,
                                question = qText,
                                choices = qChoices,
                                multiSelect = qMulti,
                            )
                        }
                    } else {
                        val text =
                            payload?.get("question") as? String
                                ?: payload?.get("text") as? String
                        val rawOptions = payload?.get("choices") ?: payload?.get("options")

                        @Suppress("UNCHECKED_CAST")
                        val options = (rawOptions as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        val qid = payload?.get("qid") as? String ?: payload?.get("question_id") as? String
                        val multi = payload?.get("multi_select") as? Boolean ?: false
                        if ((!text.isNullOrBlank() || options.isNotEmpty()) && qid != null) {
                            listOf(
                                WsEvent.ClarifyQuestion(
                                    qid = qid,
                                    question = text.orEmpty(),
                                    choices = options,
                                    multiSelect = multi,
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    }

                val primary = parsedQuestions.firstOrNull()
                val legacyText =
                    if (parsedQuestions.size > 1) {
                        parsedQuestions.mapIndexed { index, q -> "${index + 1}. ${q.question}" }.joinToString("\n\n")
                    } else if (primary != null) {
                        primary.question
                    } else {
                        payload?.get("question") as? String
                            ?: payload?.get("text") as? String
                    }

                @Suppress("UNCHECKED_CAST")
                val legacyOptions =
                    primary?.choices
                        ?: (payload?.get("choices") ?: payload?.get("options"))
                            .let { (it as? List<*>)?.filterIsInstance<String>() }
                val legacyQid =
                    primary?.qid
                        ?: payload?.get("qid") as? String
                        ?: payload?.get("question_id") as? String
                val legacyMulti =
                    primary?.multiSelect
                        ?: payload?.get("multi_select") as? Boolean
                        ?: false

                WsEvent.ClarifyRequest(
                    text = legacyText,
                    options = legacyOptions,
                    clarifyId = clarifyId,
                    sessionId = sessionId,
                    questionId = legacyQid,
                    multiSelect = legacyMulti,
                    questions = parsedQuestions,
                )
            }

            "clarify.expire" -> {
                val clarifyId = payload?.get("request_id") as? String ?: payload?.get("clarify_id") as? String
                WsEvent.ClarifyExpire(clarifyId, sessionId)
            }

            "status.update" -> {
                val status = payload?.get("status") as? String
                WsEvent.StatusUpdate(status, payload)
            }

            "error" -> {
                val message =
                    payload?.get("message") as? String
                        ?: payload?.get("error") as? String
                WsEvent.GatewayError(message, sessionId)
            }

            "background.complete" -> {
                WsEvent.BackgroundComplete(payload)
            }

            // Change events (issue #784): gateway watches on-disk signatures
            // and broadcasts these so screens can refresh on change. pet.changed
            // intentionally absent — mobile has no pet feature.
            "cron.changed", "sessions.changed", "platforms.changed", "pairing.changed" -> {
                WsEvent.ChangeEvent(eventType, payload)
            }

            "review.summary" -> {
                val text = (payload?.get("text") as? String)?.trim() ?: ""
                WsEvent.ReviewSummary(text, sessionId)
            }

            "btw.complete" -> {
                val taskId = payload?.get("task_id") as? String ?: ""
                val question = payload?.get("question") as? String ?: ""
                val text = (payload?.get("text") as? String)?.trim() ?: ""
                WsEvent.BtwComplete(taskId, question, text, sessionId)
            }

            // Auto-titling finishes asynchronously and emits session.title,
            // sometimes after message.complete already refreshed the list.
            // Reuse the same refresh path as other session metadata changes.
            "session.updated", "session.title" -> {
                WsEvent.SessionUpdated(payload)
            }

            "session.usage" -> {
                WsEvent.SessionUsage(payload, sessionId)
            }

            "todo.updated" -> {
                val revision =
                    (payload?.get("revision") as? Number)?.toInt()
                        ?: (params["revision"] as? Number)?.toInt()
                val rawTodos = payload ?: params
                val todos = extractTodosFromMap(rawTodos) ?: emptyList()
                WsEvent.TodoUpdated(todos, revision, sessionId)
            }

            "reaction" -> {
                val kind = payload?.get("kind") as? String ?: ""
                WsEvent.ReactionEvent(kind)
            }

            "approval.request" -> {
                parseApprovalRequest(payload, sessionId)
            }

            "sudo.request" -> {
                val requestId = payload?.get("request_id") as? String
                WsEvent.SudoRequest(requestId, sessionId)
            }

            "sudo.expire" -> {
                val requestId = payload?.get("request_id") as? String
                WsEvent.SudoExpire(requestId, sessionId)
            }

            "secret.request" -> {
                val requestId = payload?.get("request_id") as? String
                val envVar = payload?.get("env_var") as? String
                val prompt = payload?.get("prompt") as? String
                WsEvent.SecretRequest(requestId, sessionId, envVar, prompt)
            }

            "secret.expire" -> {
                val requestId = payload?.get("request_id") as? String
                WsEvent.SecretExpire(requestId, sessionId)
            }

            else -> {
                // PR02 / R03: event types are server-controlled, including during replay.
                Log.w(TAG, "Unknown event type")
                WsEvent.Unknown(rawJson)
            }
        }
    }

    internal fun parseServerRequest(
        id: String,
        method: String,
        params: Map<*, *>?,
    ): WsEvent {
        val sessionId = params?.get("session_id") as? String
        // Hermes Agent v0.22+ sends exec approvals as an `approval` server request. Show the normal card;
        // the answer goes through `approval.respond` with request_id, and the server then withdraws this frame.
        if (method == "approval") {
            val approval =
                if (id.isBlank() ||
                    sessionId.isNullOrBlank()
                ) {
                    null
                } else {
                    parseApprovalRequest(params, sessionId)
                }
            return approval?.takeUnless { it.requestId.isNullOrBlank() } ?: WsEvent.UnsupportedServerRequest(id)
        }
        if (method == "clarify") {
            if (id.isBlank() || sessionId.isNullOrBlank()) return WsEvent.UnsupportedServerRequest(id)
            return parseClarifyServerRequest(id, sessionId, params)
        }
        val kind = VaultPromptKind.entries.firstOrNull { it.method == method }
        if (kind == null || id.isBlank() || sessionId.isNullOrBlank()) {
            return WsEvent.UnsupportedServerRequest(id)
        }
        return WsEvent.VaultRequest(
            VaultPromptRequest(
                id = id,
                sessionId = sessionId,
                kind = kind,
                origin = params["origin"] as? String,
                site = (params["site"] ?: params["display_name"]) as? String,
                hint = params["hint"] as? String,
            ),
        )
    }

    private fun parseClarifyServerRequest(
        id: String,
        sessionId: String,
        params: Map<*, *>?,
    ): WsEvent {
        val questions =
            (params?.get("questions") as? List<*>).orEmpty().mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val qid = (map["qid"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WsEvent.ClarifyQuestion(
                    qid = qid,
                    question = map["question"] as? String ?: "",
                    choices = (map["choices"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                    multiSelect = map["multi_select"] as? Boolean ?: false,
                )
            }
        if (questions.isNotEmpty()) {
            val rawAnswers = params?.get("answers") as? Map<*, *>
            val lockedAnswers =
                buildMap {
                    questions.forEach { question ->
                        val answer = rawAnswers?.get(question.qid)
                        if (answer is String || (answer == null && rawAnswers?.containsKey(question.qid) == true)) {
                            put(question.qid, answer)
                        }
                    }
                }
            return WsEvent.ClarifyRequest(
                text = questions.mapIndexed { index, q -> "${index + 1}. ${q.question}" }.joinToString("\n\n"),
                options = questions.first().choices,
                clarifyId = id,
                sessionId = sessionId,
                questionId = questions.first().qid,
                multiSelect = questions.first().multiSelect,
                questions = questions,
                lockedAnswers = lockedAnswers,
            )
        }
        val text = params?.get("question") as? String
        val choices = (params?.get("choices") as? List<*>)?.filterIsInstance<String>().orEmpty()
        if (text.isNullOrBlank() && choices.isEmpty()) return WsEvent.UnsupportedServerRequest(id)
        // Single question: no qid on the wire; the answer is the whole request result.
        return WsEvent.ClarifyRequest(
            text = text.orEmpty(),
            options = choices,
            clarifyId = id,
            sessionId = sessionId,
            questionId = null,
            multiSelect = params?.get("multi_select") as? Boolean ?: false,
        )
    }

    /** One decoder for live events, session.info snapshots and approval.pending replay. */
    internal fun parseApprovalRequest(
        payload: Map<*, *>?,
        sessionId: String?,
    ): WsEvent.ApprovalRequest {
        val command = payload?.get("command") as? String
        val description = payload?.get("description") as? String

        @Suppress("UNCHECKED_CAST")
        val patternKeys = (payload?.get("pattern_keys") as? List<*>)?.filterIsInstance<String>()
        val requestId = payload?.get("request_id") as? String

        @Suppress("UNCHECKED_CAST")
        val rawChoices =
            if (payload?.containsKey("choices") == true) {
                (payload["choices"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            } else {
                null
            }
        val allowPermanent = payload?.get("allow_permanent") as? Boolean
        val allowSession = payload?.get("allow_session") as? Boolean
        val smartDenied = payload?.get("smart_denied") as? Boolean
        // Flags restrict an explicit advertisement; missing choices never infer broader permission.
        val choices =
            allowedApprovalChoices(rawChoices, allowPermanent, smartDenied).filter { choice ->
                allowSession != false || choice == "once" || choice == "deny"
            }
        return WsEvent.ApprovalRequest(
            command,
            description,
            patternKeys,
            sessionId,
            requestId,
            choices,
            allowPermanent,
            smartDenied,
        )
    }
}
