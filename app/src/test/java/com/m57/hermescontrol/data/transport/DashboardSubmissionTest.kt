package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.ScopedRpcSubmission
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.PromptAcceptance
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DashboardSubmissionTest {
    private val scope = ConnectionScope("synthetic", "connection", "profile", "process", 3)
    private val session = SessionRef("runtime", "saved")
    private val transport = DashboardRpcTransport()

    @Before
    fun setup() {
        mockkObject(HermesWsClient)
        every { HermesWsClient.matchesCurrentScope(scope) } returns true
    }

    @After
    fun cleanup() = unmockkAll()

    private fun reply(
        body: String,
        accepted: Boolean = true,
    ) {
        every { HermesWsClient.writeScoped(scope, any(), any(), any()) } returns
            ScopedRpcSubmission("rpc", accepted, CompletableDeferred(Json.parseToJsonElement(body)))
    }

    @Test
    fun `approval allows only exact once or deny and requires one resolved request`() =
        runTest {
            val ref = RequestRef(scope, session, "approval")
            for (choice in listOf("session", "always", "approve", "unexpected")) {
                assertEquals(SubmissionResult.NotSent, transport.respondApproval(ref, choice))
            }
            verify(exactly = 0) { HermesWsClient.writeScoped(any(), any(), any(), any()) }
            reply("""{"resolved":1}""")
            assertEquals(SubmissionResult.Confirmed("rpc", true), transport.respondApproval(ref, "once"))
            verify(exactly = 1) {
                HermesWsClient.writeScoped(
                    scope,
                    WsMethods.APPROVAL_RESPOND,
                    mapOf("session_id" to "runtime", "request_id" to "approval", "choice" to "once", "all" to false),
                    any(),
                )
            }
            for (count in listOf(0, 2, -1)) {
                reply("""{"resolved":$count}""")
                assertEquals(SubmissionResult.Confirmed("rpc", false), transport.respondApproval(ref, "deny"))
            }
            for (body in listOf("{}", """{"resolved":"1"}""", """{"resolved":true}""")) {
                reply(body)
                assertEquals(SubmissionResult.Unknown, transport.respondApproval(ref, "once"))
            }
        }

    @Test
    fun `clarification pins question id and expired status never confirms`() =
        runTest {
            val ref = RequestRef(scope, session, "batch", "q2")
            reply("""{"status":"ok","remaining":["q1"]}""")
            assertEquals(
                SubmissionResult.Confirmed("rpc", true),
                transport.respondClarification(ref, "synthetic answer"),
            )
            verify(exactly = 1) {
                HermesWsClient.writeScoped(
                    scope,
                    WsMethods.CLARIFY_RESPOND,
                    mapOf(
                        "session_id" to "runtime",
                        "request_id" to "batch",
                        "clarify_id" to "batch",
                        "question_id" to "q2",
                        "answer" to "synthetic answer",
                    ),
                    any(),
                )
            }
            reply("""{"status":"expired"}""")
            assertEquals(SubmissionResult.Unknown, transport.respondClarification(ref, "synthetic answer"))
            reply("""{"status":"ok"}""")
            every { HermesWsClient.matchesCurrentScope(scope) } returns false
            assertEquals(SubmissionResult.Unknown, transport.respondClarification(ref, "synthetic answer"))
        }

    @Test
    fun `approval received is only a render acknowledgement`() =
        runTest {
            val ref = RequestRef(scope, session, "approval")
            reply("""{"acknowledged":true}""")
            assertEquals(SubmissionResult.Confirmed("rpc", true), transport.acknowledgeApproval(ref))
            verify(exactly = 0) { HermesWsClient.writeScoped(scope, WsMethods.APPROVAL_RESPOND, any(), any()) }
            reply("""{"acknowledged":false}""")
            assertEquals(SubmissionResult.Unknown, transport.acknowledgeApproval(ref))
        }

    @Test
    fun `only verified prompt statuses confirm receipt`() =
        runTest {
            for ((status, value) in listOf(
                "streaming" to PromptAcceptance.STREAMING,
                "queued" to PromptAcceptance.QUEUED,
                "redirected" to PromptAcceptance.REDIRECTED,
                "steered" to PromptAcceptance.STEERED,
            )) {
                reply("""{"status":"$status"}""")
                assertEquals(SubmissionResult.Confirmed("rpc", value), transport.submitPrompt(scope, session, "text"))
            }
            for (body in listOf("{}", """{"status":"rejected"}""", """{"status":true}""")) {
                reply(body)
                assertEquals(SubmissionResult.Unknown, transport.submitPrompt(scope, session, "text"))
            }
        }

    @Test
    fun `lost acknowledgement and scope change never trigger a second write`() =
        runTest {
            every { HermesWsClient.writeScoped(scope, any(), any(), any()) } returns
                ScopedRpcSubmission(
                    "rpc",
                    true,
                    CompletableDeferred<Any?>().apply {
                        completeExceptionally(
                            HermesWsClient.HermesRpcException("synthetic", 4010),
                        )
                    },
                )
            assertEquals(SubmissionResult.Unknown, transport.submitPrompt(scope, session, "text", redirect = true))
            verify(exactly = 1) { HermesWsClient.writeScoped(scope, WsMethods.SESSION_REDIRECT, any(), any()) }
            verify(exactly = 0) { HermesWsClient.writeScoped(scope, WsMethods.PROMPT_SUBMIT, any(), any()) }
            reply("""{"status":"streaming"}""")
            every { HermesWsClient.matchesCurrentScope(scope) } returns false
            assertEquals(SubmissionResult.Unknown, transport.submitPrompt(scope, session, "text"))
            reply("{}", accepted = false)
            assertEquals(SubmissionResult.NotSent, transport.submitPrompt(scope, session, "text"))
        }

    @Test
    fun `attachment confirmation requires exact success and file reference`() =
        runTest {
            val image = Attachment("content://synthetic", "image.png", "image/png")
            reply("""{"attached":true}""")
            assertEquals(
                SubmissionResult.Confirmed("rpc", null),
                transport.attach(scope, session, image, "data:synthetic"),
            )
            reply("""{"attached":"true"}""")
            assertEquals(SubmissionResult.Unknown, transport.attach(scope, session, image, "data:synthetic"))
            val file = image.copy(name = "file.txt", mimeType = "text/plain")
            reply("""{"attached":true,"ref_text":"@file:synthetic"}""")
            assertEquals(
                SubmissionResult.Confirmed("rpc", "@file:synthetic"),
                transport.attach(scope, session, file, "data:synthetic"),
            )
            reply("""{"ref_text":"@file:synthetic"}""")
            assertEquals(SubmissionResult.Unknown, transport.attach(scope, session, file, "data:synthetic"))
        }
}
