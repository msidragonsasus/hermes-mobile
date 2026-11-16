package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.net.Uri
import android.util.Log
import com.m57.hermescontrol.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.local.OutgoingDraftRepository
import com.m57.hermescontrol.data.local.OutgoingDraftStore
import com.m57.hermescontrol.data.local.SlashUsageStore
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.parseContextBreakdown
import com.m57.hermescontrol.data.model.parseUsageSnapshot
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.transport.DashboardRpcTransport
import com.m57.hermescontrol.data.transport.HermesAttentionTransport
import com.m57.hermescontrol.data.transport.HermesSubmissionTransport
import com.m57.hermescontrol.data.transport.dashboardAttentionItems
import com.m57.hermescontrol.data.ws.CommandBlocklist
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.EventParser
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.VaultPromptKind
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.toAny
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.domain.hermes.AttentionKind
import com.m57.hermescontrol.domain.hermes.AttentionRepository
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.DraftState
import com.m57.hermescontrol.domain.hermes.OutgoingDraft
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.SubmissionProofSource
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import com.m57.hermescontrol.domain.hermes.draftTargetKey
import com.m57.hermescontrol.ui.common.ActionProgressController
import com.m57.hermescontrol.vault.VaultPromptUi
import com.m57.hermescontrol.vault.vaultOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ChatViewModel"
private const val MESSAGE_PAGE_SIZE = 150

private data class PreparedAttachment(
    val attachment: Attachment,
    val encodedFile: File,
)

private sealed interface PrepareAttachmentResult {
    data class Success(
        val prepared: PreparedAttachment,
    ) : PrepareAttachmentResult

    data object TooLarge : PrepareAttachmentResult

    data object Unreadable : PrepareAttachmentResult
}

/**
 * Canonical comparison key for a tool message's result payload.
 *
 * WS tool messages store the full tool.complete payload
 * (`{"tool_id":..., "name":..., "args":..., "result": {...}}`) while REST
 * transcript rows store just the result object (`{"output":..., "exit_code":...}`).
 * This key normalizes both sides — preferring the `result` field when present,
 * and treating int/float JSON numbers as equal — so the two representations of
 * the SAME tool call can be matched regardless of position or pagination.
 * Returns null for unparseable content (no match possible).
 */
internal fun canonicalToolResultKey(content: String): String? {
    val element =
        try {
            OkHttpProvider.json.parseToJsonElement(content)
        } catch (_: Exception) {
            return null
        }

    fun canon(e: kotlinx.serialization.json.JsonElement): String =
        when (e) {
            is kotlinx.serialization.json.JsonObject -> {
                e.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${canon(it.value)}" }
            }

            is kotlinx.serialization.json.JsonArray -> {
                e.joinToString(",") { canon(it) }
            }

            is kotlinx.serialization.json.JsonPrimitive -> {
                // Canonicalize ALL numbers through double, collapsing int/float
                // spellings of the same value (0 vs 0.0 → "i0", 0.5 → "d0.5").
                val s = e.content
                val d = s.toDoubleOrNull()
                if (d != null) {
                    if (d == d.toLong().toDouble()) "i${d.toLong()}" else "d$d"
                } else {
                    "s$s"
                }
            }
        }
    return when (element) {
        is kotlinx.serialization.json.JsonObject -> {
            element["result"]?.let { canon(it) } ?: canon(element)
        }

        else -> {
            canon(element)
        }
    }
}

/**
 * True when [a] and [b] are the same logical message (the WS-persisted and
 * REST-persisted copies of one row — they carry different ids, see #771).
 * Tool messages match on their normalized result payload; other roles on
 * exact content, IGNORING leading/trailing whitespace.
 *
 * Issue #842: the app seals the RAW streamed text (which can carry leading
 * blank lines the model emits before its narration), while the backend
 * persists a CLEANED copy (leading whitespace stripped). An exact-content
 * compare made the reload merge treat them as different messages and add
 * the REST copy on top — the commentary duplicated ~10s after the stream
 * ended. Trim closes the drift: the sealed live bubble is covered by the
 * REST row and the duplicate never renders.
 */
internal fun sameLogicalMessage(
    a: ChatMessage,
    b: ChatMessage,
): Boolean {
    if (a.role != b.role) return false
    if (a.approvalInfo != null || b.approvalInfo != null) {
        val ref = a.approvalInfo?.ref
        return if (ref != null) ref == b.approvalInfo?.ref else a.id == b.id
    }
    if (a.clarifyInfo != null || b.clarifyInfo != null) {
        val ref = a.clarifyInfo?.ref
        return if (ref != null) ref == b.clarifyInfo?.ref else a.id == b.id
    }
    if (a.role == MessageRole.TOOL) {
        // Issue #842: prefer the gateway's tool call id when both sides carry
        // it — the REST transcript stores `tool_call_id` and the live WS
        // bubble keeps it from `tool.start`. Content canonicalization cannot
        // cover MCP/web tools: the REST side stores the payload as raw
        // `<untrusted_tool_result>` text (not JSON), so it has no key at all.
        if (a.toolCallId.isNotBlank() && b.toolCallId.isNotBlank()) {
            return a.toolCallId == b.toolCallId
        }
        val ka = canonicalToolResultKey(a.content)
        val kb = canonicalToolResultKey(b.content)
        return ka != null && ka == kb
    }
    val ta = a.content.trim()
    val tb = b.content.trim()
    if (ta == tb) return true
    // Attachment dedupe: when the user sends an image/file, the backend
    // persists an ENRICHED copy of the prompt — the real caption plus
    // `@image:`/`@file:` reference lines and a `[screenshot]` marker
    // (tui_gateway _build_image_ref_message / run_agent flattening). The
    // optimistic bubble carries the raw caption only, so the exact compare
    // above fails and the REST sync renders the same message twice. Compare
    // USER rows on the caption with injection lines stripped (both sides —
    // stripping a clean string is a no-op). USER-only so assistant text that
    // legitimately mentions these tokens is never collapsed.
    if (a.role == MessageRole.USER) {
        val ca = stripAttachmentRefLines(ta)
        val cb = stripAttachmentRefLines(tb)
        if (ca == cb) return true
    }
    // Issue #842: a seal race can leave the live orphan a few trailing tokens
    // short of the streamed narration (the last delta was still in the
    // throttled buffer when tool.start sealed the message). The backend
    // persists the COMPLETE copy, so the orphan is a strict prefix of the
    // REST row. Accept prefix-covering only for substantial texts (>=40
    // chars) so a short reply can never be swallowed by a longer message
    // that merely starts with it.
    return ta.length >= 40 &&
        tb.length >= 40 &&
        (tb.startsWith(ta) || ta.startsWith(tb))
}

/**
 * Remove backend attachment-injection lines from a user message so the
 * optimistic bubble and the server-enriched REST copy compare equal.
 * Lines the gateway adds on top of the user's caption: `@image:<path>`,
 * `@file:<ref>`, and `[screenshot]` (multipart image placeholder written by
 * run_agent). Blank lines left behind are dropped too.
 */
internal fun stripAttachmentRefLines(content: String): String =
    content
        .lines()
        .map { it.trim() }
        .filterNot { line ->
            line.startsWith("@image:") ||
                line.startsWith("@file:") ||
                line == "[screenshot]"
        }.joinToString("\n")
        .trim()

/**
 * Room accumulates BOTH the WS-persisted copy (UUID id, rich tool payload,
 * tool name) and the REST-persisted copy (`rest-` id, result-only payload,
 * no tool name) of every message. Painting the cache verbatim renders the
 * same call twice. Drop the `rest-` copy whenever a WS copy of the same
 * logical message exists (issue #771).
 */
internal fun dedupeCachedMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val rest = messages.filter { it.id.startsWith("rest-") }
    if (rest.isEmpty()) return messages
    val nonRest = messages.filterNot { it.id.startsWith("rest-") }
    if (nonRest.isEmpty()) return messages
    val keepRest = rest.filter { restMsg -> nonRest.none { sameLogicalMessage(it, restMsg) } }
    // A canonical steer tag must survive warm-cache dedupe too. Ambiguous user
    // echoes retain REST identities rather than assigning tags by repeated text.
    val users =
        mergeTranscriptWithLive(
            rest.filter { it.role == MessageRole.USER },
            nonRest.filter { it.role == MessageRole.USER },
        )
    return ((nonRest + keepRest).filterNot { it.role == MessageRole.USER } + users).sortedBy { it.timestamp }
}

/**
 * A transcript reload must NOT yank live WS bubbles the server has not
 * persisted yet. The gateway stores a tool row only once the tool
 * COMPLETES server-side, so a reload that lands while a tool is running
 * (app background/foreground mid-turn, reconnect re-resume, pull-refresh)
 * returns a page without the tool row — replacing the list outright made
 * the in-flight tool bubble vanish and left tool.complete with no RUNNING
 * message to update (issue #771).
 *
 * Merge instead of replace: append any current message the REST page does
 * not already cover (checked by id AND logical content via
 * [sameLogicalMessage]) and keep chronological order.
 */
internal fun mergeTranscriptWithLive(
    restMessages: List<ChatMessage>,
    currentMessages: List<ChatMessage>,
): List<ChatMessage> {
    val dedupedRest = restMessages.dedupeById()
    val dedupedCurrent = currentMessages.dedupeById()
    // User rows can carry live/cache-only metadata (for example whether the
    // bubble continues the active turn). Keep that richer copy when REST
    // returns the same logical row.
    val originalUsers = dedupedCurrent.filter { it.role == MessageRole.USER }
    val currentUsers = originalUsers.toMutableList()
    val mergedRest =
        dedupedRest.map { rest ->
            if (rest.role != MessageRole.USER) return@map rest
            val exactIndex = currentUsers.indexOfFirst { it.id == rest.id }
            val matchIndex =
                if (exactIndex >= 0) {
                    exactIndex
                } else {
                    // Content is only an echo match when unique on BOTH sides. The
                    // same words can be a normal question and a different accepted steer.
                    val candidates = originalUsers.filter { sameLogicalMessage(rest, it) }
                    val unique =
                        candidates.singleOrNull()?.takeIf { candidate ->
                            dedupedRest.count { it.role == MessageRole.USER && sameLogicalMessage(it, candidate) } == 1
                        }
                    currentUsers.indexOfFirst { it.id == unique?.id }
                }
            if (matchIndex >= 0) {
                currentUsers.removeAt(matchIndex).let { local ->
                    preferServerDisplayProjection(
                        rest,
                        if (rest.displayKind == null || rest.displayKind == local.displayKind) {
                            local
                        } else {
                            local.copy(displayKind = rest.displayKind)
                        },
                    )
                }
            } else {
                rest
            }
        }
    val restIds = dedupedRest.map { it.id }.toSet()
    val liveTail =
        dedupedCurrent.filter { old ->
            old.id !in restIds && mergedRest.none { sameLogicalMessage(it, old) }
        }
    if (liveTail.isEmpty()) return mergedRest.dedupeById()
    return (mergedRest + liveTail).dedupeById().sortedBy { it.timestamp }
}

/** Merge a REST page without matching it against already-settled transcript rows. */
internal fun mergeIncrementalTranscriptPage(
    restMessages: List<ChatMessage>,
    currentMessages: List<ChatMessage>,
    sessionId: String,
    offset: Int,
): List<ChatMessage> {
    val settledEnd =
        currentMessages.indexOfLast { message ->
            serverMessageIndex(message.id, sessionId)?.let { it < offset } == true
        }
    return (
        currentMessages.take(settledEnd + 1) +
            mergeTranscriptWithLive(restMessages, currentMessages.drop(settledEnd + 1))
    ).dedupeById()
}

private fun serverMessageIndex(
    id: String,
    sessionId: String,
): Int? = id.removePrefix("rest-$sessionId-").takeIf { it != id }?.toIntOrNull()

internal fun List<ChatMessage>.dedupeById(): List<ChatMessage> = associateBy { it.id }.values.toList()

/** Native component buttons send only when a message would go out now, never into the queue. */
internal fun canSendNativeAction(state: ChatUiState): Boolean =
    state.isConnected && !state.isAgentTyping && !state.responseStatusUnknown && !state.isLoading &&
        state.currentSessionId != null &&
        state.outgoingDrafts.none { it.state == DraftState.QUEUED && it.savedSessionId == state.currentSessionId }

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val responseLedger: ResponseLedger = ResponseLedger(),
    /** Canonical rows awaiting exhaustive response coverage are persisted but not double-rendered. */
    val stagedCanonicalMessages: List<ChatMessage> = emptyList(),
    val currentSessionId: String? = null,
    val sessions: List<SessionUi> = emptyList(),
    val chatTitle: String = "Hermes",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isAgentTyping: Boolean = false,
    /** A lost socket cannot establish whether the server still runs this turn. */
    val responseStatusUnknown: Boolean = false,
    /** Restored cache can predate canonical steer tags; wait for authoritative transcript boundaries. */
    val replyBoundariesReady: Boolean = true,
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val isLoading: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val hasOlderMessages: Boolean = false,
    /**
     * Real per-turn tool-call budget (agent.max_turns) from GET /api/config.
     * Null when it could not be fetched — the tool-call dividers then degrade
     * to a bare count instead of a hardcoded default.
     */
    val maxToolCallsPerTurn: Int? = null,
    /** Standalone streaming message — rendered after the main list. */
    val streamingMessage: ChatMessage? = null,
    val errorMessage: String? = null,
    // Background job completion toast (issue #527) — non-blocking snackbar
    val backgroundCompleteMessage: String? = null,
    // Attachment feedback — surfaced as a non-blocking snackbar (issue #724)
    val openError: String? = null,
    val savingAttachmentPath: String? = null,
    // Gateway file currently being downloaded for open — drives the inline
    // loading indicator on the attachment card (issue #913 follow-up).
    val openingAttachmentPath: String? = null,
    val clarifyRequest: ClarifyUi? = null,
    // Sudo / secret prompts — surfaced as dialogs (issue #524)
    val sudoPrompt: SudoPromptUi? = null,
    val secretPrompt: SecretPromptUi? = null,
    val vaultPrompt: VaultPromptUi? = null,
    val showSessionPicker: Boolean = false,
    // /update confirm dialog (issue #862) — the command is handled client-side
    val updateConfirmOpen: Boolean = false,
    // Search state lives in ChatSearchState (searchDelegate.searchState) — a
    // snapshot-backed holder, so search updates don't recompose the whole UI.
    // Cached settings
    val typingEffectEnabled: Boolean = true,
    val typingEffectDelayMs: Int = 30,
    // Commands catalog
    val commandCatalog: CommandCatalog = CommandCatalog(),
    // Per-command usage counts for the slash-autocomplete ranking (issue
    // #865). Empty until the local store loads; commands without recorded
    // usage keep their catalog order.
    val slashUsageCounts: Map<String, Int> = emptyMap(),
    // Transient nav request from /resume and /history (issue #864): the
    // screen consumes it by navigating to the history tab, then clears it.
    val openHistoryRequested: Boolean = false,
    // In-session model picker (issue #589) — surfaced when the user types /model
    // (or taps the top-bar model chip). Mirror of the global model screen's
    // picker, but the selection hot-swaps the CURRENT session via the slash path.
    val showModelPicker: Boolean = false,
    val modelPickerProviders: List<ModelProvider> = emptyList(),
    val modelPickerPinned: List<PinnedModel> = emptyList(),
    val modelPickerLoading: Boolean = false,
    val modelPickerError: String? = null,
    val modelSwitchConfirmMessage: String? = null,
    // Current session's active model label (provider/model), shown in the chip
    val currentSessionModel: String? = null,
    // Per-model reasoning capabilities for the current session's model
    // (issue #946). Null when unknown — UI offers full scale.
    val currentModelCapabilities: ModelCapabilities? = null,
    // Reasoning effort level for the current session
    val reasoningLevel: String? = null,
    val terminalBackend: String? = null,
    // Context-window meter (issue #756): tokens currently used by the session
    // prompt (numerator) and the active model's full context window (denominator).
    // Both null until the first successful fetch.
    val usedContextTokens: Long? = null,
    val fullContextTokens: Long? = null,
    // Detailed token breakdown for the context meter's detail sheet (null until
    // the first successful session-detail fetch).
    val contextBreakdown: ContextBreakdown? = null,
    // How many times the current session has been context-compressed (null
    // until the first successful session.usage fetch) — drives the
    // "compressed ×N" badge on the context chip.
    val compressionCount: Int? = null,
    // Attachment state
    val pendingAttachments: List<Attachment> = emptyList(),
    /** One-shot composer recovery after an attachment is rejected before send. */
    val composerTextToRestore: String? = null,
    val outgoingDrafts: List<OutgoingDraft> = emptyList(),
    val activeDraftIds: Set<String> = emptySet(),
    val draftStorageError: Boolean = false,
    // Reaction animation — set when a reaction WS event arrives, auto-clears
    val reactionKind: String? = null,
    /** Monotonic trigger ID so consecutive same-kind reactions re-animate. */
    val reactionTriggerId: Long = 0L,
    /** Side-question state for /btw (issue #1015). */
    val btwState: BtwUiState? = null,
    /** Subagent delegation indicators (issue #538) — transient UI state. */
    val subagentIndicators: List<SubagentIndicator> = emptyList(),
    /** Agent todo / plan items (issue #736). */
    val todos: List<TodoItem> = emptyList(),
    // Session resume recovery (desktop parity: bounded auto-retry + error UI)
    val resumeError: String? = null,
    val isResumeRetrying: Boolean = false,
    /** Text staged to prefill the composer (e.g. from /undo). */
    val pendingPrefillText: String? = null,
) {
    /** Convenience — derived from [connectionStatus]. */
    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED
}

data class SessionUi(
    val id: String,
    val title: String,
    val messageCount: Int = 0,
    val parentSessionId: String? = null,
    val depth: Int = 0,
)

data class ClarifyQuestionUi(
    val qid: String = "q0",
    val question: String = "",
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)

data class ClarifyUi(
    val text: String,
    val options: List<String> = emptyList(),
    val clarifyId: String? = null,
    val questionId: String? = null,
    val multiSelect: Boolean = false,
    val questions: List<ClarifyQuestionUi> = emptyList(),
    val ref: RequestRef? = null,
    val questionStates: Map<String, com.m57.hermescontrol.domain.hermes.AttentionStatus> = emptyMap(),
    val questionLocks: Map<String, com.m57.hermescontrol.domain.hermes.ClarificationLock> = emptyMap(),
    val responseEnabled: Boolean = false,
    val lockedAnswers: Map<String, String> = emptyMap(),
) {
    fun canRespond(qid: String): Boolean =
        responseEnabled && qid !in questionLocks && qid !in lockedAnswers &&
            questionStates[qid] == com.m57.hermescontrol.domain.hermes.AttentionStatus.PENDING

    /**
     * Normalized list of questions to display. Guarantees at least one question
     * entry even for legacy single-question clarify events.
     */
    val resolvedQuestions: List<ClarifyQuestionUi>
        get() =
            if (questions.isNotEmpty()) {
                questions
            } else {
                listOf(
                    ClarifyQuestionUi(
                        qid = questionId ?: "q0",
                        question = text,
                        choices = options,
                        multiSelect = multiSelect,
                    ),
                )
            }
}

/**
 * State for the context-aware side-question bottom sheet (issue #1015, `/btw`).
 */
data class BtwUiState(
    val taskId: String? = null,
    val question: String = "",
    val answer: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * String sent to the agent when a clarify prompt is dismissed (the Dismiss
 * button). This is a *reject* — "I'm not answering this question" — NOT an
 * instruction to proceed. Deliberately NOT the CLI's interrupt sentinel
 * ("...Use your best judgement to proceed."): a mobile Dismiss is a
 * skip-the-question gesture, not an interrupt of the whole turn. The agent is
 * unblocked but told no answer was given, so it re-asks or backs off rather
 * than charging ahead.
 */
private const val CLARIFY_DISMISS_RESPONSE = com.m57.hermescontrol.domain.hermes.CLARIFICATION_DECLINED_ANSWER

/** Transient — not persisted. Holds a pending sudo.password request. */
data class SudoPromptUi(
    val requestId: String?,
    val sessionId: String?,
)

/** Transient — not persisted. Holds a pending secret (token/password) request. */
data class SecretPromptUi(
    val requestId: String?,
    val sessionId: String?,
    val envVar: String? = null,
    val prompt: String? = null,
)

/**
 * Token breakdown backing the context meter's detail sheet. All values are
 * cumulative lifetime token counts sourced from `GET /api/sessions/{id}`
 * (`input_tokens`, `output_tokens`, `cache_read_tokens`, `cache_write_tokens`,
 * `reasoning_tokens`, `message_count`) — verified present on the live
 * gateway's `sessions` table. Informational accounting only; the meter's
 * live used/full values come from the `session.context_breakdown` RPC
 * (issue #756).
 */
data class ContextBreakdown(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val reasoningTokens: Long,
    val messageCount: Int,
)

class ChatViewModel(
    application: Application,
    private val startCleanup: Boolean,
    repo: ChatPersistenceRepository =
        ChatPersistenceRepository(
            HermesDatabase.get(application).chatMessageDao(),
        ),
    slashUsageStore: SlashUsageStore = SlashUsageStore(application.applicationContext),
    searchDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val draftStore: OutgoingDraftStore =
        OutgoingDraftRepository(HermesDatabase.get(application).outgoingDraftDao()),
    private val submissionTransport: HermesSubmissionTransport = RichAnswerSubmissionTransport(DashboardRpcTransport()),
    private val attentionTransport: HermesAttentionTransport = DashboardRpcTransport(),
    private val attentionRepository: AttentionRepository =
        (application as? com.m57.hermescontrol.HermesControlApp)?.attentionRepository ?: AttentionRepository(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val streamingTestEnvironment: (() -> Boolean)? = null,
    private val streamingTokenClock: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, startCleanup = true)

    // ── Internal state ───────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(ChatUiState())

    private val _streamingState = MutableStateFlow(StreamingState())

    /** Maps an in-flight RPC id to its method for UI error labeling. */
    private val idToMethod = ConcurrentHashMap<String, String>()

    private data class SessionRequest(
        val generation: Long,
        val resumeSequence: Long = 0L,
        val sessionId: String? = null,
        val scope: ConnectionScope? = null,
        val streamVersion: Long = -1L,
        val snapshotStartedAt: Long = 0L,
        val resync: Boolean = false,
    )

    private val sessionRequestById = ConcurrentHashMap<String, SessionRequest>()
    private var sessionGeneration = 0L
    private val todoHistoryMemo = TodoHistoryMemo()

    private data class ActiveModelSwitch(
        val spec: String,
        val previousModel: String?,
    )

    private val pendingModelSwitchRequests = ConcurrentHashMap<String, ActiveModelSwitch>()
    private var activeModelSwitchConfirmation: ActiveModelSwitch? = null
    private var optimisticPreviousModel: String? = null

    /**
     * A picker selection awaiting server confirmation. The gateway can acknowledge `config.set`
     * while its agent is still building and then attach that build with the previous model, so an
     * acknowledgement alone is not proof; only a matching session info confirms the switch.
     */
    private data class ChatSelection(
        val scope: String,
        val generation: Long,
        val provider: String,
        val model: String,
        val spec: String = "",
        val previousModel: String? = null,
        val acknowledgedModel: String? = null,
        val reapplied: Boolean = false,
    ) {
        fun matches(
            infoModel: String?,
            infoProvider: String?,
        ): Boolean =
            infoModel != null && (infoModel == model || infoModel == acknowledgedModel) &&
                (infoProvider.isNullOrBlank() || infoProvider == provider)
    }

    private var pendingChatSelection: ChatSelection? = null

    private data class ReasoningSelection(
        val scope: String,
        val generation: Long,
        val level: String,
    )

    private val pendingReasoningSelections = ConcurrentHashMap<String, ReasoningSelection>()
    private var resumeRequestSequence = 0L
    private var activeResumeRequestSequence = 0L
    private var replayResyncJob: Job? = null
    private var replayResyncOwner: Pair<ConnectionScope, String>? = null
    private var hydrationRequestSequence = 0L
    private var activeHydrationRequestSequence = 0L
    private var resumedGeneration = -1L
    private var hydratedGeneration = -1L

    /** Runtime TUI session returned by session.resume; Desktop storage keeps the original ID. */
    private var runtimeSessionId: String? = null
    private var sessionScope: ConnectionScope? = null
        set(value) {
            if (field != value) todoHistoryMemo.clear()
            field = value
        }
    private var subagentSnapshotJob: Job? = null
    private var subagentSnapshotFailures = 0
    private var subagentSnapshotOwner: Pair<ConnectionScope, String>? = null
    private val streamObservationVersion =
        java.util.concurrent.atomic
            .AtomicLong()
    private val contextUsageRevision =
        java.util.concurrent.atomic
            .AtomicLong()
    private val draftOperationErrors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var editingDraftId: String? = null
    private var draftObserver: Job? = null
    private var observedDraftTarget: String? = null
    private var observedDraftScope: ConnectionScope? = null
    private val unsavedDrafts = ConcurrentHashMap<String, OutgoingDraft>()

    /** Issue #969: Prompt staged before session.create resolves. */
    private data class PendingPrompt(
        val draft: OutgoingDraft,
        val wasStreaming: Boolean,
        val userMessage: ChatMessage?,
        val queued: Boolean,
        val redirect: Boolean,
        val generation: Long,
        val opening: TurnOpening,
    )

    private var pendingInitialPrompt: PendingPrompt? = null
    private val additionalPendingPrompts = mutableListOf<PendingPrompt>()
    private var pendingPromptTimeout: Job? = null
    private val activePromptIntents = ConcurrentHashMap<String, OutgoingDraft>()
    private var hasObservedActiveResponse = false
    private var transcriptOwner: TranscriptOwner? = null
    private var transcriptOpening = false
    private var terminalCommitPending = false
    private var terminalCommitResponseId: String? = null
    private var observedTranscriptScope: ConnectionScope? = null
    private var observedTranscriptOrdinal: Long? = null

    private data class PendingTerminal(
        val event: WsEvent.MessageComplete,
        val observedAt: Long,
        val generation: Long,
        val scope: ConnectionScope,
        val responseId: String,
        val ordinal: Long,
    )

    private val pendingTerminals = mutableListOf<PendingTerminal>()

    // Collector completion is distinct from admission and from exact terminal evidence.
    // Only the ordered collector advances the stream cursor; a retained proof never does.
    private var completedTranscriptOrdinal: Long? = null
    private val scopedProcessingVersion = MutableStateFlow(0L)

    private fun terminalAlreadyHandled(original: com.m57.hermescontrol.data.ws.ScopedWsEvent): Boolean {
        val event = original.event as? WsEvent.MessageComplete ?: return false
        return pendingTerminals.any {
            it.scope == original.scope && it.generation == sessionGeneration &&
                it.ordinal == original.ordinal && it.event == event
        } ||
            _uiState.value.responseLedger.responses.any { response ->
                response.authority.opening?.observationProcessId == original.scope.processId &&
                    response.authority.terminals.any {
                        it.observation == original.ordinal && it.connectionGeneration == original.scope.generation &&
                            it.rowIds == event.persistedTurn?.rowIds &&
                            it.userRowId == event.persistedTurn?.userRowId &&
                            it.finalRowId == event.persistedTurn?.finalAssistantRowId
                    }
            }
    }

    /** Stream admission only: later ordinals do not prove that an earlier terminal was handled. */
    private fun admitScopedObservation(observation: com.m57.hermescontrol.data.ws.ScopedWsEvent): Boolean {
        if (observation.scope != submissionTransport.configuredScope()) return false
        if (observation.scope != observedTranscriptScope) {
            pendingTerminals.clear()
            observedTranscriptScope = observation.scope
            observedTranscriptOrdinal = null
            completedTranscriptOrdinal = null
            scopedProcessingVersion.value++
        }
        val previous = observedTranscriptOrdinal
        if (previous != null && observation.ordinal <= previous) return false
        observedTranscriptOrdinal = observation.ordinal
        if (observation.gap || (previous != null && observation.ordinal != previous + 1)) {
            _uiState.update { it.copy(responseLedger = it.responseLedger.gap()) }
            persistTranscript()
        }
        return true
    }

    private data class TranscriptCommit(
        val write: TranscriptWrite,
        val generation: Long,
        val terminalResponseId: String?,
    )

    private enum class TranscriptBarrier { ACCEPTED_INPUT, TERMINAL, SEALED_SEGMENT, NAVIGATION_FLUSH }

    private var transcriptOpenJob: Job? = null
    private val transcriptOperations = OrderedTranscriptOperations(viewModelScope)

    /** A fresh prompt put on the wire for this session generation. */
    private data class PendingAdmission(
        val generation: Long,
        val runtimeSessionId: String,
    )

    @Volatile private var pendingAdmission: PendingAdmission? = null
    private var refusedAdmission: PendingAdmission? = null

    /** Snapshot before an optimistic send opens the terminal fence. */
    private data class TurnOpening(
        val version: Long,
        val streamVersion: Long,
        val wasEnded: Boolean,
        val responseId: String? = null,
        val continuationId: String? = null,
        val ordinary: Boolean = false,
    )

    private val turnLifecycleVersion =
        java.util.concurrent.atomic
            .AtomicLong()

    // Set by a send that starts a turn; any stream frame afterwards proves admission.
    private var admissionUnproven = false

    // Only a create ACK, idle resume ACK or a terminal turn event releases a queue.
    private var queueExecutionIdle = false
    private var queueTurnObserved = false
    private val queueClaims = ConcurrentHashMap.newKeySet<String>()
    private var lastQueueTimestamp = 0L
    private var pendingCreateQueueOrigin: Pair<Long, String?>? = null

    /**
     * Whether the gateway has confirmed a persisted DB row for the current
     * session. `session.create` does NOT persist a row until the first prompt
     * (the gateway creates it lazily), so a freshly created session that has
     * never been prompted CANNOT be resumed — `session.resume` on its storage
     * key returns 4007 "session not found" and the REST transcript 404s.
     * The flag is cleared on create/switch and set once the row is confirmed
     * (REST 200, resume success, or a MessageStart = the server accepted a
     * prompt). Reconnects skip the doomed resume while it is false (issue:
     * 4007 "failed to load session" popup after tab switches).
     */
    private var sessionHasServerPresence = false

    /** Dedupe guard for [recoverGoneSession] (WS reject + REST 404 land together). */
    private var sessionGoneRecoveryInFlight = false

    /** Show the "session gone" notice once the recovery create lands (create wipes messages). */
    private var pendingGoneSessionNotice = false
    private var loadedMessageOffset = 0

    /**
     * True once the backend honored `order=latest` on the initial page (the
     * pagination echo came back). Offsets then count BACK from the newest
     * message and older pages INCREASE the offset; a full page means more
     * older messages exist. False on legacy backends (no `order` param) —
     * offsets stay absolute and decrease toward 0 (issue #859).
     */
    private var latestPaging = false
    private var isSyncingMessages = false

    // ── Session resume recovery (desktop parity) ────────────────────────
    // Bounded auto-retry with exponential backoff, mirroring the desktop's
    // use-route-resume: a failed session.resume retries 1s→2s→4s→8s up to
    // MAX_RESUME_RETRIES, then surfaces an explicit error + manual Retry
    // instead of latching the spinner forever.
    private var resumeRetrySessionId: String? = null
    private var resumeRetryAttempt = 0
    private var resumeRetryJob: Job? = null

    // Main-owned, exact-request deadline; never shared with other RPC methods.
    private var resumeDeadlineId: String? = null
    private var resumeDeadlineJob: Job? = null
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    /** Tracks the auto-clear coroutine for reaction animations. */
    private var reactionClearJob: Job? = null

    private val wsClient = HermesWsClient

    // ── Session persistence ──────────────────────────────────────────────
    private val repo: ChatPersistenceRepository = repo
    private val slashUsageStore: SlashUsageStore = slashUsageStore
    private val slashDispatcher = SlashCommandDispatcher()

    /**
     * Progress popup for `/update` from chat (issue #862). The backend `/update`
     * handler is interactive + session-exiting and can never answer the slash
     * worker (45s timeout), so the command is intercepted client-side and
     * routed through the same REST action + shared popup as the System screen.
     */
    val actionProgress = ActionProgressController(scope = viewModelScope)
    private val searchDelegate =
        ChatSearchDelegate(
            scope = viewModelScope,
            uiState = _uiState,
            dispatcher = searchDispatcher,
        )

    /** Snapshot-backed in-chat search state (see [ChatSearchDelegate]). */
    val searchState: ChatSearchState
        get() = searchDelegate.searchState
    private val attachmentsDelegate = ChatAttachmentsDelegate(uiState = _uiState)

    private val mediaDelegate =
        ChatMediaDelegate(
            uiState = _uiState,
            getApplication = { getApplication() },
            scope = viewModelScope,
            ioDispatcher = ioDispatcher,
        )

    /**
     * Model options cached from GET /api/model/options so the in-session model
     * picker (issue #589) opens instantly when the user types /model or taps the
     * top-bar chip. Preloaded at GatewayReady; fetched from cache on open if empty.
     */
    private var cachedModelOptions: List<ModelProvider> = emptyList()
    private var modelOptionsJob: Job? = null

    private val streamingController =
        ChatStreamingController(
            scope = viewModelScope,
            uiState = _uiState,
            streamingState = _streamingState,
            isCurrentSession = { sessionId -> isCurrentSession(sessionId) },
            isTestEnvironment = streamingTestEnvironment ?: { isTestEnvironment() },
            tokenClock = streamingTokenClock,
        )

    /** Read-only diagnostic; does not force VM/controller state or bypass stream admission. */
    internal fun pendingStreamingTokenCount(): Int = streamingController.pendingTokenCount

    // ── Public state ─────────────────────────────────────────────────────

    /** Identifiers only; Main-thread render memo keys must follow selection and connection epochs. */
    internal fun renderPlanningScope(): Any =
        _uiState.value.currentSessionId to
            Triple(sessionGeneration, sessionScope, submissionTransport.configuredScope())

    /**
     * Combined UI state: merges internal state with the WS connection status
     * flow so there is a single source of truth for connection state.
     */
    val uiState: StateFlow<ChatUiState> =
        combine(
            _uiState,
            wsClient.connectionStatus,
        ) { state, connStatus ->
            state.copy(connectionStatus = connStatus)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            _uiState.value,
        )

    /**
     * Session ID to resume when the WebSocket connects. Set synchronously by
     * [ChatScreen] via `SideEffect` during composition — before any WS event
     * can be processed. This prevents the race where [GatewayReady] fires
     * before ChatScreen's `LaunchedEffect` can call [switchSession], causing
     * [createNewSession] to create an empty chat that overwrites the
     * notification session (issue #240).
     */
    var initialSessionId: String? = null

    init {
        refreshDrafts()
        viewModelScope.launch {
            combine(_uiState, wsClient.connectionStatus) { state, _ -> state }.collect {
                drainOutgoingQueue()
            }
        }
        viewModelScope.launch {
            fun durableRows(rows: List<ChatMessage>): List<ChatMessage> =
                rows.map {
                    // These fields are deliberately transient in ChatMessageMapper.
                    it.copy(
                        approvalInfo = null,
                        clarifyInfo = null,
                        attachments = null,
                        toolOutputRiskData = null,
                        progressPreview = null,
                    )
                }
            var last: Triple<List<ChatMessage>, ResponseLedger, List<ChatMessage>>? = null
            _uiState.collect { state ->
                val structural =
                    Triple(
                        durableRows(state.messages),
                        state.responseLedger,
                        durableRows(state.stagedCanonicalMessages),
                    )
                if (structural != last) {
                    last = structural
                    persistTranscript()
                }
            }
        }
        observeStreamingCheckpoints()
        refreshSettings()
        refreshMaxToolCallsPerTurn()

        connectWebSocket(setLoading = false)
        viewModelScope.launch {
            wsClient.events.collect { event ->
                if (isTranscriptEvent(event)) return@collect
                try {
                    handleWsEvent(event)
                } catch (e: Exception) {
                    android.util.Log.e("ChatVM", "Uncaught in event loop", e)
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
        viewModelScope.launch {
            wsClient.scopedEvents.collect { observation ->
                val event = observation.event
                if (!admitScopedObservation(observation)) return@collect
                observation.gapSessionId?.let { resyncAfterReplayGap(observation.scope, it) }
                run {
                    com.m57.hermescontrol.data.transport
                        .dashboardAttentionSignal(
                            observation.scope,
                            event,
                            observation.ordinal,
                        )?.let(attentionRepository::apply)
                    if (isTranscriptEvent(event)) {
                        if (sessionScope == observation.scope && event != null &&
                            !(event is WsEvent.MessageComplete && terminalAlreadyHandled(observation))
                        ) {
                            handleWsEvent(event, eventOrdinal = observation.ordinal)
                        }
                        return@run
                    }
                    when (event) {
                        is WsEvent.VaultRequest -> {
                            val request = event.request
                            if (isCurrentSession(request.sessionId) &&
                                (request.kind != VaultPromptKind.LOGIN || request.origin?.let(::vaultOrigin) != null)
                            ) {
                                _uiState.update { state ->
                                    if (state.vaultPrompt?.request == request &&
                                        state.vaultPrompt.scope == observation.scope
                                    ) {
                                        state
                                    } else {
                                        state.copy(
                                            vaultPrompt = VaultPromptUi(observation.scope, request),
                                            isAgentTyping = false,
                                        )
                                    }
                                }
                            }
                        }

                        is WsEvent.ServerRequestCancel -> {
                            _uiState.update { state ->
                                val prompt = state.vaultPrompt
                                if (prompt?.scope == observation.scope && prompt.request.id == event.requestId &&
                                    prompt.request.sessionId == event.sessionId
                                ) {
                                    state.copy(vaultPrompt = null)
                                } else {
                                    state
                                }
                            }
                        }

                        is WsEvent.ApprovalRequest -> {
                            if (isCurrentAttentionSession(
                                    event.sessionId,
                                    observation.scope,
                                )
                            ) {
                                handleApprovalRequest(event, observation.scope)
                            }
                        }

                        is WsEvent.ClarifyRequest -> {
                            if (isCurrentAttentionSession(
                                    event.sessionId,
                                    observation.scope,
                                )
                            ) {
                                handleClarifyRequest(event, observation.scope)
                            }
                        }

                        is WsEvent.ClarifyExpire -> {
                            settleClarification(event.sessionId, event.clarifyId, observation.scope, resolved = false)
                        }

                        is WsEvent.ClarifyResolved -> {
                            settleClarification(event.sessionId, event.clarifyId, observation.scope, resolved = true)
                        }

                        is WsEvent.SessionInfo -> {
                            val runtimeId = event.data?.get("session_id") as? String
                            val approval = event.data?.get("pending_approval") as? Map<*, *>
                            if (isCurrentAttentionSession(runtimeId, observation.scope) && approval != null) {
                                handleApprovalRequest(parseApprovalMap(approval, runtimeId), observation.scope)
                            }
                        }

                        else -> {}
                    }
                }
                completedTranscriptOrdinal = observation.ordinal
                scopedProcessingVersion.value++
            }
        }
        viewModelScope.launch {
            attentionRepository.items.collect { refreshAttentionStates() }
        }
        viewModelScope.launch {
            wsClient.connectionScope.collect { scope ->
                scopedProcessingVersion.value++
                todoHistoryMemo.clear()
                refreshDrafts()
                _uiState.update { state ->
                    if (state.vaultPrompt != null &&
                        state.vaultPrompt.scope != scope
                    ) {
                        state.copy(vaultPrompt = null)
                    } else {
                        state
                    }
                }
                refreshAttentionStates()
            }
        }

        // B7 (Jun 30 2026, kanban t_connection_loading): clear loading state on connection failure or status change
        viewModelScope.launch {
            wsClient.connectionStatus.collect { status ->
                refreshDrafts()
                refreshAttentionStates()
                if (status == ConnectionStatus.DISCONNECTED ||
                    status == ConnectionStatus.RECONNECTING ||
                    status == ConnectionStatus.NO_NETWORK ||
                    status == ConnectionStatus.AUTH_EXPIRED
                ) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            vaultPrompt = null,
                            responseStatusUnknown =
                                it.responseStatusUnknown ||
                                    (it.isAgentTyping && hasObservedActiveResponse) ||
                                    activePromptIntents.values.any { draft -> draft.state == DraftState.UNKNOWN },
                            responseLedger = it.responseLedger.gap(),
                        )
                    }
                    // The runtime session id is only valid while the socket
                    // owns it — a dropped connection may mean the gateway
                    // closed/pruned the session (or restarted, wiping the
                    // runtime registry). Clear it so session-scoped RPCs can't
                    // fire with a stale id and 4001 "session not found";
                    // handleGatewayReady rebinds it on the re-resume.
                    queueExecutionIdle = false
                    replayResyncJob?.cancel()
                    replayResyncOwner = null
                    runtimeSessionId = null
                    activeResumeRequestSequence = ++resumeRequestSequence
                    cancelResumeDeadline()
                    releasePendingInitialPrompt()
                    // Fail any in-flight awaited RPCs so callers don't hang
                    // across the disconnect (delegated to HermesWsClient, issue #526).
                    wsClient.rejectAllPending()
                }
            }
        }
        // Desktop parity (requestFreshSession): when the profile switch
        // coordinator fires, wipe the open conversation. The re-dialed socket
        // then delivers gateway.ready → handleGatewayReady loads the new
        // profile's session list and auto-creates a FRESH session, so the
        // previous profile's context never leaks into the new profile's chat.
        viewModelScope.launch {
            ProfileSwitchCoordinator.switched
                .collect { _ ->
                    pendingGoneSessionNotice = false
                    sessionHasServerPresence = false
                    resetSessionState(sessionId = null, title = "Hermes", isLoading = true)
                }
        }
        // Same wipe when the CONNECTION profile changes (different server):
        // without it, gateway.ready on the re-dialed socket tries to resume
        // the OLD server's session on the NEW server and fails (split-brain
        // after connection-profile switch, reproduced live 2026-08-12).
        viewModelScope.launch {
            ProfileSwitchCoordinator.connectionSwitched
                .collect { _ ->
                    pendingGoneSessionNotice = false
                    sessionHasServerPresence = false
                    resetSessionState(sessionId = null, title = "Hermes", isLoading = true)
                }
        }
        // Slash-command usage ranking (issue #865): mirror the local usage
        // counts into state so the autocomplete can surface most-used
        // commands first. Best-effort — the store never throws.
        viewModelScope.launch {
            slashUsageStore.counts().collect { counts ->
                _uiState.update { it.copy(slashUsageCounts = counts) }
            }
        }
        // Several paths raise the spinner and rely on one WS reply to lower it
        // (profile switch waits for gateway.ready, an unsent resume never arms its
        // deadline). One watchdog covers them all instead of a timer per path.
        if (!isTestEnvironment()) {
            viewModelScope.launch {
                _uiState.map { it.isLoading }.distinctUntilChanged().collectLatest { loading ->
                    if (!loading) return@collectLatest
                    do {
                        delay(LOADING_WATCHDOG_MS)
                    } while (!recoverStuckLoading())
                }
            }
        }
        if (wsClient.connectionStatus.value == ConnectionStatus.CONNECTED) {
            handleGatewayReady()
        }
    }

    // ── Connection ───────────────────────────────────────────────────────

    private fun connectWebSocket(setLoading: Boolean = false) {
        // In loopback (token) mode the session token is the WS credential and
        // must be present before connecting. In gated (ticket) mode the ticket
        // is minted fresh by HermesWsClient.refreshWsTicketIfNeeded() from the
        // persisted session cookie, so getToken() is expected to be empty here
        // and must NOT block the connect (issue #640: chat showed "reconnect"
        // immediately after basic-auth login because this guard returned early).
        val isGated =
            runCatching { AuthManager.serverStore.getLatestState().wsAuthParam == "ticket" }
                .getOrNull() ?: false
        if (!isGated) {
            val token = AuthManager.getToken() ?: return
            if (token.isBlank()) return
        }

        // Don't disturb an already-working (or already-recovering) connection.
        // HermesWsClient is a global singleton shared by every tab; the chat tab
        // is recreated on every open, so calling connect() here must be a no-op
        // unless the singleton is in a terminal state. Re-entering connect() while
        // it is CONNECTING/RECONNECTING races the in-flight socket and can leave
        // the status stuck on RECONNECTING (see HermesWsClient.connect).
        val status = wsClient.connectionStatus.value
        if (status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.RECONNECTING ||
            status == ConnectionStatus.AUTH_EXPIRED
        ) {
            return
        }

        if (setLoading) {
            _uiState.update { it.copy(isLoading = true) }
        }

        viewModelScope.launch(ioDispatcher) {
            wsClient.connect()
        }

        // B7 (Jun 30 2026, kanban t_connection_loading): safety timeout to clear spinner if connection hangs
        if (!isTestEnvironment()) {
            viewModelScope.launch {
                delay(10_000L)
                if (_uiState.value.isLoading) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private var watchdogRetriedGeneration = -1L

    /** Returns false while a resume retry (bounded or the watchdog's own) still owns the spinner. */
    internal fun recoverStuckLoading(): Boolean {
        val state = _uiState.value
        if (!state.isLoading || state.resumeError != null) return true
        if (state.isResumeRetrying) return false
        Log.w(TAG, "Chat loading exceeded watchdog; recovering")
        when {
            wsClient.connectionStatus.value != ConnectionStatus.CONNECTED ->
                // The connection banner owns this state; never cover the chat with a spinner.
                _uiState.update { it.copy(isLoading = false) }
            state.currentSessionId == null ->
                // Missed gateway.ready (e.g. after a profile switch): redo its work on this socket.
                handleGatewayReady()
            watchdogRetriedGeneration != sessionGeneration -> {
                // A launch-time resume can stall while a manual Retry succeeds: do that Retry once.
                watchdogRetriedGeneration = sessionGeneration
                retryResumeSession()
                return false
            }
            state.messages.isEmpty() ->
                _uiState.update {
                    it.copy(isLoading = false, resumeError = "Session load timed out")
                }
            else -> _uiState.update { it.copy(isLoading = false) }
        }
        return true
    }

    // ── WS Event Handling ────────────────────────────────────────────────

    private fun handleGatewayReady() {
        if (BuildConfig.DEBUG) Log.d(TAG, "gatewayReady current=${_uiState.value.currentSessionId != null}")
        todoHistoryMemo.clear()
        queueExecutionIdle = false
        // A definitely-unsent queue entry may retry on a fresh binding, never UNKNOWN.
        // Claims for possibly-delivered requests survive reconnects.
        refreshDrafts()
        // A (re)connect is a fresh start: clear any stale resume error and
        // cancel a pending retry — the re-resume below rebinds the session
        // on the new socket (desktop parity: gatewayBecameOpen re-resumes
        // even when the route looks already active).
        _uiState.update { it.copy(isLoading = false, resumeError = null, isResumeRetrying = false) }
        cancelResumeRetry()
        addSystemMessage("Connected to Hermes")
        loadSessions()
        fetchCommandCatalog()
        preloadModelOptions()
        val currentId = _uiState.value.currentSessionId
        val requestedSession = NavigationController.pendingSessionId ?: initialSessionId
        if (!requestedSession.isNullOrBlank() && requestedSession != currentId) {
            initialSessionId = null
            switchSession(requestedSession)
            return
        }
        if (currentId != null) {
            if (sessionHasServerPresence) {
                resumeSession(currentId, sessionGeneration)
            } else {
                // Issue #969: No server-side row yet — the gateway dropped the
                // ephemeral unpersisted session when the old socket closed.
                // Re-create the session on the new socket so runtimeSessionId is
                // refreshed and ready for prompts.
                createNewSession(setLoading = false, queueOrigin = currentId)
            }
        } else {
            val initial = initialSessionId
            if (!initial.isNullOrBlank()) {
                initialSessionId = null
                switchSession(initial)
            } else {
                openMainConversation()
            }
        }
    }

    /** Outcome of looking up the profile's forever "Bot Chat". */
    internal sealed interface MainConversation {
        data class Found(
            val sessionId: String,
        ) : MainConversation

        /** The profile has no Bot Chat yet. */
        data object Absent : MainConversation

        /** Unknown (error, timeout, older server): never create a second Bot Chat on a guess. */
        data object Unknown : MainConversation
    }

    /** Exact-title lookup resolved to the live compression tip. Unit tests keep the legacy start. */
    internal var mainConversationResolver: suspend () -> MainConversation = {
        if (isTestEnvironment()) {
            MainConversation.Unknown
        } else {
            withTimeoutOrNull(MAIN_CONVERSATION_LOOKUP_MS) {
                val result = sendRpcAndAwait(WsMethods.SESSION_LIST, mapOf("title" to BOT_CHAT_TITLE))
                val sessions = (result.toJsonElement().toAny() as? Map<*, *>)?.get("sessions") as? List<*>
                // An older server ignores `title` and lists everything: trust only the exact row.
                val row = (sessions?.firstOrNull() as? Map<*, *>)?.takeIf { it["title"] == BOT_CHAT_TITLE }
                val id = ((row?.get("resolved_id") ?: row?.get("id")) as? String)?.takeIf(String::isNotBlank)
                when {
                    id != null -> MainConversation.Found(id)
                    sessions != null && sessions.isEmpty() -> MainConversation.Absent
                    else -> MainConversation.Unknown
                }
            } ?: MainConversation.Unknown
        }
    }

    /**
     * A cold start with no explicit target continues the one main conversation instead of
     * minting a new session each launch (AUTONOMOUS_WORKER.md §8). A missing Bot Chat is
     * created under that title; an unknown lookup falls back to a plain new session.
     */
    private fun openMainConversation() {
        val generation = sessionGeneration
        viewModelScope.launch {
            var lookup = runCatching { mainConversationResolver() }
            // A read right after the socket opens can be refused at once; ask once more.
            if (lookup.getOrNull() !is MainConversation.Found && lookup.getOrNull() != MainConversation.Absent) {
                delay(MAIN_CONVERSATION_RETRY_MS)
                lookup = runCatching { mainConversationResolver() }
            }
            val target = lookup.getOrDefault(MainConversation.Unknown)
            if (BuildConfig.DEBUG) {
                val failure = lookup.exceptionOrNull()?.javaClass?.simpleName
                Log.d(TAG, "mainConversation ${target::class.simpleName} $failure")
            }
            // The user (or a notification) may have opened something while we looked.
            if (generation != sessionGeneration || _uiState.value.currentSessionId != null ||
                NavigationController.pendingSessionId != null
            ) {
                return@launch
            }
            when (target) {
                is MainConversation.Found -> switchSession(target.sessionId)
                MainConversation.Absent -> createNewSession(setLoading = false, title = BOT_CHAT_TITLE)
                MainConversation.Unknown -> createNewSession(setLoading = false)
            }
        }
    }

    private fun correlatedResponses(event: WsEvent.MessageComplete): List<ResponseRecord> =
        event.persistedTurn
            ?.let { proof ->
                _uiState.value.responseLedger.responses.filter { response ->
                    val facts = response.authority
                    val submissions =
                        listOfNotNull(facts.opening?.submissionId, facts.queued?.submissionId) +
                            facts.controls.mapNotNull { it.submissionId }
                    val rows = listOfNotNull(facts.opening?.userRowId) + facts.controls.mapNotNull { it.userRowId }
                    (proof.submissionId != null && proof.submissionId in submissions) ||
                        (listOfNotNull(proof.userRowId) + proof.userRowIds.orEmpty()).any { it in rows } ||
                        (
                            facts.opening == null && facts.queued?.userRowId != null &&
                                facts.queued.userRowId == proof.userRowId
                        )
                }
            }.orEmpty()

    private fun correlatedResponse(event: WsEvent.MessageComplete): ResponseRecord? =
        correlatedResponses(event).singleOrNull()

    private fun terminalEvidence(
        event: WsEvent,
        time: Long,
        ordinal: Long,
    ): TerminalEvidence {
        val complete = event as? WsEvent.MessageComplete
        val receipt = complete?.persistedTurn
        return TerminalEvidence(
            time,
            ordinal,
            receipt?.rowIds.orEmpty(),
            receipt?.userRowId,
            receipt?.finalAssistantRowId,
            receipt?.submissionId,
            receipt?.complete,
            complete?.partial,
            complete?.responseTransformed,
            complete?.status,
            sessionScope?.generation,
        )
    }

    private fun retryPendingTerminals() {
        val pending = pendingTerminals.toList()
        pendingTerminals.clear()
        for (terminal in pending) {
            if (terminal.generation != sessionGeneration || terminal.scope != sessionScope ||
                !submissionTransport.isReady(terminal.scope) || !isCurrentSession(terminal.event.sessionId)
            ) {
                continue
            }
            if (correlatedResponse(terminal.event) != null) {
                handleWsEvent(terminal.event, terminal.observedAt, terminal.ordinal)
            } else if (terminal.responseId in _uiState.value.responseLedger.pendingSubmissions.values) {
                pendingTerminals.add(terminal)
            }
        }
    }

    private fun handleWsEvent(
        event: WsEvent,
        observedAt: Long = clock(),
        eventOrdinal: Long = observedTranscriptOrdinal ?: 0L,
    ) {
        if (event is WsEvent.SessionUsage && isCurrentSession(event.sessionId)) {
            // A push is newer than any meter request already in flight.
            contextUsageRevision.incrementAndGet()
        }
        // Scope the entire transition, including buffer flushing and resets.
        // Reducer filtering alone cannot protect partial reasoning from the
        // ViewModel's post-reducer terminal-event effects.
        val transitionSessionId =
            when (event) {
                is WsEvent.MessageStart -> event.sessionId
                is WsEvent.MessageToken -> event.sessionId
                is WsEvent.ThinkingDelta -> event.sessionId
                is WsEvent.ReasoningDelta -> event.sessionId
                is WsEvent.MessageComplete -> event.sessionId
                is WsEvent.MessageDone -> event.sessionId
                is WsEvent.MessageInterim -> event.sessionId
                is WsEvent.MessageSegmentEnd -> event.sessionId
                is WsEvent.ToolStart -> event.sessionId
                is WsEvent.ToolComplete -> event.sessionId
                is WsEvent.SubagentEvent -> event.sessionId
                else -> null
            }
        if (!isCurrentSession(transitionSessionId)) return
        if (event is WsEvent.MessageStart &&
            (
                _uiState.value.responseLedger.activeId == null ||
                    _uiState.value.responseLedger.responses.any {
                        it.id == _uiState.value.responseLedger.activeId &&
                            it.finishedAt != null
                    }
            )
        ) {
            // A remotely observed run has identity, but no invented exhaustive historical coverage.
            _uiState.update { it.copy(responseLedger = it.responseLedger.open().gap()) }
        }
        val receipt = (event as? WsEvent.MessageComplete)?.persistedTurn
        var currentLedger = _uiState.value.responseLedger
        val matches = (event as? WsEvent.MessageComplete)?.let(::correlatedResponses).orEmpty()
        if (matches.size > 1) {
            _uiState.update { state ->
                state.copy(
                    responseLedger =
                        state.responseLedger.copy(
                            responses =
                                state.responseLedger.responses.map {
                                    if (it.id in
                                        matches.map { match -> match.id }
                                    ) {
                                        it.copy(
                                            authority =
                                                it.authority.revoke(
                                                    "terminal-binding",
                                                    matches
                                                        .map { match ->
                                                            match.id
                                                        }.sorted()
                                                        .toString(),
                                                ),
                                        )
                                    } else {
                                        it
                                    }
                                },
                        ),
                )
            }
            persistTranscript()
            return
        }
        val correlated = matches.singleOrNull()
        val queuedExecution =
            correlated?.authority?.queued != null && correlated.finishedAt == null &&
                currentLedger.responses.none {
                    it.id != correlated.id && it.authority.opening != null &&
                        it.finishedAt == null
                }
        if (queuedExecution) {
            currentLedger = currentLedger.copy(activeId = correlated!!.id)
            _uiState.update { it.copy(responseLedger = currentLedger) }
        }
        val freshOpening =
            currentLedger.responses
                .singleOrNull { it.id == currentLedger.activeId }
                ?.authority
                ?.opening
        val compatibleFirstTerminal =
            !currentLedger.requiresCorrelation && freshOpening != null &&
                freshOpening.disposition == "STREAMING" &&
                freshOpening.connectionGeneration == sessionScope?.generation &&
                freshOpening.observationProcessId == sessionScope?.processId

        val canonicalWindowAllowed =
            event is WsEvent.MessageComplete && receipt?.finalAssistantRowId != null &&
                event.partial != true && event.responseTransformed != true &&
                (event.status == null || event.status == "complete")
        if (!currentLedger.requiresCorrelation && freshOpening == null && correlated == null &&
            event is WsEvent.MessageComplete &&
            event.sessionId != null && sessionScope != null &&
            currentLedger.activeId in currentLedger.pendingSubmissions.values &&
            (receipt?.submissionId != null || receipt?.userRowId != null || !receipt?.userRowIds.isNullOrEmpty())
        ) {
            pendingTerminals.add(
                PendingTerminal(
                    event,
                    observedAt,
                    sessionGeneration,
                    sessionScope!!,
                    currentLedger.activeId!!,
                    eventOrdinal,
                ),
            )
        }
        if ((event is WsEvent.MessageComplete || event is WsEvent.MessageDone) &&
            currentLedger.requiresCorrelation && correlated == null
        ) {
            val scope = sessionScope
            val responseId = currentLedger.activeId
            if (event is WsEvent.MessageComplete && event.sessionId != null && scope != null &&
                scope == submissionTransport.configuredScope() && responseId != null &&
                responseId in currentLedger.pendingSubmissions.values &&
                (receipt?.submissionId != null || receipt?.userRowId != null || !receipt?.userRowIds.isNullOrEmpty())
            ) {
                // The worker may finish before prompt.submit returns its admission IDs.
                // Retain only explicitly addressed evidence in this exact observation lifetime.
                pendingTerminals.add(
                    PendingTerminal(event, observedAt, sessionGeneration, scope, responseId, eventOrdinal),
                )
                return
            }
            // A no-key server queue cannot open a reply, but its observed final prose must not disappear.
            val unboundQueuedBody =
                (event as? WsEvent.MessageComplete)
                    ?.takeIf { complete ->
                        complete.text.isNotBlank() && complete.responseReused != true &&
                            currentLedger.responses.any { it.authority.queued != null && it.authority.opening == null }
                    }?.let { ChatMessage(role = MessageRole.ASSISTANT, content = it.text, timestamp = observedAt) }
            _uiState.update {
                it.copy(
                    responseStatusUnknown = true,
                    responseLedger = it.responseLedger.gap(),
                    messages = it.messages + listOfNotNull(unboundQueuedBody),
                )
            }
            persistTranscript()
            return
        }
        val terminalReplay =
            correlated ?: currentLedger.responses.singleOrNull {
                // Reuse only the fresh-first compatibility attribution, before any
                // later send/start. Safety enrichment must bypass reducer/queue effects.
                event is WsEvent.MessageComplete && receipt == null &&
                    (
                        event.partial == true || event.responseTransformed == true ||
                            (event.status != null && event.status != "complete")
                    ) &&
                    compatibleFirstTerminal && _streamingState.value.turnEnded &&
                    freshOpening?.observation == sessionGeneration &&
                    !_uiState.value.responseStatusUnknown &&
                    it.id == currentLedger.activeId && it.finishedAt != null &&
                    // Terminal safety attribution does not depend on current body coverage.
                    it.authority.terminals.isNotEmpty() && !currentLedger.blocksAuthority(it.id)
            }
        if (terminalReplay != null && (
                terminalReplay.id != currentLedger.activeId ||
                    terminalReplay.finishedAt != null
            )
        ) {
            // An attributed old terminal cannot finalize, flush or overwrite the current stream.
            _uiState.update {
                it.copy(
                    responseLedger =
                        it.responseLedger
                            .update(terminalReplay.id) { old ->
                                old.admitTerminal(terminalEvidence(event, observedAt, eventOrdinal)).copy(
                                    canonicalRowIds =
                                        if (receipt?.complete ==
                                            true
                                        ) {
                                            receipt.rowIds
                                        } else {
                                            (old.canonicalRowIds + receipt?.rowIds.orEmpty()).distinct()
                                        },
                                    canonicalFinalAssistantRowId =
                                        old.canonicalFinalAssistantRowId ?: receipt?.finalAssistantRowId,
                                    canonicalComplete = receipt?.complete ?: old.canonicalComplete,
                                    canonicalWindowAllowed = old.canonicalWindowAllowed || canonicalWindowAllowed,
                                )
                            }.revokeExclusiveClaims(),
                )
            }
            persistTranscript()
            return
        }
        // Actionable questions must retain the socket observation's original identity.
        if (event is WsEvent.ClarifyRequest || event is WsEvent.ClarifyExpire ||
            event is WsEvent.ClarifyResolved
        ) {
            return
        }
        val segmentFrame = event is WsEvent.MessageInterim || event is WsEvent.MessageSegmentEnd
        if (event is WsEvent.MessageStart || event is WsEvent.MessageToken ||
            event is WsEvent.ThinkingDelta || event is WsEvent.ReasoningDelta ||
            event is WsEvent.MessageComplete || event is WsEvent.MessageDone ||
            event is WsEvent.ToolStart || event is WsEvent.ToolComplete || segmentFrame
        ) {
            streamObservationVersion.incrementAndGet()
            admissionUnproven = false
        }
        // RpcError is reduced before ViewModel request handling. Drop stale
        // session errors here so the shared reducer cannot clear loading or
        // surface an error for a newly selected session.
        if (event is WsEvent.RpcError && (!idToMethod.containsKey(event.id) || isStaleSessionRequest(event.id))) {
            forgetRequest(event.id)
            return
        }

        // Flush any throttled reasoning before a state transition so the
        // finalized/orphan message carries the latest reasoning text.
        when (event) {
            is WsEvent.MessageStart,
            is WsEvent.MessageComplete,
            is WsEvent.MessageDone,
            is WsEvent.ToolStart,
            is WsEvent.MessageInterim,
            is WsEvent.MessageSegmentEnd,
            -> {
                streamingController.flushPendingReasoning()
                // Issue #842: the token buffer can hold deltas that landed
                // <33ms before the transition. The reducer seals the
                // streaming message into the orphan at tool.start — flush
                // first so the seal carries the COMPLETE narration (a
                // truncated seal fails the later REST dedupe and ghosts).
                streamingController.flushPendingTokens()
            }

            else -> {}
        }

        // Frames of an ended turn stay fenced until a legitimate send/start.
        val lateFrame = _streamingState.value.turnEnded && event !is WsEvent.MessageStart
        // First, let the reducer compute the new state and any effects
        var result =
            ChatWsEventReducer.reduce(
                _uiState.value,
                _streamingState.value,
                event,
                runtimeSessionId ?: _uiState.value.currentSessionId,
                hydrateTodos = todoHistoryMemo::hydrate,
            )

        // Optimistic local input is not evidence that a remote response is running.
        if (event is WsEvent.MessageStart || event is WsEvent.MessageToken ||
            event is WsEvent.ThinkingDelta || event is WsEvent.ReasoningDelta ||
            event is WsEvent.ToolStart || event is WsEvent.ToolComplete ||
            event is WsEvent.MessageComplete || event is WsEvent.MessageDone || segmentFrame
        ) {
            hasObservedActiveResponse = result.state.isAgentTyping
        }
        if (!lateFrame &&
            (
                event is WsEvent.MessageStart || event is WsEvent.MessageToken ||
                    event is WsEvent.ThinkingDelta || event is WsEvent.ReasoningDelta ||
                    event is WsEvent.ToolStart || event is WsEvent.ToolComplete || segmentFrame
            )
        ) {
            queueTurnObserved = true
            queueExecutionIdle = false
        }
        val queueTurnEnded =
            queueTurnObserved && transitionSessionId != null &&
                (event is WsEvent.MessageComplete || event is WsEvent.MessageDone)
        if (queueTurnEnded) queueTurnObserved = false
        // Apply the new state
        val ownedIds = newNonUserMemberIds(_uiState.value.messages, result.state.messages)
        var ledger = _uiState.value.responseLedger.observe(ownedIds)
        if ((event is WsEvent.MessageComplete || event is WsEvent.MessageDone) && !lateFrame) {
            ledger =
                ledger.update(ledger.activeId) {
                    (
                        if (correlated != null ||
                            compatibleFirstTerminal
                        ) {
                            it.admitTerminal(terminalEvidence(event, observedAt, eventOrdinal))
                        } else {
                            it.copy(
                                finishedAt = it.finishedAt ?: observedAt,
                                coverage = false,
                                authority = it.authority.copy(local = null, deliveryGap = true),
                            )
                        }
                    ).copy(
                        canonicalRowIds =
                            if (receipt?.complete ==
                                true
                            ) {
                                receipt.rowIds
                            } else {
                                (it.canonicalRowIds + receipt?.rowIds.orEmpty()).distinct()
                            },
                        canonicalFinalAssistantRowId = it.canonicalFinalAssistantRowId ?: receipt?.finalAssistantRowId,
                        canonicalWindowAllowed = it.canonicalWindowAllowed || canonicalWindowAllowed,
                        canonicalComplete = receipt?.complete ?: it.canonicalComplete,
                        userRowIds =
                            (
                                it.userRowIds +
                                    listOfNotNull(
                                        receipt?.userRowId,
                                    ) + receipt?.userRowIds.orEmpty()
                            ).distinct(),
                    )
                }
        }
        // A new member or an explicitly selected previewed interim can identify the terminal row.
        // Fully sealed/reused prose without that delivery proof must remain unbound.
        val finalRow = receipt?.finalAssistantRowId
        val finalized = result.streamingState.finalizedMessage
        val finalOwner = ledger.responses.singleOrNull { it.id == ledger.activeId }
        val finalFacts = finalOwner?.authority
        val finalOpening = finalFacts?.opening
        // Selection differs; binding and lossless collision handling do not. An ordinary
        // terminal must have created this member, whereas preview needs its observed identity.
        val selectedFinal =
            finalized != null &&
                (
                    finalized.id in ownedIds ||
                        (
                            event is WsEvent.MessageComplete && event.responsePreviewed == true &&
                                finalized.id == _streamingState.value.previewedFinalCandidateId
                        )
                )
        val safeFinalBinding =
            selectedFinal && finalized != null && finalRow != null && finalRow in receipt?.rowIds.orEmpty() &&
                finalOpening != null && finalFacts != null && sessionScope != null &&
                finalized.id in finalOwner.messageIds &&
                result.state.messages.count { it.id == finalized.id && it.role == MessageRole.ASSISTANT } == 1 &&
                ledger.responses.count { finalized.id in it.messageIds } == 1 &&
                finalOpening.target == sessionScope?.draftTargetKey() &&
                finalOpening.session == _uiState.value.currentSessionId &&
                finalOpening.connectionGeneration == sessionScope?.generation &&
                finalOpening.observationProcessId == sessionScope?.processId &&
                finalFacts.terminals.isNotEmpty() &&
                finalFacts.terminals.all { it.safe } && finalFacts.revocations.isEmpty() &&
                ledger
                    .revokeExclusiveClaims()
                    .responses
                    .singleOrNull { it.id == finalOwner.id }
                    ?.authority
                    ?.revocations
                    ?.isEmpty() == true &&
                ledger.aliases.none { (row, member) -> row != finalRow.toString() && member == finalized.id } &&
                ledger.responses.none { other ->
                    other.id != finalOwner.id &&
                        (
                            finalRow in other.canonicalRowIds || other.canonicalFinalAssistantRowId == finalRow ||
                                other.authority.terminals.any { finalRow in it.rowIds || it.finalRowId == finalRow }
                        )
                }
        if (!lateFrame && canonicalWindowAllowed && event is WsEvent.MessageComplete &&
            event.responseReused != true && correlated?.id == ledger.activeId && safeFinalBinding &&
            finalRow != null && finalized != null && finalOpening != null &&
            (ledger.aliases[finalRow.toString()] == null || ledger.aliases[finalRow.toString()] == finalized.id)
        ) {
            val canonicalFinalId = canonicalMessageId(finalOpening.target, finalOpening.session, finalRow)
            val collision =
                canonicalFinalId != finalized.id &&
                    (result.state.messages + result.state.stagedCanonicalMessages).any { it.id == canonicalFinalId }
            val fold =
                if (collision) {
                    foldPreviewedFinalCollision(
                        result.state.messages,
                        result.state.stagedCanonicalMessages,
                        ledger,
                        finalized.id,
                        canonicalFinalId,
                    )
                } else {
                    null
                }
            if (!collision || fold != null) {
                if (fold != null) {
                    result =
                        result.copy(
                            state =
                                result.state.copy(
                                    messages = fold.messages,
                                    stagedCanonicalMessages = fold.stagedMessages,
                                ),
                            streamingState = result.streamingState.copy(finalizedMessage = fold.finalizedMessage),
                        )
                }
                ledger = ledger.copy(aliases = ledger.aliases + (finalRow.toString() to finalized.id))
            }
        }
        // Install the requirement before publishing state or admitting work: either collector
        // or the writer may run immediately on Main.immediate.
        if (!lateFrame && ledger.activeId != null &&
            (event is WsEvent.MessageComplete || event is WsEvent.MessageDone)
        ) {
            terminalCommitPending = true
            terminalCommitResponseId = ledger.activeId
        }
        _uiState.update { result.state.copy(responseLedger = ledger.revokeExclusiveClaims()) }
        _streamingState.update { result.streamingState }
        if (event !is WsEvent.MessageToken && event !is WsEvent.ThinkingDelta &&
            event !is WsEvent.ReasoningDelta && event !is WsEvent.ToolProgress
        ) {
            when (event) {
                is WsEvent.MessageComplete, is WsEvent.MessageDone -> {
                    commitTranscript(TranscriptBarrier.TERMINAL)
                }

                is WsEvent.MessageInterim, is WsEvent.MessageSegmentEnd -> {
                    commitTranscript(
                        TranscriptBarrier.SEALED_SEGMENT,
                    )
                }

                else -> {
                    persistTranscript()
                }
            }
        }

        // Process side-effects from the reducer
        for (effect in result.effects) {
            when (effect) {
                // Saved above as one structural snapshot, never independently row-first.
                is ReducerEffect.PersistMessage -> {
                    Unit
                }

                is ReducerEffect.CreateNewSession -> {
                    createNewSession()
                }

                is ReducerEffect.LoadSessions -> {
                    loadSessions()
                }

                is ReducerEffect.RefreshSessions -> {
                    loadSessions()
                }

                is ReducerEffect.RefreshContextUsage -> {
                    // Streaming finished — refresh the context meter now rather
                    // than waiting up to 5s for the next session-sync poll.
                    viewModelScope.launch { fetchContextUsage() }
                }

                is ReducerEffect.AttachHostMedia -> {
                    // Issue #724: turn host-path MEDIA: directives into real
                    // attachments (images inline, every other file tappable)
                    // via the gateway /api/files/download endpoint. Works on a
                    // remote phone too.
                    viewModelScope.launch(ioDispatcher) {
                        mediaDelegate.attachHostMedia(effect.sessionId, effect.messageId)
                    }
                }
            }
        }

        // Handle complex events that need ViewModel-specific context
        when (event) {
            is WsEvent.GatewayReady -> {
                handleGatewayReady()
            }

            is WsEvent.SessionInfo -> {
                // Session info pushed by backend when config changes
                // (model switch, reasoning level, etc.)
                val info = event.data
                if (info != null) {
                    val model = info["model"] as? String
                    val provider = info["provider"] as? String
                    val reasoningEffort = info["reasoning_effort"] as? String
                    val terminalBackend = info["terminal_backend"] as? String
                    val newModelLabel =
                        if (model != null && provider != null) {
                            "$provider/$model"
                        } else {
                            model
                        }
                    // Issue #817: on a REAL model swap the meter's denominator
                    // still belongs to the old model until the next fetch.
                    // Blank it so the chip hides instead of flashing a stale
                    // window under the new label, and re-fire the fetch so the
                    // new window lands immediately (no 30s poll wait). Only a
                    // change from a known label counts — the first SessionInfo
                    // of a session just sets the label.
                    val previousLabel = _uiState.value.currentSessionModel
                    val modelSwapped =
                        previousLabel != null && newModelLabel != null && newModelLabel != previousLabel
                    _uiState.update { state ->
                        state.copy(
                            currentSessionModel = newModelLabel ?: state.currentSessionModel,
                            reasoningLevel =
                                if (reasoningEffort.isNullOrEmpty()) {
                                    null
                                } else {
                                    reasoningEffort
                                },
                            terminalBackend = terminalBackend ?: state.terminalBackend,
                            fullContextTokens = if (modelSwapped) null else state.fullContextTokens,
                        )
                    }
                    syncCurrentModelCapabilities()
                    reconcileModelSelection(model, provider)
                    if (modelSwapped) {
                        // Issue #817: after a swap the REST model/info window is
                        // PROFILE-scoped and may describe the old model (e.g. a
                        // session-scoped swap) — the meter must not fall back to
                        // it. Wait for the RPC's live context_max instead; the
                        // chip stays hidden until the real window lands.
                        viewModelScope.launch { fetchContextUsage(skipRestFallback = true) }
                    }
                    // Pending approvals are reconciled by the scoped event collector.
                }
            }

            is WsEvent.MessageToken -> {
                streamingController.handleMessageToken(event)
            }

            is WsEvent.ThinkingDelta -> {
                streamingController.handleThinkingDelta(event)
            }

            is WsEvent.ReasoningDelta -> {
                streamingController.handleReasoningDelta(event)
            }

            is WsEvent.MessageStart -> {
                // The server accepted a prompt for this session — its DB row
                // now exists (created lazily at prompt.submit), so a reconnect
                // resume will succeed.
                sessionHasServerPresence = true
                streamingController.beginStreamingMessage()
            }

            is WsEvent.MessageComplete -> {
                // Buffers cleared before reduce; ViewModel resets them after
                streamingController.resetStreaming()
            }

            is WsEvent.MessageDone -> {
                streamingController.resetStreaming()
            }

            is WsEvent.ToolStart -> {
                // Issue #771: the reducer keeps the streaming message (and its
                // reasoning) alive across the tool call so the finalized answer
                // retains the thinking card. Only the token buffers are cleared
                // here — resetStreaming() would wipe streamingMessage +
                // reasoningText and re-introduce the mid-turn reasoning vanish.
                streamingController.clearStreamingBuffers()
            }

            is WsEvent.MessageInterim -> {
                // The sealed segment must not re-flush into the next live message.
                streamingController.clearStreamingBuffers()
            }

            is WsEvent.RpcResult -> {
                handleRpcResult(event.id, event.result)
            }

            is WsEvent.RpcError -> {
                handleRpcError(event.id, event.error)
            }

            is WsEvent.SessionUpdated -> {
                loadSessions()
            }

            is WsEvent.ClarifyRequest -> {
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                        responseStatusUnknown = false,
                    )
                }
                _streamingState.update { StreamingState() }
                streamingController.resetStreaming()
            }

            is WsEvent.ApprovalRequest -> {
                // Approval actions are observed only from the socket-scoped stream.
            }

            is WsEvent.SudoRequest -> {
                handleSudoRequest(event)
            }

            is WsEvent.SudoExpire -> {
                handleSudoExpire(event)
            }

            is WsEvent.SecretRequest -> {
                handleSecretRequest(event)
            }

            is WsEvent.SecretExpire -> {
                handleSecretExpire(event)
            }

            is WsEvent.GatewayError -> {
                // Reducer already set errorMessage. tui_gateway reports a refused
                // admission only as this session's `error` (no message.start or
                // complete); any stream frame since the submit proves admission,
                // so other errors never end a running turn.
                val admission = pendingAdmission
                if (admission != null && event.sessionId == admission.runtimeSessionId &&
                    admission.generation == sessionGeneration && admissionUnproven && _uiState.value.isAgentTyping
                ) {
                    // Refusal is not authoritative idle evidence for already queued input.
                    queueExecutionIdle = false
                    queueTurnObserved = false
                    refusedAdmission = admission
                    pendingAdmission = null
                    admissionUnproven = false
                    hasObservedActiveResponse = false
                    _streamingState.update { it.copy(turnEnded = true) }
                    _uiState.update { it.copy(isAgentTyping = false, responseStatusUnknown = false) }
                }
            }

            is WsEvent.BackgroundComplete -> {
                // Reducer already set backgroundCompleteMessage; the UI observes
                // it via a LaunchedEffect and triggers the snackbar.
            }

            is WsEvent.ReactionEvent -> {
                // Cancel any previous auto-clear to avoid race (agy finding #1)
                reactionClearJob?.cancel()
                _uiState.update {
                    it.copy(
                        reactionKind = event.kind,
                        reactionTriggerId = it.reactionTriggerId + 1L,
                    )
                }
                // Auto-clear after the animation duration
                reactionClearJob =
                    viewModelScope.launch {
                        delay(2_000L)
                        _uiState.update { it.copy(reactionKind = null) }
                    }
            }

            else -> { /* reducer handles these */ }
        }
        drainOutgoingQueue()
    }

    // ── Message streaming ────────────────────────────────────────────────

    /**
     * Checks if an incoming WS event belongs to the currently active
     * session. Returns true if the event should be processed.
     */
    private fun isCurrentAttentionSession(
        eventSessionId: String?,
        scope: ConnectionScope?,
    ): Boolean =
        !eventSessionId.isNullOrBlank() && eventSessionId == runtimeSessionId &&
            scope != null && scope == sessionScope && scope == submissionTransport.configuredScope()

    private fun isCurrentSession(eventSessionId: String?): Boolean {
        // If the event has no session ID, process it (legacy compatibility)
        if (eventSessionId == null) return true
        return eventSessionId == runtimeSessionId || eventSessionId == _uiState.value.currentSessionId
    }

    // ── RPC response handling ────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun handleRpcResult(
        id: String,
        result: Any?,
    ) {
        cancelResumeDeadline(id)
        val method = idToMethod.remove(id) ?: return
        val request = sessionRequestById.remove(id)
        if (request != null && isStaleSessionRequest(request)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "drop stale $method ${staleReason(request)}")
            return
        }
        when (method) {
            WsMethods.SESSION_CREATE -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val runtimeId = resultMap["session_id"] as? String ?: return
                val storageId = resultMap["stored_session_id"] as? String ?: runtimeId
                val queuedOrigin = pendingCreateQueueOrigin?.takeIf { it.first == sessionGeneration }?.second
                pendingCreateQueueOrigin = null
                runtimeSessionId = runtimeId
                sessionScope = request?.scope
                queueExecutionIdle = true
                bindNewSessionQueue(queuedOrigin, storageId)
                // The gateway persists the row lazily on the first prompt —
                // do not resume this key until presence is confirmed.
                sessionHasServerPresence = false
                sessionGoneRecoveryInFlight = false
                _uiState.update {
                    it.copy(
                        currentSessionId = storageId,
                        isLoading = false,
                        messages = if (pendingInitialPrompt != null) it.messages else emptyList(),
                        chatTitle = "Hermes",
                        usedContextTokens = null,
                        fullContextTokens = null,
                        contextBreakdown = null,
                        compressionCount = null,
                    )
                }
                // A gone-session recovery just landed — announce it now that
                // the message list has been reset by the create.
                if (pendingGoneSessionNotice) {
                    pendingGoneSessionNotice = false
                    addSystemMessage("Previous session is no longer available on the server — starting a new chat")
                }
                // Mirror the active session id app-wide so session-scoped
                // drawer screens (e.g. Processes, issue #532) can issue
                // session-scoped RPCs. See ActiveSessionHolder.
                ActiveSessionHolder.setScoped(runtimeId, storageId, sessionScope)
                _streamingState.update { StreamingState() }
                openTranscriptCache(storageId, sessionGeneration, load = false)
                addSystemMessage("Session created", persist = true)
                loadSessions()
                fetchContextUsage()

                drainPendingPrompts(storageId, runtimeId)
            }

            WsMethods.SESSION_BRANCH -> {
                val resultMap = result as? Map<String, Any?> ?: return
                // The result carries BOTH ids: `session_id` is the runtime
                // registry id, `stored_session_id` is the DB key. currentSessionId
                // must stay the storage key — storing the runtime id here made
                // every later resume 4007 "session not found" (the DB lookup
                // misses) and the REST transcript 404.
                val runtimeId = resultMap["session_id"] as? String ?: return
                val storageId = resultMap["stored_session_id"] as? String ?: runtimeId
                val generation =
                    resetSessionState(
                        sessionId = storageId,
                        title = (resultMap["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Hermes",
                        isLoading = false,
                    )
                runtimeSessionId = runtimeId
                sessionScope = request?.scope
                ActiveSessionHolder.setScoped(runtimeId, storageId, sessionScope)
                sessionHasServerPresence = false
                sessionGoneRecoveryInFlight = false
                openTranscriptCache(storageId, generation, load = false)
                addSystemMessage("Session branched", persist = true)
                loadSessionMessages(storageId, generation)
                loadSessions()
                fetchContextUsage()
            }

            WsMethods.SESSION_LIST -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val sessionsList = resultMap["sessions"] as? List<Map<String, Any?>> ?: return
                val sessions =
                    sessionsList.map { s ->
                        SessionUi(
                            id = s["id"] as? String ?: "",
                            title = s["title"] as? String ?: "Untitled",
                            messageCount = (s["message_count"] as? Double)?.toInt() ?: 0,
                        )
                    }
                _uiState.update { state ->
                    val newTitle = sessions.find { s -> s.id == state.currentSessionId }?.title
                    state.copy(
                        sessions = sessions,
                        chatTitle = newTitle ?: state.chatTitle,
                    )
                }
            }

            WsMethods.SESSION_RESUME -> {
                applySessionResume(result, request)
            }

            WsMethods.SESSION_INTERRUPT -> {
                // Issue #842 follow-up: seal whatever the agent streamed so far
                // (interim commentary + partial answer) BEFORE clearing the
                // streaming state. The old tool.start orphan seal used to leave
                // pre-tool text behind on interrupt; with that seal gone, the
                // partial would otherwise vanish entirely.
                sealStreamingMessageIfAny()
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                        responseStatusUnknown = false,
                    )
                }
                _streamingState.update { StreamingState(turnEnded = true) }
                streamingController.resetStreaming()
                addSystemMessage("Session interrupted")
            }

            WsMethods.COMMANDS_CATALOG -> {
                val map = result as? Map<*, *> ?: return
                val catalog = parseCommandCatalog(map)
                if (catalog != null) {
                    _uiState.update { it.copy(commandCatalog = catalog) }
                }
            }

            WsMethods.COMMAND_DISPATCH -> {
                handleDispatchResult(result)
            }

            WsMethods.APPROVAL_RESPOND -> {
                val map = result as? Map<*, *>
                val resolved = (map?.get("resolved") as? Number)?.toInt() ?: 0
                if (resolved > 0) {
                    addSystemMessage("Approval submitted")
                }
            }

            WsMethods.APPROVAL_PENDING -> {
                handleApprovalPendingResult(result)
            }

            WsMethods.CONFIG_SET -> {
                val pending = pendingModelSwitchRequests.remove(id)
                val reasoning = pendingReasoningSelections.remove(id)
                val map = result as? Map<*, *> ?: return
                val key = map["key"] as? String
                if (key == "reasoning" && reasoning != null && reasoning.generation == sessionGeneration &&
                    reasoning.scope == AuthManager.uiPreferenceScope()
                ) {
                    if (map["value"] == reasoning.level) {
                        _uiState.update { it.copy(reasoningLevel = reasoning.level) }
                        AuthManager.writeUiPreference(reasoning.scope, "chat_reasoning", reasoning.level)
                    } else {
                        _uiState.update { it.copy(errorMessage = "推論強度の変更結果を確認できません。セッションを更新してください。") }
                    }
                }
                if (key == "model") {
                    val confirmRequired = map["confirm_required"] as? Boolean ?: false
                    if (confirmRequired) {
                        val confirmMessage =
                            (map["confirm_message"] as? String)
                                ?: (map["warning"] as? String)
                                ?: "This model requires confirmation to switch. Continue?"
                        activeModelSwitchConfirmation = pending
                        _uiState.update {
                            it.copy(modelSwitchConfirmMessage = confirmMessage)
                        }
                    } else if (pending != null && map["deferred"] != true) {
                        // A deferred (mid-turn) pick is applied by the gateway at the next turn
                        // start and reported through session info; only a live apply can be
                        // silently dropped by a concurrent agent build.
                        pendingChatSelection =
                            pendingChatSelection
                                ?.takeIf { it.spec == pending.spec && it.generation == sessionGeneration }
                                ?.copy(acknowledgedModel = map["value"] as? String ?: "")
                                ?: pendingChatSelection
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleDispatchResult(result: Any?) {
        val map = result.toJsonElement().toAny() as? Map<*, *> ?: return
        val type = map["type"] as? String ?: return
        when (type) {
            "send" -> {
                val message = map["message"] as? String ?: ""
                submitPrompt(message)
            }

            "exec" -> {
                val output = map["output"] as? String ?: map["message"] as? String ?: ""
                addAssistantMessage(output)
            }

            "skill" -> {
                val message = map["message"] as? String ?: ""
                submitPrompt(message)
            }

            "plugin" -> {
                val output = map["output"] as? String ?: ""
                addAssistantMessage(output)
            }

            "alias" -> {
                val target = map["target"] as? String ?: return
                handleSlashCommand(target)
            }

            "prefill" -> {
                val message = map["message"] as? String ?: ""
                val notice = map["notice"] as? String ?: ""
                handlePrefillResult(message, notice)
            }

            else -> {
                val output = map["output"] as? String ?: map.toString()
                addAssistantMessage(output)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applySessionResume(
        result: Any?,
        request: SessionRequest?,
    ) {
        val resultMap = result as? Map<String, Any?>
        val runtimeId = (resultMap?.get("session_id") as? String)?.takeIf { it.isNotBlank() }
        val storedId = request?.sessionId ?: _uiState.value.currentSessionId
        val resumedId = resultMap?.get("resumed") as? String
        val snapshotStoredId = resultMap?.get("stored_session_id") as? String
        if (runtimeId == null || storedId == null || (resumedId != null && resumedId != storedId) ||
            (request?.resync == true && snapshotStoredId != null && snapshotStoredId != storedId)
        ) {
            releasePendingInitialPrompt()
            storedId?.let {
                handleResumeFailure(
                    it,
                    request?.generation ?: sessionGeneration,
                    "Invalid resume acknowledgement",
                    resyncScope = request?.scope?.takeIf { request.resync },
                )
            }
            return
        }
        runtimeSessionId = runtimeId
        sessionScope = request?.scope
        reconcileResumedExecution(resultMap, request)
        // Resume succeeded — the gateway confirmed the DB row.
        sessionHasServerPresence = true
        val sessionId =
            request?.sessionId
                ?: (resultMap?.get("resumed") as? String)
                ?: _uiState.value.currentSessionId

        // Parse session info from backend — model, provider, reasoning_effort
        val infoMap = resultMap?.get("info") as? Map<String, Any?>
        val model = infoMap?.get("model") as? String
        val provider = infoMap?.get("provider") as? String
        val reasoningEffort = infoMap?.get("reasoning_effort") as? String
        val terminalBackend = infoMap?.get("terminal_backend") as? String

        // B8 (Jun 20 2026, kanban t_session_resume): do NOT reload
        // cached messages here — switchSession() already did so before
        // the WS round-trip. Calling loadCachedMessages() here would
        // overwrite any message the user sent between switchSession() and
        // the server ack, making the chat appear to go blank.
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = null,
                currentSessionId = sessionId,
                currentSessionModel =
                    if (model != null && provider != null) {
                        "$provider/$model"
                    } else {
                        model ?: it.currentSessionModel
                    },
                reasoningLevel =
                    if (reasoningEffort.isNullOrEmpty()) {
                        null
                    } else {
                        reasoningEffort
                    },
                terminalBackend = terminalBackend ?: it.terminalBackend,
            )
        }
        syncCurrentModelCapabilities()
        // Mirror the active runtime session id app-wide (issue #532).
        ActiveSessionHolder.setScoped(runtimeSessionId ?: sessionId, sessionId, sessionScope)
        request?.scope?.let {
            if (request.resync) wsClient.adoptReplaySnapshot(it, runtimeId, resultMap)
            wsClient.restoreVaultPrompts(it, resultMap, runtimeId, request.snapshotStartedAt)
        }
        if (request?.resync != true) addSystemMessage("Session resumed")
        fetchContextUsage()
        val generation = request?.generation ?: sessionGeneration
        resumedGeneration = generation
        finishResumeWhenHydrated(generation)
        if (request?.resync != true) drainPendingPrompts(storedId, runtimeId)
        // Reconnect replay: resume payload can carry `pending_approval`
        // (server `_session_info_payload`); surface it, then ask for
        // the full queue in case more are parked.
        val pendingApproval = resultMap?.get("pending_approval") as? Map<*, *>
        if (pendingApproval != null && request?.scope != null) {
            handleApprovalRequest(parseApprovalMap(pendingApproval, runtimeSessionId), request.scope)
        }
        val activeSessionId = runtimeSessionId ?: sessionId
        if (activeSessionId != null) replayPendingApproval(activeSessionId)
    }

    private fun handleRpcError(
        id: String,
        error: Any?,
    ) {
        cancelResumeDeadline(id)
        val method = idToMethod.remove(id) ?: return
        pendingReasoningSelections.remove(id)
        val failedModelSwitch = pendingModelSwitchRequests.remove(id)
        val request = sessionRequestById.remove(id)
        if (request != null && isStaleSessionRequest(request)) return
        val errorMsg =
            when (error) {
                is Map<*, *> -> error["message"] as? String ?: error.toString()
                is JsonRpcError -> error.message
                else -> error.toString()
            }

        if (failedModelSwitch != null) {
            revertModelSelection(failedModelSwitch.previousModel, "モデルを切り替えられませんでした: $errorMsg")
            return
        }

        // Session resume failures go through the bounded retry (desktop
        // parity) instead of a one-shot snackbar — the session may be
        // mid-flush on the gateway or the WS may have just rebound, and a
        // brief backoff usually clears it. Persistent failure ends in the
        // explicit error + Retry state.
        if (method == WsMethods.SESSION_RESUME) {
            val sessionId = request?.sessionId ?: _uiState.value.currentSessionId
            if (sessionId != null) {
                val generation = request?.generation ?: sessionGeneration
                if (resumedGeneration == generation) resumedGeneration = -1L
                handleResumeFailure(sessionId, generation, errorMsg)
            }
            return
        }

        if (method == WsMethods.SESSION_CREATE) {
            val pending = pendingInitialPrompt
            releasePendingInitialPrompt()
            if (pending != null) {
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                        responseStatusUnknown = false,
                        errorMessage = "Failed to create session: $errorMsg",
                    )
                }
            }
        }

        // Surface error in UI (these are server-pushed RpcError for
        // fire-and-forget RPCs — awaited RPCs handle their own failure
        // via the HermesWsClient.request() deferred).
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = "Error ($method): $errorMsg",
            )
        }
    }

    // ── Send message ─────────────────────────────────────────────────────

    /**
     * Send a user prompt, uploading any pending attachments to the backend
     * first via their dedicated RPC methods.
     *
     * Flow:
     * 1. Snapshot pending attachments (then clear them from UI)
     * 2. Add user message to UI immediately (optimistic UX)
     * 3. Persist a draft. Hold initial input only while the same connected session is being created.
     * 4. Snapshot all attachments, then persist UNKNOWN before the first wire write.
     * 5. Await fixed-scope attachment and prompt confirmations without automatic retries.
     * 6. Cache the confirmed user message; otherwise retain the draft for manual review.
     */
    fun sendMessage(
        text: String,
        steer: Boolean = false,
    ) {
        if (text.isBlank() && _uiState.value.pendingAttachments.isEmpty()) return
        val trimmed = text.trim()
        // PR06a: restored drafts contain literal prompt text, including /queue payloads.
        if (editingDraftId == null && trimmed.startsWith("/", ignoreCase = true)) {
            if (isModelPickerCommand(trimmed)) {
                openModelPicker(refresh = trimmed.removePrefix("/").trim().equals("model --refresh", ignoreCase = true))
            } else {
                handleSlashCommand(trimmed)
            }
            return
        }
        val attachments = _uiState.value.pendingAttachments.toList()
        val oversized = attachments.firstOrNull { it.size > MAX_CHAT_ATTACHMENT_BYTES }
        if (oversized != null) {
            _uiState.update {
                it.copy(
                    errorMessage = attachmentTooLargeMessage(oversized),
                    composerTextToRestore = text,
                )
            }
            return
        }
        val state = _uiState.value
        val scope = submissionTransport.configuredScope()
        if (activePromptIntents.values.any {
                scope != null && it.targetKey == scope.draftTargetKey() &&
                    it.savedSessionId == state.currentSessionId &&
                    it.text == text && it.attachments == attachments
            }
        ) {
            return
        }
        val wasStreaming = state.isAgentTyping
        val knownActive = wasStreaming && hasObservedActiveResponse && !state.responseStatusUnknown
        val hasQueue =
            state.outgoingDrafts.any {
                it.state == DraftState.QUEUED && it.savedSessionId == state.currentSessionId
            }
        if ((!steer || !knownActive || attachments.isNotEmpty()) &&
            (
                wasStreaming || state.responseStatusUnknown || scope == null ||
                    !submissionTransport.isReady(scope) || hasQueue || runtimeSessionId == null ||
                    state.isLoading || (!queueExecutionIdle && !knownActive && !canRetryRefusedAdmission())
            )
        ) {
            enqueuePrompt(text, attachments)
            return
        }
        val queueNewInput = state.responseStatusUnknown || (wasStreaming && !knownActive)
        val message =
            ChatMessage(
                role = MessageRole.USER,
                content = text,
                timestamp = clock(),
                attachments =
                    attachments.takeIf {
                        it.isNotEmpty()
                    },
            )
        clearAttachments()
        val turnOpening = openTurnLifecycle(awaitsAdmission = !knownActive)
        _uiState.update { it.copy(messages = it.messages + message, isAgentTyping = true) }
        stagePrompt(
            text,
            attachments,
            wasStreaming,
            message,
            turnOpening,
            queued = queueNewInput,
            redirect = knownActive && attachments.isEmpty(),
        )
    }

    /**
     * One-tap send from a native component. Unlike [sendMessage] it never reads or clears the
     * composer's pending attachments or the draft being edited, never queues, and refuses while a
     * reply is generating, offline, loading, or other input is waiting. Returns whether it sent.
     */
    fun sendNativeAction(text: String): Boolean {
        // Connection status is merged into uiState asynchronously; read the live value here.
        val state = _uiState.value.copy(connectionStatus = wsClient.connectionStatus.value)
        if (text.isBlank() || !canSendNativeAction(state)) return false
        val scope = sessionScope ?: submissionTransport.configuredScope() ?: return false
        if (!submissionTransport.isReady(scope) || runtimeSessionId == null ||
            (!queueExecutionIdle && !canRetryRefusedAdmission())
        ) {
            return false
        }
        // A second tap before the first reply starts is the same intent: drop it.
        if (activePromptIntents.values.any {
                it.targetKey == scope.draftTargetKey() && it.savedSessionId == state.currentSessionId && it.text == text
            }
        ) {
            return false
        }
        val message = ChatMessage(role = MessageRole.USER, content = text, timestamp = clock())
        val turnOpening = openTurnLifecycle(awaitsAdmission = true)
        _uiState.update { it.copy(messages = it.messages + message, isAgentTyping = true) }
        stagePrompt(text, emptyList(), false, message, turnOpening, useEditingDraft = false)
        return true
    }

    /**
     * A legitimately sent or resumed turn opens a fresh lifecycle, so its tokens
     * (even without message.start) are accepted while late frames of an ended
     * turn stay fenced until then. A send that starts a turn [awaitsAdmission]
     * until a stream frame proves the gateway admitted it.
     */
    private fun openTurnLifecycle(awaitsAdmission: Boolean): TurnOpening {
        val continuation =
            _uiState.value.responseLedger.let { ledger ->
                ledger.responses
                    .singleOrNull {
                        it.id ==
                            ledger.activeId &&
                            it.finishedAt == null
                    }?.id
            }
        if (awaitsAdmission) _uiState.update { it.copy(responseLedger = it.responseLedger.open()) }
        val opening =
            TurnOpening(
                turnLifecycleVersion.incrementAndGet(),
                streamObservationVersion.get(),
                _streamingState.value.turnEnded,
                _uiState.value.responseLedger.activeId,
                continuation,
                awaitsAdmission,
            )
        if (awaitsAdmission) {
            admissionUnproven = true
            pendingAdmission = null
        }
        refusedAdmission = null
        _streamingState.update { if (it.turnEnded) it.copy(turnEnded = false) else it }
        return opening
    }

    /** Explicit new intent may retry a refusal; queued intent still needs fresh idle evidence. */
    private fun canRetryRefusedAdmission(): Boolean =
        refusedAdmission?.let {
            it.generation == sessionGeneration && it.runtimeSessionId == runtimeSessionId
        } == true

    private fun nextQueueTimestamp(): Long {
        lastQueueTimestamp =
            maxOf(
                System.currentTimeMillis(),
                lastQueueTimestamp + 1,
                (_uiState.value.outgoingDrafts.maxOfOrNull { it.createdAt } ?: 0) + 1,
            )
        return lastQueueTimestamp
    }

    /** User-submitted follow-ups are durable intent, separate from ambiguous wire writes. */
    private fun enqueuePrompt(
        text: String,
        attachments: List<Attachment>,
    ) {
        val scope = sessionScope ?: submissionTransport.configuredScope()
        if (scope == null) {
            _uiState.update {
                it.copy(
                    composerTextToRestore = text,
                    errorMessage = getApplication<Application>().getString(R.string.draft_target_unavailable),
                )
            }
            return
        }
        val existing =
            _uiState.value.outgoingDrafts.firstOrNull {
                it.id == editingDraftId && it.state == DraftState.DRAFT && it.targetKey == scope.draftTargetKey()
            }
        val draft =
            OutgoingDraft(
                id =
                    existing?.id ?: java.util.UUID
                        .randomUUID()
                        .toString(),
                scope = scope,
                savedSessionId = _uiState.value.currentSessionId,
                runtimeSessionId = null,
                text = text,
                attachments = attachments,
                createdAt = nextQueueTimestamp(),
                state = DraftState.QUEUED,
                queued = true,
            )
        editingDraftId = null
        clearAttachments()
        activePromptIntents[draft.id] = draft
        _uiState.update { it.copy(activeDraftIds = it.activeDraftIds + draft.id) }
        val generation = sessionGeneration
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { draftStore.save(draft) }
                val boundId = _uiState.value.currentSessionId
                if (generation == sessionGeneration && draft.savedSessionId == null && boundId != null &&
                    sessionScope?.draftTargetKey() == draft.targetKey
                ) {
                    withContext(ioDispatcher) { draftStore.save(draft.copy(savedSessionId = boundId)) }
                }
                unsavedDrafts.remove(draft.id)
                queueClaims.remove(draft.id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // A failed durable save must not become an automatic job.
                retainUnstoredDraft(
                    draft.copy(state = DraftState.DRAFT),
                    null,
                    _uiState.value.isAgentTyping,
                    generation,
                )
            } finally {
                finishDraftAttempt(draft.id)
            }
        }
    }

    /** Bind only the unpersisted conversation being re-created, never another saved chat. */
    private fun bindNewSessionQueue(
        origin: String?,
        storageId: String,
    ) {
        val scope = sessionScope ?: return
        val drafts =
            _uiState.value.outgoingDrafts.filter {
                it.state == DraftState.QUEUED && (it.savedSessionId == origin || it.savedSessionId == null) &&
                    it.scope == scope && it.id !in _uiState.value.activeDraftIds
            }
        drafts.forEach { draft ->
            activePromptIntents[draft.id] = draft
            _uiState.update { it.copy(activeDraftIds = it.activeDraftIds + draft.id) }
            viewModelScope.launch {
                try {
                    withContext(ioDispatcher) { draftStore.save(draft.copy(savedSessionId = storageId)) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    draftOperationErrors[draft.id] = draft.targetKey
                    _uiState.update { it.copy(draftStorageError = true) }
                } finally {
                    finishDraftAttempt(draft.id)
                }
            }
        }
    }

    private fun drainOutgoingQueue() {
        val state = _uiState.value
        val scope = submissionTransport.configuredScope() ?: return
        val storageId = state.currentSessionId ?: return
        val runtimeId = runtimeSessionId ?: return
        if (!queueExecutionIdle || state.isAgentTyping || state.responseStatusUnknown || state.isLoading ||
            sessionScope != scope || !submissionTransport.isReady(scope) ||
            state.clarifyRequest != null || state.sudoPrompt != null || state.secretPrompt != null ||
            state.vaultPrompt != null ||
            state.activeDraftIds.isNotEmpty() || state.draftStorageError
        ) {
            return
        }
        val draft =
            state.outgoingDrafts.firstOrNull {
                it.state == DraftState.QUEUED && it.savedSessionId == storageId &&
                    it.scope == scope
            } ?: return
        // Claim before launching; observer emissions and repeated terminal events cannot duplicate it.
        if (!queueClaims.add(draft.id)) return
        queueExecutionIdle = false
        queueTurnObserved = false
        val outgoing = draft.copy(scope = scope)
        activePromptIntents[draft.id] = outgoing
        val message =
            ChatMessage(
                role = MessageRole.USER,
                content = draft.text,
                timestamp = clock(),
                attachments = draft.attachments.takeIf { it.isNotEmpty() },
            )
        val turnOpening = openTurnLifecycle(awaitsAdmission = true)
        _uiState.update {
            it.copy(
                activeDraftIds = it.activeDraftIds + draft.id,
                messages = it.messages + message,
                isAgentTyping = true,
            )
        }
        dispatchPrompt(outgoing, false, storageId, runtimeId, message, true, false, sessionGeneration, turnOpening)
    }

    fun queueDraft(id: String) {
        val draft = _uiState.value.outgoingDrafts.firstOrNull { it.id == id } ?: return
        val scope = submissionTransport.configuredScope() ?: return
        if (draft.state != DraftState.DRAFT || id in _uiState.value.activeDraftIds ||
            draft.targetKey != scope.draftTargetKey()
        ) {
            return
        }
        val queued =
            draft.copy(
                scope = scope,
                state = DraftState.QUEUED,
                queued = true,
                createdAt = nextQueueTimestamp(),
            )
        activePromptIntents[id] = draft
        _uiState.update { it.copy(activeDraftIds = it.activeDraftIds + id) }
        if (draft.savedSessionId == null) {
            createNewSession()
        } else if (draft.savedSessionId != _uiState.value.currentSessionId) {
            switchSession(draft.savedSessionId)
        }
        val generation = sessionGeneration
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { draftStore.save(queued) }
                val boundId = _uiState.value.currentSessionId
                if (generation == sessionGeneration && draft.savedSessionId == null && boundId != null &&
                    sessionScope?.draftTargetKey() == draft.targetKey
                ) {
                    withContext(ioDispatcher) {
                        draftStore.save(queued.copy(savedSessionId = boundId))
                    }
                }
                clearDraftOperationError(id)
                queueClaims.remove(id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                draftOperationErrors[id] = draft.targetKey
                _uiState.update { it.copy(draftStorageError = true) }
            } finally {
                finishDraftAttempt(id)
            }
        }
    }

    /** Persist intent before any wire write. Ordinary drafts are never automatic jobs. */
    private fun stagePrompt(
        text: String,
        attachments: List<Attachment>,
        wasStreaming: Boolean,
        message: ChatMessage?,
        turnOpening: TurnOpening,
        queued: Boolean = false,
        redirect: Boolean = false,
        useEditingDraft: Boolean = true,
    ) {
        val scope = sessionScope ?: submissionTransport.configuredScope()
        if (scope == null) {
            _uiState.update {
                it.copy(
                    composerTextToRestore = text,
                    pendingAttachments = attachments + it.pendingAttachments,
                    messages = it.messages.filterNot { row -> row.id == message?.id },
                    isAgentTyping = wasStreaming,
                    errorMessage = getApplication<Application>().getString(R.string.draft_target_unavailable),
                )
            }
            return
        }
        val existing =
            _uiState.value.outgoingDrafts.firstOrNull {
                useEditingDraft && it.id == editingDraftId && it.state == DraftState.DRAFT &&
                    it.targetKey == scope.draftTargetKey()
            }
        val effectiveQueued = (existing?.queued ?: queued) || _uiState.value.responseStatusUnknown
        val effectiveRedirect = redirect && !effectiveQueued
        if (useEditingDraft) editingDraftId = null
        val draft =
            OutgoingDraft(
                id =
                    existing?.id ?: java.util.UUID
                        .randomUUID()
                        .toString(),
                scope = scope,
                savedSessionId = _uiState.value.currentSessionId,
                runtimeSessionId = runtimeSessionId,
                text = text,
                attachments = attachments,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                queued = effectiveQueued,
            )
        val generation = sessionGeneration
        activePromptIntents[draft.id] = draft
        _uiState.update { state ->
            state.copy(
                responseLedger =
                    state.responseLedger.copy(
                        pendingInputs =
                            state.responseLedger.pendingInputs +
                                listOfNotNull(message?.id?.let { it to (turnOpening.responseId ?: "") }).toMap(),
                        pendingSubmissions =
                            state.responseLedger.pendingSubmissions + (draft.id to (turnOpening.responseId ?: "")),
                    ),
            )
        }
        _uiState.update { it.copy(activeDraftIds = it.activeDraftIds + draft.id) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { draftStore.save(draft) }
                unsavedDrafts.remove(draft.id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                retainUnstoredDraft(draft, message, wasStreaming, generation)
                finishDraftAttempt(draft.id)
                return@launch
            }
            if (generation != sessionGeneration || !submissionTransport.isReady(scope)) {
                forgetUnsentAuthority(draft.id, message?.id, generation)
                removeUnsentBubble(message, wasStreaming, generation)
                finishDraftAttempt(draft.id)
                return@launch
            }
            val storageId = _uiState.value.currentSessionId
            val runtimeId = runtimeSessionId
            if (storageId == null || runtimeId == null) {
                // Session creation may finish while the durable save is suspended. Recheck above;
                // only this same connected scope may dispatch after its creation ACK.
                val pending =
                    PendingPrompt(
                        draft,
                        wasStreaming,
                        message,
                        effectiveQueued,
                        effectiveRedirect,
                        generation,
                        turnOpening,
                    )
                if (pendingInitialPrompt == null) {
                    pendingInitialPrompt = pending
                    pendingPromptTimeout =
                        viewModelScope.launch {
                            delay(5_000)
                            releasePendingInitialPrompt()
                        }
                } else {
                    additionalPendingPrompts += pending
                }
                return@launch
            }
            if (sessionScope != scope) {
                forgetUnsentAuthority(draft.id, message?.id, generation)
                removeUnsentBubble(message, wasStreaming, generation)
                finishDraftAttempt(draft.id)
                return@launch
            }
            dispatchPrompt(
                draft,
                wasStreaming,
                storageId,
                runtimeId,
                message,
                effectiveQueued,
                effectiveRedirect,
                generation,
                turnOpening,
            )
        }
    }

    private fun dispatchPrompt(
        draft: OutgoingDraft,
        wasStreaming: Boolean,
        storageSessionId: String,
        agentSessionId: String,
        userMessage: ChatMessage?,
        queued: Boolean,
        redirect: Boolean,
        dispatchGeneration: Long,
        turnOpening: TurnOpening,
    ) {
        val outgoing = draft.copy(savedSessionId = storageSessionId, runtimeSessionId = agentSessionId)
        _uiState.update { state ->
            val capturedId = turnOpening.responseId
            val ledger =
                if (turnOpening.ordinary && capturedId != null &&
                    state.responseLedger.responses.none { it.id == capturedId }
                ) {
                    state.responseLedger.copy(
                        responses = state.responseLedger.responses + ResponseRecord(id = capturedId),
                        activeId = capturedId,
                        requiresCorrelation =
                            state.responseLedger.requiresCorrelation || state.responseLedger.responses.isNotEmpty(),
                    )
                } else {
                    state.responseLedger
                }
            state.copy(
                responseLedger =
                    ledger.copy(
                        pendingInputs =
                            ledger.pendingInputs +
                                listOfNotNull(userMessage?.id?.let { it to (turnOpening.responseId ?: "") }).toMap(),
                        pendingSubmissions =
                            state.responseLedger.pendingSubmissions + (draft.id to (turnOpening.responseId ?: "")),
                        possibleContinuations =
                            state.responseLedger.possibleContinuations +
                                listOfNotNull(turnOpening.continuationId?.let { draft.id to it }).toMap(),
                    ),
            )
        }
        val session =
            SessionRef(
                agentSessionId,
                // v1 uses the existing current-generation guarded resume binding, never create's runtime fallback.
                storageSessionId.takeIf {
                    resumedGeneration == sessionGeneration && sessionHasServerPresence &&
                        outgoing.attachments.isEmpty() && turnOpening.ordinary
                },
            )
        var streamVersion = streamObservationVersion.get()
        var canRollbackOpening = false
        val unknownBeforeAttempt = _uiState.value.responseStatusUnknown
        val idleBeforeAttempt = queueExecutionIdle
        viewModelScope.launch(ioDispatcher) {
            val prepared = mutableListOf<PreparedAttachment>()
            var attempted = false
            var remoteAttachment = false
            try {
                if (outgoing != draft) draftStore.save(outgoing)
                for (attachment in outgoing.attachments) {
                    when (val result = prepareAttachment(attachment)) {
                        is PrepareAttachmentResult.Success -> {
                            prepared += result.prepared
                        }

                        PrepareAttachmentResult.TooLarge -> {
                            if (userMessage != null) {
                                rejectOversizedAttachment(
                                    attachment,
                                    outgoing.attachments,
                                    userMessage,
                                    wasStreaming,
                                    dispatchGeneration,
                                )
                            }
                            if (outgoing.state ==
                                DraftState.QUEUED
                            ) {
                                draftStore.save(outgoing.copy(state = DraftState.DRAFT))
                            }
                            if (dispatchGeneration == sessionGeneration) editingDraftId = outgoing.id
                            return@launch
                        }

                        PrepareAttachmentResult.Unreadable -> {
                            if (outgoing.state ==
                                DraftState.QUEUED
                            ) {
                                draftStore.save(outgoing.copy(state = DraftState.DRAFT))
                            }
                            restoreUnsentComposer(outgoing, userMessage, wasStreaming, dispatchGeneration)
                            if (dispatchGeneration == sessionGeneration) {
                                _uiState.update {
                                    it.copy(
                                        errorMessage =
                                            getApplication<Application>().getString(
                                                R.string.draft_attachment_unavailable,
                                            ),
                                    )
                                }
                            }
                            return@launch
                        }
                    }
                }
                if (dispatchGeneration != sessionGeneration || !submissionTransport.isReady(outgoing.scope)) {
                    queueClaims.remove(outgoing.id)
                    removeUnsentBubble(userMessage, wasStreaming, dispatchGeneration)
                    return@launch
                }
                // This write-ahead record survives process death even if the first send/ACK is lost.
                draftStore.save(outgoing.copy(state = DraftState.UNKNOWN))
                activePromptIntents[outgoing.id] = outgoing.copy(state = DraftState.UNKNOWN)
                canRollbackOpening = turnOpening.streamVersion == streamObservationVersion.get()
                streamVersion = streamObservationVersion.incrementAndGet()
                // queued is a wire disposition, not proof of established work.
                if (!wasStreaming && !redirect && turnOpening.version == turnLifecycleVersion.get()) {
                    pendingAdmission = PendingAdmission(dispatchGeneration, agentSessionId)
                }
                attempted = true
                queueExecutionIdle = false
                val fileRefs = mutableListOf<String>()
                for ((attachment, file) in prepared) {
                    val dataUrl = "data:${attachment.mimeType};base64,${file.readText(Charsets.US_ASCII)}"
                    when (val result = submissionTransport.attach(outgoing.scope, session, attachment, dataUrl)) {
                        is SubmissionResult.Confirmed -> {
                            remoteAttachment = true
                            result.value?.let(fileRefs::add)
                        }

                        SubmissionResult.NotSent -> {
                            if (!remoteAttachment) {
                                draftStore.save(outgoing)
                                queueClaims.remove(outgoing.id)
                            }
                            removeUnsentBubble(userMessage, wasStreaming, dispatchGeneration)
                            return@launch
                        }

                        SubmissionResult.Expired, SubmissionResult.Unknown -> {
                            restoreTypingState(wasStreaming, dispatchGeneration, streamVersion)
                            return@launch
                        }
                    }
                }
                val fullText =
                    if (fileRefs.isEmpty()) {
                        outgoing.text
                    } else {
                        fileRefs.joinToString("\n") + if (outgoing.text.isBlank()) "" else "\n\n${outgoing.text}"
                    }
                when (
                    val result =
                        submissionTransport.submitPrompt(
                            outgoing.scope,
                            session,
                            fullText,
                            redirect,
                            queued,
                        )
                ) {
                    is SubmissionResult.Confirmed -> {
                        // The durable draft already protected the content before sending. Cache only
                        // confirmed user messages so a definitely-unsent draft cannot reappear as sent.
                        val acceptedMessage =
                            userMessage?.let { message ->
                                when (result.value) {
                                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.STEERED,
                                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.REDIRECTED,
                                    -> message.copy(displayKind = "steer")

                                    else -> message
                                }
                            }

                        val committed =
                            withContext(Dispatchers.Main.immediate) {
                                transcriptOpenJob?.join()
                                if (dispatchGeneration == sessionGeneration &&
                                    submissionTransport.isReady(outgoing.scope) &&
                                    (
                                        result.proofSource !=
                                            SubmissionProofSource.TERMINAL_RECEIPT ||
                                            (
                                                result.terminalAuthenticationEpoch != null &&
                                                    result.terminalAuthenticationEpoch % 2L == 0L &&
                                                    result.terminalAuthenticationEpoch ==
                                                    AuthManager.connectionEpoch.value
                                            )
                                    )
                                ) {
                                    _uiState.update { state ->
                                        state.copy(
                                            responseLedger =
                                                state.responseLedger.acceptInput(
                                                    outgoing.id,
                                                    turnOpening.responseId,
                                                    turnOpening.continuationId,
                                                    turnOpening.ordinary,
                                                    result.requestId,
                                                    result.value.name,
                                                    outgoing.scope.draftTargetKey(),
                                                    storageSessionId,
                                                    dispatchGeneration,
                                                    acceptedMessage?.id,
                                                    result.promptEvidence?.userRowId,
                                                    result.promptEvidence?.submissionId,
                                                    outgoing.scope.generation,
                                                    outgoing.scope.processId,
                                                    result.proofSource,
                                                ),
                                            messages =
                                                state.messages.map {
                                                    if (it.id ==
                                                        acceptedMessage?.id
                                                    ) {
                                                        acceptedMessage
                                                    } else {
                                                        it
                                                    }
                                                },
                                        )
                                    }
                                    if (streamVersion == streamObservationVersion.get() &&
                                        result.value != com.m57.hermescontrol.domain.hermes.PromptAcceptance.QUEUED
                                    ) {
                                        hasObservedActiveResponse = true
                                        _uiState.update { it.copy(responseStatusUnknown = false) }
                                    }
                                    if (result.proofSource ==
                                        SubmissionProofSource.TERMINAL_RECEIPT
                                    ) {
                                        val original = result.terminalObservation ?: return@withContext null

                                        fun validOwner() =
                                            dispatchGeneration == sessionGeneration &&
                                                original.scope == sessionScope &&
                                                submissionTransport.isReady(outgoing.scope) &&
                                                result.terminalAuthenticationEpoch == AuthManager.connectionEpoch.value
                                        // The retained event stays in this one active dispatch coroutine. Never
                                        // advance the stream fence to make its unprocessed predecessors disappear.
                                        combine(
                                            scopedProcessingVersion,
                                            AuthManager.connectionEpoch,
                                        ) { _, _ -> Unit }.first {
                                            !validOwner() || terminalAlreadyHandled(original) ||
                                                (
                                                    observedTranscriptScope == original.scope &&
                                                        (completedTranscriptOrdinal ?: -1L) >= original.ordinal - 1L
                                                )
                                        }
                                        if (!validOwner() ||
                                            original.event !is WsEvent.MessageComplete
                                        ) {
                                            return@withContext null
                                        }
                                        if (!terminalAlreadyHandled(original)) {
                                            if (original.gap) {
                                                _uiState.update {
                                                    it.copy(
                                                        responseLedger = it.responseLedger.gap(),
                                                    )
                                                }
                                            }
                                            // Original ordinal is safety order, even when a later collector
                                            // observation established a real gap. Never invent local coverage.
                                            handleWsEvent(original.event, eventOrdinal = original.ordinal)
                                        }
                                    }
                                    ActiveSessionHolder.setScoped(agentSessionId, storageSessionId, outgoing.scope)
                                    retryPendingTerminals()
                                    commitTranscript(TranscriptBarrier.ACCEPTED_INPUT)
                                } else {
                                    null
                                }
                            }
                        if (committed == null ||
                            !committed.await()
                        ) {
                            throw IllegalStateException("Transcript commit was rejected")
                        }
                        draftStore.delete(outgoing.scope, outgoing.id)
                        unsavedDrafts.remove(outgoing.id)
                        draftOperationErrors.remove(outgoing.id)
                    }

                    SubmissionResult.NotSent -> {
                        withContext(
                            Dispatchers.Main.immediate,
                        ) {
                            forgetUnsentAuthority(
                                outgoing.id,
                                userMessage?.id,
                                dispatchGeneration,
                                retireProvisional =
                                    canRollbackOpening && turnOpening.ordinary && !remoteAttachment &&
                                        turnOpening.version == turnLifecycleVersion.get() &&
                                        streamVersion == streamObservationVersion.get(),
                            )
                        }
                        if (!remoteAttachment) {
                            draftStore.save(outgoing)
                            queueClaims.remove(outgoing.id)
                        }
                        val ownsOpening =
                            canRollbackOpening &&
                                turnOpening.version == turnLifecycleVersion.get() &&
                                streamVersion == streamObservationVersion.get()
                        removeUnsentBubble(userMessage, wasStreaming, dispatchGeneration, ownsOpening)
                        if (!remoteAttachment && !wasStreaming && dispatchGeneration == sessionGeneration &&
                            ownsOpening
                        ) {
                            pendingAdmission = null
                            admissionUnproven = false
                            queueExecutionIdle = idleBeforeAttempt
                            _streamingState.update { it.copy(turnEnded = turnOpening.wasEnded) }
                            _uiState.update { it.copy(responseStatusUnknown = unknownBeforeAttempt) }
                        }
                    }

                    SubmissionResult.Expired, SubmissionResult.Unknown -> {
                        restorePromptUncertainty(outgoing, turnOpening, wasStreaming, dispatchGeneration, streamVersion)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Outgoing draft operation unavailable")
                if (!attempted) {
                    retainUnstoredDraft(outgoing, userMessage, wasStreaming, dispatchGeneration)
                } else {
                    // No resend and no rollback after a possible remote write. Keep UNKNOWN.
                    draftOperationErrors[outgoing.id] = outgoing.targetKey
                    if (dispatchGeneration == sessionGeneration) _uiState.update { it.copy(draftStorageError = true) }
                    restoreTypingState(wasStreaming, dispatchGeneration, streamVersion)
                }
            } finally {
                if (!attempted) {
                    withContext(Dispatchers.Main.immediate) {
                        forgetUnsentAuthority(outgoing.id, userMessage?.id, dispatchGeneration)
                    }
                }
                prepared.forEach { it.encodedFile.delete() }
                finishDraftAttempt(outgoing.id)
            }
        }
    }

    private fun forgetUnsentAuthority(
        attemptUid: String,
        userId: String?,
        generation: Long,
        retireProvisional: Boolean = false,
    ) {
        if (generation != sessionGeneration) return
        _uiState.update { state ->
            val provisionalId = state.responseLedger.pendingSubmissions[attemptUid]
            val ledger =
                state.responseLedger.copy(
                    pendingSubmissions = state.responseLedger.pendingSubmissions - attemptUid,
                    pendingInputs = state.responseLedger.pendingInputs - listOfNotNull(userId).toSet(),
                    possibleContinuations = state.responseLedger.possibleContinuations - attemptUid,
                )
            state.copy(
                messages = state.messages.filterNot { it.id == userId },
                responseLedger =
                    if (retireProvisional) ledger.retireUnusedProvisional(provisionalId) else ledger,
            )
        }
        persistTranscript()
    }

    /**
     * Pending receipt is evidence of uncertainty, NOT an accepted terminal. Inspect mutable
     * candidates and publish the flag together on Main, fenced to this ordinary dispatch.
     */
    private suspend fun restorePromptUncertainty(
        outgoing: OutgoingDraft,
        opening: TurnOpening,
        wasStreaming: Boolean,
        generation: Long,
        streamVersion: Long,
    ) {
        withContext(Dispatchers.Main.immediate) {
            if (generation != sessionGeneration) return@withContext
            if (streamVersion == streamObservationVersion.get()) {
                restoreTypingState(wasStreaming, generation, streamVersion)
                return@withContext
            }
            val responseId = opening.responseId ?: return@withContext
            val state = _uiState.value
            val response = state.responseLedger.responses.singleOrNull { it.id == responseId } ?: return@withContext
            if (!opening.ordinary || outgoing.queued || opening.version != turnLifecycleVersion.get() ||
                state.responseLedger.activeId != responseId ||
                state.responseLedger.pendingSubmissions[outgoing.id] != responseId ||
                response.finishedAt != null || response.authority.terminals.isNotEmpty() ||
                _streamingState.value.turnEnded ||
                outgoing.savedSessionId != state.currentSessionId || outgoing.runtimeSessionId != runtimeSessionId ||
                outgoing.scope != sessionScope || outgoing.scope != submissionTransport.configuredScope() ||
                !submissionTransport.isReady(outgoing.scope)
            ) {
                return@withContext
            }
            val hasPendingTerminal =
                pendingTerminals.any { pending ->
                    val receipt = pending.event.persistedTurn
                    pending.responseId == responseId && pending.generation == generation &&
                        pending.scope == outgoing.scope && pending.event.sessionId != null &&
                        pending.event.sessionId in listOf(outgoing.savedSessionId, outgoing.runtimeSessionId) &&
                        (
                            receipt?.submissionId != null || receipt?.userRowId != null ||
                                !receipt?.userRowIds.isNullOrEmpty()
                        )
                }
            if (hasPendingTerminal) {
                // Do not roll typing back, seal the partial, clear pending evidence, or release work.
                _uiState.value = state.copy(responseStatusUnknown = true)
            }
        }
    }

    private fun restoreTypingState(
        wasStreaming: Boolean,
        generation: Long,
        streamVersion: Long,
    ) {
        if (generation == sessionGeneration && streamVersion == streamObservationVersion.get()) {
            // A possible remote write is not an idle or delivery confirmation.
            _uiState.update { it.copy(isAgentTyping = wasStreaming, responseStatusUnknown = true) }
        }
    }

    private fun removeUnsentBubble(
        message: ChatMessage?,
        wasStreaming: Boolean,
        generation: Long,
        restoreTyping: Boolean = true,
    ) {
        if (generation == sessionGeneration) {
            _uiState.update {
                it.copy(
                    messages =
                        it.messages.filterNot { row ->
                            row.id == message?.id
                        },
                    isAgentTyping = if (restoreTyping) wasStreaming else it.isAgentTyping,
                )
            }
        }
    }

    private fun restoreUnsentComposer(
        draft: OutgoingDraft,
        message: ChatMessage?,
        wasStreaming: Boolean,
        generation: Long,
    ) {
        if (generation != sessionGeneration) return
        editingDraftId = draft.id
        _uiState.update {
            it.copy(
                composerTextToRestore = draft.text,
                pendingAttachments = draft.attachments + it.pendingAttachments,
                messages = it.messages.filterNot { row -> row.id == message?.id },
                isAgentTyping = wasStreaming,
            )
        }
    }

    private fun retainUnstoredDraft(
        draft: OutgoingDraft,
        message: ChatMessage?,
        wasStreaming: Boolean,
        generation: Long,
    ) {
        unsavedDrafts[draft.id] = draft
        _uiState.update {
            it.copy(
                draftStorageError = true,
                outgoingDrafts =
                    if (draft.targetKey == observedDraftTarget) {
                        it.outgoingDrafts.filterNot { row -> row.id == draft.id } + draft
                    } else {
                        it.outgoingDrafts
                    },
            )
        }
        restoreUnsentComposer(draft, message, wasStreaming, generation)
    }

    private fun clearDraftOperationError(id: String) {
        draftOperationErrors.remove(id)
        _uiState.update {
            it.copy(
                draftStorageError =
                    unsavedDrafts.values.any { row -> row.targetKey == observedDraftTarget } ||
                        (observedDraftTarget?.let(draftOperationErrors::containsValue) == true),
            )
        }
    }

    private fun finishDraftAttempt(id: String) {
        activePromptIntents.remove(id)
        val scope = submissionTransport.configuredScope()
        _uiState.update { state ->
            val activeIds = state.activeDraftIds - id
            state.copy(
                activeDraftIds = activeIds,
                outgoingDrafts = state.outgoingDrafts.map { projectQueuedDraft(it, scope, activeIds) },
            )
        }
    }

    private fun takePendingPrompts(): List<PendingPrompt> {
        val pending = listOfNotNull(pendingInitialPrompt) + additionalPendingPrompts
        pendingInitialPrompt = null
        additionalPendingPrompts.clear()
        pendingPromptTimeout?.cancel()
        pendingPromptTimeout = null
        return pending
    }

    private fun releasePendingInitialPrompt() {
        val pending = takePendingPrompts()
        pending.forEach {
            removeUnsentBubble(it.userMessage, it.wasStreaming, it.generation)
            finishDraftAttempt(it.draft.id)
        }
        pending.firstOrNull()?.let { first ->
            if (first.generation == sessionGeneration) _uiState.update { it.copy(isAgentTyping = first.wasStreaming) }
        }
    }

    /** Only fresh intent staged on this same connected scope may follow its binding ACK. */
    private fun drainPendingPrompts(
        storageId: String,
        runtimeId: String,
    ) {
        takePendingPrompts().forEach { pending ->
            if (pending.generation == sessionGeneration && pending.draft.scope == sessionScope &&
                (pending.draft.savedSessionId == null || pending.draft.savedSessionId == storageId) &&
                submissionTransport.isReady(pending.draft.scope)
            ) {
                val wasStreaming = _uiState.value.isAgentTyping
                val redirect = pending.redirect && wasStreaming && !_uiState.value.responseStatusUnknown
                val turnOpening = pending.opening
                _uiState.update { it.copy(isAgentTyping = true) }
                dispatchPrompt(
                    pending.draft,
                    wasStreaming,
                    storageId,
                    runtimeId,
                    pending.userMessage,
                    pending.queued,
                    redirect,
                    pending.generation,
                    turnOpening,
                )
            } else {
                removeUnsentBubble(pending.userMessage, pending.wasStreaming, pending.generation)
                finishDraftAttempt(pending.draft.id)
            }
        }
    }

    /** Resume carries execution evidence; connection and transcript hydration do not. */
    private fun reconcileResumedExecution(
        result: Map<String, Any?>?,
        request: SessionRequest?,
    ) {
        if (request == null || request.streamVersion != streamObservationVersion.get()) return
        val running = result?.get("running") as? Boolean ?: return
        if (!running &&
            activePromptIntents.values.any {
                it.state == DraftState.UNKNOWN && it.savedSessionId == _uiState.value.currentSessionId &&
                    it.targetKey == submissionTransport.configuredScope()?.draftTargetKey()
            }
        ) {
            return
        }
        val status = result["status"] as? String
        val snapshotInfo = result["info"] as? Map<*, *>
        val canonicalSettled =
            request.resync && !running && (result["prompts"] as? List<*>)?.isEmpty() == true &&
                snapshotInfo?.get("desktop_protocol") == "hermes-gateway-v1"
        val isIdle = !running && status == "idle" && result["hydrating"] != true
        queueTurnObserved = running
        queueExecutionIdle = isIdle
        if (running) {
            hasObservedActiveResponse = true
            // A running resume proves the turn was admitted.
            admissionUnproven = false
            openTurnLifecycle(awaitsAdmission = false)
            _uiState.update { it.copy(isAgentTyping = true, responseStatusUnknown = false) }
        } else if (isIdle || canonicalSettled) {
            hasObservedActiveResponse = false
            sealStreamingMessageIfAny()
            streamingController.resetStreaming()
            _streamingState.value = StreamingState(turnEnded = true)
            _uiState.update {
                it.copy(isAgentTyping = false, responseStatusUnknown = false, isThinking = false, thinkingText = "")
            }
        }
    }

    private fun projectQueuedDraft(
        draft: OutgoingDraft,
        scope: ConnectionScope?,
        activeIds: Set<String>,
    ): OutgoingDraft =
        if (scope != null && draft.state == DraftState.QUEUED && draft.scope != scope &&
            draft.id !in queueClaims && draft.id !in activeIds
        ) {
            draft.copy(state = DraftState.DRAFT, queued = false)
        } else {
            draft
        }

    private fun refreshDrafts() {
        val scope = submissionTransport.configuredScope() ?: return
        val target = scope.draftTargetKey()
        if (observedDraftScope == scope && draftObserver?.isActive == true) return
        observedDraftTarget = target
        observedDraftScope = scope
        draftObserver?.cancel()
        _uiState.update { it.copy(outgoingDrafts = unsavedDrafts.values.filter { draft -> draft.targetKey == target }) }
        draftObserver =
            viewModelScope.launch {
                try {
                    draftStore.observe(scope).collect { rows ->
                        if (observedDraftScope == scope) {
                            val pending = unsavedDrafts.values.filter { it.targetKey == target }
                            _uiState.update {
                                it.copy(
                                    outgoingDrafts =
                                        (rows + pending)
                                            .associateBy { row ->
                                                row.id
                                            }.values
                                            .sortedBy { row ->
                                                row.createdAt
                                            }.map { row ->
                                                projectQueuedDraft(row, scope, it.activeDraftIds)
                                            },
                                    draftStorageError =
                                        pending.isNotEmpty() || draftOperationErrors.containsValue(target),
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _uiState.update { it.copy(draftStorageError = true) }
                }
            }
    }

    fun restoreDraft(id: String) {
        val draft = _uiState.value.outgoingDrafts.firstOrNull { it.id == id } ?: return
        if (draft.state == DraftState.UNKNOWN || id in _uiState.value.activeDraftIds) return
        if (draft.state == DraftState.QUEUED) {
            if (draft.targetKey != submissionTransport.configuredScope()?.draftTargetKey()) return
            activePromptIntents[id] = draft
            _uiState.update { it.copy(activeDraftIds = it.activeDraftIds + id) }
            viewModelScope.launch {
                try {
                    val editable = draft.copy(state = DraftState.DRAFT)
                    withContext(ioDispatcher) { draftStore.save(editable) }
                    _uiState.update {
                        it.copy(
                            outgoingDrafts =
                                it.outgoingDrafts.map { row ->
                                    if (row.id == id) editable else row
                                },
                        )
                    }
                    clearDraftOperationError(id)
                    finishDraftAttempt(id)
                    restoreDraft(id)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    draftOperationErrors[id] = draft.targetKey
                    _uiState.update { it.copy(draftStorageError = true) }
                } finally {
                    finishDraftAttempt(id)
                }
            }
            return
        }
        if (draft.targetKey != submissionTransport.configuredScope()?.draftTargetKey()) return
        if (draft.savedSessionId ==
            null
        ) {
            createNewSession()
        } else if (draft.savedSessionId !=
            _uiState.value.currentSessionId
        ) {
            switchSession(draft.savedSessionId)
        }
        if (draft.targetKey != submissionTransport.configuredScope()?.draftTargetKey()) return
        editingDraftId = id
        _uiState.update { it.copy(composerTextToRestore = draft.text, pendingAttachments = draft.attachments) }
    }

    fun openDraftConversation(id: String) {
        val draft = _uiState.value.outgoingDrafts.firstOrNull { it.id == id } ?: return
        if (draft.targetKey != submissionTransport.configuredScope()?.draftTargetKey()) return
        draft.savedSessionId?.let(::switchSession)
    }

    fun discardDraft(id: String) {
        val draft = _uiState.value.outgoingDrafts.firstOrNull { it.id == id } ?: return
        if (id in _uiState.value.activeDraftIds ||
            draft.targetKey != submissionTransport.configuredScope()?.draftTargetKey()
        ) {
            return
        }
        activePromptIntents[id] = draft
        _uiState.update { it.copy(activeDraftIds = it.activeDraftIds + id) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { draftStore.delete(draft.scope, id) }
                unsavedDrafts.remove(id)
                draftOperationErrors.remove(id)
                if (editingDraftId == id) editingDraftId = null
                _uiState.update {
                    it.copy(
                        outgoingDrafts = it.outgoingDrafts.filterNot { row -> row.id == id },
                        draftStorageError =
                            unsavedDrafts.values.any { row -> row.targetKey == observedDraftTarget } ||
                                (observedDraftTarget?.let(draftOperationErrors::containsValue) == true),
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                draftOperationErrors[id] = draft.targetKey
                _uiState.update { it.copy(draftStorageError = true) }
            } finally {
                finishDraftAttempt(id)
            }
        }
    }

    /** Snapshot one URI to private cache while enforcing the outbound frame limit. */
    private suspend fun prepareAttachment(attachment: Attachment): PrepareAttachmentResult {
        var encodedFile: File? = null
        return try {
            val context = getApplication<Application>()
            val uri = Uri.parse(attachment.uri)
            encodedFile = File.createTempFile("chat-attachment-", ".b64", context.cacheDir)
            val result =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    encodedFile.outputStream().buffered().use { output ->
                        encodeAttachmentBase64(input, output)
                    }
                } ?: return PrepareAttachmentResult.Unreadable.also { encodedFile.delete() }

            when (result) {
                AttachmentSizeResult.WITHIN_LIMIT -> {
                    PrepareAttachmentResult.Success(PreparedAttachment(attachment, encodedFile))
                }

                AttachmentSizeResult.TOO_LARGE -> {
                    PrepareAttachmentResult.TooLarge.also { encodedFile.delete() }
                }
            }
        } catch (e: Exception) {
            encodedFile?.delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Attachment snapshot unavailable")
            PrepareAttachmentResult.Unreadable
        }
    }

    private fun rejectOversizedAttachment(
        attachment: Attachment,
        attachments: List<Attachment>,
        message: ChatMessage,
        wasStreaming: Boolean,
        generation: Long,
    ) {
        Log.w(TAG, "Rejecting oversized attachment")
        if (generation != sessionGeneration) return
        _uiState.update { state ->
            state.copy(
                messages = state.messages.filterNot { it.id == message.id },
                pendingAttachments = attachments + state.pendingAttachments,
                composerTextToRestore = message.content,
                errorMessage = attachmentTooLargeMessage(attachment),
                isAgentTyping = wasStreaming,
            )
        }
    }

    private fun attachmentTooLargeMessage(attachment: Attachment): String =
        "Attachment too large: ${attachment.name} (maximum 10 MB)"

    /**
     * Send a JSON-RPC call and suspend until the response arrives, delegating
     * the deferred + 120s timeout to [HermesWsClient.request] (issue #526).
     * Throws [HermesWsClient.HermesRpcException] on RPC error, or
     * [kotlinx.coroutines.TimeoutCancellationException] if the server never
     * answers within the timeout.
     */
    private suspend fun sendRpcAndAwait(
        method: String,
        params: Map<String, Any>,
    ): Any? = HermesWsClient.request(method, params).await()

    // ── Attachment management ─────────────────────────────────────────────

    /**
     * Add a picked file as a pending attachment.
     * [uri] should be a content:// URI string; the ViewModel will read
     * the content and encode it for sending.
     */
    fun addAttachment(
        uri: String,
        name: String,
        mimeType: String,
        size: Long,
    ) = attachmentsDelegate.addAttachment(uri, name, mimeType, size)

    fun addAttachments(attachments: List<Attachment>) = attachmentsDelegate.addAttachments(attachments)

    fun removeAttachment(index: Int) = attachmentsDelegate.removeAttachment(index)

    fun openAttachment(attachment: Attachment) = mediaDelegate.openAttachment(attachment)

    fun saveAttachment(
        attachment: Attachment,
        destination: android.net.Uri,
    ) = mediaDelegate.saveAttachment(attachment, destination)

    fun clearOpenError() {
        _uiState.update { it.copy(openError = null) }
    }

    fun consumeComposerTextRestore() {
        _uiState.update { it.copy(composerTextToRestore = null) }
    }

    fun gatewayPathFor(attachment: Attachment): String = mediaDelegate.gatewayPathFor(attachment)

    fun clearAttachments() = attachmentsDelegate.clearAttachments()

    private fun handleSlashCommand(command: String) {
        if (command.substringBefore(' ').equals("/queue", ignoreCase = true)) {
            handleQueueCommand(command)
            return
        }
        // Classify FIRST (pure logic) — /queue's optimistic bubble must show
        // the queued TEXT (prefix stripped, see QueuePrompt.displayContent) so
        // it matches the server echo and the transcript sync dedupes instead
        // of rendering a duplicate below its answer.
        val result = slashDispatcher.dispatch(command)

        if (result is SlashResult.SideQuestion) {
            handleSideQuestionCommand(result.question)
            return
        }

        val displayContent =
            if (result is SlashResult.QueuePrompt) result.displayContent else command
        val userMsg =
            ChatMessage(
                role = MessageRole.USER,
                content = displayContent,
                // A requested configuration change is not a successful-switch event or a new prompt.
                displayKind = if (result is SlashResult.ModelSwitch) "model_switch_request" else null,
            )
        val sessionId = _uiState.value.currentSessionId

        _uiState.update { it.copy(messages = it.messages + userMsg) }

        // Persist — OUTSIDE update{}
        if (sessionId != null) {
            persistTranscript()
        }

        if (result is SlashResult.Undo) {
            handleUndoCommand(result.count)
            return
        }

        // Block desktop/CLI-only + TUI-only commands that don't function on
        // mobile (issue #576, deliverable #3). These are also hidden from the
        // suggestion menu, but a user can still type one — intercept it here
        // (before any RPC fires) with a clear message instead of a doomed call.
        if (CommandBlocklist.contains(command)) {
            addAssistantMessage(
                "${command.split(" ", limit = 2)[0]} is not supported on mobile",
            )
            return
        }

        // Track per-command usage for the slash-autocomplete ranking (issue
        // #865). Counted here, AFTER the blocklist guard, so commands that
        // can never dispatch don't climb the ranking. Best-effort — a store
        // failure must never block the dispatch itself.
        val commandName = command.split(" ", limit = 2)[0].lowercase()
        viewModelScope.launch {
            slashUsageStore.recordUse(commandName)
        }

        when (result) {
            is SlashResult.Interrupt -> {
                interruptSession()
            }

            is SlashResult.NewSession -> {
                val currentTitle = _uiState.value.chatTitle
                if (currentTitle.equals(BOT_CHAT_TITLE, ignoreCase = true)) {
                    // Bot Chat is a canonical forever-conversation — compact instead of creating an orphan
                    val sessionId = _uiState.value.currentSessionId
                    if (!sessionId.isNullOrBlank()) {
                        addAssistantMessage(
                            "Bot chats are one continuous conversation — compacting instead. " +
                                "For a throwaway session, create a standard chat.",
                        )
                        dispatchViaRpc("/compact")
                    } else {
                        createNewSession()
                    }
                } else {
                    createNewSession()
                }
            }

            is SlashResult.SessionBranch -> {
                branchSession(command)
            }

            is SlashResult.ModelSwitch -> {
                handleModelSwitch(command)
            }

            is SlashResult.Update -> {
                openUpdateConfirm()
            }

            is SlashResult.OpenHistory -> {
                // Client-side: open the session history tab so the user can
                // pick a past session to resume (issue #864) — no gateway
                // round-trip (the backend slash worker can't answer /resume).
                _uiState.update { it.copy(openHistoryRequested = true) }
            }

            is SlashResult.QueuePrompt -> {
                handleQueueCommand(command)
            }

            is SlashResult.SideQuestion -> {
                handleSideQuestionCommand(result.question)
            }

            is SlashResult.Undo -> {
                handleUndoCommand(result.count)
            }

            is SlashResult.RpcDispatch -> {
                dispatchViaRpc(command)
            }
        }
    }

    /** `/queue` uses the same durable follow-up queue as the composer. */
    private fun handleQueueCommand(command: String) {
        val arg = command.split(" ", limit = 2).getOrElse(1) { "" }.trim()
        if (arg.isBlank()) {
            addAssistantMessage("usage: /queue <prompt>")
            return
        }
        enqueuePrompt(arg, _uiState.value.pendingAttachments.toList())
    }

    // ── Side Questions via /btw (issue #1015) ─────────────────────────────

    fun dismissBtw() {
        _uiState.update { it.copy(btwState = null) }
    }

    fun submitSideQuestion(question: String) {
        val trimmed = question.trim()
        if (trimmed.isBlank()) {
            addAssistantMessage(
                "Usage: `/btw <question>` — Ask a side question about this session without mutating its history.",
            )
            return
        }
        val sessionId = runtimeSessionId
        if (sessionId.isNullOrBlank()) {
            addAssistantMessage("No active session for side questions. Start a chat first.")
            return
        }
        val scope = sessionScope ?: return
        val request = SessionRequest(sessionGeneration, sessionId = _uiState.value.currentSessionId, scope = scope)

        val ready = submissionTransport.isReady(scope)
        _uiState.update {
            it.copy(
                btwState =
                    BtwUiState(
                        question = trimmed,
                        isLoading = ready,
                        error = if (ready) null else "Request not sent; reconnect does not submit it",
                    ),
            )
        }
        if (!ready) return

        viewModelScope.launch {
            if (isStaleSessionRequest(request)) return@launch
            try {
                val rpcResult =
                    wsClient
                        .writeScoped(
                            scope,
                            WsMethods.PROMPT_BTW,
                            mapOf("session_id" to sessionId, "text" to trimmed),
                            timeoutMs = HermesWsClient.REQUEST_TIMEOUT_MS,
                        ).response
                        .await()
                if (isStaleSessionRequest(request)) return@launch
                val taskId = (rpcResult.toJsonElement().toAny() as? Map<*, *>)?.get("task_id") as? String
                if (!taskId.isNullOrBlank()) {
                    _uiState.update { state ->
                        state.btwState?.let { current ->
                            state.copy(btwState = current.copy(taskId = taskId))
                        } ?: state
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isStaleSessionRequest(request)) return@launch
                _uiState.update { state ->
                    state.btwState?.let { current ->
                        state.copy(
                            btwState =
                                current.copy(
                                    isLoading = false,
                                    error = e.message ?: "Failed to dispatch side question",
                                ),
                        )
                    } ?: state
                }
            }
        }
    }

    private fun handleSideQuestionCommand(question: String) {
        viewModelScope.launch {
            slashUsageStore.recordUse("btw")
        }
        submitSideQuestion(question)
    }

    // ── Update from chat (issue #862) ────────────────────────────────────
    // `/update` can't travel via the slash worker (the backend handler is
    // interactive + session-exiting → guaranteed 45s timeout). Intercept it
    // client-side: confirm, then trigger the same REST action the System
    // screen uses and track it in the shared ActionProgressDialog.

    fun openUpdateConfirm() {
        _uiState.update { it.copy(updateConfirmOpen = true) }
    }

    fun closeUpdateConfirm() {
        _uiState.update { it.copy(updateConfirmOpen = false) }
    }

    /**
     * Run the backend update: `POST /api/hermes/update` returns immediately
     * (`{ok, name}`) while `hermes update` runs in the background, so
     * [actionProgress] polls its status log until it exits and the popup shows
     * the live tail + final state.
     */
    fun applyUpdate() {
        actionProgress.open()
        viewModelScope.launch(ioDispatcher) {
            val result = safeApiCall { ApiClient.hermesApi.updateHermes() }
            when (result) {
                is NetworkResult.Success -> {
                    val name = result.data.name
                    if (name != null) {
                        actionProgress.markStarted(name)
                    } else {
                        actionProgress.fail(
                            "Update started but the backend did not report an action name",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    actionProgress.fail("Failed to start update: ${result.error.message}")
                }
            }
        }
    }

    /**
     * Fork the active conversation via the session.branch WS RPC (issue #533).
     * The backend already supports session.branch; the mobile previously had
     * no client surface, so `/fork` fell through to command.dispatch and 4018'd.
     * The optional arg becomes the new branch's title.
     */
    private fun branchSession(command: String) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            addAssistantMessage("No active session. Use `/new` to create one.")
            return
        }
        val arg = command.split(" ", limit = 2).getOrElse(1) { "" }.trim()
        val params = mutableMapOf<String, Any>("session_id" to sessionId)
        if (arg.isNotBlank()) params["name"] = arg
        val generation = sessionGeneration
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_BRANCH,
                params,
                onSent = { id -> trackSessionRequest(id, WsMethods.SESSION_BRANCH, generation) },
            )
        }
    }

    private fun dispatchViaRpc(command: String) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            addAssistantMessage("No active session. Use `/new` to create one.")
            return
        }
        val parts = command.split(" ", limit = 2)
        val name = parts[0].lowercase().removePrefix("/")
        val arg = parts.getOrElse(1) { "" }
        // PR06a: capture before suspension; keep result handling on Main with session selection.
        val scope = sessionScope ?: return
        val request = SessionRequest(sessionGeneration, sessionId = _uiState.value.currentSessionId, scope = scope)
        viewModelScope.launch {
            if (isStaleSessionRequest(request)) return@launch
            try {
                // Primary path: command.dispatch handles quick/plugin/bundle/
                // skill commands + a few hardcoded ones. It returns a hard 4018
                // "not a ... command" for everything that lives only in the TUI
                // slash worker (the 29 commands that 4018'd on mobile — issue
                // #576). For those we fall back to slash.exec, which runs the
                // full COMMAND_REGISTRY through the worker.
                val result =
                    wsClient
                        .writeScoped(
                            scope,
                            WsMethods.COMMAND_DISPATCH,
                            mapOf("name" to name, "arg" to arg, "session_id" to sessionId),
                            timeoutMs = HermesWsClient.REQUEST_TIMEOUT_MS,
                        ).response
                        .await()
                if (isStaleSessionRequest(request)) return@launch
                handleDispatchResult(result)
            } catch (e: HermesWsClient.HermesRpcException) {
                if (isStaleSessionRequest(request)) return@launch
                val msg = e.message.orEmpty()
                // Registry miss on command.dispatch: the backend emits exactly
                // "not a quick/plugin/bundle/skill command: <name>" (tui_gateway
                // server.py L12408). Match that precise phrase so unrelated
                // errors can't accidentally trigger the slash.exec fallback.
                if (msg.contains("not a quick/plugin/bundle/skill command")) {
                    // Registry miss on command.dispatch -> retry via slash.exec,
                    // which routes the full CLI command set through the worker.
                    try {
                        val result =
                            wsClient
                                .writeScoped(
                                    scope,
                                    WsMethods.SLASH_EXEC,
                                    mapOf(
                                        "command" to "/$name${if (arg.isNotEmpty()) " $arg" else ""}",
                                        "session_id" to sessionId,
                                    ),
                                    timeoutMs = HermesWsClient.REQUEST_TIMEOUT_MS,
                                ).response
                                .await()
                        if (isStaleSessionRequest(request)) return@launch
                        val output = (result.toJsonElement().toAny() as? Map<*, *>)?.get("output") as? String
                        if (!output.isNullOrBlank()) addAssistantMessage(output)
                    } catch (e2: HermesWsClient.HermesRpcException) {
                        if (isStaleSessionRequest(request)) return@launch
                        addAssistantMessage("/$name: ${e2.message}")
                    }
                } else {
                    // Legit error from command.dispatch (busy, no history, etc.)
                    addAssistantMessage("/$name: ${e.message}")
                }
            }
        }
    }

    /**
     * Hot-swap the current session's model via the backend's model-switch
     * mechanism (issue #589).
     *
     * The TUI gateway's `prompt.submit` does NOT parse slash commands (it would
     * make the LLM treat "/model ..." as a chat message), and `command.dispatch`
     * only knows quick/plugin/bundle/skill commands (4018s on /model). The
     * correct RPC is `config.set` with `key="model"` — the gateway (server.py
     * `config.set`, L10253) routes `key=="model"` straight to `_apply_model_switch`
     * using the same `_sessions.get(session_id)` lookup that the working
     * `command.dispatch` uses.
     *
     * IMPORTANT: `config.set` key=model passes the value DIRECTLY to
     * `parse_model_flags` (it does NOT strip a leading "/model" like slash.exec /
     * prompt.submit do). So we strip the "/model" prefix here and send the bare
     * spec `parse_model_flags` understands:
     *   `<model> --provider <slug> --session`
     * (matching the TUI client's `modelValueForConfigSet`).
     */
    private fun handleModelSwitch(
        command: String,
        confirmExpensive: Boolean = false,
        rollbackModel: String? = null,
    ) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            val previous = optimisticPreviousModel
            optimisticPreviousModel = null
            if (pendingChatSelection != null) {
                // Picker path: the chip already shows the pick, so undo it instead of
                // leaving a model label the server never received.
                revertModelSelection(previous, "セッションへの接続後にモデルを選択してください。")
            } else {
                addAssistantMessage("No active session. Use `/new` to create one.")
            }
            return
        }
        // Strip a leading "/model" (and any following whitespace) — config.set
        // key=model expects the bare spec, not a slash command. Match the
        // dispatcher's case-insensitive "/model" detection so a typed "/MODEL"
        // (or any casing) doesn't forward the literal slash prefix to the
        // backend, where parse_model_flags wouldn't recognize it.
        val spec =
            if (command.startsWith("/model", ignoreCase = true)) {
                command.substring(6).trim()
            } else {
                command.trim()
            }
        val previousModel = rollbackModel ?: optimisticPreviousModel ?: _uiState.value.currentSessionModel
        optimisticPreviousModel = null
        val params =
            mutableMapOf<String, Any>(
                "key" to "model",
                "value" to spec,
                "session_id" to sessionId,
            )
        if (confirmExpensive) {
            params["confirm_expensive_model"] = true
        }
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.CONFIG_SET,
                params,
                onSent = { id ->
                    trackRequest(id, WsMethods.CONFIG_SET)
                    pendingModelSwitchRequests[id] = ActiveModelSwitch(spec, previousModel)
                },
            )
        }
    }

    /**
     * Submits [text] as a prompt to the current session via WS, without
     * adding a duplicate user message. Used by [handleDispatchResult] when
     * a slash command resolves to a normal user prompt (e.g. `/init` → "Scan this repo").
     */
    private fun submitPrompt(
        text: String,
        queued: Boolean = false,
        redirect: Boolean = false,
    ) {
        if (text.isBlank()) return
        val wasStreaming = _uiState.value.isAgentTyping
        val turnOpening = openTurnLifecycle(awaitsAdmission = !redirect)
        _uiState.update { it.copy(isAgentTyping = true) }
        stagePrompt(text, emptyList(), wasStreaming, null, turnOpening, queued, redirect)
    }

    private fun addAssistantMessage(text: String) {
        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = text)
        _uiState.update { it.copy(messages = it.messages + msg) }

        // Persist — OUTSIDE update{}
        val sessionId = _uiState.value.currentSessionId
        if (sessionId != null) {
            persistTranscript()
        }
    }

    private fun handleUndoCommand(count: String) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            addAssistantMessage("No active session to undo.")
            return
        }
        val scope = sessionScope ?: return
        val request = SessionRequest(sessionGeneration, sessionId = _uiState.value.currentSessionId, scope = scope)
        viewModelScope.launch {
            slashUsageStore.recordUse("/undo")
        }
        viewModelScope.launch {
            if (isStaleSessionRequest(request)) return@launch
            try {
                val result =
                    wsClient
                        .writeScoped(
                            scope,
                            WsMethods.COMMAND_DISPATCH,
                            mapOf("name" to "undo", "arg" to count, "session_id" to sessionId),
                            timeoutMs = HermesWsClient.REQUEST_TIMEOUT_MS,
                        ).response
                        .await()
                if (isStaleSessionRequest(request)) return@launch
                handleDispatchResult(result)
            } catch (e: HermesWsClient.HermesRpcException) {
                if (isStaleSessionRequest(request)) return@launch
                addAssistantMessage(e.message ?: "Failed to undo.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isStaleSessionRequest(request)) return@launch
                addAssistantMessage(e.message ?: "Failed to undo.")
            }
        }
    }

    private fun handlePrefillResult(
        message: String,
        notice: String,
    ) {
        val storageSessionId = _uiState.value.currentSessionId
        if (message.isNotBlank()) {
            _uiState.update { it.copy(pendingPrefillText = message) }
        }
        val feedback =
            when {
                notice.isNotBlank() && message.isNotBlank() -> "$notice (Rewound to: \"$message\")"
                notice.isNotBlank() -> notice
                message.isNotBlank() -> "↶ Rewound to: \"$message\""
                else -> "↶ Rewound"
            }
        if (storageSessionId != null) {
            val generation = ++sessionGeneration
            todoHistoryMemo.clear()
            pendingTerminals.clear()
            scopedProcessingVersion.value++
            transcriptOwner?.valid = false
            transcriptOwner = null
            transcriptOpening = true
            terminalCommitPending = false
            queueExecutionIdle = false
            streamingController.resetStreaming()
            _streamingState.value = StreamingState(turnEnded = true)
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    stagedCanonicalMessages = emptyList(),
                    responseLedger = ResponseLedger(requiresCorrelation = true),
                )
            }
            openTranscriptCache(
                storageSessionId,
                generation,
                load = false,
                emptyLedger = _uiState.value.responseLedger,
            ) {
                loadSessionMessages(storageSessionId, generation)
                addSystemMessage(feedback, persist = true)
                fetchContextUsage()
            }
        } else {
            addSystemMessage(feedback, persist = false)
        }
    }

    fun consumePendingPrefill() {
        _uiState.update { it.copy(pendingPrefillText = null) }
    }

    // ── Session management ───────────────────────────────────────────────

    fun interruptSession() {
        val sessionId = runtimeSessionId ?: return
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_INTERRUPT,
                mapOf("session_id" to sessionId),
                onSent = { id -> trackRequest(id, WsMethods.SESSION_INTERRUPT) },
            )
        }
    }

    /**
     * Send course-correction guidance to a live subagent child session (issue #1030).
     */
    fun steerSubagent(
        indicator: SubagentIndicator,
        message: String,
    ) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return
        val subagentId = indicator.subagentId
        val steerCommand =
            if (!subagentId.isNullOrBlank()) {
                "/steer $subagentId $trimmed"
            } else {
                "/steer $trimmed"
            }
        submitPrompt(steerCommand, redirect = true)
        _uiState.update { current ->
            val updated =
                current.subagentIndicators.map { ind ->
                    if (ind.subagentId == indicator.subagentId &&
                        (ind.goal == indicator.goal || indicator.subagentId != null)
                    ) {
                        val newLogs =
                            (
                                ind.logs +
                                    SubagentLogLine(
                                        text =
                                            getApplication<Application>().getString(
                                                R.string.subagent_correction_requested,
                                                trimmed,
                                            ),
                                        isSummary = true,
                                    )
                            ).takeLast(30)
                        ind.copy(logs = newLogs)
                    } else {
                        ind
                    }
                }
            current.copy(subagentIndicators = updated)
        }
    }

    /**
     * Stop / interrupt a single live subagent early (issue #1030).
     */
    fun stopSubagent(indicator: SubagentIndicator) {
        val subagentId = indicator.subagentId
        val stopCommand =
            if (!subagentId.isNullOrBlank()) {
                "/stop $subagentId"
            } else {
                "/stop"
            }
        submitPrompt(stopCommand, redirect = true)
        _uiState.update { current ->
            val updated =
                current.subagentIndicators.map { ind ->
                    if (ind.subagentId == indicator.subagentId &&
                        (ind.goal == indicator.goal || indicator.subagentId != null)
                    ) {
                        val newLogs =
                            (
                                ind.logs +
                                    SubagentLogLine(
                                        text =
                                            getApplication<Application>().getString(
                                                R.string.subagent_stop_requested,
                                            ),
                                        isSummary = true,
                                    )
                            ).takeLast(30)
                        ind.copy(logs = newLogs)
                    } else {
                        ind
                    }
                }
            current.copy(subagentIndicators = updated)
        }
    }

    fun createNewSession(
        setLoading: Boolean = true,
        queueOrigin: String? = null,
        title: String? = null,
    ) {
        // A fresh create has no persisted row until the first prompt.
        sessionHasServerPresence = false
        val generation = resetSessionState(sessionId = null, title = title ?: "Hermes", isLoading = setLoading)
        pendingCreateQueueOrigin = generation to queueOrigin
        viewModelScope.launch(ioDispatcher) {
            // Composer choices belong to their session. A new conversation follows the bot's
            // server defaults, including defaults edited from another bot/group settings page.
            wsClient.send(
                WsMethods.SESSION_CREATE,
                params = mapOf("source" to "desktop") + listOfNotNull(title?.let { "title" to it }),
                onSent = { id -> trackSessionRequest(id, WsMethods.SESSION_CREATE, generation) },
            )
        }
        // B7 safety timeout: clear loading state if RPC response never arrives
        if (setLoading && !isTestEnvironment()) {
            viewModelScope.launch {
                delay(10_000L)
                // Only clear if no newer session creation has started — prevents a
                // stale timeout from wiping the loading flag of a subsequent request.
                if (generation == sessionGeneration && _uiState.value.isLoading) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun loadSessions() {
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_LIST,
                onSent = { id -> trackRequest(id, WsMethods.SESSION_LIST) },
            )
        }
    }

    private fun fetchCommandCatalog() {
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.COMMANDS_CATALOG,
                onSent = { id -> trackRequest(id, WsMethods.COMMANDS_CATALOG) },
            )
        }
    }

    fun refreshCurrentSession() {
        subagentSnapshotFailures = 0
        refreshSubagents()
        val sessionId = _uiState.value.currentSessionId ?: return
        // No server-side copy yet (created but never prompted): the REST
        // transcript 404s and would burn the resume retry budget for nothing.
        if (!sessionHasServerPresence) return
        loadSessionMessages(sessionId, sessionGeneration)
    }

    fun refreshSettings() {
        _uiState.update { state ->
            state.copy(
                typingEffectEnabled = AuthManager.isTypingEffectEnabled(),
                typingEffectDelayMs = AuthManager.getTypingEffectDelayMs(),
            )
        }
    }

    /**
     * Fetch the real per-turn tool-call budget (`agent.max_turns` — falling back
     * to the legacy top-level `max_turns`) from GET /api/config so the chat's
     * tool-call dividers can render `count/max` against the actual backend
     * limit. Never hardcoded. Null on failure — the divider degrades to a
     * bare count.
     */
    private fun refreshMaxToolCallsPerTurn() {
        viewModelScope.launch(ioDispatcher) {
            try {
                val response = ApiClient.hermesApi.getConfig()
                if (!response.isSuccessful) return@launch
                val config = response.body() ?: return@launch
                val agent = config["agent"] as? JsonObject
                val max =
                    (agent?.get("max_turns")?.jsonPrimitive?.intOrNull)
                        ?: config["max_turns"]?.jsonPrimitive?.intOrNull
                if (max != null && max > 0) {
                    _uiState.update { it.copy(maxToolCallsPerTurn = max) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch max tool calls per turn", e)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCommandCatalog(map: Map<*, *>): CommandCatalog? =
        try {
            val jsonElement = map.toJsonElement()
            OkHttpProvider.json.decodeFromJsonElement<CommandCatalog>(jsonElement)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command catalog", e)
            null
        }

    // ── In-session model picker (issue #589) ─────────────────────────────

    /**
     * Whether [command] should open the in-session model picker instead of being
     * dispatched as a normal slash command. A bare `/model` opens cached options;
     * `/model --refresh` also requests a live catalog refresh.
     * A fully-typed `/model provider/model` is forwarded straight to the backend.
     */
    private fun isModelPickerCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (!trimmed.startsWith("/", ignoreCase = true)) return false
        val body = trimmed.removePrefix("/").trimStart()
        // Model arguments still route through the existing backend switch command.
        return body.equals("model", ignoreCase = true) ||
            body.equals("model --refresh", ignoreCase = true) ||
            (
                body.startsWith("model ", ignoreCase = true) &&
                    body.substringAfter("model").trim().isEmpty()
            )
    }

    /** Preload cached server options without forcing a live provider refresh. */
    private fun preloadModelOptions() {
        loadModelOptions(refresh = false)
    }

    /** Open immediately with cached options; a live refresh is an explicit action. */
    fun openModelPicker(refresh: Boolean = false) {
        val hasCached = cachedModelOptions.isNotEmpty()
        _uiState.update {
            it.copy(
                showModelPicker = true,
                modelPickerProviders = cachedModelOptions,
                modelPickerPinned = AuthManager.getPinnedModels(),
                modelPickerLoading = modelOptionsJob?.isActive == true,
                modelPickerError = null,
            )
        }
        if (refresh || !hasCached) {
            loadModelOptions(refresh = refresh)
        }
    }

    fun refreshModelOptions() {
        loadModelOptions(refresh = true)
    }

    private fun loadModelOptions(refresh: Boolean) {
        if (modelOptionsJob?.isActive == true) return
        _uiState.update { it.copy(modelPickerLoading = true, modelPickerError = null) }
        modelOptionsJob =
            viewModelScope.launch(ioDispatcher) {
                val result =
                    safeApiCall {
                        ApiClient.hermesApi.getModelOptions(refresh = refresh, includeUnconfigured = false)
                    }
                when (result) {
                    is NetworkResult.Success -> {
                        cachedModelOptions = result.data.providers.orEmpty()
                        _uiState.update {
                            it.copy(
                                modelPickerProviders = cachedModelOptions,
                                modelPickerPinned = AuthManager.getPinnedModels(),
                                modelPickerLoading = false,
                            )
                        }
                        syncCurrentModelCapabilities()
                    }

                    is NetworkResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                modelPickerLoading = false,
                                modelPickerError = "Failed to load models: ${result.error.message}",
                            )
                        }
                    }
                }
            }
    }

    fun closeModelPicker() {
        _uiState.update { it.copy(showModelPicker = false, modelPickerError = null) }
    }

    fun togglePinModel(
        providerSlug: String,
        modelName: String,
    ) {
        val currentPinned = AuthManager.getPinnedModels().toMutableList()
        val target = PinnedModel(providerSlug, modelName)
        if (currentPinned.contains(target)) {
            currentPinned.remove(target)
        } else {
            currentPinned.add(target)
        }
        AuthManager.savePinnedModels(currentPinned)
        _uiState.update { it.copy(modelPickerPinned = currentPinned) }
    }

    /**
     * Hot-swap the CURRENT session's model via the /model slash command.
     *
     * Builds the backend-valid command form `/model <model> --provider <slug>
     * --session`. The `--session` flag keeps the switch scoped to this chat
     * only (it writes a per-session override and never touches the global
     * model config), per the backend model-switch contract.
     */
    fun sendSlashModel(
        provider: String,
        model: String,
    ) {
        pendingChatSelection =
            ChatSelection(
                scope = AuthManager.uiPreferenceScope(),
                generation = sessionGeneration,
                provider = provider,
                model = model,
                spec = modelSwitchSpec(provider, model),
                previousModel = _uiState.value.currentSessionModel,
            )
        optimisticPreviousModel = _uiState.value.currentSessionModel
        _uiState.update {
            it.copy(
                showModelPicker = false,
                modelPickerLoading = false,
                // Optimistic: reflect the chosen model in the top-bar chip until
                // the next session sync confirms the backend hot-swap.
                currentSessionModel = "$provider/$model",
            )
        }
        syncCurrentModelCapabilities()
        // Model switch changes the context-window denominator — refetch it.
        fetchContextUsage()
        handleSlashCommand("/model ${modelSwitchSpec(provider, model)}")
    }

    private fun modelSwitchSpec(
        provider: String,
        model: String,
    ): String = "$model --provider $provider --session"

    /**
     * Compare a server-reported session model with the outstanding picker selection.
     *
     * A match confirms and remembers the pick. A mismatch after a live acknowledgement means the
     * gateway attached an agent built with its previous model (the pick only reached a pending
     * session override), so the pick is applied once more against the now-built agent. A second
     * mismatch is surfaced instead of retrying, and the chip keeps the server-reported model.
     */
    private fun reconcileModelSelection(
        infoModel: String?,
        infoProvider: String?,
    ) {
        val selected = pendingChatSelection ?: return
        if (selected.generation != sessionGeneration || selected.scope != AuthManager.uiPreferenceScope()) {
            pendingChatSelection = null
            return
        }
        if (selected.matches(infoModel, infoProvider)) {
            AuthManager.writeUiPreference(selected.scope, "chat_model", "${selected.provider}/${selected.model}")
            pendingChatSelection = null
            return
        }
        if (selected.acknowledgedModel == null || infoModel == null) return
        if (!selected.reapplied) {
            pendingChatSelection = selected.copy(acknowledgedModel = null, reapplied = true)
            handleModelSwitch(selected.spec, rollbackModel = selected.previousModel)
            return
        }
        pendingChatSelection = null
        _uiState.update {
            it.copy(errorMessage = "選択したモデル（${selected.model}）がサーバーで適用されませんでした。現在のモデルは $infoModel です。")
        }
    }

    /** Undo the optimistic picker label after the server refused or never received the switch. */
    private fun revertModelSelection(
        previousModel: String?,
        message: String,
    ) {
        pendingChatSelection = null
        _uiState.update {
            it.copy(
                currentSessionModel = previousModel ?: it.currentSessionModel,
                errorMessage = message,
            )
        }
        syncCurrentModelCapabilities()
        fetchContextUsage()
    }

    fun dismissModelSwitchConfirm() {
        pendingChatSelection = null
        val pending = activeModelSwitchConfirmation
        activeModelSwitchConfirmation = null
        _uiState.update {
            it.copy(
                modelSwitchConfirmMessage = null,
                currentSessionModel = pending?.previousModel ?: it.currentSessionModel,
            )
        }
        syncCurrentModelCapabilities()
        fetchContextUsage()
    }

    fun confirmModelSwitchExpensive() {
        val pending = activeModelSwitchConfirmation
        activeModelSwitchConfirmation = null
        _uiState.update { it.copy(modelSwitchConfirmMessage = null) }
        if (pending != null) {
            handleModelSwitch(pending.spec, confirmExpensive = true, rollbackModel = pending.previousModel)
        }
    }

    /**
     * Set the reasoning effort level for the current session.
     *
     * Applies and remembers the level after the session-scoped `config.set` acknowledgement.
     * The current gateway cannot clear an override, so null must not pretend to reset it.
     *
     * @param level One of "low", "medium", "high", or null for default.
     */
    fun setReasoningLevel(level: String?) {
        if (level == null) {
            _uiState.update { it.copy(errorMessage = "現在の接続では、推論強度の既定値への復帰を確認できません。使う強度を明示して選択してください。") }
            return
        }
        val sessionId =
            runtimeSessionId ?: run {
                _uiState.update { it.copy(errorMessage = "セッションへの接続後に推論強度を選択してください。") }
                return
            }
        val selection = ReasoningSelection(AuthManager.uiPreferenceScope(), sessionGeneration, level)
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.CONFIG_SET,
                mapOf(
                    "key" to "reasoning",
                    "value" to level,
                    "session_id" to sessionId,
                ),
                onSent = { id ->
                    trackRequest(id, WsMethods.CONFIG_SET)
                    pendingReasoningSelections[id] = selection
                },
            )
        }
    }

    fun getModelCapabilities(
        providerSlug: String,
        modelName: String,
    ): ModelCapabilities? = cachedModelOptions.find { it.slug == providerSlug }?.capabilities?.get(modelName)

    fun getCurrentModelCapabilities(): ModelCapabilities? {
        val label = _uiState.value.currentSessionModel ?: return null
        val idx = label.indexOf('/')
        if (idx <= 0) return null
        val provider = label.substring(0, idx)
        val model = label.substring(idx + 1)
        return getModelCapabilities(provider, model)
    }

    private fun syncCurrentModelCapabilities() {
        val label = _uiState.value.currentSessionModel
        val caps =
            if (label == null) {
                null
            } else {
                val idx = label.indexOf('/')
                if (idx <= 0) {
                    null
                } else {
                    val provider = label.substring(0, idx)
                    val model = label.substring(idx + 1)
                    cachedModelOptions.find { it.slug == provider }?.capabilities?.get(model)
                }
            }
        _uiState.update { state ->
            if (state.currentModelCapabilities != caps) state.copy(currentModelCapabilities = caps) else state
        }
    }

    fun switchSession(sessionId: String) {
        val requestedTarget = submissionTransport.configuredScope()?.draftTargetKey()
        val selectedTarget = sessionScope?.draftTargetKey()
        if (sessionId == _uiState.value.currentSessionId && requestedTarget == selectedTarget) {
            // The same saved ID on another server/profile is a different transcript.
            // A socket-generation change within the same durable target still preserves
            // the current partial text while refreshing its runtime binding.
            if (submissionTransport.configuredScope()?.let(submissionTransport::isReady) == true) {
                resumeSession(sessionId, sessionGeneration)
            }
            return
        }

        // The id came from the gateway's own session list / picker — its row
        // is expected to exist, so resume it optimistically on reconnect even
        // before the REST page confirms (a transient 500 must not strand the
        // user on an un-resumable session). Only VM-created (never prompted)
        // sessions stay unconfirmed: see createNewSession.
        sessionHasServerPresence = true
        sessionGoneRecoveryInFlight = false
        pendingGoneSessionNotice = false
        val title =
            _uiState.value.sessions
                .find { it.id == sessionId }
                ?.title ?: "Hermes"
        val generation = resetSessionState(sessionId, title, isLoading = true)
        val cached = loadCachedMessages(sessionId, generation)
        viewModelScope.launch {
            // Warm-cache fast-path (desktop parity): paint the cached Room
            // transcript immediately so the screen never sits blank, then load
            // the fresh server transcript in parallel. If the cache is empty
            // the spinner stays up until the server page lands.
            // Bounded: the transcript actor is serialized, so rapid session switching
            // queues cache opens; never let that backlog hold the resume itself.
            withTimeoutOrNull(CACHE_PAINT_BUDGET_MS) { cached.join() }
            // Cancellation of a superseded cache consumer also releases join(). Do not
            // let that old selection resume the transport or change current queue gating.
            if (!isCurrentSessionRequest(sessionId, generation)) return@launch
            // Resume the selected desktop session and hydrate its transcript.
            resumeSession(sessionId, generation)
            loadSessions()
        }
    }

    private fun loadCachedMessages(
        sessionId: String,
        generation: Long,
    ): Job = openTranscriptCache(sessionId, generation, load = true)

    private fun openTranscriptCache(
        sessionId: String,
        generation: Long,
        load: Boolean,
        emptyLedger: ResponseLedger? = null,
        onOpened: (() -> Unit)? = null,
    ): Job {
        val target = (sessionScope ?: submissionTransport.configuredScope())?.draftTargetKey()
        transcriptOpenJob?.cancel()
        transcriptOpening = true
        val lease = TranscriptOwnerLease()
        // Admission is synchronous. A rewind claims and clears in this single ordered operation;
        // its new owner is not eligible for checkpoints until the empty manifest is durable.
        val result =
            target?.let {
                transcriptOperations.strict {
                    try {
                        withContext(ioDispatcher) {
                            repo.openTranscript(target, sessionId).also { (owner, _) ->
                                lease.capture(owner)
                                if (emptyLedger != null) {
                                    check(repo.save(repo.prepare(owner, emptyList(), emptyLedger))) {
                                        "Transcript rewind commit was rejected"
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        lease.abandon()
                        throw e
                    }
                }
            }
        return viewModelScope
            .launch {
                if (result == null) {
                    transcriptOpening = false
                    return@launch
                }
                val (owner, cached) =
                    try {
                        result.await()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (isCurrentSessionRequest(sessionId, generation)) {
                            transcriptOpening = false
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage =
                                        if (emptyLedger == null) {
                                            "Saved transcript could not be read; existing data preserved"
                                        } else {
                                            "Transcript could not be saved"
                                        },
                                )
                            }
                        }
                        return@launch
                    }
                if (!isCurrentSessionRequest(sessionId, generation) || !owner.valid ||
                    owner.target != submissionTransport.configuredScope()?.draftTargetKey()
                ) {
                    owner.valid = false
                    return@launch
                }
                transcriptOwner = owner
                lease.transfer()
                transcriptOpening = false
                if (load) {
                    _uiState.update { state ->
                        if (state.messages.isEmpty()) {
                            state.copy(
                                messages = cached.messages,
                                stagedCanonicalMessages = cached.stagedMessages,
                                responseLedger =
                                    cached.payload.ledger.gap().copy(
                                        requiresCorrelation =
                                            cached.payload.ledger.requiresCorrelation ||
                                                cached.messages.any {
                                                    it.role == MessageRole.USER ||
                                                        it.role == MessageRole.ASSISTANT
                                                },
                                    ),
                                isLoading = state.isLoading && cached.messages.isEmpty(),
                                todos = todoHistoryMemo.hydrate(cached.messages),
                            )
                        } else {
                            state
                        }
                    }
                }
                if (emptyLedger == null) persistTranscript()
                onOpened?.invoke()
            }.also { job ->
                transcriptOpenJob = job
                // Also runs if this consumer was canceled before its coroutine body ever started.
                job.invokeOnCompletion {
                    lease.abandon()
                    result?.cancel()
                }
            }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeStreamingCheckpoints() {
        viewModelScope.launch {
            // Keep UI deltas immediate. Quiet-period saves are replaceable only at the
            // pending tail; seals and terminal barriers remain strict and ordered.
            _streamingState.debounce(250L).collect { streaming ->
                val live = streaming.streamingMessage
                if (live != null && (live.content.isNotEmpty() || live.reasoningText.isNotEmpty())) persistTranscript()
            }
        }
    }

    /** Main-thread structural coordinator: capture the revision and immutable whole-cache state now. */
    private fun persistTranscript(): kotlinx.coroutines.CompletableDeferred<Boolean>? = enqueueTranscript(null)

    private fun commitTranscript(kind: TranscriptBarrier): kotlinx.coroutines.CompletableDeferred<Boolean>? =
        enqueueTranscript(kind)

    private fun enqueueTranscript(kind: TranscriptBarrier?): kotlinx.coroutines.CompletableDeferred<Boolean>? {
        val state = _uiState.value
        val session = state.currentSessionId ?: return null
        val owner = transcriptOwner
        if (owner == null) return null // Only explicit create/cache/undo may claim ownership.
        if (!owner.valid || owner.session != session ||
            owner.target != submissionTransport.configuredScope()?.draftTargetKey()
        ) {
            return null
        }
        val live =
            _streamingState.value.streamingMessage?.takeIf {
                (it.content.isNotEmpty() || it.reasoningText.isNotEmpty()) &&
                    state.messages.none { row -> row.id == it.id }
            }
        val messages = state.messages + listOfNotNull(live)
        val ledger = if (live == null) state.responseLedger else state.responseLedger.observe(listOf(live.id))
        val commit =
            TranscriptCommit(
                repo.prepare(owner, messages, ledger, state.stagedCanonicalMessages),
                sessionGeneration,
                terminalCommitResponseId.takeIf { terminalCommitPending },
            )
        val save: suspend () -> Boolean = {
            try {
                val saved = withContext(ioDispatcher) { repo.save(commit.write) }
                if (saved && commit.generation == sessionGeneration && commit.write.owner === transcriptOwner &&
                    commit.write.owner.valid &&
                    commit.write.owner.target == submissionTransport.configuredScope()?.draftTargetKey() &&
                    commit.write.owner.session == _uiState.value.currentSessionId &&
                    commit.terminalResponseId != null && commit.terminalResponseId == terminalCommitResponseId &&
                    commit.write.snapshot.payload.ledger.responses.any {
                        it.id == commit.terminalResponseId && it.finishedAt != null
                    } && terminalCommitPending
                ) {
                    terminalCommitPending = false
                    queueExecutionIdle = true
                    drainOutgoingQueue()
                }
                saved
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(errorMessage = "Transcript could not be saved") }
                throw e
            }
        }
        return if (kind == null) {
            transcriptOperations.checkpoint(owner, commit.generation, save)
        } else {
            transcriptOperations.strict(owner, save)
        }
    }

    private fun loadSessionMessages(
        sessionId: String,
        generation: Long,
        resyncScope: ConnectionScope? = null,
    ) {
        val requestSequence = ++hydrationRequestSequence
        activeHydrationRequestSequence = requestSequence
        val recoveryApi = resyncScope?.let { ApiClient.hermesApi }
        viewModelScope.launch {
            // Initial page: ask for the NEWEST page directly (order=latest —
            // offset is measured back from the newest message, page returned
            // chronologically; verified in hermes_state.py get_messages).
            // No count-based anchor, so a stale session-list message_count can
            // no longer land the page at the wrong position (issue #859).
            // Legacy backends without the `order` param ignore it and echo no
            // `pagination` — detect that and fall back to the count-based
            // anchor so nothing regresses.
            val latestResult =
                fetchMessagePage(
                    sessionId,
                    0,
                    MESSAGE_PAGE_SIZE,
                    order = "latest",
                    api = recoveryApi,
                    profile = resyncScope?.serverProfileId,
                )
            if (!isCurrentHydration(sessionId, generation, requestSequence, resyncScope)) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "drop stale hydration gen=${generation != sessionGeneration} " +
                            "seq=${requestSequence != activeHydrationRequestSequence} " +
                            "session=${sessionId != _uiState.value.currentSessionId}",
                    )
                }
                return@launch
            }
            val (result, requestedOffset) =
                if (latestResult is NetworkResult.Success && latestResult.data.pagination?.order == "latest") {
                    latestPaging = true
                    latestResult to 0
                } else if (latestResult is NetworkResult.Success) {
                    latestPaging = false
                    val messageCount =
                        if (recoveryApi != null) {
                            latestResult.data.total ?: withContext(ioDispatcher) {
                                val detail =
                                    safeApiCall {
                                        recoveryApi.getSessionDetail(sessionId, resyncScope.serverProfileId)
                                    }
                                (detail as? NetworkResult.Success)?.data?.message_count
                                    ?: latestResult.data.messages.size
                            }
                        } else {
                            fetchServerMessageCount(sessionId, generation, requestSequence)
                        }
                    if (!isCurrentHydration(sessionId, generation, requestSequence, resyncScope)) return@launch
                    val offset = (messageCount - MESSAGE_PAGE_SIZE).coerceAtLeast(0)
                    fetchMessagePage(
                        sessionId,
                        offset,
                        MESSAGE_PAGE_SIZE,
                        api = recoveryApi,
                        profile = resyncScope?.serverProfileId,
                    ) to offset
                } else {
                    latestPaging = false
                    latestResult to 0
                }
            if (!isCurrentHydration(sessionId, generation, requestSequence, resyncScope)) return@launch
            when (result) {
                is NetworkResult.Success -> {
                    // REST 200 — the gateway has the row for this session.
                    sessionHasServerPresence = true
                    val serverOffset = result.data.pagination?.offset ?: result.data.offset ?: requestedOffset
                    val chatMessages = result.data.messages.orEmpty()
                    loadedMessageOffset = serverOffset

                    if (!isCurrentHydration(sessionId, generation, requestSequence, resyncScope)) return@launch
                    _uiState.update { state ->
                        if (!isCurrentHydration(
                                sessionId,
                                generation,
                                requestSequence,
                                resyncScope,
                            )
                        ) {
                            return@update state
                        }
                        // Merge, don't replace: a reload mid-turn must not
                        // drop live WS bubbles (running tool call, streaming
                        // answer) the server hasn't persisted yet. Issue #771.
                        val reconciled =
                            reconcileTranscript(
                                state,
                                submissionTransport.configuredScope()?.draftTargetKey() ?: return@update state,
                                sessionId,
                                chatMessages,
                                serverOffset,
                                latestPaging,
                            )
                        val merged = reconciled.messages
                        val hasOlder =
                            if (latestPaging) {
                                // Newest-anchored: older messages exist iff the
                                // page came back FULL (a short page means we hit
                                // the oldest boundary).
                                val returned = result.data.pagination?.returned ?: chatMessages.size
                                returned >= MESSAGE_PAGE_SIZE && chatMessages.isNotEmpty()
                            } else {
                                serverOffset > 0 && chatMessages.isNotEmpty()
                            }
                        reconciled.copy(
                            messages = merged,
                            todos = todoHistoryMemo.hydrate(merged),
                            isLoading = false,
                            hasOlderMessages = hasOlder,
                            isLoadingOlder = false,
                        )
                    }
                    hydratedGeneration = generation
                    finishResumeWhenHydrated(generation)
                }

                is NetworkResult.Failure -> {
                    val errorMsg = result.error.message
                    val is404 =
                        errorMsg.contains("404", ignoreCase = true) ||
                            errorMsg.contains("not found", ignoreCase = true)
                    if (is404) {
                        // A 404 from GET /api/sessions/{id}/messages indicates the session has no
                        // persisted rows in state.db (e.g. newly created/lazy unprompted session, or empty history).
                        // It is NOT a fatal error: keep the session open with empty transcript, clear loading,
                        // and mark hydrated cleanly.
                        _uiState.update {
                            if (!isCurrentHydration(
                                    sessionId,
                                    generation,
                                    requestSequence,
                                    resyncScope,
                                )
                            ) {
                                return@update it
                            }
                            it.copy(
                                isLoading = false,
                                isLoadingOlder = false,
                                hasOlderMessages = false,
                            )
                        }
                        hydratedGeneration = generation
                        finishResumeWhenHydrated(generation)
                        return@launch
                    }
                    if (hydratedGeneration == generation) hydratedGeneration = -1L
                    _uiState.update {
                        if (!isCurrentHydration(sessionId, generation, requestSequence, resyncScope)) return@update it
                        it.copy(
                            isLoading = false,
                            isLoadingOlder = false,
                        )
                    }
                    // Route the transcript failure through the bounded resume
                    // retry (desktop parity) instead of a one-shot snackbar —
                    // a transient backend/network blip recovers on its own,
                    // and a persistent failure ends in an explicit Retry.
                    handleResumeFailure(
                        sessionId,
                        generation,
                        "Failed to load messages: ${result.error.message}",
                        resyncScope = resyncScope,
                    )
                }
            }
        }
    }

    // ── Session resume recovery (desktop parity) ─────────────────────────

    /**
     * Persists the in-flight streaming message as-is (isStreaming=false) so an
     * interrupted turn keeps the text the user already saw on screen. No-op
     * when there is no streaming content/reasoning to save. (Issue #842
     * follow-up: replaces the old tool.start orphan seal — the streaming
     * message now survives tool calls, so interrupts are the only path that
     * would otherwise drop the partial text.)
     */
    private fun sealStreamingMessageIfAny() {
        val streaming = _streamingState.value.streamingMessage ?: return
        if (streaming.content.isBlank() && streaming.reasoningText.isBlank()) return
        val finalized =
            streaming.copy(
                isStreaming = false,
                finishTimestamp = System.currentTimeMillis(),
            )
        _uiState.update { it.copy(messages = (it.messages + finalized).dedupeById()) }
        val sid = _uiState.value.currentSessionId
        if (sid != null) {
            commitTranscript(TranscriptBarrier.SEALED_SEGMENT)
        }
    }

    private fun resetSessionState(
        sessionId: String?,
        title: String,
        isLoading: Boolean,
    ): Long {
        // Preserve the latest displayed partial before retiring this selection.
        // The existing exact-target guard still rejects cross-profile writes.
        commitTranscript(TranscriptBarrier.NAVIGATION_FLUSH)
        replayResyncJob?.cancel()
        replayResyncOwner = null
        subagentSnapshotJob?.cancel()
        // Earlier structural snapshots drain before the next claim in the same actor.
        // Undo, unlike navigation, explicitly revokes its old owner.
        transcriptOwner = null
        transcriptOpening = false
        terminalCommitPending = false
        terminalCommitResponseId = null
        subagentSnapshotOwner = null
        subagentSnapshotFailures = 0
        queueExecutionIdle = false
        queueTurnObserved = false
        pendingCreateQueueOrigin = null
        val generation = ++sessionGeneration
        if (BuildConfig.DEBUG) Log.d(TAG, "resetSession gen=$generation loading=$isLoading hasId=${sessionId != null}")
        todoHistoryMemo.clear()
        pendingTerminals.clear()
        scopedProcessingVersion.value++
        pendingChatSelection = null
        pendingReasoningSelections.clear()
        cancelResumeDeadline()
        cancelResumeRetry()
        resumedGeneration = -1L
        hydratedGeneration = -1L
        runtimeSessionId = null
        hasObservedActiveResponse = false
        sessionScope = submissionTransport.configuredScope()
        editingDraftId = null
        releasePendingInitialPrompt()
        refreshDrafts()
        ActiveSessionHolder.clear()
        loadedMessageOffset = 0
        latestPaging = false
        isSyncingMessages = false
        streamingController.resetStreaming()
        _streamingState.value = StreamingState()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                responseLedger = ResponseLedger(),
                stagedCanonicalMessages = emptyList(),
                replyBoundariesReady = !isLoading,
                currentSessionId = sessionId,
                chatTitle = title,
                isAgentTyping = false,
                responseStatusUnknown = false,
                isThinking = false,
                thinkingText = "",
                isLoading = isLoading,
                isLoadingOlder = false,
                hasOlderMessages = false,
                streamingMessage = null,
                errorMessage = null,
                openError = null,
                clarifyRequest = null,
                sudoPrompt = null,
                secretPrompt = null,
                vaultPrompt = null,
                showSessionPicker = false,
                showModelPicker = false,
                modelPickerLoading = false,
                modelSwitchConfirmMessage = null,
                currentSessionModel = null,
                currentModelCapabilities = null,
                reasoningLevel = null,
                terminalBackend = null,
                usedContextTokens = null,
                fullContextTokens = null,
                contextBreakdown = null,
                compressionCount = null,
                pendingAttachments = emptyList(),
                composerTextToRestore = null,
                reactionKind = null,
                subagentIndicators = emptyList(),
                todos = emptyList(),
                resumeError = null,
                isResumeRetrying = false,
                pendingPrefillText = null,
            )
        }
        return generation
    }

    private fun resyncAfterReplayGap(
        scope: ConnectionScope,
        runtimeId: String,
    ) {
        val sessionId = _uiState.value.currentSessionId ?: return
        if (!sessionHasServerPresence || !isCurrentSession(runtimeId) || !submissionTransport.isReady(scope)) return
        val owner = scope to sessionId
        if (replayResyncOwner == owner && replayResyncJob?.isActive == true) return
        replayResyncJob?.cancel()
        cancelResumeRetry()
        resyncSession(scope, sessionId, sessionGeneration)
    }

    private fun resyncSession(
        scope: ConnectionScope,
        sessionId: String,
        generation: Long,
    ) {
        if (!isCurrentSessionRequest(sessionId, generation) || !submissionTransport.isReady(scope)) return
        val sequence = ++resumeRequestSequence
        activeResumeRequestSequence = sequence
        activeHydrationRequestSequence = ++hydrationRequestSequence
        resumedGeneration = -1L
        hydratedGeneration = -1L
        queueExecutionIdle = false
        val request =
            SessionRequest(
                generation,
                sequence,
                sessionId,
                scope,
                streamObservationVersion.get(),
                snapshotStartedAt = wsClient.snapshotObservationOrdinal(),
                resync = true,
            )
        replayResyncOwner = scope to sessionId
        replayResyncJob =
            viewModelScope.launch {
                val result =
                    try {
                        wsClient.resumeAfterReplayGap(scope, sessionId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (!isStaleSessionRequest(request)) {
                            handleResumeFailure(sessionId, generation, "Session resynchronization failed", scope)
                        }
                        return@launch
                    }
                if (isStaleSessionRequest(request)) return@launch
                val body = (result as? kotlinx.serialization.json.JsonElement)?.toAny() ?: result
                applySessionResume(body, request)
                if (resumedGeneration == generation) loadSessionMessages(sessionId, generation, resyncScope = scope)
            }
    }

    private fun resumeSession(
        sessionId: String,
        generation: Long,
    ) {
        cancelResumeDeadline()
        // A prior ACK in this generation must not let new hydration reset the retry budget.
        resumedGeneration = -1L
        queueExecutionIdle = false
        val requestSequence = ++resumeRequestSequence
        val streamVersion = streamObservationVersion.get()
        replayResyncJob?.cancel()
        replayResyncOwner = null
        activeResumeRequestSequence = requestSequence
        val profile = AuthManager.activeProfileId.value
        val params =
            mutableMapOf<String, Any>(
                "session_id" to sessionId,
                "omit_messages" to true,
            )
        if (!profile.isNullOrBlank()) {
            params["profile"] = profile
        }
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_RESUME,
                params,
                onSent = { id ->
                    trackSessionRequest(
                        id = id,
                        method = WsMethods.SESSION_RESUME,
                        generation = generation,
                        resumeSequence = requestSequence,
                        sessionId = sessionId,
                        streamVersion = streamVersion,
                    )
                    // Registration stays synchronous on the sender. Only timer ownership
                    // crosses to Main, without waiting under the transport's outbound lock.
                    val request = sessionRequestById[id]
                    if (request != null) {
                        viewModelScope.launch { armResumeDeadline(id, request) }
                    }
                },
            )
        }
        loadSessionMessages(sessionId, generation)
    }

    private fun isPendingResumeDeadline(
        id: String,
        request: SessionRequest,
    ): Boolean =
        idToMethod[id] == WsMethods.SESSION_RESUME &&
            sessionRequestById[id] === request &&
            request.resumeSequence == activeResumeRequestSequence &&
            request.generation == sessionGeneration &&
            request.sessionId == _uiState.value.currentSessionId &&
            request.scope != null &&
            request.scope == sessionScope &&
            request.scope == submissionTransport.configuredScope() &&
            submissionTransport.isReady(request.scope) &&
            wsClient.connectionStatus.value == ConnectionStatus.CONNECTED

    private fun armResumeDeadline(
        id: String,
        request: SessionRequest,
    ) {
        // ACK/error can consume registration before this Main turn. A superseded
        // sender must not cancel the timer belonging to the current request.
        if (!isPendingResumeDeadline(id, request)) return
        cancelResumeDeadline()
        resumeDeadlineId = id
        resumeDeadlineJob =
            viewModelScope.launch {
                delay(SESSION_RESUME_DEADLINE_MS)
                if (resumeDeadlineId != id || !isPendingResumeDeadline(id, request)) return@launch
                // Claim, forget, invalidate and fail atomically on Main: a late ACK
                // can no longer bind a runtime or drain preserved queued input.
                forgetRequest(id)
                resumeDeadlineId = null
                resumeDeadlineJob = null
                if (resumedGeneration == request.generation) resumedGeneration = -1L
                handleResumeFailure(
                    request.sessionId ?: return@launch,
                    request.generation,
                    "Session resume confirmation timed out",
                )
            }
    }

    private fun cancelResumeDeadline(id: String? = null) {
        if (id != null && id != resumeDeadlineId) return
        resumeDeadlineJob?.cancel()
        resumeDeadlineJob = null
        resumeDeadlineId = null
    }

    private fun cancelResumeRetry() {
        resumeRetryJob?.cancel()
        resumeRetryJob = null
        resumeRetrySessionId = null
        resumeRetryAttempt = 0
    }

    private fun finishResumeWhenHydrated(generation: Long) {
        if (generation != sessionGeneration ||
            resumedGeneration != generation ||
            hydratedGeneration != generation
        ) {
            return
        }
        cancelResumeRetry()
        _uiState.update {
            it.copy(
                isLoading = false,
                isResumeRetrying = false,
                resumeError = null,
                errorMessage = null,
            )
        }
    }

    private fun resumeRetryDelayMs(attempt: Int): Long =
        minOf(RESUME_RETRY_MAX_MS, RESUME_RETRY_BASE_MS * (1L shl attempt))

    /**
     * Bounded auto-retry for a failed session resume (mirrors the desktop's
     * use-route-resume). A failed resume — gateway RPC reject or REST
     * transcript failure — retries with exponential backoff (1s→2s→4s→8s),
     * capped at [MAX_RESUME_RETRIES]. After exhaustion the UI gets an
     * explicit error + manual Retry ([retryResumeSession]) instead of an
     * infinite spinner.
     *
     * Failures that PROVE the session is gone server-side — the resume RPC's
     * 4007 "session not found" (DB miss) and the REST transcript's 404 — are
     * terminal: retrying can never succeed, so they recover immediately with
     * a fresh chat ([recoverGoneSession]) instead of burning the budget.
     */

    private fun isDefinitiveSessionGone(message: String): Boolean =
        message.contains("session not found", ignoreCase = true) ||
            message.contains("404", ignoreCase = true)

    /**
     * The gateway definitively has no row for this session. Recover by
     * starting a fresh chat instead of dead-ending on a Retry button that
     * re-sends the same doomed key (the pre-fix behavior: 4007 popup whose
     * Retry never fixed anything). Dedupe: the WS reject and the REST 404 for
     * the same resume land close together — the first recovery switches
     * currentSessionId (on the create result), so a second call no-ops on the
     * sessionId guard; [sessionGoneRecoveryInFlight] closes the window before
     * that result lands.
     */
    private fun recoverGoneSession(sessionId: String) {
        if (_uiState.value.currentSessionId != sessionId) return
        if (sessionGoneRecoveryInFlight) return
        if (_uiState.value.messages.isNotEmpty()) {
            cancelResumeRetry()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isResumeRetrying = false,
                    resumeError = "Session not found on server (displaying cached messages)",
                )
            }
            return
        }
        sessionGoneRecoveryInFlight = true
        cancelResumeRetry()
        _uiState.update {
            it.copy(
                isLoading = false,
                isResumeRetrying = false,
                resumeError = null,
            )
        }
        // createNewSession() clears messages immediately — queue the notice
        // until its result lands so the user actually sees it.
        pendingGoneSessionNotice = true
        createNewSession(setLoading = false)
    }

    private fun handleResumeFailure(
        sessionId: String,
        generation: Long,
        errorMessage: String,
        resyncScope: ConnectionScope? = null,
    ) {
        // Only handle if still on this session.
        if (!isCurrentSessionRequest(sessionId, generation)) return
        _uiState.update { it.copy(errorMessage = null) }

        // New session → reset the counter for a fresh backoff cycle.
        if (resumeRetrySessionId != sessionId) {
            resumeRetrySessionId = sessionId
            resumeRetryAttempt = 0
        }

        // A definitive "session not found" (4007 RPC / 404 REST) is permanent:
        // no backoff will fix it — recover with a fresh chat right away.
        if (resyncScope == null && isDefinitiveSessionGone(errorMessage)) {
            recoverGoneSession(sessionId)
            return
        }

        if (resumeRetryAttempt >= MAX_RESUME_RETRIES) {
            // Exhausted — surface the error + manual Retry affordance.
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isResumeRetrying = false,
                    resumeError = errorMessage,
                    errorMessage = null,
                )
            }
            return
        }

        // A WS RPC reject and the REST transcript failure for the same resume
        // land close together — treat them as ONE failure: if a retry is
        // already armed for this session, don't double-count or re-schedule.
        if (resumeRetrySessionId == sessionId && resumeRetryJob?.isActive == true) {
            return
        }

        val delayMs = resumeRetryDelayMs(resumeRetryAttempt)
        resumeRetryAttempt++

        _uiState.update {
            it.copy(
                isLoading = false,
                isResumeRetrying = true,
                resumeError = null,
                errorMessage = null,
            )
        }

        resumeRetryJob?.cancel()
        resumeRetryJob =
            viewModelScope.launch {
                delay(delayMs)
                // Re-check liveness at fire time: the user may have switched
                // sessions or the gateway may have reconnected meanwhile.
                if (!isCurrentSessionRequest(sessionId, generation) ||
                    (resyncScope != null && !submissionTransport.isReady(resyncScope))
                ) {
                    return@launch
                }

                _uiState.update { it.copy(isResumeRetrying = false) }
                if (_uiState.value.messages.isEmpty()) {
                    _uiState.update { it.copy(isLoading = true) }
                }
                // Retry the full resume: rebind the runtime via WS + refresh
                // the transcript via REST. Both are idempotent.
                if (resyncScope == null) {
                    resumeSession(sessionId, generation)
                } else {
                    resyncSession(resyncScope, sessionId, generation)
                }
            }
    }

    /**
     * Manual retry after the bounded auto-retry exhausted. Clears the
     * exhausted latch and starts a fresh backoff cycle (mirrors the desktop's
     * resumeSession: reconnect / reselect / Retry all reset the counter).
     */
    fun retryResumeSession() {
        val sessionId = _uiState.value.currentSessionId ?: return
        val generation = sessionGeneration
        cancelResumeRetry()
        _uiState.update {
            it.copy(
                resumeError = null,
                isResumeRetrying = false,
                isLoading = true,
            )
        }
        resumeSession(sessionId, generation)
    }

    fun loadOlderMessages() {
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        val generation = sessionGeneration
        if (!state.hasOlderMessages || state.isLoadingOlder) return
        if (!latestPaging && loadedMessageOffset <= 0) return
        val oldOffset = loadedMessageOffset
        // latest: offsets count BACK from the newest message, so older pages go
        // UP; legacy: absolute offsets go DOWN toward 0 (issue #859).
        val newOffset =
            if (latestPaging) {
                oldOffset + MESSAGE_PAGE_SIZE
            } else {
                (oldOffset - MESSAGE_PAGE_SIZE).coerceAtLeast(0)
            }
        // Legacy: the final page can be short; requesting a full page at a
        // clamped offset OVERLAPS already-loaded rows (duplicate stable keys
        // crash LazyColumn), so size the request to the real gap. Latest:
        // pages are disjoint from-end ranges, so a full page never overlaps.
        val limit = if (latestPaging) MESSAGE_PAGE_SIZE else oldOffset - newOffset
        _uiState.update { it.copy(isLoadingOlder = true) }
        viewModelScope.launch {
            val result =
                fetchMessagePage(
                    sessionId,
                    newOffset,
                    limit,
                    order = if (latestPaging) "latest" else null,
                )
            when (result) {
                is NetworkResult.Success -> {
                    if (!isCurrentSessionRequest(sessionId, generation)) return@launch
                    val returnedOffset = result.data.pagination?.offset ?: result.data.offset ?: newOffset
                    val older = result.data.messages.orEmpty()
                    loadedMessageOffset = returnedOffset

                    _uiState.update { current ->
                        if (!isCurrentSessionRequest(sessionId, generation)) return@update current
                        val hasOlder =
                            if (latestPaging) {
                                // Full page = more older messages behind it; a
                                // short (or empty) page is the oldest boundary.
                                val returned = result.data.pagination?.returned ?: older.size
                                returned >= limit && older.isNotEmpty()
                            } else {
                                returnedOffset < oldOffset && older.isNotEmpty() && returnedOffset > 0
                            }
                        reconcileTranscript(
                            current,
                            submissionTransport.configuredScope()?.draftTargetKey() ?: return@update current,
                            sessionId,
                            older,
                            returnedOffset,
                            latestPaging,
                            older = true,
                        ).copy(
                            isLoadingOlder = false,
                            hasOlderMessages = hasOlder,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        if (isCurrentSessionRequest(sessionId, generation)) {
                            it.copy(isLoadingOlder = false)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    fun syncCurrentSession() {
        refreshSubagents()
        // Issue #840: a session created but never prompted has no server row
        // yet — the REST transcript 404s and the resume-retry machinery spins
        // forever (visible in logcat as repeated /messages 404s). MessageStart
        // flips the flag once the first prompt persists the row.
        if (!sessionHasServerPresence) return
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        val generation = sessionGeneration
        if (isSyncingMessages || state.isLoading || state.isLoadingOlder ||
            (!state.responseStatusUnknown && (state.isAgentTyping || _streamingState.value.streamingMessage != null))
        ) {
            return
        }
        val nextOffset =
            if (latestPaging) {
                // Newest-anchored paging can't compute an absolute
                // "after my last row" offset (from-end offsets shift as the
                // transcript grows), so refetch the newest page; the logical
                // merge below keeps existing copies and only adds new rows
                // (issue #859).
                0
            } else {
                state.messages
                    .mapNotNull { serverMessageIndex(it.id, sessionId) }
                    .maxOrNull()
                    ?.plus(1)
                    ?: loadedMessageOffset
            }
        isSyncingMessages = true
        viewModelScope.launch {
            try {
                val result =
                    fetchMessagePage(
                        sessionId,
                        nextOffset,
                        MESSAGE_PAGE_SIZE,
                        order = if (latestPaging) "latest" else null,
                    )
                when (result) {
                    is NetworkResult.Success -> {
                        if (!isCurrentSessionRequest(sessionId, generation)) return@launch
                        val incoming = result.data.messages.orEmpty()
                        if (incoming.isEmpty()) return@launch
                        _uiState.update { current ->
                            if (!isCurrentSessionRequest(sessionId, generation)) return@update current
                            reconcileTranscript(
                                current,
                                submissionTransport.configuredScope()?.draftTargetKey() ?: return@update current,
                                sessionId,
                                incoming,
                                nextOffset,
                                latestPaging,
                            )
                        }
                    }

                    is NetworkResult.Failure -> {}
                }
            } finally {
                if (generation == sessionGeneration) isSyncingMessages = false
            }
        }
    }

    /** Repair missed child completions independently of the parent turn's running/idle status. */
    fun refreshSubagents() {
        val scope = sessionScope ?: return
        val runtimeId = runtimeSessionId ?: return
        if (!submissionTransport.isReady(scope) || wsClient.connectionScope.value != scope) return
        val owner = scope to runtimeId
        if (subagentSnapshotOwner != owner) {
            subagentSnapshotJob?.cancel()
            subagentSnapshotOwner = owner
            subagentSnapshotFailures = 0
        }
        // Older servers may lack this RPC. Preserve their event-fed state without retrying forever.
        if (subagentSnapshotJob?.isActive == true || subagentSnapshotFailures >= 3) return
        val generation = sessionGeneration
        val before = _uiState.value.subagentIndicators
        subagentSnapshotJob =
            viewModelScope.launch {
                try {
                    val snapshot =
                        wsClient
                            .readSubagents(scope, runtimeId)
                            .await()
                            .toJsonElement()
                            .toAny()
                    val reconciled = reconcileSubagentSnapshot(before, snapshot)
                    if (reconciled == null) {
                        if (subagentSnapshotOwner == owner) subagentSnapshotFailures++
                        return@launch
                    }
                    if (generation != sessionGeneration || runtimeId != runtimeSessionId || scope != sessionScope ||
                        scope != wsClient.connectionScope.value
                    ) {
                        return@launch
                    }
                    subagentSnapshotFailures = 0
                    _uiState.update { current ->
                        // Streamed events and user actions observed after the read started win over that read.
                        if (current.subagentIndicators !== before || current.subagentIndicators == reconciled) {
                            current
                        } else {
                            current.copy(subagentIndicators = reconciled)
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (subagentSnapshotOwner == owner) subagentSnapshotFailures++
                }
            }
    }

    /**
     * Refresh occupancy from the live session, with session.usage as a fallback
     * when the detailed breakdown is unavailable. REST supplies only the model
     * window fallback and cumulative detail-sheet counters; it must not delay
     * the live meter or overwrite its session-specific window.
     */
    fun fetchContextUsage(skipRestFallback: Boolean = false) {
        val sessionId = _uiState.value.currentSessionId ?: return
        val profile = AuthManager.activeProfileId.value
        val scope = AuthManager.uiPreferenceScope()
        val generation = sessionGeneration
        val rpcSessionId = runtimeSessionId
        val model = _uiState.value.currentSessionModel
        val revision = contextUsageRevision.incrementAndGet()

        fun isCurrent(): Boolean =
            revision == contextUsageRevision.get() && generation == sessionGeneration &&
                sessionId == _uiState.value.currentSessionId && rpcSessionId == runtimeSessionId &&
                model == _uiState.value.currentSessionModel && scope == AuthManager.uiPreferenceScope()

        // REST can be slow or unavailable while the socket is healthy.
        viewModelScope.launch(ioDispatcher) {
            if (!skipRestFallback) {
                val fullResult = safeApiCall { ApiClient.hermesApi.getModelInfo() }
                if (fullResult is NetworkResult.Success) {
                    val full =
                        (
                            fullResult.data.effective_context_length
                                ?: fullResult.data.auto_context_length
                                ?: fullResult.data.config_context_length
                        )?.takeIf { it > 0L }
                    _uiState.update { current ->
                        if (isCurrent() && current.fullContextTokens == null) {
                            current.copy(fullContextTokens = full)
                        } else {
                            current
                        }
                    }
                }
            }
            if (!isCurrent()) return@launch
            val usedResult = safeApiCall { ApiClient.hermesApi.getSessionDetail(sessionId, profile) }
            if (usedResult is NetworkResult.Success) {
                val d = usedResult.data
                val used = d.input_tokens
                if (used != null) {
                    _uiState.update { current ->
                        if (isCurrent()) {
                            current.copy(
                                contextBreakdown =
                                    ContextBreakdown(
                                        inputTokens = used,
                                        outputTokens = d.output_tokens ?: 0L,
                                        cacheReadTokens = d.cache_read_tokens ?: 0L,
                                        cacheWriteTokens = d.cache_write_tokens ?: 0L,
                                        reasoningTokens = d.reasoning_tokens ?: 0L,
                                        messageCount = d.message_count ?: 0,
                                    ),
                            )
                        } else {
                            current
                        }
                    }
                }
            }
        }
        // Storage IDs are not registered in the live gateway until resume
        // confirms its runtime ID. Never query a different session after a wait.
        if (rpcSessionId == null) return
        viewModelScope.launch(ioDispatcher) {
            suspend fun request(method: String): Any? =
                try {
                    sendRpcAndAwait(method, mapOf("session_id" to rpcSessionId))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }

            val usageRequest = async { parseUsageSnapshot(request(WsMethods.SESSION_USAGE)) }
            val breakdown = parseContextBreakdown(request(WsMethods.SESSION_CONTEXT_BREAKDOWN))
            val used = breakdown?.contextUsed?.takeIf { it > 0L }
            val full = breakdown?.contextMax?.takeIf { it > 0L }
            _uiState.update { current ->
                if (isCurrent()) {
                    current.copy(
                        usedContextTokens = used ?: current.usedContextTokens,
                        fullContextTokens = full ?: current.fullContextTokens,
                    )
                } else {
                    current
                }
            }
            // Apply usage occupancy too, rather than discarding everything
            // except compressions when context_breakdown fails or is cold.
            val usage = usageRequest.await()
            _uiState.update { current ->
                if (isCurrent()) {
                    current.copy(
                        usedContextTokens =
                            used ?: usage?.contextUsed?.takeIf { it > 0L } ?: current.usedContextTokens,
                        fullContextTokens =
                            full ?: usage?.contextMax?.takeIf { it > 0L } ?: current.fullContextTokens,
                        compressionCount = usage?.compressions ?: current.compressionCount,
                    )
                } else {
                    current
                }
            }
        }
    }

    private suspend fun fetchServerMessageCount(
        sessionId: String,
        generation: Long,
        requestSequence: Long,
    ): Int {
        val known =
            _uiState.value.sessions
                .find { it.id == sessionId }
                ?.messageCount
        if (known != null) return known
        val result =
            withContext(ioDispatcher) {
                // Backend caps limit at 100 (sessions.py Query le=100) — 500
                // 422'd (seen in device logcat after a branch). Sessions are
                // ordered "recent", so the target is always in the top page.
                safeApiCall { ApiClient.hermesApi.getSessions(limit = 100, offset = 0, order = "recent") }
            }
        if (result is NetworkResult.Success) {
            val sessions = result.data.sessions.orEmpty()
            val count = sessions.find { it.id == sessionId }?.message_count
            if (count != null) {
                _uiState.update { current ->
                    if (isCurrentHydration(sessionId, generation, requestSequence)) {
                        current.copy(
                            sessions =
                                current.sessions.map {
                                    if (it.id == sessionId) {
                                        it.copy(messageCount = count)
                                    } else {
                                        it
                                    }
                                },
                        )
                    } else {
                        current
                    }
                }
                return count
            }
        }
        return known
            ?: if (isCurrentHydration(sessionId, generation, requestSequence)) {
                _uiState.value.messages.size
            } else {
                0
            }
    }

    private suspend fun fetchMessagePage(
        sessionId: String,
        offset: Int,
        limit: Int,
        order: String? = null,
        api: HermesApiService? = null,
        profile: String? = null,
    ) = withContext(ioDispatcher) {
        safeApiCall {
            (api ?: ApiClient.hermesApi).getSessionMessages(
                sessionId = sessionId,
                limit = limit,
                offset = offset,
                includeCompacted = true,
                order = order,
                profile = profile,
            )
        }
    }

    // ── UI actions ───────────────────────────────────────────────────────

    /** Explicit refusal uses the same exact-request ACK path as an answer. */
    fun dismissClarify(clarify: ClarifyUi) {
        respondToClarifyBatch(
            clarify,
            clarify.resolvedQuestions.filter { clarify.canRespond(it.qid) }.associate {
                it.qid to if (clarify.ref?.serverRequestId != null) null else CLARIFY_DISMISS_RESPONSE
            },
        )
    }

    fun respondToClarify(
        clarify: ClarifyUi,
        option: String,
    ) {
        val question = clarify.resolvedQuestions.singleOrNull() ?: return
        respondToClarifyBatch(clarify, mapOf(question.qid to option))
    }

    fun respondToClarifyBatch(
        clarify: ClarifyUi,
        answers: Map<String, String?>,
    ) {
        val request = clarify.ref ?: return
        for (question in clarify.resolvedQuestions) {
            if (!answers.containsKey(question.qid)) continue
            val answer = answers[question.qid]?.trim()
            if (answer != null && answer.isEmpty()) continue
            val ref = request.copy(questionId = if (clarify.questions.isEmpty()) clarify.questionId else question.qid)
            viewModelScope.launch(ioDispatcher) {
                if (_uiState.value.messages.none { it.clarifyInfo?.ref == request }) return@launch
                attentionRepository.respondClarification(
                    ref,
                    answer,
                    submissionTransport.configuredScope(),
                    submissionTransport.isReady(ref.scope),
                    System.currentTimeMillis(),
                    attentionTransport,
                )
            }
        }
    }

    private fun handleClarifyRequest(
        event: WsEvent.ClarifyRequest,
        scope: ConnectionScope,
    ) {
        if (!isCurrentAttentionSession(event.sessionId, scope)) return
        val runtimeId = event.sessionId?.takeIf { it.isNotBlank() }
        val requestId = event.clarifyId?.takeIf { it.isNotBlank() }
        val validQuestions =
            event.questionId?.isBlank() != true &&
                event.questions.all { it.qid.isNotBlank() } &&
                event.questions
                    .map { it.qid }
                    .distinct()
                    .size == event.questions.size
        val ref =
            if (runtimeId != null && requestId != null && validQuestions) {
                RequestRef(
                    scope,
                    SessionRef(runtimeId),
                    requestId,
                    questionId = event.questionId.takeIf { event.questions.isEmpty() },
                    serverRequestId = event.serverRequestId,
                )
            } else {
                null
            }
        val info =
            ClarifyUi(
                text = event.text.orEmpty(),
                options = event.options.orEmpty(),
                clarifyId = event.clarifyId,
                questionId = event.questionId,
                multiSelect = event.multiSelect,
                questions = event.questions.map { ClarifyQuestionUi(it.qid, it.question, it.choices, it.multiSelect) },
                ref = ref,
            )
        if (ref != null) {
            dashboardAttentionItems(scope, event).forEach(attentionRepository::observe)
            if (_uiState.value.messages.any { it.clarifyInfo?.ref == ref }) {
                refreshAttentionStates()
                return
            }
        }
        var added = false
        _uiState.update { state ->
            if (ref != null && state.messages.any { it.clarifyInfo?.ref == ref }) return@update state
            added = true
            state.copy(
                messages =
                    state.messages +
                        ChatMessage(
                            role = MessageRole.SYSTEM,
                            content = "確認への回答が必要です",
                            clarifyInfo = info,
                        ),
                clarifyRequest = if (ref != null) info else state.clarifyRequest,
                isAgentTyping = false,
                responseStatusUnknown = false,
            )
        }
        if (added) {
            _streamingState.update { StreamingState() }
            streamingController.resetStreaming()
        }
        refreshAttentionStates()
    }

    private fun settleClarification(
        sessionId: String?,
        clarifyId: String?,
        scope: ConnectionScope,
        resolved: Boolean,
    ) {
        val runtimeId = sessionId?.takeIf(String::isNotBlank) ?: return
        val requestId = clarifyId?.takeIf(String::isNotBlank) ?: return
        attentionRepository.settleClarification(scope, runtimeId, requestId, resolved)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearBackgroundComplete() {
        _uiState.update { it.copy(backgroundCompleteMessage = null) }
    }

    /** Consume the /resume · /history navigation request (issue #864). */
    fun consumeOpenHistoryRequest() {
        _uiState.update { it.copy(openHistoryRequested = false) }
    }

    // ── Approval flow ───────────────────────────────────────────────────

    private fun handleApprovalRequest(
        event: WsEvent.ApprovalRequest,
        sourceScope: ConnectionScope? = null,
    ) {
        if (!isCurrentAttentionSession(event.sessionId, sourceScope)) return
        val runtimeId = event.sessionId?.takeIf { it.isNotBlank() }
        val requestId = event.requestId?.takeIf { it.isNotBlank() }
        val ref =
            if (sourceScope != null && runtimeId != null && requestId != null) {
                RequestRef(sourceScope, SessionRef(runtimeId), requestId, serverRequestId = event.serverRequestId)
            } else {
                null
            }
        val description = event.description ?: event.command ?: "実行内容は未提供です"
        val item =
            sourceScope
                ?.let { dashboardAttentionItems(it, event).firstOrNull() }
                ?.let(attentionRepository::observe)
        val boundRef = item?.ref ?: ref
        val info =
            ApprovalInfo(
                command = event.command,
                description = event.description,
                patternKeys = event.patternKeys,
                requestId = event.requestId,
                choices = event.choices,
                allowPermanent = event.allowPermanent,
                smartDenied = event.smartDenied,
                ref = boundRef,
                status = item?.status ?: com.m57.hermescontrol.domain.hermes.AttentionStatus.UNKNOWN,
                responseEnabled = ref != null && submissionTransport.isReady(ref.scope),
            )
        var added = false
        _uiState.update { state ->
            if (boundRef != null) {
                val existing =
                    state.messages.indexOfFirst { message ->
                        val old = message.approvalInfo?.ref
                        old == boundRef || (
                            old != null && old.serverRequestId == null &&
                                old.scope == boundRef.scope && old.session.runtimeId == boundRef.session.runtimeId &&
                                old.requestId == boundRef.requestId
                        )
                    }
                if (existing >= 0) {
                    return@update state.copy(
                        messages =
                            state.messages.mapIndexed { index, message ->
                                if (index == existing) message.copy(approvalInfo = info) else message
                            },
                    )
                }
            }
            added = true
            state.copy(
                messages =
                    state.messages +
                        ChatMessage(
                            role = MessageRole.SYSTEM,
                            content = "承認が必要です\n$description",
                            approvalInfo = info,
                        ),
                isAgentTyping = false,
                responseStatusUnknown = false,
            )
        }
        if (added && ref != null) {
            viewModelScope.launch(ioDispatcher) { attentionTransport.acknowledgeApproval(ref) }
        }
    }

    private fun refreshClarification(info: ClarifyUi): ClarifyUi {
        val ref = info.ref
        val items = attentionRepository.items.value
        val questions =
            info.resolvedQuestions.associate { question ->
                val questionRef =
                    ref?.copy(
                        questionId = if (info.questions.isEmpty()) info.questionId else question.qid,
                    )
                question.qid to items.firstOrNull { it.ref == questionRef }
            }
        return info.copy(
            responseEnabled =
                ref != null && ref.scope == submissionTransport.configuredScope() &&
                    submissionTransport.isReady(ref.scope),
            questionLocks =
                info.resolvedQuestions
                    .mapNotNull { question ->
                        val questionRef =
                            ref?.copy(
                                questionId = if (info.questions.isEmpty()) info.questionId else question.qid,
                            )
                        attentionRepository.items.value.firstOrNull { it.ref == questionRef }?.clarificationLock?.let {
                            question.qid to
                                it
                        }
                    }.toMap(),
            questionStates =
                questions.mapValues { (_, item) ->
                    item?.status ?: com.m57.hermescontrol.domain.hermes.AttentionStatus.UNKNOWN
                },
            lockedAnswers =
                questions
                    .mapNotNull { (qid, item) ->
                        item?.details?.lockedAnswer?.let { qid to it }
                    }.toMap(),
        )
    }

    private fun refreshAttentionStates() {
        val items = attentionRepository.items.value.associateBy { it.ref }
        _uiState.update { state ->
            state.copy(
                clarifyRequest =
                    state.clarifyRequest?.let(::refreshClarification)?.takeUnless { info ->
                        info.questionStates.values.all {
                            it == com.m57.hermescontrol.domain.hermes.AttentionStatus.CONFIRMED ||
                                it == com.m57.hermescontrol.domain.hermes.AttentionStatus.EXPIRED
                        }
                    },
                messages =
                    state.messages.map { message ->
                        val clarify = message.clarifyInfo
                        if (clarify != null) return@map message.copy(clarifyInfo = refreshClarification(clarify))
                        val info = message.approvalInfo ?: return@map message
                        val item = items[info.ref]
                        message.copy(
                            approvalInfo =
                                info.copy(
                                    status = item?.status ?: info.status,
                                    responseEnabled =
                                        info.ref?.let {
                                            it.scope == submissionTransport.configuredScope() &&
                                                submissionTransport.isReady(it.scope)
                                        } == true,
                                ),
                        )
                    },
            )
        }
    }

    /**
     * Convert a backend approval map (snake_case, from `approval.pending` or
     * `session.info` `pending_approval`) into a typed event. Mirrors
     * [EventParser] defaults so replay and live paths agree.
     */
    private fun parseApprovalMap(
        map: Map<*, *>,
        sessionId: String?,
    ): WsEvent.ApprovalRequest = EventParser.parseApprovalRequest(map, sessionId)

    /**
     * Reconnect replay (desktop `replayPendingApproval` parity): ask the
     * gateway for all unresolved approvals using the fixed resumed session.
     * This read never resends an answer or resets an uncertain response.
     */
    private fun replayPendingApproval(sessionId: String) {
        val scope = sessionScope ?: return
        val runtimeId = runtimeSessionId ?: return
        val generation = sessionGeneration
        if (sessionId != runtimeId && sessionId != _uiState.value.currentSessionId) return
        viewModelScope.launch(ioDispatcher) {
            try {
                val result =
                    wsClient
                        .writeScoped(
                            scope,
                            WsMethods.APPROVAL_PENDING,
                            mapOf("session_id" to runtimeId),
                        ).response
                        .await()
                if (generation != sessionGeneration || !submissionTransport.isReady(scope)) return@launch
                handleApprovalPendingResult(result.toJsonElement().toAny(), scope, runtimeId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A read failure cannot authorize a response or resend one.
            }
        }
    }

    private fun handleApprovalPendingResult(
        result: Any?,
        scope: ConnectionScope? = null,
        runtimeId: String? = null,
    ) {
        val approvals = (result as? Map<*, *>)?.get("approvals") as? List<*> ?: return
        for (approval in approvals.filterIsInstance<Map<*, *>>()) {
            handleApprovalRequest(parseApprovalMap(approval, runtimeId), scope)
        }
    }

    fun respondToApproval(
        messageId: String,
        action: String,
    ) {
        if (action !in setOf("once", "deny")) return
        val approval =
            _uiState.value.messages
                .firstOrNull { it.id == messageId }
                ?.approvalInfo ?: return
        val ref = approval.ref ?: return
        if (approval.choices?.let { action !in it } == true) return
        viewModelScope.launch(ioDispatcher) {
            attentionRepository.respond(
                ref = ref,
                currentScope = submissionTransport.configuredScope(),
                ready = submissionTransport.isReady(ref.scope),
                now = System.currentTimeMillis(),
            ) { attentionTransport.respondApproval(ref, action) }
        }
    }

    // ── Sudo / secret prompt flow (issue #524) ──────────────────────────

    fun respondToVaultPrompt(
        prompt: VaultPromptUi,
        value: String,
    ): Boolean {
        if (_uiState.value.vaultPrompt != prompt || !isCurrentSession(prompt.request.sessionId) ||
            submissionTransport.configuredScope() != prompt.scope
        ) {
            return false
        }
        val sent = wsClient.respondToVaultPrompt(prompt.scope, prompt.request.id, value)
        _uiState.update { state ->
            if (state.vaultPrompt !=
                prompt
            ) {
                state
            } else {
                state.copy(vaultPrompt = if (sent) null else prompt.copy(sendFailed = true))
            }
        }
        return sent
    }

    /**
     * The agent needs the user's sudo password. Previously dropped → agent
     * hung forever. Now we surface a secure dialog and reply via sudo.respond.
     */
    private fun handleSudoRequest(event: WsEvent.SudoRequest) {
        _uiState.update {
            it.copy(
                sudoPrompt = SudoPromptUi(event.requestId, event.sessionId),
                isAgentTyping = false,
                responseStatusUnknown = false,
            )
        }
    }

    /**
     * Backend sudo timeout (120s) — clear only the matching dialog so a
     * late expire for an old prompt never kills the current one.
     * Desktop parity: `patchOverlayState(prev => prev.sudo?.requestId === id ? null : prev)`.
     */
    private fun handleSudoExpire(event: WsEvent.SudoExpire) {
        _uiState.update { state ->
            val current = state.sudoPrompt ?: return@update state
            if (event.requestId != null && current.requestId != null &&
                event.requestId != current.requestId
            ) {
                return@update state
            }
            state.copy(sudoPrompt = null)
        }
    }

    /**
     * The agent needs a secret value (token/password). Previously dropped →
     * agent hung forever. Now we surface a secure dialog and reply via
     * secret.respond.
     */
    private fun handleSecretRequest(event: WsEvent.SecretRequest) {
        _uiState.update {
            it.copy(
                secretPrompt =
                    SecretPromptUi(
                        event.requestId,
                        event.sessionId,
                        event.envVar,
                        event.prompt,
                    ),
                isAgentTyping = false,
                responseStatusUnknown = false,
            )
        }
    }

    /**
     * Backend secret timeout — match-only clear like [handleSudoExpire].
     */
    private fun handleSecretExpire(event: WsEvent.SecretExpire) {
        _uiState.update { state ->
            val current = state.secretPrompt ?: return@update state
            if (event.requestId != null && current.requestId != null &&
                event.requestId != current.requestId
            ) {
                return@update state
            }
            state.copy(secretPrompt = null)
        }
    }

    /**
     * Cancel → send empty password (desktop parity:
     * `prompt-overlays.tsx` `send('')`). Backend treats empty sudo as
     * failed sudo (no command runs), so closing the dialog is a safe
     * refusal that unblocks the turn instantly instead of hanging 120s
     * until `sudo.expire`.
     */
    fun dismissSudo() {
        val prompt = _uiState.value.sudoPrompt ?: return
        val sessionId = prompt.sessionId ?: _uiState.value.currentSessionId
        _uiState.update { it.copy(sudoPrompt = null) }
        if (sessionId == null) return
        viewModelScope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "password" to "",
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            wsClient.send(
                method = WsMethods.SUDO_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.SUDO_RESPOND) },
            )
        }
    }

    /**
     * Cancel → send empty value (desktop parity). Backend `secret_cb`
     * returns `skipped=True` on empty, unblocking the turn instantly.
     */
    fun dismissSecret() {
        val prompt = _uiState.value.secretPrompt ?: return
        val sessionId = prompt.sessionId ?: _uiState.value.currentSessionId
        _uiState.update { it.copy(secretPrompt = null) }
        if (sessionId == null) return
        viewModelScope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "value" to "",
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            wsClient.send(
                method = WsMethods.SECRET_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.SECRET_RESPOND) },
            )
        }
    }

    /**
     * Send the user's sudo password back to the gateway. Mirrors
     * respondToApproval: clear the prompt immediately, then fire the RPC.
     */
    fun respondToSudo(password: String) {
        val prompt = _uiState.value.sudoPrompt ?: return
        val sessionId = prompt.sessionId ?: _uiState.value.currentSessionId ?: return
        if (password.isBlank()) return

        _uiState.update { it.copy(sudoPrompt = null) }

        viewModelScope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "password" to password,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            wsClient.send(
                method = WsMethods.SUDO_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.SUDO_RESPOND) },
            )
        }
    }

    /**
     * Send the user's secret value back to the gateway. Mirrors respondToSudo.
     */
    fun respondToSecret(value: String) {
        val prompt = _uiState.value.secretPrompt ?: return
        val sessionId = prompt.sessionId ?: _uiState.value.currentSessionId ?: return
        if (value.isBlank()) return

        _uiState.update { it.copy(secretPrompt = null) }

        viewModelScope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "value" to value,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            wsClient.send(
                method = WsMethods.SECRET_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.SECRET_RESPOND) },
            )
        }
    }

    fun reconnect() {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
            )
        }
        viewModelScope.launch(ioDispatcher) {
            wsClient.rejectAllPending()
            wsClient.disconnect()
        }
        viewModelScope.launch {
            delay(500)
            connectWebSocket(setLoading = true)
        }
    }

    fun relogin(
        username: String,
        password: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val endpoint = AuthManager.endpointForBuild()
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val jsonBody =
                JSONObject()
                    .put("provider", "basic")
                    .put("username", username)
                    .put("password", password)
                    .put("next", "")
                    .toString()

            try {
                val loginClient =
                    com.m57.hermescontrol.data.remote.OkHttpProvider.probe
                        .newBuilder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                val loginReq =
                    Request
                        .Builder()
                        .url(endpoint.resolve("auth/password-login").toString())
                        .header("Content-Type", "application/json")
                        .post(jsonBody.toRequestBody(jsonMediaType))
                        .build()
                loginClient.newCall(loginReq).execute().use { loginResp ->
                    if (!loginResp.isSuccessful) {
                        val msg =
                            when (loginResp.code) {
                                401 -> "Invalid username or password (401)"
                                403 -> "Forbidden (403)"
                                else -> "HTTP error code: ${loginResp.code}"
                            }
                        withContext(Dispatchers.Main) {
                            onResult(false, msg)
                        }
                        return@launch
                    }
                }

                val ticketClient =
                    com.m57.hermescontrol.data.remote.OkHttpProvider.base
                        .newBuilder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                val ticketReq =
                    Request
                        .Builder()
                        .url(endpoint.resolve("api/auth/ws-ticket").toString())
                        .post("{}".toRequestBody(jsonMediaType))
                        .build()
                ticketClient.newCall(ticketReq).execute().use { ticketResp ->
                    if (!ticketResp.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            onResult(false, "Failed to mint WS ticket: HTTP ${ticketResp.code}")
                        }
                        return@launch
                    }

                    val body = ticketResp.body.string()
                    val ticket = JSONObject(body).optString("ticket").takeIf { it.isNotBlank() }

                    if (ticket.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            onResult(false, "Invalid ticket returned from server")
                        }
                        return@launch
                    }

                    AuthManager.setWsAuthParam("ticket")
                    AuthManager.setToken(ticket)

                    withContext(Dispatchers.Main) {
                        onResult(true, null)
                        reconnect()
                    }
                }
            } catch (e: java.io.IOException) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection failed: ${e.message}")
                }
            } catch (e: org.json.JSONException) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection failed: ${e.message}")
                }
            }
        }
    }

    private fun addSystemMessage(
        text: String,
        persist: Boolean = false,
    ) {
        val msg = ChatMessage(role = MessageRole.SYSTEM, content = text)
        val sessionId = _uiState.value.currentSessionId

        _uiState.update { it.copy(messages = it.messages + msg) }

        // Persist — OUTSIDE update{}
        if (persist && sessionId != null) {
            persistTranscript()
        }
    }

    // ── Pending request tracking ─────────────────────────────────────────

    private fun trackRequest(
        id: String,
        method: String,
    ) {
        idToMethod[id] = method
        if (method in setOf(WsMethods.COMMAND_DISPATCH, WsMethods.SLASH_EXEC, WsMethods.PROMPT_BTW)) {
            sessionRequestById.putIfAbsent(
                id,
                SessionRequest(
                    sessionGeneration,
                    sessionId = _uiState.value.currentSessionId,
                    scope = submissionTransport.configuredScope(),
                ),
            )
        }
    }

    private fun trackSessionRequest(
        id: String,
        method: String,
        generation: Long,
        resumeSequence: Long = 0L,
        sessionId: String? = null,
        streamVersion: Long = -1L,
    ) {
        sessionRequestById[id] =
            SessionRequest(
                generation,
                resumeSequence,
                sessionId,
                submissionTransport.configuredScope(),
                streamVersion,
                snapshotStartedAt = wsClient.snapshotObservationOrdinal(),
            )
        trackRequest(id, method)
    }

    private fun isCurrentSessionRequest(
        sessionId: String,
        generation: Long,
    ): Boolean = generation == sessionGeneration && sessionId == _uiState.value.currentSessionId

    private fun isCurrentHydration(
        sessionId: String,
        generation: Long,
        requestSequence: Long,
        scope: ConnectionScope? = null,
    ): Boolean =
        requestSequence == activeHydrationRequestSequence && isCurrentSessionRequest(sessionId, generation) &&
            (scope == null || submissionTransport.isReady(scope))

    private fun isStaleSessionRequest(id: String): Boolean =
        sessionRequestById[id]?.let(::isStaleSessionRequest) == true

    private fun staleReason(request: SessionRequest): String =
        "gen=${request.generation != sessionGeneration} " +
            "scope=${request.scope != null && !submissionTransport.isReady(request.scope)} " +
            "session=${request.sessionId != null && request.sessionId != _uiState.value.currentSessionId} " +
            "resumeSeq=${request.resumeSequence != 0L && request.resumeSequence != activeResumeRequestSequence}"

    private fun isStaleSessionRequest(request: SessionRequest): Boolean =
        request.generation != sessionGeneration ||
            (request.scope != null && !submissionTransport.isReady(request.scope)) ||
            (request.sessionId != null && request.sessionId != _uiState.value.currentSessionId) ||
            (request.resumeSequence != 0L && request.resumeSequence != activeResumeRequestSequence)

    private fun forgetRequest(id: String) {
        idToMethod.remove(id)
        sessionRequestById.remove(id)
    }

    // ── Search ────────────────────────────────────────────────────────────
    // Compatibility façade: stable public API around ChatSearchDelegate.
    // These thin delegates keep ChatViewModel's public surface intact while
    // the search logic now lives in the delegate. Safe to remove once all
    // callers migrate directly to the delegate.

    fun toggleSearch() = searchDelegate.toggleSearch()

    fun setSearchQuery(query: String) = searchDelegate.setSearchQuery(query)

    fun navigateSearchMatch(direction: Int) = searchDelegate.navigateSearchMatch(direction)

    fun clearSearch() = searchDelegate.clearSearch()

    private var isTestEnv: Boolean? = null

    private fun isTestEnvironment(): Boolean {
        if (isTestEnv == null) {
            isTestEnv =
                try {
                    Class.forName("org.junit.Test")
                    true
                } catch (e: ClassNotFoundException) {
                    false
                }
        }
        return isTestEnv == true
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCleared() {
        cancelResumeDeadline()
        activeResumeRequestSequence = ++resumeRequestSequence
        todoHistoryMemo.clear()
        transcriptOwner?.valid = false
        transcriptOpenJob?.cancel()
        transcriptOperations.close()
        super.onCleared()
        // PERF-16: Don't disconnect the global HermesWsClient singleton when
        // leaving the Chat screen — it's used by background notification reply.
    }

    companion object {
        private const val SESSION_RESUME_DEADLINE_MS = 15_000L

        /** Longer than the resume deadline, so the normal failure path reports first. */
        private const val LOADING_WATCHDOG_MS = 18_000L

        /** How long a session switch waits for the cached transcript before resuming anyway. */
        private const val CACHE_PAINT_BUDGET_MS = 1_500L

        /** Exact title of the profile's forever conversation (Hermes core `BOT_CHAT_TITLE`). */
        const val BOT_CHAT_TITLE = "Bot Chat"

        /** Main-conversation lookup budget; past it the launch falls back to a new session. */
        private const val MAIN_CONVERSATION_LOOKUP_MS = 5_000L

        /** Pause before the single retry of an unknown main-conversation lookup. */
        private const val MAIN_CONVERSATION_RETRY_MS = 1_000L

        /** Max auto-retry attempts for a failed session.resume (desktop parity). */
        const val MAX_RESUME_RETRIES = 4

        /** Base backoff for resume retries — doubles per attempt, capped at 8s. */
        const val RESUME_RETRY_BASE_MS = 1_000L

        /** Upper bound for the resume retry backoff delay. */
        const val RESUME_RETRY_MAX_MS = 8_000L
    }
}
