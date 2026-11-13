package com.m57.hermescontrol.ui.chat

/** Preserve new-list order and duplicates; an existing ID belongs to the old transcript regardless of role. */
internal fun newNonUserMemberIds(
    old: List<ChatMessage>,
    next: List<ChatMessage>,
): List<String> {
    if (old === next) return emptyList()
    val oldIds = old.mapTo(HashSet()) { it.id }
    return next.mapNotNull { message ->
        message.id.takeIf { message.role != MessageRole.USER && it !in oldIds }
    }
}
