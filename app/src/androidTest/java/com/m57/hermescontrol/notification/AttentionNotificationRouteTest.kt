package com.m57.hermescontrol.notification

import android.app.Notification
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.RequestReferenceCodec
import com.m57.hermescontrol.domain.hermes.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionNotificationRouteTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ref =
        RequestRef(
            ConnectionScope("synthetic-server", "connection", "profile", "process", 1),
            SessionRef("runtime"),
            "approval-id",
            "q2",
        )

    @Test
    fun originalQuestionSurvivesIntentAndDuplicateIdentityDoesNotCollideWithOtherRequests() {
        val intent = AttentionNotificationRoute.intent(context, ref)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(ref, RequestReferenceCodec.decode(intent.getStringExtra(AttentionNotificationRoute.EXTRA_REF)))
        assertEquals(setOf(AttentionNotificationRoute.EXTRA_REF), intent.extras!!.keySet())
        assertTrue(intent.filterEquals(AttentionNotificationRoute.intent(context, ref)))
        assertFalse(intent.data.toString().contains("synthetic-server"))
        for (other in listOf(
            ref.copy(questionId = "q1"),
            ref.copy(requestId = "another"),
            ref.copy(scope = ref.scope.copy(generation = 2)),
            ref.copy(session = SessionRef("another")),
        )) {
            assertFalse(intent.filterEquals(AttentionNotificationRoute.intent(context, other)))
            assertFalse(AttentionNotificationRoute.tag(ref) == AttentionNotificationRoute.tag(other))
        }
        val pending = AttentionNotificationRoute.contentIntent(context, ref)
        assertEquals(pending, AttentionNotificationRoute.contentIntent(context, ref))
        assertTrue(pending.isImmutable)
        pending.cancel()
    }

    @Test
    fun notificationIsPrivateAndCannotApproveOrAnswerInline() {
        val notification = AttentionNotificationRoute.notification(context, ref, "Review required")
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("Review required", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.actions.isNullOrEmpty())
        assertTrue(notification.contentIntent.isImmutable)
        notification.contentIntent.cancel()
    }
}
