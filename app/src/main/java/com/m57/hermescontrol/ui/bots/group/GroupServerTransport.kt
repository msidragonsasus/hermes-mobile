package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.serialization.Serializable

@Serializable
data class GroupServerRequest(
    val requestId: String,
    val groupName: String,
    val text: String,
    val members: List<String>,
    val memberLabels: Map<String, String>,
    val rounds: Int,
    val maxMessages: Int,
    val maxContinuationPasses: Int = DEFAULT_MAX_CONTINUATION_PASSES,
    val controlledOrder: Boolean = false,
    val initialResponders: List<String> = members,
    val direct: Boolean = false,
    val summaryMember: String? = null,
    val systemPrompt: String? = null,
    val seed: List<GroupChatMessage> = emptyList(),
)

@Serializable
data class GroupServerTitleRequest(
    val groupName: String,
    val title: String,
    val expectedTitle: String,
)

@Serializable
data class GroupServerSnapshot(
    val id: String,
    val groupName: String,
    val title: String = "",
    val createdAt: Long = 0,
    val messages: List<GroupChatMessage> = emptyList(),
    val runId: String? = null,
    val state: String = "idle",
    val activeSpeaker: String? = null,
    val error: String? = null,
) {
    val running: Boolean get() = state in setOf("queued", "running")
}

@Serializable
data class GroupServerHistoryEntry(
    val id: String,
    val groupName: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class GroupServerHistory(
    val conversations: List<GroupServerHistoryEntry> = emptyList(),
)

interface GroupServerTransport {
    suspend fun rename(
        id: String,
        request: GroupServerTitleRequest,
    ) { }

    suspend fun read(
        id: String,
        group: String,
    ): GroupServerSnapshot

    suspend fun send(
        id: String,
        request: GroupServerRequest,
    ): GroupServerSnapshot

    suspend fun stop(
        id: String,
        group: String,
    ): GroupServerSnapshot
}

class ApiGroupServerTransport : GroupServerTransport {
    override suspend fun rename(
        id: String,
        request: GroupServerTitleRequest,
    ) {
        ApiClient.hermesApi.renameGroupConversation(id, request)
    }

    override suspend fun read(
        id: String,
        group: String,
    ) = ApiClient.hermesApi.groupConversation(id, group)

    override suspend fun send(
        id: String,
        request: GroupServerRequest,
    ) = ApiClient.hermesApi.sendGroupMessage(id, request)

    override suspend fun stop(
        id: String,
        group: String,
    ) = ApiClient.hermesApi.stopGroupConversation(id, group)
}
