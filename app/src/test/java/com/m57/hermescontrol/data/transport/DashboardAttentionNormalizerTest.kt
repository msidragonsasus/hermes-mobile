package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.domain.hermes.AttentionKind
import com.m57.hermescontrol.domain.hermes.AttentionSignal
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardAttentionNormalizerTest {
    private val scope = ConnectionScope("server", "connection", "profile", "process", 1)
    private val approval =
        WsEvent.ApprovalRequest(
            "synthetic action",
            "Summary",
            listOf("pattern"),
            "runtime",
            "request",
        )

    @Test
    fun mapsRealEventFieldsWithoutInventingRiskPermissionOrExpiry() {
        val item = dashboardAttentionItems(scope, approval).single()
        assertEquals(AttentionKind.APPROVAL, item.kind)
        assertEquals("Summary", item.title)
        assertEquals("synthetic action", item.details?.action)
        assertEquals(listOf("pattern"), item.details?.patterns)
        assertTrue(item.details!!.permissions.isEmpty())
        assertNull(item.details.impact)
        assertNull(item.details.target)
        assertNull(item.expiresAt)
        assertTrue(dashboardAttentionItems(scope, approval.copy(requestId = null)).isEmpty())
    }

    @Test
    fun decodedApprovalChoicesAreSanitizedBeforeExposure() {
        val offered = listOf("once", "session", "always", "deny", "unknown")
        val cases =
            listOf(
                approval.copy(choices = null) to listOf("once", "deny"),
                approval.copy(choices = emptyList()) to emptyList(),
                approval.copy(choices = listOf("unknown")) to emptyList(),
                approval.copy(choices = offered, allowPermanent = false) to listOf("once", "session", "deny"),
                approval.copy(choices = offered, smartDenied = true) to listOf("once", "deny"),
                approval.copy(choices = listOf("session", "always"), smartDenied = true) to emptyList(),
            )
        for ((event, expected) in cases) {
            assertEquals(expected, dashboardAttentionItems(scope, event).single().details?.approvalChoices)
        }
    }

    @Test
    fun batchMapsEveryExactQuestionAndRejectsDuplicateOrBlankIdentifiers() {
        val event =
            WsEvent.ClarifyRequest(
                null,
                null,
                "request",
                "runtime",
                questions =
                    listOf(
                        WsEvent.ClarifyQuestion("one", "First?", listOf("A")),
                        WsEvent.ClarifyQuestion("two", "Second?", listOf("B"), true),
                    ),
            )
        val items = dashboardAttentionItems(scope, event)
        assertEquals(listOf("one", "two"), items.map { it.ref.questionId })
        assertEquals(listOf("B"), items.last().details?.options)
        assertTrue(items.last().details!!.multiSelect)
        assertTrue(
            dashboardAttentionItems(
                scope,
                event.copy(questions = listOf(event.questions.first(), event.questions.first())),
            ).isEmpty(),
        )
        assertTrue(dashboardAttentionItems(scope, event.copy(questionId = " ")).isEmpty())
    }

    @Test
    fun gapPrecedesRequestAndExplicitTruncationIsNeverDropped() =
        runTest {
            val signals =
                normalizeDashboardAttention(
                    flowOf(
                        ScopedWsEvent(scope, 1, approval),
                        ScopedWsEvent(scope, 3, approval.copy(requestId = "second")),
                        ScopedWsEvent(scope, 4, null, gap = true),
                    ),
                ).toList()
            assertEquals(
                listOf(
                    AttentionSignal.Requested::class,
                    AttentionSignal.Gap::class,
                    AttentionSignal.Requested::class,
                    AttentionSignal.Gap::class,
                ),
                signals.map { it::class },
            )
        }
}
