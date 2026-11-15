package com.m57.hermescontrol.worker

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal object WorkerInboxRepository {
    private const val PAGE_LIMIT = 50
    private val tokyoZone = ZoneId.of("Asia/Tokyo")

    fun cachedTodayResults(
        scope: String = AuthManager.uiPreferenceScope(),
        today: LocalDate = LocalDate.now(tokyoZone),
    ): List<InboxItem> =
        WorkerPreferences
            .readTodayCache(scope = scope, today = today)
            .items
            .filter { item ->
                (item.isResult || item.isMemoryDiff) &&
                    WorkerInboxFormatters.isCreatedOnTokyoDate(item.created_at, today)
            }.sortedByDescending { it.seq }

    suspend fun refreshTodayResults(
        scope: String = AuthManager.uiPreferenceScope(),
        today: LocalDate = LocalDate.now(tokyoZone),
    ): List<InboxItem> {
        val existing = WorkerPreferences.readTodayCache(scope = scope, today = today)
        val since = if (existing.items.isNotEmpty() && existing.sinceSeq > 0L) existing.sinceSeq else 0L
        val response =
            try {
                val resp = ApiClient.hermesApi.getWorkerInbox(since = since, limit = PAGE_LIMIT)
                if (!resp.isSuccessful) return cachedTodayResults(scope, today)
                resp.body() ?: return cachedTodayResults(scope, today)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return cachedTodayResults(scope, today)
            }

        val todayNew =
            response.items.filter { item ->
                (item.isResult || item.isMemoryDiff) &&
                    WorkerInboxFormatters.isCreatedOnTokyoDate(item.created_at, today)
            }
        val mergedById = LinkedHashMap<String, InboxItem>()
        existing.items.forEach { mergedById[it.id] = it }
        todayNew.forEach { mergedById[it.id] = it }
        val sorted = mergedById.values.sortedByDescending { it.seq }
        val latestSeq = maxOf(existing.sinceSeq, response.latest_seq, response.items.maxOfOrNull { it.seq } ?: 0L)
        WorkerPreferences.writeTodayCache(
            cache = TodayInboxCache(date = today.toString(), sinceSeq = latestSeq, items = sorted),
            scope = scope,
        )
        return sorted
    }

    suspend fun ackItem(
        scope: String,
        itemId: String,
        today: LocalDate = LocalDate.now(tokyoZone),
    ): List<InboxItem> {
        val existing = WorkerPreferences.readTodayCache(scope = scope, today = today)
        val target = existing.items.firstOrNull { it.id == itemId }
        if (target?.acked_at?.isNotBlank() == true) {
            return existing.items
        }
        val acked =
            try {
                val resp = ApiClient.hermesApi.ackWorkerInbox(itemId)
                resp.isSuccessful && resp.body()?.ok == true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        if (!acked) return existing.items

        val nowIso = Instant.now().toString()
        val updatedItems =
            existing.items.map { item ->
                if (item.id == itemId && item.acked_at.isNullOrBlank()) {
                    item.copy(acked_at = nowIso)
                } else {
                    item
                }
            }
        WorkerPreferences.writeTodayCache(
            cache = existing.copy(items = updatedItems),
            scope = scope,
        )
        return updatedItems
    }
}
