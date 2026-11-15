package com.m57.hermescontrol.worker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.notification.ChatNotificationService

object WorkerNotificationRoute {
    const val ACTION_OPEN_DESTINATION = "com.m57.hermescontrol.ACTION_OPEN_WORKER_DESTINATION"
    const val EXTRA_DESTINATION = "worker_destination"
    const val DEST_TODAY = "today"
    const val DEST_WORK = "work"

    fun destinationFor(item: InboxItem): String =
        when (item.kind) {
            InboxItem.KIND_DIAGNOSTIC,
            InboxItem.KIND_APPROVAL,
            -> DEST_WORK

            else -> DEST_TODAY
        }

    fun contentIntent(
        context: Context,
        destination: String,
        identityKey: String,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_DESTINATION
                data = "hermes-worker://$destination/$identityKey".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_DESTINATION, destination)
            }
        return PendingIntent.getActivity(
            context,
            ("$destination:$identityKey").hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

internal object WorkerNotifications {
    const val NOTIFICATION_ID_SYNC = 4101
    private const val NOTIFICATION_ID_ITEM = 4102
    private const val NOTIFICATION_ID_FALLBACK = 4103
    private const val NOTIFICATION_ID_OUTCOME = 4104
    internal const val APPROVAL_INLINE_MAX_CHARS = 500

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val serviceChannel =
            NotificationChannel(
                ChatNotificationService.SERVICE_CHANNEL_ID,
                context.getString(R.string.notif_channel_service_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.notif_channel_service_desc)
            }

        val chatChannel =
            NotificationChannel(
                ChatNotificationService.CHAT_CHANNEL_ID,
                context.getString(R.string.notif_channel_chat_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_chat_desc)
            }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(chatChannel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    fun buildSyncNotification(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat
            .Builder(context, ChatNotificationService.SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.worker_notif_syncing))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    fun buildItemNotification(
        context: Context,
        item: InboxItem,
    ): Notification {
        ensureChannels(context)
        val destination = WorkerNotificationRoute.destinationFor(item)
        val title = item.title.trim().ifBlank { context.getString(R.string.notif_title) }
        val preview =
            WorkerInboxFormatters
                .notificationPreview(item.body)
                .ifBlank { context.getString(R.string.worker_notif_generic_result) }

        val builder =
            NotificationCompat
                .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(preview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(WorkerNotificationRoute.contentIntent(context, destination, item.id))

        if (item.isApproval) {
            val escrowId = item.ref?.escrow_id?.takeIf { it.isNotBlank() }
            val digest = item.ref?.digest?.takeIf { it.isNotBlank() }
            // Approval may only apply to what the user saw: offer it from the notification only when
            // the whole body fits and is shown; otherwise the tap opens the in-app card.
            val fullBody = item.body.trim()
            val showsWholeBody = fullBody.isNotEmpty() && fullBody.length <= APPROVAL_INLINE_MAX_CHARS
            if (showsWholeBody) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(fullBody))
            }
            val denyOnly = item.ref?.deny_only == true || !showsWholeBody
            if (escrowId != null && digest != null) {
                val denyPending =
                    EscrowDecisionReceiver.decisionPendingIntent(
                        context = context,
                        escrowId = escrowId,
                        digest = digest,
                        denyOnly = denyOnly,
                        decision = EscrowDecision.DENY,
                        inboxId = item.id,
                    )
                builder.addAction(
                    NotificationCompat.Action
                        .Builder(
                            R.drawable.ic_notification,
                            context.getString(R.string.worker_action_deny),
                            denyPending,
                        ).build(),
                )
                if (!denyOnly) {
                    val approvePending =
                        EscrowDecisionReceiver.decisionPendingIntent(
                            context = context,
                            escrowId = escrowId,
                            digest = digest,
                            denyOnly = false,
                            decision = EscrowDecision.APPROVE,
                            inboxId = item.id,
                        )
                    builder.addAction(
                        NotificationCompat.Action
                            .Builder(
                                R.drawable.ic_notification,
                                context.getString(R.string.worker_action_approve),
                                approvePending,
                            ).setAuthenticationRequired(true)
                            .build(),
                    )
                }
            }
        }

        return builder.build()
    }

    fun postInboxItems(
        context: Context,
        items: List<InboxItem>,
    ) {
        if (items.isEmpty() || !canPostNotifications(context)) return
        ensureChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        items.forEach { item ->
            manager.notify(tagForItem(item.id), NOTIFICATION_ID_ITEM, buildItemNotification(context, item))
        }
    }

    fun postGenericFallback(context: Context) {
        if (!canPostNotifications(context)) return
        ensureChannels(context)
        val message = context.getString(R.string.worker_notif_generic_result)
        val notification =
            NotificationCompat
                .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_title))
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(
                    WorkerNotificationRoute.contentIntent(
                        context,
                        WorkerNotificationRoute.DEST_TODAY,
                        "generic_fallback",
                    ),
                ).build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify("worker:fallback", NOTIFICATION_ID_FALLBACK, notification)
    }

    fun postDecisionOutcome(
        context: Context,
        escrowId: String,
        inboxId: String?,
        message: String,
    ) {
        ensureChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!inboxId.isNullOrBlank()) {
            manager.cancel(tagForItem(inboxId), NOTIFICATION_ID_ITEM)
        }
        if (!canPostNotifications(context)) return
        val notification =
            NotificationCompat
                .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_title))
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(
                    WorkerNotificationRoute.contentIntent(
                        context,
                        WorkerNotificationRoute.DEST_WORK,
                        escrowId,
                    ),
                ).build()
        manager.notify("worker:decision:$escrowId", NOTIFICATION_ID_OUTCOME, notification)
    }

    private fun tagForItem(itemId: String): String = "worker:$itemId"
}
