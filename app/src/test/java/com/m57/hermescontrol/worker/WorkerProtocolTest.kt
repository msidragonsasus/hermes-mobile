package com.m57.hermescontrol.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WorkerProtocolTest {
    @Test
    fun parseTickle_acceptsValidV1Payload() {
        val parsed =
            WorkerPushProtocol.parseTickle(
                mapOf(
                    "t" to "hermes.tickle",
                    "v" to "1",
                    "seq" to "42",
                ),
            )

        assertNotNull(parsed)
        assertEquals("hermes.tickle", parsed?.type)
        assertEquals("1", parsed?.version)
        assertEquals(42L, parsed?.seq)
    }

    @Test
    fun parseTickle_defaultsMissingVersionAndRejectsUnknownTypesOrVersions() {
        val noVersion = WorkerPushProtocol.parseTickle(mapOf("t" to "hermes.tickle", "seq" to "bad"))
        assertEquals(TicklePayload("hermes.tickle", "1", null), noVersion)

        assertNull(WorkerPushProtocol.parseTickle(mapOf("t" to "other.event", "v" to "1", "seq" to "1")))
        assertNull(WorkerPushProtocol.parseTickle(mapOf("t" to "hermes.tickle", "v" to "2", "seq" to "1")))
        assertNull(WorkerPushProtocol.parseTickle(emptyMap()))
    }

    @Test
    fun decodeInboxResponse_parsesAllKindsAndIgnoresUnknownFields() {
        val raw =
            """
            {
              "latest_seq": 15,
              "extra_top_level": true,
              "items": [
                {
                  "seq": 12,
                  "id": "inb-1",
                  "kind": "result",
                  "title": "朝のレポート",
                  "body": "完了しました",
                  "created_at": "2026-11-15T00:10:00+09:00",
                  "acked_at": null,
                  "unknown_field": 123
                },
                {
                  "seq": 13,
                  "id": "inb-2",
                  "kind": "approval",
                  "title": "コマンド実行の確認",
                  "body": "内容を確認してください",
                  "ref": {
                    "job_id": "job-1",
                    "execution_id": "exec-1",
                    "escrow_id": "esc-99",
                    "digest": "sha256:abc123",
                    "deny_only": true
                  },
                  "created_at": "2026-11-15T00:12:00+09:00"
                },
                {
                  "seq": 14,
                  "id": "inb-3",
                  "kind": "diagnostic",
                  "title": "確認が必要",
                  "body": "一部失敗"
                },
                {
                  "seq": 15,
                  "id": "inb-4",
                  "kind": "memory_diff",
                  "title": "メモリの変更",
                  "body": "--- a\n+++ b\n+new line"
                }
              ]
            }
            """.trimIndent()

        val decoded = WorkerJson.decodeInboxResponse(raw)
        assertEquals(15L, decoded.latest_seq)
        assertEquals(4, decoded.items.size)
        assertTrue(decoded.items[0].isResult)
        assertTrue(decoded.items[1].isApproval)
        assertEquals("esc-99", decoded.items[1].ref?.escrow_id)
        assertEquals("sha256:abc123", decoded.items[1].ref?.digest)
        assertEquals(true, decoded.items[1].ref?.deny_only)
        assertTrue(decoded.items[2].isDiagnostic)
        assertTrue(decoded.items[3].isMemoryDiff)
    }

    @Test
    fun decisionRequestBuilder_passesDigestAndEnforcesDenyOnly() {
        val approveNormal =
            WorkerDecisionRequests.build(
                decision = EscrowDecision.APPROVE,
                digest = "sha256:deadbeef",
                denyOnly = false,
            )
        assertEquals(EscrowDecisionRequest("approve", "sha256:deadbeef"), approveNormal)

        val denyNormal =
            WorkerDecisionRequests.build(
                decision = EscrowDecision.DENY,
                digest = "sha256:deadbeef",
                denyOnly = false,
            )
        assertEquals(EscrowDecisionRequest("deny", "sha256:deadbeef"), denyNormal)

        val approveDenyOnly =
            WorkerDecisionRequests.build(
                decision = EscrowDecision.APPROVE,
                digest = "sha256:deadbeef",
                denyOnly = true,
            )
        assertNull(approveDenyOnly)

        val denyDenyOnly =
            WorkerDecisionRequests.build(
                decision = EscrowDecision.DENY,
                digest = "sha256:deadbeef",
                denyOnly = true,
            )
        assertEquals(EscrowDecisionRequest("deny", "sha256:deadbeef"), denyDenyOnly)

        assertNull(WorkerDecisionRequests.build(EscrowDecision.APPROVE, digest = "", denyOnly = false))
        assertNull(WorkerDecisionRequests.build(EscrowDecision.DENY, digest = null, denyOnly = false))
    }

    @Test
    fun wsParsers_extractJobsAndActiveSubagentsOnly() {
        val cronRaw =
            mapOf(
                "jobs" to
                    listOf(
                        mapOf(
                            "id" to "j1",
                            "name" to "morning-brief",
                            "enabled" to true,
                            "schedule" to mapOf("display" to "毎朝 8:00"),
                            "next_run_at" to "2026-11-16T08:00:00+09:00",
                            "last_run_status" to "ok",
                        ),
                        mapOf(
                            "id" to "j2",
                            "name" to "paused-task",
                            "state" to "paused",
                            "schedule_display" to "毎週月曜",
                        ),
                    ),
            )
        val jobs = WorkerWsParsers.parseCronManageJobs(cronRaw)
        assertEquals(2, jobs.size)
        assertTrue(jobs[0].enabled)
        assertEquals("毎朝 8:00", jobs[0].scheduleText)
        assertFalse(jobs[1].enabled)
        assertEquals("毎週月曜", jobs[1].scheduleText)

        val subagentRaw =
            mapOf(
                "subagents" to
                    listOf(
                        mapOf("subagent_id" to "sub-1", "status" to "running", "goal" to "Summarize notes"),
                        mapOf("subagent_id" to "sub-2", "status" to "completed", "goal" to "Done"),
                        mapOf("subagent_id" to "sub-3", "status" to "queued", "goal" to "Wait"),
                    ),
            )
        val subagents = WorkerWsParsers.parseSubagents(subagentRaw)
        assertEquals(listOf("sub-1", "sub-3"), subagents.map { it.subagentId })
    }

    @Test
    fun tokyoDateFilter_respectsJstMidnightBoundary() {
        val nov15 = LocalDate.of(2026, 11, 15)
        assertTrue(WorkerInboxFormatters.isCreatedOnTokyoDate("2026-11-14T15:00:00Z", nov15))
        assertFalse(WorkerInboxFormatters.isCreatedOnTokyoDate("2026-11-14T14:59:59Z", nov15))
        assertFalse(WorkerInboxFormatters.isCreatedOnTokyoDate("not-a-timestamp", nov15))
    }
}
