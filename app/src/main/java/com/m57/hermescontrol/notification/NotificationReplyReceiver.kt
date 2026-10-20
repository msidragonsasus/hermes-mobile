package com.m57.hermescontrol.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.local.OutgoingDraftRepository
import com.m57.hermescontrol.data.transport.DashboardRpcTransport
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

open class NotificationReplyReceiver : BroadcastReceiver() {
    companion object {
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_CONNECTION_SCOPE = "extra_connection_scope"

        internal fun replyIntent(
            context: Context,
            scope: ConnectionScope,
            savedSessionId: String,
        ): Intent =
            Intent(context, NotificationReplyReceiver::class.java).apply {
                action = "${context.packageName}.ACTION_NOTIFICATION_REPLY"
                setPackage(context.packageName)
                putExtra(EXTRA_SESSION_ID, savedSessionId)
                putExtra(EXTRA_CONNECTION_SCOPE, Json.encodeToString(scope))
                // Extras do not participate in PendingIntent identity; encode the fixed destination in data too.
                data =
                    android.net.Uri
                        .Builder()
                        .scheme("hermes-reply")
                        .authority(context.packageName)
                        .appendPath(Json.encodeToString(scope))
                        .appendPath(savedSessionId)
                        .build()
            }

        internal fun decodeScope(value: String?): ConnectionScope? =
            if (value == null || value.length > 2048) {
                null
            } else {
                runCatching {
                    Json.decodeFromString<ConnectionScope>(value)
                }.getOrNull()
            }
    }

    private val replyScope by lazy { CoroutineScope(SupervisorJob() + replyDispatcher()) }

    internal open fun replyDispatcher(): CoroutineDispatcher = Dispatchers.IO

    internal open fun goAsyncCompat(): PendingResult = goAsync()

    internal open fun replySender(context: Context): NotificationReplySender {
        val db = HermesDatabase.get(context)
        return NotificationReplySender(
            OutgoingDraftRepository(db.outgoingDraftDao()),
            DashboardRpcTransport(),
            db.chatMessageDao(),
        )
    }

    internal open fun buildReplyNotification(
        context: Context,
        outcome: ReplyOutcome,
        replyText: String,
    ): Notification {
        val textId =
            when (outcome) {
                ReplyOutcome.CONFIRMED -> R.string.notif_reply_confirmed
                ReplyOutcome.DRAFT -> R.string.notif_reply_draft
                ReplyOutcome.UNKNOWN -> R.string.notif_reply_unknown
                ReplyOutcome.STORAGE_UNAVAILABLE -> R.string.notif_reply_storage_unavailable
                ReplyOutcome.TARGET_UNAVAILABLE -> R.string.notif_reply_target_unavailable
            }
        // Open Chat's draft list without pretending the currently selected profile owns the old session.
        val open =
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_CHAT_FROM_NOTIFICATION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingOpen =
            PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(textId))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(textId) + "\n\n" + replyText))
            .setRemoteInputHistory(arrayOf(replyText))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingOpen)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT_REPLY)?.toString()
        val savedSessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (replyText.isNullOrBlank() || savedSessionId.isNullOrBlank()) return
        val originalScope = decodeScope(intent.getStringExtra(EXTRA_CONNECTION_SCOPE))
        val pendingResult = goAsyncCompat()
        replyScope.launch {
            try {
                val outcome =
                    if (originalScope == null) {
                        ReplyOutcome.TARGET_UNAVAILABLE
                    } else {
                        try {
                            replySender(context).send(originalScope, savedSessionId, replyText)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            ReplyOutcome.STORAGE_UNAVAILABLE
                        }
                    }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(
                    ChatNotificationService.PENDING_NOTIFICATION_ID,
                    buildReplyNotification(context, outcome, replyText),
                )
                if (outcome == ReplyOutcome.CONFIRMED && !NotificationHelper.isAppInForeground()) {
                    NotificationHelper.start(context)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                android.util.Log.w("NotificationReply", "Reply status notification unavailable")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
