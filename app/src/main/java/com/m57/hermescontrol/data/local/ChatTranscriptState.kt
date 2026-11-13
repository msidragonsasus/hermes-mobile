package com.m57.hermescontrol.data.local

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/** No backfill: an old unscoped message cannot establish target provenance. */
@Entity(tableName = "chat_transcript_state", primaryKeys = ["target_key", "saved_session_id"])
data class ChatTranscriptState(
    @ColumnInfo(name = "target_key") val targetKey: String,
    @ColumnInfo(name = "saved_session_id") val savedSessionId: String,
    @ColumnInfo(name = "write_epoch") val writeEpoch: Long,
    val revision: Long,
    val payload: String,
)

data class TranscriptRows(
    val state: ChatTranscriptState?,
    val rows: List<ChatMessageEntity>,
)
