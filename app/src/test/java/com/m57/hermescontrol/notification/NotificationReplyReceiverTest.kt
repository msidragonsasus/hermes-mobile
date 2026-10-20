package com.m57.hermescontrol.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.transport.HermesSubmissionTransport
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.DraftState
import com.m57.hermescontrol.domain.hermes.PromptAcceptance
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import com.m57.hermescontrol.ui.chat.fakes.FakeOutgoingDraftStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationReplyReceiverTest {
    private val dispatcher = StandardTestDispatcher()
    private val origin = ConnectionScope("synthetic-server", "connection-A", "profile-A", "process-A", 1)
    private lateinit var context: Context
    private lateinit var intent: Intent
    private lateinit var pending: BroadcastReceiver.PendingResult
    private lateinit var manager: NotificationManager
    private lateinit var dao: ChatMessageDao
    private lateinit var drafts: FakeOutgoingDraftStore
    private lateinit var transport: ReplyTransport
    private lateinit var receiver: NotificationReplyReceiver
    private val cached = mutableListOf<ChatMessageEntity>()
    private val notices = mutableListOf<Pair<ReplyOutcome, String>>()

    @Before
    fun setUp() {
        mockkStatic(RemoteInput::class)
        mockkObject(NotificationHelper)
        every { NotificationHelper.isAppInForeground() } returns true
        context = mockk(relaxed = true)
        intent = mockk(relaxed = true)
        pending = mockk(relaxed = true)
        manager = mockk(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns manager
        drafts = FakeOutgoingDraftStore()
        transport = ReplyTransport(origin)
        dao = mockk(relaxed = true)
        coEvery { dao.sessionExists(any()) } returns true
        coEvery { dao.upsert(any()) } answers {
            cached += firstArg<ChatMessageEntity>()
            Unit
        }
        receiver =
            object : NotificationReplyReceiver() {
                override fun replyDispatcher() = dispatcher

                override fun goAsyncCompat() = pending

                override fun replySender(context: Context) = NotificationReplySender(drafts, transport, dao)

                override fun buildReplyNotification(
                    context: Context,
                    outcome: ReplyOutcome,
                    replyText: String,
                ): Notification {
                    notices += outcome to replyText
                    return mockk()
                }
            }
        givenReply("saved-A", "Hello")
    }

    @After
    fun tearDown() {
        ActiveSessionHolder.clear()
        unmockkAll()
    }

    @Test
    fun confirmedReplyUsesResolvedRuntimeAndCachesSavedConversation() =
        runTest(dispatcher) {
            ActiveSessionHolder.set("stale-runtime", "saved-A")
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(listOf("saved-A"), transport.resumes)
            val request = transport.prompts.single()
            assertEquals(origin, request.first)
            assertEquals(SessionRef("resolved-runtime", "saved-A"), request.second)
            assertEquals("Hello", request.third)
            assertEquals("saved-A", ActiveSessionHolder.resolveStoredSessionId(origin, "resolved-runtime"))
            assertEquals("stale-runtime", ActiveSessionHolder.activeSessionId.value)
            val message = cached.single()
            assertEquals("saved-A", message.sessionId)
            assertEquals("Hello", message.content)
            assertEquals("USER", message.role)
            assertTrue(message.id.isNotBlank())
            assertTrue(message.timestamp > 0)
            assertEquals(ReplyOutcome.CONFIRMED, notices.single().first)
            assertTrue(drafts.drafts.value.isEmpty())
            verify { pending.finish() }
            verify { manager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, any()) }
        }

    @Test
    fun noSuccessOrCacheBeforePromptAckAndUnknownIsSavedBeforeWire() =
        runTest(dispatcher) {
            val ack = CompletableDeferred<Unit>()
            transport.beforePrompt = {
                assertEquals(
                    DraftState.UNKNOWN,
                    drafts.drafts.value
                        .single()
                        .state,
                )
                ack.await()
            }
            receiver.onReceive(context, intent)
            runCurrent()
            assertTrue(cached.isEmpty())
            assertTrue(notices.isEmpty())
            verify(exactly = 0) { pending.finish() }
            ack.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, cached.size)
            assertEquals(ReplyOutcome.CONFIRMED, notices.single().first)
        }

    @Test
    fun offlineReplyIsDraftAndReconnectionDoesNotSend() =
        runTest(dispatcher) {
            transport.ready = false
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(
                DraftState.DRAFT,
                drafts.drafts.value
                    .single()
                    .state,
            )
            assertEquals(ReplyOutcome.DRAFT, notices.single().first)
            transport.ready = true
            advanceUntilIdle()
            assertTrue(transport.resumes.isEmpty())
            assertTrue(transport.prompts.isEmpty())
            assertTrue(cached.isEmpty())
            verify { pending.finish() }
        }

    @Test
    fun oldNotificationCannotWriteToDifferentProfile() =
        runTest(dispatcher) {
            transport.scope = origin.copy(serverProfileId = "profile-B")
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(
                origin,
                drafts.drafts.value
                    .single()
                    .scope,
            )
            assertEquals(ReplyOutcome.DRAFT, notices.single().first)
            assertTrue(transport.resumes.isEmpty())
            assertTrue(transport.prompts.isEmpty())
        }

    @Test
    fun explicitReplyAfterProcessChangeResolvesSameSavedConversation() =
        runTest(dispatcher) {
            transport.scope = origin.copy(processId = "new-process", generation = 7)
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(transport.scope, transport.prompts.single().first)
            assertEquals(
                "saved-A",
                transport.prompts
                    .single()
                    .second.savedId,
            )
            assertEquals(ReplyOutcome.CONFIRMED, notices.single().first)
        }

    @Test
    fun profileSwitchDuringResumeStopsBeforePrompt() =
        runTest(dispatcher) {
            transport.beforeResume = { transport.scope = origin.copy(serverProfileId = "profile-B") }
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(
                DraftState.DRAFT,
                drafts.drafts.value
                    .single()
                    .state,
            )
            assertTrue(transport.prompts.isEmpty())
            assertEquals(ReplyOutcome.DRAFT, notices.single().first)
        }

    @Test
    fun unconfirmedResumeLeavesDraftAndNeverSubmits() =
        runTest(dispatcher) {
            transport.resumeResult = SubmissionResult.Unknown
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(1, transport.resumes.size)
            assertTrue(transport.prompts.isEmpty())
            assertEquals(
                DraftState.DRAFT,
                drafts.drafts.value
                    .single()
                    .state,
            )
            verify { pending.finish() }
        }

    @Test
    fun manualSecondReplyAfterUnconfirmedResumeIsANewIntent() =
        runTest(dispatcher) {
            transport.resumeResult = SubmissionResult.Unknown
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            transport.resumeResult = SubmissionResult.Confirmed("resume", SessionRef("resolved-runtime", "saved-A"))
            givenReply("saved-A", "Second explicit input")
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(2, transport.resumes.size)
            assertEquals("Second explicit input", transport.prompts.single().third)
            assertEquals(
                "Hello",
                drafts.drafts.value
                    .single()
                    .text,
            )
        }

    @Test
    fun lostPromptAckRemainsUnknownWithoutSuccessNoticeOrCache() =
        runTest(dispatcher) {
            transport.promptResult = SubmissionResult.Unknown
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(
                DraftState.UNKNOWN,
                drafts.drafts.value
                    .single()
                    .state,
            )
            assertEquals(ReplyOutcome.UNKNOWN, notices.single().first)
            assertTrue(cached.isEmpty())
            assertEquals(1, transport.prompts.size)
            verify { pending.finish() }
        }

    @Test
    fun definiteRejectionReturnsToDraftWithoutCache() =
        runTest(dispatcher) {
            transport.promptResult = SubmissionResult.NotSent
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(
                DraftState.DRAFT,
                drafts.drafts.value
                    .single()
                    .state,
            )
            assertEquals(ReplyOutcome.DRAFT, notices.single().first)
            assertTrue(cached.isEmpty())
        }

    @Test
    fun promptTimeoutFinishesBroadcastAndKeepsUnknown() =
        runTest(dispatcher) {
            transport.beforePrompt = { CompletableDeferred<Unit>().await() }
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(ReplyOutcome.UNKNOWN, notices.single().first)
            assertEquals(
                DraftState.UNKNOWN,
                drafts.drafts.value
                    .single()
                    .state,
            )
            assertTrue(cached.isEmpty())
            verify { pending.finish() }
        }

    @Test
    fun cacheFailureAfterAckKeepsUnknownAndFinishesBroadcast() =
        runTest(dispatcher) {
            coEvery { dao.upsert(any()) } throws IllegalStateException("Synthetic private failure")
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(ReplyOutcome.UNKNOWN, notices.single().first)
            assertEquals(
                DraftState.UNKNOWN,
                drafts.drafts.value
                    .single()
                    .state,
            )
            verify { pending.finish() }
        }

    @Test
    fun cacheTimeoutFinishesBroadcastAndRetainsDurableRecord() =
        runTest(dispatcher) {
            coEvery { dao.upsert(any()) } coAnswers { CompletableDeferred<Unit>().await() }
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertEquals(ReplyOutcome.UNKNOWN, notices.single().first)
            assertEquals(
                DraftState.UNKNOWN,
                drafts.drafts.value
                    .single()
                    .state,
            )
            verify { pending.finish() }
        }

    @Test
    fun draftStorageFailureNeverWritesAndPreservesInputInNotice() =
        runTest(dispatcher) {
            drafts.failSave = true
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertTrue(transport.resumes.isEmpty())
            assertTrue(transport.prompts.isEmpty())
            assertEquals(ReplyOutcome.STORAGE_UNAVAILABLE to "Hello", notices.single())
            verify { pending.finish() }
        }

    @Test
    fun missingLocalConversationKeepsDraftWithoutRemoteWrite() =
        runTest(dispatcher) {
            coEvery { dao.sessionExists(any()) } returns false
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertTrue(transport.resumes.isEmpty())
            assertTrue(transport.prompts.isEmpty())
            assertEquals(
                DraftState.DRAFT,
                drafts.drafts.value
                    .single()
                    .state,
            )
            assertTrue(cached.isEmpty())
            verify { pending.finish() }
        }

    @Test
    fun missingNotificationScopeNeverGuessesCurrentTarget() =
        runTest(dispatcher) {
            every { intent.getStringExtra(NotificationReplyReceiver.EXTRA_CONNECTION_SCOPE) } returns null
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertTrue(transport.resumes.isEmpty())
            assertTrue(drafts.drafts.value.isEmpty())
            assertEquals(ReplyOutcome.TARGET_UNAVAILABLE to "Hello", notices.single())
            verify { pending.finish() }
        }

    @Test
    fun malformedAndOversizedScopeAreRejected() {
        assertNull(NotificationReplyReceiver.decodeScope("{"))
        assertNull(NotificationReplyReceiver.decodeScope("x".repeat(2049)))
        assertNull(NotificationReplyReceiver.decodeScope("{}"))
        assertEquals(origin, NotificationReplyReceiver.decodeScope(Json.encodeToString(origin)))
    }

    @Test
    fun nullOrBlankInputDoesNothing() =
        runTest(dispatcher) {
            for ((session, text) in listOf(
                "saved-A" to null,
                "saved-A" to " ",
                null to "Hello",
                "" to "Hello",
                " " to "Hello",
            )) {
                givenReply(session, text)
                receiver.onReceive(context, intent)
            }
            every { RemoteInput.getResultsFromIntent(intent) } returns null
            receiver.onReceive(context, intent)
            advanceUntilIdle()
            assertTrue(cached.isEmpty())
            assertTrue(notices.isEmpty())
            assertTrue(transport.prompts.isEmpty())
            verify(exactly = 0) { pending.finish() }
        }

    private fun givenReply(
        session: String?,
        text: String?,
    ) {
        val result = mockk<Bundle>()
        every { result.getCharSequence(NotificationReplyReceiver.KEY_TEXT_REPLY) } returns text
        every { RemoteInput.getResultsFromIntent(intent) } returns result
        every { intent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID) } returns session
        every { intent.getStringExtra(NotificationReplyReceiver.EXTRA_CONNECTION_SCOPE) } returns
            Json.encodeToString(origin)
    }

    private class ReplyTransport(
        var scope: ConnectionScope,
    ) : HermesSubmissionTransport {
        var ready = true
        val resumes = mutableListOf<String>()
        val prompts = mutableListOf<Triple<ConnectionScope, SessionRef, String>>()
        var beforeResume: (suspend () -> Unit)? = null
        var beforePrompt: (suspend () -> Unit)? = null
        var resumeResult: SubmissionResult<SessionRef> =
            SubmissionResult.Confirmed(
                "resume",
                SessionRef("resolved-runtime", "saved-A"),
            )
        var promptResult: SubmissionResult<PromptAcceptance> =
            SubmissionResult.Confirmed(
                "prompt",
                PromptAcceptance.STREAMING,
            )

        override fun configuredScope() = scope

        override fun isReady(scope: ConnectionScope) = ready && this.scope == scope

        override suspend fun resume(
            scope: ConnectionScope,
            savedSessionId: String,
        ): SubmissionResult<SessionRef> {
            resumes += savedSessionId
            beforeResume?.invoke()
            return resumeResult
        }

        override suspend fun submitPrompt(
            scope: ConnectionScope,
            session: SessionRef,
            text: String,
            redirect: Boolean,
            queued: Boolean,
        ): SubmissionResult<PromptAcceptance> {
            beforePrompt?.invoke()
            if (!isReady(scope)) return SubmissionResult.NotSent
            prompts += Triple(scope, session, text)
            return promptResult
        }

        override suspend fun attach(
            scope: ConnectionScope,
            session: SessionRef,
            attachment: Attachment,
            dataUrl: String,
        ): SubmissionResult<String?> = error("Notification has no attachments")
    }
}
