package com.m57.hermescontrol.ui.bots.group

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.GroupChatRoomLease
import com.m57.hermescontrol.data.model.GroupChatRoomMeta
import com.m57.hermescontrol.data.model.GroupChatSyncFrom
import com.m57.hermescontrol.data.model.GroupChatSyncLogEntry
import com.m57.hermescontrol.data.model.GroupChatSyncSnapshot
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.GroupHistoryIssue
import com.m57.hermescontrol.data.ws.GroupMemberSession
import com.m57.hermescontrol.data.ws.GroupSessionTransport
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.HermesWsClient.HermesRpcException
import com.m57.hermescontrol.data.ws.OfflineSendPolicy
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsGroupSessionTransport
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.ui.chat.tool.ToolResultSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

data class GroupChatUiState(
    val groupName: String = "",
    val displayName: String = groupName,
    val members: List<ProfileInfo> = emptyList(),
    val messages: List<GroupChatMessage> = emptyList(),
    val activeSpeaker: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val sendIssue: GroupSendIssue? = null,
    val isSending: Boolean = false,
    val maxBotMessages: Int = DEFAULT_MAX_BOT_MESSAGES,
    val maxContinuationPasses: Int = DEFAULT_MAX_CONTINUATION_PASSES,
    val systemPrompt: String? = null,
    val settingsSaveFailed: Boolean = false,
    val isSavingGroupSettings: Boolean = false,
    val speakingOrder: List<String>? = null,
    val finalSummaryMember: String? = null,
    val discussionRounds: Int = DEFAULT_DISCUSSION_ROUNDS,
    val conversationId: String = "",
    val conversations: List<GroupConversation> = emptyList(),
    val draft: String = "",
    val isReconciling: Boolean = false,
    val reconciliationStatus: GroupReconciliationStatus? = null,
    val sendRpcErrorCode: Int? = null,
    val serverPending: Boolean = false,
)

private data class GroupTurnResult(
    val reply: String?,
)

const val DEFAULT_DISCUSSION_ROUNDS = 1
const val MAX_ALLOWED_DISCUSSION_ROUNDS = 10
const val DEFAULT_MAX_BOT_MESSAGES = 6
const val DEFAULT_MAX_CONTINUATION_PASSES = 2
const val MAX_ALLOWED_BOT_MESSAGES = 50
const val MAX_ALLOWED_CONTINUATION_PASSES = 20
const val WARN_MAX_BOT_MESSAGES = 20
const val WARN_MAX_CONTINUATION_PASSES = 6
private const val LEASE_STEP_TTL_MS = 180_000L
private const val GROUP_TURN_TIMEOUT_MS = 180_000L
private const val GROUP_TURN_HARD_CAP_MS = 20 * 60_000L
private const val HISTORY_LIMIT = 10

class GroupChatViewModel(
    private var groupName: String = "",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val groupTransport: GroupSessionTransport = WsGroupSessionTransport(),
    private val conversationStore: GroupConversationStore = MemoryGroupConversationStore(),
    private val conversationScope: () -> String? = {
        groupTransport.connectionScope.value?.let {
            listOf(it.serverIdentity, it.connectionProfileId, it.serverProfileId).joinToString("\u0000")
        }
    },
    private val turnClock: () -> Long = System::currentTimeMillis,
    private val serverTransport: GroupServerTransport? = null,
    private val titleGenerator: (suspend (String) -> String?)? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupChatUiState(groupName = groupName))
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    private val localDriverId = "android_" + UUID.randomUUID().toString().take(8)
    private var activeEpoch: String = ""
    private var sendJob: Job? = null
    private var draftSaveJob: Job? = null
    private var currentUserMessageId: String? = null
    private var leaseEpoch: String? = null
    private var routeEstablishedEpoch: String? = null
    private val failedMembers = mutableSetOf<String>()
    private var archive: GroupConversationArchive? = null
    private var archiveKey: String? = null
    private var storageFailed = false
    private var pendingConversationId: String? = null
    private var pendingNewConversationId: String? = null
    private val reconciliationMutex = Mutex()
    private var reconciliationJob: Job? = null
    private var serverPoll: Job? = null
    private var activeReaderId: String? = null
    private val titleJobs = mutableMapOf<String, Job>()
    private val titleAttempts = mutableMapOf<String, Long>()

    private fun conversation(): GroupConversation? = archive?.conversations?.find { it.id == archive?.activeId }

    /** Read-only projection; no store write, socket access, history check or reconstruction. */
    internal fun sendDiagnostics(userMessageId: String): GroupDiagnosticSnapshot? {
        if (storageFailed) return null
        val key = archiveKey ?: return null
        if (key != "${conversationScope()}\u0000$groupName") return null
        val room = conversation()?.takeIf { it.id == _uiState.value.conversationId } ?: return null
        return groupDiagnosticSnapshot(room, userMessageId, key)
    }

    private fun storageIssue() {
        storageFailed = true
        _uiState.update { it.copy(sendIssue = GroupSendIssue.STORAGE_UNAVAILABLE) }
    }

    private fun openArchive(): Boolean {
        val scope = conversationScope() ?: return false
        val key = "$scope\u0000$groupName"
        if (key == archiveKey && archive != null) return !storageFailed
        return try {
            val restored = conversationStore.read(key)
            val legacy = GroupConversation(id = legacyGroupConversationId(key))
            archive = restored?.copy(conversations = restored.conversations.map { it.recoverInterrupted() })
                ?: GroupConversationArchive(activeId = legacy.id, conversations = listOf(legacy))
            archiveKey = key
            storageFailed = false
            showConversation()
            true
        } catch (_: Exception) {
            storageIssue()
            false
        }
    }

    private fun showConversation() {
        val current = conversation() ?: return
        _uiState.update {
            it.copy(
                displayName = archive?.displayName?.takeIf { name -> name.isNotBlank() } ?: groupName,
                conversationId = current.id,
                conversations = archive?.conversations.orEmpty(),
                members = _uiState.value.members.ifEmpty { archive?.members.orEmpty() },
                messages = current.messages,
                draft = current.draft,
                serverPending = current.pendingServerRequest != null,
                reconciliationStatus = null,
                sendRpcErrorCode = null,
                sendIssue =
                    if (current.turns.any { turn ->
                            turn.delivery in unresolvedDeliveries
                        }
                    ) {
                        GroupSendIssue.UNKNOWN
                    } else {
                        null
                    },
            )
        }
        generateConversationTitle()
    }

    /** Reuse the app's title-only generator; naming never drives a bot turn or blocks chat. */
    private fun generateConversationTitle() {
        val generate = titleGenerator ?: return
        val key = archiveKey ?: return
        val owner = conversationScope() ?: return
        val room = conversation() ?: return
        val first =
            room.messages
                .firstOrNull { it.isUser && !it.isSystem }
                ?.text
                ?.takeIf { it.isNotBlank() } ?: return
        if (!room.generatedTitle.isNullOrBlank() || (room.title.isNotBlank() && room.title != first.take(60))) return
        val group = groupName
        val jobKey = "generate:$key\u0000${room.id}"
        val now = turnClock()
        if (titleJobs[jobKey]?.isActive == true || titleAttempts[jobKey]?.let { now - it < 3_600_000L } == true) return
        titleAttempts[jobKey] = now
        titleJobs[jobKey] =
            viewModelScope.launch(ioDispatcher) {
                try {
                    if (conversationScope() != owner) return@launch
                    val title = generate(first)?.trim()?.take(40)?.takeIf { it.isNotBlank() } ?: return@launch
                    if (conversationScope() != owner) return@launch
                    // A result belongs to the original conversation, even after selecting another room.
                    val latest = (if (archiveKey == key) archive else conversationStore.read(key)) ?: return@launch
                    val target = latest.conversations.find { it.id == room.id } ?: return@launch
                    if (!target.generatedTitle.isNullOrBlank()) return@launch
                    if (target.title.isNotBlank() && target.title != room.title &&
                        target.title != first.take(60)
                    ) {
                        return@launch
                    }
                    val named = target.copy(title = title, generatedTitle = title)
                    val next =
                        latest.copy(
                            conversations =
                                latest.conversations.map {
                                    if (it.id ==
                                        room.id
                                    ) {
                                        named
                                    } else {
                                        it
                                    }
                                },
                        )
                    if (archiveKey == key) {
                        if (!writeArchive(next)) return@launch
                    } else {
                        conversationStore.write(key, next)
                    }
                    if (target.pendingServerRequest ==
                        null
                    ) {
                        syncConversationTitle(key, owner, group, named, target.title)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the first-message fallback. No paid fallback or automatic request retry.
                } finally {
                    titleJobs.remove(jobKey)
                }
            }
    }

    private fun syncConversationTitle(
        key: String,
        owner: String,
        group: String,
        room: GroupConversation,
        expectedTitle: String,
    ) {
        val transport = serverTransport ?: return
        val title = room.generatedTitle?.takeIf { it.isNotBlank() && it != expectedTitle } ?: return
        val jobKey = "sync:$key\u0000${room.id}\u0000$title\u0000$expectedTitle"
        val now = turnClock()
        if (titleJobs[jobKey]?.isActive == true || titleAttempts[jobKey]?.let { now - it < 60_000L } == true) return
        titleAttempts[jobKey] = now
        titleJobs[jobKey] =
            viewModelScope.launch(ioDispatcher) {
                try {
                    if (conversationScope() == owner) {
                        transport.rename(room.id, GroupServerTitleRequest(group, title, expectedTitle))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Older/offline servers may lack metadata writes; the durable local title remains valid.
                } finally {
                    titleJobs.remove(jobKey)
                }
            }
    }

    /** Commit a complete conversation before any prompt can leave the process. */
    private fun checkpoint(turns: List<GroupTurnRecord>? = null): Boolean {
        draftSaveJob?.cancel()
        draftSaveJob = null
        if (storageFailed) return false
        val current = conversation() ?: return false
        val updated =
            current.copy(
                messages = _uiState.value.messages,
                turns = turns ?: current.turns,
                draft = _uiState.value.draft,
                title =
                    current.title.ifBlank {
                        _uiState.value.messages
                            .firstOrNull { it.isUser }
                            ?.text
                            ?.take(
                                60,
                            ).orEmpty()
                    },
            )
        val next =
            requireNotNull(archive).copy(
                members = _uiState.value.members,
                displayName = _uiState.value.displayName,
                conversations = requireNotNull(archive).conversations.map { if (it.id == updated.id) updated else it },
            )
        return writeArchive(next)
    }

    private fun writeArchive(next: GroupConversationArchive): Boolean =
        try {
            conversationStore.write(requireNotNull(archiveKey), next)
            archive = next
            _uiState.update { it.copy(conversations = next.conversations) }
            true
        } catch (_: Exception) {
            storageIssue()
            false
        }

    fun updateDraft(
        text: String,
        readerId: String? = null,
        conversationId: String? = null,
    ) {
        if (readerId != null && (readerId != activeReaderId || conversationId != _uiState.value.conversationId)) return
        _uiState.update { it.copy(draft = text) }
        draftSaveJob?.cancel()
        val key = archiveKey
        val activeId = _uiState.value.conversationId
        draftSaveJob =
            viewModelScope.launch(ioDispatcher) {
                delay(500L)
                draftSaveJob = null
                if (key == archiveKey && activeId == _uiState.value.conversationId) checkpoint()
            }
    }

    fun newConversation(id: String = UUID.randomUUID().toString()) {
        if (_uiState.value.isLoading || _uiState.value.isSavingGroupSettings || !openArchive()) return
        cancelTurns(sendInterrupt = false)
        if (!checkpoint()) return
        if (archive?.conversations?.any { it.id == id } == true) {
            selectConversation(id)
            return
        }
        val next = GroupConversation(id = id)
        val currentArchive = requireNotNull(archive)
        if (writeArchive(
                currentArchive.copy(activeId = next.id, conversations = currentArchive.conversations + next),
            )
        ) {
            memberWatermarks.clear()
            showConversation()
        }
    }

    /** A route names the saved conversation, independent of the room's last active session. */
    fun openConversation(
        name: String,
        conversationId: String?,
        newConversationId: String?,
        readerId: String? = null,
    ) {
        activeReaderId = readerId
        pendingConversationId = conversationId
        pendingNewConversationId = newConversationId
        setGroup(name)
        if (!_uiState.value.isLoading) applyConversationRoute()
    }

    private fun applyConversationRoute() {
        if (storageFailed || archive == null) return
        val selected = pendingConversationId
        val created = pendingNewConversationId
        pendingConversationId = null
        pendingNewConversationId = null
        when {
            created != null -> newConversation(created)
            selected != null -> selectConversation(selected)
        }
    }

    fun selectConversation(id: String) {
        if (serverTransport != null && archive?.conversations?.none { it.id == id } == true) {
            val currentArchive = requireNotNull(archive)
            if (!writeArchive(
                    currentArchive.copy(
                        conversations =
                            currentArchive.conversations + GroupConversation(id = id),
                    ),
                )
            ) {
                return
            }
        }
        if (id == archive?.activeId || _uiState.value.isSavingGroupSettings ||
            archive?.conversations?.none { it.id == id } != false
        ) {
            return
        }
        cancelTurns(sendInterrupt = false)
        if (!checkpoint()) return
        if (writeArchive(requireNotNull(archive).copy(activeId = id))) {
            memberWatermarks.clear()
            showConversation()
            reconcilePending()
        }
    }

    /** Read-only reconciliation, scoped to the original server, conversation, member and session. */
    fun reconcilePending() {
        if (serverTransport != null) {
            refreshServerConversation()
            return
        }
        if (reconciliationJob?.isActive == true || _uiState.value.isSending || storageFailed) return
        val key = archiveKey ?: return
        val scope =
            groupTransport.connectionScope.value ?: run {
                _uiState.update { it.copy(reconciliationStatus = GroupReconciliationStatus.OFFLINE) }
                return
            }
        val identity = conversationScope()
        reconciliationJob =
            viewModelScope.launch(ioDispatcher) {
                reconciliationMutex.withLock {
                    _uiState.update { it.copy(isReconciling = true, reconciliationStatus = null) }
                    try {
                        val records =
                            archive?.conversations.orEmpty().flatMap { room ->
                                room.turns.filter { it.delivery in unresolvedDeliveries }.map { room.id to it }
                            }
                        if (records.isEmpty()) return@withLock
                        val requestedConversation = archive?.activeId

                        fun report(
                            conversationId: String?,
                            status: GroupReconciliationStatus,
                        ) {
                            if (archiveKey == key && conversationId == requestedConversation &&
                                archive?.activeId == requestedConversation
                            ) {
                                _uiState.update { it.copy(reconciliationStatus = status) }
                            }
                        }
                        if (!groupTransport.isCurrent &&
                            !groupTransport.open(
                                "$groupName/${_uiState.value.conversationId}",
                                _uiState.value.members
                                    .map { it.name }
                                    .toSet(),
                                metadataProfileName ?: run {
                                    report(requestedConversation, GroupReconciliationStatus.READ_UNAVAILABLE)
                                    return@withLock
                                },
                            )
                        ) {
                            report(requestedConversation, GroupReconciliationStatus.OFFLINE)
                            return@withLock
                        }
                        for ((conversationId, turn) in records) {
                            if (archiveKey != key || conversationScope() != identity ||
                                groupTransport.connectionScope.value != scope
                            ) {
                                break
                            }
                            val storedId = turn.storedSessionId
                            if (storedId == null) {
                                report(conversationId, GroupReconciliationStatus.SESSION_ID_MISSING)
                                continue
                            }
                            val read = groupTransport.readHistory(turn.member, storedId)
                            if (archiveKey != key || conversationScope() != identity ||
                                groupTransport.connectionScope.value != scope
                            ) {
                                report(conversationId, GroupReconciliationStatus.CONNECTION_CHANGED)
                                break
                            }
                            val history = read.messages
                            if (history == null) {
                                report(
                                    conversationId,
                                    when (read.issue) {
                                        GroupHistoryIssue.AUTH_REQUIRED -> {
                                            GroupReconciliationStatus.AUTH_REQUIRED
                                        }

                                        GroupHistoryIssue.NOT_FOUND -> {
                                            GroupReconciliationStatus.SESSION_NOT_FOUND
                                        }

                                        GroupHistoryIssue.PROFILE_NOT_FOUND -> {
                                            GroupReconciliationStatus.PROFILE_NOT_FOUND
                                        }

                                        GroupHistoryIssue.ENDPOINT_UNAVAILABLE -> {
                                            GroupReconciliationStatus.HISTORY_ENDPOINT_UNAVAILABLE
                                        }

                                        GroupHistoryIssue.ID_MISMATCH -> {
                                            GroupReconciliationStatus.SESSION_ID_MISMATCH
                                        }

                                        GroupHistoryIssue.CONNECTION_CHANGED -> {
                                            GroupReconciliationStatus.CONNECTION_CHANGED
                                        }

                                        else -> {
                                            GroupReconciliationStatus.READ_UNAVAILABLE
                                        }
                                    },
                                )
                                continue
                            }
                            val evidence = reconcileGroupTurn(turn, history)
                            if (evidence.delivery !=
                                GroupDelivery.UNKNOWN
                            ) {
                                applyEvidence(conversationId, turn, evidence)
                            }
                            report(
                                conversationId,
                                when (evidence.delivery) {
                                    GroupDelivery.COMPLETE -> {
                                        GroupReconciliationStatus.COMPLETED
                                    }

                                    GroupDelivery.ACCEPTED -> {
                                        GroupReconciliationStatus.ACCEPTED_NOT_COMPLETE
                                    }

                                    else -> {
                                        if (read.completeHistory) {
                                            GroupReconciliationStatus.REQUEST_NOT_FOUND
                                        } else {
                                            GroupReconciliationStatus.HISTORY_INCOMPLETE
                                        }
                                    }
                                },
                            )
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        // Read unavailable or incomplete: retain uncertainty, never retry a prompt.
                        if (archiveKey ==
                            key
                        ) {
                            _uiState.update {
                                it.copy(
                                    reconciliationStatus = GroupReconciliationStatus.READ_UNAVAILABLE,
                                )
                            }
                        }
                    } finally {
                        if (archiveKey == key) _uiState.update { it.copy(isReconciling = false) }
                    }
                }
            }
    }

    private var metadataProfileName: String? = null
    private val unresolvedDeliveries = setOf(GroupDelivery.UNKNOWN, GroupDelivery.ACCEPTED)

    private fun applyEvidence(
        conversationId: String,
        turn: GroupTurnRecord,
        evidence: GroupTurnEvidence,
    ) {
        val currentArchive = archive ?: return
        val room = currentArchive.conversations.find { it.id == conversationId } ?: return
        if (room.turns.find { it.id == turn.id }?.delivery !in unresolvedDeliveries) return
        val turns = room.turns.map { if (it.id == turn.id) it.copy(delivery = evidence.delivery) else it }
        val related = turns.filter { it.userMessageId == turn.userMessageId && it.member == turn.member }
        val aggregate =
            if (related.any {
                    it.delivery in unresolvedDeliveries
                }
            ) {
                GroupDelivery.UNKNOWN
            } else {
                evidence.delivery
            }
        var messages =
            room.messages.map { message ->
                if (message.id ==
                    turn.userMessageId
                ) {
                    message.copy(deliveries = message.deliveries + (turn.member to aggregate))
                } else {
                    message
                }
            }
        evidence.reply?.let { reply ->
            val bot = _uiState.value.members.find { it.name == turn.member }
            val pass = isPass(reply)
            val response =
                GroupChatMessage(
                    id = turn.responseMessageId,
                    senderName = turn.member,
                    senderDisplayName = bot?.effectiveTitle ?: turn.member,
                    isUser = false,
                    text = if (pass) "${bot?.effectiveTitle ?: turn.member} passed" else reply,
                    timestamp = messages.find { it.id == turn.responseMessageId }?.timestamp ?: room.createdAt,
                    isSystem = pass,
                    isPass = pass,
                )
            messages =
                if (messages.any { it.id == response.id }) {
                    messages.map { if (it.id == response.id) response else it }
                } else {
                    val index = messages.indexOfFirst { it.id == turn.userMessageId }
                    val nextUser = messages.indices.firstOrNull { it > index && messages[it].isUser } ?: messages.size
                    messages.take(nextUser) + response + messages.drop(nextUser)
                }
        }
        val updated = room.copy(messages = messages, turns = turns)
        if (writeArchive(
                currentArchive.copy(
                    conversations =
                        currentArchive.conversations.map {
                            if (it.id ==
                                room.id
                            ) {
                                updated
                            } else {
                                it
                            }
                        },
                ),
            ) &&
            archive?.activeId == conversationId
        ) {
            showConversation()
        }
    }

    // Map of bot.name -> seen count of non-system messages
    private val memberWatermarks = mutableMapOf<String, Int>()

    // Map of bot.name -> active session info in this group
    private val memberSessions = mutableMapOf<String, GroupMemberSession>()

    // In-flight turn completion deferreds keyed by session_id
    private val inFlightTurns = mutableMapOf<String, CompletableDeferred<String>>()

    // Map of session_id -> active streaming message ID
    private val activeStreamMsgId = mutableMapOf<String, String>()

    // Map of session_id -> bot profile
    private val sessionToBot = mutableMapOf<String, ProfileInfo>()

    // Map of session_id -> last activity epoch ms (streaming token, tool call, thinking)
    private val sessionLastActivity = mutableMapOf<String, Long>()

    init {
        if (groupName.isNotBlank()) {
            loadGroup()
        }
        observeWsEvents()
        viewModelScope.launch(ioDispatcher) {
            groupTransport.availability.collect { available ->
                if (serverTransport == null && _uiState.value.isSending && routeEstablishedEpoch == activeEpoch &&
                    !available
                ) {
                    cancelTurns(sendInterrupt = false)
                    _uiState.update { it.copy(sendIssue = GroupSendIssue.CONNECTION_CHANGED) }
                }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            groupTransport.readReadiness.collect { ready ->
                if (ready && !_uiState.value.isSending) reconcilePending()
            }
        }
        viewModelScope.launch(ioDispatcher) {
            groupTransport.connectionScope.collect {
                val newKey = conversationScope()?.let { scope -> "$scope\u0000$groupName" }
                if (archiveKey != null && newKey != null && newKey != archiveKey) {
                    cancelTurns(sendInterrupt = false)
                    checkpoint()
                    archive = null
                    archiveKey = null
                    _uiState.value = GroupChatUiState(groupName = groupName)
                    loadGroup()
                } else {
                    if (metadataProfileName == null && groupName.isNotBlank() && !_uiState.value.isLoading) {
                        loadGroup()
                    } else {
                        reconcilePending()
                    }
                }
            }
        }
    }

    /**
     * Point the shared instance at [name] and drop all state from the
     * previously viewed group chat so one group's data never bleeds into another.
     */
    fun setGroup(name: String) {
        if (name == groupName && _uiState.value.groupName.isNotBlank()) {
            if (storageFailed) {
                if (archive == null) {
                    loadGroup()
                } else {
                    storageFailed = false
                    if (checkpoint()) showConversation()
                }
            }
            reconcilePending()
            return
        }
        cancelTurns(sendInterrupt = true)
        checkpoint()
        reconciliationJob?.cancel()
        archive = null
        archiveKey = null
        metadataProfileName = null
        groupName = name
        activeEpoch = UUID.randomUUID().toString()
        memberWatermarks.clear()
        memberSessions.clear()
        inFlightTurns.clear()
        activeStreamMsgId.clear()
        sessionToBot.clear()
        sessionLastActivity.clear()
        _uiState.value = GroupChatUiState(groupName = name)
        loadGroup()
    }

    private fun extractToolCommand(data: Map<String, Any?>?): String? {
        if (data == null) return null
        val cmd = (data["command"] as? String)?.trim()
        if (!cmd.isNullOrBlank()) return cmd
        val code = (data["code"] as? String)?.trim()
        if (!code.isNullOrBlank()) return code
        val query = (data["query"] as? String)?.trim()
        if (!query.isNullOrBlank()) return query
        val path = (data["path"] as? String)?.trim()
        if (!path.isNullOrBlank()) return path
        val pattern = (data["pattern"] as? String)?.trim()
        if (!pattern.isNullOrBlank()) return pattern
        return null
    }

    private fun extractToolSummary(data: Map<String, Any?>?): String? {
        if (data == null) return null
        val cmd = (data["command"] as? String)?.trim()
        if (!cmd.isNullOrBlank()) return cmd.take(35)
        val query = (data["query"] as? String)?.trim()
        if (!query.isNullOrBlank()) return query.take(35)
        val path = (data["path"] as? String)?.trim()
        if (!path.isNullOrBlank()) return path.take(35)
        val pattern = (data["pattern"] as? String)?.trim()
        if (!pattern.isNullOrBlank()) return pattern.take(35)
        return null
    }

    private fun extractToolOutput(data: Map<String, Any?>?): String? {
        if (data == null) return null
        val rawStr =
            (data["output"] as? String)
                ?: (data["stdout"] as? String)
                ?: (data["result"] as? String)
        if (!rawStr.isNullOrBlank()) {
            return rawStr
        }

        val resultElement = data["result"]?.toJsonElement() ?: data["output"]?.toJsonElement() ?: data.toJsonElement()
        val summary = ToolResultSummary.formatToolResultSummary(resultElement)
        return summary.ifBlank { null }
    }

    private fun touchSession(sessionId: String?) {
        val id = sessionId?.trim('\"', ' ') ?: return
        if (id in inFlightTurns) sessionLastActivity[id] = turnClock()
    }

    private fun observeWsEvents() {
        viewModelScope.launch(ioDispatcher) {
            groupTransport.events.collect { event ->
                when (event) {
                    is WsEvent.ThinkingDelta -> {
                        touchSession(event.sessionId)
                    }

                    is WsEvent.ReasoningDelta -> {
                        touchSession(event.sessionId)
                    }

                    is WsEvent.ReasoningAvailable -> {
                        touchSession(event.sessionId)
                    }

                    is WsEvent.ToolProgress -> {
                        touchSession(event.sessionId)
                    }

                    is WsEvent.MessageToken -> {
                        val sid = event.sessionId?.trim('\"', ' ')
                        if (sid != null) {
                            sessionLastActivity[sid] = turnClock()
                            val streamId = activeStreamMsgId[sid]
                            val bot = sessionToBot[sid]
                            if (streamId != null && bot != null && event.token.isNotEmpty()) {
                                _uiState.update { state ->
                                    val existing = state.messages.find { it.id == streamId }
                                    val updatedMessages =
                                        if (existing != null) {
                                            state.messages.map { msg ->
                                                if (msg.id == streamId) {
                                                    msg.copy(text = msg.text + event.token)
                                                } else {
                                                    msg
                                                }
                                            }
                                        } else {
                                            state.messages +
                                                GroupChatMessage(
                                                    id = streamId,
                                                    senderName = bot.name,
                                                    senderDisplayName = bot.effectiveTitle,
                                                    isUser = false,
                                                    avatarMeta = bot.botMeta()?.avatar,
                                                    text = event.token,
                                                    isStreaming = true,
                                                )
                                        }
                                    state.copy(messages = updatedMessages)
                                }
                            }
                        }
                    }

                    is WsEvent.ToolStart -> {
                        val sid = event.sessionId?.trim('\"', ' ')
                        if (sid != null) {
                            sessionLastActivity[sid] = turnClock()
                            val streamId = activeStreamMsgId[sid]
                            val bot = sessionToBot[sid]
                            val toolName = event.name ?: "tool"
                            val toolId =
                                (event.data?.get("tool_id") as? String)?.ifBlank { null }
                                    ?: UUID.randomUUID().toString()
                            val summary = extractToolSummary(event.data)
                            val command = extractToolCommand(event.data)
                            val toolCall =
                                GroupChatToolCall(
                                    id = toolId,
                                    name = toolName,
                                    summary = summary,
                                    command = command,
                                    isRunning = true,
                                )
                            if (streamId != null && bot != null) {
                                _uiState.update { state ->
                                    val existing = state.messages.find { it.id == streamId }
                                    val updatedMessages =
                                        if (existing != null) {
                                            state.messages.map { msg ->
                                                if (msg.id == streamId) {
                                                    msg.copy(toolCalls = msg.toolCalls + toolCall)
                                                } else {
                                                    msg
                                                }
                                            }
                                        } else {
                                            state.messages +
                                                GroupChatMessage(
                                                    id = streamId,
                                                    senderName = bot.name,
                                                    senderDisplayName = bot.effectiveTitle,
                                                    isUser = false,
                                                    avatarMeta = bot.botMeta()?.avatar,
                                                    text = "",
                                                    isStreaming = true,
                                                    toolCalls = listOf(toolCall),
                                                )
                                        }
                                    state.copy(messages = updatedMessages)
                                }
                            }
                        }
                    }

                    is WsEvent.ToolComplete -> {
                        val sid = event.sessionId?.trim('\"', ' ')
                        if (sid != null) {
                            sessionLastActivity[sid] = turnClock()
                            val streamId = activeStreamMsgId[sid]
                            val toolId = event.data?.get("tool_id") as? String
                            val toolName = event.name
                            val output = extractToolOutput(event.data)
                            val exitCode =
                                when (val rawCode = event.data?.get("exit_code")) {
                                    is Number -> rawCode.toInt()
                                    is String -> rawCode.toIntOrNull()
                                    else -> null
                                }
                            val isError =
                                (exitCode != null && exitCode != 0) ||
                                    (event.data?.get("is_error") as? Boolean == true) ||
                                    (event.data?.get("error") != null)
                            if (streamId != null) {
                                _uiState.update { state ->
                                    val existing = state.messages.find { it.id == streamId }
                                    if (existing != null) {
                                        val updatedTools =
                                            existing.toolCalls.map { tool ->
                                                if ((toolId != null && tool.id == toolId) ||
                                                    (toolId == null && tool.name == toolName && tool.isRunning) ||
                                                    (toolId == null && toolName == null && tool.isRunning)
                                                ) {
                                                    tool.copy(
                                                        isRunning = false,
                                                        output = output ?: tool.output,
                                                        exitCode = exitCode ?: tool.exitCode,
                                                        isError = isError || tool.isError,
                                                    )
                                                } else {
                                                    tool
                                                }
                                            }
                                        state.copy(
                                            messages =
                                                state.messages.map { msg ->
                                                    if (msg.id == streamId) {
                                                        msg.copy(toolCalls = updatedTools)
                                                    } else {
                                                        msg
                                                    }
                                                },
                                        )
                                    } else {
                                        state
                                    }
                                }
                            }
                        }
                    }

                    is WsEvent.MessageComplete -> {
                        val sid = (event.sessionId ?: event.storedSessionId)?.trim('\"', ' ')
                        if (sid != null) {
                            Log.d("GroupChatViewModel", "MessageComplete chars=${event.text.length}")
                            inFlightTurns[sid]?.complete(event.text)
                            if (inFlightTurns[sid] == null && groupTransport.isCurrent) {
                                archive?.conversations.orEmpty().forEach { room ->
                                    room.turns
                                        .filter { turn ->
                                            turn.delivery in unresolvedDeliveries &&
                                                sid in listOfNotNull(turn.runtimeSessionId, turn.storedSessionId)
                                        }.forEach { turn ->
                                            applyEvidence(
                                                room.id,
                                                turn,
                                                GroupTurnEvidence(GroupDelivery.COMPLETE, event.text),
                                            )
                                        }
                                }
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    private suspend fun fetchProfiles(): List<ProfileInfo> {
        try {
            val rpcResult = HermesWsClient.request(WsMethods.PROFILES_LIST).await()
            val jsonElement =
                when (rpcResult) {
                    is JsonElement -> rpcResult
                    null -> null
                    else -> rpcResult.toJsonElement()
                }
            if (jsonElement != null) {
                val resp = json.decodeFromJsonElement<ProfilesResponse>(jsonElement)
                if (!resp.profiles.isNullOrEmpty()) {
                    return resp.profiles
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // Fallback to REST API below
        }
        val result = safeApiCall { ApiClient.hermesApi.getProfiles() }
        return (result as? NetworkResult.Success)?.data?.profiles.orEmpty()
    }

    fun loadGroup() {
        if (_uiState.value.isSending) return
        if (!openArchive()) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        val requestedGroup = groupName
        val requestedEpoch = activeEpoch
        val requestedArchiveKey = archiveKey
        val requestedIdentity = conversationScope()
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val allProfiles = fetchProfiles().filter { it.name.isNotBlank() }.distinctBy { it.name }
            if (groupName != requestedGroup || activeEpoch != requestedEpoch ||
                archiveKey != requestedArchiveKey || conversationScope() != requestedIdentity
            ) {
                return@launch
            }
            if (allProfiles.isNotEmpty()) {
                // Hydrate previous group chat messages from cross-device sync snapshot
                val defaultProfile =
                    allProfiles.find { it.is_default == true || it.name == "default" }
                        ?: allProfiles.firstOrNull()
                val syncSnapshot = defaultProfile?.groupChatSyncSnapshot()
                metadataProfileName = defaultProfile?.name

                val matchingRoom =
                    syncSnapshot?.rooms?.get(groupName)
                        ?: syncSnapshot?.rooms?.get("name:$groupName")
                        ?: syncSnapshot?.rooms?.get("id:$groupName")
                        ?: syncSnapshot?.rooms?.values?.find { it.name.equals(groupName, ignoreCase = true) }

                val roomMemberNames = matchingRoom?.memberNames.orEmpty().map { it.lowercase() }

                val matchedProfiles =
                    allProfiles
                        .filter {
                            val botGroups = it.botMeta()?.allGroups.orEmpty()
                            botGroups.any { g -> g.equals(groupName, ignoreCase = true) } ||
                                roomMemberNames.contains(it.name.lowercase()) ||
                                roomMemberNames.contains(it.effectiveTitle.lowercase())
                        }.toMutableList()

                // If room listed members (e.g. remote bots or bots not in local profiles), ensure they are seated
                if (matchingRoom != null && roomMemberNames.isNotEmpty()) {
                    for (mName in matchingRoom.memberNames) {
                        val exists =
                            matchedProfiles.any {
                                it.name.equals(mName, ignoreCase = true) ||
                                    it.effectiveTitle.equals(mName, ignoreCase = true)
                            }
                        if (!exists) {
                            val local = allProfiles.find { it.name.equals(mName, ignoreCase = true) }
                            matchedProfiles.add(local ?: ProfileInfo(name = mName))
                        }
                    }
                }

                var groupMembers: List<ProfileInfo> = matchedProfiles.distinctBy { it.name }

                // Fallback: If group name was composed from bot names (e.g. "default, scoutbot")
                if (groupMembers.isEmpty() && matchingRoom?.members == null && groupName.contains(",")) {
                    val targetNames = groupName.split(",").map { it.trim().lowercase() }
                    groupMembers = allProfiles.filter { targetNames.contains(it.name.lowercase()) }
                }

                val rosterOrder =
                    matchingRoom?.memberNames.orEmpty().map { name ->
                        groupMembers.find { it.name.equals(name, true) || it.effectiveTitle.equals(name, true) }?.name
                            ?: name
                    }
                groupMembers =
                    orderedGroupMembers(
                        orderedGroupMembers(groupMembers, rosterOrder),
                        matchingRoom?.speakingOrder ?: rosterOrder,
                    )

                Log.d(
                    "GroupChatViewModel",
                    "Resolved members count=${groupMembers.size}",
                )

                var initialMessages = _uiState.value.messages
                if (archive?.legacyImported == false && initialMessages.isEmpty() && syncSnapshot?.rooms != null) {
                    val syncedLog = matchingRoom?.log.orEmpty()
                    if (syncedLog.isNotEmpty()) {
                        initialMessages =
                            syncedLog.map { entry ->
                                val isUser = entry.from?.kind != "member"
                                val senderName =
                                    entry.from
                                        ?.name
                                        .orEmpty()
                                        .ifBlank { if (isUser) "user" else "bot" }
                                val memberProfile =
                                    allProfiles.find {
                                        it.name.equals(senderName, ignoreCase = true) ||
                                            it.effectiveTitle.equals(senderName, ignoreCase = true)
                                    }
                                GroupChatMessage(
                                    id = entry.id ?: UUID.randomUUID().toString(),
                                    senderName = memberProfile?.name ?: senderName,
                                    senderDisplayName =
                                        if (isUser) "You" else (memberProfile?.effectiveTitle ?: senderName),
                                    isUser = isUser,
                                    avatarMeta = memberProfile?.botMeta()?.avatar,
                                    text = entry.text.orEmpty(),
                                    timestamp = entry.at ?: System.currentTimeMillis(),
                                    thread = entry.thread,
                                )
                            }
                        Log.d("GroupChatViewModel", "Hydrated ${initialMessages.size} messages from sync snapshot")
                    }
                }

                val nonSystemCount = initialMessages.filter { !it.isSystem }.size
                for (member in groupMembers) {
                    memberWatermarks[member.name] = nonSystemCount
                }

                val maxMessages =
                    (matchingRoom?.maxBotMessages ?: DEFAULT_MAX_BOT_MESSAGES).coerceIn(
                        1,
                        MAX_ALLOWED_BOT_MESSAGES,
                    )
                val maxPasses =
                    (matchingRoom?.maxContinuationPasses ?: DEFAULT_MAX_CONTINUATION_PASSES).coerceIn(
                        0,
                        MAX_ALLOWED_CONTINUATION_PASSES,
                    )
                val roomSystemPrompt = matchingRoom?.systemPrompt

                _uiState.update {
                    it.copy(
                        displayName = matchingRoom?.displayName?.takeIf { name -> name.isNotBlank() } ?: it.displayName,
                        members = groupMembers,
                        sendIssue = if (groupMembers.isEmpty()) GroupSendIssue.INVALID_MEMBERS else null,
                        messages = initialMessages,
                        maxBotMessages = maxMessages,
                        maxContinuationPasses = maxPasses,
                        systemPrompt = roomSystemPrompt,
                        discussionRounds =
                            (matchingRoom?.discussionRounds ?: DEFAULT_DISCUSSION_ROUNDS).coerceIn(
                                1,
                                MAX_ALLOWED_DISCUSSION_ROUNDS,
                            ),
                        speakingOrder =
                            matchingRoom?.speakingOrder?.let {
                                groupMembers.map { member ->
                                    member.name
                                }
                            },
                        finalSummaryMember =
                            matchingRoom?.finalSummaryMember?.takeIf { name ->
                                groupMembers.any { member ->
                                    member.name ==
                                        name
                                }
                            },
                        isLoading = false,
                    )
                }
                archive = archive?.copy(legacyImported = true)
                checkpoint()
                applyConversationRoute()
                reconcilePending()
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (it.members.isEmpty()) "Failed to load group members" else null,
                    )
                }
                applyConversationRoute()
            }
        }
    }

    private fun findRoomEntry(rooms: Map<String, GroupChatRoomMeta>?): Pair<String, GroupChatRoomMeta?> {
        if (rooms == null) return ("name:$groupName" to null)
        val exact =
            rooms.entries.find {
                it.key == groupName || it.key == "name:$groupName" || it.key == "id:$groupName"
            }
        if (exact != null) return (exact.key to exact.value)
        val byName = rooms.entries.find { it.value.name.equals(groupName, ignoreCase = true) }
        if (byName != null) return (byName.key to byName.value)
        return ("name:$groupName" to null)
    }

    fun updateGroupLimits(
        maxMessages: Int,
        maxPasses: Int,
        systemPrompt: String? = null,
        speakingOrder: List<String>? = _uiState.value.speakingOrder,
        finalSummaryMember: String? = _uiState.value.finalSummaryMember,
        discussionRounds: Int = _uiState.value.discussionRounds,
        displayName: String = _uiState.value.displayName,
    ) {
        if (_uiState.value.isSending || _uiState.value.isSavingGroupSettings) return
        val priorSettings = _uiState.value
        val settingsGroup = groupName
        val settingsEpoch = activeEpoch
        val settingsScope = groupTransport.connectionScope.value
        val orderedMembers = orderedGroupMembers(_uiState.value.members, speakingOrder.orEmpty())
        val cleanOrder = speakingOrder?.let { orderedMembers.map { member -> member.name } }
        val cleanSummary = finalSummaryMember?.takeIf { cleanOrder != null && it in cleanOrder }
        val clampedMessages = maxMessages.coerceIn(1, MAX_ALLOWED_BOT_MESSAGES)
        val clampedPasses = maxPasses.coerceIn(0, MAX_ALLOWED_CONTINUATION_PASSES)
        val cleanPrompt = systemPrompt?.trim()?.ifBlank { null }
        val clampedRounds = discussionRounds.coerceIn(1, MAX_ALLOWED_DISCUSSION_ROUNDS)
        val cleanDisplayName = displayName.trim().ifBlank { groupName }

        _uiState.update {
            it.copy(
                maxBotMessages = clampedMessages,
                maxContinuationPasses = clampedPasses,
                systemPrompt = cleanPrompt,
                members = orderedMembers,
                settingsSaveFailed = false,
                isSavingGroupSettings = true,
                speakingOrder = cleanOrder,
                finalSummaryMember = cleanSummary,
                discussionRounds = clampedRounds,
            )
        }

        viewModelScope.launch(ioDispatcher) {
            try {
                val allProfiles = fetchProfiles()
                if (groupName != settingsGroup || activeEpoch != settingsEpoch ||
                    groupTransport.connectionScope.value != settingsScope
                ) {
                    error("Settings scope changed")
                }
                val defaultProfile =
                    allProfiles.find { it.is_default == true || it.name == "default" }
                        ?: error("Metadata profile unavailable")
                val existingSnapshot =
                    defaultProfile.groupChatSyncSnapshot()
                        ?: GroupChatSyncSnapshot(version = 3, rooms = emptyMap())

                val now = System.currentTimeMillis()
                val (targetKey, existingRoom) = findRoomEntry(existingSnapshot.rooms)

                val updatedRoom =
                    (existingRoom ?: GroupChatRoomMeta(name = groupName, createdAt = now)).copy(
                        name = groupName,
                        displayName = cleanDisplayName,
                        members = existingRoom?.members ?: _uiState.value.members.map { JsonPrimitive(it.name) },
                        maxBotMessages = clampedMessages,
                        maxContinuationPasses = clampedPasses,
                        systemPrompt = cleanPrompt,
                        speakingOrder = cleanOrder,
                        finalSummaryMember = cleanSummary,
                        discussionRounds = clampedRounds,
                        updatedAt = now,
                    )

                val updatedRooms = existingSnapshot.rooms.orEmpty().toMutableMap()
                updatedRooms[targetKey] = updatedRoom

                val newSnapshot =
                    existingSnapshot.copy(
                        version = 3,
                        updatedAt = now,
                        rooms = updatedRooms,
                    )

                val savedRosterOrder =
                    existingRoom?.memberNames.orEmpty().map { name ->
                        orderedMembers.find { it.name.equals(name, true) || it.effectiveTitle.equals(name, true) }?.name
                            ?: name
                    }
                val savedRosterMembers =
                    orderedGroupMembers(
                        orderedMembers,
                        savedRosterOrder.ifEmpty { allProfiles.map { it.name } },
                    )

                val snapshotMap = newSnapshot.toMap()

                @Suppress("UNCHECKED_CAST")
                val roomsMap = snapshotMap["rooms"] as Map<String, Any?>

                @Suppress("UNCHECKED_CAST")
                val targetRoomMap =
                    (roomsMap[targetKey] as Map<String, Any?>) +
                        mapOf(
                            "speakingOrder" to cleanOrder,
                            "finalSummaryMember" to cleanSummary,
                        )
                val uiMetaPayload =
                    mapOf(
                        "hermes-bots-groups" to (snapshotMap + ("rooms" to (roomsMap + (targetKey to targetRoomMap)))),
                    )

                if (groupName != settingsGroup || activeEpoch != settingsEpoch ||
                    groupTransport.connectionScope.value != settingsScope
                ) {
                    error("Settings scope changed")
                }
                HermesWsClient
                    .request(
                        WsMethods.PROFILES_CONFIGURE,
                        mapOf(
                            "name" to defaultProfile.name,
                            "ui_meta" to uiMetaPayload,
                        ),
                    ).await()
                if (groupName != settingsGroup || activeEpoch != settingsEpoch ||
                    groupTransport.connectionScope.value != settingsScope
                ) {
                    error("Settings scope changed")
                }

                if (cleanOrder == null) {
                    _uiState.update { it.copy(members = savedRosterMembers) }
                }
                _uiState.update { it.copy(displayName = cleanDisplayName) }
                checkpoint()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (groupName == settingsGroup && activeEpoch == settingsEpoch) {
                    _uiState.update { state ->
                        state.copy(
                            speakingOrder = priorSettings.speakingOrder,
                            finalSummaryMember = priorSettings.finalSummaryMember,
                            discussionRounds = priorSettings.discussionRounds,
                            members = priorSettings.members,
                            settingsSaveFailed = true,
                        )
                    }
                }
                Log.w("GroupChatViewModel", "updateGroupLimits failed: ${e.javaClass.simpleName}")
            } finally {
                if (groupName == settingsGroup && activeEpoch == settingsEpoch) {
                    _uiState.update { it.copy(isSavingGroupSettings = false) }
                }
            }
        }
    }

    private suspend fun ensureMemberSession(bot: ProfileInfo): GroupMemberSession {
        // A fresh session for every turn prevents late events from an earlier turn sharing its route.
        // Context is reconstructed exclusively from this conversation's confirmed history.
        val session = groupTransport.create(bot.name)
        val ids = listOfNotNull(session.runtimeSessionId, session.storedSessionId)
        if (archive?.conversations.orEmpty().flatMap { it.turns }.any { turn ->
                listOfNotNull(turn.runtimeSessionId, turn.storedSessionId).any { it in ids }
            }
        ) {
            throw HermesRpcException("Group session identity was reused", OfflineSendPolicy.NOT_SENT)
        }
        return session.also { memberSessions[bot.name] = it }
    }

    private fun updateDelivery(
        member: String,
        delivery: GroupDelivery,
        epoch: String,
    ) {
        if (activeEpoch != epoch) return
        val messageId = currentUserMessageId
        _uiState.update { state ->
            state.copy(
                messages =
                    state.messages.map { message ->
                        if (message.id ==
                            messageId
                        ) {
                            message.copy(deliveries = message.deliveries + (member to delivery))
                        } else {
                            message
                        }
                    },
            )
        }
        val turns = conversation()?.turns.orEmpty().toMutableList()
        val index = turns.indexOfLast { it.userMessageId == messageId && it.member == member }
        if (index >= 0) turns[index] = turns[index].copy(delivery = delivery)
        checkpoint(turns)
    }

    private fun confirmedHistory(member: String? = null): List<GroupChatMessage> =
        _uiState.value.messages.filter { message ->
            !message.isSystem && !message.isStreaming && !message.isIncomplete &&
                (
                    message.deliveries.isEmpty() ||
                        (member != null && message.id == currentUserMessageId) ||
                        (
                            member != null &&
                                message.deliveries[member] in setOf(GroupDelivery.ACCEPTED, GroupDelivery.COMPLETE)
                        ) ||
                        (
                            member == null &&
                                message.deliveries.values.all {
                                    it in
                                        setOf(GroupDelivery.ACCEPTED, GroupDelivery.COMPLETE)
                                }
                        )
                )
        }

    private suspend fun acquireOrVerifyLease(
        defaultProfile: ProfileInfo,
        epoch: String,
    ): Boolean {
        try {
            val syncSnapshot = defaultProfile.groupChatSyncSnapshot()
            val (targetKey, existingRoom) = findRoomEntry(syncSnapshot?.rooms)
            val currentLease = existingRoom?.lease
            val now = System.currentTimeMillis()

            if (currentLease?.driverId != null &&
                currentLease.driverId != localDriverId &&
                (currentLease.expiresAt ?: 0L) > now
            ) {
                val remainingSec = ((currentLease.expiresAt ?: 0L) - now) / 1000
                Log.i(
                    "GroupChatViewModel",
                    "Active lease expires in ${remainingSec}s. Passive mode.",
                )
                _uiState.update { it.copy(sendIssue = GroupSendIssue.LEASE_HELD) }
                return false
            }

            val myLease =
                GroupChatRoomLease(
                    driverId = localDriverId,
                    epoch = epoch,
                    expiresAt = now + LEASE_STEP_TTL_MS,
                )

            val existingSnapshot = syncSnapshot ?: GroupChatSyncSnapshot(version = 3, rooms = emptyMap())
            val updatedRoom =
                (existingRoom ?: GroupChatRoomMeta(name = groupName, createdAt = now)).copy(
                    name = groupName,
                    members = existingRoom?.members ?: _uiState.value.members.map { JsonPrimitive(it.name) },
                    lease = myLease,
                    maxBotMessages = _uiState.value.maxBotMessages,
                    maxContinuationPasses = _uiState.value.maxContinuationPasses,
                    systemPrompt = _uiState.value.systemPrompt,
                    speakingOrder = _uiState.value.speakingOrder,
                    finalSummaryMember = _uiState.value.finalSummaryMember,
                    updatedAt = now,
                )

            val updatedRooms = existingSnapshot.rooms.orEmpty().toMutableMap()
            updatedRooms[targetKey] = updatedRoom
            val newSnapshot = existingSnapshot.copy(version = 3, updatedAt = now, rooms = updatedRooms)
            if (activeEpoch != epoch || !groupTransport.isCurrent) return false
            groupTransport.configureSnapshot(newSnapshot.toMap())
            if (activeEpoch != epoch || !groupTransport.isCurrent) return false
            leaseEpoch = epoch

            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("GroupChatViewModel", "acquireOrVerifyLease failed: ${e.javaClass.simpleName}")
            if (activeEpoch == epoch) _uiState.update { it.copy(sendIssue = GroupSendIssue.LEASE_UNCONFIRMED) }
            return false
        }
    }

    private suspend fun executeBotTurn(
        bot: ProfileInfo,
        epoch: String,
        members: List<ProfileInfo>,
        isFinalSummary: Boolean = false,
        discussionTurn: GroupDiscussionTurn? = null,
        discussionRounds: Int = DEFAULT_DISCUSSION_ROUNDS,
    ): GroupTurnResult? {
        if (activeEpoch != epoch || bot.name in failedMembers) return null

        val currentNonSystemMessages = confirmedHistory(bot.name)
        val seen = (memberWatermarks[bot.name] ?: 0).coerceAtLeast(0)
        val delta = currentNonSystemMessages.drop(seen)

        if (!isFinalSummary && discussionTurn == null && delta.isEmpty() && currentNonSystemMessages.isNotEmpty()) {
            return null
        }

        if (leaseEpoch != epoch || !groupTransport.isCurrent) return null
        val profiles = fetchProfiles()
        if (activeEpoch != epoch || !groupTransport.isCurrent) return null
        val metadataProfile = profiles.find { it.is_default == true || it.name == "default" } ?: profiles.firstOrNull()
        if (metadataProfile == null || !acquireOrVerifyLease(metadataProfile, epoch)) {
            failedMembers.add(bot.name)
            updateDelivery(bot.name, GroupDelivery.NOT_SENT, epoch)
            return null
        }
        _uiState.update { it.copy(activeSpeaker = bot.effectiveTitle) }

        // Keep this discussion's confirmed request when later rounds exceed the recent-message window.
        val discussionRequest =
            if (discussionTurn != null || isFinalSummary) {
                currentNonSystemMessages
                    .find { it.id == currentUserMessageId }
                    ?.takeIf { request ->
                        currentNonSystemMessages.takeLast(HISTORY_LIMIT).none { it.id == request.id }
                    }?.text
            } else {
                null
            }
        val turnPrompt =
            GroupChatMentions.buildTurnPrompt(
                groupName = groupName,
                viewer = bot,
                peers = members.filter { it.name != bot.name },
                recentLog = currentNonSystemMessages,
                historyLimit = HISTORY_LIMIT,
                customRoomPrompt = _uiState.value.systemPrompt,
            ) + (discussionRequest?.let { "\n\nCurrent discussion request:\n$it" } ?: "")

        val turnId = UUID.randomUUID().toString()
        val prompt =
            if (isFinalSummary) {
                turnPrompt +
                    "\n\nFinal summary turn: summarize the completed discussion, compare proposals and criticism, and state the conclusion or open questions. Do not hand off to another member and do not pass."
            } else if (discussionTurn != null) {
                turnPrompt +
                    "\n\nDiscussion round ${discussionTurn.round} of $discussionRounds: this is your scheduled contribution. Consider the latest completed contributions and develop, compare, or critique the proposals. Reply with (pass) only if you have nothing to add."
            } else {
                turnPrompt
            }

        val submittedPrompt = "$prompt\n\n[Group turn: $turnId]"
        val turnStartTime = turnClock()
        val streamMsgId = UUID.randomUUID().toString()
        var activeRuntimeId: String? = null
        var activeStoredId: String? = null
        var promptAttempted = false
        var promptAcknowledged = false
        try {
            val sessionInfo = ensureMemberSession(bot)
            if (activeEpoch != epoch || !groupTransport.isCurrent) return null
            val runtimeId = sessionInfo.runtimeSessionId
            val storedId = sessionInfo.storedSessionId
            activeRuntimeId = runtimeId
            activeStoredId = storedId
            activeStreamMsgId[runtimeId] = streamMsgId
            storedId?.let { activeStreamMsgId[it] = streamMsgId }
            sessionToBot[runtimeId] = bot
            storedId?.let { sessionToBot[it] = bot }
            val turnDeferred = CompletableDeferred<String>()
            inFlightTurns[runtimeId] = turnDeferred
            storedId?.let { inFlightTurns[it] = turnDeferred }
            val turnRecord =
                GroupTurnRecord(
                    id = turnId,
                    userMessageId = requireNotNull(currentUserMessageId),
                    responseMessageId = streamMsgId,
                    member = bot.name,
                    runtimeSessionId = runtimeId,
                    storedSessionId = storedId,
                    promptHash = groupPromptHash(submittedPrompt),
                )
            if (!checkpoint(conversation()?.turns.orEmpty() + turnRecord)) return null
            // Until the server ACK arrives, a handoff may have reached the peer.
            // Mark before submit so a reentrant stop/reconnect cannot label it unsent.
            updateDelivery(bot.name, GroupDelivery.UNKNOWN, epoch)
            if (storageFailed) return null
            val submission = groupTransport.submit(sessionInfo, submittedPrompt)
            promptAttempted = submission.accepted
            try {
                submission.response.await()
                promptAcknowledged = true
                updateDelivery(bot.name, GroupDelivery.ACCEPTED, epoch)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // ACK timeout is not execution failure. Keep observing the exact session until
                // completion or the activity deadline; never submit this prompt a second time.
                val code = (failure as? HermesRpcException)?.code
                if (!promptAttempted || code != -1) {
                    throw failure
                }
            }

            var replyText: String? = null
            var deadline = turnStartTime + GROUP_TURN_TIMEOUT_MS
            val hardCapDeadline = turnStartTime + GROUP_TURN_HARD_CAP_MS

            while (turnClock() < deadline) {
                val pollResult =
                    withTimeoutOrNull(2000L) {
                        turnDeferred.await()
                    }
                if (pollResult != null) {
                    replyText = pollResult
                    break
                }
                if (activeEpoch != epoch) break

                // Adaptive extension: if bot is actively working (tokens/tools streamed in last 60s), extend deadline
                val lastActivity =
                    listOfNotNull(
                        activeRuntimeId?.let { sessionLastActivity[it] },
                        activeStoredId?.let { sessionLastActivity[it] },
                    ).maxOrNull() ?: turnStartTime

                val isBusy = (turnClock() - lastActivity) < 60_000L
                if (isBusy) {
                    deadline =
                        minOf(hardCapDeadline, maxOf(deadline, turnClock() + GROUP_TURN_TIMEOUT_MS))
                }
            }
            inFlightTurns.remove(runtimeId)
            storedId?.let { inFlightTurns.remove(it) }

            if (activeEpoch != epoch) {
                return null
            }

            if (replyText != null && !isPass(replyText)) {
                _uiState.update { state ->
                    val existing = state.messages.find { it.id == streamMsgId }
                    val finalTools =
                        existing?.toolCalls?.map { it.copy(isRunning = false) } ?: emptyList()
                    val finalMsg =
                        GroupChatMessage(
                            id = streamMsgId,
                            senderName = bot.name,
                            senderDisplayName = bot.effectiveTitle,
                            isUser = false,
                            avatarMeta = bot.botMeta()?.avatar,
                            text = replyText.trim(),
                            isStreaming = false,
                            toolCalls = finalTools,
                        )
                    val updatedList =
                        if (existing != null) {
                            state.messages.map { if (it.id == streamMsgId) finalMsg else it }
                        } else {
                            state.messages + finalMsg
                        }
                    state.copy(messages = updatedList)
                }
                updateDelivery(bot.name, GroupDelivery.COMPLETE, epoch)
                return GroupTurnResult(replyText.trim())
            } else if (replyText != null && isPass(replyText)) {
                _uiState.update { state ->
                    val existing = state.messages.find { it.id == streamMsgId }
                    val passMsg =
                        GroupChatMessage(
                            id = streamMsgId,
                            senderName = bot.name,
                            senderDisplayName = bot.effectiveTitle,
                            isUser = false,
                            isSystem = true,
                            isPass = true,
                            text = "${bot.effectiveTitle} passed",
                        )
                    val updatedList =
                        if (existing != null) {
                            state.messages.map { if (it.id == streamMsgId) passMsg else it }
                        } else {
                            state.messages + passMsg
                        }
                    state.copy(messages = updatedList)
                }
                updateDelivery(bot.name, GroupDelivery.COMPLETE, epoch)
                return GroupTurnResult(null)
            } else {
                failedMembers.add(bot.name)
                updateDelivery(bot.name, GroupDelivery.UNKNOWN, epoch)
                if (activeEpoch == epoch) {
                    _uiState.update { state ->
                        state.copy(
                            sendIssue = GroupSendIssue.UNKNOWN,
                            messages =
                                state.messages.map {
                                    if (it.id == streamMsgId) it.copy(isStreaming = false, isIncomplete = true) else it
                                },
                        )
                    }
                }
                viewModelScope.launch(ioDispatcher) {
                    delay(1_000)
                    reconcilePending()
                }
                return null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("GroupChatViewModel", "Turn failed: ${e.javaClass.simpleName}")
            val delivery =
                if (!promptAttempted || (e as? HermesRpcException)?.code == OfflineSendPolicy.NOT_SENT) {
                    GroupDelivery.NOT_SENT
                } else if ((e as? HermesRpcException)?.code in setOf(-32600, -32601, -32602)) {
                    GroupDelivery.FAILED
                } else {
                    GroupDelivery.UNKNOWN
                }
            if (activeEpoch == epoch) failedMembers.add(bot.name)
            updateDelivery(bot.name, delivery, epoch)
            if (activeEpoch == epoch) {
                _uiState.update { state ->
                    state.copy(
                        sendIssue =
                            if (delivery ==
                                GroupDelivery.UNKNOWN
                            ) {
                                GroupSendIssue.UNKNOWN
                            } else {
                                GroupSendIssue.MEMBER_FAILED
                            },
                        sendRpcErrorCode = (e as? HermesRpcException)?.code,
                        messages =
                            state.messages.map {
                                if (it.id == streamMsgId) it.copy(isStreaming = false, isIncomplete = true) else it
                            },
                    )
                }
            }
            if (!promptAcknowledged) memberSessions.remove(bot.name)
            if (delivery == GroupDelivery.UNKNOWN) {
                viewModelScope.launch(ioDispatcher) {
                    delay(1_000)
                    reconcilePending()
                }
            }
            return null
        } finally {
            listOfNotNull(activeRuntimeId, activeStoredId).forEach { id ->
                if (activeStreamMsgId[id] == streamMsgId) {
                    activeStreamMsgId.remove(id)
                    sessionToBot.remove(id)
                    inFlightTurns.remove(id)
                    sessionLastActivity.remove(id)
                }
            }
        }
    }

    private fun cancelTurns(sendInterrupt: Boolean) {
        serverPoll?.cancel()
        serverPoll = null
        reconciliationJob?.cancel()
        if (sendInterrupt && groupTransport.isCurrent) {
            memberSessions.values.filter { it.runtimeSessionId in inFlightTurns }.forEach { session ->
                try {
                    groupTransport.interrupt(session)
                } catch (_: Exception) {
                    // No replay.
                }
            }
        }
        activeEpoch = UUID.randomUUID().toString()
        sendJob?.cancel()
        sendJob = null
        leaseEpoch = null
        routeEstablishedEpoch = null
        inFlightTurns.values.forEach { it.cancel() }
        inFlightTurns.clear()
        activeStreamMsgId.clear()
        sessionToBot.clear()
        sessionLastActivity.clear()
        memberSessions.clear()
        groupTransport.close()
        _uiState.update { state ->
            state.copy(
                isSending = false,
                activeSpeaker = null,
                isReconciling = false,
                messages =
                    state.messages.map { message ->
                        message.copy(
                            isStreaming = false,
                            isIncomplete = message.isIncomplete || message.isStreaming,
                            deliveries =
                                message.deliveries.mapValues { (_, value) ->
                                    when (value) {
                                        GroupDelivery.PENDING -> GroupDelivery.NOT_SENT
                                        GroupDelivery.ACCEPTED -> GroupDelivery.UNKNOWN
                                        else -> value
                                    }
                                },
                        )
                    },
            )
        }
        checkpoint(
            conversation()?.turns?.map {
                if (it.delivery ==
                    GroupDelivery.ACCEPTED
                ) {
                    it.copy(delivery = GroupDelivery.UNKNOWN)
                } else {
                    it
                }
            },
        )
    }

    fun stopGeneration() {
        if (serverTransport != null) {
            val id = _uiState.value.conversationId
            val group = groupName
            val key = archiveKey
            serverPoll?.cancel()
            serverPoll =
                viewModelScope.launch(ioDispatcher) {
                    if (key != archiveKey || key != "${conversationScope()}\u0000$group") return@launch
                    try {
                        val snapshot = serverTransport.stop(id, group)
                        if (key == archiveKey && id == _uiState.value.conversationId) applyServerSnapshot(snapshot)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        if (key == archiveKey && id == _uiState.value.conversationId) serverFailure(failure)
                    }
                }
            return
        }
        Log.i("GroupChatViewModel", "stopGeneration requested by user")
        cancelTurns(sendInterrupt = true)
        val stopMessage =
            GroupChatMessage(
                id = UUID.randomUUID().toString(),
                senderName = "system",
                senderDisplayName = "System",
                isUser = false,
                isSystem = true,
                text = STOPPED_SYSTEM_TEXT,
            )
        _uiState.update { it.copy(messages = it.messages + stopMessage) }
        checkpoint()
    }

    override fun onCleared() {
        checkpoint()
        cancelTurns(sendInterrupt = serverTransport == null)
        super.onCleared()
    }

    fun leaveGroup(
        name: String,
        readerId: String? = null,
    ) {
        // Outgoing and incoming Navigation entries can overlap while sharing this ViewModel.
        if (name != groupName || readerId != activeReaderId) return
        activeReaderId = null
        if (serverTransport != null) {
            // Detach the reader only. The server owns the accepted discussion.
            serverPoll?.cancel()
            serverPoll = null
            checkpoint()
            return
        }
        if (_uiState.value.isSending) {
            cancelTurns(sendInterrupt = true)
            _uiState.update { it.copy(sendIssue = GroupSendIssue.UNKNOWN) }
        } else {
            groupTransport.close()
            memberSessions.clear()
            leaseEpoch = null
            routeEstablishedEpoch = null
        }
        checkpoint()
    }

    fun sendMessage(rawText: String) {
        val text = rawText.trim()
        Log.d("GroupChatViewModel", "sendMessage chars=${text.length}")
        if (text.isBlank()) return

        if (isGroupStopCommand(text)) {
            stopGeneration()
            return
        }

        if (serverTransport != null) {
            sendServerMessage(text)
            return
        }

        if (_uiState.value.isSending || _uiState.value.isSavingGroupSettings || _uiState.value.isReconciling ||
            storageFailed
        ) {
            return
        }
        if (!openArchive()) return
        if (conversation()?.turns?.any { it.delivery in unresolvedDeliveries } == true) {
            _uiState.update { it.copy(sendIssue = GroupSendIssue.UNKNOWN) }
            reconcilePending()
            return
        }
        val members = _uiState.value.members
        val controlledOrder = _uiState.value.speakingOrder != null
        val summaryMember = _uiState.value.finalSummaryMember
        if (members.isEmpty() || members.any { it.name.isBlank() }) {
            _uiState.update { it.copy(sendIssue = GroupSendIssue.INVALID_MEMBERS) }
            return
        }

        val epoch = UUID.randomUUID().toString()
        val requestedScope = groupTransport.connectionScope.value
        val requestedArchiveKey = archiveKey
        activeEpoch = epoch

        val userMessage =
            GroupChatMessage(
                id = UUID.randomUUID().toString(),
                senderName = "user",
                senderDisplayName = "You",
                isUser = true,
                text = text,
                deliveries =
                    GroupChatMentions.resolveResponders(text, members).associate {
                        it.name to
                            GroupDelivery.PENDING
                    },
            )
        currentUserMessageId = userMessage.id
        failedMembers.clear()
        members.forEach { member -> memberWatermarks[member.name] = confirmedHistory(member.name).size }
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isSending = true,
                sendIssue = null,
                draft = "",
                sendRpcErrorCode = null,
            )
        }
        if (!checkpoint()) {
            _uiState.update { it.copy(isSending = false) }
            return
        }

        sendJob =
            viewModelScope.launch(ioDispatcher) {
                try {
                    val allProfiles = fetchProfiles()
                    if (activeEpoch != epoch || archiveKey != requestedArchiveKey) return@launch
                    val defaultProfile =
                        allProfiles.find { it.is_default == true || it.name == "default" }
                            ?: allProfiles.firstOrNull()

                    val preflightIssue =
                        when {
                            requestedScope == null -> {
                                GroupSendIssue.UNAVAILABLE
                            }

                            groupTransport.connectionScope.value != requestedScope -> {
                                GroupSendIssue.CONNECTION_CHANGED
                            }

                            defaultProfile == null -> {
                                GroupSendIssue.PROFILE_LIST_UNAVAILABLE
                            }

                            members.any { member -> allProfiles.none { it.name == member.name } } -> {
                                GroupSendIssue.MEMBERS_MISSING
                            }

                            !groupTransport.open(
                                "$groupName/${_uiState.value.conversationId}",
                                members
                                    .map {
                                        it.name
                                    }.toSet(),
                                defaultProfile.name,
                            ) -> {
                                GroupSendIssue.UNAVAILABLE
                            }

                            else -> {
                                null
                            }
                        }
                    if (preflightIssue != null) {
                        Log.w("GroupChatViewModel", "Group preflight rejected: ${preflightIssue.name}")
                        _uiState.update { it.copy(sendIssue = preflightIssue) }
                        return@launch
                    }
                    val metadataProfile = requireNotNull(defaultProfile)
                    routeEstablishedEpoch = epoch
                    memberSessions.clear()
                    if (!acquireOrVerifyLease(metadataProfile, epoch)) return@launch

                    var postedBotMessages = 0
                    var attemptedBotTurns = 0
                    var continuationsCount = 0
                    val maxBotLimit = _uiState.value.maxBotMessages
                    val maxPassLimit = _uiState.value.maxContinuationPasses
                    val initialResponders = GroupChatMentions.resolveResponders(text, members)
                    val userMentions = GroupChatMentions.parseMentions(text, members)
                    val directlyAddressed = !userMentions.isEveryone && userMentions.mentionedBots.isNotEmpty()
                    val roundCount = if (directlyAddressed) 1 else _uiState.value.discussionRounds
                    val scheduler =
                        if (controlledOrder) {
                            GroupRoundScheduler(
                                initialResponders.map { it.name },
                                roundCount,
                                maxPassLimit,
                                allowMentions = !directlyAddressed,
                            )
                        } else {
                            null
                        }
                    val remainingInitial = initialResponders.toMutableList()

                    if (scheduler != null) {
                        while (scheduler.hasPending && attemptedBotTurns < maxBotLimit) {
                            if (activeEpoch != epoch) return@launch
                            val turn = scheduler.nextTurn() ?: break
                            val bot = members.first { it.name == turn.member }
                            attemptedBotTurns++
                            val result =
                                executeBotTurn(
                                    bot,
                                    epoch,
                                    members,
                                    discussionTurn = turn,
                                    discussionRounds = roundCount,
                                )
                            if (activeEpoch != epoch) return@launch
                            // A failed or unconfirmed turn ends this discussion; never retry or start its summary.
                            if (result == null) return@launch
                            memberWatermarks[bot.name] = confirmedHistory(bot.name).size
                            result.reply?.let { reply ->
                                postedBotMessages++
                                persistSyncSnapshot(_uiState.value.messages, extendLease = true)
                                if (activeEpoch != epoch) return@launch
                                scheduler.prioritize(
                                    GroupChatMentions
                                        .parseMentions(
                                            reply,
                                            members,
                                            excludedSpeaker = bot.name,
                                        ).mentionedBots,
                                    bot.name,
                                )
                            }
                        }
                    } else {
                        while (remainingInitial.isNotEmpty()) {
                            if (activeEpoch != epoch) return@launch
                            if (postedBotMessages >= maxBotLimit) break
                            val bot = remainingInitial.removeAt(0)
                            val reply = executeBotTurn(bot, epoch, members)?.reply
                            memberWatermarks[bot.name] = confirmedHistory(bot.name).size
                            if (reply != null) {
                                postedBotMessages++
                                persistSyncSnapshot(_uiState.value.messages, extendLease = true)
                            }
                        }
                    }

                    while (!controlledOrder && continuationsCount < maxPassLimit && postedBotMessages < maxBotLimit) {
                        if (activeEpoch != epoch) return@launch

                        val recentAssistantMsgs = currentRoundReplies()

                        val pendingCitedBots = mutableListOf<ProfileInfo>()
                        for (msg in recentAssistantMsgs) {
                            val parsed =
                                GroupChatMentions.parseMentions(
                                    text = msg.text,
                                    members = members,
                                    excludedSpeaker = msg.senderName,
                                )
                            for (citedName in parsed.mentionedBots) {
                                val bot = members.find { it.name.equals(citedName, ignoreCase = true) }
                                if (bot != null && bot.name !in failedMembers &&
                                    !pendingCitedBots.any { it.name == bot.name }
                                ) {
                                    val seenCount = memberWatermarks[bot.name] ?: 0
                                    if (seenCount < confirmedHistory(bot.name).size) {
                                        pendingCitedBots.add(bot)
                                    }
                                }
                            }
                        }

                        if (pendingCitedBots.isEmpty()) {
                            // Quiescence / Natural Settle reached
                            break
                        }

                        continuationsCount++
                        val continuationResponders = pendingCitedBots.toMutableList()

                        while (continuationResponders.isNotEmpty()) {
                            if (activeEpoch != epoch) return@launch
                            if (postedBotMessages >= maxBotLimit) break

                            val bot = continuationResponders.removeAt(0)
                            val reply = executeBotTurn(bot, epoch, members)?.reply
                            val nonSystemSize =
                                confirmedHistory(bot.name)
                                    .size
                            memberWatermarks[bot.name] = nonSystemSize

                            if (reply != null) {
                                postedBotMessages++
                                persistSyncSnapshot(_uiState.value.messages, extendLease = true)
                            }
                        }
                    }

                    val summaryBot = members.find { it.name == summaryMember }
                    val summaryPending =
                        scheduler != null && !directlyAddressed && !scheduler.hasPending &&
                            summaryBot != null && postedBotMessages > 0
                    var summaryAttempted = false
                    if (summaryPending && attemptedBotTurns < maxBotLimit && activeEpoch == epoch) {
                        summaryAttempted = true
                        attemptedBotTurns++
                        val reply =
                            executeBotTurn(
                                requireNotNull(summaryBot),
                                epoch,
                                members,
                                isFinalSummary = true,
                            )?.reply
                        if (reply != null) {
                            postedBotMessages++
                            persistSyncSnapshot(_uiState.value.messages, extendLease = false)
                        }
                    }

                    val isBudgetExceeded =
                        if (controlledOrder) {
                            attemptedBotTurns >= maxBotLimit
                        } else {
                            postedBotMessages >= maxBotLimit || continuationsCount >= maxPassLimit
                        }
                    val hasUnfinishedWork =
                        if (scheduler != null) {
                            scheduler.hasPending || (summaryPending && !summaryAttempted)
                        } else {
                            remainingInitial.isNotEmpty() || hasPendingMentions(members)
                        }

                    if (isBudgetExceeded && hasUnfinishedWork && activeEpoch == epoch) {
                        val cappedMessage =
                            GroupChatMessage(
                                id = UUID.randomUUID().toString(),
                                senderName = "system",
                                senderDisplayName = "System",
                                isUser = false,
                                isSystem = true,
                                text = CAPPED_SYSTEM_TEXT,
                            )
                        _uiState.update { it.copy(messages = it.messages + cappedMessage) }
                        persistSyncSnapshot(_uiState.value.messages, extendLease = false)
                    }
                } finally {
                    if (activeEpoch == epoch) {
                        _uiState.update { state ->
                            state.copy(
                                activeSpeaker = null,
                                isSending = false,
                                messages =
                                    state.messages.map { message ->
                                        if (message.id ==
                                            userMessage.id
                                        ) {
                                            message.copy(
                                                deliveries =
                                                    message.deliveries.mapValues { (_, value) ->
                                                        if (value ==
                                                            GroupDelivery.PENDING
                                                        ) {
                                                            GroupDelivery.NOT_SENT
                                                        } else {
                                                            value
                                                        }
                                                    },
                                            )
                                        } else {
                                            message
                                        }
                                    },
                            )
                        }
                        checkpoint()
                    }
                }
            }
    }

    private fun serverFailure(failure: Exception) {
        val code = (failure as? retrofit2.HttpException)?.code()
        _uiState.update {
            it.copy(
                isReconciling = false,
                isSending = false,
                activeSpeaker = null,
                serverPending = true,
                errorMessage =
                    when (code) {
                        404, 405 -> "グループ会話の継続にはサーバーの更新が必要です。保存済みの会話は端末に残っています。"
                        401, 403 -> "グループ会話を確認できません。接続設定を確認してください。"
                        else -> "サーバーの会話を確認できません。履歴は保持しています。再確認してください。"
                    },
            )
        }
    }

    private fun applyServerSnapshot(snapshot: GroupServerSnapshot) {
        if (snapshot.id != _uiState.value.conversationId || snapshot.groupName != groupName) return
        val current = conversation() ?: return
        val pending = current.pendingServerRequest
        val accepted = pending == null || snapshot.messages.any { it.id == pending.requestId }
        val next =
            current.copy(
                title = current.generatedTitle ?: snapshot.title.ifBlank { current.title },
                createdAt = snapshot.createdAt,
                messages =
                    snapshot.messages.map { message ->
                        val member = _uiState.value.members.find { it.name == message.senderName }
                        message.copy(avatarMeta = member?.botMeta()?.avatar ?: message.avatarMeta)
                    } + if (accepted) emptyList() else current.messages.filter { it.id == pending?.requestId },
                pendingServerRequest = if (accepted) null else pending,
            )
        val currentArchive = requireNotNull(archive)
        if (!writeArchive(
                currentArchive.copy(
                    conversations =
                        currentArchive.conversations.map {
                            if (it.id ==
                                next.id
                            ) {
                                next
                            } else {
                                it
                            }
                        },
                ),
            )
        ) {
            return
        }
        _uiState.update {
            it.copy(
                messages = next.messages,
                isSending = snapshot.running,
                activeSpeaker = snapshot.activeSpeaker,
                serverPending = !accepted,
                isReconciling = false,
                sendIssue = null,
                errorMessage =
                    when (snapshot.state) {
                        "interrupted" -> "サーバーが再起動したため議論が中断しました。保存済みの発言は残っています。"
                        "failed" -> "エージェントの発言を完了できませんでした。保存済みの発言は残っています。"
                        "capped" -> "発言数の上限に達しました。メッセージを送ると続けられます。"
                        "stopped" -> "議論を停止しました。"
                        else -> null
                    },
            )
        }
        generateConversationTitle()
        if (accepted) {
            archiveKey?.let { key ->
                conversationScope()?.let { owner -> syncConversationTitle(key, owner, groupName, next, snapshot.title) }
            }
        }
    }

    private fun sendServerMessage(text: String) {
        if (_uiState.value.isLoading || _uiState.value.isSending || _uiState.value.isSavingGroupSettings ||
            storageFailed || !openArchive()
        ) {
            return
        }
        val current = conversation() ?: return
        // Retrying an uncertain handoff reuses its durable idempotency key.
        if (current.pendingServerRequest != null) {
            refreshServerConversation()
            return
        }
        val state = _uiState.value
        val responders = GroupChatMentions.resolveResponders(text, state.members)
        if (responders.isEmpty()) return
        val mentioned = GroupChatMentions.parseMentions(text, state.members)
        val direct = !mentioned.isEveryone && mentioned.mentionedBots.isNotEmpty()
        val request =
            GroupServerRequest(
                requestId = UUID.randomUUID().toString(),
                groupName = groupName,
                text = text,
                members = state.members.map { it.name },
                memberLabels = state.members.associate { it.name to it.effectiveTitle },
                rounds = if (direct) 1 else state.discussionRounds,
                maxMessages = state.maxBotMessages,
                maxContinuationPasses = state.maxContinuationPasses,
                controlledOrder = state.speakingOrder != null,
                initialResponders = responders.map { it.name },
                direct = direct,
                summaryMember =
                    state.finalSummaryMember?.takeIf {
                        !direct &&
                            responders.any { bot ->
                                bot.name == it
                            }
                    },
                systemPrompt = state.systemPrompt,
                seed = current.messages,
            )
        val message = GroupChatMessage(request.requestId, "user", "You", true, text = text)
        val updated =
            current.copy(
                title = current.title.ifBlank { text.take(60) },
                messages = current.messages + message,
                draft = "",
                pendingServerRequest = request,
            )
        val currentArchive = requireNotNull(archive)
        if (!writeArchive(
                currentArchive.copy(
                    conversations =
                        currentArchive.conversations.map {
                            if (it.id ==
                                current.id
                            ) {
                                updated
                            } else {
                                it
                            }
                        },
                ),
            )
        ) {
            return
        }
        _uiState.update { it.copy(messages = updated.messages, draft = "", serverPending = true, errorMessage = null) }
        refreshServerConversation()
    }

    fun refreshServerConversation() {
        val transport = serverTransport ?: return
        if (serverPoll?.isActive == true || storageFailed || groupName.isBlank()) return
        val current = conversation() ?: return
        val id = current.id
        val group = groupName
        val key = archiveKey
        _uiState.update { it.copy(isReconciling = true, serverPending = current.pendingServerRequest != null) }
        serverPoll =
            viewModelScope.launch(ioDispatcher) {
                if (key != archiveKey || key != "${conversationScope()}\u0000$group") return@launch
                try {
                    val pending = current.pendingServerRequest
                    var snapshot = if (pending != null) transport.send(id, pending) else transport.read(id, group)
                    while (key == archiveKey && id == _uiState.value.conversationId) {
                        applyServerSnapshot(snapshot)
                        if (!snapshot.running) break
                        delay(1500)
                        if (key != archiveKey || key != "${conversationScope()}\u0000$group") break
                        snapshot = transport.read(id, group)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (key == archiveKey && id == _uiState.value.conversationId) {
                        val missing =
                            failure is retrofit2.HttpException && failure.code() == 404 &&
                                failure
                                    .response()
                                    ?.errorBody()
                                    ?.string()
                                    ?.contains("conversation_not_found") == true
                        if (missing && current.pendingServerRequest == null) {
                            _uiState.update { it.copy(isReconciling = false, isSending = false, serverPending = false) }
                        } else {
                            serverFailure(failure)
                        }
                    }
                }
            }
    }

    private fun currentRoundReplies(): List<GroupChatMessage> =
        _uiState.value.messages
            .dropWhile { it.id != currentUserMessageId }
            .drop(1)
            .filter { !it.isUser && !it.isSystem && !it.isStreaming && !it.isIncomplete }

    private fun hasPendingMentions(members: List<ProfileInfo>): Boolean {
        val recentAssistantMsgs = currentRoundReplies()

        for (msg in recentAssistantMsgs) {
            val parsed =
                GroupChatMentions.parseMentions(
                    text = msg.text,
                    members = members,
                    excludedSpeaker = msg.senderName,
                )
            for (citedName in parsed.mentionedBots) {
                val bot = members.find { it.name.equals(citedName, ignoreCase = true) }
                if (bot != null && bot.name !in failedMembers) {
                    val seenCount = memberWatermarks[bot.name] ?: 0
                    if (seenCount < confirmedHistory(bot.name).size) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun persistSyncSnapshot(
        currentMessages: List<GroupChatMessage>,
        extendLease: Boolean = true,
    ) {
        // Preserve the legacy room log for older clients. New conversations are private local archives.
        if (archive?.activeId != archive?.conversations?.firstOrNull()?.id) return
        val snapshotEpoch = activeEpoch
        val snapshotRoom = groupName
        if (leaseEpoch != snapshotEpoch || !groupTransport.isCurrent) return
        viewModelScope.launch(ioDispatcher) {
            try {
                val allProfiles = fetchProfiles()
                if (activeEpoch != snapshotEpoch || groupName != snapshotRoom ||
                    !groupTransport.isCurrent
                ) {
                    return@launch
                }
                val defaultProfile =
                    allProfiles.find { it.is_default == true || it.name == "default" } ?: return@launch
                val existingSnapshot =
                    defaultProfile.groupChatSyncSnapshot()
                        ?: GroupChatSyncSnapshot(version = 3, rooms = emptyMap())

                val logEntries =
                    currentMessages
                        .filter {
                            !it.isSystem && !it.isStreaming && !it.isIncomplete &&
                                (
                                    it.deliveries.isEmpty() ||
                                        it.deliveries.values.all { status ->
                                            status in
                                                setOf(GroupDelivery.ACCEPTED, GroupDelivery.COMPLETE)
                                        }
                                )
                        }.map { msg ->
                            GroupChatSyncLogEntry(
                                id = msg.id,
                                from =
                                    GroupChatSyncFrom(
                                        kind = if (msg.isUser) "user" else "member",
                                        name = if (msg.isUser) "You" else msg.senderName,
                                    ),
                                text = msg.text,
                                at = msg.timestamp,
                                thread = msg.thread,
                            )
                        }

                val now = System.currentTimeMillis()
                val (targetKey, existingRoom) = findRoomEntry(existingSnapshot.rooms)

                val currentLease = existingRoom?.lease
                if (currentLease?.driverId != localDriverId || currentLease.epoch != snapshotEpoch ||
                    (currentLease.expiresAt ?: 0L) <= now
                ) {
                    return@launch
                }
                val updatedLease =
                    if (extendLease) {
                        GroupChatRoomLease(
                            driverId = localDriverId,
                            epoch = activeEpoch,
                            expiresAt = now + LEASE_STEP_TTL_MS,
                        )
                    } else {
                        existingRoom?.lease
                    }

                val updatedRoom =
                    (existingRoom ?: GroupChatRoomMeta(name = groupName, createdAt = now)).copy(
                        name = groupName,
                        members = existingRoom?.members ?: _uiState.value.members.map { JsonPrimitive(it.name) },
                        log = (existingRoom?.log.orEmpty() + logEntries).distinctBy { it.id ?: it },
                        lease = updatedLease,
                        maxBotMessages = _uiState.value.maxBotMessages,
                        maxContinuationPasses = _uiState.value.maxContinuationPasses,
                        systemPrompt = _uiState.value.systemPrompt,
                        speakingOrder = _uiState.value.speakingOrder,
                        finalSummaryMember = _uiState.value.finalSummaryMember,
                        updatedAt = now,
                    )

                val updatedRooms = existingSnapshot.rooms.orEmpty().toMutableMap()
                updatedRooms[targetKey] = updatedRoom
                val newSnapshot =
                    existingSnapshot.copy(
                        version = 3,
                        updatedAt = now,
                        rooms = updatedRooms,
                    )

                if (activeEpoch != snapshotEpoch || !groupTransport.isCurrent) return@launch
                groupTransport.configureSnapshot(newSnapshot.toMap())
            } catch (e: Exception) {
                Log.w("GroupChatViewModel", "persistSyncSnapshot failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun isPass(text: String): Boolean {
        val clean = text.trim().lowercase()
        return clean == "(pass)" || clean == "pass" || clean == "(pass)." || clean == "pass."
    }
}
