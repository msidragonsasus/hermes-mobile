package com.m57.hermescontrol.openclaw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class OpenClawRecoveryTest {
    @Test
    fun reconnectBackoffIsBoundedAndSuccessfulHandshakeResetsIt() =
        runBlocking {
            val waits = mutableListOf<Long>()
            var attempts = 0
            maintainClawConnection(
                connect = { ready ->
                    attempts++
                    if (attempts == 9) ready()
                    if (attempts < 10) throw ClawFailure("DISCONNECTED")
                },
                onFailure = { _, retry -> assertTrue(retry) },
                wait = { waits += it },
            )
            assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L, 1000L), waits)
        }

    @Test
    fun pairingAndAuthFailuresStopWithoutRetrying() =
        runBlocking {
            for (code in listOf("PAIRING_REQUIRED", "AUTH_TOKEN_MISMATCH", "MISSING_SCOPE", "PROTOCOL_ERROR")) {
                var attempts = 0
                maintainClawConnection(
                    connect = {
                        attempts++
                        throw ClawFailure(code)
                    },
                    onFailure = { _, retry -> assertFalse(retry) },
                    wait = { fail("Authorization failures must not retry") },
                )
                assertEquals(1, attempts)
            }
        }

    @Test
    fun handshakeTimeoutRetriesButOwnerCancellationDoesNot() =
        runBlocking {
            var attempts = 0
            maintainClawConnection(
                connect = { if (++attempts == 1) withTimeout(1) { delay(100) } },
                onFailure = { _, retry -> assertTrue(retry) },
                wait = {},
            )
            assertEquals(2, attempts)
            try {
                maintainClawConnection(
                    connect = { throw CancellationException("owner retired") },
                    onFailure = { _, _ -> fail("Cancellation must propagate") },
                )
                fail("Cancellation was swallowed")
            } catch (_: CancellationException) {
            }
        }

    private fun frame(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun deltasAreSilentAndTerminalUpdatesHaveOneIdentityPerRun() {
        fun event(state: String) =
            frame("""{"event":"chat","payload":{"sessionKey":"agent:main:a","runId":"r","state":"$state"}}""")
        assertNull(clawNotice(event("delta")))
        assertEquals("run:r", clawNotice(event("final"))?.identity)
        assertEquals(clawNotice(event("error"))?.identity, clawNotice(event("final"))?.identity)
        assertFalse(clawNotice(event("error"))!!.text.contains("secret"))
        assertNull(clawNotice(frame("""{"event":"exec.approval.requested","payload":{"id":"id"}}""")))
        assertEquals(
            "agent:main:a",
            clawNotice(
                frame(
                    """{"event":"exec.approval.requested","payload":{"id":"id","request":{"sessionKey":"agent:main:a"}}}""",
                ),
            )?.session,
        )
    }

    @Test
    fun matchingPromptAndAssistantStopReasonCannotRetireAPendingRun() {
        val pending = ClawPendingRun("run", "do work")

        fun history(
            user: String,
            reason: String,
        ) = frame(
            """{"messages":[{"role":"user","content":"$user"},{"role":"assistant","content":"result","stopReason":"$reason"}]}""",
        )
        assertNull(clawRecoveredNotice("a", pending, history("other", "stop")))
        assertNull(clawRecoveredNotice("a", pending, history("do work", "toolUse")))
        assertNull(clawRecoveredNotice("a", pending, history("do work", "")))
        assertNull(clawRecoveredNotice("a", pending.copy(previousMatches = 1), history("do work", "stop")))
        assertNull(clawRecoveredNotice("a", pending, history("do work", "stop")))
        assertNull(clawRecoveredNotice("a", pending, history("do work", "end_turn")))
        assertEquals(mapOf("a" to pending), clawPendingRuns(encodeClawPendingRuns(mapOf("a" to pending))))
    }

    @Test
    fun durableHistoryTerminalRequiresTheExactRunAndANativeReceipt() {
        val pending = ClawPendingRun("run", "same prompt", previousMatches = 9)
        for (receipt in listOf("\"id\":\"assistant-final\"", "\"seq\":2")) {
            val history =
                frame(
                    """{"messages":[{"role":"assistant","content":"result","__openclaw":
                    {"runId":"run","runTerminal":true,$receipt}}]}""",
                )
            assertEquals("run:run", clawRecoveredNotice("a", pending, history)?.identity)
            assertEquals("result", clawRecoveredNotice("a", pending, history)?.text)
            assertNull(clawRecoveredNotice("a", pending.copy(id = "another-run"), history))
        }
        for (metadata in listOf(
            """{"runId":"run","runTerminal":true}""",
            """{"runId":"run","runTerminal":"true","id":"id"}""",
            """{"runId":"run","runTerminal":false,"id":"id"}""",
            """{"runId":"run","runTerminal":true,"seq":"2"}""",
            """{"runId":"run","runTerminal":true,"seq":0}""",
            """{"runId":"run","runTerminal":true,"id":true}""",
            """{"runId":"run","runTerminal":true,"id":"id","importedFrom":"cli"}""",
            """{"runId":"run","runTerminal":true,"id":"id","cliSessionId":"external"}""",
            """{"runId":"run","runTerminal":true,"id":"id","externalId":"external"}""",
            """{"runTerminal":true,"id":"id"}""",
        )) {
            assertNull(
                clawRecoveredNotice(
                    "a",
                    pending,
                    frame("""{"messages":[{"role":"assistant","stopReason":"stop","__openclaw":$metadata}]}"""),
                ),
            )
        }
        assertNull(clawRecoveredNotice("a", pending, frame("""{"messages":[]}""")))
        assertNotNull(
            clawRecoveredNotice(
                "a",
                pending,
                frame(
                    """{"messages":[{"role":"assistant","__openclaw":{"runId":"run","runTerminal":true,
                    "id":"id","cliSessionId":"enriched","transcriptPosition":{"source":"native","rawSeq":0}}}]}""",
                ),
            ),
        )
    }

    @Test
    fun explicitActivityForThePendingRunOverridesAHistoryTerminal() {
        val pending = ClawPendingRun("run", "task")
        val terminal = """{"role":"assistant","__openclaw":{"runId":"run","runTerminal":true,"id":"id"}}"""
        for (activity in listOf(
            """"inFlightRun":{"runId":"run"}""",
            """"sessionInfo":{"key":"a","activeRunIds":["run"]}""",
            """"sessionInfo":{"key":"another-session"}""",
        )) {
            assertNull(clawRecoveredNotice("a", pending, frame("""{"messages":[$terminal],$activity}""")))
        }
        assertNotNull(
            clawRecoveredNotice(
                "a",
                pending,
                frame("""{"messages":[$terminal],"sessionInfo":{"key":"a","activeRunIds":["another-run"]}}"""),
            ),
        )
    }

    @Test
    fun waitRecoveryRequiresAnExactTerminalSnapshotRatherThanAnExpiredWait() {
        val pending = ClawPendingRun("run", "task")
        for (status in listOf("ok", "error")) {
            assertEquals(
                "run:run",
                clawWaitRecoveredNotice("a", pending, frame("""{"runId":"run","status":"$status","endedAt":2}"""))
                    ?.identity,
            )
        }
        assertNotNull(
            clawWaitRecoveredNotice(
                "a",
                pending,
                frame("""{"runId":"run","status":"timeout","endedAt":2,"stopReason":"timeout"}"""),
            ),
        )
        for (result in listOf(
            """{"runId":"other","status":"ok","endedAt":2}""",
            """{"runId":"run","status":"timeout"}""",
            """{"runId":"run","status":"timeout","endedAt":2}""",
            """{"runId":"run","status":"pending","endedAt":2}""",
            """{"runId":"run","status":"ok"}""",
            """{"runId":"run","status":"ok","endedAt":"2"}""",
            """{"runId":"run","status":"ok","endedAt":-1}""",
            """{"runId":"run","status":"error","endedAt":2,"pendingError":true}""",
            """{"runId":"run","status":"ok","endedAt":2,"yielded":true}""",
            """{"runId":"run","status":"error","endedAt":2,"timeoutPhase":"gateway_draining"}""",
        )) {
            assertNull(result, clawWaitRecoveredNotice("a", pending, frame(result)))
        }
    }

    @Test
    fun titleRequestsOnlyUseFreeRouterWithZeroPriceCeilingsAndBoundedInput() {
        val request = clawTitleRequest("x".repeat(5000))
        assertEquals("openrouter/free", request.string("model"))
        assertEquals("false", request.obj("reasoning")?.string("enabled"))
        assertEquals("1024", request.string("max_tokens"))
        val prices = request.obj("provider")!!.obj("max_price")!!
        assertEquals("0", prices.string("prompt"))
        assertEquals("0", prices.string("completion"))
        assertFalse(request.containsKey("models"))
        assertFalse(request.containsKey("tools"))
        assertEquals(1200, (request.array("messages")[1] as JsonObject).string("content")!!.length)
        assertEquals("名前", clawTitle("「名前」\n余計な説明"))
        assertEquals(40, clawTitle("長".repeat(100)).length)
    }
}
