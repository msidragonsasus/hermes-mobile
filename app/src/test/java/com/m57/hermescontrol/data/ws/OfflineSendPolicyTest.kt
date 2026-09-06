package com.m57.hermescontrol.data.ws

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSendPolicyTest {
    @Test
    fun `writes and unknown methods cannot be replayed`() {
        for (method in listOf(
            WsMethods.PROMPT_SUBMIT,
            WsMethods.SESSION_REDIRECT,
            WsMethods.SESSION_CREATE,
            WsMethods.APPROVAL_RESPOND,
            WsMethods.APPROVAL_RECEIVED,
            WsMethods.CLARIFY_RESPOND,
            WsMethods.SUDO_RESPOND,
            WsMethods.SECRET_RESPOND,
            WsMethods.SESSION_DELETE,
            WsMethods.IMAGE_ATTACH_BYTES,
            WsMethods.FILE_ATTACH,
            WsMethods.CONFIG_SET,
            "future.read",
            "future.get",
            "future.write",
            "",
        )) {
            assertFalse(method, OfflineSendPolicy.mayQueue(method))
        }
    }

    @Test
    fun `known reads retain reconnect behavior`() {
        assertTrue(OfflineSendPolicy.mayQueue(WsMethods.SESSION_HISTORY))
        assertTrue(OfflineSendPolicy.mayQueue(WsMethods.APPROVAL_PENDING))
        assertTrue(OfflineSendPolicy.mayQueue(WsMethods.SESSION_EVENTS_SINCE))
    }
}
