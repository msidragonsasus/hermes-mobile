package com.m57.hermescontrol.ui.chat.fakes

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.ChatMessageEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * In-memory [ChatMessageDao] for use in tests.
 * Uses [ConcurrentHashMap] for thread safety — no manual synchronization needed.
 */
class FakeChatMessageDao : ChatMessageDao {
    private val messages: ConcurrentMap<String, ChatMessageEntity> = ConcurrentHashMap()

    private val states = ConcurrentHashMap<Pair<String, String>, com.m57.hermescontrol.data.local.ChatTranscriptState>()

    override suspend fun transcriptState(
        target: String,
        session: String,
    ) = states[target to session]

    override suspend fun putTranscriptState(state: com.m57.hermescontrol.data.local.ChatTranscriptState) {
        states[state.targetKey to state.savedSessionId] = state
    }

    private val transaction = kotlinx.coroutines.sync.Mutex()
    var beforeSnapshotSave: (suspend () -> Unit)? = null

    override suspend fun claimTranscript(
        target: String,
        session: String,
    ): com.m57.hermescontrol.data.local.TranscriptRows {
        transaction.lock()
        try {
            return super<ChatMessageDao>.claimTranscript(target, session)
        } finally {
            transaction.unlock()
        }
    }

    override suspend fun saveTranscript(
        state: com.m57.hermescontrol.data.local.ChatTranscriptState,
        rows: List<ChatMessageEntity>,
    ): Boolean {
        beforeSnapshotSave?.invoke()
        transaction.lock()
        try {
            return super<ChatMessageDao>.saveTranscript(state, rows)
        } finally {
            transaction.unlock()
        }
    }

    override suspend fun readTranscript(
        target: String,
        session: String,
    ): com.m57.hermescontrol.data.local.TranscriptRows {
        transaction.lock()
        try {
            return super<ChatMessageDao>.readTranscript(target, session)
        } finally {
            transaction.unlock()
        }
    }

    override suspend fun sessionExists(sessionId: String): Boolean = messages.values.any { it.sessionId == sessionId }

    val sessionReads = mutableListOf<String>()

    override suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity> {
        sessionReads.add(sessionId)
        return messages.values
            .filter { it.sessionId == sessionId }
            .sortedBy { it.timestamp }
    }

    override suspend fun upsert(message: ChatMessageEntity) {
        messages[message.id] = message
    }

    /** Physical batch writes, including empty calls, for persistence work assertions. */
    val bodyWriteBatches = mutableListOf<List<ChatMessageEntity>>()

    override suspend fun upsertAll(messageList: List<ChatMessageEntity>) {
        bodyWriteBatches.add(messageList.toList())
        messageList.forEach { messages[it.id] = it }
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        messages.values.removeAll { it.sessionId == sessionId }
    }

    /** Direct access for test setup — bypasses the suspend modifier. */
    fun addMessageDirect(message: ChatMessageEntity) {
        messages[message.id] = message
    }

    /** Reset all stored messages. */
    fun clear() {
        messages.clear()
    }

    /** Returns the number of stored messages. */
    fun count(): Int = messages.size
}
