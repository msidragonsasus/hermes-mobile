package com.m57.hermescontrol.data.local

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Body and attachments remain inside the existing SQLCipher database. */
@Entity(tableName = "outgoing_drafts", indices = [Index(value = ["target_key", "created_at"])])
data class OutgoingDraftEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "target_key") val targetKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val payload: String,
)
