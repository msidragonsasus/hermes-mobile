package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.ws.HermesWsClient.HermesRpcException
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement

/** Transient capability for one room, roster and socket generation. Never persisted. */
class GroupSessionAccess internal constructor(
    val scope: ConnectionScope,
    internal val room: String,
    internal val members: Set<String>,
    internal val metadataProfile: String,
) {
    internal val sessions = mutableMapOf<String, GroupMemberSession>()
    internal val acknowledgedCreates = mutableMapOf<String, Pair<String, String?>>()
}

/** Only a matching session.create ACK can register this route with the socket client. */
class GroupMemberSession internal constructor(
    val member: String,
    val runtimeSessionId: String,
    val storedSessionId: String?,
    internal val access: GroupSessionAccess,
)

interface GroupSessionTransport {
    val events: Flow<WsEvent>
    val connectionScope: StateFlow<ConnectionScope?>
    val isCurrent: Boolean
    val availability: Flow<Boolean> get() = connectionScope.map { isCurrent }
    val readReadiness: Flow<Boolean> get() = connectionScope.map { it != null }

    fun open(
        room: String,
        members: Set<String>,
        metadataProfile: String,
    ): Boolean

    suspend fun create(member: String): GroupMemberSession

    fun submit(
        session: GroupMemberSession,
        text: String,
    ): ScopedRpcSubmission

    fun interrupt(session: GroupMemberSession): ScopedRpcSubmission

    suspend fun configureSnapshot(snapshot: Map<String, Any?>)

    suspend fun history(
        member: String,
        storedSessionId: String,
    ): List<SessionMessage>? = null

    suspend fun readHistory(
        member: String,
        storedSessionId: String,
    ): GroupHistoryRead =
        history(member, storedSessionId)?.let { GroupHistoryRead(messages = it) }
            ?: GroupHistoryRead(issue = GroupHistoryIssue.UNAVAILABLE)

    fun close()
}

enum class GroupHistoryIssue {
    UNAVAILABLE,
    AUTH_REQUIRED,
    NOT_FOUND,
    PROFILE_NOT_FOUND,
    ENDPOINT_UNAVAILABLE,
    ID_MISMATCH,
    CONNECTION_CHANGED,
}

data class GroupHistoryRead(
    val messages: List<SessionMessage>? = null,
    val issue: GroupHistoryIssue? = null,
    val completeHistory: Boolean = true,
)

class WsGroupSessionTransport : GroupSessionTransport {
    private var access: GroupSessionAccess? = null
    override val connectionScope: StateFlow<ConnectionScope?> get() = HermesWsClient.connectionScope
    override val isCurrent: Boolean get() = access?.let(HermesWsClient::isCurrentGroupAccess) == true
    override val readReadiness: Flow<Boolean> =
        combine(connectionScope, HermesWsClient.connectionStatus) { scope, status ->
            scope != null && status == ConnectionStatus.CONNECTED
        }
    override val availability: Flow<Boolean> =
        combine(
            connectionScope,
            HermesWsClient.connectionStatus,
            AuthManager.activeProfileId,
            AuthManager.selectedProfileFlow,
            AuthManager.baseUrlFlow,
        ) { _, _, _, _, _ -> isCurrent }
    override val events: Flow<WsEvent> =
        HermesWsClient.scopedEvents.mapNotNull { observed ->
            access
                ?.takeIf {
                    it.scope == observed.scope &&
                        HermesWsClient.isCurrentGroupAccess(
                            it,
                        )
                }?.let { observed.event }
        }

    override fun open(
        room: String,
        members: Set<String>,
        metadataProfile: String,
    ): Boolean {
        close()
        access = HermesWsClient.openGroupAccess(room, members, metadataProfile)
        return access != null
    }

    override suspend fun create(member: String): GroupMemberSession {
        val current = requireAccess()
        val result = HermesWsClient.createGroupMember(current, member).response.await()
        val values = (if (result is JsonElement) result.toAny() else result) as? Map<*, *>
        val payload = values?.get("result") as? Map<*, *> ?: values
        val runtimeId = payload?.get("session_id") as? String
        val storedId = payload?.get("stored_session_id") as? String
        return HermesWsClient.bindGroupMember(current, member, runtimeId, storedId)
    }

    override fun submit(
        session: GroupMemberSession,
        text: String,
    ): ScopedRpcSubmission = HermesWsClient.submitGroupMember(requireAccess(), session, text)

    override fun interrupt(session: GroupMemberSession): ScopedRpcSubmission =
        HermesWsClient.interruptGroupMember(requireAccess(), session)

    override suspend fun configureSnapshot(snapshot: Map<String, Any?>) {
        HermesWsClient.configureGroupSnapshot(requireAccess(), snapshot).response.await()
    }

    override suspend fun history(
        member: String,
        storedSessionId: String,
    ): List<SessionMessage>? = readHistory(member, storedSessionId).messages

    override suspend fun readHistory(
        member: String,
        storedSessionId: String,
    ): GroupHistoryRead {
        val current = access ?: return GroupHistoryRead(issue = GroupHistoryIssue.UNAVAILABLE)
        if (member !in current.members || !HermesWsClient.isCurrentGroupAccess(current) ||
            !storedSessionId.matches(Regex("[a-zA-Z0-9_./:-]+")) || ".." in storedSessionId
        ) {
            return GroupHistoryRead(issue = GroupHistoryIssue.CONNECTION_CHANGED)
        }
        return GroupHistoryReader(ApiClient.hermesApi).read(member, storedSessionId) {
            access === current && HermesWsClient.isCurrentGroupAccess(current)
        }
    }

    override fun close() {
        access?.let(HermesWsClient::closeGroupAccess)
        access = null
    }

    private fun requireAccess(): GroupSessionAccess =
        access ?: throw HermesRpcException("Request not sent", OfflineSendPolicy.NOT_SENT)
}
