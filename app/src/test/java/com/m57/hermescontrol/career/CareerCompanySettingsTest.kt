package com.m57.hermescontrol.career

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerCompanySettingsTest {
    @Test fun unreadCombinesGmailAndAccountScopedHermesReadState() {
        val mail =
            CareerMailItem(
                "message",
                "thread",
                "sender@example.com",
                "採用",
                "件名",
                "抜粋",
                "本文",
                "",
                "",
                true,
                account_id = "a",
            )
        assertTrue(mail.isUnread(emptySet()))
        assertFalse(mail.isUnread(setOf("career:source:mail:a:message")))
        assertTrue(mail.isUnread(setOf("career:source:mail:b:message")))
        assertFalse(mail.copy(gmail_unread = false).isUnread(emptySet()))
    }

    @Test fun legacyApplicationCaptionChangesWithoutChangingSavedData() {
        val data =
            buildJsonObject {
                put("title", "旧Shukatsuからの応募・選考")
                put("source_status", "Interview")
            }
        val entity = CareerEntity("applications", "old-id", 3, data)
        assertEquals("応募・選考", entity.title)
        assertEquals("旧Shukatsuからの応募・選考", entity.data.text("title"))
        assertEquals("Interview", entity.data.text("source_status"))
    }

    @Test fun loginUrlsAllowHttpsPathsButRejectCredentialsAndExecutableSchemes() {
        assertTrue(validCompanyUrl("https://example.com/login?year=2028"))
        listOf(
            "",
            "https://",
            "javascript:alert(1)",
            "http://example.com",
            "https://user:pass@example.com",
            "https://exa mple.com",
        ).forEach { assertFalse(it, validCompanyUrl(it)) }
    }
}
