package com.m57.hermescontrol.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ChatMessageDao {
    @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE session_id = :sessionId)")
    suspend fun sessionExists(sessionId: String): Boolean

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("SELECT * FROM chat_transcript_state WHERE target_key = :target AND saved_session_id = :session")
    suspend fun transcriptState(
        target: String,
        session: String,
    ): ChatTranscriptState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTranscriptState(state: ChatTranscriptState)

    @androidx.room3.Transaction
    suspend fun claimTranscript(
        target: String,
        session: String,
    ): TranscriptRows {
        val previous = transcriptState(target, session)
        val owned = ChatTranscriptState(target, session, (previous?.writeEpoch ?: 0) + 1, 0, previous?.payload ?: "")
        putTranscriptState(owned)
        return TranscriptRows(owned, getMessagesForSession(session))
    }

    /** Validate before any body write, inside the same SQL transaction. */
    @androidx.room3.Transaction
    suspend fun saveTranscript(
        state: ChatTranscriptState,
        rows: List<ChatMessageEntity>,
    ): Boolean {
        val current = transcriptState(state.targetKey, state.savedSessionId) ?: return false
        if (state.writeEpoch != current.writeEpoch || state.revision <= current.revision) return false
        val existingById = getMessagesForSession(state.savedSessionId).associateByTo(mutableMapOf()) { it.id }
        // Advance the local comparison for repeated IDs: direct callers retain last-row-wins semantics.
        val changed = rows.filter { existingById.put(it.id, it) != it }
        if (changed.isNotEmpty()) upsertAll(changed)
        putTranscriptState(state)
        return true
    }

    @androidx.room3.Transaction
    suspend fun readTranscript(
        target: String,
        session: String,
    ): TranscriptRows = TranscriptRows(transcriptState(target, session), getMessagesForSession(session))
}
