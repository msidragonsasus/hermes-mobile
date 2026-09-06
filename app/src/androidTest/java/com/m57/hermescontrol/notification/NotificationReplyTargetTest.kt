package com.m57.hermescontrol.notification

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationReplyTargetTest {
    @Test
    fun notificationIntentIdentityIncludesScopeAndSavedSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = ConnectionScope("synthetic-server", "connection-A", null, "synthetic-process", 1)
        val first = NotificationReplyReceiver.replyIntent(context, scope, "saved-A")
        assertEquals(context.packageName, first.`package`)
        assertEquals(NotificationReplyReceiver::class.java.name, first.component?.className)
        assertEquals("saved-A", first.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID))
        assertEquals(
            scope,
            NotificationReplyReceiver.decodeScope(
                first.getStringExtra(NotificationReplyReceiver.EXTRA_CONNECTION_SCOPE),
            ),
        )
        assertTrue(first.filterEquals(NotificationReplyReceiver.replyIntent(context, scope, "saved-A")))
        for (other in listOf(
            scope.copy(serverProfileId = "B"),
            scope.copy(generation = 2),
            scope.copy(connectionProfileId = "B"),
        )) {
            assertFalse(first.filterEquals(NotificationReplyReceiver.replyIntent(context, other, "saved-A")))
        }
        assertFalse(first.filterEquals(NotificationReplyReceiver.replyIntent(context, scope, "saved-B")))
    }

    @Test
    fun unknownNoticeShowsInputPrivatelyWithoutAReplyAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notification =
            NotificationReplyReceiver().buildReplyNotification(
                context,
                ReplyOutcome.UNKNOWN,
                "Synthetic reply to recover",
            )
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(
            context.getString(R.string.notif_reply_unknown),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertTrue(
            notification.extras
                .getCharSequence(
                    Notification.EXTRA_BIG_TEXT,
                ).toString()
                .contains("Synthetic reply to recover"),
        )
        assertTrue(notification.actions.isNullOrEmpty())
    }
}
