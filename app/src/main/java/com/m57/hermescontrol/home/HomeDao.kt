package com.m57.hermescontrol.home

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "home_items", primaryKeys = ["scope", "provider", "id"])
data class HomeRow(
    val scope: String,
    val provider: String,
    val id: String,
    val payload: String,
)

@Entity(tableName = "home_reads", primaryKeys = ["scope", "key"])
data class HomeRead(
    val scope: String,
    val key: String,
    val version: String,
)

@Entity(tableName = "home_providers", primaryKeys = ["scope", "provider"])
data class HomeProviderRow(
    val scope: String,
    val provider: String,
    val enabled: Boolean = true,
    val metadata: String = "",
)

@Dao
interface HomeDao {
    @Query("SELECT * FROM home_items WHERE scope = :scope")
    fun observe(scope: String): Flow<List<HomeRow>>

    @Query("SELECT * FROM home_reads WHERE scope = :scope")
    fun reads(scope: String): Flow<List<HomeRead>>

    @Query("SELECT * FROM home_providers WHERE scope = :scope")
    fun providers(scope: String): Flow<List<HomeProviderRow>>

    @Query("SELECT * FROM home_providers WHERE scope = :scope AND provider = :provider")
    suspend fun provider(
        scope: String,
        provider: String,
    ): HomeProviderRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: HomeRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRead(row: HomeRead)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProvider(row: HomeProviderRow)

    @Query("DELETE FROM home_items WHERE scope = :scope AND provider = :provider")
    suspend fun clearProvider(
        scope: String,
        provider: String,
    )

    @Query("SELECT * FROM home_items WHERE scope = :scope AND provider = :provider AND id = :id")
    suspend fun item(
        scope: String,
        provider: String,
        id: String,
    ): HomeRow?

    @Transaction
    suspend fun replace(
        scope: String,
        rows: List<HomeRow>,
        metadata: String,
    ) {
        clearProvider(scope, "career")
        rows.forEach { save(it) }
        saveProvider((provider(scope, "career") ?: HomeProviderRow(scope, "career")).copy(metadata = metadata))
    }

    @Transaction
    suspend fun cacheToday(
        scope: String,
        metadata: String,
    ) {
        saveProvider((provider(scope, "today_home") ?: HomeProviderRow(scope, "today_home")).copy(metadata = metadata))
    }

    @Transaction
    suspend fun setEnabled(
        scope: String,
        provider: String,
        enabled: Boolean,
    ) {
        saveProvider((provider(scope, provider) ?: HomeProviderRow(scope, provider)).copy(enabled = enabled))
    }
}
