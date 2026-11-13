package com.m57.hermescontrol.ui.chat

/**
 * One-entry, Main-confined memo owned by a ChatViewModel. Published message lists must never be
 * mutated in place: a new list identity always needs fresh hydration, even with the same row IDs.
 * Empty outcomes are cached too. Clearing releases both the history and the derived todos.
 */
internal class TodoHistoryMemo {
    private var messages: List<ChatMessage>? = null
    private var todos: List<TodoItem> = emptyList()

    fun hydrate(input: List<ChatMessage>): List<TodoItem> {
        if (input !== messages) {
            val hydrated = hydrateTodosFromMessages(input)
            messages = input
            todos = hydrated
        }
        return todos
    }

    fun clear() {
        messages = null
        todos = emptyList()
    }
}
