package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.ClarificationLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarifyCardStateContractTest {
    private fun request(status: AttentionStatus) =
        ClarifyUi(
            text = "どの資料を使いますか？",
            questionId = "exact-qid",
            responseEnabled = true,
            questionStates = mapOf("exact-qid" to status),
        )

    @Test
    fun onlyPendingExactQuestionCanRespond() {
        AttentionStatus.entries.forEach { status ->
            assertEquals(status == AttentionStatus.PENDING, request(status).canRespond("exact-qid"))
            assertFalse(request(status).canRespond("other-qid"))
        }
    }

    @Test
    fun nullSkipAndAnsweredLocksRemainReadOnlyEvenIfPendingReplays() {
        listOf(null, "既に送った回答").forEach { answer ->
            val ui =
                request(AttentionStatus.PENDING).copy(
                    questionLocks =
                        mapOf("exact-qid" to ClarificationLock(answer)),
                )
            assertFalse(ui.canRespond("exact-qid"))
            assertTrue(ui.questionLocks.containsKey("exact-qid"))
            assertEquals(answer, ui.questionLocks.getValue("exact-qid").answer)
        }
    }

    @Test
    fun disconnectedPendingAndUnknownNeverEnableResponse() {
        assertFalse(request(AttentionStatus.PENDING).copy(responseEnabled = false).canRespond("exact-qid"))
        assertFalse(request(AttentionStatus.UNKNOWN).canRespond("exact-qid"))
    }
}
