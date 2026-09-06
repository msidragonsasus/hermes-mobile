package com.m57.hermescontrol.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.RequestReferenceCodec
import java.security.MessageDigest

/** One identity per original request/question. Extras contain no prompt/command or answer. */
object AttentionNotificationRoute {
    const val ACTION = "com.m57.hermescontrol.ACTION_OPEN_ATTENTION"
    const val EXTRA_REF = "attention_request_ref"

    fun tag(ref: RequestRef): String {
        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(RequestReferenceCodec.encode(ref).toByteArray(Charsets.UTF_8))
        return "attention:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun contentIntent(
        context: Context,
        ref: RequestRef,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent(context, ref),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal fun intent(
        context: Context,
        ref: RequestRef,
    ): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION
            data = Uri.parse("hermes-attention://request/${tag(ref).removePrefix("attention:")}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_REF, RequestReferenceCodec.encode(ref))
        }

    internal fun notification(
        context: Context,
        ref: RequestRef,
        text: String,
    ): Notification =
        NotificationCompat
            .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, ref))
            .build()
}
