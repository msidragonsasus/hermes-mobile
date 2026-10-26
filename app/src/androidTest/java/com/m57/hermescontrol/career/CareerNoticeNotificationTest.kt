package com.m57.hermescontrol.career

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CareerNoticeNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notice =
        CareerNotice(
            1,
            "synthetic",
            "a",
            kind = "source_changed",
            title = "ESの提出依頼",
            created_at = "",
            eligible = true,
            company_name = "架空企業",
            category = "action",
            label = "提出依頼",
            quote = "ESを提出してください",
            deadline_text = "提出期限 2026年10月1日",
            source_ref = CareerNoticeSource("mypage", "evidence", "stable"),
        )

    @Test
    fun privateNotificationHasOriginalDetailsButPublicVersionDoesNot() {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                data =
                    Uri.parse("hermes-career://notice/synthetic")
            }
        val pending =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        try {
            val notification = buildCareerNotification(context, listOf(notice), pending)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("架空企業｜提出依頼", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            val text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            assertTrue(text.contains(notice.title))
            assertTrue(text.contains(notice.quote))
            assertTrue(text.contains(notice.deadline_text))
            assertFalse(
                notification.publicVersion.extras
                    .toString()
                    .contains("架空企業"),
            )
            assertFalse(
                notification.publicVersion.extras
                    .toString()
                    .contains("ES"),
            )
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertTrue(notification.contentIntent.isImmutable)
        } finally {
            pending.cancel()
        }
    }

    @Test
    fun encryptedPolicyAndGroupRouteSurviveReopenWithoutCrossingProfiles() =
        runBlocking {
            val scope = "notice-test:${UUID.randomUUID()}"
            val local = CareerLocalStore(context, scope)
            assertFalse(local.noticePolicyReady())
            local.initializeNoticePolicy(123)
            local.saveNoticeDeliveredCursor("a", 122)
            local.saveNoticeGroup("a", listOf(notice))
            val token = local.notificationGroupRoute(listOf(notice))
            val reopened = CareerLocalStore(context, scope)
            assertTrue(reopened.noticePolicyReady())
            assertEquals(123L, reopened.noticeCursor())
            assertEquals(122L, reopened.noticeDeliveredCursor("a"))
            assertEquals(listOf(notice), reopened.noticeGroup("a"))
            assertEquals("synthetic", reopened.resolveNotificationDestination(token)?.text("notice_id"))
            assertEquals(null, CareerLocalStore(context, "other:$scope").resolveNotificationDestination(token))
            val second = notice.copy(id = "second", seq = 2)
            assertEquals(token, reopened.notificationGroupRoute(listOf(notice, second)))
            val target =
                careerNoticeDestination(
                    reopened.resolveNotificationDestination(token)!!,
                    scope,
                ) as CareerNoticeDetailKey
            assertEquals(2, target.notices.size)
        }
}
