package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.ui.bots.GroupInfo
import com.m57.hermescontrol.ui.chat.components.conversationHistoryTitle

/** Room identity and conversation identity stay separate from their editable labels. */
internal data class GroupHistoryEntry(
    val groupName: String,
    val conversationId: String,
    val groupLabel: String,
    val conversationTitle: String,
    val lastActivity: Long,
    val hasGeneratedTitle: Boolean = false,
) {
    val key: String get() = "$groupName\u0000$conversationId"

    fun label(untitled: String): String = "$groupLabel：${conversationHistoryTitle(conversationTitle, null, untitled)}"
}

internal data class GroupHistoryResult(
    val entries: List<GroupHistoryEntry> = emptyList(),
    val failedGroups: Set<String> = emptySet(),
)

/** Older servers still return a first-message fallback; retain this device's generated name. */
internal fun mergeGroupHistory(
    remote: List<GroupHistoryEntry>,
    cached: List<GroupHistoryEntry>,
): List<GroupHistoryEntry> {
    val localTitles = cached.filter { it.hasGeneratedTitle }.associateBy { it.key }
    return (
        remote.map { entry ->
            localTitles[entry.key]?.let {
                entry.copy(
                    conversationTitle = it.conversationTitle,
                    hasGeneratedTitle = true,
                )
            }
                ?: entry
        } + cached
    ).distinctBy { it.key }.sortedByDescending { it.lastActivity }
}

/** Read only: listing a room must never create, overwrite, or recover its stored archive. */
internal fun readGroupHistory(
    groups: List<GroupInfo>,
    scope: String,
    store: GroupConversationStore,
): GroupHistoryResult {
    val failed = mutableSetOf<String>()
    val entries =
        groups.flatMap { group ->
            val key = "$scope\u0000${group.name}"
            val archive =
                try {
                    store.read(key)
                } catch (_: Exception) {
                    failed += group.name
                    return@flatMap emptyList()
                }
            val label =
                group.displayName.takeIf { it != group.name }
                    ?: archive?.displayName?.takeIf { it.isNotBlank() }
                    ?: group.name
            if (archive != null) {
                archive.conversations
                    .filterNot { conversation ->
                        // The first open creates an empty legacy slot before a new session is selected.
                        // Keep real empty sessions; omit only this unused migration placeholder.
                        archive.conversations.size > 1 && conversation.id == legacyGroupConversationId(key) &&
                            conversation.title.isBlank() && conversation.messages.isEmpty() &&
                            conversation.turns.isEmpty() && conversation.draft.isBlank()
                    }.map { conversation ->
                        GroupHistoryEntry(
                            groupName = group.name,
                            conversationId = conversation.id,
                            groupLabel = label,
                            conversationTitle =
                                conversation.generatedTitle ?: conversation.title.ifBlank {
                                    conversation.messages
                                        .firstOrNull { it.isUser }
                                        ?.text
                                        .orEmpty()
                                },
                            lastActivity = conversation.messages.maxOfOrNull { it.timestamp } ?: conversation.createdAt,
                            hasGeneratedTitle = !conversation.generatedTitle.isNullOrBlank(),
                        )
                    }
            } else if (group.legacyLog.isNotEmpty()) {
                listOf(
                    GroupHistoryEntry(
                        groupName = group.name,
                        conversationId = legacyGroupConversationId(key),
                        groupLabel = label,
                        conversationTitle =
                            group.legacyLog
                                .firstOrNull { it.from?.kind != "member" }
                                ?.text
                                .orEmpty(),
                        lastActivity = group.lastActivity,
                    ),
                )
            } else {
                emptyList()
            }
        }
    return GroupHistoryResult(
        entries.sortedWith(compareByDescending<GroupHistoryEntry> { it.lastActivity }.thenBy { it.key }),
        failed,
    )
}
