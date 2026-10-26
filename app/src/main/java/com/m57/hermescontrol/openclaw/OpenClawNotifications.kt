package com.m57.hermescontrol.openclaw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.StorageStartup
import com.m57.hermescontrol.data.local.StorageStartupState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

internal data class ClawNotice(
    val session: String,
    val identity: String,
    val heading: String,
    val text: String,
)

internal fun clawNotice(frame: JsonObject): ClawNotice? {
    val payload = frame.obj("payload") ?: return null
    if (frame.string("event") == "exec.approval.requested") {
        val session = payload.obj("request")?.string("sessionKey") ?: return null
        val id = payload.string("id") ?: return null
        return ClawNotice(session, "approval:$id", "確認が必要です", "会話を開いて実行内容を確認してください。")
    }
    if (frame.string("event") != "chat") return null
    val session = payload.string("sessionKey") ?: return null
    val run = payload.string("runId") ?: return null
    val status = payload.string("state") ?: return null
    val heading =
        when (status) {
            "final" -> "応答が届きました"
            "error" -> "処理を続けられませんでした"
            "aborted" -> "処理を停止しました"
            else -> return null
        }
    val preview = if (status == "final") messageText(payload.obj("message")).take(180) else "会話を開いて状況を確認してください。"
    return ClawNotice(session, "run:$run", heading, preview.ifBlank { "会話を開いて結果を確認してください。" })
}

internal object OpenClawNotifications {
    const val ACTION = "com.m57.hermescontrol.OPEN_OPENCLAW_NOTICE"
    const val EXTRA = "openclaw_notice_token"
    const val CHANNEL = "openclaw_updates"
    const val SERVICE_CHANNEL = "openclaw_connection"
    const val SERVICE_ID = 7_001

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "OpenClawの応答・確認待ち", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "OpenClawのバックグラウンド接続", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun background(context: Context) {
        val vm = OpenClawRuntime.active ?: return
        if (!vm.uiState.value.configured || !vm.uiState.value.notificationsEnabled) return
        context.startForegroundService(Intent(context, OpenClawNotificationService::class.java))
    }

    fun show(
        context: Context,
        store: OpenClawStore,
        notice: ClawNotice,
        title: String,
    ) {
        val delivery = store.sessionName("notice", "${notice.session}|${notice.identity}")
        if (store.read(delivery) != null) return
        if (OpenClawRuntime.foreground && OpenClawRuntime.visibleSession == notice.session) {
            store.write(delivery, "seen")
            return
        }
        if (store.read("notifications") == "false") return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        channels(context)
        val route = store.sessionName("route-session", notice.session)
        val token = store.read(route) ?: UUID.randomUUID().toString()
        store.write(route, token)
        store.write(
            "notice-route:$token",
            obj("url" to value(store.read("url")), "session" to value(notice.session)).toString(),
        )
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION
                data = android.net.Uri.parse("hermes-openclaw://notice/$token")
                putExtra(EXTRA, token)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pending =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val public =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("OpenClaw")
                .setContentText("新しい更新があります")
                .build()
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${notice.heading} · $title")
                .setContentText(notice.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setGroup("openclaw")
                .build()
        try {
            // The tag includes the full scoped session identity; Java hash collisions cannot replace another thread.
            manager.notify(store.sessionName("notification", notice.session), 0, notification)
            store.write(delivery, "delivered")
        } catch (_: SecurityException) {
            // Android notification permission can be revoked while a run is finishing.
        }
    }

    fun resolve(
        context: Context,
        token: String,
    ): String? {
        if (!Regex("[a-f0-9-]{36}").matches(token)) return null
        val store = OpenClawStore(context, OpenClawViewModel.ownerScope())
        val route = store.read("notice-route:$token") ?: return null
        val data = runCatching { Json.parseToJsonElement(route) as JsonObject }.getOrNull() ?: return null
        if (data.string("url") != store.read("url")) return null
        return data.string("session")?.takeIf { it.startsWith("agent:") && it.length <= 512 }
    }
}

/** Remote messaging listener, shared with the UI; it does not run the agent on the phone. */
class OpenClawNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        OpenClawNotifications.channels(this)
        val notification =
            NotificationCompat
                .Builder(this, OpenClawNotifications.SERVICE_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("OpenClaw")
                .setContentText("応答・確認待ちを受信しています")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setSilent(true)
                .build()
        startForeground(OpenClawNotifications.SERVICE_ID, notification)
        scope.launch {
            StorageStartup.state.first { it == StorageStartupState.Ready }
            val vm = OpenClawRuntime.model(application)
            vm.ensureConnected()
            combine(vm.uiState, OpenClawRuntime.foregroundState) { state, foreground ->
                !state.configured || !state.notificationsEnabled || foreground
            }.collectLatest { stop -> if (stop) stopSelf() }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (OpenClawRuntime.foreground) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
