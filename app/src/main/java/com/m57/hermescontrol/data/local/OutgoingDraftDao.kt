package com.m57.hermescontrol.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutgoingDraftDao {
    @Query("SELECT * FROM outgoing_drafts WHERE target_key = :targetKey ORDER BY created_at ASC")
    fun observe(targetKey: String): Flow<List<OutgoingDraftEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(draft: OutgoingDraftEntity)

    @Query("DELETE FROM outgoing_drafts WHERE id = :id AND target_key = :targetKey")
    suspend fun delete(
        id: String,
        targetKey: String,
    )
}
