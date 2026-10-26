package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.transport.HermesSubmissionTransport
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichAnswerSubmissionTransportTest {
    private val scope = ConnectionScope("fixture", null, null, "process", 1)
    private val session = SessionRef("runtime", "saved")

    @Test
    fun normalSubmissionAddsDisplayHintsOnceAndPreservesUnknownAndQueueFlags() =
        runTest {
            val delegate = mockk<HermesSubmissionTransport>()
            val wire = slot<String>()
            coEvery { delegate.submitPrompt(scope, session, capture(wire), false, true) } returns
                SubmissionResult.Unknown
            val result = RichAnswerSubmissionTransport(delegate).submitPrompt(scope, session, "図で説明して", false, true)
            assertEquals(SubmissionResult.Unknown, result)
            assertTrue(wire.captured.contains("hermes-ui-v2"))
            assertEquals("図で説明して", RichAnswerPrompt.visibleText(wire.captured))
            coVerify(exactly = 1) { delegate.submitPrompt(scope, session, any(), false, true) }
        }

    @Test
    fun steerAdvertisesMobileImagesWithoutChangingTheSessionOrResending() =
        runTest {
            val delegate = mockk<HermesSubmissionTransport>()
            val wire = slot<String>()
            coEvery { delegate.submitPrompt(scope, session, capture(wire), true, false) } returns
                SubmissionResult.NotSent
            val result = RichAnswerSubmissionTransport(delegate).submitPrompt(scope, session, "スクショ見せて", true, false)
            assertEquals(SubmissionResult.NotSent, result)
            assertEquals("スクショ見せて", RichAnswerPrompt.visibleText(wire.captured))
            assertTrue(wire.captured.contains("TUI cannot display images"))
            coVerify(exactly = 1) { delegate.submitPrompt(scope, session, any(), true, false) }
        }
}
