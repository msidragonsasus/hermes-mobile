package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.ScopedRpcSubmission
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.PromptAcceptance
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
