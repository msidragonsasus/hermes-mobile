package com.m57.hermescontrol.career

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerBrowserFillStatusTest {
    @Test
    fun blockedAmbiguousPendingAndUnsupportedHaveDistinctSafeMessages() {
        val reasons = listOf("pending", "ambiguous", "unsafe_action", "blocked", "not_retained", "unsupported")
        val messages = reasons.map { browserFillStatus("\"$it\"") }
        assertEquals(reasons.size, messages.distinct().size)
        assertTrue(browserFillStatus("\"occupied\"").contains("入力済みの値を保持"))
        assertTrue(browserFillStatus("\"filled\"").contains("ログインボタンを押して"))
        assertFalse(messages.any { it.contains("パスワードを取得") })
    }
}
