package com.m57.hermescontrol.career

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.StorageStartup
import com.m57.hermescontrol.data.local.StorageStartupState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

object CareerNotifications {
    const val ACTION = "com.m57.hermescontrol.ACTION_OPEN_CAREER_NOTICE"
    const val EXTRA_TOKEN = "career_notice_token"
    const val CHANNEL = "career_source_updates"
    private const val WORK = "career-notices"

    fun schedule(
        context: Context,
        enabled: Boolean,
    ) {
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<CareerNoticeWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Only sanitized short excerpts reach notifications; credentials and full bodies stay private. */
class CareerNoticeWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val ready = withTimeoutOrNull(10000) { StorageStartup.state.first { it != StorageStartupState.Loading } }
        if (ready != StorageStartupState.Ready) return Result.retry()
        if (!AuthManager.isGatedMode()) return Result.success()
        val scope = careerScope()
        val local = CareerLocalStore(applicationContext, scope)
        try {
            if (!local.enabled() || !local.notificationsEnabled()) return Result.success()
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return Result.success()
            }
            val manager = NotificationManagerCompat.from(applicationContext)
            manager.createNotificationChannel(
                NotificationChannel(CareerNotifications.CHANNEL, "就活の新着案内", NotificationManager.IMPORTANCE_DEFAULT),
            )
            if (!manager.areNotificationsEnabled() ||
                manager.getNotificationChannel(CareerNotifications.CHANNEL)?.importance ==
                NotificationManager.IMPORTANCE_NONE
            ) {
                return Result.success()
            }
            val repository = CareerCompanyRepository(scope)
            val platform = applicationContext.getSystemService(NotificationManager::class.java)
            if (!local.noticePolicyReady()) {
                // v2 starts at the current tail: never replay the old notification backlog.
                platform.activeNotifications.filter { it.tag?.startsWith("career:$scope:") == true }.forEach {
                    manager.cancel(it.tag, it.id)
                }
                var baseline = repository.notices(local.noticeCursor(), baseline = true)
                while (baseline.more) {
                    val next = repository.notices(baseline.cursor, baseline = true)
                    check(next.cursor > baseline.cursor)
                    baseline = next
                }
                if (scope != careerScope() || !AuthManager.isGatedMode()) return Result.success()
                local.initializeNoticePolicy(baseline.cursor)
                return Result.success()
            }
            drainCareerNoticePages(
                readCursor = { local.noticeCursor() },
                fetch = { repository.notices(it) },
                publish = publish@{ pageNotices ->
                    for ((company, scanned) in pageNotices
                        .filter {
                            it.source_ref?.key != null || it.eligible
                        }.groupBy { it.company_id }) {
                        val delivered = local.noticeDeliveredCursor(company)
                        val changes = scanned.filter { it.seq > delivered }
                        if (changes.isEmpty()) continue
                        if (scope != careerScope() || !AuthManager.isGatedMode() || !local.notificationsEnabled()) {
                            return@publish false
                        }
                        val tag = "career:$scope:company:$company"
                        val active = platform.activeNotifications.any { it.tag == tag }
                        val incoming =
                            (
                                changes.filter { !it.eligible } +
                                    careerNoticeGroups(
                                        changes,
                                    )[company].orEmpty()
                            ).sortedBy { it.seq }
                        val notices =
                            mergeCareerNotices(
                                if (active) local.noticeGroup(company) else emptyList(),
                                incoming,
                            )
                        if (notices.isEmpty()) {
                            manager.cancel(tag, 0)
                            local.saveNoticeGroup(company, emptyList())
                            local.saveNoticeDeliveredCursor(company, changes.maxOf { it.seq })
                            continue
                        }
                        val token = local.notificationGroupRoute(notices)
                        val intent =
                            Intent(applicationContext, MainActivity::class.java).apply {
                                action = CareerNotifications.ACTION
                                data = Uri.parse("hermes-career://notice/$token")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra(CareerNotifications.EXTRA_TOKEN, token)
                            }
                        val pending =
                            PendingIntent.getActivity(
                                applicationContext,
                                0,
                                intent,
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            )
                        val notification = buildCareerNotification(applicationContext, notices, pending)
                        local.saveNoticeGroup(company, notices)
                        if (scope != careerScope() || !AuthManager.isGatedMode() || !local.notificationsEnabled()) {
                            return@publish false
                        }
                        try {
                            manager.notify(tag, 0, notification)
                        } catch (_: SecurityException) {
                            return@publish false
                        }
                        local.saveNoticeDeliveredCursor(company, changes.maxOf { it.seq })
                    }
                    scope == careerScope() && AuthManager.isGatedMode() && local.notificationsEnabled()
                },
                commit = { local.saveNoticeCursor(it) },
            )
            return Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // No failure body, credential or private evidence enters work output or logs.
            return Result.retry()
        }
    }
}

internal fun buildCareerNotification(
    context: Context,
    notices: List<CareerNotice>,
    pending: PendingIntent,
): android.app.Notification {
    val public =
        NotificationCompat
            .Builder(context, CareerNotifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hermes 就活")
            .setContentText("対応が必要な案内があります。")
            .build()
    val notification =
        NotificationCompat
            .Builder(context, CareerNotifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(careerNoticeTitle(notices))
            .setContentText(notices.last().title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(careerNoticeBody(notices)))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
    return notification
}
