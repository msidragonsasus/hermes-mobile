package com.m57.hermescontrol.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import java.time.LocalDate
import java.time.ZoneId

class InboxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            WorkerNotifications.NOTIFICATION_ID_SYNC,
            WorkerNotifications.buildSyncNotification(applicationContext),
        )

    override suspend fun doWork(): Result {
        if (!WorkerAuthBootstrap.ensureReady(applicationContext) || !WorkerAuthBootstrap.isAuthenticated()) {
            return Result.success()
        }

        val scope = AuthManager.uiPreferenceScope()
        val since = WorkerPreferences.readLastInboxSeq(scope)
        val fetched =
            runCatching {
                val resp = ApiClient.hermesApi.getWorkerInbox(since = since, limit = DEFAULT_LIMIT)
                check(resp.isSuccessful) { "Inbox HTTP ${resp.code()}" }
                resp.body() ?: error("Empty inbox response")
            }

        val inbox =
            fetched.getOrElse {
                WorkerNotifications.postGenericFallback(applicationContext)
                return Result.success()
            }

        val maxSeq = maxOf(inbox.latest_seq, inbox.items.maxOfOrNull { it.seq } ?: 0L)
        WorkerPreferences.advanceInboxSeq(maxSeq, scope)
        mergeTodayCache(scope = scope, items = inbox.items, latestSeq = maxSeq)

        val unacked = inbox.items.filter { it.acked_at.isNullOrBlank() }
        if (unacked.isNotEmpty()) {
            WorkerNotifications.postInboxItems(applicationContext, unacked)
        }
        return Result.success()
    }

    private fun mergeTodayCache(
        scope: String,
        items: List<InboxItem>,
        latestSeq: Long,
    ) {
        val tokyoToday = LocalDate.now(ZoneId.of("Asia/Tokyo"))
        val existing = WorkerPreferences.readTodayCache(scope = scope, today = tokyoToday)
        val todayNew =
            items.filter { item ->
                (item.isResult || item.isMemoryDiff) &&
                    WorkerInboxFormatters.isCreatedOnTokyoDate(item.created_at, tokyoToday)
            }
        if (todayNew.isEmpty() && latestSeq <= existing.sinceSeq) return
        val mergedById = LinkedHashMap<String, InboxItem>()
        existing.items.forEach { mergedById[it.id] = it }
        todayNew.forEach { mergedById[it.id] = it }
        val sorted = mergedById.values.sortedByDescending { it.seq }
        WorkerPreferences.writeTodayCache(
            cache =
                TodayInboxCache(
                    date = tokyoToday.toString(),
                    sinceSeq = maxOf(existing.sinceSeq, latestSeq),
                    items = sorted,
                ),
            scope = scope,
        )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "hermes_worker_inbox_sync"
        private const val DEFAULT_LIMIT = 50

        fun enqueueExpedited(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<InboxSyncWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            WorkManager
                .getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
                )
        }
    }
}
