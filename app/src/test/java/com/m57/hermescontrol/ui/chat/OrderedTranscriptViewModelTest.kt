package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.transport.HermesAttentionTransport
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import com.m57.hermescontrol.domain.hermes.draftTargetKey
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.fakes.FakeOutgoingDraftStore
import com.m57.hermescontrol.ui.chat.fakes.FakeSlashUsageStore
import com.m57.hermescontrol.ui.chat.fakes.FakeSubmissionTransport
import io.mockk.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedTranscriptViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockScopedEvents = MutableSharedFlow<ScopedWsEvent>(extraBufferCapacity = 64)
    private lateinit var attentionTransport: HermesAttentionTransport
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private val mockSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository
    private lateinit var fakeSlashUsageStore: FakeSlashUsageStore
    private lateinit var fakeDraftStore: FakeOutgoingDraftStore
    private lateinit var fakeSubmission: FakeSubmissionTransport
    private lateinit var attachmentCacheDir: java.io.File

    /** Counter used to generate unique WS request IDs. */
    private var reqCount = 0
    private var eventOrdinal = 0L
    private var fixtureClock = 1000L

    /** Match production: transcript frames arrive only through original scoped observations. */
    private suspend fun emitEvent(event: WsEvent) {
        if (isTranscriptEvent(event)) {
            mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, event))
        } else {
            mockEventsFlow.emit(event)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        reqCount = 0

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(AuthManager)
        every { AuthManager.uiPreferenceScope() } returns "test-connection-profile"
        every { AuthManager.readUiPreference(any(), any()) } returns null
        every { AuthManager.writeUiPreference(any(), any(), any()) } returns Unit
        every { AuthManager.getPinnedModels() } returns emptyList()
        mockkObject(HermesWsClient)
        // Catch-all for unstubbed request() calls: the real implementation
        // registers a pending call and launches a 120s timeout job on the
        // singleton's real-IO wsScope. The timer outlives the test class,
        // fires after unmockkAll and crashes an unrelated later test with
        // MockK's "can't find stub" (UncaughtExceptionsBeforeTest). Mirror
        // the real request()'s send() delegation (tests verify send calls)
        // but skip the timer. Must COMPLETE (Unit): a never-completing
        // deferred freezes the VM's event collector at the SESSION_CREATE
        // session.usage await and lets its late completion race per-test
        // capture stubs (slash-focus flake, CI 31137581335 on 4645a2b).
        every { HermesWsClient.writeScoped(any(), any(), any(), any()) } answers {
            com.m57.hermescontrol.data.ws.ScopedRpcSubmission(
                "synthetic-command",
                true,
                HermesWsClient.request(arg(1), arg(2), arg(3)),
            )
        }
        every { HermesWsClient.request(any(), any(), any()) } answers {
            HermesWsClient.send(arg(0), arg(1)) {}
            CompletableDeferred<Any?>(Unit)
        }
        mockkObject(ApiClient)
        mockkObject(HermesDatabase)

        app = mockk(relaxed = true)
        attachmentCacheDir =
            java.nio.file.Files
                .createTempDirectory(
                    java.nio.file.Path
                        .of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")),
                    "chat-attachments-test",
                ).toFile()
        every { app.cacheDir } returns attachmentCacheDir
        fakeRepo = FakeChatPersistenceRepository()
        fakeSlashUsageStore = FakeSlashUsageStore()
        fakeDraftStore = FakeOutgoingDraftStore()
        fakeSubmission = FakeSubmissionTransport()
        attentionTransport = mockk()
        coEvery { attentionTransport.respondClarification(any(), any()) } returns
            SubmissionResult.Confirmed("clarify", true)
        coEvery { attentionTransport.acknowledgeApproval(any()) } returns SubmissionResult.Confirmed("received", true)
        coEvery { attentionTransport.respondApproval(any(), any()) } returns
            SubmissionResult.Confirmed("response", true)
        every { HermesWsClient.scopedEvents } returns mockScopedEvents
        every { HermesWsClient.connectionScope } returns MutableStateFlow(fakeSubmission.scope)
        every { HermesWsClient.readSubagents(any(), any()) } returns CompletableDeferred<Any?>(Unit)
        ActiveSessionHolder.set(null)

        mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        every { AuthManager.getSelectedProfileId() } returns null
        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns mockSwitchFlow
        every { ProfileSwitchCoordinator.connectionSwitched } returns MutableSharedFlow<String>()
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getTypingEffectDelayMs() } returns 30
        every { AuthManager.isAutoReconnect() } returns false
        every { HermesWsClient.events } returns mockEventsFlow
        every { HermesWsClient.connectionStatus } returns mockConnectionStatus
        every { HermesWsClient.connect() } answers {
            mockConnectionStatus.value = ConnectionStatus.CONNECTING
        }
        every { HermesWsClient.disconnect() } returns Unit

        // Default send stub: generates unique IDs and invokes onSent callback
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }

        // Stub model-options so preloadModelOptions() (fired at GatewayReady) is safe.
        val mockApi = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery {
            mockApi.getModelOptions(any(), any())
        } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.ModelOptionsResponse(
                    providers =
                        listOf(
                            com.m57.hermescontrol.data.model.ModelProvider(
                                slug = "openai",
                                name = "OpenAI",
                                models = listOf("gpt-4o", "gpt-4o-mini"),
                            ),
                            com.m57.hermescontrol.data.model.ModelProvider(
                                slug = "anthropic",
                                name = "Anthropic",
                                models = listOf("claude-3-5-sonnet"),
                            ),
                        ),
                ),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        attachmentCacheDir.deleteRecursively()
        unmockkAll()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Create a ViewModel with the fake repo injected directly. */
    private var immediate = false

    private fun createViewModel(
        startCleanup: Boolean = false,
        repository: ChatPersistenceRepository = fakeRepo,
    ): ChatViewModel =
        // Both dispatchers injected — a real ioDispatcher would race the
        // test scheduler (repo writes hop it) and shuffle RPC ordering.
        ChatViewModel(
            app,
            startCleanup,
            repository,
            fakeSlashUsageStore,
            if (immediate) {
                kotlinx.coroutines.test.UnconfinedTestDispatcher(
                    testDispatcher.scheduler,
                )
            } else {
                testDispatcher
            },
            if (immediate) {
                kotlinx.coroutines.test.UnconfinedTestDispatcher(
                    testDispatcher.scheduler,
                )
            } else {
                testDispatcher
            },
            fakeDraftStore,
            fakeSubmission,
            attentionTransport,
            clock = { fixtureClock },
        )

    /**
     * Create ViewModel, simulate GatewayReady, feed SESSION_CREATE result,
     * and return a Pair(viewModel, sessionId).
     *
     * Request ID sequence: GatewayReady triggers loadSessions (req-id-1),
     * fetchCommandCatalog (req-id-2), then createNewSession (req-id-3).
     */
    private suspend fun TestScope.createViewModelWithSession(
        startCleanup: Boolean = false,
    ): Pair<ChatViewModel, String> {
        val viewModel = createViewModel(startCleanup)
        advanceUntilIdle()

        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        emitEvent(WsEvent.GatewayReady(null))
        advanceUntilIdle()

        // Emit SESSION_CREATE result (req-id-3 — after loadSessions and fetchCommandCatalog)
        emitEvent(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-123")))
        advanceUntilIdle()

        // Sanity check: confirm the session was actually set
        val session = viewModel.uiState.value.currentSessionId
        checkNotNull(session) {
            "createViewModelWithSession: session was not set — " +
                "req-id-3 did not match SESSION_CREATE. " +
                "If the req sequence changed, update the RpcResult id here."
        }

        return Pair(viewModel, "session-123")
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkpoint(vm: ChatViewModel): CompletableDeferred<Boolean> =
        ChatViewModel::class.java
            .getDeclaredMethod("persistTranscript")
            .apply { isAccessible = true }
            .invoke(vm) as CompletableDeferred<Boolean>

    private fun clear(vm: ChatViewModel) {
        androidx.lifecycle.ViewModelStore().apply {
            put("test", vm)
            clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(
        vm: ChatViewModel,
        name: String,
    ): T =
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(vm) as T

    @Test fun ordinaryMutationCapturesSynchronouslyBeforeNavigation() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val gate = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = { gate.await() }
            try {
                checkpoint(vm)
                runCurrent()
                ChatViewModel::class.java
                    .getDeclaredMethod("addAssistantMessage", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(vm, "captured in A")
                assertEquals(
                    "No detached IO-to-Main producer may postpone capture",
                    1,
                    field<OrderedTranscriptOperations>(vm, "transcriptOperations").pendingCount,
                )
                vm.switchSession("B")
                fakeRepo.dao.beforeSnapshotSave = null
                gate.complete(Unit)
                runCurrent()
                assertTrue(
                    fakeRepo
                        .readTranscript(fakeSubmission.scope.draftTargetKey(), "session-123")
                        .messages
                        .any { it.content == "captured in A" },
                )
                assertFalse(
                    fakeRepo
                        .readTranscript(fakeSubmission.scope.draftTargetKey(), "B")
                        .messages
                        .any { it.content == "captured in A" },
                )
            } finally {
                gate.complete(Unit)
                clear(vm)
                runCurrent()
            }
        }

    @Test fun explicitStreamSealIsSynchronouslyAdmittedAsStrict() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val gate = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = { gate.await() }
            try {
                checkpoint(vm)
                runCurrent()
                field<MutableStateFlow<StreamingState>>(vm, "_streamingState").value =
                    StreamingState(
                        streamingMessage =
                            ChatMessage(
                                id = "partial",
                                role = MessageRole.ASSISTANT,
                                content = "partial",
                                isStreaming = true,
                            ),
                    )
                ChatViewModel::class.java
                    .getDeclaredMethod("sealStreamingMessageIfAny")
                    .apply { isAccessible = true }
                    .invoke(vm)
                assertEquals(1, field<OrderedTranscriptOperations>(vm, "transcriptOperations").pendingCount)
                repeat(32) { checkpoint(vm) }
                assertEquals(
                    "Seal must not be replaced by ordinary checkpoints",
                    2,
                    field<OrderedTranscriptOperations>(vm, "transcriptOperations").pendingCount,
                )
            } finally {
                gate.complete(Unit)
                clear(vm)
                runCurrent()
            }
        }

    @Test fun failedRewindNeverExposesFreshOwnerOrStartsSuccessfulHydration() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val old = field<TranscriptOwner>(vm, "transcriptOwner")
            fakeRepo.dao.beforeSnapshotSave = { error("rewind storage failure") }
            try {
                rewind(vm)
                runCurrent()
                assertFalse(old.valid)
                assertNull(field<TranscriptOwner?>(vm, "transcriptOwner"))
                assertFalse(field(vm, "transcriptOpening"))
                assertEquals("Transcript could not be saved", vm.uiState.value.errorMessage)
                assertTrue(
                    vm.uiState.value.messages
                        .isEmpty(),
                )
            } finally {
                clear(vm)
                runCurrent()
            }
        }

    @Test fun clearingViewModelSettlesWritesClaimsAndRevokesDetachedOwner() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val old = field<TranscriptOwner>(vm, "transcriptOwner")
            val gate = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = { gate.await() }
            val active = checkpoint(vm)
            runCurrent()
            val pending = checkpoint(vm)
            vm.switchSession("B")
            runCurrent()
            val openJob = field<kotlinx.coroutines.Job>(vm, "transcriptOpenJob")
            val queue = field<OrderedTranscriptOperations>(vm, "transcriptOperations")
            clear(vm)
            runCurrent()
            assertTrue(active.isCompleted && pending.isCompleted && openJob.isCompleted)
            assertFalse(old.valid)
            assertEquals(0, queue.pendingCount)
            gate.complete(Unit)
            runCurrent()
            assertNull(field<TranscriptOwner?>(vm, "transcriptOwner"))
        }

    @Test fun acceptedInputFailureKeepsRecoveryDraftAndLaterCheckpointCanRetry() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            fakeRepo.dao.beforeSnapshotSave = { error("storage failure") }
            try {
                vm.sendMessage("recover me")
                runCurrent()
                assertEquals(1, fakeDraftStore.drafts.value.size)
                assertNotNull(vm.uiState.value.errorMessage)
                fakeRepo.dao.beforeSnapshotSave = null
                val retry = checkpoint(vm)
                runCurrent()
                assertTrue(retry.await())
                assertEquals(
                    "A later generic success cannot delete a failed acceptance's recovery draft",
                    1,
                    fakeDraftStore.drafts.value.size,
                )
            } finally {
                clear(vm)
                runCurrent()
            }
        }

    private fun setField(
        vm: ChatViewModel,
        name: String,
        value: Any?,
    ) {
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    @Test fun staleGenerationTerminalCannotReleaseQueue() = runTest { staleTerminal("generation") }

    @Test fun staleOwnerTerminalCannotReleaseQueue() = runTest { staleTerminal("owner") }

    @Test fun differentResponseTerminalCannotReleaseQueue() = runTest { staleTerminal("response") }

    private suspend fun TestScope.staleTerminal(change: String) {
        val (vm, session) = createViewModelWithSession()
        val gate = CompletableDeferred<Unit>()
        try {
            vm.sendMessage("first")
            runCurrent()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            fakeRepo.dao.beforeSnapshotSave = { gate.await() }
            emitEvent(WsEvent.MessageDone(session))
            runCurrent()
            assertTrue(field(vm, "terminalCommitPending"))
            when (change) {
                "generation" -> {
                    setField(vm, "sessionGeneration", field<Long>(vm, "sessionGeneration") + 1)
                }

                "owner" -> {
                    val old = field<TranscriptOwner>(vm, "transcriptOwner")
                    setField(vm, "transcriptOwner", TranscriptOwner(old.target, old.session, old.epoch))
                }

                "response" -> {
                    setField(vm, "terminalCommitResponseId", "a-different-response")
                }
            }
            gate.complete(Unit)
            runCurrent()
            assertTrue("Stale $change completion cannot clear terminal requirement", field(vm, "terminalCommitPending"))
            assertFalse(field(vm, "queueExecutionIdle"))
        } finally {
            gate.complete(Unit)
            clear(vm)
            runCurrent()
        }
    }

    @Test fun writeCapturedBeforeTerminalRequirementCannotAcknowledgeLaterRequirement() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            val gate = CompletableDeferred<Unit>()
            try {
                vm.sendMessage("first")
                runCurrent()
                emitEvent(WsEvent.MessageStart(session))
                emitEvent(WsEvent.MessageDone(session))
                runCurrent()
                assertFalse(field(vm, "terminalCommitPending"))
                val responseId = vm.uiState.value.responseLedger.activeId
                assertNotNull(responseId)
                fakeRepo.dao.beforeSnapshotSave = { gate.await() }
                val older = checkpoint(vm)
                runCurrent()
                setField(vm, "terminalCommitPending", true)
                setField(vm, "terminalCommitResponseId", responseId)
                setField(vm, "queueExecutionIdle", false)
                gate.complete(Unit)
                runCurrent()
                assertTrue(older.await())
                assertTrue(
                    "Success must match the requirement captured at admission",
                    field(vm, "terminalCommitPending"),
                )
                assertFalse(field(vm, "queueExecutionIdle"))
                val retry = checkpoint(vm)
                runCurrent()
                assertTrue(retry.await())
                assertFalse(field(vm, "terminalCommitPending"))
            } finally {
                gate.complete(Unit)
                clear(vm)
                runCurrent()
            }
        }

    @Test fun acceptedInputRejectedOwnerKeepsRecoveryDraft() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            fakeSubmission.beforePrompt = { field<TranscriptOwner>(vm, "transcriptOwner").valid = false }
            try {
                vm.sendMessage("rejected owner")
                runCurrent()
                assertEquals(1, fakeDraftStore.drafts.value.size)
                assertTrue(
                    "Rejected acceptance uses existing draft-storage error surface",
                    vm.uiState.value.draftStorageError,
                )
            } finally {
                clear(vm)
                runCurrent()
            }
        }

    @Test fun canceledAcceptedCommitKeepsRecoveryDraft() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val gate = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = { gate.await() }
            vm.sendMessage("canceled commit")
            runCurrent()
            assertEquals(1, fakeDraftStore.drafts.value.size)
            clear(vm)
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, fakeDraftStore.drafts.value.size)
        }

    @Test fun terminalRequirementExistsBeforeImmediateWriterCanObserveFinishedSnapshot() =
        runTest {
            immediate = true
            Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testDispatcher.scheduler))
            val (vm, session) = createViewModelWithSession()
            try {
                vm.sendMessage("first")
                runCurrent()
                emitEvent(WsEvent.MessageStart(session))
                runCurrent()
                val requirements = mutableListOf<Boolean>()
                fakeRepo.dao.beforeSnapshotSave = {
                    if (vm.uiState.value.responseLedger.responses
                            .any { it.finishedAt != null }
                    ) {
                        requirements.add(field(vm, "terminalCommitPending"))
                    }
                }
                emitEvent(WsEvent.MessageDone(session))
                runCurrent()
                assertTrue(requirements.isNotEmpty())
                assertTrue("Flag must precede even an immediate structural collector save", requirements.first())
                assertFalse(field(vm, "terminalCommitPending"))
                assertTrue(field(vm, "queueExecutionIdle"))
            } finally {
                clear(vm)
                runCurrent()
            }
        }

    @Test fun targetChangeWhileTerminalSaveIsBlockedCannotReleaseOutgoingQueue() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            val gate = CompletableDeferred<Unit>()
            try {
                vm.sendMessage("first")
                runCurrent()
                emitEvent(WsEvent.MessageStart(session))
                runCurrent()
                fakeRepo.dao.beforeSnapshotSave = { gate.await() }
                emitEvent(WsEvent.MessageDone(session))
                runCurrent()
                assertTrue(field(vm, "terminalCommitPending"))
                fakeSubmission.scope =
                    com.m57.hermescontrol.domain.hermes
                        .ConnectionScope("other-server", null, null, "other-process", 2)
                gate.complete(Unit)
                runCurrent()
                assertTrue(
                    "Saved old-target terminal cannot clear new configured-target fence",
                    field(vm, "terminalCommitPending"),
                )
                assertFalse(field(vm, "queueExecutionIdle"))
            } finally {
                gate.complete(Unit)
                clear(vm)
                runCurrent()
            }
        }

    private fun rewind(vm: ChatViewModel) {
        ChatViewModel::class.java
            .getDeclaredMethod("handlePrefillResult", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, "retry", "rewound")
    }

    @Test fun undoRevokesImmediatelyButClaimsAndClearsAsOneOrderedBarrier() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            vm.sendMessage("old body must not resurrect after undo")
            runCurrent()
            val old = field<TranscriptOwner>(vm, "transcriptOwner")
            val gate = CompletableDeferred<Unit>()
            val emptyGate = CompletableDeferred<Unit>()
            var saves = 0
            fakeRepo.dao.beforeSnapshotSave = {
                saves++
                if (saves == 1) gate.await()
                if (saves == 2) emptyGate.await()
            }
            try {
                checkpoint(vm)
                runCurrent()
                val queue = field<OrderedTranscriptOperations>(vm, "transcriptOperations")
                var priorBarrierRan = false
                queue.strict { priorBarrierRan = true }
                rewind(vm)
                assertFalse(old.valid)
                assertEquals("Prior barrier plus synchronous rewind claim-and-clear", 2, queue.pendingCount)
                gate.complete(Unit)
                runCurrent()
                assertTrue(priorBarrierRan)
                assertEquals(2, saves)
                assertNull(
                    "Fresh owner cannot be exposed before empty manifest is durable",
                    field<TranscriptOwner?>(vm, "transcriptOwner"),
                )
                emptyGate.complete(Unit)
                runCurrent()
                val fresh = field<TranscriptOwner>(vm, "transcriptOwner")
                assertTrue(fresh.epoch > old.epoch)
                assertTrue(
                    fakeRepo.readTranscript(fresh.target, fresh.session).messages.none { it.role == MessageRole.USER },
                )
            } finally {
                gate.complete(Unit)
                emptyGate.complete(Unit)
                clear(vm)
                runCurrent()
            }
        }

    @Test fun supersedingOpenRevokesLateOldOwnerAndInstallsOnlyNewSelection() =
        runTest {
            val repository = mockk<ChatPersistenceRepository>()
            val target = fakeSubmission.scope.draftTargetKey()
            val old = TranscriptOwner(target, "B", 1)
            val fresh = TranscriptOwner(target, "C", 1)
            val release = CompletableDeferred<Unit>()
            coEvery { repository.openTranscript(target, "B") } coAnswers {
                release.await()
                old to TranscriptSnapshot(emptyList(), TranscriptPayload())
            }
            coEvery { repository.openTranscript(target, "C") } returns
                (fresh to TranscriptSnapshot(emptyList(), TranscriptPayload()))
            every { repository.prepare(any(), any(), any(), any()) } answers {
                TranscriptWrite(firstArg(), 1, TranscriptSnapshot(secondArg(), TranscriptPayload(ledger = thirdArg())))
            }
            coEvery { repository.save(any()) } returns true
            val vm = createViewModel(repository = repository)
            try {
                runCurrent()
                vm.switchSession("B")
                runCurrent()
                val oldConsumer = field<kotlinx.coroutines.Job>(vm, "transcriptOpenJob")
                vm.switchSession("C")
                runCurrent()
                assertTrue(oldConsumer.isCancelled)
                verify(exactly = 0) {
                    HermesWsClient.send(WsMethods.SESSION_RESUME, match { it["session_id"] == "B" }, any())
                }
                release.complete(Unit)
                runCurrent()
                assertFalse(old.valid)
                assertTrue(fresh.valid)
                assertTrue(field<TranscriptOwner>(vm, "transcriptOwner") === fresh)
                assertEquals("C", vm.uiState.value.currentSessionId)
            } finally {
                release.complete(Unit)
                clear(vm)
                runCurrent()
            }
            assertFalse(fresh.valid)
        }

    @Test fun canceledOpenInvalidatesOwnerCreatedAfterItsConsumerWasCanceled() =
        runTest {
            val repository = mockk<ChatPersistenceRepository>()
            val owner = TranscriptOwner(fakeSubmission.scope.draftTargetKey(), "B", 1)
            val release = CompletableDeferred<Unit>()
            var entered = false
            coEvery { repository.openTranscript(any(), any()) } coAnswers {
                entered = true
                release.await()
                owner to TranscriptSnapshot(emptyList(), TranscriptPayload())
            }
            val vm = createViewModel(repository = repository)
            try {
                runCurrent()
                vm.switchSession("B")
                runCurrent()
                assertTrue(entered)
                field<kotlinx.coroutines.Job>(vm, "transcriptOpenJob").cancel()
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertFalse("Late-created owner must not escape canceled open", owner.valid)
                assertNull(field<TranscriptOwner?>(vm, "transcriptOwner"))
            } finally {
                release.complete(Unit)
                clear(vm)
                runCurrent()
            }
        }

    @Test fun segmentAndTerminalAreStrictEvenBehindBlockedWriter() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("first")
            runCurrent()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = { gate.await() }
            try {
                checkpoint(vm)
                runCurrent()
                emitEvent(WsEvent.MessageInterim("sealed prefix", false, session))
                runCurrent()
                repeat(32) { checkpoint(vm) }
                assertEquals(
                    "Sealed segment plus latest checkpoint",
                    2,
                    field<OrderedTranscriptOperations>(vm, "transcriptOperations").pendingCount,
                )
                emitEvent(WsEvent.MessageDone(session))
                runCurrent()
                repeat(32) { checkpoint(vm) }
                assertEquals(
                    "Seal, checkpoint, terminal, checkpoint remain separate",
                    4,
                    field<OrderedTranscriptOperations>(vm, "transcriptOperations").pendingCount,
                )
            } finally {
                fakeRepo.dao.beforeSnapshotSave = null
                gate.complete(Unit)
                runCurrent()
                clear(vm)
            }
        }

    @Test fun navigationFlushSealsCheckpointSegmentBeforeOrderedClaim() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val oldOwner = field<TranscriptOwner>(vm, "transcriptOwner")
            val gate = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = { gate.await() }
            try {
                checkpoint(vm)
                runCurrent()
                val pending = checkpoint(vm)
                vm.switchSession("B")
                assertFalse("Navigation must not replace an earlier checkpoint", pending.isCompleted)
                assertTrue("Navigation detaches, not revokes, outgoing owner", oldOwner.valid)
                vm.switchSession("session-123")
                runCurrent()
                fakeRepo.dao.beforeSnapshotSave = null
                gate.complete(Unit)
                runCurrent()
                assertTrue(pending.await())
                assertEquals("session-123", vm.uiState.value.currentSessionId)
                val returnedOwner = field<TranscriptOwner>(vm, "transcriptOwner")
                assertNotSame(oldOwner, returnedOwner)
                assertTrue(returnedOwner.epoch > oldOwner.epoch)
            } finally {
                gate.complete(Unit)
                clear(vm)
                runCurrent()
            }
        }

    @Test
    fun acceptedInputIsStrictAndDraftDeletionWaitsForItsExactCommit() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val releaseOld = CompletableDeferred<Unit>()
            val releaseAccepted = CompletableDeferred<Unit>()
            var saves = 0
            fakeRepo.dao.beforeSnapshotSave = {
                saves++
                if (saves == 1) releaseOld.await()
                if (saves == 2) releaseAccepted.await()
            }
            try {
                checkpoint(vm)
                runCurrent()
                vm.sendMessage("durable accepted input")
                runCurrent()
                assertTrue(
                    vm.uiState.value.responseLedger.pendingInputs
                        .isEmpty(),
                )
                assertEquals(1, fakeDraftStore.drafts.value.size)
                repeat(64) { checkpoint(vm) }
                releaseOld.complete(Unit)
                runCurrent()
                assertEquals(2, saves)
                assertEquals(
                    "Draft must survive until accepted-input transaction completes",
                    1,
                    fakeDraftStore.drafts.value.size,
                )
                releaseAccepted.complete(Unit)
                runCurrent()
                assertTrue(
                    "A later checkpoint must not replace the accepted-input ACK",
                    fakeDraftStore.drafts.value.isEmpty(),
                )
                assertTrue(
                    fakeRepo
                        .readTranscript(fakeSubmission.scope.draftTargetKey(), "session-123")
                        .messages
                        .any { it.content == "durable accepted input" },
                )
            } finally {
                releaseOld.complete(Unit)
                releaseAccepted.complete(Unit)
                clear(vm)
                runCurrent()
            }
        }

    @Test
    fun blockedWriterReplacesOnlyPendingCheckpointInActualViewModel() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            val release = CompletableDeferred<Unit>()
            var saves = 0
            fakeRepo.dao.beforeSnapshotSave = {
                saves++
                if (saves == 1) release.await()
            }
            try {
                val active = checkpoint(vm)
                runCurrent()
                assertEquals(1, saves)
                val pending = (1..128).map { checkpoint(vm) }
                assertTrue(
                    "Replaced checkpoints must settle false immediately, not accumulate behind IO",
                    pending.dropLast(1).all { it.isCompleted && !it.getCompleted() },
                )
                assertFalse(active.isCompleted)
                assertFalse(pending.last().isCompleted)
                release.complete(Unit)
                runCurrent()
                assertTrue(active.await())
                assertTrue(pending.last().await())
                assertEquals("One active plus latest adjacent checkpoint", 2, saves)
                assertEquals(session, vm.uiState.value.currentSessionId)
            } finally {
                release.complete(Unit)
                clear(vm)
                runCurrent()
            }
        }
}
