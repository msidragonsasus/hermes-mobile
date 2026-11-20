package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.CreateSpecialistRequest
import com.m57.hermescontrol.data.model.CreateSpecialistResponse
import com.m57.hermescontrol.data.model.PaginationInfo
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.data.model.SpecialistCapabilitiesResponse
import com.m57.hermescontrol.data.model.SpecialistSetupApplyRequest
import com.m57.hermescontrol.data.model.SpecialistSetupApplyResponse
import com.m57.hermescontrol.data.model.SpecialistSetupConfirmRequest
import com.m57.hermescontrol.data.model.SpecialistSetupDraft
import com.m57.hermescontrol.data.model.SpecialistSetupMessage
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.data.model.SpecialistSetupTurn
import com.m57.hermescontrol.data.model.SpecialistSetupTurnRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.GatewayFile
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.SpecialistApiOutcome
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.transport.HermesAttentionTransport
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.DraftState
import com.m57.hermescontrol.domain.hermes.OutgoingDraft
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import com.m57.hermescontrol.domain.hermes.draftTargetKey
import com.m57.hermescontrol.ui.chat.components.quotedReplyMessage
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.fakes.FakeOutgoingDraftStore
import com.m57.hermescontrol.ui.chat.fakes.FakeSlashUsageStore
import com.m57.hermescontrol.ui.chat.fakes.FakeSubmissionTransport
import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurnsWithStreaming
import com.m57.hermescontrol.ui.chat.fullbleed.replyFooters
import com.m57.hermescontrol.ui.chat.fullbleed.replyLifecycle
import io.mockk.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
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
    private val receiptViewModels = mutableListOf<ChatViewModel>()

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

    /** Explicit server-addressed terminal fixture; never used by missing-correlation regressions. */
    private suspend fun completeAddressed(
        vm: ChatViewModel,
        session: String,
        text: String = "done",
        responseReused: Boolean? = null,
    ) {
        val record =
            vm.uiState.value.responseLedger.responses.single {
                it.id ==
                    vm.uiState.value.responseLedger.activeId
            }
        val row = record.userRowIds.first()
        emitEvent(
            WsEvent.MessageComplete(
                text,
                session,
                responseReused = responseReused,
                persistedTurn =
                    com.m57.hermescontrol.data.ws.PersistedTurn(
                        rowIds = listOf(row, row + 1),
                        complete = true,
                        userRowId = row,
                        finalAssistantRowId = row + 1,
                    ),
            ),
        )
    }

    @Test fun terminalReceipt_productionTransportRestBeforeTerminal() = runTest { terminalReceiptVertical(true) }

    @Test fun terminalReceipt_productionTransportTerminalBeforeRest() = runTest { terminalReceiptVertical(false) }

    @Test fun terminalReceipt_bufferedPrefixRestBeforeTerminal() =
        runTest {
            terminalReceiptVertical(true, requireBuffer = true)
        }

    @Test fun terminalReceipt_bufferedPrefixTerminalBeforeRest() =
        runTest {
            terminalReceiptVertical(false, requireBuffer = true)
        }

    @Test fun terminalReceipt_retainedOriginalAdmittedBeforeDelayedCollector() =
        runTest {
            terminalReceiptVertical(true, gateOriginal = true)
        }

    @Test fun terminalReceipt_failedAcceptedPersistenceRetainsDraftAndNeverResends() =
        runTest {
            terminalReceiptVertical(true, requireBuffer = true, failPersistence = true)
        }

    @Test fun terminalReceipt_authEpochChangesAfterTypedReturnCannotCommitOpening() =
        runTest {
            terminalReceiptVertical(true, invalidateAfterProof = true)
        }

    @Test fun terminalReceipt_newUnpersistedSessionDoesNotGuessSavedTarget() =
        runTest {
            var submitted: com.m57.hermescontrol.domain.hermes.SessionRef? = null
            val recording =
                object : com.m57.hermescontrol.data.transport.HermesSubmissionTransport by fakeSubmission {
                    override suspend fun submitPrompt(
                        scope: com.m57.hermescontrol.domain.hermes.ConnectionScope,
                        session: com.m57.hermescontrol.domain.hermes.SessionRef,
                        text: String,
                        redirect: Boolean,
                        queued: Boolean,
                        surface: String?,
                    ): SubmissionResult<com.m57.hermescontrol.domain.hermes.PromptAcceptance> {
                        submitted = session
                        return fakeSubmission.submitPrompt(scope, session, text, redirect, queued)
                    }
                }
            val (vm, _) = createViewModelWithSession(transport = recording)
            vm.sendMessage("new unpersisted session")
            runCurrent()
            assertNotNull(submitted)
            assertNull("A runtime fallback is not a known saved target for terminal proof", submitted?.savedId)
        }

    @Test fun terminalReceipt_realProducerRestBeforeTerminal() =
        runTest {
            terminalReceiptVertical(true, requireBuffer = true, realProducer = true)
        }

    @Test fun terminalReceipt_realProducerTerminalBeforeRest() =
        runTest {
            terminalReceiptVertical(false, requireBuffer = true, realProducer = true)
        }

    @Test fun terminalReceipt_overflowDropsOriginalAfterLaterCollectorProgress() =
        runTest {
            terminalReceiptVertical(true, overflowOriginal = true)
        }

    @Test fun terminalReceipt_unprocessedPredecessorsCannotBeSkippedByTypedProof() =
        runTest {
            terminalReceiptVertical(false, gatePredecessors = true)
        }

    /** Synthetic CONTRACT fixture until parent freezes the actual producer export. Real WS + transport + VM. */
    private suspend fun TestScope.terminalReceiptVertical(
        restFirst: Boolean,
        gateOriginal: Boolean = false,
        requireBuffer: Boolean = false,
        failPersistence: Boolean = false,
        invalidateAfterProof: Boolean = false,
        realProducer: Boolean = false,
        overflowOriginal: Boolean = false,
        gatePredecessors: Boolean = false,
    ) {
        unmockkObject(HermesWsClient)
        HermesWsClient.disconnect(clearPendingMessages = true)
        HermesWsClient.rejectAllPending()
        val socket = mockk<okhttp3.WebSocket>(relaxed = true)

        fun field(name: String) = HermesWsClient::class.java.getDeclaredField(name).apply { isAccessible = true }
        val generation =
            (
                field(
                    "connectionGeneration",
                ).get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger
            ).get()
        val scope = fakeSubmission.scope.copy(generation = generation)
        val epoch = MutableStateFlow(2L)
        every { AuthManager.activeProfileId } returns MutableStateFlow(null)
        every { AuthManager.connectionEpoch } returns epoch
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        field("webSocket").set(HermesWsClient, socket)
        field("connectionBaseUrl").set(HermesWsClient, "http://test.local/")
        (field("connected").get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)
        (field("intentionalClose").get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        @Suppress("UNCHECKED_CAST")
        (
            field(
                "_connectionScope",
            ).get(HermesWsClient) as MutableStateFlow<com.m57.hermescontrol.domain.hermes.ConnectionScope?>
        ).value =
            scope
        @Suppress("UNCHECKED_CAST")
        (field("_connectionStatus").get(HermesWsClient) as MutableStateFlow<ConnectionStatus>).value =
            ConnectionStatus.CONNECTED
        val ctor =
            Class
                .forName(
                    "com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl",
                ).declaredConstructors
                .single()
                .apply {
                    isAccessible =
                        true
                }
        val listener = ctor.newInstance(generation, scope) as okhttp3.WebSocketListener
        val sent = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val peer = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

        fun ack(
            id: String,
            result: String,
        ) = listener.onMessage(socket, """{"jsonrpc":"2.0","id":"$id","result":$result}""")
        every { socket.send(any<String>()) } answers {
            val request =
                kotlinx.serialization.json.Json.parseToJsonElement(
                    firstArg<String>(),
                ) as kotlinx.serialization.json.JsonObject
            sent += request
            val method = (request["method"] as JsonPrimitive).content
            val id = (request["id"] as JsonPrimitive).content
            if (method != WsMethods.PROMPT_SUBMIT) {
                ack(
                    id,
                    when (method) {
                        "gateway.capabilities" -> {
                            """
                            {"terminal_acceptance":{"version":1,"mode":"local-ordinary-text-v1","peer_id":"$peer"}}
                            """.trimIndent()
                        }

                        WsMethods.SESSION_RESUME -> {
                            """
                            {"session_id":"receipt-runtime","stored_session_id":"receipt-saved","running":false,"status":"idle"}
                            """.trimIndent()
                        }

                        WsMethods.SESSION_LIST -> {
                            "{\"sessions\":[]}"
                        }

                        else -> {
                            "{}"
                        }
                    },
                )
            }
            true
        }
        // Only lifecycle/configuration and unrelated roster seams are mocked; no request/write/listener/timer mock.
        val originalGate = CompletableDeferred<Unit>()
        val proofRelease = CompletableDeferred<Unit>()
        var predecessorsArmed = false
        val collected = mutableListOf<ScopedWsEvent>()
        val originalEvents = HermesWsClient.scopedEvents
        val delayedEvents =
            originalEvents
                .onEach {
                    if (predecessorsArmed ||
                        (gateOriginal && (it.event as? WsEvent.MessageComplete)?.terminalAcceptance != null)
                    ) {
                        originalGate.await()
                    }
                }.onEach { collected += it }
                .shareIn(backgroundScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 0)
        mockkObject(HermesWsClient)
        every { HermesWsClient.scopedEvents } returns delayedEvents
        every { HermesWsClient.connect() } returns Unit
        every { HermesWsClient.configuredScope() } returns scope
        every { HermesWsClient.readSubagents(any(), any()) } returns CompletableDeferred<Any?>(Unit)
        val session = "receipt-saved"
        val api = ApiClient.hermesApi
        stubEmptySessionRests(session)
        coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    emptyList(),
                    pagination =
                        com.m57.hermescontrol.data.model
                            .PaginationInfo(order = "latest", offset = 0),
                ),
            )
        val transport =
            com.m57.hermescontrol.data.transport
                .DashboardRpcTransport()
        val observedResults = mutableListOf<SubmissionResult<com.m57.hermescontrol.domain.hermes.PromptAcceptance>>()
        val recording =
            object : com.m57.hermescontrol.data.transport.HermesSubmissionTransport by transport {
                override suspend fun submitPrompt(
                    scope: com.m57.hermescontrol.domain.hermes.ConnectionScope,
                    session: com.m57.hermescontrol.domain.hermes.SessionRef,
                    text: String,
                    redirect: Boolean,
                    queued: Boolean,
                    surface: String?,
                ): SubmissionResult<com.m57.hermescontrol.domain.hermes.PromptAcceptance> =
                    transport.submitPrompt(scope, session, text, redirect, queued).also {
                        observedResults += it
                        if (overflowOriginal && it is SubmissionResult.Confirmed &&
                            it.proofSource == com.m57.hermescontrol.domain.hermes.SubmissionProofSource.TERMINAL_RECEIPT
                        ) {
                            proofRelease.await()
                        }
                        if (invalidateAfterProof && it is SubmissionResult.Confirmed &&
                            it.proofSource == com.m57.hermescontrol.domain.hermes.SubmissionProofSource.TERMINAL_RECEIPT
                        ) {
                            epoch.value =
                                4L
                        }
                    }
            }
        val vm = createViewModel(transport = recording, realTokenBuffering = requireBuffer).also(receiptViewModels::add)
        runCurrent()
        vm.switchSession(session)
        advanceUntilIdle()
        assertEquals(session, vm.uiState.value.currentSessionId)
        assertFalse("Resume and real REST hydration must finish", vm.uiState.value.isLoading)
        HermesWsClient.request("gateway.capabilities").await()
        val seed =
            if (realProducer) {
                TerminalProducerFixture.export(
                    "fixture-seed",
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    peer,
                    "receipt-runtime",
                    session,
                )
            } else {
                null
            }
        val full = seed?.get("full_body")?.jsonPrimitive?.content ?: "完全な回答。prefixではなく保存された全文です。".repeat(5)
        val currentUserRow =
            seed
                ?.get("manifest")
                ?.jsonObject
                ?.get("user_row_id")
                ?.jsonPrimitive
                ?.content
                ?.toLong() ?: 41L
        val currentFinalRow =
            seed
                ?.get("manifest")
                ?.jsonObject
                ?.get("final_assistant_row_id")
                ?.jsonPrimitive
                ?.content
                ?.toLong() ?: 42L
        val currentPrompt =
            if (seed !=
                null
            ) {
                (seed["persisted_rows"] as kotlinx.serialization.json.JsonArray)
                    .single {
                        it.jsonObject["role"]?.jsonPrimitive?.content ==
                            "user"
                    }.jsonObject["content"]!!
                    .jsonPrimitive.content
            } else {
                "receipt question"
            }

        fun wire(
            type: String,
            payload: String,
        ) = listener.onMessage(
            socket,
            """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"receipt-runtime","payload":$payload}}""",
        )
        // Distinct earlier response with IDENTICAL prose, admitted by its actual own RPC ACK.
        vm.sendMessage("independent earlier question")
        runCurrent()
        val firstPrompt = sent.last { (it["method"] as JsonPrimitive).content == WsMethods.PROMPT_SUBMIT }
        ack((firstPrompt["id"] as JsonPrimitive).content, """{"status":"streaming","user_row_id":31}""")
        runCurrent()
        wire("message.start", "{}")
        wire(
            "message.complete",
            """{"text":${JsonPrimitive(
                full,
            )},"status":"complete","partial":false,"response_reused":false,"response_transformed":false,"response_previewed":false,"persisted_turn":{"row_ids":[31,32],"complete":true,"user_row_id":31,"user_row_ids":[31],"final_assistant_row_id":32}}""",
        )
        runCurrent()
        assertTrue(vm.streamingState.value.turnEnded)
        val firstResponse =
            vm.uiState.value.responseLedger.responses
                .single()
        observedResults.clear()
        val previousSends = sent.count { (it["method"] as JsonPrimitive).content == WsMethods.PROMPT_SUBMIT }
        val entered = CompletableDeferred<Unit>()
        val page = CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
        coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } coAnswers
            {
                entered.complete(Unit)
                page.await()
            }
        vm.syncCurrentSession()
        runCurrent()
        assertTrue("Public sync must be really admitted while idle", entered.isCompleted)
        vm.sendMessage(currentPrompt)
        runCurrent()
        val responseId = vm.uiState.value.responseLedger.activeId!!
        assertNotEquals(firstResponse.id, responseId)
        assertTrue(vm.uiState.value.responseLedger.requiresCorrelation)
        val prompt = sent.last { (it["method"] as JsonPrimitive).content == WsMethods.PROMPT_SUBMIT }
        val rid = (prompt["id"] as JsonPrimitive).content
        val params = prompt["params"] as kotlinx.serialization.json.JsonObject
        val tag = params["terminal_acceptance"] as? kotlinx.serialization.json.JsonObject
        assertNotNull("Only positively negotiated sends receive the per-attempt nonce", tag)
        val nonce = (tag!!["client_attempt_nonce"] as JsonPrimitive).content
        val exported =
            if (realProducer) {
                TerminalProducerFixture.export(
                    rid,
                    nonce,
                    peer,
                    "receipt-runtime",
                    session,
                )
            } else {
                null
            }
        if (exported != null) {
            assertEquals(full, exported["full_body"]!!.jsonPrimitive.content)
            assertEquals(
                currentUserRow,
                exported["manifest"]!!
                    .jsonObject["user_row_id"]!!
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertEquals(
                currentFinalRow,
                exported["manifest"]!!
                    .jsonObject["final_assistant_row_id"]!!
                    .jsonPrimitive.content
                    .toLong(),
            )
        }
        predecessorsArmed = gatePredecessors
        wire("message.start", "{}")
        if (gatePredecessors) {
            wire("message.interim", """{"text":"PREDECESSOR_INTERIM","already_streamed":false}""")
            wire("tool.start", """{"name":"predecessor_tool","tool_id":"r1-tool"}""")
            wire(
                "tool.complete",
                """{"name":"predecessor_tool","tool_id":"r1-tool","output":"PREDECESSOR_TOOL_RESULT"}""",
            )
        }
        wire("message.delta", """{"text":${JsonPrimitive(full.take(12))}}""")
        runCurrent()
        if (gatePredecessors) {
            assertNull(
                "Actual earlier start/token/interim/tool are still unprocessed",
                vm.streamingState.value.streamingMessage,
            )
        } else {
            assertEquals(
                full.take(12),
                vm.streamingState.value.streamingMessage
                    ?.content,
            )
        }
        if (requireBuffer) {
            wire("message.delta", """{"text":${JsonPrimitive(full.substring(12, 23))}}""")
            runCurrent()
            assertEquals(
                "The unflushed pending tail must really remain buffered, not always-flush in the JVM",
                full.take(12),
                vm.streamingState.value.streamingMessage
                    ?.content,
            )
            assertEquals(
                "Actual controller pending buffer, no VM private state mutation",
                11,
                vm.pendingStreamingTokenCount(),
            )
        }
        assertTrue(fakeDraftStore.drafts.value.isNotEmpty())
        val rest =
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    listOf(
                        com.m57.hermescontrol.data.model.SessionMessage(
                            id = 31,
                            role = "user",
                            content = JsonPrimitive("independent earlier question"),
                        ),
                        com.m57.hermescontrol.data.model.SessionMessage(
                            id = 32,
                            role = "assistant",
                            content = JsonPrimitive(full),
                        ),
                        com.m57.hermescontrol.data.model.SessionMessage(
                            id = currentUserRow.toInt(),
                            role = "user",
                            content = JsonPrimitive(currentPrompt),
                        ),
                        com.m57.hermescontrol.data.model.SessionMessage(
                            id = currentFinalRow.toInt(),
                            role = "assistant",
                            content = JsonPrimitive(full),
                        ),
                    ),
                    pagination =
                        com.m57.hermescontrol.data.model
                            .PaginationInfo(order = "latest", offset = 0, returned = 4),
                ),
            )
        if (restFirst) {
            page.complete(rest)
            runCurrent()
        }
        val persistenceEntered = CompletableDeferred<Unit>()
        val persistenceRelease = CompletableDeferred<Unit>()
        var deleteAfterBarrier = false
        fakeRepo.dao.beforeSnapshotSave = {
            persistenceEntered.complete(Unit)
            persistenceRelease.await()
            check(!failPersistence) { "Synthetic transcript persistence failure" }
        }
        fakeDraftStore.beforeDelete = {
            val durable = fakeRepo.readTranscript(scope.draftTargetKey(), session)
            val owner =
                durable.payload.ledger.responses
                    .single { it.id == responseId }
            assertEquals("terminal-receipt", owner.authority.opening?.rowBindingSource)
            assertTrue(
                "ACCEPTED_INPUT must persist original terminal ownership before deleting draft",
                owner.authority.terminals.isNotEmpty(),
            )
            deleteAfterBarrier = true
        }
        fixtureClock = 2000L
        val terminalPayload = """{"text":${JsonPrimitive(
            full,
        )},"status":"complete","partial":false,"response_reused":false,"response_transformed":false,"response_previewed":false,"persisted_turn":{"row_ids":[41,42],"complete":true,"user_row_id":41,"user_row_ids":[41],"final_assistant_row_id":42},"terminal_acceptance":{"version":1,"method":"prompt.submit","original_rpc_request_id":"$rid","client_attempt_nonce":"$nonce","peer_id":"$peer","disposition":"streaming","runtime_session_id":"receipt-runtime","target_session_id":"$session","transcript_session_id":"$session","origin_user_row_id":41}}"""
        if (exported ==
            null
        ) {
            wire("message.complete", terminalPayload)
        } else {
            listener.onMessage(socket, exported["frame"].toString())
        }
        if (overflowOriginal) repeat(600) { wire("session.usage", "{}") }
        runCurrent()
        if (overflowOriginal) {
            val retained = (observedResults.single() as SubmissionResult.Confirmed).terminalObservation!!
            assertTrue(
                "Real shared-flow overflow removed original terminal",
                collected.none {
                    it.ordinal ==
                        retained.ordinal
                },
            )
            assertTrue(
                "Later scoped observations actually processed before typed VM return",
                collected.last().ordinal > retained.ordinal,
            )
            proofRelease.complete(Unit)
            runCurrent()
            assertTrue(
                "Retained terminal must survive later-ordinal collector progress",
                vm.streamingState.value.turnEnded,
            )
        }
        if (gatePredecessors) {
            assertFalse("Typed proof must wait for actual ordered predecessors", vm.streamingState.value.turnEnded)
            assertFalse("Draft cannot resolve before original terminal is applied/preserved", deleteAfterBarrier)
            assertTrue(fakeDraftStore.drafts.value.isNotEmpty())
            originalGate.complete(Unit)
            runCurrent()
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.content == "PREDECESSOR_INTERIM" },
            )
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.toolName == "predecessor_tool" },
            )
        }
        assertEquals(
            "Verified terminal must resolve the original real typed await with permanently dropped ACK",
            1,
            observedResults.size,
        )
        assertTrue(observedResults.single() is SubmissionResult.Confirmed)
        if (invalidateAfterProof) {
            assertNull(
                "Auth epoch changed after transport delivery: VM must not commit the old opening",
                vm.uiState.value.responseLedger.responses
                    .single {
                        it.id ==
                            responseId
                    }.authority.opening,
            )
            assertTrue(fakeDraftStore.drafts.value.isNotEmpty())
            assertFalse(deleteAfterBarrier)
            originalGate.complete(Unit)
            persistenceRelease.complete(Unit)
            if (!page.isCompleted) page.complete(rest)
            runCurrent()
            return
        }
        assertEquals(
            "terminal-receipt",
            vm.uiState.value.responseLedger.responses
                .single {
                    it.id == responseId
                }.authority.opening
                ?.rowBindingSource,
        )
        if (gateOriginal) {
            assertTrue(
                "Retained original terminal must be admitted before the independent collector resumes",
                vm.streamingState.value.turnEnded,
            )
        }
        assertTrue(persistenceEntered.isCompleted)
        assertTrue(
            "Draft retained while accepted-input persistence is suspended",
            fakeDraftStore.drafts.value.isNotEmpty(),
        )
        assertFalse(deleteAfterBarrier)
        originalGate.complete(Unit)
        if (!restFirst) {
            page.complete(rest)
            runCurrent()
        }
        persistenceRelease.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.streamingState.value.turnEnded)
        assertFalse(vm.uiState.value.isAgentTyping)
        assertNull(vm.streamingState.value.streamingMessage)
        if (overflowOriginal) {
            val gapped =
                vm.uiState.value.responseLedger.responses
                    .single { it.id == responseId }
            assertTrue("Original overflow gap must remain conservative", gapped.authority.deliveryGap)
            assertEquals(
                "Full retained body must still materialize exactly once despite the gap",
                2,
                vm.uiState.value.messages.count {
                    it.role ==
                        MessageRole.ASSISTANT &&
                        it.content == full
                },
            )
            assertFalse(
                "A recovered body is not local copy authority",
                gapped
                    .authorityDecision(
                        vm.uiState.value.messages
                            .map {
                                it.id
                            }.toSet(),
                    ).copyReady,
            )
            coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } returns rest
            vm.syncCurrentSession()
            runCurrent()
        }
        assertEquals(listOf(full, full), vm.footerPayloads())
        assertEquals(
            "Equal prose belongs to two distinct response IDs",
            2,
            vm.uiState.value.messages.count {
                it.role ==
                    MessageRole.ASSISTANT &&
                    it.content == full
            },
        )
        val owner =
            vm.uiState.value.responseLedger.responses
                .single { it.id == responseId }
        val originalOrdinal = (observedResults.single() as SubmissionResult.Confirmed).terminalObservation!!.ordinal
        assertEquals(
            "Exact original terminal applied once, not deduped by a later high-water",
            1,
            owner.authority.terminals.count {
                it.observation ==
                    originalOrdinal
            },
        )
        if (overflowOriginal) assertTrue(originalOrdinal < collected.last().ordinal)
        assertEquals(listOf(currentUserRow), owner.userRowIds)
        assertEquals(currentFinalRow, owner.canonicalFinalAssistantRowId)
        assertEquals(2000L, owner.finishedAt)
        assertEquals(
            1,
            owner.messageIds.count { member ->
                vm.uiState.value.messages.any {
                    it.id == member &&
                        it.content == full
                }
            },
        )
        assertEquals(
            firstResponse.finishedAt,
            vm.uiState.value.responseLedger.responses
                .single {
                    it.id ==
                        firstResponse.id
                }.finishedAt,
        )
        assertEquals(!failPersistence, deleteAfterBarrier)
        assertEquals(failPersistence, fakeDraftStore.drafts.value.isNotEmpty())
        assertEquals(
            "One send for THIS attempt, never auto-resend",
            previousSends + 1,
            sent.count {
                (it["method"] as JsonPrimitive).content ==
                    WsMethods.PROMPT_SUBMIT
            },
        )
        assertTrue(
            sent.none {
                (it["method"] as JsonPrimitive).content in
                    setOf("session.interrupt", "session.cancel", "prompt.resolve_unknown")
            },
        )
        if (!failPersistence) {
            val durable = ChatPersistenceRepository(fakeRepo.dao).readTranscript(scope.draftTargetKey(), session)
            assertEquals(
                "terminal-receipt",
                durable.payload.ledger.responses
                    .single {
                        it.id == responseId
                    }.authority.opening
                    ?.rowBindingSource,
            )
            assertEquals(2, durable.messages.count { it.role == MessageRole.ASSISTANT && it.content == full })
        }
        val stableIds =
            vm.uiState.value.messages
                .map { it.id }
        fixtureClock = 5000L
        wire("message.delta", "{\"text\":\"LATE_SENTINEL\"}")
        wire("message.delta", "{\"text\":null}")
        if (exported ==
            null
        ) {
            wire("message.complete", terminalPayload)
        } else {
            listener.onMessage(socket, exported["frame"].toString())
        }
        ack(rid, """{"status":"streaming","user_row_id":$currentUserRow}""")
        runCurrent()
        assertEquals(
            "A late ACK cannot replace terminal provenance",
            com.m57.hermescontrol.domain.hermes.SubmissionProofSource.TERMINAL_RECEIPT,
            (observedResults.single() as SubmissionResult.Confirmed).proofSource,
        )
        assertEquals(
            stableIds,
            vm.uiState.value.messages
                .map { it.id },
        )
        assertEquals(
            2000L,
            vm.uiState.value.responseLedger.responses
                .single { it.id == responseId }
                .finishedAt,
        )
        assertNull(vm.streamingState.value.streamingMessage)
        coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } returns rest
        repeat(2) {
            vm.syncCurrentSession()
            runCurrent()
            assertEquals(listOf(full, full), vm.footerPayloads())
            assertEquals(
                2000L,
                vm.uiState.value.responseLedger.responses
                    .single { it.id == responseId }
                    .finishedAt,
            )
            assertEquals(
                "terminal-receipt",
                vm.uiState.value.responseLedger.responses
                    .single {
                        it.id == responseId
                    }.authority.opening
                    ?.rowBindingSource,
            )
            val rendered =
                com.m57.hermescontrol.ui.chat.fullbleed
                    .groupIntoTurnsWithStreaming(
                        vm.uiState.value.messages,
                        vm.streamingState.value.streamingMessage,
                        vm.uiState.value.responseLedger,
                    ).filterIsInstance<com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn.Agent>()
                    .flatMap { it.entries }
                    .filterIsInstance<com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry.Prose>()
                    .map { it.message }
            assertEquals(
                "One full rendered answer per distinct owner, never prefix+full duplication",
                2,
                rendered.count {
                    it.content ==
                        full
                },
            )
            assertTrue(rendered.none { it.content == full.take(12) })
        }
        assertEquals(
            "No resend after replay and repeated public sync",
            previousSends + 1,
            sent.count {
                (it["method"] as JsonPrimitive).content ==
                    WsMethods.PROMPT_SUBMIT
            },
        )
        verify(exactly = 1) { HermesWsClient.connect() }
        androidx.lifecycle.ViewModelStore().apply {
            put("receipt", vm)
            clear()
        }
        HermesWsClient.rejectAllPending()
    }

    @Test
    fun mergeTranscriptWithLive_collapsesDuplicateIdsKeepingLatestMessage() {
        val stale = ChatMessage(id = "duplicate-id", role = MessageRole.ASSISTANT, content = "stale")
        val latest = stale.copy(content = "latest")

        val merged = mergeTranscriptWithLive(emptyList(), listOf(stale, latest))

        assertEquals(listOf(latest), merged)
    }

    @Test
    fun mergeTranscriptWithLive_matchingUserContentCollapsesLocalAndRestCopies() {
        // /queue bubbles show the stripped queued text, so the optimistic
        // local copy and the later REST echo share content — the sync merge
        // must collapse them into one row instead of rendering a duplicate
        // below its answer (PR #892 follow-up).
        val local =
            ChatMessage(
                id = "ws-local-1",
                role = MessageRole.USER,
                content = "do the thing",
                timestamp = 100L,
            )
        val rest =
            ChatMessage(
                id = "rest-sess-5",
                role = MessageRole.USER,
                content = "do the thing",
                timestamp = 100L,
            )

        val merged = mergeTranscriptWithLive(listOf(rest), listOf(local))

        assertEquals(1, merged.size)
        // The richer local copy wins when both sides carry the same content.
        assertEquals("ws-local-1", merged.single().id)
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
        receiptViewModels.forEach { vm ->
            androidx.lifecycle.ViewModelStore().apply {
                put("receipt", vm)
                clear()
            }
        }
        if (receiptViewModels.isNotEmpty()) {
            HermesWsClient.rejectAllPending()
            HermesWsClient.disconnect(clearPendingMessages = true)
        }
        Dispatchers.resetMain()
        attachmentCacheDir.deleteRecursively()
        unmockkAll()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Preserve the shared fixture's original JVM signature used by focused companion tests. */
    private fun createViewModel(startCleanup: Boolean): ChatViewModel =
        createViewModel(startCleanup, fakeSubmission, realTokenBuffering = false)

    /** Create a ViewModel with the fake repo injected directly. */
    private fun createViewModel(
        startCleanup: Boolean = false,
        transport: com.m57.hermescontrol.data.transport.HermesSubmissionTransport = fakeSubmission,
        realTokenBuffering: Boolean = false,
        specialistsRepository: SpecialistsRepository? = null,
    ): ChatViewModel =
        // Both dispatchers injected — a real ioDispatcher would race the
        // test scheduler (repo writes hop it) and shuffle RPC ordering.
        ChatViewModel(
            app,
            startCleanup,
            fakeRepo,
            fakeSlashUsageStore,
            testDispatcher,
            testDispatcher,
            fakeDraftStore,
            transport,
            attentionTransport,
            clock = { fixtureClock },
            streamingTestEnvironment = if (realTokenBuffering) ({ false }) else null,
            streamingTokenClock = { fixtureClock },
            specialistsRepositoryOverride = specialistsRepository,
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
        transport: com.m57.hermescontrol.data.transport.HermesSubmissionTransport = fakeSubmission,
    ): Pair<ChatViewModel, String> {
        val viewModel = createViewModel(startCleanup, transport)
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

    @Test fun lifecycleCoverage_partialReplayAfterGap() = runTest { coverageIndependentReplay("partial", "gap") }

    @Test fun lifecycleCoverage_transformedReplayAfterGap() =
        runTest {
            coverageIndependentReplay(
                "transformed",
                "gap",
            )
        }

    @Test fun lifecycleCoverage_errorReplayAfterGap() = runTest { coverageIndependentReplay("error", "gap") }

    @Test fun lifecycleCoverage_partialReplayAfterCanonicalRecovery() =
        runTest {
            coverageIndependentReplay("partial", "canonical")
        }

    @Test fun lifecycleCoverage_transformedReplayAfterCanonicalRecovery() =
        runTest {
            coverageIndependentReplay("transformed", "canonical")
        }

    @Test fun lifecycleCoverage_errorReplayAfterCanonicalRecovery() =
        runTest {
            coverageIndependentReplay("error", "canonical")
        }

    @Test fun lifecycleCoverage_partialReplayBeforeBodyMaterialization() =
        runTest {
            coverageIndependentReplay("partial", "bodyless")
        }

    @Test fun lifecycleCoverage_transformedReplayBeforeBodyMaterialization() =
        runTest {
            coverageIndependentReplay("transformed", "bodyless")
        }

    @Test fun lifecycleCoverage_errorReplayBeforeBodyMaterialization() =
        runTest {
            coverageIndependentReplay("error", "bodyless")
        }

    @Test fun lifecycleCoverage_safeReplayAndCanonicalRecoveryRemainIdempotent() =
        runTest {
            coverageIndependentReplay(null, "gap")
        }

    private suspend fun TestScope.coverageIndependentReplay(
        flag: String?,
        stage: String,
    ) {
        val (vm, session) = createViewModelWithSession()
        val scope = fakeSubmission.scope
        fakeSubmission.nextPromptResult =
            SubmissionResult.Confirmed(
                "exact-opening",
                com.m57.hermescontrol.domain.hermes.PromptAcceptance.STREAMING,
                com.m57.hermescontrol.domain.hermes
                    .PromptEvidence(userRowId = 10),
            )
        vm.sendMessage("first")
        advanceUntilIdle()
        emitEvent(WsEvent.MessageStart(session))
        if (stage == "bodyless") {
            emitEvent(WsEvent.ThinkingDelta("working", session))
        } else {
            emitEvent(WsEvent.MessageInterim("prefix", false, session))
        }
        fixtureClock = 2000L
        emitEvent(
            WsEvent.MessageComplete(
                if (stage == "bodyless") "" else "answer",
                session,
                persistedTurn =
                    com.m57.hermescontrol.data.ws.PersistedTurn(
                        rowIds = listOf(10, 12),
                        complete = true,
                        userRowId = 10,
                        finalAssistantRowId = 12,
                    ),
            ),
        )
        advanceUntilIdle()
        val firstState = vm.uiState.value
        val first = firstState.responseLedger.responses.single()
        assertEquals(10L, first.authority.opening!!.userRowId)
        assertEquals(12L, first.canonicalFinalAssistantRowId)
        assertEquals(
            listOf(10L, 12L),
            first.authority.terminals
                .single()
                .rowIds,
        )
        assertEquals(2000L, first.finishedAt)
        assertTrue(vm.streamingState.value.turnEnded)
        assertFalse(firstState.responseLedger.requiresCorrelation)
        if (stage == "bodyless") {
            assertTrue("A real empty terminal has no assistant members", first.messageIds.isEmpty())
            assertTrue(vm.footerPayloads().isEmpty())
        } else {
            assertEquals(
                ReplyAuthorityDecision.ReadyLocal,
                first.authorityDecision(firstState.messages.map { it.id }.toSet()),
            )
            assertEquals(listOf("prefix\n\nanswer"), vm.footerPayloads())
        }
        val raw =
            listOf(
                com.m57.hermescontrol.data.model.SessionMessage(
                    id = 10,
                    role = "user",
                    content = JsonPrimitive("first"),
                    // Explicit wire times prevent mapper wall-clock fallbacks on repeated pages.
                    timestamp = JsonPrimitive(1),
                ),
                com.m57.hermescontrol.data.model.SessionMessage(
                    id = 12,
                    role = "assistant",
                    content = JsonPrimitive("prefix\n\nanswer"),
                    timestamp = JsonPrimitive(1_700_000_000_000L),
                ),
            )
        val gap =
            listOf(
                raw.first(),
                com.m57.hermescontrol.data.model.SessionMessage(
                    id = null,
                    role = "assistant",
                    content = JsonPrimitive(""),
                ),
                raw.last(),
            )
        var reads = 0

        suspend fun hydrate(page: List<com.m57.hermescontrol.data.model.SessionMessage>) {
            coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } coAnswers {
                reads++
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages = page,
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                order = "latest",
                                offset = 0,
                                returned = page.size,
                            ),
                    ),
                )
            }
            val previousReads = reads
            vm.refreshCurrentSession()
            advanceUntilIdle()
            assertEquals("The real REST hydration producer must run", previousReads + 1, reads)
            val state = vm.uiState.value
            assertEquals(scope, fakeSubmission.scope)
            assertEquals(session, state.currentSessionId)
            assertFalse(state.responseStatusUnknown)
            if (stage == "bodyless") {
                assertTrue(
                    "First materialization of unowned assistant prose retains the history fence",
                    state.responseLedger.requiresCorrelation,
                )
            } else {
                assertFalse(state.responseLedger.requiresCorrelation)
            }
            assertEquals(firstState.responseLedger.pendingSubmissions, state.responseLedger.pendingSubmissions)
            assertEquals(
                first.authority.opening,
                state.responseLedger.responses
                    .single()
                    .authority.opening,
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            // createViewModel connects once; hydration must never reconnect.
            verify(exactly = 1) { HermesWsClient.connect() }
        }
        if (stage != "bodyless") {
            hydrate(gap)
            val gapState = vm.uiState.value
            val record = gapState.responseLedger.responses.single()
            assertNull("Missing raw address clears local proof despite filtered empty body", record.authority.local)
            assertNull(record.authority.canonical)
            assertEquals(
                ReplyAuthorityDecision.AwaitingCoverage,
                record.authorityDecision(gapState.messages.map { it.id }.toSet()),
            )
            assertTrue(vm.footerPayloads().isEmpty())
            if (stage == "canonical") {
                hydrate(raw)
                val recovered = vm.uiState.value
                val ready = recovered.responseLedger.responses.single()
                assertNull("Recovery must not recreate the local witness", ready.authority.local)
                assertEquals(
                    ReplyAuthorityDecision.ReadyCanonical,
                    ready.authorityDecision(recovered.messages.map { it.id }.toSet()),
                )
                assertEquals(listOf("prefix\n\nanswer"), vm.footerPayloads())
            }
        }
        val before = vm.uiState.value
        val originalBodies = before.messages
        val streaming = vm.streamingState.value
        val terminals =
            before.responseLedger.responses
                .single()
                .authority.terminals
        fixtureClock = 9000L
        val replay =
            WsEvent.MessageComplete(
                if (flag == null) "answer" else "must not replace body",
                session,
                partial = if (flag == "partial") true else null,
                responseTransformed = if (flag == "transformed") true else null,
                status = if (flag == "error") "error" else null,
            )
        emitEvent(replay)
        advanceUntilIdle()
        val after = vm.uiState.value
        val enriched = after.responseLedger.responses.single()
        if (flag == null) {
            assertEquals("Safe receipt-free replay is idempotent", before.responseLedger, after.responseLedger)
        } else {
            assertTrue(
                "Unsafe $flag replay must survive absent body coverage ($stage)",
                enriched.authority.terminals
                    .any {
                        !it.safe
                    },
            )
            assertEquals(
                ReplyAuthorityDecision.TerminalIneligible,
                enriched.authorityDecision(
                    after.messages
                        .map {
                            it.id
                        }.toSet(),
                ),
            )
            assertTrue(vm.footerPayloads().isEmpty())
            assertEquals(terminals.size + 1, enriched.authority.terminals.size)
        }
        assertEquals(first.finishedAt, enriched.finishedAt)
        assertEquals(first.authority.terminals.first(), enriched.authority.terminals.first())
        assertEquals(first.authority.opening, enriched.authority.opening)
        assertEquals(originalBodies, after.messages)
        assertEquals(
            "Enrichment must bypass every reducer/UI/queue side effect",
            before.copy(responseLedger = after.responseLedger),
            after,
        )
        if (flag != null) {
            assertEquals(streaming, vm.streamingState.value)
        } else {
            // Existing safe-replay reducer refreshes only the hydrated final-body alias.
            assertEquals(
                streaming.copy(finalizedMessage = vm.streamingState.value.finalizedMessage),
                vm.streamingState.value,
            )
        }
        val retained = enriched.authority.terminals
        emitEvent(WsEvent.MessageDone(session))
        emitEvent(WsEvent.MessageComplete(if (stage == "bodyless") "" else "answer", session))
        advanceUntilIdle()
        assertEquals(
            retained,
            vm.uiState.value.responseLedger.responses
                .single()
                .authority.terminals,
        )
        val recoveryStreaming = vm.streamingState.value
        for (page in listOf(raw, raw, emptyList(), raw)) {
            val bodies = vm.uiState.value.messages
            hydrate(page)
            val state = vm.uiState.value
            val record = state.responseLedger.responses.single()
            assertEquals(retained, record.authority.terminals)
            assertEquals(first.authority.opening, record.authority.opening)
            assertEquals(first.finishedAt, record.finishedAt)
            assertTrue(record.canonicalComplete)
            assertEquals(12L, record.canonicalFinalAssistantRowId)
            if (flag != null) {
                assertEquals(recoveryStreaming, vm.streamingState.value)
            } else {
                // Safe canonical replacement may refresh finalizedMessage, not execution liveness.
                assertTrue(vm.streamingState.value.turnEnded)
                assertNull(vm.streamingState.value.streamingMessage)
                assertFalse(vm.streamingState.value.isThinking)
            }
            assertEquals(before.outgoingDrafts, state.outgoingDrafts)
            assertEquals(before.isAgentTyping, state.isAgentTyping)
            assertTrue(state.messages.any { it.content == "prefix\n\nanswer" })
            if (flag == null) {
                assertEquals(
                    ReplyAuthorityDecision.ReadyCanonical,
                    record.authorityDecision(state.messages.map { it.id }.toSet()),
                )
                assertEquals(listOf("prefix\n\nanswer"), vm.footerPayloads())
            } else {
                assertEquals(
                    ReplyAuthorityDecision.TerminalIneligible,
                    record.authorityDecision(state.messages.map { it.id }.toSet()),
                )
                assertEquals(
                    "Unsafe recovery cannot replace response membership",
                    enriched.messageIds,
                    record.messageIds,
                )
                assertEquals(
                    "Unsafe recovery must retain the existing bodies exactly",
                    bodies,
                    state.messages.filter { incoming -> bodies.any { it.id == incoming.id } },
                )
                assertTrue(vm.footerPayloads().isEmpty())
            }
        }
    }

    @Test fun lifecycleReview_receiptFreePartialReplayRevokesFirstFooter() =
        runTest {
            unsafeReceiptFreeReplay(
                "partial",
            )
        }

    @Test fun lifecycleReview_receiptFreeTransformedReplayRevokesFirstFooter() =
        runTest { unsafeReceiptFreeReplay("transformed") }

    @Test fun lifecycleReview_receiptFreeErrorReplayRevokesFirstFooter() = runTest { unsafeReceiptFreeReplay("error") }

    private suspend fun TestScope.unsafeReceiptFreeReplay(flag: String) {
        val (vm, session) = createViewModelWithSession()
        fakeSubmission.addressedPromptAcks = true
        vm.sendMessage("first")
        advanceUntilIdle()
        emitEvent(WsEvent.MessageStart(session))
        emitEvent(WsEvent.MessageInterim("prefix", false, session))
        fixtureClock = 2000L
        emitEvent(WsEvent.MessageComplete("answer", session))
        advanceUntilIdle()
        assertEquals(listOf("prefix\n\nanswer"), vm.footerPayloads())
        val before = vm.uiState.value
        val streaming = vm.streamingState.value
        val first = before.responseLedger.responses.single()
        assertNotNull("The real guarded STREAMING ACK admitted this opening", first.authority.opening)
        assertEquals(ReplyAuthorityDecision.ReadyLocal, first.authorityDecision(before.messages.map { it.id }.toSet()))
        assertEquals(2000L, first.finishedAt)
        fixtureClock = 9000L
        val unsafe =
            WsEvent.MessageComplete(
                "must not replace body",
                session,
                partial = if (flag == "partial") true else null,
                responseTransformed = if (flag == "transformed") true else null,
                status = if (flag == "error") "error" else null,
            )
        emitEvent(unsafe.copy(sessionId = "unrelated-session"))
        advanceUntilIdle()
        assertEquals(before.responseLedger, vm.uiState.value.responseLedger)
        emitEvent(unsafe)
        advanceUntilIdle()
        assertTrue(
            "Unsafe receipt-free replay must revoke the first reply footer: $flag",
            vm.footerPayloads().isEmpty(),
        )
        val after = vm.uiState.value
        val enriched = after.responseLedger.responses.single()
        assertEquals(
            ReplyAuthorityDecision.TerminalIneligible,
            enriched.authorityDecision(
                after.messages
                    .map {
                        it.id
                    }.toSet(),
            ),
        )
        assertEquals(first.finishedAt, enriched.finishedAt)
        assertEquals(first.authority.terminals.first(), enriched.authority.terminals.first())
        assertEquals(first.authority.opening, enriched.authority.opening)
        assertEquals(before.messages, after.messages)
        assertEquals(streaming, vm.streamingState.value)
        assertEquals(before.isAgentTyping, after.isAgentTyping)
        assertEquals(before.outgoingDrafts, after.outgoingDrafts)
        verify(exactly = 1) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        emitEvent(WsEvent.MessageDone(session))
        emitEvent(WsEvent.MessageComplete("answer", session))
        advanceUntilIdle()
        assertTrue("Safe/missing replay fields cannot restore copy", vm.footerPayloads().isEmpty())
        assertEquals(
            first.finishedAt,
            vm.uiState.value.responseLedger.responses
                .single()
                .finishedAt,
        )
        // Real addressed replay + a complete raw page materialize readable bodies,
        // but unsafe safety facts must still deny canonical copy authority.
        completeAddressed(vm, session, "answer")
        advanceUntilIdle()
        val root =
            first.authority.opening!!
                .userRowId!!
                .toInt()
        val raw =
            listOf(
                com.m57.hermescontrol.data.model
                    .SessionMessage(id = root, role = "user", content = JsonPrimitive("first")),
                com.m57.hermescontrol.data.model.SessionMessage(
                    id = root + 1,
                    role = "assistant",
                    content = JsonPrimitive("prefix\n\nanswer"),
                ),
            )
        var hydrated = vm.uiState.value
        for (page in listOf(raw, raw, emptyList(), raw)) {
            hydrated = reconcileTranscript(hydrated, fakeSubmission.scope.draftTargetKey(), session, page, 0, true)
            val record = hydrated.responseLedger.responses.single()
            assertTrue("The complete wire receipt remains literal", record.canonicalComplete)
            assertTrue(
                "Hydration retains the canonical body",
                hydrated.messages.any { it.content == "prefix\n\nanswer" },
            )
            assertTrue("The original local prefix remains readable", hydrated.messages.any { it.content == "prefix" })
            assertEquals(
                ReplyAuthorityDecision.TerminalIneligible,
                record.authorityDecision(
                    hydrated.messages
                        .map {
                            it.id
                        }.toSet(),
                ),
            )
            assertEquals(first.finishedAt, record.finishedAt)
            assertEquals(first.authority.terminals.first(), record.authority.terminals.first())
            assertTrue(record.authority.terminals.any { !it.safe })
            assertTrue(
                com.m57.hermescontrol.ui.chat.fullbleed
                    .groupIntoTurns(hydrated.messages, hydrated.responseLedger)
                    .replyFooters(replyLifecycle(hydrated, StreamingState()))
                    .filterNotNull()
                    .isEmpty(),
            )
        }
    }

    @Test fun lifecycleReview_safeAddressedReplayProvidesCanonicalRecoveryControl() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("prefix", false, session))
            fixtureClock = 2000L
            emitEvent(WsEvent.MessageComplete("answer", session))
            advanceUntilIdle()
            assertEquals(listOf("prefix\n\nanswer"), vm.footerPayloads())
            val first =
                vm.uiState.value.responseLedger.responses
                    .single()
            fixtureClock = 9000L
            completeAddressed(vm, session, "answer")
            advanceUntilIdle()
            val root =
                first.authority.opening!!
                    .userRowId!!
                    .toInt()
            val raw =
                listOf(
                    com.m57.hermescontrol.data.model.SessionMessage(
                        id = root,
                        role = "user",
                        content = JsonPrimitive("first"),
                    ),
                    com.m57.hermescontrol.data.model.SessionMessage(
                        id = root + 1,
                        role = "assistant",
                        content = JsonPrimitive("prefix\n\nanswer"),
                    ),
                )
            var hydrated = vm.uiState.value
            for (page in listOf(raw, raw, emptyList(), raw)) {
                hydrated = reconcileTranscript(hydrated, fakeSubmission.scope.draftTargetKey(), session, page, 0, true)
                val record = hydrated.responseLedger.responses.single()
                assertNotNull(record.authority.canonical)
                assertEquals(
                    ReplyAuthorityDecision.ReadyCanonical,
                    record.authorityDecision(hydrated.messages.map { it.id }.toSet()),
                )
                assertEquals(first.finishedAt, record.finishedAt)
                assertEquals(first.authority.terminals.first(), record.authority.terminals.first())
            }
        }

    @Test fun lifecycleReview_notSentRetryCompleteCommitsBeforeQueueRelease() =
        runTest {
            notSentRetryTerminal(
                done = false,
            )
        }

    @Test fun lifecycleReview_notSentRetryDoneCommitsBeforeQueueRelease() =
        runTest {
            notSentRetryTerminal(
                done = true,
            )
        }

    private suspend fun TestScope.notSentRetryTerminal(done: Boolean) {
        val (vm, session) = createViewModelWithSession()
        fakeSubmission.nextPromptResult = SubmissionResult.NotSent
        vm.sendMessage("definitely unsent")
        advanceUntilIdle()
        val unsent = fakeDraftStore.drafts.value.single()
        assertEquals(DraftState.DRAFT, unsent.state)
        assertTrue(
            vm.uiState.value.messages
                .none { it.content == unsent.text },
        )
        fakeSubmission.nextPromptResult = null
        vm.restoreDraft(unsent.id)
        advanceUntilIdle()
        vm.sendMessage("retry")
        advanceUntilIdle()
        emitEvent(WsEvent.MessageStart(session))
        emitEvent(WsEvent.MessageInterim("retry prefix", false, session))
        advanceUntilIdle()
        vm.sendMessage("/queue queued after retry")
        advanceUntilIdle()
        verify(exactly = 0) { HermesWsClient.sendMessage(any(), "queued after retry", any(), any()) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fakeRepo.dao.beforeSnapshotSave = {
            entered.complete(Unit)
            release.await()
        }
        fixtureClock = 3000L
        emitEvent(if (done) WsEvent.MessageDone(session) else WsEvent.MessageComplete("retry final", session))
        runCurrent()
        // Never await a gate that the bug may prevent reaching.
        assertTrue("Receipt-free retry terminal must reach the structural commit", entered.isCompleted)
        verify(exactly = 0) { HermesWsClient.sendMessage(any(), "queued after retry", any(), any()) }
        val finished =
            vm.uiState.value.responseLedger.responses
                .single { it.authority.opening != null }
        assertEquals(3000L, finished.finishedAt)
        assertEquals(1, finished.authority.terminals.size)
        assertEquals(listOf(if (done) "retry prefix" else "retry prefix\n\nretry final"), vm.footerPayloads())
        fakeRepo.dao.beforeSnapshotSave = null
        release.complete(Unit)
        advanceUntilIdle()
        verify(exactly = 1) { HermesWsClient.sendMessage(session, "queued after retry", any(), true) }
        emitEvent(WsEvent.MessageDone(session))
        advanceUntilIdle()
        verify(exactly = 1) { HermesWsClient.sendMessage(session, "queued after retry", any(), true) }
        assertTrue(fakeDraftStore.drafts.value.isEmpty())
    }

    @Test fun lifecycleReview_repeatedNotSentRetiresOnlyEmptyProvisionalIdentity() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            repeat(2) {
                fakeSubmission.nextPromptResult = SubmissionResult.NotSent
                vm.sendMessage("unsent $it")
                advanceUntilIdle()
                assertTrue(
                    "Definitely unsent empty association is not response history",
                    vm.uiState.value.responseLedger.responses
                        .isEmpty(),
                )
                assertNull(vm.uiState.value.responseLedger.activeId)
                assertFalse(vm.uiState.value.responseLedger.requiresCorrelation)
                assertTrue(
                    vm.uiState.value.responseLedger.pendingSubmissions
                        .isEmpty(),
                )
                assertTrue(
                    vm.uiState.value.messages
                        .none { row -> row.role == MessageRole.USER },
                )
            }
            assertEquals(listOf("unsent 0", "unsent 1"), fakeDraftStore.drafts.value.map { it.text })
        }

    @Test fun lifecycleReview_notSentAfterRealHistoryCannotResetCorrelationFence() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("real first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageComplete("first answer", session))
            advanceUntilIdle()
            val first =
                vm.uiState.value.responseLedger.responses
                    .single()
            fakeSubmission.nextPromptResult = SubmissionResult.NotSent
            vm.sendMessage("unsent second")
            advanceUntilIdle()
            assertEquals(
                first,
                vm.uiState.value.responseLedger.responses
                    .first(),
            )
            fakeSubmission.nextPromptResult = null
            vm.sendMessage("real later")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("later prefix", false, session))
            advanceUntilIdle()
            vm.sendMessage("/queue must stay queued")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageDone(session))
            emitEvent(WsEvent.MessageComplete("unattributed", session, partial = true))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.responseLedger.requiresCorrelation)
            assertEquals(
                first,
                vm.uiState.value.responseLedger.responses
                    .first(),
            )
            assertTrue(
                vm.uiState.value.responseLedger.responses
                    .last()
                    .authority.terminals
                    .isEmpty(),
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "must stay queued", any(), any()) }
        }

    @Test fun lifecycleReview_notSentAfterObservedWorkKeepsAmbiguousBodyAndFence() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fakeSubmission.beforePrompt = {
                entered.complete(Unit)
                release.await()
            }
            fakeSubmission.nextPromptResult = SubmissionResult.NotSent
            vm.sendMessage("locally unsent")
            runCurrent()
            assertTrue(entered.isCompleted)
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("unattributed readable work", false, session))
            runCurrent()
            val provisional =
                vm.uiState.value.responseLedger.responses
                    .single()
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                provisional,
                vm.uiState.value.responseLedger.responses
                    .single(),
            )
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "unattributed readable work" },
            )
            assertNull(provisional.authority.opening)
            assertTrue(vm.uiState.value.isAgentTyping)
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            fakeSubmission.beforePrompt = null
            fakeSubmission.nextPromptResult = null
            vm.sendMessage("next real send")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.responseLedger.requiresCorrelation)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "unattributed readable work" },
            )
        }

    @Test fun lifecycleReview_notSentSteerCannotRetireAcceptedQueuedIdentity() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "queued",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.QUEUED,
                )
            vm.sendMessage("server queued")
            advanceUntilIdle()
            val queued =
                vm.uiState.value.responseLedger.responses
                    .single()
            assertNotNull(queued.authority.queued)
            fakeSubmission.nextPromptResult = SubmissionResult.NotSent
            vm.sendMessage("unsent steer", steer = true)
            advanceUntilIdle()
            assertEquals(
                queued,
                vm.uiState.value.responseLedger.responses
                    .single(),
            )
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.responseLedger.responses
                    .single()
                    .authority.terminals
                    .isEmpty(),
            )
            assertTrue(vm.uiState.value.responseLedger.requiresCorrelation)
        }

    @Test fun lifecycleReview_unknownAttemptIsNeverRetiredAsDefinitelyUnsent() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.nextPromptResult = SubmissionResult.Unknown
            vm.sendMessage("possibly delivered")
            advanceUntilIdle()
            val unknown = vm.uiState.value.responseLedger
            assertEquals(1, unknown.responses.size)
            assertTrue(unknown.pendingSubmissions.isNotEmpty())
            assertEquals(
                DraftState.UNKNOWN,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            assertEquals(
                unknown.responses.single().id,
                vm.uiState.value.responseLedger.responses
                    .single()
                    .id,
            )
            assertTrue(
                vm.uiState.value.responseLedger.responses
                    .single()
                    .authority.terminals
                    .isEmpty(),
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(any(), "possibly delivered", any(), any()) }
        }

    @Test fun lifecycleReview_staleReceiptFreeReplayCannotEnrichCompletedFirstReply() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageComplete("answer", session))
            advanceUntilIdle()
            val before = vm.uiState.value
            val streaming = vm.streamingState.value
            mockScopedEvents.emit(
                ScopedWsEvent(
                    fakeSubmission.scope.copy(generation = fakeSubmission.scope.generation + 1),
                    ++eventOrdinal,
                    WsEvent.MessageComplete("stale", session, partial = true),
                ),
            )
            advanceUntilIdle()
            assertEquals(before.responseLedger, vm.uiState.value.responseLedger)
            assertEquals(before.messages, vm.uiState.value.messages)
            assertEquals(streaming, vm.streamingState.value)
            assertEquals(listOf("answer"), vm.footerPayloads())
        }

    @Test fun authority_queuedContinuationHasOwnIdentityAndExactEndSettlesOnlyItsExecution() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("A")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("A prefix", false, session))
            advanceUntilIdle()
            val a = vm.uiState.value.responseLedger.activeId
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "queue-rpc",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.QUEUED,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(submissionId = "queued-P"),
                )
            vm.sendMessage("server queued P", steer = true)
            advanceUntilIdle()
            val queued =
                vm.uiState.value.responseLedger.responses
                    .single { it.authority.queued != null }
            assertNotEquals(a, queued.id)
            assertNull(queued.authority.opening)
            completeAddressed(vm, session, "A end")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("unclassified queued prose", false, session))
            emitEvent(
                WsEvent.MessageComplete(
                    "P end",
                    session,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            listOf(20, 21),
                            true,
                            20,
                            finalAssistantRowId = 21,
                            submissionId = "queued-P",
                        ),
                ),
            )
            advanceUntilIdle()
            assertFalse(
                "Exact queued terminal ends execution even without a full-copy delivery witness",
                vm.uiState.value.isAgentTyping,
            )
            assertNotNull(
                vm.uiState.value.responseLedger.responses
                    .single { it.id == queued.id }
                    .authority.opening,
            )
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "unclassified queued prose" },
            )
            assertEquals(listOf("A prefix\n\nA end"), vm.footerPayloads())
        }

    @Test fun authority_unsafeCopyStillReleasesQueueExactlyOnceAfterTerminalCommit() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("/queue queued next")
            advanceUntilIdle()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = {
                entered.complete(Unit)
                release.await()
            }
            emitEvent(
                WsEvent.MessageComplete(
                    "unsafe answer",
                    session,
                    partial = true,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            listOf(1010, 1011),
                            true,
                            1010,
                            finalAssistantRowId = 1011,
                        ),
                ),
            )
            runCurrent()
            entered.await()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "queued next", any(), any()) }
            fakeRepo.dao.beforeSnapshotSave = null
            release.complete(Unit)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "queued next", any(), true) }
            assertTrue(vm.footerPayloads().isEmpty())
            assertTrue(
                vm.uiState.value.responseLedger.responses
                    .first()
                    .authority.terminals
                    .single()
                    .complete == true,
            )
            emitEvent(WsEvent.MessageStart(session))
            completeAddressed(vm, session, "safe B")
            advanceUntilIdle()
            assertEquals(listOf("safe B"), vm.footerPayloads())
        }

    @Test fun authority_notSentSteerReleasesOnlyItsPendingCopyBlock() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("prefix", false, session))
            advanceUntilIdle()
            fakeSubmission.nextPromptResult = SubmissionResult.NotSent
            vm.sendMessage("unsent steer", steer = true)
            advanceUntilIdle()
            emitEvent(WsEvent.MessageComplete("final", session))
            advanceUntilIdle()
            assertEquals(listOf("prefix\n\nfinal"), vm.footerPayloads())
        }

    @Test fun authority_initialNoBubbleBindingKeepsTheCapturedOpeningAttempt() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            fakeSubmission.addressedPromptAcks = true
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            ChatViewModel::class.java
                .getDeclaredMethod(
                    "submitPrompt",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                .invoke(vm, "initial expanded prompt", false, false)
            runCurrent()
            val captured = vm.uiState.value.responseLedger.activeId
            assertNotNull(captured)
            emitEvent(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-123")))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage("session-123", "initial expanded prompt", any(), any()) }
            assertTrue(
                "The guarded ACK must produce opening evidence",
                vm.uiState.value.responseLedger.responses.any {
                    it.authority.opening !=
                        null
                },
            )
            val admitted =
                vm.uiState.value.responseLedger.responses
                    .single { it.authority.opening != null }
            assertEquals(captured, admitted.id)
            assertEquals(1010L, admitted.authority.opening!!.userRowId)
            assertTrue(admitted.userIds.isEmpty())
            emitEvent(WsEvent.MessageStart("session-123"))
            completeAddressed(vm, "session-123", "initial answer")
            advanceUntilIdle()
            assertEquals(listOf("initial answer"), vm.footerPayloads())
        }

    @Test fun authority_noUserBubbleStillRecordsExactAdmission() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            ChatViewModel::class.java
                .getDeclaredMethod(
                    "submitPrompt",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                .invoke(vm, "expanded slash prompt", false, false)
            advanceUntilIdle()
            val accepted =
                vm.uiState.value.responseLedger.responses
                    .single()
            assertNotNull(accepted.authority.opening)
            assertEquals(1010L, accepted.authority.opening!!.userRowId)
            assertTrue(accepted.userIds.isEmpty())
            emitEvent(WsEvent.MessageStart(session))
            completeAddressed(vm, session, "slash answer")
            advanceUntilIdle()
            assertEquals(listOf("slash answer"), vm.footerPayloads())
        }

    @Test fun authority_exactQueuedSubmissionPromotesOnlyAtTerminalAndCanonicalProof() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "queued-rpc",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.QUEUED,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(submissionId = "queued-submission"),
                )
            vm.sendMessage("queued on server")
            advanceUntilIdle()
            assertNull(
                vm.uiState.value.responseLedger.responses
                    .single()
                    .authority.opening,
            )
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(
                WsEvent.MessageComplete(
                    "queued answer",
                    session,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            listOf(20, 21),
                            true,
                            20,
                            finalAssistantRowId = 21,
                            submissionId = "queued-submission",
                        ),
                ),
            )
            advanceUntilIdle()
            assertNotNull(
                vm.uiState.value.responseLedger.responses
                    .single()
                    .authority.opening,
            )
            val raw =
                listOf(
                    com.m57.hermescontrol.data.model.SessionMessage(
                        id = 20,
                        role = "user",
                        content = JsonPrimitive("queued on server"),
                    ),
                    com.m57.hermescontrol.data.model.SessionMessage(
                        id = 21,
                        role = "assistant",
                        content = JsonPrimitive("queued answer"),
                    ),
                )
            val canonical =
                reconcileTranscript(vm.uiState.value, fakeSubmission.scope.draftTargetKey(), session, raw, 0, true)
            assertTrue(
                canonical.responseLedger.responses
                    .single()
                    .authorityDecision(
                        canonical.messages
                            .map {
                                it.id
                            }.toSet(),
                    ).copyReady,
            )
        }

    @Test fun authority_noKeyQueuedAckAndSessionOnlyStartNeverPromote() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed("queued-rpc", com.m57.hermescontrol.domain.hermes.PromptAcceptance.QUEUED)
            vm.sendMessage("no keys")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("readable queued body", false, session))
            emitEvent(WsEvent.MessageComplete("unattributed end", session))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.responseLedger.responses
                    .all { it.authority.opening == null },
            )
            assertTrue(vm.footerPayloads().isEmpty())
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "readable queued body" },
            )
            assertTrue(
                "An unbound complete body remains readable without settling another owner",
                vm.uiState.value.messages.any {
                    it.content ==
                        "unattributed end"
                },
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "no keys", any(), any()) }
        }

    @Test fun authority_repeatedOriginContradictionRemainsWithoutFooter() =
        runTest {
            authorityContradiction(
                false,
                false,
            )
        }

    @Test fun authority_emptyAfterOriginContradictionRemainsWithoutFooter() =
        runTest {
            authorityContradiction(true, false)
        }

    @Test fun authority_coldDecodeDoesNotReleaseOriginQuarantine() = runTest { authorityContradiction(true, true) }

    private suspend fun TestScope.authorityContradiction(
        empty: Boolean,
        reopen: Boolean,
    ) {
        val (vm, session) = createViewModelWithSession()
        fakeSubmission.addressedPromptAcks = true
        vm.sendMessage("accepted ordinary opening")
        advanceUntilIdle()
        emitEvent(WsEvent.MessageStart(session))
        emitEvent(WsEvent.MessageInterim("interim", false, session))
        advanceUntilIdle()
        fixtureClock = 4321L
        completeAddressed(vm, session, "final")
        advanceUntilIdle()
        assertEquals(listOf("interim\n\nfinal"), vm.footerPayloads())
        val record =
            vm.uiState.value.responseLedger.responses
                .single()
        val root = record.userRowIds.first()
        val final = record.canonicalFinalAssistantRowId!!
        val target = fakeSubmission.scope.draftTargetKey()
        val raw =
            listOf(
                com.m57.hermescontrol.data.model.SessionMessage(
                    id = root.toInt(),
                    role = "user",
                    content = JsonPrimitive("accepted ordinary opening"),
                ),
                com.m57.hermescontrol.data.model.SessionMessage(
                    id = final.toInt(),
                    role = "assistant",
                    content = JsonPrimitive("interim\n\nfinal"),
                ),
            )

        fun footers(state: ChatUiState) =
            com.m57.hermescontrol.ui.chat.fullbleed
                .groupIntoTurns(state.messages, state.responseLedger)
                .replyFooters(replyLifecycle(state, StreamingState()))
                .filterNotNull()
        val valid = reconcileTranscript(vm.uiState.value, target, session, raw, 0, true)
        assertEquals(1, footers(valid).size)
        val tagged = raw.map { if (it.id?.toLong() == root) it.copy(display_kind = "steer") else it }
        var contradicted = reconcileTranscript(valid, target, session, tagged, 0, true)
        assertTrue("First conflict denies actual footer", footers(contradicted).isEmpty())
        assertTrue(
            "Wire complete remains literal",
            contradicted.responseLedger.responses
                .single()
                .canonicalComplete,
        )
        if (reopen) {
            val dao =
                com.m57.hermescontrol.ui.chat.fakes
                    .FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript(target, session)
            assertTrue(
                repo.save(
                    repo.prepare(
                        owner,
                        contradicted.messages,
                        contradicted.responseLedger,
                        contradicted.stagedCanonicalMessages,
                    ),
                ),
            )
            val (_, restored) = ChatPersistenceRepository(dao).openTranscript(target, session)
            assertEquals(contradicted.messages.map { it.content }, restored.messages.map { it.content })
            contradicted =
                contradicted.copy(
                    messages = restored.messages,
                    responseLedger = restored.payload.ledger,
                    stagedCanonicalMessages = restored.stagedMessages,
                )
        }
        val later = reconcileTranscript(contradicted, target, session, if (empty) emptyList() else tagged, 0, true)
        assertEquals(
            4321L,
            later.responseLedger.responses
                .single()
                .finishedAt,
        )
        assertTrue("Repeated/absent pages cannot restore revoked copy authority", footers(later).isEmpty())
    }

    private fun ChatViewModel.footerPayloads(): List<String> =
        com.m57.hermescontrol.ui.chat.fullbleed
            .groupIntoTurnsWithStreaming(
                uiState.value.messages,
                streamingState.value.streamingMessage,
                uiState.value.responseLedger,
            ).replyFooters(replyLifecycle(uiState.value, streamingState.value))
            .filterNotNull()
            .map { it.text }

    @Test
    fun successor76_normalizedClarifySnapshotSurvivesTranscriptAndScopedOrdinalFences() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("Synthetic clarification boundary")
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("Before input", alreadyStreamed = false, session))
            runCurrent()
            val outer = "outer-successor76-clarify"
            val event =
                com.m57.hermescontrol.data.ws.EventParser.parse(
                    com.m57.hermescontrol.data.remote.OkHttpProvider.json
                        .decodeFromString<com.m57.hermescontrol.data.ws.JsonRpcResponse>(
                            """{"id":"$outer","method":"clarify","params":{"session_id":"$session","questions":[{"qid":"locked","question":"Skipped?","choices":null},{"qid":"open","question":"Continue?"}],"answers":{"locked":null}}}""",
                        ),
                ) as WsEvent.ClarifyRequest
            val info = observeClarify(vm, event)
            val requestOrdinal = eventOrdinal
            val ref =
                com.m57.hermescontrol.domain.hermes.RequestRef(
                    fakeSubmission.scope,
                    com.m57.hermescontrol.domain.hermes
                        .SessionRef(session),
                    outer,
                    serverRequestId = outer,
                )
            assertEquals(ref, info.ref)
            assertEquals(
                com.m57.hermescontrol.domain.hermes
                    .ClarificationLock(null),
                info.questionLocks["locked"],
            )
            assertFalse(info.canRespond("locked"))
            assertTrue(info.canRespond("open"))
            assertFalse(vm.showsThinking())
            assertTrue(vm.footerPayloads().isEmpty())

            // A read started before the normalized live request cannot expire it.
            mockScopedEvents.emit(
                ScopedWsEvent(
                    fakeSubmission.scope,
                    ++eventOrdinal,
                    WsEvent.OpenRequestsSnapshot(session, requestOrdinal - 1, emptyList(), emptySet()),
                ),
            )
            runCurrent()
            assertTrue(
                vm.uiState.value.clarifyRequest!!
                    .canRespond("open"),
            )
            val cancel = WsEvent.ServerRequestCancel(outer, session, "clarify", "timeout")
            mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope, eventOrdinal, cancel))
            mockScopedEvents.emit(
                ScopedWsEvent(fakeSubmission.scope.copy(generation = 999), eventOrdinal + 100, cancel),
            )
            mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope, requestOrdinal, WsEvent.MessageDone(session)))
            runCurrent()
            assertTrue(
                "Stale ordinals and foreign sockets cannot settle current input",
                vm.uiState.value.clarifyRequest!!
                    .canRespond("open"),
            )

            emitEvent(WsEvent.ReasoningDelta("Synthetic late work", session))
            emitEvent(WsEvent.ToolComplete("synthetic", mapOf("tool_id" to "input-wait"), session))
            runCurrent()
            assertFalse("Transcript early-return handling must still honor normalized input wait", vm.showsThinking())
            assertTrue(vm.footerPayloads().isEmpty())
            mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, cancel))
            runCurrent()
            assertNull(vm.uiState.value.clarifyRequest)
            val expired =
                vm.uiState.value.messages
                    .single { it.clarifyInfo?.ref == ref }
                    .clarifyInfo!!
            assertEquals(AttentionStatus.EXPIRED, expired.questionStates["open"])
            assertEquals(
                com.m57.hermescontrol.domain.hermes
                    .ClarificationLock(null),
                expired.questionLocks["locked"],
            )
            vm.respondToClarifyBatch(expired, mapOf("open" to "late", "locked" to "late"))
            advanceUntilIdle()
            coVerify(exactly = 0) { attentionTransport.respondClarification(any(), any()) }
            assertTrue("Withdrawal of input is not whole-reply completion", vm.footerPayloads().isEmpty())
        }

    @Test
    fun successor76_modernApprovalResolvedAckKeepsEveryIndependentSegmentInScopedCache() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("Synthetic approval boundary")
            emitEvent(WsEvent.MessageStart(session))
            repeat(2) { emitEvent(WsEvent.MessageInterim("A", alreadyStreamed = false, session)) }
            runCurrent()
            val outer = "outer-successor76-approval"
            val inner = "local-successor76-approval"
            val event =
                com.m57.hermescontrol.data.ws.EventParser.parse(
                    com.m57.hermescontrol.data.remote.OkHttpProvider.json
                        .decodeFromString<com.m57.hermescontrol.data.ws.JsonRpcResponse>(
                            """{"id":"$outer","method":"approval","params":{"session_id":"$session","request_id":"$inner","command":"synthetic action","choices":["once","deny"]}}""",
                        ),
                ) as WsEvent.ApprovalRequest
            mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, event))
            runCurrent()
            val row =
                vm.uiState.value.messages
                    .single { it.approvalInfo != null }
            val ref =
                com.m57.hermescontrol.domain.hermes.RequestRef(
                    fakeSubmission.scope,
                    com.m57.hermescontrol.domain.hermes
                        .SessionRef(session),
                    inner,
                    serverRequestId = outer,
                )
            assertEquals(ref, row.approvalInfo!!.ref)
            assertFalse(vm.showsThinking())
            assertTrue(vm.footerPayloads().isEmpty())
            val ack = CompletableDeferred<SubmissionResult<Boolean>>()
            coEvery { attentionTransport.respondApproval(ref, "once") } coAnswers { ack.await() }
            vm.respondToApproval(row.id, "once")
            vm.respondToApproval(row.id, "once")
            runCurrent()
            coVerify(exactly = 1) { attentionTransport.respondApproval(ref, "once") }
            mockScopedEvents.emit(
                ScopedWsEvent(
                    fakeSubmission.scope,
                    ++eventOrdinal,
                    WsEvent.ServerRequestCancel(outer, session, "approval", "resolved"),
                ),
            )
            runCurrent()
            assertEquals(
                AttentionStatus.EXPIRED,
                vm.uiState.value.messages
                    .single { it.id == row.id }
                    .approvalInfo!!
                    .status,
            )
            assertTrue("Resolved cancellation is neither an ACK nor a reply terminal", vm.footerPayloads().isEmpty())
            ack.complete(SubmissionResult.Confirmed("synthetic-exact-ack", true))
            runCurrent()
            assertEquals(
                AttentionStatus.CONFIRMED,
                vm.uiState.value.messages
                    .single { it.id == row.id }
                    .approvalInfo!!
                    .status,
            )
            emitEvent(WsEvent.ToolComplete("synthetic", mapOf("tool_id" to "approval-wait"), session))
            runCurrent()
            assertTrue(vm.showsThinking())
            assertTrue(vm.footerPayloads().isEmpty())
            emitEvent(WsEvent.MessageInterim("AB", alreadyStreamed = false, session))
            runCurrent()
            fixtureClock = 7654L
            emitEvent(WsEvent.MessageComplete("C", session))
            advanceUntilIdle()
            val expected = listOf("A\n\nA\n\nAB\n\nC")
            assertEquals(
                listOf("A", "A", "AB", "C"),
                vm.uiState.value.messages
                    .filter {
                        it.role ==
                            MessageRole.ASSISTANT
                    }.map { it.content },
            )
            assertEquals(expected, vm.footerPayloads())
            assertFalse(vm.showsThinking())
            vm.sendMessage("Next independent question")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            advanceUntilIdle()
            assertEquals(expected, vm.footerPayloads())
            val snapshot = fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), session)
            val footers =
                com.m57.hermescontrol.ui.chat.fullbleed
                    .groupIntoTurns(snapshot.messages, snapshot.payload.ledger)
                    .replyFooters(
                        com.m57.hermescontrol.ui.chat.fullbleed
                            .ReplyLifecycle(ledger = snapshot.payload.ledger),
                    ).filterNotNull()
            assertEquals(expected, footers.map { it.text })
            assertEquals(listOf(7654L), footers.map { it.finishedAt })
            val foreign =
                fakeRepo.readTranscript(
                    fakeSubmission.scope.copy(serverIdentity = "other-synthetic-server").draftTargetKey(),
                    session,
                )
            assertTrue("The persisted response cannot leak across connection scope", foreign.messages.isEmpty())
            coVerify(exactly = 1) { attentionTransport.respondApproval(ref, "once") }
        }

    @Test
    fun replyBoundary_acceptedSteerSurvivesNextSendStartAndRoomRoundTrip() =
        runTest { acceptedSteerRoundTrip(unstreamed = false) }

    @Test
    fun replyBoundary_unstreamedInterimKeepsRoomAndNextSendOrder() =
        runTest { acceptedSteerRoundTrip(unstreamed = true) }

    private suspend fun kotlinx.coroutines.test.TestScope.acceptedSteerRoundTrip(unstreamed: Boolean) {
        val (vm, session) = createViewModelWithSession()
        vm.sendMessage("A")
        advanceUntilIdle()
        emitEvent(WsEvent.MessageStart(session))
        if (!unstreamed) emitEvent(WsEvent.MessageToken("A1", session))
        advanceUntilIdle()
        emitEvent(WsEvent.MessageInterim("A1", alreadyStreamed = !unstreamed, session))
        advanceUntilIdle()
        fakeSubmission.nextPromptResult =
            SubmissionResult.Confirmed(
                "accepted-steer",
                com.m57.hermescontrol.domain.hermes.PromptAcceptance.STEERED,
            )
        vm.sendMessage("correction", steer = true)
        advanceUntilIdle()
        assertEquals(
            "steer",
            vm.uiState.value.messages
                .single { it.content == "correction" }
                .displayKind,
        )
        assertEquals("steer", fakeRepo.loadMessages(session).single { it.content == "correction" }.displayKind)
        assertTrue(vm.footerPayloads().isEmpty())
        // The DAO orders by wall-clock milliseconds, not the coroutine test clock.
        emitEvent(WsEvent.MessageComplete("A2", session))
        advanceUntilIdle()
        assertEquals(listOf("A1\n\nA2"), vm.footerPayloads())
        fakeSubmission.nextPromptResult = null
        vm.sendMessage("normal next question")
        advanceUntilIdle()
        assertEquals(listOf("A1\n\nA2"), vm.footerPayloads())
        emitEvent(WsEvent.MessageStart(session))
        advanceUntilIdle()
        assertEquals(listOf("A1\n\nA2"), vm.footerPayloads())
        val restoredSnapshot = fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), session)
        val restored = restoredSnapshot.messages
        assertEquals(
            listOf("A1\n\nA2"),
            com.m57.hermescontrol.ui.chat.fullbleed
                .groupIntoTurns(restored, restoredSnapshot.payload.ledger)
                .replyFooters(
                    com.m57.hermescontrol.ui.chat.fullbleed
                        .ReplyLifecycle(ledger = restoredSnapshot.payload.ledger),
                ).filterNotNull()
                .map { it.text },
        )
        stubEmptySessionRests(session)
        coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    messages =
                        restored.mapIndexed { index, message ->
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = index + 1,
                                role = message.role.name.lowercase(),
                                content = JsonPrimitive(message.content),
                                timestamp =
                                    JsonPrimitive(message.timestamp / 1000.0),
                                display_kind = message.displayKind,
                            )
                        },
                    pagination =
                        com.m57.hermescontrol.data.model
                            .PaginationInfo(order = "latest", offset = 0),
                ),
            )
        val requests = captureRecoveryResumes()
        vm.switchSession(session)
        runCurrent()
        recoveryAck(requests.last(), true, session)
        advanceUntilIdle()
        assertEquals(listOf("A1\n\nA2"), vm.footerPayloads())
    }

    @Test
    fun replyBoundary_restSteerTagSurvivesRicherLocalMergeAndCacheDeduplication() =
        runTest {
            val local = ChatMessage(id = "local", role = MessageRole.USER, content = "correction", timestamp = 20L)
            val rest =
                mapServerMessages(
                    "s",
                    listOf(
                        com.m57.hermescontrol.data.model.SessionMessage(
                            id = 2,
                            role = "user",
                            content = JsonPrimitive("correction"),
                            timestamp = JsonPrimitive(0.02),
                            display_kind = "steer",
                        ),
                    ),
                    0,
                    true,
                    listOf(local),
                )
            val merged = mergeTranscriptWithLive(rest, listOf(local))
            assertEquals("local", merged.single().id)
            assertEquals("steer", merged.single().displayKind)
            fakeRepo.persistMessages(rest + local, "s")
            assertEquals("steer", dedupeCachedMessages(fakeRepo.loadMessages("s")).single().displayKind)
        }

    @Test
    fun replyBoundary_ambiguousRepeatedUserTextKeepsCanonicalRestIdentitiesAndTags() {
        val local =
            listOf(
                ChatMessage(id = "local-normal", role = MessageRole.USER, content = "same", timestamp = 10L),
                ChatMessage(
                    id = "local-steer",
                    role = MessageRole.USER,
                    content = "same",
                    timestamp = 20L,
                    displayKind = "steer",
                ),
            )
        val rest =
            listOf(
                local[0].copy(id = "rest-s-1", displayKind = "steer"),
                local[1].copy(id = "rest-s-2", displayKind = null),
            )
        assertEquals(rest, mergeTranscriptWithLive(rest, local))
        assertEquals(rest, dedupeCachedMessages(local + rest))
    }

    private suspend fun TestScope.restoreAcceptedSteer(
        restFirst: Boolean,
        running: Boolean?,
        legacyCache: Boolean = false,
    ) {
        val (old, session) = createViewModelWithSession()
        old.sendMessage("A")
        advanceUntilIdle()
        emitEvent(WsEvent.MessageStart(session))
        emitEvent(WsEvent.MessageToken("A1", session))
        advanceUntilIdle()
        emitEvent(WsEvent.MessageInterim("A1", alreadyStreamed = true, session))
        advanceUntilIdle()
        // This fixture's REST page below assigns addresses from the explicit
        // stored order. Supply that same address in the simulated steer ACK;
        // equal text alone must not identify the optimistic input.
        val steerRowId =
            old.uiState.value.messages.size
                .toLong() + 1L
        fakeSubmission.nextPromptResult =
            SubmissionResult.Confirmed(
                "accepted",
                com.m57.hermescontrol.domain.hermes.PromptAcceptance.REDIRECTED,
                com.m57.hermescontrol.domain.hermes
                    .PromptEvidence(userRowId = steerRowId),
            )
        old.sendMessage("correction", steer = true)
        advanceUntilIdle()
        val accepted = fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), session).messages
        assertEquals("steer", accepted.single { it.content == "correction" }.displayKind)
        androidx.lifecycle.ViewModelStore().apply {
            put("old", old)
            clear()
        }
        if (legacyCache) {
            // A real legacy fixture has no v9 state/ownership; never corrupt scoped v9 bodies directly.
            fakeRepo = FakeChatPersistenceRepository()
            fakeRepo.persistMessages(accepted.map { it.copy(displayKind = null) }, session)
        }
        stubEmptySessionRests(session)
        val page = CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
        coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } coAnswers
            { page.await() }
        val requests = captureRecoveryResumes()
        val restored = createViewModel()
        runCurrent()
        restored.switchSession(session)
        runCurrent()
        assertTrue(
            restored.uiState.value.messages
                .isNotEmpty(),
        )
        assertTrue("Room alone cannot complete an active/unknown response", restored.footerPayloads().isEmpty())
        val response =
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    messages =
                        accepted.mapIndexed { index, message ->
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = index + 1,
                                role = message.role.name.lowercase(),
                                content = JsonPrimitive(message.content),
                                timestamp = JsonPrimitive(message.timestamp / 1000.0),
                                display_kind = message.displayKind,
                            )
                        },
                    pagination =
                        com.m57.hermescontrol.data.model
                            .PaginationInfo(order = "latest", offset = 0),
                ),
            )

        suspend fun ack() {
            val fields = mutableMapOf<String, Any>("session_id" to "resumed-runtime", "resumed" to session)
            if (running != null) {
                fields["running"] = running
                fields["status"] = "streaming"
            }
            emitEvent(WsEvent.RpcResult(requests.last(), fields))
            runCurrent()
        }
        if (restFirst) {
            page.complete(response)
            runCurrent()
            assertTrue(restored.footerPayloads().isEmpty())
            ack()
        } else {
            ack()
            assertTrue(restored.footerPayloads().isEmpty())
            page.complete(response)
            runCurrent()
        }
        assertTrue(
            "Neither hydration nor running/unknown resume is terminal",
            restored.footerPayloads().isEmpty(),
        )
        val corrections =
            restored.uiState.value.messages
                .filter { it.content == "correction" }
        if (legacyCache) {
            // An unscoped legacy row is not the canonical row merely because its
            // text matches. Keep both; only the authoritative row gains the tag.
            assertEquals(2, corrections.size)
            assertEquals(1, corrections.count { it.displayKind == null })
            assertEquals(1, corrections.count { it.displayKind == "steer" })
        } else {
            assertEquals("steer", corrections.single().displayKind)
        }
        emitEvent(WsEvent.MessageComplete("", "foreign-runtime"))
        runCurrent()
        assertTrue(restored.footerPayloads().isEmpty())
        emitEvent(WsEvent.MessageComplete("", "resumed-runtime"))
        advanceUntilIdle()
        assertTrue(
            "A cold gap/unknown legacy history plus an uncorrelated empty terminal proves no complete response",
            restored.footerPayloads().isEmpty(),
        )
        androidx.lifecycle.ViewModelStore().apply {
            put("restored", restored)
            clear()
        }
    }

    @Test fun replyBoundary_restoreRunningRestFirst() = runTest { restoreAcceptedSteer(true, true) }

    @Test fun replyBoundary_restoreRunningAckFirst() = runTest { restoreAcceptedSteer(false, true) }

    @Test fun replyBoundary_restoreUnknownRestFirst() = runTest { restoreAcceptedSteer(true, null) }

    @Test fun replyBoundary_restoreUnknownAckFirst() = runTest { restoreAcceptedSteer(false, null) }

    @Test fun replyBoundary_legacyCacheWaitsForCanonicalRestMetadata() =
        runTest {
            restoreAcceptedSteer(
                false,
                true,
                true,
            )
        }

    @Test fun replyBoundary_legacyCacheUnknownRestFirst() = runTest { restoreAcceptedSteer(true, null, true) }

    @Test
    fun replyIdentity_restoredRunningReplyDoesNotCompleteWhileSteerAckIsPending() =
        runTest {
            val session = "session-123"
            val cached =
                listOf(
                    ChatMessage(
                        id = "rest-$session-1001",
                        role = MessageRole.USER,
                        content = "original",
                        timestamp = 1000L,
                    ),
                    ChatMessage(
                        id = "rest-$session-1002",
                        role = MessageRole.ASSISTANT,
                        content = "interim",
                        timestamp = 1000L,
                    ),
                )
            fakeRepo.persistMessages(cached, session)
            stubEmptySessionRests(session)
            coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            cached.mapIndexed { index, row ->
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 1001 + index,
                                    role = row.role.name.lowercase(),
                                    content = JsonPrimitive(row.content),
                                    timestamp = JsonPrimitive(1.0),
                                )
                            },
                        pagination =
                            com.m57.hermescontrol.data.model
                                .PaginationInfo(order = "latest", offset = 0),
                    ),
                )
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            val requests = captureRecoveryResumes()
            val restored = createViewModel()
            runCurrent()
            restored.switchSession(session)
            runCurrent()
            recoveryAck(requests.last(), true)
            runCurrent()
            assertEquals(
                // Legacy row addresses have no target provenance. Retain them
                // alongside REST, without inventing identity from equal text.
                listOf("original", "interim", "original", "interim"),
                restored.uiState.value.messages
                    .filter { it.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) }
                    .map { it.content },
            )
            assertTrue(restored.footerPayloads().isEmpty())
            val gate = CompletableDeferred<Unit>()
            fakeSubmission.beforePrompt = { gate.await() }
            try {
                restored.sendMessage("pending correction", steer = true)
                runCurrent()
                assertEquals(
                    null,
                    restored.uiState.value.messages
                        .last()
                        .displayKind,
                )
                assertTrue(
                    "A pending steer is not a new response boundary or proof that cached interim prose completed",
                    restored.footerPayloads().isEmpty(),
                )
            } finally {
                gate.complete(Unit)
                androidx.lifecycle.ViewModelStore().apply {
                    put("restored", restored)
                    clear()
                }
            }
        }

    @Test
    fun replyIdentity_consecutiveEqualUnstreamedInterimsSurviveMembershipCopyAndColdReopen() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("check twice")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("Checking", false, session))
            advanceUntilIdle()
            val firstId =
                vm.uiState.value.messages
                    .single { it.role == MessageRole.ASSISTANT }
                    .id
            emitEvent(WsEvent.MessageInterim("Checking", false, session))
            advanceUntilIdle()
            val deliveries =
                vm.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
            assertEquals(listOf("Checking", "Checking"), deliveries.map { it.content })
            val ids = deliveries.map { it.id }
            assertEquals(firstId, ids.first())
            assertEquals(2, ids.distinct().size)
            assertEquals(
                ids,
                vm.uiState.value.responseLedger.responses
                    .single()
                    .messageIds,
            )
            assertTrue(vm.footerPayloads().isEmpty())
            fixtureClock = 77L
            emitEvent(WsEvent.MessageComplete("Done", session))
            advanceUntilIdle()
            val expected = listOf("Checking\n\nChecking\n\nDone")
            assertEquals(expected, vm.footerPayloads())
            val record =
                vm.uiState.value.responseLedger.responses
                    .single()
            val allIds =
                vm.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
                    .map { it.id }
            assertEquals(allIds, record.messageIds)
            assertEquals(77L, record.finishedAt)
            val saved = fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), session)
            assertEquals(
                allIds,
                saved.messages
                    .filter { it.role == MessageRole.ASSISTANT }
                    .map { it.id },
            )
            assertEquals(
                record,
                saved.payload.ledger.responses
                    .single(),
            )
            androidx.lifecycle.ViewModelStore().apply {
                put("first-vm", vm)
                clear()
            }
            // Recreate the repository as well as the VM; only persisted DAO bytes survive.
            fakeRepo = FakeChatPersistenceRepository(fakeRepo.dao)
            stubEmptySessionRests(session)
            val restored = createViewModel()
            runCurrent()
            restored.switchSession(session)
            advanceUntilIdle()
            assertEquals(
                listOf("Checking", "Checking", "Done"),
                restored.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
                    .map { it.content },
            )
            assertEquals(
                allIds,
                restored.uiState.value.responseLedger.responses
                    .single()
                    .messageIds,
            )
            assertEquals(
                record,
                restored.uiState.value.responseLedger.responses
                    .single(),
            )
            assertEquals(expected, restored.footerPayloads())
            androidx.lifecycle.ViewModelStore().apply {
                put("restored-vm", restored)
                clear()
            }
        }

    @Test
    fun replyIdentity_ordinaryCompletionSurvivesRapidNextSendAndRealVmReopen() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fixtureClock = 10L
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("  **same**\n", false, session))
            fixtureClock = 77L
            emitEvent(WsEvent.MessageComplete("tail\n\n", session))
            advanceUntilIdle()
            val footer = vm.footerPayloads()
            assertEquals(listOf("  **same**\n\n\ntail\n\n"), footer)
            assertEquals(
                77L,
                vm.uiState.value.responseLedger.responses
                    .single()
                    .finishedAt,
            )
            fixtureClock = 0L // Backward wall clock: order must still put this USER after its answer.
            vm.sendMessage("second")
            advanceUntilIdle()
            assertEquals(footer, vm.footerPayloads())
            val before =
                vm.uiState.value.messages
                    .filter { it.role != MessageRole.SYSTEM }
                    .map { it.content }
            androidx.lifecycle.ViewModelStore().apply {
                put("first-vm", vm)
                clear()
            }
            stubEmptySessionRests(session)
            val restored = createViewModel()
            runCurrent()
            restored.switchSession(session)
            advanceUntilIdle()
            assertEquals(
                before,
                restored.uiState.value.messages
                    .filter { it.role != MessageRole.SYSTEM }
                    .map { it.content },
            )
            assertEquals(footer, restored.footerPayloads())
            assertEquals(
                77L,
                restored.uiState.value.responseLedger.responses
                    .first()
                    .finishedAt,
            )
            androidx.lifecycle.ViewModelStore().apply {
                put("restored-vm", restored)
                clear()
            }
        }

    @Test
    fun replyIdentity_receiptRoutesToItsResponseNotTheNewestSend() =
        runTest {
            val (vm, session) = createViewModelWithSession()

            fun ack(row: Long) =
                SubmissionResult.Confirmed(
                    "rpc-not-submission",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.STREAMING,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(userRowId = row),
                )

            fun terminal(
                text: String,
                row: Long,
            ) = WsEvent.MessageComplete(
                text,
                session,
                persistedTurn =
                    com.m57.hermescontrol.data.ws.PersistedTurn(
                        listOf(row, row + 1),
                        true,
                        row,
                        finalAssistantRowId = row + 1,
                    ),
            )
            fakeSubmission.nextPromptResult = ack(101)
            vm.sendMessage("same")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(terminal("first", 101))
            advanceUntilIdle()
            assertEquals(listOf("first"), vm.footerPayloads())
            fakeSubmission.nextPromptResult = ack(201)
            vm.sendMessage("same")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageToken("second", session))
            advanceUntilIdle()
            emitEvent(terminal("first", 101))
            runCurrent()
            assertTrue(vm.uiState.value.isAgentTyping)
            emitEvent(terminal("second", 201))
            advanceUntilIdle()
            assertEquals(listOf("first", "second"), vm.footerPayloads())
            assertEquals(
                2,
                vm.uiState.value.messages
                    .count { it.role == MessageRole.USER && it.content == "same" },
            )
        }

    @Test
    fun replyIdentity_exhaustiveReceiptReplacesWholeResponseWithoutTextMatching() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "rpc",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.STREAMING,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(101),
                )
            vm.sendMessage("same")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("same", false, session))
            emitEvent(
                WsEvent.MessageComplete(
                    "tail",
                    session,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws
                            .PersistedTurn(listOf(101, 102, 103), true, 101, finalAssistantRowId = 103),
                ),
            )
            advanceUntilIdle()
            val response =
                vm.uiState.value.responseLedger.responses
                    .single()
            assertEquals(listOf("same\n\ntail"), vm.footerPayloads())
            stubEmptySessionRests(session)
            coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 101,
                                    role = "user",
                                    content = JsonPrimitive("same"),
                                    timestamp = JsonPrimitive(999.0),
                                ),
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 102,
                                    role = "assistant",
                                    content = JsonPrimitive("same"),
                                    timestamp = JsonPrimitive(1.0),
                                ),
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 103,
                                    role = "assistant",
                                    content = JsonPrimitive("tail"),
                                    timestamp = JsonPrimitive(0.0),
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model
                                .PaginationInfo(order = "latest", offset = 0),
                    ),
                )
            val resumes = captureRecoveryResumes()
            vm.switchSession(session)
            runCurrent()
            recoveryAck(resumes.last(), false, session)
            advanceUntilIdle()
            assertEquals(
                response.id,
                vm.uiState.value.responseLedger.responses
                    .single()
                    .id,
            )
            assertEquals(
                response.finishedAt,
                vm.uiState.value.responseLedger.responses
                    .single()
                    .finishedAt,
            )
            assertEquals(listOf("same\n\ntail"), vm.footerPayloads())
            val prose =
                vm.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
            assertEquals(listOf("same", "tail"), prose.map { it.content })
            assertTrue(
                "Whole response is now canonical, not guessed equal-text pairings",
                prose.all {
                    it.id.startsWith("canonical:")
                },
            )
        }

    @Test fun replyIdentity_unknownSteerRestoreKeepsContinuationButDoesNotInventCoverage() =
        runTest {
            uncertainSteerRestore(SubmissionResult.Unknown)
        }

    @Test fun replyIdentity_notSentSteerRestoreKeepsContinuationWithoutASentBubble() =
        runTest {
            uncertainSteerRestore(SubmissionResult.NotSent)
        }

    @Test fun replyIdentity_pendingSteerRestoreKeepsContinuationWithoutASentBubble() =
        runTest {
            uncertainSteerRestore(SubmissionResult.Unknown, pending = true)
        }

    private suspend fun TestScope.uncertainSteerRestore(
        result: SubmissionResult<com.m57.hermescontrol.domain.hermes.PromptAcceptance>,
        pending: Boolean = false,
    ) {
        val (vm, session) = createViewModelWithSession()
        vm.sendMessage("original")
        advanceUntilIdle()
        emitEvent(WsEvent.MessageStart(session))
        emitEvent(WsEvent.MessageInterim("interim", false, session))
        advanceUntilIdle()
        val responseId = vm.uiState.value.responseLedger.activeId
        val gate = CompletableDeferred<Unit>()
        if (pending) fakeSubmission.beforePrompt = { gate.await() }
        fakeSubmission.nextPromptResult = result
        vm.sendMessage("correction", steer = true)
        advanceUntilIdle()
        val continuation = vm.uiState.value.responseLedger.pendingSubmissions
        assertEquals(
            if (result ==
                SubmissionResult.NotSent
            ) {
                emptyList<String>()
            } else {
                listOf(responseId)
            },
            continuation.values.toList(),
        )
        androidx.lifecycle.ViewModelStore().apply {
            put("old", vm)
            clear()
        }
        gate.complete(Unit)
        fakeSubmission.beforePrompt = null
        stubEmptySessionRests(session)
        val restored = createViewModel()
        runCurrent()
        restored.switchSession(session)
        runCurrent()
        assertEquals(responseId, restored.uiState.value.responseLedger.activeId)
        assertEquals(continuation, restored.uiState.value.responseLedger.pendingSubmissions)
        assertEquals(
            listOf("original", "interim"),
            restored.uiState.value.messages
                .filter {
                    it.role !=
                        MessageRole.SYSTEM
                }.map { it.content },
        )
        assertTrue(restored.footerPayloads().isEmpty())
        assertFalse(
            "A cold observation gap cannot prove exhaustive prose coverage",
            restored.uiState.value.responseLedger.responses
                .single()
                .coverage,
        )
    }

    @Test
    fun replyIdentity_responseReusedDoesNotAppendAnotherProseSegment() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("question")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("delivered", false, session))
            emitEvent(WsEvent.MessageComplete("rendered-reused", session, responseReused = true))
            advanceUntilIdle()
            assertEquals(
                listOf("delivered"),
                vm.uiState.value.messages
                    .filter {
                        it.role == MessageRole.ASSISTANT
                    }.map { it.content },
            )
            assertEquals(listOf("delivered"), vm.footerPayloads())
        }

    @Test
    fun replyIdentity_actualUncorrelatedOldTerminalCannotMutateNewReplyBeforeOrAfterTokens() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageComplete("first answer", session))
            advanceUntilIdle()
            vm.sendMessage("second")
            advanceUntilIdle()
            val second = vm.uiState.value.responseLedger.activeId
            // Actual legacy payload: there is deliberately no fabricated response_id.
            val frame =
                com.m57.hermescontrol.data.ws.EventParser.parse(
                    com.m57.hermescontrol.data.ws.JsonRpcResponse(
                        jsonrpc = "2.0",
                        id = null,
                        result = null,
                        error = null,
                        method = "event",
                        params =
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                """{"type":"message.complete","session_id":"$session","payload":{"text":"old duplicate"}}""",
                            ) as kotlinx.serialization.json.JsonObject,
                    ),
                )
            emitEvent(frame)
            runCurrent()
            assertTrue(vm.uiState.value.isAgentTyping)
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageToken("new prose", session))
            advanceUntilIdle()
            emitEvent(WsEvent.MessageDone(session))
            emitEvent(frame)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isAgentTyping)
            assertEquals(
                "new prose",
                vm.streamingState.value.streamingMessage!!
                    .content,
            )
            assertNull(
                vm.uiState.value.responseLedger.responses
                    .single { it.id == second }
                    .finishedAt,
            )
            assertEquals(listOf("first answer"), vm.footerPayloads())
        }

    @Test
    fun replyIdentity_navigationWaitsForEarlierTerminalSnapshotInsteadOfErasingIt() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = {
                entered.complete(Unit)
                release.await()
            }
            emitEvent(WsEvent.MessageComplete("durable answer", session))
            runCurrent()
            entered.await()
            stubEmptySessionRests(session)
            vm.switchSession(session)
            runCurrent()
            fakeRepo.dao.beforeSnapshotSave = null
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("durable answer"), vm.footerPayloads())
            assertEquals(
                listOf("durable answer"),
                vm.uiState.value.messages
                    .filter {
                        it.role == MessageRole.ASSISTANT
                    }.map { it.content },
            )
        }

    @Test
    fun replyIdentity_undoInvalidatesDelayedTerminalAndCannotResurrectIt() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = {
                entered.complete(Unit)
                release.await()
            }
            emitEvent(WsEvent.MessageComplete("rewound answer", session))
            runCurrent()
            entered.await()
            stubEmptySessionRests(session)
            every { HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any()) } returns
                CompletableDeferred(mapOf("type" to "prefill", "message" to "first", "notice" to "rewound"))
            vm.sendMessage("/undo")
            runCurrent()
            assertTrue(vm.footerPayloads().isEmpty())
            fakeRepo.dao.beforeSnapshotSave = null
            release.complete(Unit)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.messages
                    .none { it.role == MessageRole.ASSISTANT },
            )
            assertTrue(
                fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), session).messages.none {
                    it.role ==
                        MessageRole.ASSISTANT
                },
            )
            assertTrue(vm.footerPayloads().isEmpty())
        }

    @Test
    fun replyIdentity_tokenBurstDoesNotQueueWholeTranscriptWritesAndCheckpointsLatestBody() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("original")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            var writes = 0
            fakeRepo.dao.beforeSnapshotSave = { writes += 1 }
            repeat(60) {
                emitEvent(WsEvent.MessageToken("x", session))
                runCurrent()
            }
            assertEquals(
                "x".repeat(60),
                vm.streamingState.value.streamingMessage
                    ?.content,
            )
            assertTrue("A token burst must not enqueue a whole-cache rewrite for every delta", writes <= 1)
            advanceTimeBy(250)
            runCurrent()
            val saved = fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), session)
            assertTrue(saved.messages.any { it.role == MessageRole.ASSISTANT && it.content == "x".repeat(60) })
            fakeRepo.dao.beforeSnapshotSave = null
            androidx.lifecycle.ViewModelStore().apply {
                put("vm", vm)
                clear()
            }
        }

    @Test
    fun replyIdentity_navigationCheckpointsLatestPartialBodyBeforeChangingOwner() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("original")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageToken("latest partial", session))
            runCurrent()
            stubEmptySessionRests("other-session")
            vm.switchSession("other-session")
            runCurrent()
            val saved = fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), session)
            assertTrue(saved.messages.any { it.role == MessageRole.ASSISTANT && it.content == "latest partial" })
            assertTrue(
                saved.payload.ledger.responses
                    .all { it.finishedAt == null },
            )
            androidx.lifecycle.ViewModelStore().apply {
                put("vm", vm)
                clear()
            }
        }

    @Test
    fun replyIdentity_partialSteerReceiptKeepsOneCanonicalFooterAcrossNextQuestionAndReopen() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("original")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("A1", false, session))
            advanceUntilIdle()
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "steer",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.STEERED,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(userRowId = 1012L),
                )
            vm.sendMessage("correction", steer = true)
            advanceUntilIdle()
            emitEvent(
                WsEvent.MessageComplete(
                    "A2",
                    session,
                    status = "complete",
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            listOf(1010L, 1011L, 1012L, 1013L),
                            false,
                            1010L,
                            listOf(1010L, 1012L),
                            1013L,
                        ),
                ),
            )
            advanceUntilIdle()
            val original =
                vm.uiState.value.responseLedger.responses
                    .single()
            assertEquals(listOf("A1\n\nA2"), vm.footerPayloads())
            stubEmptySessionRests(session)
            coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 1010,
                                    role = "user",
                                    content = JsonPrimitive("original"),
                                ),
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 1011,
                                    role = "assistant",
                                    content = JsonPrimitive("A1"),
                                ),
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 1012,
                                    role = "user",
                                    content = JsonPrimitive("correction"),
                                    display_kind = "steer",
                                ),
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 1013,
                                    role = "assistant",
                                    content = JsonPrimitive("A2"),
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model
                                .PaginationInfo(order = "latest", offset = 0),
                    ),
                )
            val requests = captureRecoveryResumes()
            vm.switchSession(session)
            runCurrent()
            recoveryAck(requests.last(), false, session)
            advanceUntilIdle()
            assertEquals(
                "Canonical bodies may not duplicate already-observed response segments",
                listOf("A1", "A2"),
                vm.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
                    .map { it.content },
            )
            val canonical =
                vm.uiState.value.responseLedger.responses
                    .single()
            assertEquals(original.id, canonical.id)
            assertEquals(original.finishedAt, canonical.finishedAt)
            assertFalse("The actual partial receipt must remain partial", canonical.canonicalComplete)
            assertTrue(
                "An independently proven complete window has its own provenance",
                canonical.canonicalWindowVerified,
            )
            assertEquals(listOf("A1\n\nA2"), vm.footerPayloads())
            fakeSubmission.nextPromptResult = null
            vm.sendMessage("next question")
            advanceUntilIdle()
            assertEquals(listOf("A1\n\nA2"), vm.footerPayloads())
            androidx.lifecycle.ViewModelStore().apply {
                put("first", vm)
                clear()
            }
            val restored = createViewModel()
            runCurrent()
            restored.switchSession(session)
            runCurrent()
            recoveryAck(requests.last(), true, session)
            advanceUntilIdle()
            assertEquals(listOf("A1\n\nA2"), restored.footerPayloads())
            assertEquals(
                original.finishedAt,
                restored.uiState.value.responseLedger.responses
                    .single {
                        it.id == original.id
                    }.finishedAt,
            )
            androidx.lifecycle.ViewModelStore().apply {
                put("restored", restored)
                clear()
            }
        }

    @Test
    fun replyIdentity_addressedTerminalBeforeAckRetainsReceiptTimeAndCommitsBeforeQueue() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            completeAddressed(vm, session, "first answer")
            advanceUntilIdle()
            val ack = CompletableDeferred<Unit>()
            fakeSubmission.beforePrompt = { ack.await() }
            vm.sendMessage("second")
            runCurrent()
            val second = vm.uiState.value.responseLedger.activeId
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("second prose", false, session))
            vm.sendMessage("/queue queued third")
            runCurrent()
            val terminal =
                WsEvent.MessageComplete(
                    "second tail",
                    session,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            listOf(1020, 1021),
                            true,
                            1020,
                            finalAssistantRowId = 1021,
                        ),
                )
            fixtureClock = 77L
            emitEvent(terminal)
            runCurrent()
            assertTrue(vm.uiState.value.isAgentTyping)
            assertEquals(listOf("first answer"), vm.footerPayloads())
            val entered = CompletableDeferred<Unit>()
            val commit = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = {
                if (vm.uiState.value.responseLedger.responses
                        .any { it.id == second && it.finishedAt != null }
                ) {
                    entered.complete(Unit)
                    commit.await()
                }
            }
            fixtureClock = 999L
            fakeSubmission.beforePrompt = null
            ack.complete(Unit)
            runCurrent()
            assertEquals(
                77L,
                vm.uiState.value.responseLedger.responses
                    .single { it.id == second }
                    .finishedAt,
            )
            assertEquals(listOf("first answer", "second prose\n\nsecond tail"), vm.footerPayloads())
            assertFalse(vm.uiState.value.isAgentTyping)
            entered.await()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "queued third", any(), any()) }
            fakeRepo.dao.beforeSnapshotSave = null
            commit.complete(Unit)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "queued third", any(), true) }
            val beforeDuplicate = vm.footerPayloads()
            emitEvent(terminal)
            advanceUntilIdle()
            assertEquals(beforeDuplicate, vm.footerPayloads())
            assertTrue(vm.uiState.value.isAgentTyping)
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "queued third", any(), true) }
        }

    @Test
    fun replyIdentity_pendingTerminalCannotReviveAcrossTargetRoundTripAndLateAck() =
        runTest { pendingTerminalCannotRevive(targetSwitch = true) }

    @Test
    fun replyIdentity_pendingTerminalCannotReviveAcrossSessionRoundTripAndLateAck() =
        runTest { pendingTerminalCannotRevive(targetSwitch = false) }

    private suspend fun TestScope.pendingTerminalCannotRevive(targetSwitch: Boolean) {
        val (vm, session) = createViewModelWithSession()
        fakeSubmission.addressedPromptAcks = true
        vm.sendMessage("first")
        advanceUntilIdle()
        emitEvent(WsEvent.MessageStart(session))
        completeAddressed(vm, session, "first answer")
        advanceUntilIdle()
        val scopeA = fakeSubmission.scope
        val scopeB = scopeA.copy(serverProfileId = "other-target")
        val ack = CompletableDeferred<Unit>()
        fakeSubmission.beforePrompt = { ack.await() }
        vm.sendMessage("second")
        runCurrent()
        val second = vm.uiState.value.responseLedger.activeId
        emitEvent(WsEvent.MessageStart(session))
        emitEvent(WsEvent.MessageInterim("live second", false, session))
        val terminal =
            WsEvent.MessageComplete(
                "stale terminal",
                session,
                persistedTurn =
                    com.m57.hermescontrol.data.ws.PersistedTurn(
                        listOf(1020, 1021),
                        true,
                        1020,
                        finalAssistantRowId = 1021,
                    ),
            )
        emitEvent(terminal)
        mockScopedEvents.emit(ScopedWsEvent(scopeB, ++eventOrdinal, terminal.copy(text = "foreign scope")))
        emitEvent(terminal.copy(text = "foreign session", sessionId = "other-session"))
        advanceUntilIdle()
        stubEmptySessionRests(session)
        stubEmptySessionRests("other-session")
        val resumes = captureRecoveryResumes()
        if (targetSwitch) fakeSubmission.scope = scopeB
        vm.switchSession(if (targetSwitch) session else "other-session")
        runCurrent()
        recoveryAck(resumes.last(), false, if (targetSwitch) session else "other-session")
        advanceUntilIdle()
        fakeSubmission.scope = scopeA
        vm.switchSession(session)
        runCurrent()
        recoveryAck(resumes.last(), true, session)
        advanceUntilIdle()
        fakeSubmission.beforePrompt = null
        ack.complete(Unit)
        advanceUntilIdle()
        assertEquals(second, vm.uiState.value.responseLedger.activeId)
        fakeSubmission.nextPromptResult =
            SubmissionResult.Confirmed(
                "current-steer",
                com.m57.hermescontrol.domain.hermes.PromptAcceptance.STEERED,
                com.m57.hermescontrol.domain.hermes
                    .PromptEvidence(userRowId = 1020),
            )
        vm.sendMessage("current correction", steer = true)
        advanceUntilIdle()
        assertTrue(
            vm.uiState.value.responseLedger.responses
                .single { it.id == second }
                .userRowIds
                .contains(1020),
        )
        assertNull(
            vm.uiState.value.responseLedger.responses
                .single { it.id == second }
                .finishedAt,
        )
        assertTrue(vm.uiState.value.isAgentTyping)
        assertEquals(listOf("first answer"), vm.footerPayloads())
        assertTrue(
            vm.uiState.value.messages.none {
                it.content in
                    setOf("stale terminal", "foreign scope", "foreign session")
            },
        )
    }

    @Test
    fun replyIdentity_terminalProvenanceRequiresExplicitFinalAndSafeFrameOnActiveAndOldPaths() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            val terminal =
                WsEvent.MessageComplete(
                    "first answer",
                    session,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            listOf(1010, 1012, 1011),
                            false,
                            1010,
                            finalAssistantRowId = 1012,
                        ),
                )
            emitEvent(terminal)
            advanceUntilIdle()
            val first =
                vm.uiState.value.responseLedger.responses
                    .single()
            assertEquals(1012L, first.canonicalFinalAssistantRowId)
            assertTrue(first.canonicalWindowAllowed)
            assertFalse(first.canonicalComplete)
            assertFalse(first.canonicalWindowVerified)
            assertEquals(
                vm.uiState.value.messages
                    .single { it.content == "first answer" }
                    .id,
                vm.uiState.value.responseLedger.aliases["1012"],
            )
            emitEvent(WsEvent.MessageComplete("first answer", session))
            advanceUntilIdle()
            assertEquals(
                first,
                vm.uiState.value.responseLedger.responses
                    .single(),
            )
            vm.sendMessage("second")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageToken("second live", session))
            advanceUntilIdle()
            val unsafeFrames =
                listOf(
                    terminal.copy(partial = true),
                    terminal.copy(responseTransformed = true),
                    terminal.copy(status = "error"),
                    terminal.copy(persistedTurn = terminal.persistedTurn!!.copy(finalAssistantRowId = null)),
                )
            for (unsafe in unsafeFrames) {
                emitEvent(unsafe)
                advanceUntilIdle()
                val old =
                    vm.uiState.value.responseLedger.responses
                        .single { it.id == first.id }
                assertFalse(
                    old
                        .authorityDecision(
                            vm.uiState.value.messages
                                .map { it.id }
                                .toSet(),
                        ).copyReady,
                )
                assertEquals(
                    "Missing replay fields do not erase the original explicit final",
                    1012L,
                    old.canonicalFinalAssistantRowId,
                )
                assertEquals(first.finishedAt, old.finishedAt)
                assertTrue(vm.uiState.value.isAgentTyping)
                emitEvent(terminal)
                advanceUntilIdle()
                assertFalse(
                    "A corrected replay cannot erase unsafe terminal enrichment",
                    vm.uiState.value.responseLedger.responses
                        .single { it.id == first.id }
                        .authorityDecision(
                            vm.uiState.value.messages
                                .map { it.id }
                                .toSet(),
                        ).copyReady,
                )
            }
            val noFinal =
                WsEvent.MessageComplete(
                    "second answer",
                    session,
                    partial = true,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws
                            .PersistedTurn(listOf(1020, 1021), true, 1020),
                )
            emitEvent(noFinal)
            advanceUntilIdle()
            val second =
                vm.uiState.value.responseLedger.responses
                    .last()
            assertNull(second.canonicalFinalAssistantRowId)
            assertFalse(second.canonicalWindowAllowed)
            assertFalse(second.canonicalWindowVerified)
        }

    @Test
    fun pendingTokenTailRepairDoesNotLeakIntoNextSegmentOrReviveAfterTerminal() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("question")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            // Disable the JVM-only always-flush override, then hold the actual throttle closed.
            // No sleep/wall-clock threshold is used; the interim must force-flush this buffer.
            ChatViewModel::class.java
                .getDeclaredField("isTestEnv")
                .apply { isAccessible = true }
                .set(vm, false)
            val controller =
                ChatViewModel::class.java
                    .getDeclaredField("streamingController")
                    .apply {
                        isAccessible =
                            true
                    }.get(vm) as ChatStreamingController
            ChatStreamingController::class.java
                .getDeclaredField("lastFlushMs")
                .apply {
                    isAccessible = true
                }.setLong(controller, Long.MAX_VALUE)
            emitEvent(WsEvent.MessageToken("Buffered prefix", session))
            runCurrent()
            assertEquals(
                "The prefix must still be pending in the actual controller buffer",
                "",
                vm.streamingState.value.streamingMessage
                    ?.content
                    .orEmpty(),
            )
            emitEvent(WsEvent.MessageInterim("Buffered prefix and full tail.", true, session))
            runCurrent()
            val sealed =
                vm.uiState.value.messages
                    .single { it.role == MessageRole.ASSISTANT }
            assertEquals("Buffered prefix and full tail.", sealed.content)
            advanceUntilIdle()
            assertNull(vm.streamingState.value.streamingMessage)
            assertEquals(
                listOf(sealed.id),
                vm.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
                    .map { it.id },
            )
            emitEvent(WsEvent.MessageToken("Next segment", session))
            advanceUntilIdle()
            assertEquals(
                "Next segment",
                vm.streamingState.value.streamingMessage
                    ?.content,
            )
            emitEvent(WsEvent.MessageInterim("Foreign body", true, "foreign-session"))
            advanceUntilIdle()
            assertEquals(
                "Next segment",
                vm.streamingState.value.streamingMessage
                    ?.content,
            )
            assertEquals(
                sealed.content,
                vm.uiState.value.messages
                    .single { it.id == sealed.id }
                    .content,
            )
            emitEvent(WsEvent.MessageComplete("Next segment", session))
            advanceUntilIdle()
            emitEvent(WsEvent.MessageToken("Late token", session))
            emitEvent(WsEvent.MessageSegmentEnd(session))
            advanceUntilIdle()
            assertTrue(vm.streamingState.value.turnEnded)
            assertNull(vm.streamingState.value.streamingMessage)
            assertEquals(
                listOf("Buffered prefix and full tail.", "Next segment"),
                vm.uiState.value.messages
                    .filter {
                        it.role ==
                            MessageRole.ASSISTANT
                    }.map { it.content },
            )
            assertFalse(vm.uiState.value.isAgentTyping)
        }

    @Test
    fun partialRestBeforePreviewedCompleteDoesNotDuplicateFinal() =
        runTest { previewedFinalWithSuspendedIdleRest(restBeforeComplete = true) }

    @Test
    fun previewedCompleteBeforeSuspendedPartialRestDoesNotDuplicateFinal() =
        runTest { previewedFinalWithSuspendedIdleRest(restBeforeComplete = false) }

    @Test
    fun partialRestBeforePreviewedCompleteKeepsIndependentEqualInterim() =
        runTest { previewedFinalWithSuspendedIdleRest(restBeforeComplete = true, equalProse = true) }

    @Test
    fun partialRestBeforePreviewedCompleteSurvivesReplayHydrateAndColdReopen() =
        runTest { previewedFinalWithSuspendedIdleRest(restBeforeComplete = true, equalProse = true, reopen = true) }

    @Test
    fun unsafePreviewedCompleteCannotFoldExistingCanonicalFinal() =
        runTest { previewedFinalWithSuspendedIdleRest(restBeforeComplete = true, proofVariant = "unsafe") }

    @Test
    fun previewedCompleteWithoutFinalInReceiptCannotFoldExistingCanonicalFinal() =
        runTest { previewedFinalWithSuspendedIdleRest(restBeforeComplete = true, proofVariant = "missing-final") }

    @Test
    fun restBeforePreviewedMediaRefusesFoldAndRunsFirstTerminalNormalization() =
        runTest { previewedFinalWithSuspendedIdleRest(true, collisionVariant = "media") }

    @Test
    fun restBeforePreviewedProjectedReasoningRefusesAuthorityTransfer() =
        runTest { previewedFinalWithSuspendedIdleRest(true, collisionVariant = "projected-trace") }

    @Test
    fun restBeforePreviewedProjectedEmptyReasoningStillRefusesAuthorityTransfer() =
        runTest { previewedFinalWithSuspendedIdleRest(true, collisionVariant = "projected-empty") }

    @Test
    fun restBeforePreviewedProjectedMediaRefusesFoldAndRunsFirstTerminalNormalization() =
        runTest { previewedFinalWithSuspendedIdleRest(true, collisionVariant = "projected-media") }

    /** A sync genuinely admitted while idle may return after a new response's previews. */
    private suspend fun TestScope.previewedFinalWithSuspendedIdleRest(
        restBeforeComplete: Boolean,
        equalProse: Boolean = false,
        reopen: Boolean = false,
        proofVariant: String? = null,
        collisionVariant: String? = null,
        ordinary: Boolean = false,
    ) {
        if (collisionVariant?.contains("media") == true) {
            every { AuthManager.isGatedMode() } returns false
        }
        val (vm, session) = createViewModelWithSession()
        val api = ApiClient.hermesApi
        stubEmptySessionRests(session)
        coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    messages = emptyList(),
                    pagination =
                        com.m57.hermescontrol.data.model
                            .PaginationInfo(order = "latest", offset = 0),
                ),
            )
        // Establish server presence + latest paging with real public resume/hydration.
        val resumes = captureRecoveryResumes()
        vm.switchSession(session)
        runCurrent()
        recoveryAck(resumes.last(), false, session)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isAgentTyping)
        assertTrue(
            "Only setup/system rows may precede the question: " + vm.uiState.value.messages,
            vm.uiState.value.messages
                .none { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT },
        )

        val requestEntered = CompletableDeferred<Unit>()
        val page = CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
        coEvery { api.getSessionMessages(session, any(), 0, "latest", any()) } coAnswers {
            requestEntered.complete(Unit)
            page.await()
        }
        // No reflection, private-state patch, polling, sleep or sync call while typing.
        vm.syncCurrentSession()
        runCurrent()
        assertTrue("The actual VM sync must already be suspended in REST while idle", requestEntered.isCompleted)
        assertFalse(page.isCompleted)
        assertFalse(vm.uiState.value.isAgentTyping)

        fakeSubmission.addressedPromptAcks = true
        vm.sendMessage("question")
        advanceUntilIdle()
        val opening =
            vm.uiState.value.responseLedger.responses
                .single()
        assertEquals(listOf(1010L), opening.userRowIds)
        assertNotNull(opening.authority.opening)

        suspend fun wire(
            type: String,
            payload: String,
        ) {
            val frame =
                """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$session","payload":$payload}}"""
            val response =
                kotlinx.serialization.json.Json.decodeFromString(
                    com.m57.hermescontrol.data.ws.JsonRpcResponse
                        .serializer(),
                    frame,
                )
            emitEvent(
                com.m57.hermescontrol.data.ws.EventParser
                    .parse(response, frame),
            )
            runCurrent()
        }
        val hasMedia = collisionVariant?.contains("media") == true
        val projected = collisionVariant?.startsWith("projected") == true
        val finalText = "Previewed terminal answer" + if (hasMedia) "\nMEDIA:/host/a/report.pdf" else ""
        val finalJson = JsonPrimitive(finalText).toString()
        val commentaryText = if (equalProse) finalText else "Prior commentary"
        wire("message.start", "{}")
        wire("reasoning.delta", """{"text":"checking"}""")
        wire("message.interim", """{"text":"$commentaryText","already_streamed":false}""")
        if (collisionVariant == "projected-trace") {
            wire("reasoning.delta", """{"text":"LIVE_TRACE_NOT_IN_REST_PROJECTION"}""")
        }
        if (ordinary) {
            wire("message.delta", """{"text":${JsonPrimitive(finalText.take(5))}}""")
        } else {
            wire("message.interim", """{"text":$finalJson,"already_streamed":false}""")
        }
        advanceUntilIdle()
        val previews =
            (vm.uiState.value.messages + listOfNotNull(vm.streamingState.value.streamingMessage))
                .filter { it.role == MessageRole.ASSISTANT }
        assertEquals(
            listOf(commentaryText, if (ordinary) finalText.take(5) else finalText),
            previews.map { it.content },
        )
        val commentaryId = previews[0].id
        val previewId = previews[1].id
        assertNotEquals(commentaryId, previewId)
        assertEquals(
            previewId,
            if (ordinary) {
                vm.streamingState.value.streamingMessage
                    ?.id
            } else {
                vm.streamingState.value.previewedFinalCandidateId
            },
        )
        assertTrue(vm.uiState.value.isAgentTyping)
        assertNull(vm.uiState.value.responseLedger.aliases["1017"])
        assertFalse(page.isCompleted)
        val canonicalFinalId = canonicalMessageId(fakeSubmission.scope.draftTargetKey(), session, 1017)
        val response =
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    messages =
                        listOf(
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = 1010,
                                role = "user",
                                content = JsonPrimitive("question"),
                            ),
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = 1017,
                                role = "assistant",
                                content = JsonPrimitive(finalText),
                                display_content = if (projected) JsonPrimitive(finalText) else null,
                            ),
                        ),
                    pagination =
                        com.m57.hermescontrol.data.model.PaginationInfo(
                            order = "latest",
                            offset = 0,
                            returned = 150,
                        ),
                ),
            )

        suspend fun hydrate() {
            assertTrue(page.complete(response))
            runCurrent()
            coVerify(exactly = 2) { api.getSessionMessages(session, any(), 0, "latest", any()) }
            assertTrue(
                vm.uiState.value.messages
                    .any { it.bodyForStorage() == finalText },
            )
            if (collisionVariant != null) {
                val canonical =
                    vm.uiState.value.messages
                        .single { it.id == canonicalFinalId }
                assertEquals(projected, canonical.hasServerDisplayProjection)
                if (projected) assertEquals("", canonical.reasoningText)
                if (hasMedia) {
                    assertEquals(HostMediaExtractor.strip(finalText), canonical.content)
                    assertEquals(finalText, canonical.sourceBody)
                    val attachment = canonical.attachments!!.single()
                    assertEquals(
                        GatewayFileClient.buildMediaUrl("http://test.local/", "test-token", "/host/a/report.pdf"),
                        attachment.gatewayUrl,
                    )
                    assertNotNull(attachment.gatewayUrl)
                    assertEquals(AttachmentSource.GATEWAY, attachment.source)
                }
            }
        }

        suspend fun complete() {
            wire(
                "message.complete",
                """{"text":$finalJson,"status":"${if (proofVariant == "status") "error" else "complete"}","response_previewed":${!ordinary},"response_reused":${proofVariant == "reused"},"response_transformed":${proofVariant == "transformed"},"partial":${proofVariant == "unsafe"},"persisted_turn":{"row_ids":${if (proofVariant == "missing-final") "[1010]" else "[1010,1017]"},"complete":false,"user_row_id":1010,"final_assistant_row_id":1017}}""",
            )
            advanceUntilIdle()
            if (proofVariant == "missing-final") {
                // The existing admission fence rejects malformed row proof before reduction.
                assertFalse(vm.streamingState.value.turnEnded)
                assertTrue(vm.uiState.value.isAgentTyping)
                assertNull(
                    vm.uiState.value.responseLedger.responses
                        .single()
                        .canonicalFinalAssistantRowId,
                )
                assertTrue(
                    vm.uiState.value.responseLedger.responses
                        .single()
                        .authority.terminals
                        .isEmpty(),
                )
                return
            }
            assertTrue(vm.streamingState.value.turnEnded)
            assertFalse(vm.uiState.value.isAgentTyping)
            assertEquals(
                1017L,
                vm.uiState.value.responseLedger.responses
                    .single()
                    .canonicalFinalAssistantRowId,
            )
        }
        if (restBeforeComplete) {
            hydrate()
            val canonicalBeforeTerminal =
                vm.uiState.value.messages
                    .single { it.id == canonicalFinalId }
            assertTrue("REST was applied before the terminal", vm.uiState.value.isAgentTyping)
            assertFalse(vm.streamingState.value.turnEnded)
            assertEquals(
                previewId,
                if (ordinary) {
                    vm.streamingState.value.streamingMessage
                        ?.id
                } else {
                    vm.streamingState.value.previewedFinalCandidateId
                },
            )
            complete()
            if (collisionVariant != null) {
                val first = vm.uiState.value
                val local = first.messages.single { it.id == previewId }
                assertEquals(
                    "First terminal must normalize raw MEDIA via the actual delegate",
                    HostMediaExtractor.strip(finalText),
                    local.content,
                )
                assertFalse(
                    "Local observation must not be relabelled as server projection",
                    local.hasServerDisplayProjection,
                )
                assertEquals(canonicalBeforeTerminal, first.messages.single { it.id == canonicalFinalId })
                assertNull(first.responseLedger.aliases["1017"])
                assertEquals(
                    listOf(canonicalFinalId, commentaryId, previewId),
                    first.messages.filter { it.role == MessageRole.ASSISTANT }.map { it.id },
                )
                assertEquals(previews[0], first.messages.single { it.id == commentaryId })
                assertEquals(previews[1].reasoningText, local.reasoningText)
                assertEquals(previews[1].timestamp, local.timestamp)
                assertNotNull(local.finishTimestamp)
                if (collisionVariant == "projected-trace") {
                    assertEquals("LIVE_TRACE_NOT_IN_REST_PROJECTION", local.reasoningText)
                }
                if (hasMedia) {
                    assertEquals(finalText, local.sourceBody)
                    assertEquals(finalText, local.bodyForStorage())
                    assertEquals(canonicalBeforeTerminal.attachments, local.attachments)
                    assertEquals("/host/a/report.pdf", gatewayPathFromUrl(local.attachments!!.single().gatewayUrl!!))
                }
                val finishedAt =
                    first.responseLedger.responses
                        .single()
                        .finishedAt
                complete()
                val replay = vm.uiState.value
                assertEquals(first.messages, replay.messages)
                assertEquals(
                    finishedAt,
                    replay.responseLedger.responses
                        .single()
                        .finishedAt,
                )
                assertNull(replay.responseLedger.aliases["1017"])
                return
            }
        } else {
            complete()
            hydrate()
        }
        val state = vm.uiState.value
        if (restBeforeComplete) {
            assertEquals(
                "The earlier independent commentary must remain intact",
                previews[0],
                state.messages.single { it.id == commentaryId },
            )
        }
        val finalMaterializations =
            (
                state.messages +
                    if (ordinary &&
                        proofVariant == "missing-final"
                    ) {
                        listOfNotNull(vm.streamingState.value.streamingMessage)
                    } else {
                        emptyList()
                    }
            ).filter {
                it.id in setOf(previewId, canonicalFinalId) && it.role == MessageRole.ASSISTANT
            }
        if (proofVariant != null) {
            assertEquals("Missing/unsafe identity proof must retain both observations", 2, finalMaterializations.size)
            assertNull(state.responseLedger.aliases["1017"])
            assertEquals(previews[0], state.messages.single { it.id == commentaryId })
            return
        }
        assertEquals(
            "An explicitly previewed final has one materialization after an already-admitted partial REST " +
                "races before complete; rows=" + state.messages.map { "${it.id}:${it.content}" },
            1,
            finalMaterializations.size,
        )
        assertEquals(finalText, finalMaterializations.single().content)
        assertEquals(
            opening.id,
            state.responseLedger.responses
                .single()
                .id,
        )
        if (reopen) {
            assertEquals(previewId, finalMaterializations.single().id)
            assertEquals(previewId, state.responseLedger.aliases["1017"])
            val finishedAt =
                state.responseLedger.responses
                    .single()
                    .finishedAt
            val finishTimestamp = finalMaterializations.single().finishTimestamp
            val authority =
                state.responseLedger.responses
                    .single()
                    .authority
            assertTrue("The partial REST gap must not be repaired by binding identity", authority.deliveryGap)
            assertTrue(vm.footerPayloads().isEmpty())
            complete() // Replay cannot append, move, or finish the selected delivery a second time.
            assertEquals(
                finishedAt,
                vm.uiState.value.responseLedger.responses
                    .single()
                    .finishedAt,
            )
            assertEquals(
                finishTimestamp,
                vm.uiState.value.messages
                    .single { it.id == previewId }
                    .finishTimestamp,
            )
            assertEquals(
                listOf(commentaryId, previewId),
                vm.uiState.value.messages
                    .filter {
                        it.role == MessageRole.ASSISTANT
                    }.map { it.id },
            )
            // Repeat both page directions with only the final addressed row, avoiding a new
            // exhaustive [U,F] window proof (the existing commentary-retirement contract is separate).
            val finalOnly = response.body()!!.copy(messages = response.body()!!.messages.takeLast(1))
            coEvery { api.getSessionMessages(session, any(), 0, "latest", any()) } returns
                retrofit2.Response.success(finalOnly)
            repeat(2) {
                vm.syncCurrentSession()
                advanceUntilIdle()
                assertEquals(
                    listOf(commentaryId, previewId),
                    vm.uiState.value.messages
                        .filter {
                            it.role == MessageRole.ASSISTANT
                        }.map { it.id },
                )
                assertEquals(previewId, vm.uiState.value.responseLedger.aliases["1017"])
                assertTrue(vm.footerPayloads().isEmpty())
            }
            var reconciled = vm.uiState.value
            repeat(2) {
                for (older in listOf(false, true)) {
                    reconciled =
                        reconcileTranscript(
                            reconciled,
                            fakeSubmission.scope.draftTargetKey(),
                            session,
                            finalOnly.messages,
                            offset = if (older) 150 else 0,
                            latest = !older,
                            older = older,
                        )
                    assertEquals(1, reconciled.messages.count { it.id in setOf(previewId, canonicalFinalId) })
                }
            }
            val saved = fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), session)
            assertEquals(
                listOf(commentaryId, previewId),
                saved.messages.filter { it.role == MessageRole.ASSISTANT }.map { it.id },
            )
            assertEquals(previewId, saved.payload.ledger.aliases["1017"])
            androidx.lifecycle.ViewModelStore().apply {
                put("race-vm", vm)
                clear()
            }
            fakeRepo = FakeChatPersistenceRepository(fakeRepo.dao)
            stubEmptySessionRests(session)
            val restored = createViewModel()
            runCurrent()
            restored.switchSession(session)
            advanceUntilIdle()
            assertEquals(
                listOf(commentaryId, previewId),
                restored.uiState.value.messages
                    .filter {
                        it.role == MessageRole.ASSISTANT
                    }.map { it.id },
            )
            assertEquals(previewId, restored.uiState.value.responseLedger.aliases["1017"])
            assertEquals(
                finishedAt,
                restored.uiState.value.responseLedger.responses
                    .single()
                    .finishedAt,
            )
            assertTrue(restored.footerPayloads().isEmpty())
            androidx.lifecycle.ViewModelStore().apply {
                put("race-restored", restored)
                clear()
            }
        }
    }

    @Test
    fun streamedInterimTailRepairSurvivesPreviewedCompleteAndPartialHydrate() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("question")
            advanceUntilIdle()
            val opening =
                vm.uiState.value.responseLedger.responses
                    .single()
            assertEquals(listOf(1010L), opening.userRowIds)
            assertNotNull(opening.authority.opening)

            suspend fun wireEvent(
                type: String,
                payload: String,
            ) {
                val wire =
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$session","payload":$payload}}"""
                val response =
                    kotlinx.serialization.json.Json.decodeFromString(
                        com.m57.hermescontrol.data.ws.JsonRpcResponse
                            .serializer(),
                        wire,
                    )
                emitEvent(
                    com.m57.hermescontrol.data.ws.EventParser
                        .parse(response, wire),
                )
            }
            wireEvent("message.start", "{}")
            wireEvent("reasoning.delta", """{"text":"checking the answer"}""")
            val finalText = "Previewed terminal answer"
            val clippedPrefix = "Previewed terminal"
            wireEvent("message.delta", """{"text":"$clippedPrefix"}""")
            advanceUntilIdle()
            val streamed = vm.streamingState.value.streamingMessage
            assertEquals(clippedPrefix, streamed?.content)
            assertNotNull(streamed)
            wireEvent("message.interim", """{"text":"$finalText","already_streamed":true}""")
            advanceUntilIdle()
            assertEquals(
                "The same streamed member must receive the authoritative missing tail",
                finalText,
                vm.uiState.value.messages
                    .single {
                        it.id ==
                            streamed!!.id
                    }.content,
            )
            val previewId =
                vm.uiState.value.messages
                    .single { it.content == finalText }
                    .id
            assertTrue(
                vm.uiState.value.responseLedger.responses
                    .single()
                    .messageIds
                    .contains(previewId),
            )
            // Current server marks Codex finals already delivered as interim with this flag,
            // independently of response_reused. Exercise the parser, not a typed-event shortcut.
            wireEvent(
                "message.complete",
                """{"text":"$finalText","status":"complete","response_previewed":true,"persisted_turn":{"row_ids":[1010,1017],"complete":false,"user_row_id":1010,"final_assistant_row_id":1017}}""",
            )
            advanceUntilIdle()
            val terminalState = vm.uiState.value
            val terminal = terminalState.responseLedger.responses.single()
            assertEquals(
                "An explicitly previewed completion must not append another final before REST",
                listOf(previewId),
                terminalState.messages
                    .filter {
                        it.role == MessageRole.ASSISTANT && it.content == finalText
                    }.map { it.id },
            )
            assertEquals(opening.id, terminal.id)
            assertEquals(1017L, terminal.canonicalFinalAssistantRowId)
            assertFalse(terminal.canonicalComplete)
            assertFalse(terminalState.isAgentTyping)
            assertTrue(vm.streamingState.value.turnEnded)

            val api = ApiClient.hermesApi
            stubEmptySessionRests(session)
            coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 1017,
                                    role = "assistant",
                                    content = JsonPrimitive(finalText),
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                order = "latest",
                                offset = 0,
                                returned = 150,
                            ),
                    ),
                )
            // A real latest-page hydrate omits the accepted root and cannot retire the
            // entire response manifest by exhaustive receipt coverage.
            val resumes = captureRecoveryResumes()
            vm.switchSession(session)
            runCurrent()
            recoveryAck(resumes.last(), false, session)
            advanceUntilIdle()
            vm.syncCurrentSession()
            advanceUntilIdle()
            coVerify(exactly = 2) {
                api.getSessionMessages(session, any(), 0, "latest", any())
            }
            val hydrated = vm.uiState.value
            val canonicalFinalId = canonicalMessageId(fakeSubmission.scope.draftTargetKey(), session, 1017)
            val addressedIds = terminal.messageIds.toSet() + previewId + canonicalFinalId
            assertEquals(
                "Partial hydrate must not retain multiple materializations of the previewed final",
                1,
                hydrated.messages.count {
                    it.id in addressedIds && it.role == MessageRole.ASSISTANT &&
                        it.content == finalText
                },
            )
            assertEquals(
                opening.id,
                hydrated.responseLedger.responses
                    .single()
                    .id,
            )
            assertFalse(hydrated.isAgentTyping)
            assertTrue(vm.streamingState.value.turnEnded)
        }

    @Test
    fun previewedCompleteFromWirePartialHydrateDoesNotDuplicateInterimFinal() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("question")
            advanceUntilIdle()
            val opening =
                vm.uiState.value.responseLedger.responses
                    .single()
            assertEquals(listOf(1010L), opening.userRowIds)
            assertNotNull(opening.authority.opening)

            suspend fun wireEvent(
                type: String,
                payload: String,
            ) {
                val wire =
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$session","payload":$payload}}"""
                val response =
                    kotlinx.serialization.json.Json.decodeFromString(
                        com.m57.hermescontrol.data.ws.JsonRpcResponse
                            .serializer(),
                        wire,
                    )
                emitEvent(
                    com.m57.hermescontrol.data.ws.EventParser
                        .parse(response, wire),
                )
            }
            wireEvent("message.start", "{}")
            wireEvent("reasoning.delta", """{"text":"checking the answer"}""")
            val finalText = "Previewed terminal answer"
            wireEvent("message.interim", """{"text":"$finalText","already_streamed":false}""")
            advanceUntilIdle()
            val previewId =
                vm.uiState.value.messages
                    .single { it.content == finalText }
                    .id
            assertTrue(
                vm.uiState.value.responseLedger.responses
                    .single()
                    .messageIds
                    .contains(previewId),
            )
            // Current server marks Codex finals already delivered as interim with this flag,
            // independently of response_reused. Exercise the parser, not a typed-event shortcut.
            wireEvent(
                "message.complete",
                """{"text":"$finalText","status":"complete","response_previewed":true,"persisted_turn":{"row_ids":[1010,1017],"complete":false,"user_row_id":1010,"final_assistant_row_id":1017}}""",
            )
            advanceUntilIdle()
            val terminalState = vm.uiState.value
            val terminal = terminalState.responseLedger.responses.single()
            assertEquals(
                "An explicitly previewed completion must not append another final before REST",
                listOf(previewId),
                terminalState.messages
                    .filter {
                        it.role == MessageRole.ASSISTANT && it.content == finalText
                    }.map { it.id },
            )
            assertEquals(opening.id, terminal.id)
            assertEquals(1017L, terminal.canonicalFinalAssistantRowId)
            assertFalse(terminal.canonicalComplete)
            assertFalse(terminalState.isAgentTyping)
            assertTrue(vm.streamingState.value.turnEnded)

            val api = ApiClient.hermesApi
            stubEmptySessionRests(session)
            coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 1017,
                                    role = "assistant",
                                    content = JsonPrimitive(finalText),
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                order = "latest",
                                offset = 0,
                                returned = 150,
                            ),
                    ),
                )
            // A real latest-page hydrate omits the accepted root and cannot retire the
            // entire response manifest by exhaustive receipt coverage.
            val resumes = captureRecoveryResumes()
            vm.switchSession(session)
            runCurrent()
            recoveryAck(resumes.last(), false, session)
            advanceUntilIdle()
            vm.syncCurrentSession()
            advanceUntilIdle()
            coVerify(exactly = 2) {
                api.getSessionMessages(session, any(), 0, "latest", any())
            }
            val hydrated = vm.uiState.value
            val canonicalFinalId = canonicalMessageId(fakeSubmission.scope.draftTargetKey(), session, 1017)
            val addressedIds = terminal.messageIds.toSet() + previewId + canonicalFinalId
            assertEquals(
                "Partial hydrate must not retain multiple materializations of the previewed final",
                1,
                hydrated.messages.count {
                    it.id in addressedIds && it.role == MessageRole.ASSISTANT &&
                        it.content == finalText
                },
            )
            assertEquals(
                opening.id,
                hydrated.responseLedger.responses
                    .single()
                    .id,
            )
            assertFalse(hydrated.isAgentTyping)
            assertTrue(vm.streamingState.value.turnEnded)
        }

    @Test
    fun replyIdentity_terminalAliasDoesNotSelectReusedOrFullySealedProse() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageToken("first prose", session))
            advanceUntilIdle()
            emitEvent(
                WsEvent.MessageComplete(
                    "reused",
                    session,
                    responseReused = true,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            listOf(1010, 1011),
                            true,
                            1010,
                            finalAssistantRowId = 1011,
                        ),
                ),
            )
            advanceUntilIdle()
            assertNull(vm.uiState.value.responseLedger.aliases["1011"])
            vm.sendMessage("second")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("sealed prose", false, session))
            completeAddressed(vm, session, "sealed prose", responseReused = true)
            advanceUntilIdle()
            assertNull(vm.uiState.value.responseLedger.aliases["1021"])
            assertEquals(listOf("first prose", "sealed prose"), vm.footerPayloads())
        }

    @Test
    fun replyIdentity_queueCannotOutrunTerminalCommit() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("first")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("/queue queued next")
            advanceUntilIdle()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeSnapshotSave = {
                entered.complete(Unit)
                release.await()
            }
            emitEvent(WsEvent.MessageComplete("first answer", session))
            runCurrent()
            entered.await()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "queued next", any(), any()) }
            fakeRepo.dao.beforeSnapshotSave = null
            release.complete(Unit)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "queued next", any(), true) }
            assertEquals(listOf("first answer"), vm.footerPayloads())
        }

    @Test
    fun replyIdentity_canonicalPagesAccumulateDurablyAcrossReopenBeforeWholeReplacement() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("question")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("first prose", false, session))
            val userRow =
                vm.uiState.value.responseLedger.responses
                    .single()
                    .userRowIds
                    .single()
            emitEvent(
                WsEvent.MessageComplete(
                    "last prose",
                    session,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            listOf(userRow, userRow + 1, userRow + 2),
                            true,
                            userRow,
                            finalAssistantRowId = userRow + 2,
                        ),
                ),
            )
            advanceUntilIdle()
            val original =
                vm.uiState.value.responseLedger.responses
                    .single()
            stubEmptySessionRests(session)
            coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } coAnswers {
                val offset = arg<Int>(2)
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            if (offset == 0) {
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        id = (userRow + 2).toInt(),
                                        role = "assistant",
                                        content = JsonPrimitive("last prose"),
                                    ),
                                )
                            } else {
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        id = userRow.toInt(),
                                        role = "user",
                                        content = JsonPrimitive("question"),
                                    ),
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        id = (userRow + 1).toInt(),
                                        role = "assistant",
                                        content = JsonPrimitive("first prose"),
                                    ),
                                )
                            },
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                order = "latest",
                                offset = offset,
                                returned = if (offset == 0) 150 else 2,
                            ),
                    ),
                )
            }
            val resumes = captureRecoveryResumes()
            vm.switchSession(session)
            runCurrent()
            recoveryAck(resumes.last(), false, session)
            advanceUntilIdle()
            assertTrue(
                "Fixture must hydrate the short newest page with an explicit older-page continuation",
                vm.uiState.value.hasOlderMessages,
            )
            assertEquals(listOf("first prose\n\nlast prose"), vm.footerPayloads())
            androidx.lifecycle.ViewModelStore().apply {
                put("first", vm)
                clear()
            }
            val restored = createViewModel()
            runCurrent()
            restored.switchSession(session)
            runCurrent()
            recoveryAck(resumes.last(), false, session)
            advanceUntilIdle()
            assertTrue("Fixture must make the older page reachable", restored.uiState.value.hasOlderMessages)
            restored.loadOlderMessages()
            advanceUntilIdle()
            val response =
                restored.uiState.value.responseLedger.responses
                    .single()
            assertEquals(original.id, response.id)
            assertEquals(original.finishedAt, response.finishedAt)
            assertEquals(listOf("first prose\n\nlast prose"), restored.footerPayloads())
            assertTrue(
                restored.uiState.value.messages
                    .filter {
                        it.role == MessageRole.ASSISTANT
                    }.all { it.id.startsWith("canonical:") },
            )
            val durable = fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), session)
            assertEquals(
                listOf("first prose", "last prose"),
                durable.messages
                    .filter {
                        it.role == MessageRole.ASSISTANT
                    }.map { it.content },
            )
        }

    @Test
    fun replyIdentity_lateSteerAcceptanceRetainsCapturedResponseAfterNewSend() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("original")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("first prose", false, session))
            advanceUntilIdle()
            val originalId = vm.uiState.value.responseLedger.activeId
            val gate = CompletableDeferred<Unit>()
            fakeSubmission.beforePrompt = {
                fakeSubmission.beforePrompt = null
                gate.await()
                fakeSubmission.nextPromptResult =
                    SubmissionResult.Confirmed("late-rpc", com.m57.hermescontrol.domain.hermes.PromptAcceptance.STEERED)
            }
            vm.sendMessage("late correction", steer = true)
            runCurrent()
            emitEvent(WsEvent.MessageComplete("last prose", session))
            advanceUntilIdle()
            vm.sendMessage("new question")
            advanceUntilIdle()
            val nextId = vm.uiState.value.responseLedger.activeId
            assertNotNull(nextId)
            assertTrue(originalId != nextId)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(nextId, vm.uiState.value.responseLedger.activeId)
            val correction =
                vm.uiState.value.messages
                    .single { it.content == "late correction" }
            assertEquals("steer", correction.displayKind)
            assertTrue(
                correction.id in
                    vm.uiState.value.responseLedger.responses
                        .single { it.id == originalId }
                        .userIds,
            )
            assertFalse(
                correction.id in
                    vm.uiState.value.responseLedger.responses
                        .single { it.id == nextId }
                        .userIds,
            )
            assertEquals(listOf("first prose\n\nlast prose"), vm.footerPayloads())
        }

    @Test
    fun replyIdentity_durableTargetsRemainSeparateAcrossAtoBtoAAndRejectForeignTerminal() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            val scopeA = fakeSubmission.scope
            vm.sendMessage("A question")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageComplete("A answer", session))
            advanceUntilIdle()
            val aResponse =
                vm.uiState.value.responseLedger.responses
                    .single()
                    .id
            stubEmptySessionRests(session)
            val resumes = captureRecoveryResumes()
            val scopeB = scopeA.copy(serverProfileId = "B")
            fakeSubmission.scope = scopeB
            vm.switchSession(session)
            runCurrent()
            recoveryAck(resumes.last(), false, session)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.messages
                    .none { it.content == "A answer" },
            )
            vm.sendMessage("B question")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageComplete("B answer", session))
            advanceUntilIdle()
            assertEquals(listOf("B answer"), vm.footerPayloads())
            fakeSubmission.scope = scopeA
            vm.switchSession(session)
            runCurrent()
            recoveryAck(resumes.last(), false, session)
            advanceUntilIdle()
            mockScopedEvents.emit(
                ScopedWsEvent(scopeB, ++eventOrdinal, WsEvent.MessageComplete("foreign overwrite", session)),
            )
            advanceUntilIdle()
            assertEquals(listOf("A answer"), vm.footerPayloads())
            assertEquals(
                aResponse,
                vm.uiState.value.responseLedger.responses
                    .single()
                    .id,
            )
            assertEquals(
                listOf("B answer"),
                fakeRepo
                    .readTranscript(scopeB.draftTargetKey(), session)
                    .messages
                    .filter {
                        it.role ==
                            MessageRole.ASSISTANT
                    }.map { it.content },
            )
        }

    @Test
    fun replyBoundary_onlyActualAcceptedSteerDispositionsAreTagged() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            val cases =
                listOf(
                    SubmissionResult.Confirmed("queued", com.m57.hermescontrol.domain.hermes.PromptAcceptance.QUEUED),
                    SubmissionResult.Confirmed(
                        "streaming",
                        com.m57.hermescontrol.domain.hermes.PromptAcceptance.STREAMING,
                    ),
                    SubmissionResult.Unknown,
                    SubmissionResult.NotSent,
                )
            for ((index, result) in cases.withIndex()) {
                emitEvent(WsEvent.MessageStart(session))
                runCurrent()
                fakeSubmission.nextPromptResult = result
                val text = "not-a-confirmed-steer-$index"
                vm.sendMessage(text, steer = true)
                advanceUntilIdle()
                assertTrue(
                    vm.uiState.value.messages
                        .filter { it.content == text }
                        .all { it.displayKind == null },
                )
                val persisted = fakeRepo.loadMessages(session).filter { it.content == text }
                assertTrue(persisted.all { it.displayKind == null })
                assertEquals(if (result is SubmissionResult.Confirmed) 1 else 0, persisted.size)
            }
        }

    @Test
    fun replyBoundary_lateAcceptedSteerAckCannotTagAnotherSessionGeneration() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            fakeSubmission.beforePrompt = { gate.await() }
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "late",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.STEERED,
                )
            vm.sendMessage("same correction", steer = true)
            runCurrent()
            stubEmptySessionRests("other-session")
            vm.switchSession("other-session")
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("other-session", vm.uiState.value.currentSessionId)
            assertFalse(
                vm.uiState.value.messages
                    .any { it.content == "same correction" || it.displayKind == "steer" },
            )
            assertTrue(fakeRepo.loadMessages("other-session").none { it.displayKind == "steer" })
        }

    // ── Slash command tests ──────────────────────────────────────────────────

    @Test
    fun newChatUsesBotDefaultsWhileResumeKeepsItsOwnSettings() =
        runTest {
            every { AuthManager.readUiPreference("test-connection-profile", "chat_model") } returns
                "openai-codex/test-model"
            every { AuthManager.readUiPreference("test-connection-profile", "chat_reasoning") } returns "high"
            val (model, _) = createViewModelWithSession()
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_CREATE,
                    match {
                        it["source"] == "desktop" && "model" !in it &&
                            "provider" !in it && "reasoning_effort" !in it
                    },
                    any(),
                )
            }
            model.switchSession("existing")
            advanceUntilIdle()
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    match {
                        it["session_id"] == "existing" && "model" !in it &&
                            "reasoning_effort" !in it
                    },
                    any(),
                )
            }
        }

    @Test
    fun modelPreferenceIsSavedOnlyAfterMatchingServerInfo() =
        runTest {
            val (model, _) = createViewModelWithSession()
            model.sendSlashModel("openai-codex", "test-model")
            advanceUntilIdle()
            verify(exactly = 0) { AuthManager.writeUiPreference(any(), "chat_model", any()) }
            emitEvent(
                WsEvent.SessionInfo(
                    mapOf(
                        "model" to "test-model",
                        "provider" to "openai-codex",
                        "reasoning_effort" to "high",
                    ),
                ),
            )
            advanceUntilIdle()
            verify(exactly = 1) {
                AuthManager.writeUiPreference("test-connection-profile", "chat_model", "openai-codex/test-model")
            }
        }

    @Test
    fun reasoningPreferenceWaitsForAcknowledgementAndNullDoesNotPretendToReset() =
        runTest {
            val (model, _) = createViewModelWithSession()
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("reasoning-request")
                "reasoning-request"
            }
            model.setReasoningLevel("high")
            advanceUntilIdle()
            verify(exactly = 0) { AuthManager.writeUiPreference(any(), "chat_reasoning", any()) }
            emitEvent(WsEvent.RpcResult("reasoning-request", mapOf("key" to "reasoning", "value" to "high")))
            advanceUntilIdle()
            assertEquals("high", model.uiState.value.reasoningLevel)
            verify { AuthManager.writeUiPreference("test-connection-profile", "chat_reasoning", "high") }
            model.setReasoningLevel(null)
            advanceUntilIdle()
            assertEquals("high", model.uiState.value.reasoningLevel)
            assertNotNull(model.uiState.value.errorMessage)
        }

    @Test
    fun queue_restartRequiresReviewWithoutChangingSavedIntentOrUnknownSends() =
        runTest {
            val originalScope = fakeSubmission.scope
            val attachment = Attachment("content://retained", "retained.txt", "text/plain", 12L)
            val queued =
                OutgoingDraft(
                    "retained-queue",
                    originalScope,
                    "session-123",
                    null,
                    "Retained request",
                    listOf(attachment),
                    100L,
                    DraftState.QUEUED,
                    queued = true,
                )
            val unknown = queued.copy(id = "retained-unknown", state = DraftState.UNKNOWN, createdAt = 101L)
            fakeDraftStore.save(queued)
            fakeDraftStore.save(unknown)
            fakeSubmission.scope = originalScope.copy(processId = "restarted-process", generation = 2)
            val (vm, session) = createViewModelWithSession()
            val projected =
                vm.uiState.value.outgoingDrafts
                    .first { it.id == queued.id }
            assertEquals(queued.copy(state = DraftState.DRAFT, queued = false), projected)
            assertEquals(
                unknown,
                vm.uiState.value.outgoingDrafts
                    .first { it.id == unknown.id },
            )
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            assertEquals(listOf(queued, unknown), fakeDraftStore.drafts.value)
            vm.restoreDraft(queued.id)
            advanceUntilIdle()
            assertEquals(queued.text, vm.uiState.value.composerTextToRestore)
            assertEquals(queued.attachments, vm.uiState.value.pendingAttachments)
        }

    @Test
    fun queue_reconnectRequiresExplicitRequeueThenSendsExactlyOnce() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            stubEmptySessionRests(session)
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("/queue Review after reconnect")
            advanceUntilIdle()
            val saved = fakeDraftStore.drafts.value.single()
            val requests = captureRecoveryResumes()
            fakeSubmission.scope = fakeSubmission.scope.copy(generation = 2)
            fakeSubmission.ready = false
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            fakeSubmission.ready = true
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            runCurrent()
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            assertEquals(
                DraftState.DRAFT,
                vm.uiState.value.outgoingDrafts
                    .single()
                    .state,
            )
            assertEquals(saved, fakeDraftStore.drafts.value.single())
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            assertFalse(vm.uiState.value.isLoading)
            vm.queueDraft(saved.id)
            advanceUntilIdle()
            vm.queueDraft(saved.id)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(any(), "Review after reconnect", any(), true) }
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
        }

    @Test
    fun queue_sameLiveScopeViewModelRecreationKeepsItsAuthorization() =
        runTest {
            val (old, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            old.sendMessage("/queue Same live connection")
            advanceUntilIdle()
            val saved = fakeDraftStore.drafts.value.single()
            androidx.lifecycle.ViewModelStore().apply {
                put("old", old)
                clear()
            }
            val recreated = createViewModel()
            advanceUntilIdle()
            assertEquals(
                saved,
                recreated.uiState.value.outgoingDrafts
                    .single(),
            )
            assertEquals(
                DraftState.QUEUED,
                recreated.uiState.value.outgoingDrafts
                    .single()
                    .state,
            )
        }

    @Test
    fun queue_busyFollowUpsWaitAndRunInFifoOrderWithoutSteering() =
        runTest {
            fakeSubmission.addressedPromptAcks = true
            val (vm, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("/queue First follow-up")
            vm.sendMessage("/queue Second follow-up")
            advanceUntilIdle()
            assertEquals(
                listOf("First follow-up", "Second follow-up"),
                vm.uiState.value.outgoingDrafts
                    .map { it.text },
            )
            assertTrue(fakeDraftStore.drafts.value.all { it.state == DraftState.QUEUED })
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            emitEvent(WsEvent.MessageComplete("Finished", session))
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "First follow-up", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendMessage(session, "Second follow-up", any(), any()) }
            // Duplicate terminal notifications cannot release another queued turn.
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(session, "Second follow-up", any(), any()) }
            emitEvent(WsEvent.MessageStart(session))
            completeAddressed(vm, session)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "Second follow-up", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
        }

    @Test
    fun queue_reconnectWaitsForIdleAckAndUsesFreshRuntimeAndScope() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("After reconnect")
            // Deliver the current ACK before its bounded resume deadline.
            runCurrent()
            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            recoveryAck(requests.last(), true)
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            emitEvent(WsEvent.MessageStart("runtime-recovered"))
            emitEvent(WsEvent.MessageDone("runtime-recovered"))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage("runtime-recovered", "After reconnect", any(), true) }
        }

    @Test
    fun queue_unknownAckBlocksAndUnknownWriteIsNeverReplayed() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("Uncertain queue item")
            runCurrent()
            emitEvent(WsEvent.RpcResult(requests.last(), mapOf("session_id" to "runtime-recovered")))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            fakeSubmission.nextPromptResult = SubmissionResult.Unknown
            emitEvent(WsEvent.MessageStart("runtime-recovered"))
            emitEvent(WsEvent.MessageDone("runtime-recovered"))
            advanceUntilIdle()
            assertEquals(
                DraftState.UNKNOWN,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            vm.queueDraft(
                fakeDraftStore.drafts.value
                    .single()
                    .id,
            )
            fakeSubmission.ready = false
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            fakeSubmission.ready = true
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            runCurrent()
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(any(), "Uncertain queue item", any(), any()) }
            assertEquals(
                DraftState.UNKNOWN,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
        }

    @Test
    fun queue_editAndCancelRemoveAutomaticSendIntent() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("/queue Edit me")
            vm.sendMessage("/queue Cancel me")
            advanceUntilIdle()
            val drafts = vm.uiState.value.outgoingDrafts
            vm.restoreDraft(drafts[0].id)
            advanceUntilIdle()
            assertEquals("Edit me", vm.uiState.value.composerTextToRestore)
            assertEquals(
                DraftState.DRAFT,
                fakeDraftStore.drafts.value
                    .first { it.id == drafts[0].id }
                    .state,
            )
            vm.discardDraft(drafts[1].id)
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            assertEquals(listOf("Edit me"), fakeDraftStore.drafts.value.map { it.text })
            assertTrue(
                vm.uiState.value.activeDraftIds
                    .isEmpty(),
            )
        }

    @Test
    fun queue_otherConversationAndProfileNeverReceivePendingInput() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("/queue Original conversation only")
            advanceUntilIdle()
            val original = fakeDraftStore.drafts.value.single()
            val requests = captureRecoveryResumes()
            stubEmptySessionRests("other-session")
            vm.switchSession("other-session")
            runCurrent()
            emitEvent(
                WsEvent.RpcResult(
                    requests.last(),
                    mapOf(
                        "session_id" to "other-runtime",
                        "resumed" to "other-session",
                        "running" to false,
                        "status" to "idle",
                    ),
                ),
            )
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart("other-runtime"))
            emitEvent(WsEvent.MessageDone("other-runtime"))
            advanceUntilIdle()
            fakeSubmission.scope = fakeSubmission.scope.copy(serverProfileId = "another-profile")
            vm.queueDraft(original.id)
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            assertEquals(original, fakeDraftStore.drafts.value.single())
        }

    @Test
    fun queue_storageFailureRestoresTextAndNeverDispatchesUnsavedJob() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            fakeDraftStore.failSave = true
            vm.sendMessage("Keep my text")
            advanceUntilIdle()
            assertEquals("Keep my text", vm.uiState.value.composerTextToRestore)
            assertTrue(vm.uiState.value.draftStorageError)
            assertEquals(
                DraftState.DRAFT,
                vm.uiState.value.outgoingDrafts
                    .single()
                    .state,
            )
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun queue_explicitSteerStillReachesCurrentRun() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("Change direction", steer = true)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendRedirect(session, "Change direction", any()) }
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
        }

    @Test
    fun queue_offlineNewConversationSurvivesRestartAndBindsOnlyAfterExplicitConfirmation() =
        runTest {
            fakeSubmission.ready = false
            val old = createViewModel()
            advanceUntilIdle()
            old.sendMessage("Saved before connecting")
            advanceUntilIdle()
            val saved = fakeDraftStore.drafts.value.single()
            assertEquals(DraftState.QUEUED, saved.state)
            assertNull(saved.savedSessionId)
            androidx.lifecycle.ViewModelStore().apply {
                put("old", old)
                clear()
            }
            fakeSubmission.scope = fakeSubmission.scope.copy(processId = "recreated", generation = 2)
            val recreated = createViewModel()
            advanceUntilIdle()
            var createId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createId = "queue-new-session"
                arg<((String) -> Unit)?>(2)?.invoke(createId)
                createId
            }
            fakeSubmission.ready = true
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            runCurrent()
            emitEvent(
                WsEvent.RpcResult(
                    createId,
                    mapOf(
                        "session_id" to "fresh-runtime",
                        "stored_session_id" to "fresh-storage",
                    ),
                ),
            )
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Saved before connecting", any(), any()) }
            assertEquals(saved, fakeDraftStore.drafts.value.single())
            assertEquals(
                DraftState.DRAFT,
                recreated.uiState.value.outgoingDrafts
                    .single()
                    .state,
            )
            assertEquals("fresh-storage", recreated.uiState.value.currentSessionId)
            recreated.queueDraft(saved.id)
            runCurrent()
            emitEvent(
                WsEvent.RpcResult(
                    createId,
                    mapOf("session_id" to "confirmed-runtime", "stored_session_id" to "confirmed-storage"),
                ),
            )
            advanceUntilIdle()
            verify(
                exactly = 1,
            ) { HermesWsClient.sendMessage("confirmed-runtime", "Saved before connecting", any(), true) }
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
        }

    @Test
    fun queue_recreatedConversationDoesNotRebindStaleAuthorizationToANewSession() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            fakeSubmission.scope = fakeSubmission.scope.copy(generation = 2)
            fakeSubmission.ready = false
            vm.sendMessage("Before reconnect")
            vm.sendMessage("Before reconnect")
            advanceUntilIdle()
            assertEquals(1, fakeDraftStore.drafts.value.size)
            var createId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createId = "recreated-queue-session"
                arg<((String) -> Unit)?>(2)?.invoke(createId)
                createId
            }
            fakeSubmission.ready = true
            emitEvent(WsEvent.GatewayReady(null))
            runCurrent()
            vm.sendMessage("During reconnect")
            runCurrent()
            emitEvent(
                WsEvent.RpcResult(
                    createId,
                    mapOf(
                        "session_id" to "new-runtime",
                        "stored_session_id" to "new-storage",
                    ),
                ),
            )
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Before reconnect", any(), any()) }
            verify(exactly = 1) { HermesWsClient.sendMessage("new-runtime", "During reconnect", any(), true) }
            assertEquals(
                "session-123",
                fakeDraftStore.drafts.value
                    .single()
                    .savedSessionId,
            )
            emitEvent(WsEvent.MessageStart("new-runtime"))
            emitEvent(WsEvent.MessageDone("new-runtime"))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage("new-runtime", "During reconnect", any(), true) }
            assertEquals(
                "Before reconnect",
                fakeDraftStore.drafts.value
                    .single()
                    .text,
            )
            assertEquals(
                DraftState.DRAFT,
                vm.uiState.value.outgoingDrafts
                    .single()
                    .state,
            )
        }

    @Test
    fun queue_failedEditPausesAutomaticSendUntilUserRecoversTheDraft() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("/queue Edit after saving recovers")
            advanceUntilIdle()
            val id =
                fakeDraftStore.drafts.value
                    .single()
                    .id
            fakeDraftStore.failSave = true
            vm.restoreDraft(id)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.draftStorageError)
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            fakeDraftStore.failSave = false
            vm.restoreDraft(id)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.draftStorageError)
            assertEquals(
                DraftState.DRAFT,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            vm.sendMessage("Corrected request")
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "Corrected request", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Edit after saving recovers", any(), any()) }
        }

    @Test
    fun nativeAction_sendsShownConditionsAndKeepsComposerDraftAndAttachments() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            // A draft being edited in the composer, with an attachment picked.
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("/queue Draft still being edited")
            advanceUntilIdle()
            val editing = fakeDraftStore.drafts.value.single()
            vm.restoreDraft(editing.id)
            advanceUntilIdle()
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            vm.addAttachment("uri-native", "keep.txt", "text/plain", 100)
            advanceUntilIdle()
            val composerBefore = vm.uiState.value.composerTextToRestore
            val attachmentsBefore = vm.uiState.value.pendingAttachments
            assertTrue(canSendNativeAction(vm.uiState.value))

            val text = "この条件で最小サイズを検討\n「積立試算」\n毎月 45,000円、期間 10年、想定年率 3.5%。"
            assertTrue(vm.sendNativeAction(text))
            advanceUntilIdle()

            verify(exactly = 1) { HermesWsClient.sendMessage(session, text, any(), false) }
            assertEquals(
                text,
                vm.uiState.value.messages
                    .last { it.role == MessageRole.USER }
                    .content,
            )
            assertNull(
                vm.uiState.value.messages
                    .last { it.role == MessageRole.USER }
                    .attachments,
            )
            assertEquals(attachmentsBefore, vm.uiState.value.pendingAttachments)
            assertEquals(composerBefore, vm.uiState.value.composerTextToRestore)
            assertEquals(
                DraftState.DRAFT,
                fakeDraftStore.drafts.value
                    .single { it.id == editing.id }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }

            // The composer's draft is still the one being edited: sending it reuses its id.
            vm.sendMessage("Draft after the native send")
            advanceUntilIdle()
            assertEquals(
                "Draft after the native send",
                fakeDraftStore.drafts.value
                    .single { it.id == editing.id }
                    .text,
            )
        }

    @Test
    fun clarifyReplayAnswerRemainsReadOnlyEvenBeforePendingStateRefresh() {
        val info =
            ClarifyUi(
                text = "Choose",
                responseEnabled = true,
                questionStates = mapOf("q0" to AttentionStatus.PENDING, "q1" to AttentionStatus.PENDING),
                lockedAnswers = mapOf("q0" to "Blue"),
            )
        assertFalse(info.canRespond("q0"))
        assertTrue(info.canRespond("q1"))
        assertFalse(info.copy(responseEnabled = false).canRespond("q1"))
    }

    @Test
    fun integratedModernClarifyResolvedCancellationCannotConfirmAnUnsubmittedAnswer() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            val outer = "outer-integrated-cancel-without-answer"
            val request =
                com.m57.hermescontrol.data.ws.EventParser.parseServerRequest(
                    outer,
                    "clarify",
                    mapOf(
                        "session_id" to session,
                        "questions" to listOf(mapOf("qid" to "open", "question" to "Choose?")),
                    ),
                ) as WsEvent.ClarifyRequest
            val info = observeClarify(vm, request)
            val cancelled =
                com.m57.hermescontrol.data.ws.EventParser.parseParams(
                    mapOf(
                        "type" to "request.cancel",
                        "session_id" to session,
                        "payload" to mapOf("id" to outer, "method" to "clarify", "reason" to "resolved"),
                    ),
                )

            mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, cancelled))
            runCurrent()

            assertNull(vm.uiState.value.clarifyRequest)
            val card =
                vm.uiState.value.messages
                    .single { it.clarifyInfo?.ref == info.ref }
                    .clarifyInfo!!
            assertEquals(AttentionStatus.EXPIRED, card.questionStates["open"])
            assertFalse(card.canRespond("open"))
            coVerify(exactly = 0) { attentionTransport.respondClarification(any(), any()) }
        }

    @Test
    fun testClarifyReplayUpdatesExistingCardAndResolvedClearsPendingQuestion() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val request =
                WsEvent.ClarifyRequest(
                    "Choose",
                    null,
                    "srq-1",
                    "session-123",
                    questions = listOf(WsEvent.ClarifyQuestion("q0", "First"), WsEvent.ClarifyQuestion("q1", "Second")),
                )
            val info = observeClarify(vm, request)
            mockScopedEvents.emit(
                ScopedWsEvent(
                    fakeSubmission.scope,
                    ++eventOrdinal,
                    request.copy(lockedAnswers = mapOf("q0" to "Blue")),
                ),
            )
            runCurrent()
            val restored = checkNotNull(vm.uiState.value.clarifyRequest)
            assertEquals(AttentionStatus.CONFIRMED, restored.questionStates["q0"])
            assertEquals("Blue", restored.lockedAnswers["q0"])
            assertFalse(restored.canRespond("q0"))
            assertTrue(restored.canRespond("q1"))

            mockScopedEvents.emit(
                ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, WsEvent.ClarifyResolved("srq-1", "session-123")),
            )
            runCurrent()
            assertNull(vm.uiState.value.clarifyRequest)
            val cards =
                vm.uiState.value.messages
                    .mapNotNull { it.clarifyInfo }
                    .filter { it.ref == info.ref }
            assertEquals(AttentionStatus.CONFIRMED, cards.single().questionStates["q1"])
        }

    @Test
    fun nativeAction_isRefusedWhileGeneratingOfflineAndOnRepeatedTaps() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            assertFalse(canSendNativeAction(vm.uiState.value))
            assertFalse(vm.sendNativeAction("この行で相談\n「比較」"))
            advanceUntilIdle()
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()

            assertTrue(canSendNativeAction(vm.uiState.value))
            val text = "これで進める\n「進め方」\n選択: A"
            assertTrue(vm.sendNativeAction(text))
            runCurrent()
            assertFalse(canSendNativeAction(vm.uiState.value))
            assertFalse(vm.sendNativeAction(text))
            assertFalse(vm.sendNativeAction("別の文面"))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(session, text, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "別の文面", any(), any()) }
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.content == text },
            )

            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            runCurrent()
            assertFalse(canSendNativeAction(vm.uiState.value))
            assertFalse(vm.sendNativeAction("オフライン"))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "オフライン", any(), any()) }
            assertTrue(fakeDraftStore.drafts.value.none { it.text == "オフライン" })
        }

    @Test
    fun queue_readdingEditedDraftAppendsAfterAlreadyQueuedMessages() =
        runTest {
            fakeSubmission.addressedPromptAcks = true
            val (vm, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("/queue Older draft")
            advanceUntilIdle()
            val id =
                fakeDraftStore.drafts.value
                    .single()
                    .id
            vm.sendMessage("/queue Already waiting")
            advanceUntilIdle()
            vm.restoreDraft(id)
            advanceUntilIdle()
            vm.sendMessage("Added later")
            advanceUntilIdle()
            assertEquals(listOf("Already waiting", "Added later"), fakeDraftStore.drafts.value.map { it.text })
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "Already waiting", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Added later", any(), any()) }
            emitEvent(WsEvent.MessageStart(session))
            completeAddressed(vm, session)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "Added later", any(), true) }
        }

    private fun stubDraftAttachment(vm: ChatViewModel) {
        mockkStatic(Uri::class)
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { Uri.parse("content://draft/attachment") } returns uri
        every { app.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } answers { java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
            CompletableDeferred(mapOf("attached" to true, "ref_text" to "@file:synthetic"))
        vm.addAttachment("content://draft/attachment", "fixture.txt", "text/plain", 3)
    }

    @Test
    fun draft_lostAckDoesNotEraseObservedStreamingState() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            fakeSubmission.beforePrompt = {
                emitEvent(WsEvent.MessageStart(sessionId))
                runCurrent()
            }
            fakeSubmission.nextPromptResult = SubmissionResult.Unknown
            vm.sendMessage("Observe the answer")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isAgentTyping)
            assertEquals(
                DraftState.UNKNOWN,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
        }

    @Test
    fun draft_unknownAttachmentStopsBeforePromptAndRetainsFullIntent() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            stubDraftAttachment(vm)
            fakeSubmission.nextAttachmentResult = SubmissionResult.Unknown
            vm.sendMessage("Inspect attachment")
            advanceUntilIdle()
            val stored = fakeDraftStore.drafts.value.single()
            assertEquals(DraftState.UNKNOWN, stored.state)
            assertEquals(1, stored.attachments.size)
            assertEquals("Inspect attachment", stored.text)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun draft_rejectedPromptAfterConfirmedAttachmentStaysUnknown() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            stubDraftAttachment(vm)
            fakeSubmission.nextPromptResult = SubmissionResult.NotSent
            vm.sendMessage("Partial remote write")
            advanceUntilIdle()
            assertEquals(
                DraftState.UNKNOWN,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            verify(exactly = 1) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun draft_messageCacheFailureAfterAckKeepsUnknownWithoutResending() =
        runTest {
            fakeRepo = spyk(fakeRepo)
            coEvery {
                fakeRepo.save(match { write -> write.snapshot.messages.any { it.content == "Confirmed remotely" } })
            } throws
                IllegalStateException("Synthetic cache failure")
            val (vm, _) = createViewModelWithSession()
            vm.sendMessage("Confirmed remotely")
            advanceUntilIdle()
            assertEquals(
                DraftState.UNKNOWN,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            assertTrue(vm.uiState.value.draftStorageError)
            assertTrue(
                vm.uiState.value.activeDraftIds
                    .isEmpty(),
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(any(), "Confirmed remotely", any(), any()) }
        }

    @Test
    fun draft_restoredQueuePayloadStartingWithSlashIsSentOnceAsLiteralPrompt() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            fakeSubmission.ready = false
            vm.sendMessage("/queue /help")
            advanceUntilIdle()
            val draft = fakeDraftStore.drafts.value.single()
            assertEquals("/help", draft.text)
            assertTrue(draft.queued)
            fakeSubmission.ready = true
            vm.restoreDraft(draft.id)
            advanceUntilIdle()
            vm.sendMessage("/help")
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "/help", any(), true) }
            verify(exactly = 0) { HermesWsClient.writeScoped(any(), WsMethods.COMMAND_DISPATCH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
        }

    @Test
    fun draft_offlineSurvivesRecreationAndReconnectWithoutSending() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            fakeSubmission.ready = false
            vm.sendMessage("Keep this offline")
            advanceUntilIdle()
            val stored = fakeDraftStore.drafts.value.single()
            assertEquals(DraftState.QUEUED, stored.state)
            assertTrue(
                vm.uiState.value.activeDraftIds
                    .isEmpty(),
            )
            assertFalse(
                vm.uiState.value.messages
                    .any { it.content == stored.text },
            )

            fakeSubmission.scope = fakeSubmission.scope.copy(processId = "next-process", generation = 2)
            val recreated = createViewModel()
            advanceUntilIdle()
            assertEquals(
                stored.copy(state = DraftState.DRAFT, queued = false),
                recreated.uiState.value.outgoingDrafts
                    .single(),
            )
            fakeSubmission.ready = true
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            recreated.restoreDraft(stored.id)
            advanceUntilIdle()
            assertEquals(stored.text, recreated.uiState.value.composerTextToRestore)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun draft_unknownIsDurableBeforeWireAndCannotBeRestoredOrReplayed() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            fakeSubmission.beforePrompt = {
                assertEquals(
                    DraftState.UNKNOWN,
                    fakeDraftStore.drafts.value
                        .single()
                        .state,
                )
            }
            fakeSubmission.nextPromptResult = SubmissionResult.Unknown
            vm.sendMessage("Possible remote action")
            advanceUntilIdle()
            val stored = fakeDraftStore.drafts.value.single()
            assertEquals(DraftState.UNKNOWN, stored.state)
            assertTrue(
                vm.uiState.value.activeDraftIds
                    .isEmpty(),
            )
            assertFalse(fakeRepo.dao.getMessagesForSession("session-123").any { it.content == stored.text })
            vm.restoreDraft(stored.id)
            advanceUntilIdle()
            assertNull(vm.uiState.value.composerTextToRestore)
            emitEvent(WsEvent.RpcError("late-untracked", JsonRpcError(500, "late failure")))
            advanceUntilIdle()
            assertNull(vm.uiState.value.errorMessage)
            assertEquals(
                DraftState.UNKNOWN,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            val recreated = createViewModel()
            advanceUntilIdle()
            assertEquals(
                stored,
                recreated.uiState.value.outgoingDrafts
                    .single(),
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(any(), stored.text, any(), any()) }
        }

    @Test
    fun draft_scopeChangeAtWireKeepsUnsentDraftUnderOriginalTarget() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val originalScope = fakeSubmission.scope
            fakeSubmission.beforePrompt = {
                fakeSubmission.scope = originalScope.copy(serverProfileId = "other-profile")
            }
            vm.sendMessage("Original target only")
            advanceUntilIdle()
            val stored = fakeDraftStore.drafts.value.single()
            assertEquals(originalScope, stored.scope)
            assertEquals(DraftState.DRAFT, stored.state)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            vm.restoreDraft(stored.id)
            vm.discardDraft(stored.id)
            advanceUntilIdle()
            assertNull(vm.uiState.value.composerTextToRestore)
            assertEquals(stored, fakeDraftStore.drafts.value.single())
        }

    @Test
    fun draft_delayedReceiptClearsDurableRecordWithoutResending() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            val receipt = CompletableDeferred<Unit>()
            fakeSubmission.beforePrompt = { receipt.await() }
            vm.sendMessage("Delayed receipt")
            runCurrent()
            assertEquals(
                DraftState.UNKNOWN,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            // An execution event can precede the matching RPC receipt; it must not lose the intent.
            emitEvent(WsEvent.MessageStart(sessionId))
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(
                DraftState.UNKNOWN,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            assertTrue(vm.uiState.value.isAgentTyping)
            receipt.complete(Unit)
            advanceUntilIdle()
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
            assertFalse(vm.uiState.value.responseStatusUnknown)
            assertTrue(fakeRepo.dao.getMessagesForSession(sessionId).any { it.content == "Delayed receipt" })
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Delayed receipt", any(), any()) }
        }

    @Test
    fun draft_confirmedPromptRemovesRecordAndCachesMessage() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            fakeSubmission.beforePrompt = {
                assertEquals(
                    DraftState.UNKNOWN,
                    fakeDraftStore.drafts.value
                        .single()
                        .state,
                )
                assertFalse(fakeRepo.dao.getMessagesForSession("session-123").any { it.content == "Confirmed prompt" })
            }
            vm.sendMessage("Confirmed prompt")
            advanceUntilIdle()
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
            assertTrue(fakeRepo.dao.getMessagesForSession("session-123").any { it.content == "Confirmed prompt" })
        }

    @Test
    fun testSlashCommand_help_addsHelpMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            } returns
                CompletableDeferred(
                    mapOf("type" to "exec", "output" to "**Available Commands:**\n\u2022 `/status`\n\u2022 `/new`"),
                )

            viewModel.sendMessage("/help")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("Available Commands") },
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("/status") },
            )
        }

    @Test
    fun testSlashCommand_new_createsNewSession() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/new")
            advanceUntilIdle()

            verify(atLeast = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun asyncAutoTitleAfterReplyRefreshesHistoryAndCurrentHeader() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.MessageDone(sessionId))
            advanceUntilIdle()

            var refreshId: String? = null
            every { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) } answers {
                val id = "title-refresh"
                refreshId = id
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }
            val title = "会話の自動命名を修正"
            val event =
                com.m57.hermescontrol.data.ws.EventParser.parseParams(
                    mapOf(
                        "type" to "session.title",
                        "session_id" to "runtime-session",
                        "payload" to mapOf("session_id" to sessionId, "title" to title),
                    ),
                )
            emitEvent(event)
            advanceUntilIdle()
            assertEquals("title-refresh", refreshId)

            emitEvent(
                WsEvent.RpcResult(
                    "title-refresh",
                    mapOf("sessions" to listOf(mapOf("id" to sessionId, "title" to title, "message_count" to 2.0))),
                ),
            )
            advanceUntilIdle()

            assertEquals(title, viewModel.uiState.value.chatTitle)
            assertEquals(
                title,
                viewModel.uiState.value.sessions
                    .single()
                    .title,
            )
            assertEquals(sessionId, viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.uiState.value.isAgentTyping)
        }

    @Test
    fun testSlashCommand_new_inBotChat_compactsInsteadOfCreatingSession() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            // Set session title to "Bot Chat" via SESSION_LIST result
            emitEvent(
                WsEvent.RpcResult(
                    id = "req-id-1",
                    result =
                        mapOf(
                            "sessions" to
                                listOf(
                                    mapOf(
                                        "id" to sessionId,
                                        "title" to "Bot Chat",
                                        "message_count" to 5.0,
                                    ),
                                ),
                        ),
                ),
            )
            advanceUntilIdle()

            // Reset recording on HermesWsClient to only inspect calls after /new
            clearMocks(HermesWsClient, answers = false, recordedCalls = true)

            viewModel.sendMessage("/new")
            advanceUntilIdle()

            // In Bot Chat, /new should NOT create a fresh session, but instead dispatch /compact
            verify(exactly = 0) {
                HermesWsClient.send(
                    WsMethods.SESSION_CREATE,
                    any(),
                    any(),
                )
            }
            assertTrue(
                viewModel.uiState.value.messages.any {
                    it.content.contains("compacting instead")
                },
            )
        }

    @Test
    fun profileSwitch_wipesOpenSessionForFreshStart() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            assertEquals(sessionId, viewModel.uiState.value.currentSessionId)

            // Profile switch fires → chat wipes the stale conversation. The
            // re-dialed socket's gateway.ready then auto-creates a FRESH
            // session in the new profile (handleGatewayReady, desktop
            // requestFreshSession parity).
            mockSwitchFlow.emit("meow")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSessionId)
            assertEquals("Hermes", viewModel.uiState.value.chatTitle)
        }

    @Test
    fun profileSwitch_endToEnd_recreatesFreshSessionInNewProfile() =
        runTest {
            val (viewModel, oldSessionId) = createViewModelWithSession()
            assertEquals(oldSessionId, viewModel.uiState.value.currentSessionId)

            // 1. Switch fires → stale conversation wiped.
            mockSwitchFlow.emit("meow")
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.currentSessionId)

            // 2. The coordinator re-dials the socket: disconnect first (status
            //    transition), then gateway.ready → handleGatewayReady:
            //    loadSessions (req-id-4), fetchCommandCatalog (req-id-5),
            //    then createNewSession (req-id-6) — desktop requestFreshSession.
            //    (The first ready cycle consumed req-ids 1-3.)
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // 3. Fresh session created → a NEW session id, not the old one.
            //    createNewSession is the LAST WS send of the ready cycle, so
            //    its id is the current counter value (robust against extra
            //    sends from the reconnect path).
            emitEvent(
                WsEvent.RpcResult("req-id-$reqCount", mapOf("session_id" to "session-meow")),
            )
            advanceUntilIdle()

            assertEquals("session-meow", viewModel.uiState.value.currentSessionId)
            assertTrue(viewModel.uiState.value.currentSessionId != oldSessionId)
            // The fresh session create goes through send() → WsProfileParams
            // injects the active profile (the WS profile-scoping seam).
            verify(atLeast = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testSlashCommand_fork_sendsSessionBranch() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // /fork with an optional branch title.
            viewModel.sendMessage("/fork my-fork")
            advanceUntilIdle()

            val branchSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH }
            assertNotNull("session.branch should be dispatched for /fork", branchSent)
            assertEquals(sessionId, branchSent!!.second["session_id"])
            assertEquals("my-fork", branchSent.second["name"])
        }

    @Test
    fun testSessionBranch_publishesRuntimeSessionId() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("branch-request")
                "branch-request"
            }

            viewModel.sendMessage("/fork")
            emitEvent(
                WsEvent.RpcResult(
                    "branch-request",
                    mapOf(
                        "session_id" to "runtime-branch",
                        "stored_session_id" to "stored-branch",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("runtime-branch", ActiveSessionHolder.activeSessionId.value)
        }

    @Test
    fun testSlashCommand_fork_withoutName_omitsNameParam() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("/fork")
            advanceUntilIdle()

            val branchSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH }
            assertNotNull("session.branch should be dispatched for /fork", branchSent)
            assertEquals(sessionId, branchSent!!.second["session_id"])
            assertFalse("name param should be omitted when no title given", branchSent.second.containsKey("name"))
        }

    @Test
    fun testSlashCommand_stop_sendsInterrupt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/stop")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun testSlashCommand_interrupt_sendsInterrupt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/interrupt")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun testSlashCommand_btw_dispatchesPromptBtw_andSetsBtwState() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(WsMethods.PROMPT_BTW, any(), any())
            } returns
                CompletableDeferred(
                    mapOf("task_id" to "btw_123456"),
                )

            viewModel.sendMessage("/btw which file was that in?")
            advanceUntilIdle()

            val btw = viewModel.uiState.value.btwState
            assertNotNull(btw)
            assertEquals("which file was that in?", btw?.question)
            assertEquals("btw_123456", btw?.taskId)
            assertTrue(btw?.isLoading == true)
            verify {
                HermesWsClient.request(
                    WsMethods.PROMPT_BTW,
                    mapOf("session_id" to sessionId, "text" to "which file was that in?"),
                    any(),
                )
            }
            // Ensure transcript messages were NOT polluted with the side question
            assertTrue(
                viewModel.uiState.value.messages
                    .none { it.content.contains("which file was that in?") },
            )
        }

    // ── Slash usage ranking (issue #865) ────────────────────────────────────

    @Test
    fun slashUsage_dispatchIncrementsCountPerCommand() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            } returns
                CompletableDeferred(
                    mapOf("type" to "exec", "output" to "ok"),
                )

            viewModel.sendMessage("/help")
            advanceUntilIdle()
            viewModel.sendMessage("/help")
            advanceUntilIdle()
            viewModel.sendMessage("/new")
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.slashUsageCounts["/help"])
            assertEquals(1, viewModel.uiState.value.slashUsageCounts["/new"])
        }

    @Test
    fun slashUsage_blockedCommand_notCounted() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/clear")
            advanceUntilIdle()

            // A blocklisted command can never dispatch — it must not climb
            // the autocomplete ranking either.
            assertTrue(
                viewModel.uiState.value.slashUsageCounts
                    .isEmpty(),
            )
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
        }

    // ── /resume · /history open the history tab (issue #864) ────────────────

    @Test
    fun slashCommand_resume_requestsHistoryNavigationWithoutGateway() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/resume")
            advanceUntilIdle()

            assertTrue(
                "openHistoryRequested must be set for the screen to navigate",
                viewModel.uiState.value.openHistoryRequested,
            )
            // Client-side only: no gateway round-trip for /resume.
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.SLASH_EXEC, any(), any())
            }
        }

    @Test
    fun testReplayGapResyncsOnlyMatchingChatAndFetchesFreshHistoryAfterResume() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            stubEmptySessionRests("stored-gap")
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("initial-resume")
                "initial-resume"
            }
            vm.switchSession("stored-gap")
            // Drain registration and hydration, not primary88's resume deadline.
            runCurrent()
            mockEventsFlow.emit(
                WsEvent.RpcResult("initial-resume", mapOf("session_id" to "runtime-gap", "resumed" to "stored-gap")),
            )
            advanceUntilIdle()

            val scope = fakeSubmission.scope
            val snapshot = CompletableDeferred<Any?>()
            coEvery { HermesWsClient.resumeAfterReplayGap(scope, "stored-gap") } coAnswers { snapshot.await() }
            val history =
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    total = 1,
                    messages =
                        listOf(
                            com.m57.hermescontrol.data.model.SessionMessage(
                                role = "assistant",
                                content = kotlinx.serialization.json.JsonPrimitive("Recovered history"),
                            ),
                        ),
                )
            coEvery {
                ApiClient.hermesApi.getSessionMessages("stored-gap", any(), any(), any(), any(), scope.serverProfileId)
            } returns
                retrofit2.Response.success(history)
            mockScopedEvents.emit(ScopedWsEvent(scope, ++eventOrdinal, null, gap = true, gapSessionId = "other"))
            mockScopedEvents.emit(
                ScopedWsEvent(scope.copy(generation = 999), ++eventOrdinal, null, true, "runtime-gap"),
            )
            runCurrent()
            coVerify(exactly = 0) { HermesWsClient.resumeAfterReplayGap(any(), any()) }

            repeat(2) { mockScopedEvents.emit(ScopedWsEvent(scope, ++eventOrdinal, null, true, "runtime-gap")) }
            runCurrent()
            coVerify(exactly = 1) { HermesWsClient.resumeAfterReplayGap(scope, "stored-gap") }
            assertFalse(
                vm.uiState.value.messages
                    .any { it.content == "Recovered history" },
            )
            snapshot.complete(mapOf("session_id" to "runtime-gap", "resumed" to "stored-gap", "running" to true))
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "Recovered history" },
            )
            assertTrue(vm.uiState.value.isAgentTyping)
            assertEquals("stored-gap", vm.uiState.value.currentSessionId)
        }

    @Test
    fun slashCommand_history_requestsHistoryNavigation() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/history")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.openHistoryRequested)
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
        }

    @Test
    fun consumeOpenHistoryRequest_clearsFlag() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/resume")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.openHistoryRequested)

            viewModel.consumeOpenHistoryRequest()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.openHistoryRequested)
        }

    @Test
    fun testSlashCommand_unknown_showsErrorMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            // Awaited RPC errors are handled by dispatchViaRpc, not an unowned WS event.
            every { HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any()) } returns
                CompletableDeferred<Any?>().apply {
                    completeExceptionally(HermesWsClient.HermesRpcException("Unknown command: nonexistent", -32601))
                }

            viewModel.sendMessage("/nonexistent")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("Unknown command: nonexistent") },
            )
        }

    @Test
    fun testSlashCommandStatusRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/status")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testSlashCommandSessionsRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/sessions")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testSlashCommandStatsRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/stats")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testBareModelCommand_opensPickerInsteadOfDispatch() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            // A bare "/model" must NOT dispatch a slash command; it opens the picker.
            viewModel.sendMessage("/model")
            advanceUntilIdle()

            assertTrue(
                "picker should be shown when bare /model is typed",
                viewModel.uiState.value.showModelPicker,
            )
            assertTrue(
                "picker should have preloaded providers (cached at GatewayReady)",
                viewModel.uiState.value.modelPickerProviders
                    .isNotEmpty(),
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun modelRefresh_keepsCachedOptionsAndDeduplicatesRequestsAcrossReopen() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            val response =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.ModelOptionsResponse>>()
            val updated =
                listOf(
                    com.m57.hermescontrol.data.model.ModelProvider(
                        slug = "openai",
                        name = "OpenAI",
                        models = listOf("new-model"),
                    ),
                )
            coEvery { api.getModelOptions(true, false) } coAnswers { response.await() }

            viewModel.openModelPicker()
            val cached = viewModel.uiState.value.modelPickerProviders
            viewModel.refreshModelOptions()
            viewModel.refreshModelOptions()
            runCurrent()

            assertTrue(viewModel.uiState.value.modelPickerLoading)
            assertEquals(cached, viewModel.uiState.value.modelPickerProviders)
            viewModel.closeModelPicker()
            viewModel.openModelPicker()
            assertTrue(viewModel.uiState.value.modelPickerLoading)
            viewModel.refreshModelOptions()
            coVerify(exactly = 1) { api.getModelOptions(true, false) }

            response.complete(
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .ModelOptionsResponse(updated),
                ),
            )
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.modelPickerLoading)
            assertEquals(updated, viewModel.uiState.value.modelPickerProviders)
            viewModel.closeModelPicker()
            viewModel.openModelPicker()
            assertEquals(updated, viewModel.uiState.value.modelPickerProviders)
            coVerify(exactly = 1) { api.getModelOptions(true, false) }
        }

    @Test
    fun modelRefresh_failureRetainsOptionsAndShowsPickerError() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            viewModel.openModelPicker()
            val cached = viewModel.uiState.value.modelPickerProviders
            val chatError = viewModel.uiState.value.errorMessage
            coEvery { ApiClient.hermesApi.getModelOptions(true, false) } throws java.io.IOException("offline")

            viewModel.refreshModelOptions()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.showModelPicker)
            assertFalse(viewModel.uiState.value.modelPickerLoading)
            assertEquals(cached, viewModel.uiState.value.modelPickerProviders)
            assertNotNull(viewModel.uiState.value.modelPickerError)
            assertEquals(chatError, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun modelRefreshCommand_opensPickerAndRefreshesCatalogWithoutDispatch() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val api = ApiClient.hermesApi

            viewModel.sendMessage("/model --refresh")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.showModelPicker)
            coVerify(exactly = 1) { api.getModelOptions(true, false) }
            verify(exactly = 0) { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testModelPickerSelection_hotSwapsCurrentSessionViaSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/model")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.showModelPicker)

            // Selecting a model must send the bare spec "gpt-4o --provider openai
            // --session" via the `config.set` RPC with key="model" (the gateway
            // routes key=="model" to _apply_model_switch; the /model prefix is
            // stripped before send because config.set does not parse slash
            // commands). NOT command.dispatch (4018s on /model), NOT prompt.submit
            // (LLM would treat it as text). Capture the config.set params.
            val modelCalls = mutableListOf<Triple<String, String, String>>()
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                val params = arg<Map<String, Any>>(1)
                modelCalls.add(
                    Triple(
                        params["key"] as String,
                        params["value"] as String,
                        params["session_id"] as String,
                    ),
                )
                "req-cfg-${modelCalls.size}"
            }

            viewModel.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()

            assertFalse("picker closes after selection", viewModel.uiState.value.showModelPicker)
            assertEquals(
                "openai/gpt-4o",
                viewModel.uiState.value.currentSessionModel,
            )
            verify { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) }
            val call = modelCalls.firstOrNull { it.first == "model" }
            assertNotNull("selection must route through config.set key=model", call)
            assertEquals("gpt-4o --provider openai --session", call!!.second)
            assertEquals(sessionId, call.third)
        }

    @Test
    fun testTypedModelCommandWithArg_dispatchesDirectly() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // A fully-typed "/model <model> --provider <slug> --session" bypasses the
            // picker and dispatches straight to the backend as a normal prompt.
            viewModel.sendMessage("/model gpt-4o --provider openai --session")
            advanceUntilIdle()

            assertFalse(
                "typed /model with arg should not open the picker",
                viewModel.uiState.value.showModelPicker,
            )
            // A fully-typed /model goes to the backend via the `config.set` RPC
            // (key="model"), which the gateway routes to _apply_model_switch. NOT
            // command.dispatch (4018s on /model) and NOT prompt.submit (LLM would
            // treat it as text).
            verify { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) }
        }

    @Test
    fun testTypedModelCommand_caseInsensitive_doesNotForwardSlashPrefix() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // A fully-typed /MODEL (uppercase) must still route through
            // config.set key=model with the BARE spec — the leading "/MODEL"
            // slash prefix must be stripped, or parse_model_flags on the
            // backend won't recognize it and the hot-swap silently fails.
            val modelCalls = mutableListOf<Triple<String, String, String>>()
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                val params = arg<Map<String, Any>>(1)
                modelCalls.add(
                    Triple(
                        params["key"] as String,
                        params["value"] as String,
                        params["session_id"] as String,
                    ),
                )
                "req-cfg-ci-${modelCalls.size}"
            }

            viewModel.sendMessage("/MODEL gpt-4o --provider openai --session")
            advanceUntilIdle()

            val call = modelCalls.firstOrNull { it.first == "model" }
            assertNotNull("uppercase /MODEL must route through config.set key=model", call)
            assertEquals(
                "slash prefix must be stripped before send",
                "gpt-4o --provider openai --session",
                call!!.second,
            )
            assertFalse(
                "value must not carry the literal /MODEL prefix",
                call.second.startsWith("/"),
            )
            assertEquals(sessionId, call.third)
        }

    @Test
    fun testModelSwitch_confirmRequired_triggersConfirmationDialogAndReSends() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            var lastReqId = ""
            val capturedParams = mutableListOf<Map<String, Any>>()
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                val params = arg<Map<String, Any>>(1)
                capturedParams.add(params)
                val id = "req-confirm-${capturedParams.size}"
                lastReqId = id
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // User sends model switch command that triggers privacy/expensive guard
            viewModel.sendMessage("/model muse-spark-1.3-contributor-free --provider opencode-free --session")
            advanceUntilIdle()

            assertEquals(1, capturedParams.size)
            assertNull(capturedParams[0]["confirm_expensive_model"])

            // Backend returns confirm_required = true
            emitEvent(
                WsEvent.RpcResult(
                    id = lastReqId,
                    result =
                        mapOf(
                            "key" to "model",
                            "value" to "muse-spark-1.3-contributor-free",
                            "confirm_required" to true,
                            "confirm_message" to "Meta training warning",
                        ),
                ),
            )
            advanceUntilIdle()

            // UI state now shows confirmation dialog message
            assertEquals("Meta training warning", viewModel.uiState.value.modelSwitchConfirmMessage)

            // User confirms
            viewModel.confirmModelSwitchExpensive()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.modelSwitchConfirmMessage)
            assertEquals(2, capturedParams.size)
            assertEquals(true, capturedParams[1]["confirm_expensive_model"])
            assertEquals(
                "muse-spark-1.3-contributor-free --provider opencode-free --session",
                capturedParams[1]["value"],
            )
            assertEquals(sessionId, capturedParams[1]["session_id"])
        }

    @Test
    fun testModelSwitch_dismissConfirmation_revertsModel() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            var lastReqId = ""
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                val id = "req-cfg-dismiss"
                lastReqId = id
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // Set an initial model
            viewModel.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            assertEquals("openai/gpt-4o", viewModel.uiState.value.currentSessionModel)

            // Switch to expensive model
            viewModel.sendSlashModel("opencode-free", "muse-spark-1.3-contributor-free")
            advanceUntilIdle()
            assertEquals("opencode-free/muse-spark-1.3-contributor-free", viewModel.uiState.value.currentSessionModel)

            // Backend requires confirmation
            emitEvent(
                WsEvent.RpcResult(
                    id = lastReqId,
                    result =
                        mapOf(
                            "key" to "model",
                            "value" to "muse-spark-1.3-contributor-free",
                            "confirm_required" to true,
                            "confirm_message" to "Meta training warning",
                        ),
                ),
            )
            advanceUntilIdle()
            assertEquals("Meta training warning", viewModel.uiState.value.modelSwitchConfirmMessage)

            // User dismisses/cancels
            viewModel.dismissModelSwitchConfirm()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.modelSwitchConfirmMessage)
            // Reverted back to previous model
            assertEquals("openai/gpt-4o", viewModel.uiState.value.currentSessionModel)
        }

    @Test
    fun modelPickIsReappliedWhenAgentBuildAttachesThePreviousModel() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.SessionInfo(mapOf("model" to "free-default", "provider" to "openrouter")))
            advanceUntilIdle()
            val capturedParams = mutableListOf<Map<String, Any>>()
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                capturedParams.add(arg(1))
                val id = "req-model-${capturedParams.size}"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendSlashModel("openrouter", "picked-model")
            advanceUntilIdle()
            assertEquals("openrouter/picked-model", viewModel.uiState.value.currentSessionModel)
            // Gateway acknowledges while its agent is still building: no session info yet.
            emitEvent(
                WsEvent.RpcResult(
                    "req-model-1",
                    mapOf("key" to "model", "value" to "picked-model", "scope" to "session"),
                ),
            )
            advanceUntilIdle()
            // The in-flight build attaches with the previous model and announces it.
            emitEvent(WsEvent.SessionInfo(mapOf("model" to "free-default", "provider" to "openrouter")))
            advanceUntilIdle()

            assertEquals(2, capturedParams.size)
            assertEquals("picked-model --provider openrouter --session", capturedParams[1]["value"])
            assertEquals(sessionId, capturedParams[1]["session_id"])
            verify(exactly = 0) { AuthManager.writeUiPreference(any(), "chat_model", any()) }

            // The re-apply hits the built agent, which switches and reports the pick.
            emitEvent(WsEvent.SessionInfo(mapOf("model" to "picked-model", "provider" to "openrouter")))
            emitEvent(
                WsEvent.RpcResult(
                    "req-model-2",
                    mapOf("key" to "model", "value" to "picked-model", "scope" to "session"),
                ),
            )
            advanceUntilIdle()
            assertEquals("openrouter/picked-model", viewModel.uiState.value.currentSessionModel)
            assertNull(viewModel.uiState.value.errorMessage)
            assertEquals(2, capturedParams.size)
            verify(exactly = 1) {
                AuthManager.writeUiPreference("test-connection-profile", "chat_model", "openrouter/picked-model")
            }
        }

    @Test
    fun modelPickThatTheServerKeepsIgnoringIsReportedAfterOneReapply() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            var calls = 0
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                calls++
                val id = "req-ignored-$calls"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }
            viewModel.sendSlashModel("openrouter", "picked-model")
            advanceUntilIdle()
            repeat(2) { attempt ->
                emitEvent(
                    WsEvent.RpcResult("req-ignored-${attempt + 1}", mapOf("key" to "model", "value" to "picked-model")),
                )
                emitEvent(WsEvent.SessionInfo(mapOf("model" to "free-default", "provider" to "openrouter")))
                advanceUntilIdle()
            }

            assertEquals(2, calls)
            assertEquals("openrouter/free-default", viewModel.uiState.value.currentSessionModel)
            assertNotNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun deferredModelPickIsNotReappliedBeforeTheNextTurn() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            var calls = 0
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                calls++
                arg<((String) -> Unit)?>(2)?.invoke("req-deferred")
                "req-deferred"
            }
            viewModel.sendSlashModel("openrouter", "picked-model")
            advanceUntilIdle()
            emitEvent(
                WsEvent.RpcResult(
                    "req-deferred",
                    mapOf("key" to "model", "value" to "picked-model", "deferred" to true),
                ),
            )
            emitEvent(WsEvent.SessionInfo(mapOf("model" to "free-default", "provider" to "openrouter")))
            advanceUntilIdle()

            assertEquals(1, calls)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun rejectedModelSwitchRevertsTheOptimisticChip() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            emitEvent(WsEvent.SessionInfo(mapOf("model" to "free-default", "provider" to "openrouter")))
            advanceUntilIdle()
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("req-rejected")
                "req-rejected"
            }
            viewModel.sendSlashModel("openrouter", "picked-model")
            advanceUntilIdle()
            assertEquals("openrouter/picked-model", viewModel.uiState.value.currentSessionModel)

            emitEvent(
                WsEvent.RpcError("req-rejected", JsonRpcError(code = 4001, message = "session not found")),
            )
            advanceUntilIdle()

            assertEquals("openrouter/free-default", viewModel.uiState.value.currentSessionModel)
            assertEquals("モデルを切り替えられませんでした: session not found", viewModel.uiState.value.errorMessage)
            verify(exactly = 0) { AuthManager.writeUiPreference(any(), "chat_model", any()) }
        }

    @Test
    fun testUndoCommand_dispatchesAndPrefillsComposer() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(
                    WsMethods.COMMAND_DISPATCH,
                    mapOf("name" to "undo", "arg" to "2", "session_id" to sessionId),
                    any(),
                )
            } returns
                CompletableDeferred(
                    mapOf(
                        "type" to "prefill",
                        "message" to "undone user message",
                        "notice" to "↶ Undid 2 turns",
                    ),
                )

            viewModel.sendMessage("/undo 2")
            advanceUntilIdle()

            // Prefill text is staged in UI state
            assertEquals("undone user message", viewModel.uiState.value.pendingPrefillText)
            // Notice is displayed as system message with rewind target feedback
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("↶ Undid 2 turns") && it.content.contains("undone user message") },
            )
            // Verify slash usage was recorded
            assertEquals(1, viewModel.uiState.value.slashUsageCounts["/undo"])

            // Consume prefill text
            viewModel.consumePendingPrefill()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.pendingPrefillText)
        }

    // ── Connection / init tests ──────────────────────────────────────────────

    @Test
    fun testInitialStateAndConnection() =
        runTest {
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

            createViewModel()
            advanceUntilIdle()

            verify { HermesWsClient.connect() }
        }

    @Test
    fun testAlreadyConnectedOnLaunch_createsSession() =
        runTest {
            mockConnectionStatus.value = ConnectionStatus.CONNECTED

            createViewModel()
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
            verify { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testGatewayReady_createsSessionIfNoneExists() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
            verify { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
            assertTrue(viewModel.uiState.value.isConnected)
        }

    @Test
    fun mainConversation_found_resumesBotChatTip() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.mainConversationResolver = { ChatViewModel.MainConversation.Found("bot-chat-tip") }

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            assertEquals("bot-chat-tip", viewModel.uiState.value.currentSessionId)
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "bot-chat-tip", "omit_messages" to true),
                    any(),
                )
            }
            verify(inverse = true) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun mainConversation_absent_createsTitledBotChat() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.mainConversationResolver = { ChatViewModel.MainConversation.Absent }

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_CREATE,
                    mapOf("source" to "desktop", "title" to ChatViewModel.BOT_CHAT_TITLE),
                    any(),
                )
            }
            assertEquals(ChatViewModel.BOT_CHAT_TITLE, viewModel.uiState.value.chatTitle)
        }

    @Test
    fun newSpecialistPersistsLazyBotChatBeforeSetupAndBlocksOrdinarySend() =
        runTest {
            val profile = "bot-test"
            val title = ChatViewModel.BOT_CHAT_TITLE
            val target = SpecialistChatTarget("test-connection-profile", 2L, profile)
            val greeting = SpecialistSetupMessage("greeting", "assistant", "どんなテーマを調べましょうか？")
            var snapshot = SpecialistSetupSnapshot(profile, greeting = greeting)
            val draft = SpecialistSetupDraft("draft", description = "技術調査")
            val api = mockk<HermesApiService>()
            val repository = SpecialistsRepository { api }
            val create = CreateSpecialistRequest(title = "調査bot", request_id = "create-fixture")
            val capabilities = CompletableDeferred<Unit>()
            val titled = CompletableDeferred<Any?>()
            var createId = ""
            coEvery { api.createSpecialist(create, any()) } returns
                Response.success(CreateSpecialistResponse(profile, setup = snapshot))
            coEvery { api.getSpecialistCapabilities(any()) } coAnswers {
                capabilities.await()
                Response.success(SpecialistCapabilitiesResponse(setup_conversation = true, setup_apply = true))
            }
            coEvery { api.getSpecialistSetup(profile, any()) } answers { Response.success(snapshot) }
            every { AuthManager.activeProfileId } returns MutableStateFlow(profile)
            every { AuthManager.connectionEpoch } returns MutableStateFlow(target.epoch)
            every { AuthManager.readSpecialistRequest(any()) } returns null
            every { AuthManager.writeSpecialistRequest(any(), any()) } returns Unit
            every { app.getString(R.string.specialist_chat_setup_required) } returns "Bot Chatで初期設定を続けてください。"
            val scope = fakeSubmission.scope.copy(serverProfileId = profile)
            fakeSubmission.scope = scope
            every { HermesWsClient.connectionScope } returns MutableStateFlow(scope)
            every { HermesWsClient.readBotChat(scope, title, any()) } answers {
                val sessions =
                    if (titled.isCompleted) {
                        listOf(
                            mapOf("title" to title, "resolved_id" to "saved"),
                        )
                    } else {
                        emptyList()
                    }
                CompletableDeferred<Any?>(mapOf("sessions" to sessions))
            }
            every { HermesWsClient.setBotChatTitle(scope, "runtime", title, any()) } returns titled
            every {
                HermesWsClient.send(WsMethods.SESSION_CREATE, mapOf("source" to "desktop", "title" to title), any())
            } answers {
                createId = "create-bot-chat"
                arg<(String) -> Unit>(2).invoke(createId)
                createId
            }

            val created = repository.createSpecialist(create, target.requestScope) as SpecialistApiOutcome.Success
            val viewModel = createViewModel(specialistsRepository = repository)
            runCurrent()
            viewModel.specialistChat.bind(target, null, created.data.setup)
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            runCurrent()
            assertEquals("create-bot-chat", createId)
            advanceTimeBy(10_001)
            emitEvent(WsEvent.RpcResult(createId, mapOf("session_id" to "runtime", "stored_session_id" to "saved")))
            runCurrent()

            verify(exactly = 1) { HermesWsClient.setBotChatTitle(scope, "runtime", title, any()) }
            assertTrue(viewModel.uiState.value.canonicalBotChatPending)
            viewModel.sendMessage("通常経路では送らない")
            runCurrent()
            assertEquals("Bot Chatで初期設定を続けてください。", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.specialistChat.submit("IDを確認するまで待つ"))
            assertEquals(
                listOf(
                    greeting.content,
                ),
                viewModel.specialistChat.state.value.projectMessages(emptyList()).map {
                    it.content
                },
            )

            titled.complete(mapOf("title" to title, "pending" to false))
            runCurrent()
            val chat = viewModel.uiState.value
            assertEquals("saved", chat.currentSessionId)
            assertEquals("saved", chat.canonicalBotChatId)
            assertFalse(chat.canonicalBotChatPending)
            assertFalse(chat.messages.any { it.content == "Session created" })
            viewModel.bindSpecialistChat(target, chat.canonicalBotChatId)
            capabilities.complete(Unit)
            runCurrent()

            coEvery { api.sendSpecialistSetupTurn(profile, any(), any()) } answers {
                val request = secondArg<SpecialistSetupTurnRequest>()
                assertEquals("saved", request.session_id)
                assertEquals("setup", request.mode)
                assertEquals(0, request.expected_revision)
                assertEquals(0, request.expected_conversation_revision)
                val turn =
                    SpecialistSetupTurn(
                        request.request_id,
                        request.session_id,
                        conversation_revision = 1,
                        revision = 1,
                        user = SpecialistSetupMessage("user", "user", request.text),
                        assistant = SpecialistSetupMessage("answer", "assistant", "設定案を作りました。"),
                        card = draft,
                    )
                snapshot =
                    snapshot.copy(
                        revision = 1,
                        conversation_revision = 1,
                        draft = draft,
                        conversation_turns = listOf(turn),
                    )
                Response.success(turn)
            }
            assertTrue(viewModel.specialistChat.submit("技術調査を担当して"))
            runCurrent()
            assertEquals(draft, viewModel.specialistChat.state.value.activeDraft)
            assertTrue(
                viewModel.specialistChat.state.value.projectMessages(emptyList()).any {
                    it.id ==
                        SPECIALIST_SETUP_REVIEW
                },
            )

            coEvery { api.confirmSpecialistSetup(profile, any(), any()) } answers {
                assertEquals(1, secondArg<SpecialistSetupConfirmRequest>().expected_revision)
                assertEquals(draft.id, secondArg<SpecialistSetupConfirmRequest>().draft_id)
                snapshot =
                    snapshot.copy(
                        setup_completed = true,
                        revision = 2,
                        settings_revision = 2,
                        activation = "pending_apply",
                    )
                Response.success(snapshot)
            }
            viewModel.specialistChat.confirm(draft.id, 1)
            runCurrent()
            assertTrue(viewModel.specialistChat.state.value.pendingApply)
            assertTrue(viewModel.blocksSpecialistRegularPrompt())

            coEvery { api.applySpecialistSetup(profile, any(), any()) } answers {
                val request = secondArg<SpecialistSetupApplyRequest>()
                assertEquals("saved", request.session_id)
                assertEquals(2, request.expected_revision)
                snapshot = snapshot.copy(activation = "applied", applied_revision = 2, routing_ready = true)
                Response.success(SpecialistSetupApplyResponse("applied", request.session_id, 2, 2))
            }
            viewModel.specialistChat.apply()
            runCurrent()
            assertFalse(viewModel.blocksSpecialistRegularPrompt())
            assertTrue(
                viewModel.specialistChat.state.value.snapshot
                    ?.routing_ready == true,
            )
            verify(exactly = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun mainConversation_unknown_keepsPlainNewSession() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.mainConversationResolver = { error("lookup failed") }

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_CREATE, mapOf("source" to "desktop"), any()) }
            assertEquals("Hermes", viewModel.uiState.value.chatTitle)
        }

    @Test
    fun mainConversation_refusedThenFound_retriesOnce() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            var calls = 0
            viewModel.mainConversationResolver = {
                calls++
                if (calls == 1) {
                    error("refused before the socket settled")
                } else {
                    ChatViewModel.MainConversation.Found("bot-chat-tip")
                }
            }

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            assertEquals(2, calls)
            assertEquals("bot-chat-tip", viewModel.uiState.value.currentSessionId)
            verify(inverse = true) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun mainConversation_reconnectFromEmptyFallback_returnsToBotChat() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("fallback-create")
                "fallback-create"
            }
            viewModel.mainConversationResolver = { error("lookup failed") }
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            emitEvent(
                WsEvent.RpcResult(
                    "fallback-create",
                    mapOf("session_id" to "fallback-runtime", "stored_session_id" to "fallback-storage"),
                ),
            )
            advanceUntilIdle()
            assertEquals("fallback-storage", viewModel.uiState.value.currentSessionId)

            viewModel.mainConversationResolver = { ChatViewModel.MainConversation.Found("bot-chat-tip") }
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            assertEquals("bot-chat-tip", viewModel.uiState.value.currentSessionId)
            verify(exactly = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun showMainConversation_fromPastConversation_switchesToBotChat() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("past-session")
            advanceUntilIdle()
            viewModel.mainConversationResolver = { ChatViewModel.MainConversation.Found("bot-chat-tip") }

            viewModel.showMainConversation()
            advanceUntilIdle()

            assertEquals("bot-chat-tip", viewModel.uiState.value.currentSessionId)
        }

    @Test
    fun showMainConversation_unknown_keepsOpenConversation() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("past-session")
            advanceUntilIdle()
            viewModel.mainConversationResolver = { ChatViewModel.MainConversation.Unknown }

            viewModel.showMainConversation()
            advanceUntilIdle()

            assertEquals("past-session", viewModel.uiState.value.currentSessionId)
            verify(inverse = true) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun mainConversation_lateLookup_doesNotOverrideAnOpenedSession() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            viewModel.mainConversationResolver = {
                gate.await()
                ChatViewModel.MainConversation.Found("bot-chat-tip")
            }

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            viewModel.switchSession("picked-by-user")
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("picked-by-user", viewModel.uiState.value.currentSessionId)
            verify(inverse = true) {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "bot-chat-tip", "omit_messages" to true),
                    any(),
                )
            }
        }

    @Test
    fun testGatewayReady_withInitialSessionId_switchesToIt() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.initialSessionId = "session-from-notification"

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            assertEquals("session-from-notification", viewModel.uiState.value.currentSessionId)
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-from-notification", "omit_messages" to true),
                    any(),
                )
            }
            // Should NOT create a new session
            verify(inverse = true) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testGatewayReady_pendingNotificationResumesWithoutCreatingSession() =
        runTest {
            stubEmptySessionRests("session-from-notification")
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("notification-resume")
                "notification-resume"
            }
            val controller = com.m57.hermescontrol.NavigationController
            controller.backStack = null
            controller.openChatSessionFromNotification("session-from-notification")
            try {
                val vm = createViewModel()
                advanceUntilIdle()
                mockConnectionStatus.value = ConnectionStatus.CONNECTED
                emitEvent(WsEvent.GatewayReady(null))
                runCurrent()
                emitEvent(
                    WsEvent.RpcResult(
                        "notification-resume",
                        mapOf("session_id" to "runtime-notification"),
                    ),
                )
                advanceUntilIdle()
                assertEquals("session-from-notification", vm.uiState.value.currentSessionId)
                assertEquals("session-from-notification", controller.pendingSessionId)
                verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
                verify(exactly = 1) {
                    HermesWsClient.send(
                        WsMethods.SESSION_RESUME,
                        mapOf("session_id" to "session-from-notification", "omit_messages" to true),
                        any(),
                    )
                }
            } finally {
                controller.consumePendingChatNavigation()
            }
        }

    // ── RPC result tests ─────────────────────────────────────────────────────

    @Test
    fun testSessionCreateRpcResult() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // GatewayReady sends SESSION_LIST (req-id-1), COMMANDS_CATALOG (req-id-2),
            // then SESSION_CREATE (req-id-3)
            emitEvent(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            assertEquals("session-123", viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                "Session created",
                viewModel.uiState.value.messages[0]
                    .content,
            )
        }

    @Test
    fun testSessionListRpcResult() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // GatewayReady sends SESSION_LIST (req-id-1), COMMANDS_CATALOG (req-id-2),
            // then SESSION_CREATE (req-id-3). Emit the SESSION_LIST result.
            emitEvent(
                WsEvent.RpcResult(
                    "req-id-1",
                    mapOf(
                        "sessions" to
                            listOf(
                                mapOf(
                                    "id" to "session-123",
                                    "title" to "My Session Title",
                                    "message_count" to 12.0,
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.sessions.size)
            assertEquals(
                "session-123",
                viewModel.uiState.value.sessions[0]
                    .id,
            )
            assertEquals(
                "My Session Title",
                viewModel.uiState.value.sessions[0]
                    .title,
            )
            assertEquals(
                12,
                viewModel.uiState.value.sessions[0]
                    .messageCount,
            )
        }

    // ── Streaming tests ──────────────────────────────────────────────────────

    @Test
    fun testMessageStreamingFlow() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // 1 — Start: reducer creates streamingMessage and sets isAgentTyping on uiState
            emitEvent(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertNotNull(viewModel.streamingState.value.streamingMessage)

            // 2 — Thinking
            emitEvent(WsEvent.ThinkingDelta("Thinking...", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("Thinking...", viewModel.streamingState.value.thinkingText)

            // 3 — Deeper thinking
            emitEvent(WsEvent.ThinkingDelta(" deeper", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("Thinking... deeper", viewModel.streamingState.value.thinkingText)

            // 4 — First token (flushed by isTestEnvironment)
            emitEvent(WsEvent.MessageToken("Hello", sessionId))
            advanceUntilIdle()
            assertFalse(viewModel.streamingState.value.isThinking)
            assertEquals(
                "Hello",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )

            // 5 — Second token
            emitEvent(WsEvent.MessageToken(" world", sessionId))
            advanceUntilIdle()
            assertEquals(
                "Hello world",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )

            // 6 — Complete: reducer finalizes message + resets streamingState
            emitEvent(WsEvent.MessageComplete("Hello world!", sessionId))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isAgentTyping)
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Hello world!",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
        }

    @Test
    fun testReasoningStreamingFlow() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // 1 — Reasoning available: block becomes visible (no token yet)
            emitEvent(WsEvent.ReasoningAvailable(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isReasoning)

            // 2 — Reasoning delta
            emitEvent(WsEvent.ReasoningDelta("Let me think", sessionId))
            advanceUntilIdle()
            assertEquals("Let me think", viewModel.streamingState.value.reasoningText)

            // 3 — Deeper reasoning
            emitEvent(WsEvent.ReasoningDelta(" step by step", sessionId))
            advanceUntilIdle()
            assertEquals("Let me think step by step", viewModel.streamingState.value.reasoningText)

            // 4 — Thinking still independent
            emitEvent(WsEvent.ThinkingDelta("thinking", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("thinking", viewModel.streamingState.value.thinkingText)
            // reasoning untouched
            assertEquals("Let me think step by step", viewModel.streamingState.value.reasoningText)

            // 5 — Complete: reducer finalizes message, attaching reasoning
            emitEvent(WsEvent.MessageComplete("The answer is 42", sessionId))
            advanceUntilIdle()

            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "The answer is 42",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            // reasoning carried onto the finalized UI message
            assertEquals(
                "Let me think step by step",
                viewModel.uiState.value.messages[1]
                    .reasoningText,
            )
            // reasoning persisted to the entity (survives reload)
            val persisted =
                fakeRepo.dao.getMessagesForSession(sessionId).first { it.role == "ASSISTANT" }
            assertEquals("Let me think step by step", persisted.reasoningText)
        }

    @Test
    fun testToolExecution_sealsInterimTextAndKeepsDistinctCompletePrefix() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("Calculating sum", sessionId))
            advanceUntilIdle()

            emitEvent(WsEvent.ToolStart("calculator", mapOf("input" to "2+2")))
            advanceUntilIdle()

            // Issue #842: the interim text is sealed as its own bubble at
            // tool.start (desktop parity), the stream tail clears, and the
            // sealed id is tracked as delivery identity, never a text prefix.
            // messages[0] = "Session created" system message
            assertEquals(
                "Calculating sum",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.ASSISTANT,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
            assertEquals(
                MessageRole.TOOL,
                viewModel.uiState.value.messages[2]
                    .role,
            )
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(
                listOf(
                    viewModel.uiState.value.messages[1]
                        .id,
                ),
                viewModel.streamingState.value.sealedOrphanIds,
            )

            // This is a distinct final body, even though it begins with the
            // commentary's words. Preserve its complete text without guessing reuse.
            emitEvent(WsEvent.MessageComplete("Calculating sum = 4", sessionId, responseReused = false))
            advanceUntilIdle()
            val assistant =
                viewModel.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
            assertEquals(2, assistant.size)
            assertEquals("Calculating sum", assistant[0].content)
            assertEquals("Calculating sum = 4", assistant[1].content)
            assertFalse(assistant[1].isStreaming)
            assertNull(viewModel.streamingState.value.streamingMessage)
        }

    @Test
    fun testMessageStart_finalizesPreviousStreamingMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("First part", sessionId))
            advanceUntilIdle()

            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("Second part", sessionId))
            advanceUntilIdle()

            // messages[0] = "Session created" system message
            assertEquals(
                "First part",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
            assertNotNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(
                "Second part",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    @Test
    fun testToolExecution_serializesDataAsJson() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            emitEvent(
                WsEvent.ToolStart(
                    name = "calculator",
                    data = mapOf("input" to "2+2", "nested" to mapOf("key" to "value")),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                MessageRole.TOOL,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertEquals(
                ToolStatus.RUNNING,
                viewModel.uiState.value.messages[1]
                    .toolStatus,
            )

            emitEvent(
                WsEvent.ToolComplete("calculator", mapOf("result" to "4", "exit_code" to 0)),
            )
            advanceUntilIdle()

            assertEquals(
                ToolStatus.COMPLETED,
                viewModel.uiState.value.messages[1]
                    .toolStatus,
            )
        }

    @Test
    fun round1ChatActualSendResumeDelayedSnapshotKeepsNewLiveRequestActionable() =
        runTest {
            stubEmptySessionRests("saved-A")
            val (vm, _) = createViewModelWithSession()

            fun field(name: String) = HermesWsClient::class.java.getDeclaredField(name).apply { isAccessible = true }
            val generation =
                (
                    field(
                        "connectionGeneration",
                    ).get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger
                ).get()
            fakeSubmission.scope = fakeSubmission.scope.copy(generation = generation)
            val scope = fakeSubmission.scope
            every { AuthManager.activeProfileId } returns MutableStateFlow(null)
            val socket = mockk<okhttp3.WebSocket>(relaxed = true)
            field("webSocket").set(HermesWsClient, socket)
            (field("connected").get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)
            (field("intentionalClose").get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)
            field("connectionBaseUrl").set(HermesWsClient, AuthManager.getBaseUrl())
            @Suppress("UNCHECKED_CAST")
            val scopes =
                field(
                    "_connectionScope",
                ).get(HermesWsClient) as MutableStateFlow<com.m57.hermescontrol.domain.hermes.ConnectionScope?>
            scopes.value = scope
            val listener =
                Class
                    .forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
                    .declaredConstructors
                    .single()
                    .apply { isAccessible = true }
                    .newInstance(
                        generation,
                        scope,
                    ) as okhttp3.WebSocketListener

            @Suppress("UNCHECKED_CAST")
            val actualEvents = field("parsedEvents").get(HermesWsClient) as MutableSharedFlow<WsEvent>

            @Suppress("UNCHECKED_CAST")
            val actualScoped = field("_scopedEvents").get(HermesWsClient) as MutableSharedFlow<ScopedWsEvent>
            val forwardEvents =
                backgroundScope.launch(
                    testDispatcher,
                ) { actualEvents.collect { mockEventsFlow.emit(it) } }
            val forwardScoped =
                backgroundScope.launch(
                    testDispatcher,
                ) { actualScoped.collect { mockScopedEvents.emit(it) } }
            val sent = mutableListOf<kotlinx.serialization.json.JsonObject>()
            every { socket.send(any<String>()) } answers {
                sent.add(
                    kotlinx.serialization.json.Json.parseToJsonElement(
                        firstArg<String>(),
                    ) as kotlinx.serialization.json.JsonObject,
                )
                true
            }
            var resumeCalls = 0
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                resumeCalls++
                callOriginal()
            }
            try {
                runCurrent()
                vm.switchSession("saved-A")
                runCurrent()
                assertEquals(
                    "Actual Chat resume must call real send; state=${vm.uiState.value.currentSessionId}",
                    1,
                    resumeCalls,
                )
                assertEquals(
                    "Actual send must reach connected fake socket; scope=${scopes.value}, connected=${field(
                        "connected",
                    ).get(HermesWsClient)}, base=${field("connectionBaseUrl").get(HermesWsClient)}",
                    1,
                    sent.size,
                )
                val resume = sent.single()
                assertEquals("session.resume", (resume["method"] as JsonPrimitive).content)

                fun live(id: String) =
                    """{"id":"outer-$id","method":"approval","params":{"session_id":"runtime-A","request_id":"inner-$id"}}"""
                listener.onMessage(socket, live("B"))
                runCurrent()
                listener.onMessage(
                    socket,
                    """{"id":${resume["id"]},"result":{"session_id":"runtime-A","resumed":"saved-A","open_requests":[{"id":"outer-A","method":"approval","params":{"session_id":"runtime-A","request_id":"inner-A"}}]}}""",
                )
                runCurrent()
                val repository =
                    ChatViewModel::class.java
                        .getDeclaredField("attentionRepository")
                        .apply {
                            isAccessible =
                                true
                        }.get(vm) as com.m57.hermescontrol.domain.hermes.AttentionRepository
                val newer = repository.items.value.single { it.ref.requestId == "inner-B" }
                assertTrue(
                    "Delayed Chat send-based snapshot must not expire newer live B",
                    repository.actionable(newer.ref, scope),
                )
                assertTrue(
                    repository.items.value.any {
                        it.ref.requestId == "inner-A" &&
                            repository.actionable(it.ref, scope)
                    },
                )
                assertTrue(
                    vm.uiState.value.messages
                        .any { it.approvalInfo?.ref?.requestId == "inner-A" },
                )
                assertEquals("runtime-A", ActiveSessionHolder.activeSessionId.value)
            } finally {
                forwardEvents.cancel()
                forwardScoped.cancel()
                field("webSocket").set(HermesWsClient, null)
                (field("connected").get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)
            }
        }

    @Test
    fun round1AttentionCardsRequireExactRuntimeAndScopeNotStoredId() =
        runTest {
            stubEmptySessionRests("saved-A")
            val (vm, _) = createViewModelWithSession()
            var resumeId = ""
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                resumeId = "round1-resume"
                arg<((String) -> Unit)?>(2)?.invoke(resumeId)
                resumeId
            }
            vm.switchSession("saved-A")
            runCurrent()
            mockEventsFlow.emit(WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-A", "resumed" to "saved-A")))
            runCurrent()
            var ordinal = 100L

            suspend fun emitAttention(
                runtime: String,
                scope: com.m57.hermescontrol.domain.hermes.ConnectionScope = fakeSubmission.scope,
            ) {
                for (method in listOf("approval", "clarify")) {
                    val params =
                        if (method ==
                            "approval"
                        ) {
                            "\"request_id\":\"inner-$runtime\""
                        } else {
                            "\"questions\":[{\"qid\":\"q\",\"question\":\"Synthetic?\"}]"
                        }
                    val event =
                        com.m57.hermescontrol.data.ws.EventParser.parse(
                            com.m57.hermescontrol.data.remote.OkHttpProvider.json
                                .decodeFromString<com.m57.hermescontrol.data.ws.JsonRpcResponse>(
                                    """{"id":"outer-$method-$runtime","method":"$method","params":{"session_id":"$runtime",$params}}""",
                                ),
                        )
                    mockScopedEvents.emit(ScopedWsEvent(scope, ++ordinal, event))
                    runCurrent()
                }
            }
            emitAttention("saved-A") // Another runtime may legitimately equal this Chat's storage key.
            emitAttention("runtime-other")
            assertTrue(
                "Storage/other runtime cards must stay out of current Chat",
                vm.uiState.value.messages.none {
                    it.approvalInfo !=
                        null ||
                        it.clarifyInfo != null
                },
            )
            emitAttention("runtime-A", fakeSubmission.scope.copy(generation = 999))
            assertTrue(
                vm.uiState.value.messages
                    .none { it.approvalInfo != null || it.clarifyInfo != null },
            )
            emitAttention("runtime-A")
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.approvalInfo != null },
            )
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.clarifyInfo != null },
            )
            val repository =
                ChatViewModel::class.java
                    .getDeclaredField("attentionRepository")
                    .apply { isAccessible = true }
                    .get(vm) as com.m57.hermescontrol.domain.hermes.AttentionRepository
            for (runtime in listOf("saved-A", "runtime-other", "runtime-A")) {
                assertEquals(
                    2,
                    repository.items.value.count {
                        it.ref.session.runtimeId == runtime &&
                            repository.actionable(it.ref, fakeSubmission.scope)
                    },
                )
            }
        }

    @Test
    fun modernParsedFormCarriesLockedSkipAndCancellationToExistingBubble() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            val event =
                com.m57.hermescontrol.data.ws.EventParser.parse(
                    com.m57.hermescontrol.data.remote.OkHttpProvider.json
                        .decodeFromString<com.m57.hermescontrol.data.ws.JsonRpcResponse>(
                            """{"id":"outer-modern-form","method":"clarify","params":{"session_id":"$sessionId","questions":[{"qid":"locked","question":"Known?"},{"qid":"open","question":"Unanswered?"}],"answers":{"locked":null}}}""",
                        ),
                ) as WsEvent.ClarifyRequest
            val info = observeClarify(vm, event)
            assertEquals("outer-modern-form", info.ref?.serverRequestId)
            assertEquals(
                com.m57.hermescontrol.domain.hermes
                    .ClarificationLock(null),
                info.questionLocks["locked"],
            )
            assertFalse(info.canRespond("locked"))
            assertTrue(info.canRespond("open"))
            mockScopedEvents.emit(
                ScopedWsEvent(
                    fakeSubmission.scope,
                    2,
                    WsEvent.ServerRequestCancel("outer-modern-form", sessionId, "clarify", "timeout"),
                ),
            )
            advanceUntilIdle()
            val updated =
                vm.uiState.value.messages
                    .last { it.clarifyInfo != null }
                    .clarifyInfo!!
            assertEquals(AttentionStatus.CONFIRMED, updated.questionStates["locked"])
            assertEquals(AttentionStatus.EXPIRED, updated.questionStates["open"])
            assertEquals(
                com.m57.hermescontrol.domain.hermes
                    .ClarificationLock(null),
                updated.questionLocks["locked"],
            )
            vm.respondToClarifyBatch(updated, mapOf("open" to "late", "locked" to "late"))
            advanceUntilIdle()
            coVerify(exactly = 0) { attentionTransport.respondClarification(any(), any()) }
        }

    // Clarifications must retain the rendered request identity until its exact ACK.
    private suspend fun TestScope.observeClarify(
        vm: ChatViewModel,
        event: WsEvent.ClarifyRequest = WsEvent.ClarifyRequest("Choose", listOf("Yes"), "clarify-1", "session-123"),
    ): ClarifyUi {
        mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, event))
        advanceUntilIdle()
        return vm.uiState.value.messages
            .last { it.clarifyInfo != null }
            .clarifyInfo!!
    }

    @Test
    fun testClarifyExactCardWaitsForAckAndDoubleTapSendsOnce() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val first = observeClarify(vm)
            val second = observeClarify(vm, WsEvent.ClarifyRequest("Choose", listOf("Yes"), "second", "session-123"))
            val reply = CompletableDeferred<SubmissionResult<Boolean>>()
            coEvery { attentionTransport.respondClarification(any(), any()) } coAnswers { reply.await() }
            vm.respondToClarify(first, "Yes")
            vm.respondToClarify(first, "Yes")
            runCurrent()
            assertEquals(
                AttentionStatus.SUBMITTING,
                vm.uiState.value.messages
                    .first { it.clarifyInfo?.ref == first.ref }
                    .clarifyInfo!!
                    .questionStates["q0"],
            )
            assertEquals(
                AttentionStatus.PENDING,
                vm.uiState.value.clarifyRequest!!
                    .questionStates["q0"],
            )
            assertEquals(
                second.ref,
                vm.uiState.value.clarifyRequest!!
                    .ref,
            )
            coVerify(exactly = 1) { attentionTransport.respondClarification(first.ref!!, "Yes") }
            reply.complete(SubmissionResult.Confirmed("ack", true))
            advanceUntilIdle()
            assertEquals(
                AttentionStatus.CONFIRMED,
                vm.uiState.value.messages
                    .first { it.clarifyInfo?.ref == first.ref }
                    .clarifyInfo!!
                    .questionStates["q0"],
            )
            assertFalse(sameLogicalMessage(vm.uiState.value.messages[1], vm.uiState.value.messages[2]))
        }

    @Test
    fun queue_messageDoneClearsTheCurrentClarificationAndReleasesTheNextInput() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(session))
            runCurrent()
            vm.sendMessage("/queue After the question")
            advanceUntilIdle()
            observeClarify(vm)
            emitEvent(WsEvent.MessageDone("other-session"))
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.clarifyRequest)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            assertNull(vm.uiState.value.clarifyRequest)
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "After the question", any(), true) }
            coVerify(exactly = 0) { attentionTransport.respondClarification(any(), any()) }
        }

    @Test
    fun confirmedClarificationClearsTheActiveRequestButRetainsItsEvidence() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val info = observeClarify(vm)
            vm.respondToClarify(info, "Yes")
            advanceUntilIdle()
            assertNull(vm.uiState.value.clarifyRequest)
            assertEquals(
                AttentionStatus.CONFIRMED,
                vm.uiState.value.messages
                    .last { it.clarifyInfo?.ref == info.ref }
                    .clarifyInfo!!
                    .questionStates["q0"],
            )
        }

    @Test
    fun testClarifyBatchSendsOnlyEnteredQuestionsAndKeepsPartialState() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val info =
                observeClarify(
                    vm,
                    WsEvent.ClarifyRequest(
                        "Batch",
                        null,
                        "batch",
                        "session-123",
                        questions =
                            listOf(
                                WsEvent.ClarifyQuestion("a", "First"),
                                WsEvent.ClarifyQuestion("b", "Second"),
                            ),
                    ),
                )
            vm.respondToClarifyBatch(info, mapOf("a" to " Answer ", "b" to " ", "foreign" to "ignored"))
            advanceUntilIdle()
            coVerify(
                exactly = 1,
            ) { attentionTransport.respondClarification(info.ref!!.copy(questionId = "a"), "Answer") }
            coVerify(exactly = 1) { attentionTransport.respondClarification(any(), any()) }
            assertEquals(
                mapOf("a" to AttentionStatus.CONFIRMED, "b" to AttentionStatus.PENDING),
                vm.uiState.value.clarifyRequest!!
                    .questionStates,
            )
            vm.dismissClarify(info)
            advanceUntilIdle()
            coVerify(exactly = 1) {
                attentionTransport.respondClarification(
                    info.ref!!.copy(questionId = "b"),
                    "The user cancelled — no answer provided.",
                )
            }
            coVerify(exactly = 2) { attentionTransport.respondClarification(any(), any()) }
            assertNull(vm.uiState.value.clarifyRequest)
        }

    @Test
    fun testClarifyUnknownReplayDoesNotResend() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val info = observeClarify(vm)
            coEvery { attentionTransport.respondClarification(any(), any()) } returns SubmissionResult.Unknown
            vm.respondToClarify(info, "Yes")
            advanceUntilIdle()
            val replay = observeClarify(vm)
            assertEquals(AttentionStatus.UNKNOWN, replay.questionStates["q0"])
            assertNotNull(vm.uiState.value.clarifyRequest)
            vm.respondToClarify(replay, "Yes")
            vm.dismissClarify(info)
            advanceUntilIdle()
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.clarifyInfo != null },
            )
            coVerify(exactly = 1) { attentionTransport.respondClarification(any(), any()) }
        }

    @Test
    fun testClarifyMissingIdentityAndMalformedQuestionsCannotRespond() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            for (event in listOf(
                WsEvent.ClarifyRequest("No request", null, null, "session-123"),
                WsEvent.ClarifyRequest("No session", null, "request", null),
                WsEvent.ClarifyRequest("Blank qid", null, "request", "session-123", questionId = ""),
                WsEvent.ClarifyRequest(
                    "Duplicates",
                    null,
                    "request",
                    "session-123",
                    questions = listOf(WsEvent.ClarifyQuestion("a", "First"), WsEvent.ClarifyQuestion("a", "Second")),
                ),
            )) {
                val info = observeClarify(vm, event)
                assertNull(info.ref)
                assertFalse(info.responseEnabled)
                assertNull(vm.uiState.value.clarifyRequest)
                vm.respondToClarify(info, "Yes")
                vm.dismissClarify(info)
            }
            advanceUntilIdle()
            coVerify(exactly = 0) { attentionTransport.respondClarification(any(), any()) }
        }

    @Test
    fun testClarifyOfflineAndEveryScopeMismatchCannotRespond() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val info = observeClarify(vm)
            val original = fakeSubmission.scope
            fakeSubmission.ready = false
            vm.respondToClarify(info, "Yes")
            advanceUntilIdle()
            fakeSubmission.ready = true
            for (scope in listOf(
                original.copy(generation = 2),
                original.copy(serverIdentity = "other"),
                original.copy(connectionProfileId = "other"),
                original.copy(serverProfileId = "other"),
                original.copy(processId = "other"),
            )) {
                fakeSubmission.scope = scope
                vm.respondToClarify(info, "Yes")
                advanceUntilIdle()
            }
            coVerify(exactly = 0) { attentionTransport.respondClarification(any(), any()) }
        }

    @Test
    fun testClarifyExpireRequiresExactIdentityAndLateAckCannotUndoIt() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val info = observeClarify(vm)
            val reply = CompletableDeferred<SubmissionResult<Boolean>>()
            coEvery { attentionTransport.respondClarification(any(), any()) } coAnswers { reply.await() }
            vm.respondToClarify(info, "Yes")
            runCurrent()
            for (event in listOf(
                WsEvent.ClarifyExpire(null, "session-123"),
                WsEvent.ClarifyExpire("clarify-1", null),
                WsEvent.ClarifyExpire("other", "session-123"),
            )) {
                mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, event))
            }
            runCurrent()
            assertEquals(
                AttentionStatus.SUBMITTING,
                vm.uiState.value.clarifyRequest!!
                    .questionStates["q0"],
            )
            mockScopedEvents.emit(
                ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, WsEvent.ClarifyExpire("clarify-1", "session-123")),
            )
            runCurrent()
            reply.complete(SubmissionResult.Confirmed("late", true))
            advanceUntilIdle()
            assertEquals(
                AttentionStatus.EXPIRED,
                vm.uiState.value.messages
                    .last { it.clarifyInfo?.ref == info.ref }
                    .clarifyInfo!!
                    .questionStates["q0"],
            )
            assertNull(vm.uiState.value.clarifyRequest)
        }

    @Test
    fun testClarifySeparateExplicitQuestionIdsAndNotSentRetryRequireNewTap() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val first = observeClarify(vm, WsEvent.ClarifyRequest("First", null, "request", "session-123", "a"))
            val second = observeClarify(vm, WsEvent.ClarifyRequest("Second", null, "request", "session-123", "b"))
            assertEquals(
                2,
                vm.uiState.value.messages
                    .count { it.clarifyInfo != null },
            )
            assertEquals("a", first.ref!!.questionId)
            assertEquals("b", second.ref!!.questionId)
            coEvery { attentionTransport.respondClarification(any(), any()) } returns SubmissionResult.NotSent
            vm.respondToClarify(first, "Answer")
            advanceUntilIdle()
            assertEquals(
                AttentionStatus.PENDING,
                vm.uiState.value.messages
                    .first { it.clarifyInfo?.ref == first.ref }
                    .clarifyInfo!!
                    .questionStates["a"],
            )
            coVerify(exactly = 1) { attentionTransport.respondClarification(first.ref, "Answer") }
            vm.respondToClarify(first, "Answer")
            advanceUntilIdle()
            coVerify(exactly = 2) { attentionTransport.respondClarification(first.ref, "Answer") }
            coVerify(exactly = 0) { attentionTransport.respondClarification(second.ref, any()) }
        }

    @Test
    fun testClarifyOldCardAfterSessionSwitchCannotRespond() =
        runTest {
            stubEmptySessionRests("session-b")
            val (vm, _) = createViewModelWithSession()
            val info = observeClarify(vm)
            vm.switchSession("session-b")
            advanceUntilIdle()
            vm.respondToClarify(info, "Answer")
            vm.dismissClarify(info)
            advanceUntilIdle()
            coVerify(exactly = 0) { attentionTransport.respondClarification(any(), any()) }
        }

    @Test
    fun testClarifyUnscopedAndOldSocketAreIgnored() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val event = WsEvent.ClarifyRequest("Choose", null, "request", "session-123")
            emitEvent(event)
            mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope.copy(generation = 77), 1, event))
            advanceUntilIdle()
            assertNull(vm.uiState.value.clarifyRequest)
            assertTrue(
                vm.uiState.value.messages
                    .none { it.clarifyInfo != null },
            )
        }

    @Test
    fun vaultRequestDisplaysOnlyForItsSessionAndSocketAndSendsOutsideTranscript() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val request =
                com.m57.hermescontrol.data.ws.VaultPromptRequest(
                    "login",
                    "session-123",
                    com.m57.hermescontrol.data.ws.VaultPromptKind.LOGIN,
                    "https://example.test",
                )
            mockScopedEvents.emit(
                ScopedWsEvent(fakeSubmission.scope.copy(generation = 999), 1, WsEvent.VaultRequest(request)),
            )
            mockScopedEvents.emit(
                ScopedWsEvent(
                    fakeSubmission.scope,
                    ++eventOrdinal,
                    WsEvent.VaultRequest(request.copy(sessionId = "other")),
                ),
            )
            advanceUntilIdle()
            assertNull(vm.uiState.value.vaultPrompt)
            mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, WsEvent.VaultRequest(request)))
            advanceUntilIdle()
            val prompt = vm.uiState.value.vaultPrompt!!
            val messages = vm.uiState.value.messages
            every { HermesWsClient.respondToVaultPrompt(any(), any(), any()) } returns true
            assertTrue(vm.respondToVaultPrompt(prompt, "dummy-credentials"))
            assertFalse(vm.respondToVaultPrompt(prompt, "dummy-credentials"))
            advanceUntilIdle()
            assertNull(vm.uiState.value.vaultPrompt)
            assertEquals(messages, vm.uiState.value.messages)
            verify(
                exactly = 1,
            ) { HermesWsClient.respondToVaultPrompt(fakeSubmission.scope, "login", "dummy-credentials") }
        }

    @Test
    fun vaultRequestFailureCancellationAndScopeChangeNeverBecomeImplicitDecline() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val request =
                com.m57.hermescontrol.data.ws.VaultPromptRequest(
                    "login",
                    "session-123",
                    com.m57.hermescontrol.data.ws.VaultPromptKind.LOGIN,
                    "https://example.test",
                )
            mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, WsEvent.VaultRequest(request)))
            advanceUntilIdle()
            every { HermesWsClient.respondToVaultPrompt(any(), any(), any()) } returns false
            assertFalse(vm.respondToVaultPrompt(vm.uiState.value.vaultPrompt!!, "dummy"))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.vaultPrompt!!
                    .sendFailed,
            )
            mockScopedEvents.emit(
                ScopedWsEvent(fakeSubmission.scope, ++eventOrdinal, WsEvent.ServerRequestCancel("old", "session-123")),
            )
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.vaultPrompt)
            val prompt = vm.uiState.value.vaultPrompt!!
            fakeSubmission.scope = fakeSubmission.scope.copy(serverProfileId = "other")
            assertFalse(vm.respondToVaultPrompt(prompt, "dummy"))
            mockScopedEvents.emit(ScopedWsEvent(prompt.scope, 3, WsEvent.ServerRequestCancel("login", "session-123")))
            fakeSubmission.scope = prompt.scope
            advanceUntilIdle()
            assertNull(vm.uiState.value.vaultPrompt)
            verify(exactly = 0) { HermesWsClient.respondToVaultPrompt(any(), any(), "") }
        }

    // ── Attachments ──────────────────────────────────────────────────────────

    /** Add [count] dummy attachments so a test starts with a populated list. */
    private fun TestScope.addDummyAttachments(
        viewModel: ChatViewModel,
        count: Int,
    ) {
        repeat(count) { i ->
            viewModel.addAttachment("uri$i", "file$i.txt", "text/plain", (i + 1) * 100L)
        }
        advanceUntilIdle()
    }

    @Test
    fun testAddAttachment() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment(
                uri = "content://dummy/1",
                name = "dummy.txt",
                mimeType = "text/plain",
                size = 1024L,
            )
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("content://dummy/1", pending[0].uri)
            assertEquals("dummy.txt", pending[0].name)
        }

    @Test
    fun addAttachments_keepsEverySelectedImageInOrder() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            val selected =
                listOf(
                    Attachment("content://image/1", "one.jpg", "image/jpeg", 100L),
                    Attachment("content://image/2", "two.png", "image/png", 200L),
                    Attachment("content://image/3", "three.webp", "image/webp", 300L),
                )

            viewModel.addAttachments(selected)
            advanceUntilIdle()

            assertEquals(selected, viewModel.uiState.value.pendingAttachments)
        }

    @Test
    fun sendMessage_mixedAttachmentsWithOversizedFileAreRejectedBeforeReading() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver

            viewModel.addAttachment(
                uri = "content://image/photo",
                name = "photo.jpg",
                mimeType = "image/jpeg",
                size = 1024,
            )
            viewModel.addAttachment(
                uri = "content://oversized/archive",
                name = "archive.zip",
                mimeType = "application/zip",
                size = MAX_CHAT_ATTACHMENT_BYTES + 1,
            )

            viewModel.sendMessage("Inspect this archive")
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)
            assertEquals("Inspect this archive", viewModel.uiState.value.composerTextToRestore)
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("too large") == true,
            )
            verify(exactly = 0) { contentResolver.openInputStream(any()) }

            viewModel.consumeComposerTextRestore()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.composerTextToRestore)
        }

    @Test
    fun sendMessage_unknownSizeOversizedAttachmentRestoresComposerWithoutGhostMessage() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val uriString = "content://unknown/large-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } returns oversizedStream

            viewModel.addAttachment(
                uri = uriString,
                name = "unknown-size.bin",
                mimeType = "application/octet-stream",
                size = 0,
            )

            viewModel.sendMessage("Inspect this file")
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)
            assertEquals("Inspect this file", viewModel.uiState.value.composerTextToRestore)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Inspect this file" },
            )
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("too large") == true,
            )
        }

    @Test
    fun sendMessage_oversizedDuringSnapshotLeavesNoPersistedGhostMessage() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val persistedBeforeSend = fakeRepo.dao.count()
            val uriString = "content://unknown/growing-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } returns oversizedStream

            viewModel.addAttachment(
                uri = uriString,
                name = "growing-file.bin",
                mimeType = "application/octet-stream",
                size = 0,
            )

            viewModel.sendMessage("Inspect changing file")
            advanceUntilIdle()

            assertEquals(persistedBeforeSend, fakeRepo.dao.count())
            assertFalse(
                fakeRepo.dao
                    .getMessagesForSession("session-123")
                    .any { it.content == "Inspect changing file" },
            )
            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Inspect changing file" },
            )
        }

    @Test
    fun sendMessage_draftPersistenceFailureRestoresComposerAndDoesNotDispatch() =
        runTest {
            // The durable pre-send record is now the draft, not the confirmed-message cache.
            fakeDraftStore.failSave = true
            val (viewModel, _) = createViewModelWithSession()

            viewModel.sendMessage("keep this prompt")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.messages.any { it.content == "keep this prompt" })
            assertEquals("keep this prompt", state.composerTextToRestore)
            assertTrue(state.draftStorageError)
            assertFalse(state.isAgentTyping)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun sendMessage_laterGrowingAttachmentDoesNotPartiallyUploadEarlierAttachments() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val imageUriString = "content://image/valid"
            val growingUriString = "content://unknown/growing-file"
            val imageUri = mockk<Uri>()
            val growingUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            mockkStatic(Uri::class)
            every { Uri.parse(imageUriString) } returns imageUri
            every { Uri.parse(growingUriString) } returns growingUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(imageUri) } returns
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            every { contentResolver.openInputStream(growingUri) } returns oversizedStream

            viewModel.addAttachments(
                listOf(
                    Attachment(imageUriString, "photo.jpg", "image/jpeg", 4),
                    Attachment(growingUriString, "growing.bin", "application/octet-stream", 0),
                ),
            )

            viewModel.sendMessage("Inspect both")
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.request(
                    match { it == WsMethods.IMAGE_ATTACH_BYTES || it == WsMethods.FILE_ATTACH },
                    any(),
                    any(),
                )
            }
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)
        }

    @Test
    fun sendMessage_oversizeResultAfterSessionSwitchDoesNotMutateNewSession() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val uriString = "content://unknown/session-switch-large-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
                        remaining -= count
                        return count
                    }
                }

            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } answers {
                viewModel.switchSession("session-456")
                oversizedStream
            }

            viewModel.addAttachment(
                uri = uriString,
                name = "large.bin",
                mimeType = "application/octet-stream",
                size = 0,
            )

            viewModel.sendMessage("Inspect in the old session")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("session-456", state.currentSessionId)
            assertTrue(state.pendingAttachments.isEmpty())
            assertNull(state.composerTextToRestore)
            assertNull(state.errorMessage)
        }

    @Test
    fun sendMessage_promptErrorAfterSessionSwitchDoesNotMutateNewSession() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, oldSessionId) = createViewModelWithSession()
            val uriString = "content://file/session-switch-valid-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val promptRequestId = "old-session-prompt"

            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } answers {
                viewModel.switchSession("session-456")
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            }
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(promptRequestId)
                promptRequestId
            }

            viewModel.addAttachment(
                uri = uriString,
                name = "valid.bin",
                mimeType = "application/octet-stream",
                size = 4,
            )

            viewModel.sendMessage("Send in the old session")
            advanceUntilIdle()

            // A selection change during attachment preparation now stops before any prompt write.
            verify(exactly = 0) {
                HermesWsClient.sendMessage(
                    oldSessionId,
                    match { it.contains("Send in the old session") },
                    any(),
                    any(),
                )
            }
            emitEvent(
                WsEvent.RpcError(
                    promptRequestId,
                    JsonRpcError(code = 4001, message = "old session rejected prompt"),
                ),
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("session-456", state.currentSessionId)
            assertNull(state.errorMessage)
        }

    @Test
    fun testRemoveAttachment_validIndex() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            viewModel.addAttachment("uri2", "file2.txt", "text/plain", 200)
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            viewModel.removeAttachment(0)
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri2", pending[0].uri)
        }

    @Test
    fun testRemoveAttachment_invalidIndex() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)

            // Out of bounds index should not crash or change list
            viewModel.removeAttachment(5)
            viewModel.removeAttachment(-1)
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri1", pending[0].uri)
        }

    @Test
    fun testRemoveAttachment_mixedValidAndInvalidSequence() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            addDummyAttachments(viewModel, 3) // [uri0, uri1, uri2]

            // 1. Remove a valid index (the middle item) → list shrinks correctly.
            viewModel.removeAttachment(1)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)
            assertEquals(
                "uri0",
                viewModel.uiState.value.pendingAttachments[0]
                    .uri,
            )
            assertEquals(
                "uri2",
                viewModel.uiState.value.pendingAttachments[1]
                    .uri,
            )

            // 2. Fire invalid removals (out of bounds + negative) — must be no-ops.
            viewModel.removeAttachment(99)
            viewModel.removeAttachment(-1)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            // 3. Another valid removal on the shifted list → still consistent.
            viewModel.removeAttachment(1)
            advanceUntilIdle()
            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri0", pending[0].uri)
        }

    @Test
    fun testClearAttachments() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            viewModel.addAttachment("uri2", "file2.txt", "text/plain", 200)
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            viewModel.clearAttachments()
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertTrue(pending.isEmpty())
        }

    // ── Send message ─────────────────────────────────────────────────────────

    @Test
    fun testSendMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            ActiveSessionHolder.set(sessionId, "stale-session")

            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Hello Hermes",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.USER,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertEquals(sessionId, ActiveSessionHolder.resolveStoredSessionId(sessionId))

            verify { HermesWsClient.sendMessage(sessionId, "Hello Hermes", any()) }
        }

    @Test
    fun testSendMessagePreservesQuotedReplyWireText() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val reply = quotedReplyMessage("Original\nSecond line", "Follow-up")
            val expected = "> Original\n> Second line\n\nFollow-up"

            viewModel.sendMessage(reply)
            advanceUntilIdle()

            assertEquals(
                expected,
                viewModel.uiState.value.messages
                    .last()
                    .content,
            )
            verify { HermesWsClient.sendMessage(sessionId, expected, any()) }
        }

    @Test
    fun testSendMessageRedirectWhenStreaming() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // First send starts streaming
            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isAgentTyping)

            // Second send while streaming triggers redirect
            viewModel.sendMessage("Wait, correction", steer = true)
            advanceUntilIdle()

            verify { HermesWsClient.sendRedirect(sessionId, "Wait, correction", any()) }
        }

    // ── Session switch ───────────────────────────────────────────────────────

    @Test
    fun testSwitchSession_opensSelectedHistorySessionInsteadOfLatestDescendant() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            coEvery { ApiClient.hermesApi.getLatestDescendant("session-456") } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.LatestDescendantResponse(
                        requested_session_id = "session-456",
                        session_id = "unrelated-descendant",
                        changed = true,
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertEquals("session-456", viewModel.uiState.value.currentSessionId)
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )

            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-456", "omit_messages" to true),
                    any(),
                )
            }
        }

    @Test
    fun testSwitchSession_ignoresLateResumeResultFromPreviousSelection() =
        runTest {
            stubEmptySessionRests("session-a", "session-b")
            val (viewModel, _) = createViewModelWithSession()
            val resumeRequests = mutableMapOf<String, String>()
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val sessionId = arg<Map<String, String>>(1).getValue("session_id")
                val requestId = "resume-$sessionId"
                resumeRequests[sessionId] = requestId
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.switchSession("session-a")
            runCurrent()
            viewModel.switchSession("session-b")
            runCurrent()
            emitEvent(
                WsEvent.RpcResult(
                    resumeRequests.getValue("session-b"),
                    mapOf("session_id" to "runtime-b", "resumed" to "session-b"),
                ),
            )
            emitEvent(
                WsEvent.RpcResult(
                    resumeRequests.getValue("session-a"),
                    mapOf("session_id" to "runtime-a", "resumed" to "session-a"),
                ),
            )
            advanceUntilIdle()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertEquals("runtime-b", ActiveSessionHolder.activeSessionId.value)
        }

    @Test
    fun testReconnect_ignoresSupersededResumeForSameSession() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            val resumeRequests = mutableListOf<String>()
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val requestId = "resume-${resumeRequests.size + 1}"
                resumeRequests += requestId
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.switchSession("session-a")
            runCurrent()
            emitEvent(WsEvent.GatewayReady(null))
            runCurrent()
            emitEvent(
                WsEvent.RpcResult(resumeRequests[1], mapOf("session_id" to "runtime-new")),
            )
            emitEvent(
                WsEvent.RpcResult(resumeRequests[0], mapOf("session_id" to "runtime-old")),
            )
            advanceUntilIdle()

            assertEquals("session-a", viewModel.uiState.value.currentSessionId)
            assertEquals("runtime-new", ActiveSessionHolder.activeSessionId.value)
        }

    @Test
    fun testSwitchSession_ignoresLateResumeErrorBeforeReducerMutation() =
        runTest {
            val mockApi = ApiClient.hermesApi
            val messagesA =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            val messagesB =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            every { AuthManager.getBaseUrl() } returns "http://test.local/"
            coEvery { mockApi.getSessions(any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .SessionListResponse(sessions = emptyList(), total = 0),
                )
            coEvery {
                mockApi.getSessionMessages(
                    "session-a",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesA.await() }
            coEvery {
                mockApi.getSessionMessages(
                    "session-b",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesB.await() }

            val (viewModel, _) = createViewModelWithSession()
            val resumeRequests = mutableMapOf<String, String>()
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val sessionId = arg<Map<String, String>>(1).getValue("session_id")
                val requestId = "resume-$sessionId"
                resumeRequests[sessionId] = requestId
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.switchSession("session-a")
            runCurrent()
            viewModel.switchSession("session-b")
            runCurrent()
            emitEvent(
                WsEvent.RpcError(
                    resumeRequests.getValue("session-a"),
                    JsonRpcError(code = -32000, message = "stale resume failure"),
                ),
            )
            runCurrent()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertTrue(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)
            assertNull(viewModel.uiState.value.resumeError)

            val empty =
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .SessionMessagesResponse(messages = emptyList()),
                )
            messagesA.complete(empty)
            messagesB.complete(empty)
            advanceUntilIdle()
        }

    @Test
    fun testSwitchSession_ignoresLateRestHydrationFromPreviousGeneration() =
        runTest {
            val mockApi = ApiClient.hermesApi
            val messagesA =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            val messagesB =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            every { AuthManager.getBaseUrl() } returns "http://test.local/"
            coEvery { mockApi.getSessions(any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .SessionListResponse(sessions = emptyList(), total = 0),
                )
            coEvery {
                mockApi.getSessionMessages(
                    "session-a",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesA.await() }
            coEvery {
                mockApi.getSessionMessages(
                    "session-b",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesB.await() }

            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-a")
            runCurrent()
            viewModel.switchSession("session-b")
            runCurrent()

            messagesB.complete(
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    role = "assistant",
                                    content = JsonPrimitive("message-b"),
                                ),
                            ),
                    ),
                ),
            )
            runCurrent()
            messagesA.complete(
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    role = "assistant",
                                    content = JsonPrimitive("stale-message-a"),
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertEquals(
                listOf("message-b"),
                viewModel.uiState.value.messages
                    .map { it.content },
            )
        }

    @Test
    fun testSwitchSession_clearsSessionBoundUiState() =
        runTest {
            stubEmptySessionRests("session-b")
            val (viewModel, sessionId) = createViewModelWithSession()
            mockScopedEvents.emit(
                ScopedWsEvent(
                    fakeSubmission.scope,
                    ++eventOrdinal,
                    WsEvent.ClarifyRequest("Choose", listOf("A"), "clarify-1", sessionId),
                ),
            )
            emitEvent(WsEvent.SudoRequest("sudo-1", sessionId))
            emitEvent(WsEvent.SecretRequest("secret-1", sessionId))
            emitEvent(
                WsEvent.SessionInfo(
                    mapOf(
                        "model" to "model-a",
                        "provider" to "provider-a",
                        "reasoning_effort" to "high",
                        "terminal_backend" to "docker",
                    ),
                ),
            )
            emitEvent(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "sub-1", "goal" to "work"),
                    sessionId = sessionId,
                ),
            )
            emitEvent(
                WsEvent.ToolStart(
                    name = "todo",
                    data = mapOf("todos" to listOf(mapOf("id" to "todo-1", "content" to "work"))),
                    sessionId = sessionId,
                ),
            )
            emitEvent(WsEvent.GatewayError("old error"))
            viewModel.openModelPicker()
            runCurrent()

            val oldState = viewModel.uiState.value
            assertNotNull(oldState.clarifyRequest)
            assertNotNull(oldState.sudoPrompt)
            assertNotNull(oldState.secretPrompt)
            assertNotNull(oldState.currentSessionModel)
            assertEquals("docker", oldState.terminalBackend)
            assertTrue(oldState.subagentIndicators.isNotEmpty())
            assertTrue(oldState.todos.isNotEmpty())
            assertTrue(oldState.showModelPicker)
            assertNotNull(oldState.errorMessage)

            viewModel.switchSession("session-b")
            runCurrent()
            val state = viewModel.uiState.value

            assertEquals("session-b", state.currentSessionId)
            assertTrue(state.messages.isEmpty())
            assertNull(state.clarifyRequest)
            assertNull(state.sudoPrompt)
            assertNull(state.secretPrompt)
            assertNull(state.currentSessionModel)
            assertNull(state.reasoningLevel)
            assertNull(state.terminalBackend)
            assertTrue(state.subagentIndicators.isEmpty())
            assertTrue(state.todos.isEmpty())
            assertFalse(state.showModelPicker)
            assertNull(state.errorMessage)
            assertNull(state.resumeError)
        }

    @Test
    fun testCreateNewSession_invalidatesResumeAndClearsSessionStateBeforeSend() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            var resumeRequestId = ""
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                resumeRequestId = "resume-a"
                arg<((String) -> Unit)?>(2)?.invoke(resumeRequestId)
                resumeRequestId
            }
            viewModel.switchSession("session-a")
            advanceUntilIdle()
            emitEvent(WsEvent.SudoRequest("sudo-1", "session-a"))
            emitEvent(WsEvent.GatewayError("old error"))
            runCurrent()

            viewModel.createNewSession()
            emitEvent(
                WsEvent.RpcError(
                    resumeRequestId,
                    JsonRpcError(code = -32000, message = "stale resume failure"),
                ),
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertNull(state.currentSessionId)
            assertNull(state.sudoPrompt)
            assertNull(state.errorMessage)
            assertNull(state.resumeError)
            assertTrue(state.isLoading)
        }

    @Test
    fun testSessionBranchResult_clearsSessionBoundUiState() =
        runTest {
            stubEmptySessionRests("branch-1")
            val (viewModel, sessionId) = createViewModelWithSession()
            val captured = captureSends()
            emitEvent(WsEvent.SecretRequest("secret-1", sessionId))
            emitEvent(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "sub-1", "goal" to "work"),
                    sessionId = sessionId,
                ),
            )
            emitEvent(
                WsEvent.ToolStart(
                    name = "todo",
                    data = mapOf("todos" to listOf(mapOf("id" to "todo-1", "content" to "work"))),
                    sessionId = sessionId,
                ),
            )
            emitEvent(WsEvent.SessionInfo(mapOf("model" to "model-a", "provider" to "provider-a")))
            runCurrent()

            viewModel.sendMessage("/fork")
            runCurrent()
            val branchRequest = captured.last { it.first == WsMethods.SESSION_BRANCH }.second
            emitEvent(
                WsEvent.RpcResult(
                    branchRequest,
                    mapOf("session_id" to "branch-1", "title" to "Branch"),
                ),
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals("branch-1", state.currentSessionId)
            assertNull(state.secretPrompt)
            assertNull(state.currentSessionModel)
            assertTrue(state.subagentIndicators.isEmpty())
            assertTrue(state.todos.isEmpty())
            assertNull(state.errorMessage)
        }

    // ── Session resume recovery (desktop parity: warm cache + bounded retry) ──

    /** Override the send stub to capture (method → id) pairs. */
    private fun captureSends(): MutableList<Pair<String, String>> {
        val captured = mutableListOf<Pair<String, String>>()
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            captured.add(arg<String>(0) to id)
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        return captured
    }

    private fun stubSession456Rests(success: Boolean) {
        val mockApi = ApiClient.hermesApi
        // mapServerMessages reads AuthManager.getBaseUrl() on the SUCCESS path
        // before mapping any messages. mockkObject is spy-semantics: unstubbed
        // calls fall through to the REAL AuthManager, whose serverStore is null
        // unless another test class happened to init it earlier in the JVM
        // (the order-dependent flakiness). Stub it for determinism — same
        // pattern as E2eIntegrationTest / ApiClientTest.
        if (success) {
            coEvery {
                mockApi.getSessions(any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionInfo(
                                    id = "session-456",
                                    title = "Test",
                                    message_count = 0,
                                ),
                            ),
                        total = 1,
                    ),
                )
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages = emptyList(),
                    ),
                )
        } else {
            // Relaxed mock returns a non-success response → NetworkResult.Failure.
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.error(
                    500,
                    okhttp3.ResponseBody.create(null, "{\"detail\":\"boom\"}"),
                )
        }
    }

    private fun stubEmptySessionRests(vararg sessionIds: String) {
        val mockApi = ApiClient.hermesApi
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        coEvery { mockApi.getSessions(any(), any(), any()) } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionListResponse(
                    sessions =
                        sessionIds.map {
                            com.m57.hermescontrol.data.model.SessionInfo(
                                id = it,
                                title = it,
                                message_count = 0,
                            )
                        },
                    total = sessionIds.size,
                ),
            )
        sessionIds.forEach { sessionId ->
            coEvery { mockApi.getSessionMessages(sessionId, any(), any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .SessionMessagesResponse(messages = emptyList()),
                )
        }
    }

    @Test
    fun testSwitchSession_paintsWarmCache_andKeepsItWhenResumeExhausted() =
        runTest {
            // Seed the Room cache for the target session.
            fakeRepo.dao.addMessageDirect(
                com.m57.hermescontrol.data.local.ChatMessageEntity(
                    id = "cached-1",
                    sessionId = "session-456",
                    role = "user",
                    content = "Cached hello",
                    timestamp = 1L,
                ),
            )
            // Server keeps failing → bounded retries exhaust → resumeError.
            stubSession456Rests(success = false)

            val (viewModel, _) = createViewModelWithSession()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // Warm cache painted and survived the exhausted retry cycle — the
            // screen never went blank, and the user gets history + an error.
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                "Cached hello",
                viewModel.uiState.value.messages[0]
                    .content,
            )
            assertFalse(viewModel.uiState.value.isLoading)
            assertNotNull("resumeError must be set after retries exhaust", viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)
        }

    /**
     * Regression for the spurious "Error: JsonRpcError(code=4001, message=session
     * not found, data=null)" snackbar when opening a session from history: the
     * chat sync effect fires fetchContextUsage() immediately on session switch,
     * BEFORE session.resume has registered the session on the gateway. The
     * context/usage RPCs resolve against the gateway's LIVE runtime registry
     * (_sess_nowait) and would 4001 on the storage id — so they must be skipped
     * until the resume result confirms the runtime id (which is then used).
     */
    @Test
    fun testSwitchSession_contextRpcSkippedUntilResumeConfirmsRuntimeId() =
        runTest {
            stubSession456Rests(success = true)

            // Awaited live-registry RPCs (sendRpcAndAwait → HermesWsClient.request).
            // Completed deferred so the awaits resolve; capture the params. Installed
            // BEFORE createViewModelWithSession so the SESSION_CREATE-era
            // fetchContextUsage() call is stubbed too (otherwise its unstubbed
            // invocation is still recorded and pollutes the verify(exactly = 0)).
            val paramsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.request(any(), capture(paramsSlot), any())
            } returns CompletableDeferred<Any?>(emptyMap<String, Any?>())

            val (viewModel, _) = createViewModelWithSession()

            // Capture the session.resume request id so its result can be delivered.
            var resumeRequestId: String? = null
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                reqCount++
                val id = "resume-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                resumeRequestId = id
                id
            }

            // Switch to session-456: runtimeSessionId resets to null until the
            // resume result lands — the storage id is not (yet) live on the
            // gateway. ChatScreen's sync effect fires fetchContextUsage()
            // immediately on session switch — simulate it while the resume
            // result is still in flight. The live-registry RPCs must NOT fire
            // with the stale storage id (the gateway would 4001).
            viewModel.switchSession("session-456")
            runCurrent()
            viewModel.fetchContextUsage()
            runCurrent()

            verify(exactly = 0) {
                HermesWsClient.request(
                    WsMethods.SESSION_CONTEXT_BREAKDOWN,
                    match { it["session_id"] == "session-456" },
                    any(),
                )
            }
            verify(exactly = 0) {
                HermesWsClient.request(
                    WsMethods.SESSION_USAGE,
                    match { it["session_id"] == "session-456" },
                    any(),
                )
            }

            // Resume result lands → runtime id confirmed → the RPCs fire with it.
            val resumeId = checkNotNull(resumeRequestId)
            emitEvent(
                WsEvent.RpcResult(
                    resumeId,
                    mapOf(
                        "session_id" to "runtime-456",
                        "resumed" to "session-456",
                        "info" to emptyMap<String, Any?>(),
                    ),
                ),
            )
            advanceUntilIdle()

            verify {
                HermesWsClient.request(
                    WsMethods.SESSION_CONTEXT_BREAKDOWN,
                    match { it["session_id"] == "runtime-456" },
                    any(),
                )
            }
            verify {
                HermesWsClient.request(
                    WsMethods.SESSION_USAGE,
                    match { it["session_id"] == "runtime-456" },
                    any(),
                )
            }
            assertEquals("runtime-456", paramsSlot.captured["session_id"])
        }

    @Test
    fun testSessionResume_boundedRetry_thenExplicitError() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // Drive one RPC failure per cycle; each failure arms a backoff retry.
            // Exhaustion needs MAX+1 failures: attempts 0..MAX-1 arm retries,
            // the MAX-th failure hits the exhausted latch.
            for (cycle in 1..(ChatViewModel.MAX_RESUME_RETRIES + 1)) {
                val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
                emitEvent(
                    WsEvent.RpcError(
                        resumeId,
                        JsonRpcError(code = -32000, message = "resume rejected"),
                    ),
                )
                advanceUntilIdle()
            }

            val resumeSends = captured.count { it.first == WsMethods.SESSION_RESUME }
            assertEquals(
                "initial + MAX_RESUME_RETRIES retries",
                ChatViewModel.MAX_RESUME_RETRIES + 1,
                resumeSends,
            )
            assertNotNull(viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun testSessionResume_rpcAndRestFailuresCountAsOneCycle() =
        runTest {
            // REST fails + one RpcError lands while the retry is already armed —
            // they must count as ONE failure, so the retry budget is not
            // double-burned (5 sends total, not 4).
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            runCurrent()
            // First cycle: REST already failed and armed the retry; now the WS
            // reject for the same resume arrives while that job is pending.
            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            emitEvent(
                WsEvent.RpcError(
                    resumeId,
                    JsonRpcError(code = -32000, message = "resume rejected"),
                ),
            )
            runCurrent()
            advanceUntilIdle()

            val resumeSends = captured.count { it.first == WsMethods.SESSION_RESUME }
            assertEquals(
                "initial + MAX_RESUME_RETRIES retries — no double-burn",
                ChatViewModel.MAX_RESUME_RETRIES + 1,
                resumeSends,
            )
            assertNotNull(viewModel.uiState.value.resumeError)
        }

    @Test
    fun testReconnectOnUnconfirmedSession_skipsDoomedResume() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            // Simulate a WS reconnect while sitting on the freshly created
            // session (never prompted — the gateway persists the DB row
            // lazily on the first prompt). The old code re-resumed the
            // storage key and the gateway 4007'd "session not found"
            // permanently; the retry could never fix it because the row only
            // appears once a prompt lands.
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any())
            }
            assertNull(
                "reconnect on an unconfirmed session must not error",
                viewModel.uiState.value.resumeError,
            )

            // Positive control: once the session HAS server presence (REST
            // 200 confirmed the row), a reconnect resumes it as before.
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-456", "omit_messages" to true),
                    any(),
                )
            }
        }

    @Test
    fun testContextUsage_fallsBackToUsageWhenBreakdownFails() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } answers {
                CompletableDeferred<Any?>().apply { completeExceptionally(IllegalStateException("unavailable")) }
            }
            every { HermesWsClient.request(WsMethods.SESSION_USAGE, any(), any()) } returns
                CompletableDeferred<Any?>(
                    mapOf("context_used" to 8500L, "context_max" to 128000L, "compressions" to 2),
                )

            viewModel.fetchContextUsage()
            advanceUntilIdle()

            assertEquals(8500L, viewModel.uiState.value.usedContextTokens)
            assertEquals(128000L, viewModel.uiState.value.fullContextTokens)
            assertEquals(2, viewModel.uiState.value.compressionCount)
        }

    @Test
    fun testContextUsage_latePollCannotOverwriteNewerPoll() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val oldBreakdown = CompletableDeferred<Any?>()
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } returns oldBreakdown
            viewModel.fetchContextUsage()
            runCurrent()

            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("context_used" to 9000L, "context_max" to 128000L))
            viewModel.fetchContextUsage()
            runCurrent()
            assertEquals(9000L, viewModel.uiState.value.usedContextTokens)

            oldBreakdown.complete(mapOf("context_used" to 75000L, "context_max" to 128000L))
            advanceUntilIdle()

            assertEquals(9000L, viewModel.uiState.value.usedContextTokens)
        }

    @Test
    fun testContextUsage_latePollCannotOverwriteUsagePush() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val oldBreakdown = CompletableDeferred<Any?>()
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } returns oldBreakdown
            viewModel.fetchContextUsage()
            runCurrent()

            emitEvent(
                WsEvent.SessionUsage(
                    mapOf("context_used" to 9000L, "context_max" to 128000L, "compressions" to 1),
                    sessionId,
                ),
            )
            runCurrent()
            oldBreakdown.complete(mapOf("context_used" to 75000L, "context_max" to 128000L))
            advanceUntilIdle()

            assertEquals(9000L, viewModel.uiState.value.usedContextTokens)
            assertEquals(1, viewModel.uiState.value.compressionCount)
        }

    @Test
    fun testContextUsage_oldSessionResponseCannotPopulateNewSession() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val oldBreakdown = CompletableDeferred<Any?>()
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } returns oldBreakdown
            viewModel.fetchContextUsage()
            runCurrent()

            stubSession456Rests(success = true)
            viewModel.switchSession("session-456")
            runCurrent()
            oldBreakdown.complete(mapOf("context_used" to 75000L, "context_max" to 128000L))
            advanceUntilIdle()

            assertEquals("session-456", viewModel.uiState.value.currentSessionId)
            assertNull(viewModel.uiState.value.usedContextTokens)
            assertNull(viewModel.uiState.value.compressionCount)
        }

    @Test
    fun testSessionInfoModelSwap_blanksStaleMeterAndRefetches() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Establish the OLD model's label through the real event path.
            emitEvent(
                WsEvent.SessionInfo(
                    mapOf("model" to "deepseek-v4-flash", "provider" to "opencode-go"),
                ),
            )
            advanceUntilIdle()
            assertEquals("opencode-go/deepseek-v4-flash", viewModel.uiState.value.currentSessionModel)

            // Fetch the meter so it carries the OLD model's window (1M).
            var contextMax = 1_000_000L
            var breakdownCalls = 0
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } answers {
                breakdownCalls++
                CompletableDeferred<Any?>(
                    mapOf("context_max" to contextMax, "context_used" to 42025L),
                )
            }
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(1_000_000L, viewModel.uiState.value.fullContextTokens)

            // The swap: the live agent hasn't warmed up yet, so the RPC comes
            // back empty — the meter must NOT fall back to the profile-scoped
            // REST window (which describes the OLD model). It stays hidden.
            contextMax = 0L
            val callsBeforeSwap = breakdownCalls
            emitEvent(
                WsEvent.SessionInfo(
                    mapOf("model" to "tencent/hy3:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()

            assertEquals("nous/tencent/hy3:free", viewModel.uiState.value.currentSessionModel)
            assertTrue("model swap must re-fire the meter fetch", breakdownCalls > callsBeforeSwap)
            assertNull(
                "cold RPC after a swap must keep the meter hidden, not show the old window",
                viewModel.uiState.value.fullContextTokens,
            )

            // The runtime warms up — the next fetch lands the NEW model's window.
            contextMax = 262_144L
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(262_144L, viewModel.uiState.value.fullContextTokens)
        }

    @Test
    fun testSessionInfoSameModel_keepsMeterUntouched() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Establish label + meter through the real paths.
            emitEvent(
                WsEvent.SessionInfo(
                    mapOf("model" to "tencent/hy3:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()
            var breakdownCalls = 0
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } answers {
                breakdownCalls++
                CompletableDeferred<Any?>(
                    mapOf("context_max" to 262_144L),
                )
            }
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(262_144L, viewModel.uiState.value.fullContextTokens)
            val callsBefore = breakdownCalls

            // Identical model again — no swap, no blank, no refetch.
            emitEvent(
                WsEvent.SessionInfo(
                    mapOf("model" to "tencent/hy3:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()

            assertEquals(callsBefore, breakdownCalls)
            assertEquals(262_144L, viewModel.uiState.value.fullContextTokens)
            assertEquals("nous/tencent/hy3:free", viewModel.uiState.value.currentSessionModel)
        }

    @Test
    fun testResumeNotFound_recoversWithNewSession() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            // Reject this still-pending attempt, not an already-expired request.
            runCurrent()

            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            val createsBefore = captured.count { it.first == WsMethods.SESSION_CREATE }

            // Gateway definitively has no row for this session (RPC 4007) —
            // e.g. the session was deleted or pruned server-side.
            emitEvent(
                WsEvent.RpcError(resumeId, JsonRpcError(code = 4007, message = "session not found")),
            )
            advanceUntilIdle()

            // No dead-end popup: the app recovered by creating a fresh session.
            assertNull("4007 must not dead-end on resumeError", viewModel.uiState.value.resumeError)
            assertEquals(createsBefore + 1, captured.count { it.first == WsMethods.SESSION_CREATE })

            // A second reject for the same resume (the paired REST 404 lands
            // just after the WS reject) must not double-create.
            emitEvent(
                WsEvent.RpcError(resumeId, JsonRpcError(code = 4007, message = "session not found")),
            )
            advanceUntilIdle()
            assertEquals(createsBefore + 1, captured.count { it.first == WsMethods.SESSION_CREATE })

            // Land the recovery create → the user lands on the new session
            // with an explanatory notice (queued until the create result so
            // the create's message wipe can't swallow it).
            val createId = captured.last { it.first == WsMethods.SESSION_CREATE }.second
            emitEvent(
                WsEvent.RpcResult(
                    createId,
                    mapOf(
                        "session_id" to "runtime-new",
                        "stored_session_id" to "session-new",
                    ),
                ),
            )
            advanceUntilIdle()
            assertEquals("session-new", viewModel.uiState.value.currentSessionId)
            assertTrue(
                "recovery notice must be visible",
                viewModel.uiState.value.messages
                    .any { it.content.contains("no longer available") },
            )
        }

    @Test
    fun testBranchResult_keepsStorageIdAsCurrentSession() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            // mapServerMessages reads AuthManager.getBaseUrl() unconditionally
            // on the REST-success path (mockkObject spy fall-through → real
            // uninitialized AuthManager throws). Stub it — same pattern as
            // stubSession456Rests / E2eIntegrationTest.

            // Stub the transcript fetch explicitly (relaxed mocks return null
            // and muddy the retry path) and capture which session id it is
            // requested with.
            val fetchedSessions = mutableListOf<String>()
            coEvery {
                ApiClient.hermesApi.getSessionMessages(capture(fetchedSessions), any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .SessionMessagesResponse(messages = emptyList()),
                )

            // /fork sends session.branch keyed on the runtime id.
            viewModel.sendMessage("/fork")
            advanceUntilIdle()

            val branchId = captured.last { it.first == WsMethods.SESSION_BRANCH }.second
            val branchStorage = "branch-storage-1"
            emitEvent(
                WsEvent.RpcResult(
                    branchId,
                    mapOf(
                        "session_id" to "branch-runtime-1",
                        "stored_session_id" to branchStorage,
                        "title" to "Branch",
                        "message_count" to 0,
                        "messages" to emptyList<Any>(),
                    ),
                ),
            )
            advanceUntilIdle()

            // The DB key must stay in currentSessionId — storing the runtime
            // id here made every later resume 4007 and the transcript 404.
            assertEquals(branchStorage, viewModel.uiState.value.currentSessionId)
            // And the transcript must be fetched by the storage key, not the
            // runtime registry id (which the gateway 404s on).
            assertTrue(
                "transcript must be fetched by the storage key",
                fetchedSessions.contains(branchStorage),
            )
        }

    @Test
    fun testSessionResume_wsSuccessWaitsForRestRetry() =
        runTest {
            stubSession456Rests(success = true)
            val mockApi = ApiClient.hermesApi
            var restCalls = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                val call = restCalls++
                if (call < 3) {
                    retrofit2.Response.error(
                        500,
                        okhttp3.ResponseBody.create(null, "{\"detail\":\"boom\"}"),
                    )
                } else {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model
                            .SessionMessagesResponse(messages = emptyList()),
                    )
                }
            }
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            withTimeout(2_000) { viewModel.uiState.first { it.isResumeRetrying } }

            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            emitEvent(
                WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456")),
            )
            runCurrent()

            assertTrue("WS success must leave the REST retry armed", viewModel.uiState.value.isResumeRetrying)
            assertEquals(1, captured.count { it.first == WsMethods.SESSION_RESUME })

            advanceTimeBy(ChatViewModel.RESUME_RETRY_BASE_MS)
            runCurrent()
            // A retried resume needs its own ACK; prior generation proof cannot
            // complete this attempt merely because its REST hydration succeeded.
            assertEquals(2, captured.count { it.first == WsMethods.SESSION_RESUME })
            assertNotEquals(
                resumeProbeField(viewModel, "sessionGeneration"),
                resumeProbeField(viewModel, "resumedGeneration"),
            )
            val retryId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            emitEvent(WsEvent.RpcResult(retryId, mapOf("session_id" to "runtime-456")))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isResumeRetrying)
            assertNull(viewModel.uiState.value.resumeError)
            assertEquals(2, captured.count { it.first == WsMethods.SESSION_RESUME })
        }

    @Test
    fun testRetryResumeSession_clearsErrorAndResends() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            runCurrent()

            // Exhaust the retry budget (MAX+1 failures — see boundedRetry test).
            for (cycle in 1..(ChatViewModel.MAX_RESUME_RETRIES + 1)) {
                val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
                emitEvent(
                    WsEvent.RpcError(
                        resumeId,
                        JsonRpcError(code = -32000, message = "resume rejected"),
                    ),
                )
                runCurrent()
                if (cycle <= ChatViewModel.MAX_RESUME_RETRIES) {
                    advanceTimeBy(
                        minOf(
                            ChatViewModel.RESUME_RETRY_MAX_MS,
                            ChatViewModel.RESUME_RETRY_BASE_MS * (1L shl (cycle - 1)),
                        ),
                    )
                    runCurrent()
                }
            }
            assertNotNull(viewModel.uiState.value.resumeError)

            // Manual retry clears the exhausted latch and dispatches a fresh resume.
            viewModel.retryResumeSession()
            runCurrent()

            assertNull(viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)
            val resumeSends = captured.count { it.first == WsMethods.SESSION_RESUME }
            assertEquals(ChatViewModel.MAX_RESUME_RETRIES + 2, resumeSends)
        }

    @Test
    fun testGatewayReconnect_clearsResumeErrorAndRestartsCycle() =
        runTest {
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertNotNull("resumeError must be set after retries exhaust", viewModel.uiState.value.resumeError)

            // Reconnect is a fresh start: the error latch is cleared and the
            // current session is re-resumed on the new socket.
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            runCurrent()

            assertNull(viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)

            // The re-resume's own failures start a NEW bounded cycle.
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.resumeError)
        }

    @Test
    fun testInterruptSession_withSessionId_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.interruptSession()
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, mapOf("session_id" to sessionId), any()) }
        }

    @Test
    fun testInterruptSession_withoutSessionId_doesNotSendRpc() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.interruptSession()
            advanceUntilIdle()

            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test
    fun testRpcErrorHandling() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            emitEvent(
                WsEvent.RpcError(
                    "req-id-1",
                    JsonRpcError(code = -32603, message = "Internal error during creation"),
                ),
            )
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("Internal error during creation"),
            )
        }

    // ── Session mismatch ─────────────────────────────────────────────────────

    @Test
    fun testSessionMismatchEventsAreIgnored() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            emitEvent(
                WsEvent.ToolStart(name = "calculator", data = mapOf("input" to "2+2"), sessionId = "session-other"),
            )
            emitEvent(
                WsEvent.ClarifyRequest(
                    text = "Choose:",
                    options = listOf("Yes"),
                    clarifyId = "clarify-1",
                    sessionId = "session-other",
                ),
            )
            emitEvent(WsEvent.MessageStart("session-other"))
            emitEvent(WsEvent.MessageToken("Hello", "session-other"))
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                "Session created",
                viewModel.uiState.value.messages[0]
                    .content,
            )
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertNull(viewModel.uiState.value.clarifyRequest)
        }

    // ── Reconnect ────────────────────────────────────────────────────────────

    @Test
    fun testReconnectDoesNotDuplicateEventCollection() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.reconnect()
            advanceUntilIdle()

            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("Hello", sessionId))
            advanceUntilIdle()

            assertEquals(
                "Hello",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    // ── MessageComplete without streaming ────────────────────────────────────

    @Test
    fun repeatedCompletionKeepsOneDisplayedAndPersistedReply() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("Same reply", sessionId))
            emitEvent(WsEvent.MessageComplete("Same reply", sessionId, reasoning = "Reasoning"))
            advanceUntilIdle()
            val first =
                viewModel.uiState.value.messages
                    .last()

            emitEvent(WsEvent.MessageDone(sessionId))
            emitEvent(WsEvent.MessageComplete("Same reply", sessionId))
            advanceUntilIdle()

            val replies =
                viewModel.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
            assertEquals(listOf(first), replies)
            assertEquals(
                listOf(first.id),
                fakeRepo
                    .readTranscript(fakeSubmission.scope.draftTargetKey(), sessionId)
                    .messages
                    .filter {
                        it.role == MessageRole.ASSISTANT
                    }.map { it.id },
            )
        }

    @Test
    fun completionAfterDoneUpdatesTheExistingReplyWithAuthoritativeText() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("Partial reply", sessionId))
            emitEvent(WsEvent.MessageDone(sessionId))
            advanceUntilIdle()
            val firstId =
                viewModel.uiState.value.messages
                    .last()
                    .id

            emitEvent(WsEvent.MessageComplete("Partial reply, now complete", sessionId))
            advanceUntilIdle()

            val reply =
                viewModel.uiState.value.messages
                    .single { it.role == MessageRole.ASSISTANT }
            assertEquals(firstId, reply.id)
            assertEquals("Partial reply, now complete", reply.content)
            val saved =
                fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), sessionId).messages.single {
                    it.role ==
                        MessageRole.ASSISTANT
                }
            assertEquals(firstId, saved.id)
            assertEquals(reply.content, saved.content)
        }

    @Test
    fun sameReplyInANewTurnIsKept() =
        runTest {
            fakeSubmission.addressedPromptAcks = true
            val (viewModel, sessionId) = createViewModelWithSession()
            repeat(2) { index ->
                viewModel.sendMessage("question-$index")
                advanceUntilIdle()
                emitEvent(WsEvent.MessageStart(sessionId))
                emitEvent(WsEvent.MessageToken("Same reply", sessionId))
                completeAddressed(viewModel, sessionId, "Same reply")
                advanceUntilIdle()
            }
            val replies =
                viewModel.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
            assertEquals(2, replies.size)
            assertEquals(2, replies.map { it.id }.distinct().size)
            assertEquals(2, fakeRepo.loadMessages(sessionId).count { it.role == MessageRole.ASSISTANT })
        }

    @Test
    fun repeatedCompletionAfterToolCommentaryDoesNotRepeatTheFinalReply() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("Checking. ", sessionId))
            emitEvent(WsEvent.ToolStart("terminal", emptyMap(), sessionId))
            emitEvent(WsEvent.ToolComplete("terminal", emptyMap(), sessionId))
            emitEvent(WsEvent.MessageToken("Final reply", sessionId))
            emitEvent(WsEvent.MessageDone(sessionId))
            advanceUntilIdle()
            emitEvent(WsEvent.MessageComplete("Final reply", sessionId, responseReused = true))
            emitEvent(WsEvent.MessageDone(sessionId))
            emitEvent(WsEvent.MessageComplete("Final reply", sessionId, responseReused = true))
            advanceUntilIdle()

            val replies =
                viewModel.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
            assertEquals(listOf("Checking. ", "Final reply"), replies.map { it.content })
            assertEquals(2, fakeRepo.loadMessages(sessionId).count { it.role == MessageRole.ASSISTANT })
        }

    @Test
    fun testMessageCompleteWithoutStreaming_upsertsAssistantMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            emitEvent(WsEvent.MessageComplete("Fully complete message", sessionId))
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Fully complete message",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.ASSISTANT,
                viewModel.uiState.value.messages[1]
                    .role,
            )
        }

    // ── Approval flow ────────────────────────────────────────────────────────

    @Test
    fun approvalTurnResumeDoesNotDuplicateToolsOrFinalAnswer() =
        runTest { approvalTurnResume(restBeforeTerminal = true) }

    @Test
    fun approvalTurnResumeFoldsHistoryThatArrivesAfterTerminal() =
        runTest { approvalTurnResume(restBeforeTerminal = false) }

    private suspend fun TestScope.approvalTurnResume(restBeforeTerminal: Boolean) {
        val (vm, session) = createViewModelWithSession()
        fakeSubmission.addressedPromptAcks = true
        vm.sendMessage("Synthetic request")
        runCurrent()
        emitEvent(WsEvent.MessageStart(session))
        val userRow =
            vm.uiState.value.responseLedger.responses
                .single()
                .userRowIds
                .single()
                .toInt()
        val history =
            mutableListOf(
                SessionMessage(
                    id = userRow,
                    role = "user",
                    content = JsonPrimitive("Synthetic request"),
                    timestamp = JsonPrimitive(1.0),
                ),
            )

        suspend fun tools(
            range: IntRange,
            runtime: String,
        ) {
            for (index in range) {
                val payload = mapOf("tool_id" to "call-$index")
                emitEvent(WsEvent.ToolStart("terminal", payload, runtime))
                emitEvent(WsEvent.ToolComplete("terminal", payload, runtime))
                runCurrent()
                history +=
                    SessionMessage(
                        id = userRow + history.size,
                        role = "tool",
                        content = JsonPrimitive("Stored result $index"),
                        timestamp = JsonPrimitive(1.0),
                        tool_call_id = "call-$index",
                    )
            }
        }

        fun rest(rows: List<SessionMessage>) =
            retrofit2.Response.success(
                SessionMessagesResponse(
                    rows.toList(),
                    pagination =
                        PaginationInfo(order = "latest", offset = 0),
                ),
            )

        tools(1..15, session)
        vm.respondToApproval(observeApproval(vm, "approval-1", session).id, "once")
        runCurrent()

        // The resume starts history hydration independently from the running ACK.
        stubEmptySessionRests(session)
        val partial =
            CompletableDeferred<retrofit2.Response<SessionMessagesResponse>>()
        coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } coAnswers
            { partial.await() }
        val resumes = captureRecoveryResumes()
        mockConnectionStatus.value = ConnectionStatus.RECONNECTING
        runCurrent()
        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        emitEvent(WsEvent.GatewayReady(null))
        runCurrent()
        val runtime = "runtime-recovered"
        recoveryAck(resumes.last(), running = true, runtimeId = runtime)
        runCurrent()
        partial.complete(rest(history))
        runCurrent()
        val resumedToolCount =
            vm.uiState.value.messages
                .count { it.role == MessageRole.TOOL }

        tools(16..34, runtime)
        val secondApproval = observeApproval(vm, "approval-2", runtime)
        assertEquals("approval-2", secondApproval.approvalInfo?.requestId)
        vm.respondToApproval(secondApproval.id, "once")
        runCurrent()
        // Reconnect again while the approval has paused the turn. The resumed history
        // request can return after the server commits the final, on either side of the WS terminal.
        val full =
            CompletableDeferred<retrofit2.Response<SessionMessagesResponse>>()
        val requested = CompletableDeferred<Unit>()
        val api = ApiClient.hermesApi
        coEvery { api.getSessionMessages(session, any(), any(), any(), any(), any()) } coAnswers {
            requested.complete(Unit)
            full.await()
        }
        assertFalse("Approval pauses the typing indicator", vm.uiState.value.isAgentTyping)
        mockConnectionStatus.value = ConnectionStatus.RECONNECTING
        runCurrent()
        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        emitEvent(WsEvent.GatewayReady(null))
        runCurrent()
        assertTrue(requested.isCompleted)
        recoveryAck(resumes.last(), running = true, runtimeId = runtime)
        runCurrent()
        tools(35..38, runtime)
        emitEvent(WsEvent.ReasoningDelta("Synthetic reasoning", runtime))
        emitEvent(WsEvent.MessageToken("Synthetic final", runtime))
        advanceTimeBy(40L)
        runCurrent()
        assertNotNull(vm.streamingState.value.streamingMessage)
        history +=
            SessionMessage(
                id = userRow + history.size,
                role = "assistant",
                content = JsonPrimitive("Synthetic final"),
                timestamp = JsonPrimitive(1.0),
                reasoning = JsonPrimitive("Stored reasoning"),
            )
        if (restBeforeTerminal) full.complete(rest(history))
        runCurrent()
        val whileStreaming = vm.uiState.value
        val liveTurns =
            groupIntoTurnsWithStreaming(
                whileStreaming.messages,
                vm.streamingState.value.streamingMessage,
                whileStreaming.responseLedger,
            )
        emitEvent(WsEvent.MessageComplete("Synthetic final", runtime))
        runCurrent()
        if (!restBeforeTerminal) {
            full.complete(rest(history))
            runCurrent()
        }
        val completed = vm.uiState.value
        val completedTurns = groupIntoTurnsWithStreaming(completed.messages, null, completed.responseLedger)

        assertEquals("One copy of each completed call immediately after running resume", 15, resumedToolCount)
        assertApprovalTurn(liveTurns)
        assertTrue(vm.streamingState.value.turnEnded)
        assertEquals(1, completed.messages.count { it.role == MessageRole.ASSISTANT })
        val reasoning = completed.messages.single { it.role == MessageRole.ASSISTANT }.reasoningText
        assertTrue(reasoning.contains("Synthetic reasoning") && reasoning.contains("Stored reasoning"))
        assertApprovalTurn(completedTurns)
        vm.syncCurrentSession()
        runCurrent()
        val synced = vm.uiState.value
        assertApprovalTurn(groupIntoTurnsWithStreaming(synced.messages, null, synced.responseLedger))
    }

    private fun assertApprovalTurn(turns: List<ChatTurn>) {
        val entries =
            turns
                .filterIsInstance<ChatTurn.Agent>()
                .filter { turn ->
                    turn.entries.any { entry ->
                        entry is AgentEntry.ToolRow ||
                            entry is AgentEntry.Prose ||
                            (entry is AgentEntry.SystemEvent && entry.message.approvalInfo != null)
                    }
                }.single()
                .entries
        val tools = entries.filterIsInstance<AgentEntry.ToolRow>()
        val approvals =
            entries
                .filterIsInstance<AgentEntry.SystemEvent>()
                .mapNotNull { it.message.approvalInfo }
        assertEquals((1..38).map { "call-$it" }, tools.map { it.message.toolCallId })
        assertEquals(listOf("approval-1", "approval-2"), approvals.map { it.requestId })
        assertTrue(approvals.all { it.status == AttentionStatus.CONFIRMED })
        assertEquals(
            listOf(15, 34),
            entries.mapIndexedNotNull { index, entry ->
                if (entry is AgentEntry.SystemEvent &&
                    entry.message.approvalInfo != null
                ) {
                    entries.take(index).count { it is AgentEntry.ToolRow }
                } else {
                    null
                }
            },
        )
        val answer = entries.filterIsInstance<AgentEntry.Prose>().single()
        assertTrue(answer.message.reasoningText.isNotBlank())
        assertEquals(answer, entries.last())
    }

    private suspend fun TestScope.observeApproval(
        viewModel: ChatViewModel,
        requestId: String? = "request-1",
        runtimeId: String? = "session-123",
    ): ChatMessage {
        mockScopedEvents.emit(
            ScopedWsEvent(
                fakeSubmission.scope,
                ++eventOrdinal,
                WsEvent.ApprovalRequest(
                    command = "test command",
                    description = "対象の変更",
                    patternKeys = listOf("shell:test"),
                    sessionId = runtimeId,
                    requestId = requestId,
                    choices = listOf("once", "session", "always", "deny"),
                ),
            ),
        )
        advanceUntilIdle()
        return viewModel.uiState.value.messages
            .last { it.approvalInfo != null }
    }

    @Test
    fun testApprovalRequest_preservesMetadataAndAcknowledgesExactRuntime() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val row = observeApproval(vm)
            assertEquals("test command", row.approvalInfo?.command)
            assertEquals(listOf("shell:test"), row.approvalInfo?.patternKeys)
            assertEquals("request-1", row.approvalInfo?.requestId)
            assertEquals(
                "session-123",
                row.approvalInfo
                    ?.ref
                    ?.session
                    ?.runtimeId,
            )
            coVerify(exactly = 1) { attentionTransport.acknowledgeApproval(row.approvalInfo!!.ref!!) }
            coVerify(exactly = 0) { attentionTransport.respondApproval(any(), any()) }
        }

    @Test
    fun testApprovalResponse_rejectsUnadvertisedOnceFromChatCallback() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            mockScopedEvents.emit(
                ScopedWsEvent(
                    fakeSubmission.scope,
                    ++eventOrdinal,
                    WsEvent.ApprovalRequest(
                        "<write to SOUL.md>",
                        "Protected instruction change",
                        listOf("protected_instruction_file"),
                        "session-123",
                        "deny-only-request",
                        choices = listOf("deny"),
                    ),
                ),
            )
            advanceUntilIdle()
            val row =
                vm.uiState.value.messages
                    .last { it.approvalInfo != null }
            vm.respondToApproval(row.id, "once")
            advanceUntilIdle()
            coVerify(exactly = 0) { attentionTransport.respondApproval(any(), any()) }
            vm.respondToApproval(row.id, "deny")
            advanceUntilIdle()
            coVerify(exactly = 1) { attentionTransport.respondApproval(row.approvalInfo!!.ref!!, "deny") }
        }

    @Test
    fun testApprovalRequest_sameContentDifferentIdentitySurvivesTranscriptMerge() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val first = observeApproval(vm, "first")
            val second = observeApproval(vm, "second")
            assertFalse(sameLogicalMessage(first, second))
            assertTrue(sameLogicalMessage(first, first.copy(id = "rest-copy")))
            assertFalse(sameLogicalMessage(first, first.copy(approvalInfo = null)))
        }

    @Test
    fun testApprovalResponse_targetsTappedRowAndWaitsForAck() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val first = observeApproval(vm, "first")
            val second = observeApproval(vm, "second")
            val reply = CompletableDeferred<SubmissionResult<Boolean>>()
            coEvery { attentionTransport.respondApproval(any(), any()) } coAnswers { reply.await() }
            vm.respondToApproval(first.id, "once")
            runCurrent()
            vm.respondToApproval(first.id, "once")
            runCurrent()
            assertEquals(
                AttentionStatus.SUBMITTING,
                vm.uiState.value.messages
                    .first { it.id == first.id }
                    .approvalInfo
                    ?.status,
            )
            assertEquals(
                AttentionStatus.PENDING,
                vm.uiState.value.messages
                    .first { it.id == second.id }
                    .approvalInfo
                    ?.status,
            )
            coVerify(exactly = 1) { attentionTransport.respondApproval(first.approvalInfo!!.ref!!, "once") }
            reply.complete(SubmissionResult.Confirmed("ack", true))
            advanceUntilIdle()
            assertEquals(
                AttentionStatus.CONFIRMED,
                vm.uiState.value.messages
                    .first { it.id == first.id }
                    .approvalInfo
                    ?.status,
            )
        }

    @Test
    fun testApprovalResponse_rejectsBroadAndUnknownChoices() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val row = observeApproval(vm)
            for (choice in listOf("session", "always", "approve", "unknown")) vm.respondToApproval(row.id, choice)
            advanceUntilIdle()
            coVerify(exactly = 0) { attentionTransport.respondApproval(any(), any()) }
            assertEquals(
                AttentionStatus.PENDING,
                vm.uiState.value.messages
                    .last()
                    .approvalInfo
                    ?.status,
            )
            vm.respondToApproval(row.id, "deny")
            advanceUntilIdle()
            coVerify(exactly = 1) { attentionTransport.respondApproval(row.approvalInfo!!.ref!!, "deny") }
        }

    @Test
    fun testApprovalResponse_missingIdentityCannotSendEvenDeny() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val noRequest = observeApproval(vm, requestId = null)
            val noSession = observeApproval(vm, requestId = "other", runtimeId = null)
            for (row in listOf(noRequest, noSession)) {
                assertNull(row.approvalInfo?.ref)
                assertFalse(row.approvalInfo!!.responseEnabled)
                vm.respondToApproval(row.id, "deny")
            }
            advanceUntilIdle()
            coVerify(exactly = 0) { attentionTransport.respondApproval(any(), any()) }
            coVerify(exactly = 0) { attentionTransport.acknowledgeApproval(any()) }
        }

    @Test
    fun testApprovalResponse_scopeChangeAndOfflineCannotSend() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val row = observeApproval(vm)
            val original = fakeSubmission.scope
            fakeSubmission.ready = false
            vm.respondToApproval(row.id, "once")
            advanceUntilIdle()
            fakeSubmission.ready = true
            for (scope in listOf(
                original.copy(generation = 2),
                original.copy(serverIdentity = "other"),
                original.copy(connectionProfileId = "other"),
                original.copy(serverProfileId = "other"),
                original.copy(processId = "other"),
            )) {
                fakeSubmission.scope = scope
                vm.respondToApproval(row.id, "once")
                advanceUntilIdle()
            }
            coVerify(exactly = 0) { attentionTransport.respondApproval(any(), any()) }
        }

    @Test
    fun testApprovalResponse_unknownIsNotAutomaticallyRetriedByReplay() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val row = observeApproval(vm)
            coEvery { attentionTransport.respondApproval(any(), any()) } returns SubmissionResult.Unknown
            vm.respondToApproval(row.id, "once")
            advanceUntilIdle()
            observeApproval(vm)
            vm.respondToApproval(row.id, "once")
            advanceUntilIdle()
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.approvalInfo?.requestId == "request-1" },
            )
            assertEquals(
                AttentionStatus.UNKNOWN,
                vm.uiState.value.messages
                    .last()
                    .approvalInfo
                    ?.status,
            )
            coVerify(exactly = 1) { attentionTransport.respondApproval(any(), any()) }
        }

    @Test
    fun testApprovalResponse_definitelyNotSentRequiresNewTap() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val row = observeApproval(vm)
            coEvery { attentionTransport.respondApproval(any(), any()) } returns SubmissionResult.NotSent
            vm.respondToApproval(row.id, "once")
            advanceUntilIdle()
            assertEquals(
                AttentionStatus.PENDING,
                vm.uiState.value.messages
                    .last()
                    .approvalInfo
                    ?.status,
            )
            coVerify(exactly = 1) { attentionTransport.respondApproval(any(), any()) }
            vm.respondToApproval(row.id, "deny")
            advanceUntilIdle()
            coVerify(exactly = 2) { attentionTransport.respondApproval(any(), any()) }
        }

    @Test
    fun testApprovalRequest_ignoresUnscopedAndOldSocketEvents() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val event = WsEvent.ApprovalRequest("command", null, null, "session-123", "old")
            emitEvent(event)
            mockScopedEvents.emit(ScopedWsEvent(fakeSubmission.scope.copy(generation = 0), 1, event))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.messages
                    .none { it.approvalInfo != null },
            )
            coVerify(exactly = 0) { attentionTransport.acknowledgeApproval(any()) }
        }

    // ── Sudo / secret prompt flow (issue #524) ───────────────────────────

    @Test
    fun testSudoRequest_setsPromptState() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(
                WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null),
            )
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.sudoPrompt
            assertNotNull(prompt)
            assertEquals("sudo-1", prompt?.requestId)
        }

    @Test
    fun testRespondToSudo_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(
                WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null),
            )
            advanceUntilIdle()

            viewModel.respondToSudo("hunter2")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.SUDO_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("hunter2", params["password"])
                        assertEquals("sudo-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToSudo_clearsPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(
                WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null),
            )
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            viewModel.respondToSudo("hunter2")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testSecretRequest_setsPromptState() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(
                WsEvent.SecretRequest(requestId = "secret-1", sessionId = null),
            )
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.secretPrompt
            assertNotNull(prompt)
            assertEquals("secret-1", prompt?.requestId)
        }

    @Test
    fun testRespondToSecret_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(
                WsEvent.SecretRequest(requestId = "secret-1", sessionId = null),
            )
            advanceUntilIdle()

            viewModel.respondToSecret("super-secret-token")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.SECRET_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("super-secret-token", params["value"])
                        assertEquals("secret-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToSecret_clearsPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(
                WsEvent.SecretRequest(requestId = "secret-1", sessionId = null),
            )
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            viewModel.respondToSecret("super-secret-token")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun testSudoExpire_clearsMatchingPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            emitEvent(WsEvent.SudoExpire(requestId = "sudo-1", sessionId = null))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testSudoExpire_ignoresNonMatchingRequestId() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(WsEvent.SudoRequest(requestId = "sudo-2", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            emitEvent(WsEvent.SudoExpire(requestId = "sudo-stale", sessionId = null))
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testSecretExpire_clearsMatchingPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(WsEvent.SecretRequest(requestId = "secret-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            emitEvent(WsEvent.SecretExpire(requestId = "secret-1", sessionId = null))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun testSecretRequest_setsEnvVarAndPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(
                WsEvent.SecretRequest(
                    requestId = "secret-1",
                    sessionId = null,
                    envVar = "GITHUB_TOKEN",
                    prompt = "Enter your GitHub token",
                ),
            )
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.secretPrompt
            assertNotNull(prompt)
            assertEquals("GITHUB_TOKEN", prompt?.envVar)
            assertEquals("Enter your GitHub token", prompt?.prompt)
        }

    @Test
    fun testDismissSudo_sendsEmptyPassword() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            viewModel.dismissSudo()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
            verify {
                HermesWsClient.send(
                    WsMethods.SUDO_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("", params["password"])
                        assertEquals("sudo-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testDismissSecret_sendsEmptyValue() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            emitEvent(WsEvent.SecretRequest(requestId = "secret-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            viewModel.dismissSecret()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.secretPrompt)
            verify {
                HermesWsClient.send(
                    WsMethods.SECRET_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("", params["value"])
                        assertEquals("secret-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Test
    fun testRefreshSettings_updatesUiState() =
        runTest {
            // Given the default setup, init{} already calls refreshSettings() once,
            // so the initial state reflects the setUp defaults (typingEffectEnabled=true,
            // typingEffectDelayMs=30).
            val viewModel = createViewModel()
            advanceUntilIdle()
            with(viewModel.uiState.value) {
                assertTrue(typingEffectEnabled)
                assertEquals(30, typingEffectDelayMs)
            }

            // When settings change after construction and refreshSettings() is re-invoked,
            // the UI state must reflect the NEW values — this proves refreshSettings()
            // re-reads AuthManager live (the real regression scenario).
            every { AuthManager.isTypingEffectEnabled() } returns false
            every { AuthManager.getTypingEffectDelayMs() } returns 50
            viewModel.refreshSettings()
            advanceUntilIdle()

            // Then
            val state = viewModel.uiState.value
            assertFalse(state.typingEffectEnabled)
            assertEquals(50, state.typingEffectDelayMs)
        }

    @Test
    fun testToggleSearch() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.searchState.isActive)

            viewModel.toggleSearch()
            advanceUntilIdle()

            assertTrue(viewModel.searchState.isActive)

            viewModel.setSearchQuery("test")
            advanceUntilIdle()

            viewModel.toggleSearch()
            advanceUntilIdle()

            assertFalse(viewModel.searchState.isActive)
            assertEquals("", viewModel.searchState.query)
        }

    @Test
    fun testSendMessage_readContentUriThrowsException_handlesGracefully() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // Android framework Uri.parse throws "not mocked" in plain unit tests,
            // so stub it (no Robolectric here). Then make the resolver throw on read.
            mockkStatic(Uri::class)
            val mockUri = mockk<Uri>()
            every { Uri.parse("content://dummy") } returns mockUri

            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(any()) } throws
                SecurityException("Permission denied")

            viewModel.addAttachment("content://dummy", "test.png", "image/png", 1000)
            advanceUntilIdle()

            viewModel.sendMessage("Here is an image")
            advanceUntilIdle()

            // Preserve the complete intent; never silently send without the requested attachment.
            verify { Log.e(any(), "Attachment snapshot unavailable") }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            assertEquals("Here is an image", viewModel.uiState.value.composerTextToRestore)
            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)
            assertEquals(
                DraftState.DRAFT,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
        }

    /**
     * Regression for the "session not found" (code 4001) error when sending an
     * image: the mobile image-attach path must pass `session_id` to
     * `image.attach_bytes` (the gateway resolves the session from it; desktop
     * does the same). Without it the backend 4001s and the image is dropped.
     * Also asserts the image attach is AWAITED (staged before prompt.submit),
     * not fire-and-forget.
     */
    @Test
    fun testSendMessage_imageAttachment_sendsSessionId() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // sendRpcAndAwait calls HermesWsClient.request(method, params) — the
            // 2-arg form (Kotlin synthesizes request(String, Map)). Stub that
            // exact signature, capture params, and return a completed deferred
            // so the await resolves without hanging.
            val paramsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.request(any(), capture(paramsSlot), any())
            } returns CompletableDeferred<Any?>(mapOf("attached" to true))

            // Mock Android's Base64OutputStream constructor to avoid "Stub!" exception in JVM tests
            io.mockk.mockkConstructor(android.util.Base64OutputStream::class)
            every {
                anyConstructed<android.util.Base64OutputStream>().write(
                    any<ByteArray>(),
                    any(),
                    any(),
                )
            } returns Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit

            // The image bytes read via ContentResolver must succeed.
            mockkStatic(Uri::class)
            val mockUri = mockk<Uri>()
            every { Uri.parse("content://dummy") } returns mockUri
            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(any()) } returns
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))

            viewModel.addAttachment("content://dummy", "test.png", "image/png", 1000)
            advanceUntilIdle()
            assertTrue(
                "pendingAttachments must contain the image before send",
                viewModel.uiState.value.pendingAttachments
                    .isNotEmpty(),
            )
            assertTrue(
                "attached png must have isImage=true",
                viewModel.uiState.value.pendingAttachments
                    .first()
                    .isImage,
            )

            viewModel.sendMessage("Here is an image")
            advanceUntilIdle()

            // Verify the image attach RPC was issued with session_id.
            verify { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any()) }
            assertEquals(
                "session_id must be forwarded to image.attach_bytes",
                sessionId,
                paramsSlot.captured["session_id"],
            )
            assertTrue("encoded attachment cache must be cleaned", attachmentCacheDir.listFiles().isNullOrEmpty())
        }

    // ── Pending request timeout + rejectAllPending (issue #526) ───────────

    /**
     * On disconnect (RECONNECTING) the ViewModel must run rejectAllPending
     * without throwing and stay usable — mirroring desktop
     * JsonRpcGatewayClient.rejectAllPending invoked on socket close. This is
     * what prevents callers awaiting a CompletableDeferred from hanging
     * across a socket drop.
     */
    @Test
    fun disconnectPreservesPartialTextAndShowsUnknownUntilObservedCompletion() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("Partial answer", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertFalse(viewModel.uiState.value.responseStatusUnknown)
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.responseStatusUnknown)
            assertEquals(
                "Partial answer",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            advanceUntilIdle()
            assertTrue(
                "Socket connection alone is not proof of execution",
                viewModel.uiState.value.responseStatusUnknown,
            )
            emitEvent(WsEvent.MessageDone(sessionId))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.responseStatusUnknown)
            assertFalse(viewModel.uiState.value.isAgentTyping)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content == "Partial answer" },
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
        }

    @Test
    fun unknownResponseAllowsReadOnlyHistorySyncWithoutDiscardingPartialText() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            var reads = 0
            coEvery { ApiClient.hermesApi.getSessionMessages(sessionId, any(), any(), any(), any()) } coAnswers {
                reads++
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .SessionMessagesResponse(messages = emptyList()),
                )
            }
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("Partial answer", sessionId))
            advanceUntilIdle()
            viewModel.syncCurrentSession()
            advanceUntilIdle()
            assertEquals(0, reads)
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            advanceUntilIdle()
            viewModel.syncCurrentSession()
            advanceUntilIdle()
            assertEquals(1, reads)
            assertTrue(viewModel.uiState.value.responseStatusUnknown)
            assertEquals(
                "Partial answer",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
        }

    @Test
    fun anotherSessionsTerminalEventDoesNotResetPartialReasoningOrUnknownState() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.ReasoningDelta("Partial reasoning", sessionId))
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()
            val before = viewModel.streamingState.value
            emitEvent(WsEvent.MessageDone("another-session"))
            advanceUntilIdle()
            assertEquals(before, viewModel.streamingState.value)
            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertTrue(viewModel.uiState.value.responseStatusUnknown)
        }

    @Test
    fun idleDisconnectDoesNotClaimAnUnknownResponse() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.responseStatusUnknown)
            assertFalse(viewModel.uiState.value.isAgentTyping)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
        }

    @Test
    fun testDisconnect_rejectsPendingWithoutError() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val row = observeApproval(viewModel)
            coEvery { attentionTransport.respondApproval(any(), any()) } returns SubmissionResult.Unknown
            viewModel.respondToApproval(row.id, "once")
            advanceUntilIdle()

            // Simulate socket drop → reconnecting (triggers rejectAllPending).
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()

            // No exception propagated; VM remains usable.
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * viewModel.reconnect() calls rejectAllPending() before wsClient.disconnect(),
     * so any in-flight awaited RPC is failed fast instead of hanging until its
     * own timeout.
     */
    @Test
    fun testReconnect_rejectsPendingWithoutError() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val row = observeApproval(viewModel)
            coEvery { attentionTransport.respondApproval(any(), any()) } returns SubmissionResult.Unknown
            viewModel.respondToApproval(row.id, "once")
            advanceUntilIdle()

            // User-initiated reconnect must not throw / hang.
            viewModel.reconnect()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.errorMessage)
            verify { HermesWsClient.disconnect() }
        }

    // ── History pagination guard (issue #674 & #686) ───────────────────────────────

    /**
     * When the initial REST page returns empty messages, hasOlderMessages
     * must be false — otherwise the UI shows a load-more button that fetches
     * the same empty page again, creating an infinite loop.
     */
    @Test
    fun testEmptyInitialRestPage_disablesOlderPagination() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessions(any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionInfo(
                                    id = "session-456",
                                    title = "Test",
                                    message_count = 200,
                                ),
                            ),
                        total = 1,
                    ),
                )
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages = emptyList(),
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertFalse(
                "hasOlderMessages must be false when initial page is empty",
                viewModel.uiState.value.hasOlderMessages,
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun testLoadOlderMessages_honorsServerReturnedOffset() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessions(any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionInfo(
                                    id = "session-456",
                                    title = "Test",
                                    message_count = 100,
                                ),
                            ),
                        total = 1,
                    ),
                )
            var messagesCallCount = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                messagesCallCount += 1
                // Call 1 = the order=latest probe (discarded when the legacy
                // backend echoes no pagination — issue #859).
                if (messagesCallCount == 2) {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        role = "assistant",
                                        content = JsonPrimitive("Msg 50"),
                                    ),
                                ),
                            offset = 50,
                            total = 100,
                        ),
                    )
                } else {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        role = "user",
                                        content = JsonPrimitive("Older Msg 20"),
                                    ),
                                ),
                            offset = 20,
                            total = 100,
                        ),
                    )
                }
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.hasOlderMessages)

            // Server returns effective offset 20 (different from requested offset 0)
            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.hasOlderMessages)
            val messages = viewModel.uiState.value.messages
            assertEquals(2, messages.size)
            assertEquals("unaddressed:${fakeSubmission.scope.draftTargetKey()}:rest-session-456-20", messages[0].id)
            assertEquals("Older Msg 20", messages[0].content)
        }

    @Test
    fun testLoadOlderMessages_oldServerFallback_stopsPaginationWhenOffsetDoesNotDecrease() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessions(any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionInfo(
                                    id = "session-456",
                                    title = "Test",
                                    message_count = 100,
                                ),
                            ),
                        total = 1,
                    ),
                )
            var messagesCallCount = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                messagesCallCount += 1
                if (messagesCallCount == 1) {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        role = "assistant",
                                        content = JsonPrimitive("Msg 50"),
                                    ),
                                ),
                            offset = 50,
                            total = 100,
                        ),
                    )
                } else {
                    // Older server ignores query params and returns offset = 50
                    // (same as oldOffset, offset did not decrease)
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        role = "assistant",
                                        content = JsonPrimitive("Msg 50"),
                                    ),
                                ),
                            offset = 50,
                            total = 100,
                        ),
                    )
                }
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertFalse(
                "hasOlderMessages must be false when returned offset does not decrease",
                viewModel.uiState.value.hasOlderMessages,
            )
        }

    @Test
    fun testLoadMessages_handlesJsonObjectToolResult() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessions(any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionInfo(
                                    id = "session-456",
                                    title = "Test",
                                    message_count = 1,
                                ),
                            ),
                        total = 1,
                    ),
                )
            val jsonObjectContent =
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                }
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    role = "tool",
                                    content = jsonObjectContent,
                                ),
                            ),
                        offset = 0,
                        total = 1,
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            val messages = viewModel.uiState.value.messages
            assertEquals(1, messages.size)
            assertEquals("{\"status\":\"ok\"}", messages[0].content)
        }

    // ── Newest-anchored paging (issue #859) ───────────────────────────────

    @Test
    fun testInitialLoad_latestOrder_pagesFromNewest() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            val captured = mutableListOf<Triple<Int, Int, String?>>()
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                captured.add(Triple(arg<Int>(2), arg<Int>(1), arg<String?>(3)))
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            (1..150).map { i ->
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = i,
                                    role = "assistant",
                                    content = JsonPrimitive("Msg $i"),
                                )
                            },
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 150,
                            ),
                    ),
                )
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // One request: the newest page at offset 0 with order=latest —
            // no count-based anchor, no sessions-list fetch.
            assertEquals(1, captured.size)
            assertEquals(Triple(0, 150, "latest"), captured[0])
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertEquals(150, viewModel.uiState.value.messages.size)
            // Stable keys come from the server row id, not the from-end position.
            assertEquals(
                canonicalMessageId(fakeSubmission.scope.draftTargetKey(), "session-456", 150),
                viewModel.uiState.value.messages
                    .last()
                    .id,
            )
        }

    @Test
    fun testInitialLoad_latestOrder_shortPage_hasNoOlder() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 1,
                                    role = "assistant",
                                    content = JsonPrimitive("Only Msg"),
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 1,
                            ),
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.hasOlderMessages)
            assertEquals(1, viewModel.uiState.value.messages.size)
        }

    @Test
    fun testLoadOlderMessages_latestOrder_increasesOffset() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            val captured = mutableListOf<Triple<Int, Int, String?>>()
            var page = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                captured.add(Triple(arg<Int>(2), arg<Int>(1), arg<String?>(3)))
                page += 1
                val (offset, returned) =
                    when (page) {
                        1 -> 0 to 150
                        2 -> 150 to 150
                        else -> 300 to 50
                    }
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            (1..returned).map { i ->
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = offset + i,
                                    role = "assistant",
                                    content = JsonPrimitive("Page $page Msg $i"),
                                )
                            },
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                limit = 150,
                                offset = offset,
                                order = "latest",
                                returned = returned,
                            ),
                    ),
                )
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasOlderMessages)

            // Older pages INCREASE the from-end offset, always full-size.
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(Triple(150, 150, "latest"), captured[1])
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertEquals(300, viewModel.uiState.value.messages.size)

            // Short final page: the oldest boundary — pagination stops.
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(Triple(300, 150, "latest"), captured[2])
            assertFalse(viewModel.uiState.value.hasOlderMessages)
            assertEquals(350, viewModel.uiState.value.messages.size)
        }

    @Test
    fun testSync_latestOrder_grownTranscript_keepsNewestMessage() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            var page = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                page += 1
                // Hydration serves rows 1..150; the transcript then grows by 10
                // and the sync refetches the newest page (rows 11..160).
                val (from, to) = if (page == 1) 1 to 150 else 11 to 160
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            (from..to).map { i ->
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = i,
                                    role = "assistant",
                                    content = JsonPrimitive("Msg $i"),
                                )
                            },
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 150,
                            ),
                    ),
                )
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertEquals(150, viewModel.uiState.value.messages.size)

            viewModel.syncCurrentSession()
            advanceUntilIdle()

            // 160 rows: the 10 new ones appended, the existing 150 kept —
            // no duplicates, no dropped newest copy (stable row-id keys).
            assertEquals(160, viewModel.uiState.value.messages.size)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.id == canonicalMessageId(fakeSubmission.scope.draftTargetKey(), "session-456", 160) },
            )
        }

    @Test
    fun syncAppliesProjectedToolBodyOverCachedUuidCopy() = runTest { assertProjectedToolSync("projected-call") }

    @Test
    fun syncAppliesProjectedToolBodyOverCachedUuidWithoutCallId() = runTest { assertProjectedToolSync("") }

    private suspend fun TestScope.assertProjectedToolSync(cachedCallId: String) {
        val physical = """{"result":"physical tool output"}"""
        val (viewModel, _) = createViewModelWithSession()
        val live =
            ChatMessage(
                id = "cached-uuid-tool",
                role = MessageRole.TOOL,
                content = physical,
                toolCallId = cachedCallId,
            )
        // The indexed cache preserves logical IDs; the old import intentionally prefixes legacy IDs.
        val (cachedOwner, _) = fakeRepo.openTranscript(fakeSubmission.scope.draftTargetKey(), "session-456")
        assertTrue(fakeRepo.save(fakeRepo.prepare(cachedOwner, listOf(live), ResponseLedger())))
        var page = 0
        coEvery {
            ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
        } coAnswers {
            page += 1
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    messages =
                        if (page == 1) {
                            emptyList()
                        } else {
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 23,
                                    role = "tool",
                                    content = JsonPrimitive(physical),
                                    tool_call_id = "projected-call",
                                    display_content = JsonPrimitive("safe projected output"),
                                ),
                            )
                        },
                    pagination =
                        com.m57.hermescontrol.data.model.PaginationInfo(
                            limit = 150,
                            offset = 0,
                            order = "latest",
                        ),
                ),
            )
        }
        viewModel.switchSession("session-456")
        advanceUntilIdle()
        assertTrue(
            "Fixture must restore the cached UUID before sync",
            viewModel.uiState.value.messages.any {
                it.id ==
                    live.id
            },
        )
        assertEquals(
            physical,
            viewModel.uiState.value.messages
                .single { it.id == live.id }
                .content,
        )

        viewModel.syncCurrentSession()
        advanceUntilIdle()

        assertTrue(
            "Display sync must preserve the existing tool UUID",
            viewModel.uiState.value.messages.any {
                it.id ==
                    live.id
            },
        )
        assertEquals(
            "safe projected output",
            viewModel.uiState.value.messages
                .single { it.id == live.id }
                .content,
        )
        assertEquals(
            "safe projected output",
            fakeRepo
                .readTranscript(fakeSubmission.scope.draftTargetKey(), "session-456")
                .messages
                .single {
                    it.id ==
                        live.id
                }.content,
        )
        assertEquals(
            1,
            viewModel.uiState.value.messages
                .count { it.role == MessageRole.TOOL },
        )
        assertEquals(
            1,
            fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), "session-456").messages.count {
                it.role == MessageRole.TOOL
            },
        )
    }

    @Test
    fun syncLegacyImportedToolPreservesUnknownProvenance() =
        runTest {
            val physical = """{"result":"physical tool output"}"""
            val (viewModel, _) = createViewModelWithSession()
            val old =
                ChatMessage(
                    id = "cached-uuid-tool",
                    role = MessageRole.TOOL,
                    content = physical,
                    toolCallId = "projected-call",
                )
            // Preserve the original v74 unscoped-import path, including its deliberate legacy prefix.
            fakeRepo.persistMessages(listOf(old), "session-456")
            var page = 0
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                page += 1
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            if (page == 1) {
                                emptyList()
                            } else {
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        id = 23,
                                        role = "tool",
                                        content = JsonPrimitive(physical),
                                        tool_call_id = "projected-call",
                                        display_content = JsonPrimitive("safe projected output"),
                                    ),
                                )
                            },
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                            ),
                    ),
                )
            }
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val importedId = "legacy-${old.id}"
            assertEquals(
                physical,
                viewModel.uiState.value.messages
                    .single { it.id == importedId }
                    .content,
            )
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.id == old.id },
            )
            viewModel.syncCurrentSession()
            advanceUntilIdle()
            val next = viewModel.uiState.value
            assertEquals(physical, next.messages.single { it.id == importedId }.content)
            assertEquals(
                "safe projected output",
                next.messages
                    .single {
                        it.id == canonicalMessageId(fakeSubmission.scope.draftTargetKey(), "session-456", 23)
                    }.content,
            )
            assertTrue(
                next.responseLedger.responses.all {
                    it.authorityDecision(next.messages.map { m -> m.id }.toSet()) == ReplyAuthorityDecision.Unproven
                },
            )
            val saved = fakeRepo.readTranscript(fakeSubmission.scope.draftTargetKey(), "session-456")
            assertEquals(physical, saved.messages.single { it.id == importedId }.content)
            assertEquals(2, saved.messages.count { it.role == MessageRole.TOOL })
            assertNull(saved.payload.ledger.aliases["23"])
        }

    // ── Attachment open (issue #724) ─────────────────────────────────────

    @Test
    fun `openAttachment GATEWAY success fires ACTION_VIEW with FileProvider uri`() =
        runTest {
            val cacheDir =
                java.io.File(System.getProperty("java.io.tmpdir"), "hermes_open_test_${System.nanoTime()}")
            cacheDir.mkdirs()
            every { app.cacheDir } returns cacheDir

            mockkObject(GatewayFileClient)
            val file = java.io.File(cacheDir, "note.txt").apply { writeBytes("hello".toByteArray()) }
            coEvery {
                GatewayFileClient.fetch(any(), any())
            } returns GatewayFileResult.Success(GatewayFile("note.txt", "text/plain", file))

            val intentSlot = slot<Intent>()
            // android.jar stubs Intent ctor/setters to throw "not mocked" in unit tests;
            // mock the Intent constructor so openWithView can build + deliver it,
            // and capture the constructed instance to assert on its setters.
            mockkConstructor(Intent::class)
            every { anyConstructed<Intent>().setDataAndType(any(), any()) } answers { self as Intent }
            every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
            mockkStatic(FileProvider::class)
            every {
                FileProvider.getUriForFile(any(), any(), any())
            } returns mockk(relaxed = true)
            every { app.getApplicationContext() } returns app
            every { app.applicationContext } returns app
            every { app.startActivity(capture(intentSlot)) } returns Unit

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "note.txt",
                    mimeType = "text/plain",
                    gatewayUrl = "https://gw/api/files/download?path=%2Ftmp%2Fnote.txt&token=t",
                    source = AttachmentSource.GATEWAY,
                )

            vm.openAttachment(attachment)
            advanceUntilIdle()

            // fetch was invoked (proves we entered the IO launch)
            coVerify { GatewayFileClient.fetch(any(), any()) }
            // ACTION_VIEW intent delivered, with the right type + grant flag.
            verify { app.startActivity(any()) }
            verify { intentSlot.captured.setDataAndType(any(), eq("text/plain")) }
            verify { intentSlot.captured.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            assertNull(vm.uiState.value.openError)
            cacheDir.deleteRecursively()
        }

    @Test
    fun `saveAttachment writes gateway file to selected document`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val file =
                java.io
                    .File(System.getProperty("java.io.tmpdir"), "note_${System.nanoTime()}.txt")
                    .apply { writeText("downloaded") }
            coEvery { GatewayFileClient.fetch("/tmp/note.txt", any()) } returns
                GatewayFileResult.Success(GatewayFile("note.txt", "text/plain", file))
            // copyChunked is mocked with the object — restore real copy so the
            // saved document actually receives the bytes.
            coEvery { GatewayFileClient.copyChunked(any(), any()) } coAnswers {
                firstArg<java.io.InputStream>().copyTo(secondArg<java.io.OutputStream>())
            }
            val resolver = mockk<android.content.ContentResolver>()
            val output = java.io.ByteArrayOutputStream()
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            every { resolver.openOutputStream(destination, "wt") } returns output

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "note.txt",
                    mimeType = "text/plain",
                    gatewayUrl = "https://gw/api/files/download?path=%2Ftmp%2Fnote.txt&token=t",
                    source = AttachmentSource.GATEWAY,
                )

            vm.saveAttachment(attachment, destination)
            advanceUntilIdle()

            assertArrayEquals("downloaded".toByteArray(), output.toByteArray())
            assertNull(vm.uiState.value.savingAttachmentPath)
            assertEquals("Saved note.txt", vm.uiState.value.openError)
        }

    @Test
    fun `saveAttachment never deletes the selected document when download fails`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            coEvery {
                GatewayFileClient.fetch("/tmp/missing.pdf", any())
            } returns GatewayFileResult.NotFound

            val (viewModel, _) = createViewModelWithSession()
            viewModel.saveAttachment(
                attachment =
                    Attachment(
                        uri = "gateway:/tmp/missing.pdf",
                        name = "missing.pdf",
                        mimeType = "application/pdf",
                        size = 0,
                        source = AttachmentSource.GATEWAY,
                        gatewayUrl = "https://host/files/download?path=%2Ftmp%2Fmissing.pdf",
                    ),
                destination = destination,
            )
            advanceUntilIdle()

            verify(exactly = 0) { resolver.delete(any(), any(), any()) }
            assertNull(viewModel.uiState.value.savingAttachmentPath)
        }

    @Test
    fun `saveAttachment never deletes the selected document when writing fails`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            every { resolver.openOutputStream(destination, "wt") } throws IllegalStateException("write failed")
            coEvery { GatewayFileClient.fetch("/tmp/report.pdf", any()) } returns
                GatewayFileResult.Success(
                    GatewayFile(
                        "report.pdf",
                        "application/pdf",
                        java.io
                            .File(System.getProperty("java.io.tmpdir"), "report_${System.nanoTime()}.pdf")
                            .apply { writeBytes(byteArrayOf(1)) },
                    ),
                )

            val viewModel = createViewModel()
            viewModel.saveAttachment(
                Attachment(
                    uri = "gateway:/tmp/report.pdf",
                    name = "report.pdf",
                    mimeType = "application/pdf",
                    source = AttachmentSource.GATEWAY,
                ),
                destination,
            )
            advanceUntilIdle()

            verify(exactly = 0) { resolver.delete(any(), any(), any()) }
            assertNull(viewModel.uiState.value.savingAttachmentPath)
            assertTrue(
                viewModel.uiState.value.openError
                    .orEmpty()
                    .startsWith("Could not save"),
            )
        }

    @Test
    fun `saveAttachment ignores overlapping saves`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val firstResult = CompletableDeferred<GatewayFileResult>()
            val fetchedPaths = mutableListOf<String>()
            coEvery { GatewayFileClient.fetch(capture(fetchedPaths), any()) } coAnswers { firstResult.await() }
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            every { app.contentResolver } returns resolver
            val viewModel = createViewModel()

            viewModel.saveAttachment(
                Attachment("gateway:/tmp/first.pdf", "first.pdf", "application/pdf", source = AttachmentSource.GATEWAY),
                mockk(),
            )
            runCurrent()
            viewModel.saveAttachment(
                Attachment(
                    "gateway:/tmp/second.pdf",
                    "second.pdf",
                    "application/pdf",
                    source = AttachmentSource.GATEWAY,
                ),
                mockk(),
            )

            assertEquals("/tmp/first.pdf", viewModel.uiState.value.savingAttachmentPath)
            assertEquals(listOf("/tmp/first.pdf"), fetchedPaths)

            firstResult.complete(GatewayFileResult.NotFound)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.savingAttachmentPath)
        }

    @Test
    fun `openAttachment GATEWAY not-found surfaces openError`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_open_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            coEvery {
                GatewayFileClient.fetch(any(), any())
            } returns GatewayFileResult.NotFound

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "missing.pdf",
                    mimeType = "application/pdf",
                    gatewayUrl = "https://gw/api/files/download?path=%2Ftmp%2Fmissing.pdf&token=t",
                    source = AttachmentSource.GATEWAY,
                )

            vm.openAttachment(attachment)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.openError)
            assertTrue(
                vm.uiState.value.openError!!
                    .contains("missing.pdf"),
            )
        }

    @Test
    fun `openAttachment shows opening state while fetch is in flight and clears it after`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_open_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val firstResult = CompletableDeferred<GatewayFileResult>()
            val fetchedPaths = mutableListOf<String>()
            coEvery { GatewayFileClient.fetch(capture(fetchedPaths), any()) } coAnswers { firstResult.await() }

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "gateway:/tmp/big.pdf",
                    name = "big.pdf",
                    mimeType = "application/pdf",
                    source = AttachmentSource.GATEWAY,
                )

            vm.openAttachment(attachment)
            runCurrent()

            // Indicator visible while the download is in flight…
            assertEquals(listOf("/tmp/big.pdf"), fetchedPaths)
            assertEquals("/tmp/big.pdf", vm.uiState.value.openingAttachmentPath)

            firstResult.complete(GatewayFileResult.NotFound)
            advanceUntilIdle()

            // …and cleared in all outcomes.
            assertNull(vm.uiState.value.openingAttachmentPath)
            assertTrue(
                vm.uiState.value.openError
                    .orEmpty()
                    .contains("big.pdf"),
            )
        }

    @Test
    fun `sendMessage while session create is in flight queues prompt and dispatches on SESSION_CREATE`() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val sentPrompts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), capture(sentPrompts), any(), any()) } returns "req-send-1"

            vm.sendMessage("My super important long prompt")
            runCurrent() // Observe pending input before its bounded binding deadline.

            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            assertEquals(
                "My super important long prompt",
                vm.uiState.value.outgoingDrafts
                    .single()
                    .text,
            )
            assertFalse(vm.uiState.value.isAgentTyping)

            // Now emit SESSION_CREATE result for the active session request
            val createReqId = "req-id-$reqCount"
            emitEvent(
                WsEvent.RpcResult(
                    createReqId,
                    mapOf("session_id" to "session-969", "stored_session_id" to "session-storage-969"),
                ),
            )
            advanceUntilIdle()

            // Prompt should be dispatched automatically!
            assertEquals(listOf("My super important long prompt"), sentPrompts)
            assertEquals("session-storage-969", vm.uiState.value.currentSessionId)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "My super important long prompt" },
            )
        }

    @Test
    fun `reconnect with unpersisted session re-creates session on new socket and dispatches queued prompt`() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Session has no server presence yet (fresh session, no prompt sent).
            // Simulate reconnect:
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // On reconnect, an unpersisted session triggers createNewSession to mint a valid session on the new socket
            verify(atLeast = 2) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }

            val sentPrompts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), capture(sentPrompts), any(), any()) } returns "req-send-2"

            val createReqId = "req-id-$reqCount"
            vm.sendMessage("Prompt typed during reconnect")
            runCurrent() // Observe pending input before its bounded binding deadline.

            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            assertEquals(
                "Prompt typed during reconnect",
                vm.uiState.value.outgoingDrafts
                    .single()
                    .text,
            )
            assertFalse(vm.uiState.value.isAgentTyping)

            // Complete the new SESSION_CREATE RPC
            emitEvent(
                WsEvent.RpcResult(
                    createReqId,
                    mapOf("session_id" to "session-reconnected", "stored_session_id" to "storage-reconnected"),
                ),
            )
            advanceUntilIdle()

            assertTrue(sentPrompts.contains("Prompt typed during reconnect"))
        }

    @Test
    fun `session creation failure clears agent typing and surfaces error for queued prompt`() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            vm.sendMessage("Prompt before failure")
            runCurrent() // Observe pending input before its bounded binding deadline.

            assertFalse(vm.uiState.value.isAgentTyping)

            val createReqId = "req-id-$reqCount"
            // Emit RPC error for SESSION_CREATE
            emitEvent(WsEvent.RpcError(createReqId, JsonRpcError(code = 500, message = "Backend exploded")))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isAgentTyping)
            assertNotNull(vm.uiState.value.errorMessage)
            assertTrue(
                vm.uiState.value.errorMessage!!
                    .contains("Backend exploded"),
            )
        }

    @Test
    fun subagentRosterRepairsMissedCompletionEvenWhileParentIsRunning() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.SubagentEvent("subagent.start", mapOf("subagent_id" to "sa-1"), sessionId))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.subagentIndicators
                    .single()
                    .isRunning,
            )
            every { HermesWsClient.readSubagents(fakeSubmission.scope, sessionId) } returns
                CompletableDeferred<Any?>(mapOf("subagents" to emptyList<Any>()))

            vm.syncCurrentSession()
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.subagentIndicators
                    .isEmpty(),
            )
            assertTrue(vm.uiState.value.isAgentTyping)
        }

    @Test
    fun subagentSnapshotCannotOverwriteNewerStreamedActivity() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            val snapshot = CompletableDeferred<Any?>()
            every { HermesWsClient.readSubagents(any(), any()) } returns snapshot
            vm.refreshSubagents()
            runCurrent()
            emitEvent(WsEvent.SubagentEvent("subagent.start", mapOf("subagent_id" to "sa-new"), sessionId))
            runCurrent()
            snapshot.complete(mapOf("subagents" to emptyList<Any>()))
            advanceUntilIdle()
            assertEquals(
                "sa-new",
                vm.uiState.value.subagentIndicators
                    .single()
                    .subagentId,
            )
        }

    @Test
    fun subagentSnapshotCannotPopulateAnotherSession() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val snapshot = CompletableDeferred<Any?>()
            every { HermesWsClient.readSubagents(any(), any()) } returns snapshot
            vm.refreshSubagents()
            runCurrent()
            vm.switchSession("other-session")
            snapshot.complete(mapOf("subagents" to listOf(mapOf("subagent_id" to "sa-old", "status" to "running"))))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.subagentIndicators
                    .isEmpty(),
            )
        }

    @Test
    fun unsupportedSubagentRosterPreservesEventsAndStopsRepeatedRequests() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.SubagentEvent("subagent.start", mapOf("subagent_id" to "sa-1"), sessionId))
            advanceUntilIdle()
            every { HermesWsClient.readSubagents(any(), any()) } answers {
                CompletableDeferred<Any?>().apply { completeExceptionally(IllegalStateException("Method not found")) }
            }
            repeat(5) {
                vm.refreshSubagents()
                advanceUntilIdle()
            }
            verify(exactly = 3) { HermesWsClient.readSubagents(any(), any()) }
            assertTrue(
                vm.uiState.value.subagentIndicators
                    .single()
                    .isRunning,
            )

            every { HermesWsClient.readSubagents(any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("subagents" to emptyList<Any>()))
            vm.refreshCurrentSession()
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.subagentIndicators
                    .isEmpty(),
            )
        }

    // ── Subagent steering & cancellation (issue #1030) ───────────────────

    @Test
    fun testSteerSubagent_sendsRedirectWithoutInventingStatus() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val indicator =
                SubagentIndicator(
                    type = "subagent.start",
                    subagentId = "sub-123",
                    goal = "Search database",
                    status = "running",
                )
            emitEvent(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "sub-123", "goal" to "Search database"),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            every { app.getString(com.m57.hermescontrol.R.string.subagent_correction_requested, any()) } returns
                "Course correction requested: Use index scan instead"
            viewModel.steerSubagent(indicator, "Use index scan instead")
            advanceUntilIdle()

            val updated =
                viewModel.uiState.value.subagentIndicators
                    .first { it.subagentId == "sub-123" }
            assertEquals("running", updated.status)
            assertTrue(updated.logs.any { it.text.contains("Use index scan instead") })
            io.mockk.verify { HermesWsClient.sendRedirect(sessionId, "/steer sub-123 Use index scan instead", any()) }
        }

    @Test
    fun testStopSubagent_sendsRedirectWithoutClaimingCancellation() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val indicator =
                SubagentIndicator(
                    type = "subagent.start",
                    subagentId = "sub-123",
                    goal = "Run deep scan",
                    status = "running",
                )
            emitEvent(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "sub-123", "goal" to "Run deep scan"),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            every { app.getString(com.m57.hermescontrol.R.string.subagent_stop_requested) } returns "Stop requested"
            viewModel.stopSubagent(indicator)
            advanceUntilIdle()

            val updated =
                viewModel.uiState.value.subagentIndicators
                    .first { it.subagentId == "sub-123" }
            assertEquals("running", updated.status)
            assertTrue(updated.logs.any { it.text == "Stop requested" })
            io.mockk.verify { HermesWsClient.sendRedirect(sessionId, "/stop sub-123", any()) }
        }

    private fun captureRecoveryResumes(): MutableList<String> {
        val requests = mutableListOf<String>()
        every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
            val id = "recovery-${requests.size + 1}"
            requests += id
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.sendRedirect(any(), any(), any()) } returns "recovery-redirect"
        return requests
    }

    private suspend fun TestScope.interruptedRecovery(): Pair<ChatViewModel, MutableList<String>> {
        val (vm, sessionId) = createViewModelWithSession()
        stubEmptySessionRests(sessionId)
        val requests = captureRecoveryResumes()
        emitEvent(WsEvent.MessageStart(sessionId))
        emitEvent(WsEvent.MessageToken("Preserved partial", sessionId))
        runCurrent()
        fakeSubmission.ready = false
        mockConnectionStatus.value = ConnectionStatus.RECONNECTING
        runCurrent()
        fakeSubmission.ready = true
        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        emitEvent(WsEvent.GatewayReady(null))
        runCurrent()
        assertTrue(vm.uiState.value.responseStatusUnknown)
        return vm to requests
    }

    private suspend fun recoveryAck(
        id: String,
        running: Boolean,
        runtimeId: String = "runtime-recovered",
    ) {
        emitEvent(
            WsEvent.RpcResult(
                id,
                mapOf(
                    "session_id" to runtimeId,
                    "resumed" to "session-123",
                    "running" to running,
                    "status" to if (running) "streaming" else "idle",
                ),
            ),
        )
    }

    @Test
    fun recovery_sameSelectedConversationReadsAgainWithoutReplayingUnknownDraft() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            recoveryAck(requests.last(), false)
            runCurrent()
            fakeSubmission.nextPromptResult = SubmissionResult.Unknown
            vm.sendMessage("Uncertain old intent")
            runCurrent()
            val draft = fakeDraftStore.drafts.value.single()
            assertEquals(DraftState.UNKNOWN, draft.state)
            val before = requests.size
            vm.openDraftConversation(draft.id)
            vm.openDraftConversation(draft.id)
            runCurrent()
            assertEquals(before + 2, requests.size)
            recoveryAck(requests.last(), false)
            recoveryAck(requests[before], true, "stale-runtime")
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isAgentTyping)
            assertEquals("runtime-recovered", ActiveSessionHolder.activeSessionId.value)
            assertEquals(draft, fakeDraftStore.drafts.value.single())
            verify(exactly = 1) { HermesWsClient.sendMessage(any(), "Uncertain old intent", any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
        }

    @Test
    fun recovery_idleAckResolvesExecutionAndPreservesPartialWithoutClaimingOldIntentDelivered() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.responseStatusUnknown)
            assertFalse(vm.uiState.value.isAgentTyping)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "Preserved partial" },
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun recovery_missingExecutionEvidenceKeepsUnknownButFreshInputUsesQueuedPrompt() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            emitEvent(WsEvent.RpcResult(requests.last(), mapOf("session_id" to "runtime-recovered")))
            runCurrent()
            assertTrue(vm.uiState.value.responseStatusUnknown)
            vm.sendMessage("Fresh explicit input")
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            assertTrue(vm.uiState.value.responseStatusUnknown)
            emitEvent(WsEvent.MessageStart("runtime-recovered"))
            emitEvent(WsEvent.MessageDone("runtime-recovered"))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage("runtime-recovered", "Fresh explicit input", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
            assertFalse(vm.uiState.value.responseStatusUnknown)
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
        }

    @Test
    fun recovery_runningAckAllowsKnownActiveRedirectWithoutInterruptOrReplay() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            recoveryAck(requests.last(), true)
            runCurrent()
            assertFalse(vm.uiState.value.responseStatusUnknown)
            assertTrue(vm.uiState.value.isAgentTyping)
            vm.sendMessage("Explicit current correction", steer = true)
            advanceUntilIdle()
            verify(
                exactly = 1,
            ) { HermesWsClient.sendRedirect("runtime-recovered", "Explicit current correction", any()) }
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun recovery_resumeAckDrainsFreshBoundIntentOnceDespiteRepeatedTapAndDuplicateAck() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("New input while binding")
            vm.sendMessage("New input while binding")
            runCurrent()
            assertEquals(1, fakeDraftStore.drafts.value.size)
            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            recoveryAck(requests.last(), false)
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            verify(
                exactly = 1,
            ) { HermesWsClient.sendMessage("runtime-recovered", "New input while binding", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
            assertTrue(
                vm.uiState.value.activeDraftIds
                    .isEmpty(),
            )
        }

    @Test
    fun recovery_multipleDistinctFreshInputsSurviveBindingWithoutReplacingOneAnother() =
        runTest {
            fakeSubmission.addressedPromptAcks = true
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("Fresh first")
            vm.sendMessage("Fresh second")
            runCurrent()
            assertEquals(2, fakeDraftStore.drafts.value.size)
            recoveryAck(requests.last(), true)
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            emitEvent(WsEvent.MessageDone("runtime-recovered"))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage("runtime-recovered", "Fresh first", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendMessage("runtime-recovered", "Fresh second", any(), true) }
            emitEvent(WsEvent.MessageStart("runtime-recovered"))
            completeAddressed(vm, "runtime-recovered")
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage("runtime-recovered", "Fresh second", any(), true) }
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
        }

    @Test
    fun recovery_supersededResumeAckCannotDrainFreshInputOrReplaceBinding() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("Fresh pending")
            runCurrent()
            vm.switchSession("session-123")
            runCurrent()
            recoveryAck(requests.first(), false, "old-runtime")
            runCurrent()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage("runtime-recovered", "Fresh pending", any(), true) }
            assertEquals("runtime-recovered", ActiveSessionHolder.activeSessionId.value)
        }

    @Test
    fun recovery_lateIdleAckDoesNotOverwriteObservedNewRunningTurn() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            emitEvent(WsEvent.MessageStart("session-123"))
            emitEvent(WsEvent.MessageToken("New live text", "session-123"))
            runCurrent()
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isAgentTyping)
            assertFalse(vm.uiState.value.responseStatusUnknown)
            assertTrue(
                vm.streamingState.value.streamingMessage
                    ?.content
                    .orEmpty()
                    .contains("New live text"),
            )
        }

    @Test
    fun recovery_lateRunningAckDoesNotOverwriteObservedCompletion() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            emitEvent(WsEvent.MessageDone("session-123"))
            runCurrent()
            recoveryAck(requests.last(), true)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isAgentTyping)
            assertFalse(vm.uiState.value.responseStatusUnknown)
        }

    @Test
    fun recovery_pendingQueueSurvivesBindingTimeoutAndSendsAfterIdleAck() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("No binding acknowledgement")
            runCurrent()
            testScheduler.advanceTimeBy(5_001)
            runCurrent()
            assertTrue(
                vm.uiState.value.activeDraftIds
                    .isEmpty(),
            )
            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            verify(
                exactly = 1,
            ) { HermesWsClient.sendMessage("runtime-recovered", "No binding acknowledgement", any(), true) }
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
        }

    @Test
    fun recovery_conversationSwitchRetainsFreshDraftWithoutDispatchingIntoNewConversation() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("Bound to original")
            runCurrent()
            stubEmptySessionRests("other-session")
            vm.switchSession("other-session")
            runCurrent()
            recoveryAck(requests.first(), false)
            advanceUntilIdle()
            assertEquals("other-session", vm.uiState.value.currentSessionId)
            assertEquals(
                "session-123",
                fakeDraftStore.drafts.value
                    .single()
                    .savedSessionId,
            )
            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun recovery_scopeChangeRejectsLateBindingAndKeepsIntentUnderOriginalTarget() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("Original scope")
            runCurrent()
            val original =
                fakeDraftStore.drafts.value
                    .single()
                    .scope
            fakeSubmission.scope = fakeSubmission.scope.copy(generation = 2)
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            assertEquals(
                original,
                fakeDraftStore.drafts.value
                    .single()
                    .scope,
            )
            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun recovery_lostFreshPromptAckRemainsUnknownAndCannotBeReplayedByCheckingConversation() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("Fresh uncertain")
            runCurrent()
            fakeSubmission.nextPromptResult = SubmissionResult.Unknown
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            val draft = fakeDraftStore.drafts.value.single()
            assertEquals(DraftState.UNKNOWN, draft.state)
            vm.openDraftConversation(draft.id)
            runCurrent()
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            assertEquals(draft, fakeDraftStore.drafts.value.single())
            verify(exactly = 1) { HermesWsClient.sendMessage(any(), "Fresh uncertain", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
        }

    @Test
    fun recovery_definitelyUnsentInputAfterIdleBindingDoesNotLeaveStaleTyping() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("Definitely unsent")
            runCurrent()
            fakeSubmission.nextPromptResult = SubmissionResult.NotSent
            recoveryAck(requests.last(), false)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isAgentTyping)
            assertFalse(vm.uiState.value.responseStatusUnknown)
            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun recovery_newUnknownWriteRemainsUnknownThroughUnrelatedReconnectAndNextInputQueues() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            recoveryAck(requests.last(), false)
            runCurrent()
            fakeSubmission.nextPromptResult = SubmissionResult.Unknown
            vm.sendMessage("Fresh lost acknowledgement")
            runCurrent()
            assertTrue(vm.uiState.value.responseStatusUnknown)
            emitEvent(WsEvent.GatewayReady(null))
            runCurrent()
            assertTrue(vm.uiState.value.responseStatusUnknown)
            emitEvent(WsEvent.RpcResult(requests.last(), mapOf("session_id" to "runtime-recovered")))
            runCurrent()
            fakeSubmission.nextPromptResult = null
            vm.sendMessage("Different fresh input")
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Different fresh input", any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
            assertEquals(
                setOf(DraftState.UNKNOWN, DraftState.QUEUED),
                fakeDraftStore.drafts.value
                    .map { it.state }
                    .toSet(),
            )
        }

    @Test
    fun recovery_hydratingResumeDoesNotClaimIdleExecution() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            emitEvent(
                WsEvent.RpcResult(
                    requests.last(),
                    mapOf(
                        "session_id" to "runtime-recovered",
                        "running" to false,
                        "status" to "resuming",
                        "hydrating" to true,
                    ),
                ),
            )
            advanceUntilIdle()
            assertTrue(vm.uiState.value.responseStatusUnknown)
            assertTrue(vm.uiState.value.isAgentTyping)
            assertEquals(
                "Preserved partial",
                vm.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    @Test
    fun recovery_delayedIdleAckCannotClearFreshWireAttemptAwaitingItsAcknowledgement() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            emitEvent(WsEvent.RpcResult(requests.last(), mapOf("session_id" to "runtime-recovered")))
            runCurrent()
            vm.switchSession("session-123")
            runCurrent()
            val release = CompletableDeferred<Unit>()
            fakeSubmission.beforePrompt = { release.await() }
            vm.sendMessage("Fresh awaiting acknowledgement")
            runCurrent()
            recoveryAck(requests.last(), false)
            runCurrent()
            assertTrue(vm.uiState.value.isAgentTyping)
            assertFalse(vm.uiState.value.responseStatusUnknown)
            vm.switchSession("session-123")
            runCurrent()
            recoveryAck(requests.last(), false)
            runCurrent()
            assertTrue(vm.uiState.value.isAgentTyping)
            release.complete(Unit)
            advanceUntilIdle()
            verify(
                exactly = 1,
            ) { HermesWsClient.sendMessage("runtime-recovered", "Fresh awaiting acknowledgement", any(), true) }
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
        }

    @Test
    fun recovery_disconnectBeforeBindingDistinguishesUnsentInputFromUnknownRemoteWrite() =
        runTest {
            val vm = createViewModel()
            runCurrent()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            runCurrent()
            vm.sendMessage("Never written")
            runCurrent()
            fakeSubmission.ready = false
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            assertFalse(vm.uiState.value.responseStatusUnknown)
            assertFalse(vm.uiState.value.isAgentTyping)
            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun recovery_mismatchedResumeAcknowledgementNeverDispatchesFreshIntentToAnotherSession() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            vm.sendMessage("Must stay in original")
            runCurrent()
            emitEvent(
                WsEvent.RpcResult(
                    requests.last(),
                    mapOf(
                        "session_id" to "other-runtime",
                        "resumed" to "other-session",
                        "running" to false,
                        "status" to "idle",
                    ),
                ),
            )
            runCurrent()
            assertEquals("session-123", vm.uiState.value.currentSessionId)
            assertEquals(
                DraftState.QUEUED,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            assertTrue(vm.uiState.value.responseStatusUnknown)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun recovery_definitelyRejectedWireAfterDisconnectDoesNotTurnUnsentDraftIntoUnknownResponse() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            recoveryAck(requests.last(), false)
            runCurrent()
            fakeSubmission.beforePrompt = {
                fakeSubmission.ready = false
                mockConnectionStatus.value = ConnectionStatus.RECONNECTING
                runCurrent()
            }
            vm.sendMessage("Rejected before wire")
            advanceUntilIdle()
            assertFalse(vm.uiState.value.responseStatusUnknown)
            assertEquals(
                DraftState.DRAFT,
                fakeDraftStore.drafts.value
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    // ── Thinking indicator (「思考中」 shimmer) ─────────────────────────────

    private fun ChatViewModel.showsThinking(): Boolean = showsThinkingIndicator(uiState.value, streamingState.value)

    @Test
    fun thinkingIndicatorStaysWithTheTurnThroughCommentaryAndToolsUntilTheFinalAnswer() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            vm.sendMessage("調べて")
            advanceUntilIdle()
            assertTrue("A submitted turn shows progress before the first event", vm.showsThinking())

            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.ReasoningDelta("hidden plan", sessionId))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())

            emitEvent(WsEvent.MessageToken("確認します。", sessionId))
            advanceUntilIdle()
            assertFalse("Streaming commentary is its own progress", vm.showsThinking())

            emitEvent(WsEvent.ToolStart("terminal", mapOf("tool_id" to "t1"), sessionId))
            advanceUntilIdle()
            assertTrue("A tool batch keeps the turn visibly active", vm.showsThinking())
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "確認します。" && !it.isStreaming },
            )

            emitEvent(WsEvent.ToolComplete("terminal", mapOf("tool_id" to "t1"), sessionId))
            emitEvent(WsEvent.ReasoningDelta(" more", sessionId))
            advanceUntilIdle()
            assertTrue("Thinking again after the tool result shows below the commentary", vm.showsThinking())
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "確認します。" },
            )

            emitEvent(WsEvent.MessageToken("結果です。", sessionId))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())

            emitEvent(WsEvent.MessageComplete("結果です。", sessionId))
            advanceUntilIdle()
            assertFalse("The completed turn leaves no indicator", vm.showsThinking())
            assertFalse(vm.uiState.value.isAgentTyping)
        }

    @Test
    fun thinkingIndicatorReturnsAfterStreamedInterimWithoutToolsAndFinalIsNotDuplicated() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("進めて")
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("まず確認します。", sessionId))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())

            emitEvent(WsEvent.MessageInterim("まず確認します。", alreadyStreamed = true, sessionId))
            advanceUntilIdle()
            assertTrue("The turn continues after interim commentary", vm.showsThinking())
            assertNull(vm.streamingState.value.streamingMessage)
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.content == "まず確認します。" },
            )

            emitEvent(WsEvent.ThinkingDelta("考え中", sessionId))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())

            emitEvent(WsEvent.MessageToken("完了しました。", sessionId))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
            assertEquals(
                "完了しました。",
                vm.streamingState.value.streamingMessage
                    ?.content,
            )

            emitEvent(WsEvent.MessageComplete("完了しました。", sessionId, responseReused = true))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
            val turn =
                vm.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
            assertEquals(listOf("まず確認します。", "完了しました。"), turn.map { it.content })
        }

    @Test
    fun thinkingIndicatorKeepsUnstreamedInterimOnceAndShowsAfterIt() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("調べて")
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageInterim("検索します。", alreadyStreamed = false, sessionId))
            emitEvent(WsEvent.MessageInterim("検索します。", alreadyStreamed = false, sessionId))
            advanceUntilIdle()
            assertEquals(
                2,
                vm.uiState.value.messages
                    .count { it.content == "検索します。" },
            )
            assertTrue(vm.showsThinking())

            emitEvent(WsEvent.MessageComplete("検索します。", sessionId, responseReused = true))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
            assertEquals(
                2,
                vm.uiState.value.messages
                    .count { it.content.contains("検索します。") },
            )
        }

    @Test
    fun thinkingIndicatorKeepsIdenticalUnstreamedInterimsFromDistinctSegments() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("調べて")
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageInterim("検索します。", alreadyStreamed = false, sessionId))
            emitEvent(WsEvent.MessageInterim("検索します。", alreadyStreamed = false, sessionId))
            emitEvent(WsEvent.ToolStart("web_search", mapOf("tool_id" to "s1"), sessionId))
            emitEvent(WsEvent.ToolComplete("web_search", mapOf("tool_id" to "s1"), sessionId))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())

            emitEvent(WsEvent.MessageInterim("検索します。", alreadyStreamed = false, sessionId))
            advanceUntilIdle()
            emitEvent(WsEvent.MessageComplete("結果です。", sessionId))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
            assertEquals(
                "Without explicit delivery identity, every unstreamed interim is an independent segment",
                listOf("検索します。", "検索します。", "検索します。", "結果です。"),
                vm.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
                    .map { it.content },
            )
        }

    @Test
    fun thinkingIndicatorFollowsSegmentBoundaryThinkingClearAndReasoningAfterText() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("書いて")
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.ThinkingDelta("", sessionId))
            advanceUntilIdle()
            assertFalse(vm.streamingState.value.isThinking)
            assertTrue("An empty thinking clear is not the end of the turn", vm.showsThinking())

            emitEvent(WsEvent.MessageToken("途中の文", sessionId))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())

            emitEvent(WsEvent.MessageSegmentEnd(sessionId))
            advanceUntilIdle()
            assertTrue("A closed reply segment means work continues", vm.showsThinking())
            assertEquals(
                "途中の文",
                vm.streamingState.value.streamingMessage
                    ?.content,
            )

            emitEvent(WsEvent.MessageToken("。続き", sessionId))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
            emitEvent(WsEvent.ReasoningDelta("reconsider", sessionId))
            advanceUntilIdle()
            assertTrue("Reasoning after already rendered text shows below it", vm.showsThinking())
            assertEquals(
                "途中の文。続き",
                vm.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    @Test
    fun thinkingIndicatorUsesTheActiveTurnWhenTheProviderSendsNoThinking() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("実行して")
            emitEvent(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())
            emitEvent(WsEvent.ToolStart("terminal", mapOf("tool_id" to "a"), sessionId))
            emitEvent(WsEvent.ToolStart("read_file", mapOf("tool_id" to "b"), sessionId))
            emitEvent(WsEvent.ToolComplete("terminal", mapOf("tool_id" to "a"), sessionId))
            emitEvent(WsEvent.ToolComplete("read_file", mapOf("tool_id" to "b"), sessionId))
            advanceUntilIdle()
            assertTrue("A finished tool batch is not a finished turn", vm.showsThinking())
        }

    @Test
    fun thinkingIndicatorWaitsForAnApprovalAndResumesAfterItIsConfirmed() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("消して")
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.ToolStart("terminal", mapOf("tool_id" to "a"), sessionId))
            emitEvent(WsEvent.ToolStart("read_file", mapOf("tool_id" to "b"), sessionId))
            advanceUntilIdle()
            val row = observeApproval(vm)
            assertFalse("Never thinking while the user must approve", vm.showsThinking())

            emitEvent(WsEvent.ToolComplete("read_file", mapOf("tool_id" to "b"), sessionId))
            advanceUntilIdle()
            assertFalse("A sibling tool frame does not override the pending approval", vm.showsThinking())
            assertFalse(vm.uiState.value.isAgentTyping)

            vm.respondToApproval(row.id, "once")
            advanceUntilIdle()
            emitEvent(WsEvent.ToolComplete("terminal", mapOf("tool_id" to "a"), sessionId))
            advanceUntilIdle()
            assertTrue("Work after the confirmed approval is the same turn", vm.showsThinking())
            assertTrue(vm.uiState.value.isAgentTyping)
        }

    @Test
    fun thinkingIndicatorIsHiddenWhileAClarificationWaits() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("決めて")
            emitEvent(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            observeClarify(vm)
            emitEvent(WsEvent.ReasoningDelta("late", sessionId))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
        }

    @Test
    fun thinkingIndicatorEndsOnEveryTerminalAndIgnoresLateFrames() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("一")
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageDone(sessionId))
            emitEvent(WsEvent.MessageComplete("", sessionId))
            advanceUntilIdle()
            assertFalse("done then complete both end the turn", vm.showsThinking())
            emitEvent(WsEvent.ReasoningDelta("late", sessionId))
            emitEvent(WsEvent.ToolComplete("terminal", null, sessionId))
            advanceUntilIdle()
            assertFalse("Late frames never revive a finished turn", vm.showsThinking())
            assertFalse(vm.uiState.value.isAgentTyping)

            vm.sendMessage("二")
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.ReasoningDelta("plan", sessionId))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())
            emitEvent(WsEvent.MessageComplete("Error: provider failed", sessionId))
            advanceUntilIdle()
            assertFalse("An error status completion ends the turn", vm.showsThinking())
            emitEvent(WsEvent.ReasoningDelta("other session", "another-session"))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
        }

    @Test
    fun thinkingIndicatorEndsOnInterruptAndStaysEndedForLateToolFrames() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            every { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("interrupt-1")
                "interrupt-1"
            }
            vm.sendMessage("長い作業")
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.ToolStart("terminal", mapOf("tool_id" to "a"), sessionId))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())
            vm.interruptSession()
            advanceUntilIdle()
            emitEvent(WsEvent.RpcResult("interrupt-1", emptyMap<String, Any?>()))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
            emitEvent(WsEvent.ToolComplete("terminal", mapOf("tool_id" to "a"), sessionId))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
        }

    @Test
    fun thinkingIndicatorFencesLateTokensAndBoundariesUntilTheNextLegitimateTurn() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            every { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("interrupt-1")
                "interrupt-1"
            }

            fun assertEnded(reason: String) {
                assertFalse(reason, vm.showsThinking())
                assertFalse(reason, vm.uiState.value.isAgentTyping)
            }
            vm.sendMessage("一")
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageToken("答え", sessionId))
            emitEvent(WsEvent.MessageComplete("答え", sessionId))
            advanceUntilIdle()
            assertEnded("complete ends the turn")

            emitEvent(WsEvent.MessageToken("late", sessionId))
            advanceUntilIdle()
            assertEnded("a late text token never revives a completed turn")
            assertNull(vm.streamingState.value.streamingMessage)
            emitEvent(WsEvent.MessageSegmentEnd(sessionId))
            advanceUntilIdle()
            assertEnded("a late segment boundary never revives a completed turn")
            emitEvent(WsEvent.MessageToken("", sessionId))
            advanceUntilIdle()
            assertEnded("a late empty token never revives a completed turn")

            vm.sendMessage("二")
            advanceUntilIdle()
            assertTrue("The next legitimate send shows progress", vm.showsThinking())
            emitEvent(WsEvent.MessageToken("新しい", sessionId))
            advanceUntilIdle()
            assertTrue("A turn without message.start still streams", vm.uiState.value.isAgentTyping)
            assertEquals(
                "新しい",
                vm.streamingState.value.streamingMessage
                    ?.content,
            )
            emitEvent(WsEvent.MessageSegmentEnd(sessionId))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())

            vm.interruptSession()
            advanceUntilIdle()
            emitEvent(WsEvent.RpcResult("interrupt-1", emptyMap<String, Any?>()))
            advanceUntilIdle()
            assertEnded("an acknowledged interrupt ends the turn")
            emitEvent(WsEvent.MessageToken("late", sessionId))
            advanceUntilIdle()
            assertEnded("a late token never revives an interrupted turn")
            emitEvent(WsEvent.MessageSegmentEnd(sessionId))
            emitEvent(WsEvent.MessageToken("", sessionId))
            advanceUntilIdle()
            assertEnded("late boundaries never revive an interrupted turn")
        }

    @Test
    fun thinkingIndicatorEndsWhenTheGatewayRefusesThePendingSubmission() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("拒否される")
            advanceUntilIdle()
            assertTrue(vm.showsThinking())

            emitEvent(WsEvent.GatewayError("another session's failure", "another-session"))
            advanceUntilIdle()
            assertTrue("Another session's error is not this submission's refusal", vm.showsThinking())

            // tui_gateway emits only `error` (no message.start/complete) on a refused admission.
            emitEvent(WsEvent.GatewayError("session is active elsewhere", sessionId))
            advanceUntilIdle()
            assertFalse("A refused submission leaves no indicator", vm.showsThinking())
            assertFalse(vm.uiState.value.isAgentTyping)
            assertNotNull(vm.uiState.value.errorMessage)

            vm.sendMessage("もう一度")
            advanceUntilIdle()
            assertTrue("The next send starts normally", vm.showsThinking())
            verify(exactly = 2) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun thinkingIndicatorEndsWhenAFreshQueuedTurnIsRefusedBeforeStart() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            // This turn originated on the server: no prior local PendingAdmission exists.
            emitEvent(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            vm.sendMessage("/queue Queued B")
            advanceUntilIdle()
            assertEquals(
                "Queued B",
                fakeDraftStore.drafts.value
                    .single()
                    .text,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }

            emitEvent(WsEvent.MessageComplete("A finished", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Queued B", any(), true) }
            assertTrue(vm.showsThinking())
            emitEvent(WsEvent.GatewayError("foreign refusal", "another-session"))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())
            emitEvent(WsEvent.GatewayError("B refused before start", sessionId))
            advanceUntilIdle()
            assertFalse("A fresh queued turn's refusal ends its indicator", vm.showsThinking())
            assertFalse(vm.uiState.value.isAgentTyping)
            assertTrue(vm.streamingState.value.turnEnded)
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
        }

    @Test
    fun thinkingIndicatorRefusalCannotReleaseDurableQueueUsingOldIdleEvidence() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            stubEmptySessionRests(sessionId)
            val sent = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                sent += arg<String>(1)
                arg<((String) -> Unit)?>(2)?.invoke("tracked-${sent.size}")
                "tracked-${sent.size}"
            }
            vm.sendMessage("A awaiting start")
            advanceUntilIdle() // Submission ACK settles, but no message.start arrives.
            vm.sendMessage("/queue Durable B")
            advanceUntilIdle()
            val savedB = fakeDraftStore.drafts.value.single()
            assertEquals(DraftState.QUEUED, savedB.state)
            assertEquals(listOf("A awaiting start"), sent)

            emitEvent(WsEvent.GatewayError("A refused", sessionId))
            advanceUntilIdle()
            assertEquals("Refusal is not new queue-release evidence", listOf("A awaiting start"), sent)
            assertEquals(listOf(savedB), fakeDraftStore.drafts.value)
            assertFalse(vm.uiState.value.isAgentTyping)
            assertFalse(vm.showsThinking())
            // Neither duplicate refusal nor a stray terminal for the unobserved turn releases B.
            emitEvent(WsEvent.GatewayError("A refused", sessionId))
            emitEvent(WsEvent.MessageDone(sessionId))
            advanceUntilIdle()
            assertEquals(listOf("A awaiting start"), sent)
            assertEquals(listOf(savedB), fakeDraftStore.drafts.value)

            val requests = captureRecoveryResumes()
            vm.switchSession(sessionId)
            runCurrent()
            recoveryAck(requests.last(), false, sessionId)
            advanceUntilIdle()
            assertEquals(listOf("A awaiting start", "Durable B"), sent)
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Durable B", any(), true) }
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
            emitEvent(WsEvent.MessageDone(sessionId))
            advanceUntilIdle()
            assertEquals(listOf("A awaiting start", "Durable B"), sent)
        }

    @Test
    fun thinkingIndicatorDefinitelyUnsentFreshAttemptRestoresTheEndedFence() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageComplete("A finished", sessionId))
            advanceUntilIdle()
            assertTrue(vm.streamingState.value.turnEnded)
            fakeSubmission.nextPromptResult = SubmissionResult.NotSent
            vm.sendMessage("Definitely unsent B")
            advanceUntilIdle()
            val unsent = fakeDraftStore.drafts.value.single()
            assertEquals("Definitely unsent B", unsent.text)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Definitely unsent B", any(), any()) }
            emitEvent(WsEvent.MessageToken("late A", sessionId))
            emitEvent(WsEvent.MessageSegmentEnd(sessionId))
            advanceUntilIdle()
            assertFalse("NotSent must not reopen A to late tokens and boundaries", vm.showsThinking())
            assertFalse(vm.uiState.value.isAgentTyping)
            assertTrue(vm.streamingState.value.turnEnded)
            assertNull(vm.streamingState.value.streamingMessage)
            assertEquals(listOf(unsent), fakeDraftStore.drafts.value)

            fakeSubmission.nextPromptResult = null
            vm.sendMessage("Legitimate C")
            advanceUntilIdle()
            assertTrue(vm.showsThinking())
            emitEvent(WsEvent.MessageToken("C without start", sessionId))
            advanceUntilIdle()
            assertEquals(
                "C without start",
                vm.streamingState.value.streamingMessage
                    ?.content,
            )
            emitEvent(WsEvent.MessageSegmentEnd(sessionId))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())
        }

    @Test
    fun thinkingIndicatorUnsentRollbackDoesNotClobberNewerObservedReplyWithoutStart() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageDone(sessionId))
            advanceUntilIdle()
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fakeSubmission.beforePrompt = {
                waiting.complete(Unit)
                release.await()
            }
            fakeSubmission.nextPromptResult = SubmissionResult.NotSent
            vm.sendMessage("B pending local result")
            runCurrent()
            assertTrue(waiting.isCompleted)
            emitEvent(WsEvent.MessageToken("New observed reply", sessionId))
            emitEvent(WsEvent.MessageSegmentEnd(sessionId))
            runCurrent()
            assertTrue(vm.showsThinking())
            release.complete(Unit)
            advanceUntilIdle()
            assertTrue("An older NotSent result cannot clear newer observed work", vm.uiState.value.isAgentTyping)
            assertTrue(vm.showsThinking())
            assertFalse(vm.streamingState.value.turnEnded)
            assertEquals(
                "B pending local result",
                fakeDraftStore.drafts.value
                    .single()
                    .text,
            )
            assertFalse(
                vm.uiState.value.messages.any {
                    it.role == MessageRole.USER &&
                        it.content == "B pending local result"
                },
            )
        }

    @Test
    fun thinkingIndicatorUnsentRollbackDoesNotClobberANewerSessionGeneration() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.MessageDone(sessionId))
            advanceUntilIdle()
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fakeSubmission.beforePrompt = {
                waiting.complete(Unit)
                release.await()
            }
            fakeSubmission.nextPromptResult = SubmissionResult.NotSent
            vm.sendMessage("Old generation B")
            runCurrent()
            assertTrue(waiting.isCompleted)
            stubEmptySessionRests("other-session")
            val requests = captureRecoveryResumes()
            vm.switchSession("other-session")
            runCurrent()
            emitEvent(
                WsEvent.RpcResult(
                    requests.last(),
                    mapOf("session_id" to "other-session", "running" to true, "status" to "streaming"),
                ),
            )
            runCurrent()
            assertTrue(vm.showsThinking())
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals("other-session", vm.uiState.value.currentSessionId)
            assertTrue(vm.uiState.value.isAgentTyping)
            assertTrue(vm.showsThinking())
            assertFalse(vm.streamingState.value.turnEnded)
        }

    @Test
    fun thinkingIndicatorKeepsAnEstablishedTurnThroughAGatewayError() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("続けて")
            emitEvent(WsEvent.MessageStart(sessionId))
            emitEvent(WsEvent.ReasoningDelta("plan", sessionId))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())

            emitEvent(WsEvent.GatewayError("unrelated failure"))
            emitEvent(WsEvent.GatewayError("tool failure", sessionId))
            advanceUntilIdle()
            assertTrue("An admitted turn is not ended by an error event", vm.showsThinking())
            assertTrue(vm.uiState.value.isAgentTyping)
        }

    @Test
    fun thinkingIndicatorHidesWhileTheResponseStatusIsUnknownAndOnSessionSwitch() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            vm.sendMessage("続けて")
            emitEvent(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(vm.showsThinking())
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            advanceUntilIdle()
            assertTrue(vm.uiState.value.responseStatusUnknown)
            assertFalse("Reconnecting alone does not prove the turn still runs", vm.showsThinking())
            emitEvent(WsEvent.ReasoningDelta("still running", sessionId))
            advanceUntilIdle()
            assertTrue("An observed frame of the unfinished turn is authoritative", vm.showsThinking())

            vm.switchSession("another")
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
            emitEvent(WsEvent.ToolComplete("terminal", null, sessionId))
            advanceUntilIdle()
            assertFalse(vm.showsThinking())
        }

    @Test fun freezeRace_ordinaryDelayedAck() = runTest { freezeRace(false, true) }

    @Test fun freezeRace_codexPreviewDelayedAck() = runTest { freezeRace(true, true) }

    @Test fun freezeRace_ordinaryAdmittedAck() = runTest { freezeRace(false, false) }

    @Test fun freezeRace_codexPreviewAdmittedAck() = runTest { freezeRace(true, false) }

    /** TEST ONLY. Every product state change uses real public VM entry points/scoped parser events. */
    private suspend fun TestScope.freezeRace(
        previewed: Boolean,
        delayedAck: Boolean,
    ) {
        val label = (if (previewed) "preview" else "ordinary") + (if (delayedAck) "-delayed" else "-admitted")
        val failures = mutableListOf<String>()

        fun expect(
            name: String,
            valid: Boolean,
        ) {
            println("CHECK $label $name=${if (valid) "GREEN" else "RED"}")
            if (!valid) failures += name
        }
        val finalText = "**テスト専用の回答です。**\n" + "これは競合を再現するための架空の文章であり、実際の会話本文ではありません。".repeat(5)
        val f = finalText.take(195).padEnd(195, '。') + "。"
        assertEquals(196, f.length)
        val prefix = f.take(23)
        val bufferedPrefix = f.take(41)
        val (vm, session) = createViewModelWithSession()
        val api = ApiClient.hermesApi
        stubEmptySessionRests(session)
        coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    emptyList(),
                    pagination =
                        com.m57.hermescontrol.data.model
                            .PaginationInfo(order = "latest", offset = 0),
                ),
            )
        val resumes = captureRecoveryResumes()
        vm.switchSession(session)
        runCurrent()
        recoveryAck(resumes.last(), false, session)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isAgentTyping)

        suspend fun wire(
            type: String,
            payload: String,
        ) {
            val frame =
                """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$session","payload":$payload}}"""
            val rpc =
                kotlinx.serialization.json.Json.decodeFromString(
                    com.m57.hermescontrol.data.ws.JsonRpcResponse
                        .serializer(),
                    frame,
                )
            val event =
                com.m57.hermescontrol.data.ws.EventParser
                    .parse(rpc, frame)
            assertFalse("Parser must produce concrete event: $type", event is WsEvent.Unknown)
            emitEvent(event)
            runCurrent()
        }
        // Independent equal-prose response, also naturally establishes requiresCorrelation.
        fakeSubmission.addressedPromptAcks = true
        vm.sendMessage("previous independent question")
        advanceUntilIdle()
        wire("message.start", "{}")
        completeAddressed(vm, session, f)
        advanceUntilIdle()
        val first =
            vm.uiState.value.responseLedger.responses
                .single()
        val firstId =
            vm.uiState.value.messages
                .single { it.role == MessageRole.ASSISTANT }
                .id
        assertEquals(listOf(1010L), first.userRowIds)
        assertTrue(vm.streamingState.value.turnEnded)

        val requestEntered = CompletableDeferred<Unit>()
        val page = CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
        coEvery { api.getSessionMessages(session, any(), 0, "latest", any()) } coAnswers {
            requestEntered.complete(Unit)
            page.await()
        }
        vm.syncCurrentSession()
        runCurrent()
        assertTrue("Public sync must be admitted during real idle", requestEntered.isCompleted)
        assertFalse(vm.uiState.value.isAgentTyping)
        val ackEntered = CompletableDeferred<Unit>()
        val ack = CompletableDeferred<Unit>()
        if (delayedAck) {
            fakeSubmission.beforePrompt = {
                ackEntered.complete(Unit)
                ack.await()
            }
        }
        vm.sendMessage("current question")
        runCurrent()
        if (delayedAck) assertTrue(ackEntered.isCompleted)
        val responseId = vm.uiState.value.responseLedger.activeId!!
        assertNotEquals(first.id, responseId)
        assertTrue(
            "Correlation fence must arise from real earlier completion/new send",
            vm.uiState.value.responseLedger.requiresCorrelation,
        )
        val opening =
            vm.uiState.value.responseLedger.responses
                .single { it.id == responseId }
        assertEquals(if (delayedAck) emptyList<Long>() else listOf(1020L), opening.userRowIds)
        wire("message.start", "{}")
        // Only fixture timing seams: disable JVM always-flush; open first flush, then hold tail pending.
        ChatViewModel::class.java
            .getDeclaredField("isTestEnv")
            .apply { isAccessible = true }
            .set(vm, false)
        val controller =
            ChatViewModel::class.java
                .getDeclaredField("streamingController")
                .apply {
                    isAccessible = true
                }.get(vm) as ChatStreamingController
        val flushClock =
            ChatStreamingController::class.java
                .getDeclaredField(
                    "lastFlushMs",
                ).apply { isAccessible = true }
        flushClock.setLong(controller, 0L)
        wire("message.delta", """{"text":${JsonPrimitive(prefix)}}""")
        assertEquals(
            prefix,
            vm.streamingState.value.streamingMessage
                ?.content,
        )
        val liveId =
            vm.streamingState.value.streamingMessage!!
                .id
        flushClock.setLong(controller, Long.MAX_VALUE)
        wire("message.delta", """{"text":${JsonPrimitive(bufferedPrefix.removePrefix(prefix))}}""")
        assertEquals(
            prefix,
            vm.streamingState.value.streamingMessage
                ?.content,
        )
        val observedBuffer =
            ChatStreamingController::class.java
                .getDeclaredField("streamingBuffer")
                .apply {
                    isAccessible =
                        true
                }.get(controller)
                .toString()
        assertEquals("Actual controller must retain the unflushed tail, not ignore it", bufferedPrefix, observedBuffer)
        val canonicalId = canonicalMessageId(fakeSubmission.scope.draftTargetKey(), session, 1021)
        assertNotEquals(liveId, canonicalId)

        fun prose(): List<ChatMessage> =
            com.m57.hermescontrol.ui.chat.fullbleed
                .groupIntoTurnsWithStreaming(
                    vm.uiState.value.messages,
                    vm.streamingState.value.streamingMessage,
                    vm.uiState.value.responseLedger,
                ).filterIsInstance<com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn.Agent>()
                .flatMap { it.entries }
                .filterIsInstance<com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry.Prose>()
                .map { it.message }

        fun snapshot(stage: String) {
            val state = vm.uiState.value
            val stream = vm.streamingState.value
            val row = state.responseLedger.responses.single { it.id == responseId }
            println(
                "SNAPSHOT $label $stage " +
                    kotlinx.serialization.json.buildJsonObject {
                        put("responseId", responseId)
                        put("firstId", firstId)
                        put("liveId", liveId)
                        put("canonicalId", canonicalId)
                        put("typing", state.isAgentTyping)
                        put("turnEnded", stream.turnEnded)
                        put("live", stream.streamingMessage?.id.orEmpty())
                        put(
                            "liveLength",
                            stream.streamingMessage?.content?.length ?: 0,
                        )
                        put(
                            "historyIds",
                            state.messages
                                .filter { it.role == MessageRole.ASSISTANT }
                                .map { it.id }
                                .joinToString("|"),
                        )
                        put("renderIds", prose().map { it.id }.joinToString("|"))
                        put("renderLengths", prose().map { it.content.length }.joinToString("|"))
                        put("alias1021", state.responseLedger.aliases["1021"].orEmpty())
                        put("userRows", row.userRowIds.joinToString("|"))
                        put("terminals", row.authority.terminals.size)
                        put("finishedAt", row.finishedAt?.toString().orEmpty())
                        put("finalRow", row.canonicalFinalAssistantRowId?.toString().orEmpty())
                        put("footers", vm.footerPayloads().map { it.length }.joinToString("|"))
                    },
            )
        }
        val rest =
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    messages =
                        listOf(
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = 1010,
                                role = "user",
                                content = JsonPrimitive("previous independent question"),
                            ),
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = 1011,
                                role = "assistant",
                                content = JsonPrimitive(f),
                            ),
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = 1020,
                                role = "user",
                                content = JsonPrimitive("current question"),
                            ),
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = 1021,
                                role = "assistant",
                                content = JsonPrimitive(f),
                            ),
                        ),
                    pagination =
                        com.m57.hermescontrol.data.model
                            .PaginationInfo(order = "latest", offset = 0, returned = 4),
                ),
            )
        assertTrue(page.complete(rest))
        runCurrent()
        snapshot("rest-prefix")
        assertTrue(
            vm.uiState.value.messages
                .any { it.id == canonicalId && it.content == f },
        )
        assertEquals(
            prefix,
            vm.streamingState.value.streamingMessage
                ?.content,
        )
        assertEquals(listOf(canonicalId, liveId), prose().filter { it.id in setOf(canonicalId, liveId) }.map { it.id })
        assertTrue(vm.uiState.value.isAgentTyping)
        assertFalse(vm.streamingState.value.turnEnded)
        if (previewed) {
            wire("message.interim", """{"text":${JsonPrimitive(f)},"already_streamed":true}""")
            assertEquals(liveId, vm.streamingState.value.previewedFinalCandidateId)
            assertEquals(
                f,
                vm.uiState.value.messages
                    .single { it.id == liveId }
                    .content,
            )
            assertNull(vm.streamingState.value.streamingMessage)
            snapshot("codex-sealed")
        }
        val terminal = """{"text":${JsonPrimitive(
            f,
        )},"status":"complete","response_previewed":$previewed,"persisted_turn":{"row_ids":[1020,1021],"complete":false,"user_row_id":1020,"final_assistant_row_id":1021}}"""
        fixtureClock = 2000L
        wire("message.complete", terminal)
        snapshot("complete-before-release")
        if (delayedAck) {
            assertTrue("Before ACK, addressed terminal must be deferred", vm.uiState.value.isAgentTyping)
            assertFalse(vm.streamingState.value.turnEnded)
            assertTrue(
                vm.uiState.value.responseLedger.responses
                    .single { it.id == responseId }
                    .authority.terminals
                    .isEmpty(),
            )
            if (!previewed) {
                assertEquals(
                    prefix,
                    vm.streamingState.value.streamingMessage
                        ?.content,
                )
            }
            fakeSubmission.beforePrompt = null
            fixtureClock = 3000L
            ack.complete(Unit)
        }
        advanceUntilIdle()
        snapshot("settled")

        fun checkTerminal(stage: String) {
            val state = vm.uiState.value
            val row = state.responseLedger.responses.single { it.id == responseId }
            expect("$stage.ended", vm.streamingState.value.turnEnded)
            expect("$stage.notTyping", !state.isAgentTyping)
            expect("$stage.noLive", vm.streamingState.value.streamingMessage == null)
            expect(
                "$stage.terminalAdmitted",
                row.authority.terminals.isNotEmpty() && row.finishedAt == 2000L &&
                    row.canonicalFinalAssistantRowId == 1021L &&
                    row.userRowIds == listOf(1020L),
            )
            val targetHistory = state.messages.filter { it.id in setOf(liveId, canonicalId) }
            val targetRender = prose().filter { it.id in setOf(liveId, canonicalId) }
            // A fresh authoritative page may legitimately replace local IDs with canonical IDs.
            // This is not text-based suppression: assert exact addressed membership and cardinality.
            val expectedFinalId = if (stage == "rehydrated") canonicalId else liveId
            val priorCanonicalId = canonicalMessageId(fakeSubmission.scope.draftTargetKey(), session, 1011)
            expect("$stage.exactHistoryIds", targetHistory.map { it.id } == listOf(expectedFinalId))
            expect("$stage.exactRenderIds", targetRender.map { it.id } == listOf(expectedFinalId))
            expect("$stage.fullBody", targetHistory.isNotEmpty() && targetHistory.all { it.content == f })
            expect(
                "$stage.aliasResolves",
                state.responseLedger.aliases["1021"] == expectedFinalId &&
                    targetHistory.count { it.id == expectedFinalId } == 1,
            )
            expect(
                "$stage.independentEqualProse",
                state.messages.singleOrNull { it.id == priorCanonicalId }?.content == f &&
                    prose().count { it.id == priorCanonicalId } == 1 &&
                    state.responseLedger.responses
                        .single { it.id == first.id }
                        .finishedAt == first.finishedAt,
            )
            expect("$stage.noDoubledFooter", vm.footerPayloads().none { it == "$f\n\n$f" })
        }
        checkTerminal("settled")
        val afterTerminalIds = prose().map { it.id }
        fixtureClock = 4000L
        wire("message.delta", """{"text":"LATE_SENTINEL"}""")
        wire("message.delta", "{\"text\":null}")
        wire("message.complete", terminal)
        advanceUntilIdle()
        snapshot("late-replay")
        expect("late-replay.identitiesStable", prose().map { it.id } == afterTerminalIds)
        checkTerminal("late-replay")
        coEvery { api.getSessionMessages(session, any(), 0, "latest", any()) } returns rest
        vm.syncCurrentSession()
        advanceUntilIdle()
        snapshot("rehydrated")
        checkTerminal("rehydrated")
        androidx.lifecycle.ViewModelStore().apply {
            put("race", vm)
            clear()
        }
        assertTrue("$label contract violations: ${failures.joinToString()}", failures.isEmpty())
    }

    @Test fun liveness_terminalBeforeUnknown_marksUncertainWithoutGrantingCompletion() =
        runTest {
            terminalBeforeUnknown(true)
        }

    @Test fun liveness_activeTokensWithoutTerminal_staysActiveOnUnknown() =
        runTest {
            terminalBeforeUnknown(false)
        }

    /** TEST ONLY: real submit suspension/result + scoped wire events; reflection is read-only. */
    private suspend fun TestScope.terminalBeforeUnknown(withTerminal: Boolean) {
        val label = if (withTerminal) "terminal-before-unknown" else "active-no-terminal"

        fun field(
            owner: Any,
            name: String,
        ): Any? =
            owner.javaClass
                .getDeclaredField(name)
                .apply {
                    isAccessible = true
                }.get(owner)
        val (vm, session) = createViewModelWithSession()
        stubEmptySessionRests(session)
        val resumes = captureRecoveryResumes()
        vm.switchSession(session)
        runCurrent()
        recoveryAck(resumes.last(), false, session)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isAgentTyping)

        suspend fun wire(
            type: String,
            payload: String,
        ) {
            val frame =
                """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$session","payload":$payload}}"""
            val rpc =
                kotlinx.serialization.json.Json.decodeFromString(
                    com.m57.hermescontrol.data.ws.JsonRpcResponse
                        .serializer(),
                    frame,
                )
            val event =
                com.m57.hermescontrol.data.ws.EventParser
                    .parse(rpc, frame)
            assertFalse("Concrete parsed event required: $type", event is WsEvent.Unknown)
            emitEvent(event)
            runCurrent()
        }
        // Establish the correlation fence through a real prior accepted/completed turn.
        fakeSubmission.addressedPromptAcks = true
        vm.sendMessage("liveness prior independent question")
        advanceUntilIdle()
        wire("message.start", "{}")
        completeAddressed(vm, session, "prior independent answer")
        advanceUntilIdle()
        val prior =
            vm.uiState.value.responseLedger.responses
                .single()
        assertEquals(listOf(1010L), prior.userRowIds)
        assertTrue(vm.streamingState.value.turnEnded)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        fakeSubmission.beforePrompt = {
            attempts++
            entered.complete(Unit)
            release.await()
        }
        val question = "liveness target question"
        vm.sendMessage(question)
        runCurrent()
        assertTrue(entered.isCompleted)
        val targetId = vm.uiState.value.responseLedger.activeId!!
        val generation = field(vm, "sessionGeneration")
        val scope = field(vm, "sessionScope")
        val submittedVersion = (field(vm, "streamObservationVersion") as java.util.concurrent.atomic.AtomicLong).get()
        assertTrue(vm.uiState.value.responseLedger.requiresCorrelation)
        assertNotEquals(prior.id, targetId)
        assertTrue(targetId in vm.uiState.value.responseLedger.pendingSubmissions.values)
        val prefix = "テスト専用：途中まで届いた回答。"
        wire("message.start", "{}")
        wire("message.delta", """{"text":${JsonPrimitive(prefix.take(8))}}""")
        wire("message.delta", """{"text":${JsonPrimitive(prefix.drop(8))}}""")
        assertEquals(
            prefix,
            vm.streamingState.value.streamingMessage
                ?.content,
        )
        val liveId =
            vm.streamingState.value.streamingMessage!!
                .id
        val afterTokensVersion = (field(vm, "streamObservationVersion") as java.util.concurrent.atomic.AtomicLong).get()
        assertTrue(
            "Actual start/deltas must invalidate the captured observation version",
            afterTokensVersion > submittedVersion,
        )

        fun pending(): List<*> = field(vm, "pendingTerminals") as List<*>

        fun snapshot(stage: String) {
            val state = vm.uiState.value
            val stream = vm.streamingState.value
            val row = state.responseLedger.responses.single { it.id == targetId }
            println(
                "LIVENESS $label $stage " +
                    buildJsonObject {
                        put("session", session)
                        put("generation", field(vm, "sessionGeneration").toString())
                        put("scope", field(vm, "sessionScope").toString())
                        put("targetId", targetId)
                        put("activeId", state.responseLedger.activeId.orEmpty())
                        put("submittedVersion", submittedVersion)
                        put(
                            "observationVersion",
                            (field(vm, "streamObservationVersion") as java.util.concurrent.atomic.AtomicLong).get(),
                        )
                        put("typing", state.isAgentTyping)
                        put("unknown", state.responseStatusUnknown)
                        put("turnEnded", stream.turnEnded)
                        put("liveId", stream.streamingMessage?.id.orEmpty())
                        put("liveText", stream.streamingMessage?.content.orEmpty())
                        put("pendingTerminalCount", pending().size)
                        put("pendingTerminals", pending().joinToString())
                        put("pendingSubmissions", state.responseLedger.pendingSubmissions.toString())
                        put("userRows", row.userRowIds.joinToString())
                        put("terminalAuthorityCount", row.authority.terminals.size)
                        put("opening", row.authority.opening.toString())
                        put("finishedAt", row.finishedAt.toString())
                        put("queueExecutionIdle", field(vm, "queueExecutionIdle").toString())
                        put("draftStates", fakeDraftStore.drafts.value.joinToString { "${it.id}:${it.state}" })
                        put("promptAttempts", attempts)
                    },
            )
        }
        snapshot("tokens-before-terminal")
        val terminal =
            """{"text":"テスト専用：サーバー側では完了した回答全文。","status":"complete","persisted_turn":{"row_ids":[1020,1021],"complete":false,"user_row_id":1020,"final_assistant_row_id":1021}}"""
        if (withTerminal) {
            fixtureClock = 2000L
            wire("message.complete", terminal)
            assertEquals("Actual addressed terminal must be held pending admission", 1, pending().size)
            val held = pending().single()!!
            assertEquals(setOf(targetId), field(held, "responseIds"))
            assertEquals(generation, field(held, "generation"))
            assertEquals(scope, field(held, "scope"))
            val event = field(held, "event") as WsEvent.MessageComplete
            assertEquals(session, event.sessionId)
            assertEquals(1020L, event.persistedTurn?.userRowId)
            assertEquals(1021L, event.persistedTurn?.finalAssistantRowId)
            assertEquals(2000L, field(held, "observedAt"))
        } else {
            assertTrue(pending().isEmpty())
        }
        snapshot("before-unknown-release")
        assertFalse(vm.uiState.value.responseStatusUnknown)
        assertEquals(
            afterTokensVersion,
            (field(vm, "streamObservationVersion") as java.util.concurrent.atomic.AtomicLong).get(),
        )
        fakeSubmission.nextPromptResult = SubmissionResult.Unknown
        fixtureClock = 3000L
        release.complete(Unit)
        advanceUntilIdle()
        snapshot("settled-unknown")
        // No further external observation: exhaust a long virtual horizon and drain the scheduler again.
        advanceTimeBy(180_000L)
        advanceUntilIdle()
        snapshot("after-180s-idle")
        val state = vm.uiState.value
        val row = state.responseLedger.responses.single { it.id == targetId }
        assertEquals(generation, field(vm, "sessionGeneration"))
        assertEquals(scope, field(vm, "sessionScope"))
        assertEquals(targetId, state.responseLedger.activeId)
        assertEquals(if (withTerminal) 1 else 0, pending().size)
        assertTrue(row.authority.terminals.isEmpty())
        assertNull("Unknown is not completion authority", row.finishedAt)
        assertNull("Unknown cannot invent an accepted opening", row.authority.opening)
        assertTrue("No admission row may be inferred from pending evidence", row.userRowIds.isEmpty())
        assertTrue("Pending admission must be retained", targetId in state.responseLedger.pendingSubmissions.values)
        assertFalse("Do not fake completion", vm.streamingState.value.turnEnded)
        assertEquals(
            liveId,
            vm.streamingState.value.streamingMessage
                ?.id,
        )
        assertEquals(
            "Do not erase or replace the observed partial",
            prefix,
            vm.streamingState.value.streamingMessage
                ?.content,
        )
        assertTrue(
            "Unknown draft must remain recoverable",
            fakeDraftStore.drafts.value
                .single()
                .state == DraftState.UNKNOWN,
        )
        assertEquals("Do not retry or resend", 1, attempts)
        verify(exactly = 1) { HermesWsClient.sendMessage(session, question, any(), any()) }
        assertEquals(
            prior.finishedAt,
            state.responseLedger.responses
                .single { it.id == prior.id }
                .finishedAt,
        )
        val uncertain = state.responseStatusUnknown
        val typing = state.isAgentTyping
        println(
            "LIVENESS_CONTRACT $label unknown=$uncertain typing=$typing pending=${pending().size} attempts=$attempts",
        )
        androidx.lifecycle.ViewModelStore().apply {
            put("liveness", vm)
            clear()
        }
        if (withTerminal) {
            // Minimal liveness: pending evidence for THIS response/lifetime + Unknown must be visible
            // as uncertainty. This does NOT require terminal admission, queue discharge or completion.
            assertTrue(
                "Pending addressed terminal + settled Unknown must surface responseStatusUnknown; stale observation guard must not silently leave an apparently ongoing reply",
                uncertain,
            )
        } else {
            assertFalse(
                "Unknown for submit cannot demote a genuinely active observed stream without terminal evidence",
                uncertain,
            )
            assertTrue("Active stream control must remain ongoing", typing)
        }
    }

    @Test fun ordinaryRestBeforeCompleteRetainsIndependentCommentaryAndReopens() =
        runTest {
            previewedFinalWithSuspendedIdleRest(true, equalProse = true, reopen = true, ordinary = true)
        }

    @Test fun ordinaryCompleteBeforeRestHasOneFinal() =
        runTest {
            previewedFinalWithSuspendedIdleRest(false, ordinary = true)
        }

    @Test fun ordinaryUnsafeTerminalCannotFoldCanonical() =
        runTest {
            previewedFinalWithSuspendedIdleRest(true, proofVariant = "unsafe", ordinary = true)
        }

    @Test fun ordinaryTransformedTerminalCannotFoldCanonical() =
        runTest {
            previewedFinalWithSuspendedIdleRest(true, proofVariant = "transformed", ordinary = true)
        }

    @Test fun ordinaryErrorTerminalCannotFoldCanonical() =
        runTest {
            previewedFinalWithSuspendedIdleRest(true, proofVariant = "status", ordinary = true)
        }

    @Test fun ordinaryMissingFinalInReceiptCannotFoldCanonical() =
        runTest {
            previewedFinalWithSuspendedIdleRest(true, proofVariant = "missing-final", ordinary = true)
        }

    @Test fun ordinaryRawMediaCannotImportCanonicalGatewayCards() =
        runTest {
            previewedFinalWithSuspendedIdleRest(true, collisionVariant = "media", ordinary = true)
        }

    @Test fun ordinaryProjectedReasoningCannotTransferAuthority() =
        runTest {
            previewedFinalWithSuspendedIdleRest(true, collisionVariant = "projected-trace", ordinary = true)
        }

    @Test fun ordinaryProjectedEmptyCannotTransferAuthority() =
        runTest {
            previewedFinalWithSuspendedIdleRest(true, collisionVariant = "projected-empty", ordinary = true)
        }

    @Test fun ordinaryProjectedMediaPreservesSourceAndNormalizesCards() =
        runTest {
            previewedFinalWithSuspendedIdleRest(true, collisionVariant = "projected-media", ordinary = true)
        }

    private data class CompletionPendingFixture(
        val vm: ChatViewModel,
        val session: String,
        val responseId: String,
        val liveId: String,
        val prefix: String,
        val release: CompletableDeferred<Unit>,
        val resumes: MutableList<String>,
    )

    private fun completionField(
        owner: Any,
        name: String,
    ): Any? =
        owner.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner)

    private suspend fun TestScope.completionWire(
        session: String,
        type: String,
        payload: String,
    ) {
        val frame =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$session","payload":$payload}}"""
        val rpc =
            kotlinx.serialization.json.Json.decodeFromString(
                com.m57.hermescontrol.data.ws.JsonRpcResponse
                    .serializer(),
                frame,
            )
        val event =
            com.m57.hermescontrol.data.ws.EventParser
                .parse(rpc, frame)
        assertFalse(event is WsEvent.Unknown)
        emitEvent(event)
        runCurrent()
    }

    /** Only public VM calls and real scoped/parser events establish the pending receipt. */
    private suspend fun TestScope.completionPendingFixture(): CompletionPendingFixture {
        val (vm, session) = createViewModelWithSession()
        stubEmptySessionRests(session)
        coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    emptyList(),
                    pagination =
                        com.m57.hermescontrol.data.model
                            .PaginationInfo(order = "latest", offset = 0),
                ),
            )
        val resumes = captureRecoveryResumes()
        vm.switchSession(session)
        runCurrent()
        recoveryAck(resumes.last(), false, session)
        advanceUntilIdle()
        fakeSubmission.addressedPromptAcks = true
        vm.sendMessage("earlier independent question")
        advanceUntilIdle()
        completionWire(session, "message.start", "{}")
        completeAddressed(vm, session, "earlier independent answer")
        advanceUntilIdle()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fakeSubmission.beforePrompt = {
            entered.complete(Unit)
            release.await()
        }
        vm.sendMessage("unconfirmed target question")
        runCurrent()
        assertTrue(entered.isCompleted)
        val responseId = vm.uiState.value.responseLedger.activeId!!
        completionWire(session, "message.start", "{}")
        val prefix = "Observed prefix; do not erase."
        completionWire(session, "message.delta", """{"text":${JsonPrimitive(prefix)}}""")
        val liveId =
            vm.streamingState.value.streamingMessage!!
                .id
        fixtureClock = 2000L
        completionWire(
            session,
            "message.complete",
            """{"text":"Stored full final body.","status":"complete","persisted_turn":{"row_ids":[1020,1021],"complete":false,"user_row_id":1020,"final_assistant_row_id":1021}}""",
        )
        val pending = completionField(vm, "pendingTerminals") as List<*>
        assertEquals(1, pending.size)
        assertEquals(setOf(responseId), completionField(pending.single()!!, "responseIds"))
        assertFalse(vm.uiState.value.responseStatusUnknown)
        return CompletionPendingFixture(vm, session, responseId, liveId, prefix, release, resumes)
    }

    private suspend fun TestScope.settleCompletionUnknown(f: CompletionPendingFixture) {
        fakeSubmission.nextPromptResult = SubmissionResult.Unknown
        f.release.complete(Unit)
        advanceUntilIdle()
    }

    private fun assertCompletionUnproven(
        f: CompletionPendingFixture,
        pending: Boolean = true,
    ) {
        val state = f.vm.uiState.value
        val record = state.responseLedger.responses.single { it.id == f.responseId }
        assertNull(record.authority.opening)
        assertTrue(record.authority.terminals.isEmpty())
        assertNull(record.finishedAt)
        assertNull(state.responseLedger.aliases["1021"])
        assertTrue(f.responseId in state.responseLedger.pendingSubmissions.values)
        assertFalse(
            record
                .authorityDecision(
                    state.messages
                        .map {
                            it.id
                        }.toSet(),
                    state.responseLedger.blocksAuthority(record.id),
                ).copyReady,
        )
        if (pending) assertEquals(1, (completionField(f.vm, "pendingTerminals") as List<*>).size)
        assertTrue(fakeDraftStore.drafts.value.any { it.state == DraftState.UNKNOWN })
        assertFalse(completionField(f.vm, "queueExecutionIdle") as Boolean)
    }

    @Test fun pendingUnknownSyncRetrievesFullBodyWithoutAckOrCompletion() =
        runTest {
            val f = completionPendingFixture()
            settleCompletionUnknown(f)
            assertTrue(f.vm.uiState.value.responseStatusUnknown)
            assertFalse(f.vm.showsThinking())
            val api = ApiClient.hermesApi
            coEvery { api.getSessionMessages(f.session, any(), 0, "latest", any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        listOf(
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = 1021,
                                role = "assistant",
                                content = JsonPrimitive("Stored full final body."),
                            ),
                        ),
                        pagination =
                            com.m57.hermescontrol.data.model
                                .PaginationInfo(order = "latest", offset = 0, returned = 1),
                    ),
                )
            val before = fakeDraftStore.drafts.value.toList()
            f.vm.syncCurrentSession()
            advanceUntilIdle()
            coVerify(exactly = 2) { api.getSessionMessages(f.session, any(), 0, "latest", any()) }
            val canonical = canonicalMessageId(fakeSubmission.scope.draftTargetKey(), f.session, 1021)
            assertEquals(
                "Stored full final body.",
                f.vm.uiState.value.messages
                    .single { it.id == canonical }
                    .content,
            )
            assertEquals(
                f.liveId,
                f.vm.streamingState.value.streamingMessage
                    ?.id,
            )
            assertEquals(
                f.prefix,
                f.vm.streamingState.value.streamingMessage
                    ?.content,
            )
            assertFalse(f.vm.streamingState.value.turnEnded)
            assertTrue(f.vm.uiState.value.responseStatusUnknown)
            assertEquals(before, fakeDraftStore.drafts.value)
            assertCompletionUnproven(f)
            verify(exactly = 2) { HermesWsClient.sendMessage(f.session, any(), any(), any()) }
        }

    @Test fun pendingUnknownEmptySyncPreservesPartialAndUncertainty() = runTest { completionSyncUnavailable(false) }

    @Test fun pendingUnknownFailedSyncPreservesPartialAndUncertainty() = runTest { completionSyncUnavailable(true) }

    private suspend fun TestScope.completionSyncUnavailable(failed: Boolean) {
        val f = completionPendingFixture()
        settleCompletionUnknown(f)
        val api = ApiClient.hermesApi
        if (failed) {
            coEvery { api.getSessionMessages(f.session, any(), 0, "latest", any()) } throws
                java.io.IOException("synthetic read failure")
        } else {
            coEvery { api.getSessionMessages(f.session, any(), 0, "latest", any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .SessionMessagesResponse(emptyList()),
                )
        }
        val before = f.vm.uiState.value.messages
        f.vm.syncCurrentSession()
        advanceUntilIdle()
        assertEquals(before, f.vm.uiState.value.messages)
        assertEquals(
            f.liveId,
            f.vm.streamingState.value.streamingMessage
                ?.id,
        )
        assertEquals(
            f.prefix,
            f.vm.streamingState.value.streamingMessage
                ?.content,
        )
        assertTrue(f.vm.uiState.value.responseStatusUnknown)
        assertFalse(f.vm.streamingState.value.turnEnded)
        assertCompletionUnproven(f)
    }

    @Test fun pendingUnknownInFlightSyncAndLatePageCannotAffectNewSession() =
        runTest {
            val f = completionPendingFixture()
            settleCompletionUnknown(f)
            val entered = CompletableDeferred<Unit>()
            val page =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            val api = ApiClient.hermesApi
            coEvery { api.getSessionMessages(f.session, any(), 0, "latest", any()) } coAnswers
                {
                    entered.complete(Unit)
                    page.await()
                }
            f.vm.syncCurrentSession()
            runCurrent()
            assertTrue(entered.isCompleted)
            f.vm.syncCurrentSession()
            runCurrent()
            coVerify(exactly = 2) { api.getSessionMessages(f.session, any(), 0, "latest", any()) }
            stubEmptySessionRests("new-session")
            f.vm.switchSession("new-session")
            runCurrent()
            completionResumeAck(f.resumes.last(), "new-session", false)
            runCurrent()
            val before = f.vm.uiState.value.messages
            page.complete(
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        listOf(
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = 1021,
                                role = "assistant",
                                content = JsonPrimitive("OLD_SESSION_PAGE"),
                            ),
                        ),
                    ),
                ),
            )
            advanceUntilIdle()
            assertEquals("new-session", f.vm.uiState.value.currentSessionId)
            assertEquals(before, f.vm.uiState.value.messages)
            assertFalse(
                f.vm.uiState.value.messages
                    .any { it.content == "OLD_SESSION_PAGE" },
            )
        }

    @Test fun oldUnknownCannotDemoteNewOrdinaryAfterSessionSwitch() =
        runTest {
            val f = completionPendingFixture()
            stubEmptySessionRests("other-session")
            f.vm.switchSession("other-session")
            runCurrent()
            completionResumeAck(f.resumes.last(), "other-session", false)
            advanceUntilIdle()
            fakeSubmission.beforePrompt = null
            f.vm.sendMessage("new ordinary question in other session")
            advanceUntilIdle()
            val current = f.vm.uiState.value.responseLedger.activeId
            assertNotNull("A real new ordinary send must have opened a response", current)
            assertNotEquals(f.responseId, current)
            completionWire("other-session", "message.start", "{}")
            completionWire("other-session", "message.delta", """{"text":"NEW_LIFETIME_PREFIX"}""")
            val stream = f.vm.streamingState.value
            settleCompletionUnknown(f)
            assertEquals(current, f.vm.uiState.value.responseLedger.activeId)
            assertEquals(stream, f.vm.streamingState.value)
            assertTrue(f.vm.uiState.value.isAgentTyping)
            assertFalse(f.vm.uiState.value.responseStatusUnknown)
        }

    @Test fun oldUnknownCannotDemoteSameResponseAfterSteerLifecycle() =
        runTest {
            val f = completionPendingFixture()
            fakeSubmission.beforePrompt = null
            // Do not supply the original pending row via this separate control.
            fakeSubmission.addressedPromptAcks = false
            f.vm.sendMessage("new explicit steering intent", steer = true)
            advanceUntilIdle()
            assertEquals(f.responseId, f.vm.uiState.value.responseLedger.activeId)
            val stream = f.vm.streamingState.value
            settleCompletionUnknown(f)
            assertEquals(stream, f.vm.streamingState.value)
            assertTrue(f.vm.uiState.value.isAgentTyping)
            assertFalse(f.vm.uiState.value.responseStatusUnknown)
            assertTrue((completionField(f.vm, "pendingTerminals") as List<*>).isNotEmpty())
        }

    @Test fun scopeGenerationRevisitUnknownCannotBorrowTerminal() = runTest { completionScopeRevisit("generation") }

    @Test fun scopeProcessRevisitUnknownCannotBorrowTerminal() = runTest { completionScopeRevisit("process") }

    @Test fun scopeServerRevisitUnknownCannotBorrowTerminal() = runTest { completionScopeRevisit("server") }

    @Test fun scopeProfileRevisitUnknownCannotBorrowTerminal() = runTest { completionScopeRevisit("profile") }

    private suspend fun TestScope.completionScopeRevisit(change: String) {
        val f = completionPendingFixture()
        val original = fakeSubmission.scope
        val other =
            when (change) {
                "generation" -> original.copy(generation = original.generation + 1)
                "process" -> original.copy(processId = "other-process")
                "server" -> original.copy(serverIdentity = "other-server")
                else -> original.copy(connectionProfileId = "other-profile", serverProfileId = "other-server-profile")
            }
        fakeSubmission.scope = other
        mockScopedEvents.emit(ScopedWsEvent(other, ++eventOrdinal, null))
        runCurrent()
        fakeSubmission.scope = original
        mockScopedEvents.emit(ScopedWsEvent(original, ++eventOrdinal, null))
        runCurrent()
        assertTrue((completionField(f.vm, "pendingTerminals") as List<*>).isEmpty())
        settleCompletionUnknown(f)
        assertFalse(f.vm.uiState.value.responseStatusUnknown)
        assertTrue(f.vm.uiState.value.isAgentTyping)
        assertEquals(
            f.liveId,
            f.vm.streamingState.value.streamingMessage
                ?.id,
        )
        assertEquals(
            f.prefix,
            f.vm.streamingState.value.streamingMessage
                ?.content,
        )
        assertCompletionUnproven(f, pending = false)
    }

    @Test fun delayedConfirmedConsumesOriginalPendingTerminalOnce() =
        runTest {
            val f = completionPendingFixture()
            fakeSubmission.nextPromptResult = null
            f.release.complete(Unit)
            advanceUntilIdle()
            val response =
                f.vm.uiState.value.responseLedger.responses
                    .single { it.id == f.responseId }
            assertNotNull(response.authority.opening)
            assertEquals(2000L, response.finishedAt)
            assertEquals(1, response.authority.terminals.size)
            assertTrue((completionField(f.vm, "pendingTerminals") as List<*>).isEmpty())
            assertTrue(f.vm.streamingState.value.turnEnded)
            assertFalse(f.vm.uiState.value.responseStatusUnknown)
            completionWire(
                f.session,
                "message.complete",
                """{"text":"Stored full final body.","status":"complete","persisted_turn":{"row_ids":[1020,1021],"complete":false,"user_row_id":1020,"final_assistant_row_id":1021}}""",
            )
            advanceUntilIdle()
            val replay =
                f.vm.uiState.value.responseLedger.responses
                    .single { it.id == f.responseId }
            assertEquals(2000L, replay.finishedAt)
            assertEquals(
                2000L,
                replay.authority.terminals
                    .first()
                    .observedAt,
            ) // Replay may append evidence, not apply completion twice.
            assertEquals(
                1,
                f.vm.uiState.value.messages
                    .count { it.content == "Stored full final body." },
            )
        }

    @Test fun pendingUnknownScopedCompleteReplayCannotInventOpening() =
        runTest {
            val f = completionPendingFixture()
            settleCompletionUnknown(f)
            completionWire(
                f.session,
                "message.complete",
                """{"text":"Stored full final body.","status":"complete","persisted_turn":{"row_ids":[1020,1021],"complete":false,"user_row_id":1020,"final_assistant_row_id":1021}}""",
            )
            advanceUntilIdle()
            assertTrue(f.vm.uiState.value.responseStatusUnknown)
            assertFalse(f.vm.streamingState.value.turnEnded)
            assertEquals(
                f.prefix,
                f.vm.streamingState.value.streamingMessage
                    ?.content,
            )
            val record =
                f.vm.uiState.value.responseLedger.responses
                    .single { it.id == f.responseId }
            assertNull(record.authority.opening)
            assertTrue(record.authority.terminals.isEmpty())
            assertNull(record.finishedAt)
            assertNull(f.vm.uiState.value.responseLedger.aliases["1021"])
        }

    @Test fun pendingUnknownFinallyThenIdleResumeRecoversExecutionNotAck() =
        runTest {
            completionResumeBoundary(false, false)
        }

    @Test fun pendingUnknownFinallyThenRunningResumeRecoversActivityNotAck() =
        runTest {
            completionResumeBoundary(true, false)
        }

    @Test fun pendingUnknownInFlightIntentRejectsIdleResume() = runTest { completionResumeBoundary(false, true) }

    private suspend fun TestScope.completionResumeBoundary(
        running: Boolean,
        inFlight: Boolean,
    ) {
        val f = completionPendingFixture()
        if (!inFlight) settleCompletionUnknown(f)
        coEvery { ApiClient.hermesApi.getSessionMessages(f.session, any(), 0, "latest", any()) } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    listOf(
                        com.m57.hermescontrol.data.model.SessionMessage(
                            id = 1021,
                            role = "assistant",
                            content = JsonPrimitive("Stored full final body."),
                        ),
                    ),
                    pagination =
                        com.m57.hermescontrol.data.model
                            .PaginationInfo(order = "latest", offset = 0, returned = 1),
                ),
            )
        f.vm.retryResumeSession()
        runCurrent()
        recoveryAck(f.resumes.last(), running, f.session)
        runCurrent()
        val record =
            f.vm.uiState.value.responseLedger.responses
                .single { it.id == f.responseId }
        assertNull(record.authority.opening)
        assertTrue(record.authority.terminals.isEmpty())
        assertNull(record.finishedAt)
        assertNull(f.vm.uiState.value.responseLedger.aliases["1021"])
        assertTrue(f.responseId in f.vm.uiState.value.responseLedger.pendingSubmissions.values)
        if (inFlight) {
            assertFalse(f.vm.streamingState.value.turnEnded)
            assertTrue(f.vm.uiState.value.isAgentTyping)
            assertEquals(
                f.prefix,
                f.vm.streamingState.value.streamingMessage
                    ?.content,
            )
            settleCompletionUnknown(f)
            assertTrue(f.vm.uiState.value.responseStatusUnknown)
        } else {
            val canonical = canonicalMessageId(fakeSubmission.scope.draftTargetKey(), f.session, 1021)
            assertEquals(
                "Stored full final body.",
                f.vm.uiState.value.messages
                    .single { it.id == canonical }
                    .content,
            )
            assertEquals(!running, f.vm.streamingState.value.turnEnded)
            assertEquals(running, f.vm.uiState.value.isAgentTyping)
            assertFalse(f.vm.uiState.value.responseStatusUnknown)
            assertTrue(
                fakeDraftStore.drafts.value
                    .single()
                    .state == DraftState.UNKNOWN,
            )
            if (!running) {
                assertNull(f.vm.streamingState.value.streamingMessage)
                assertEquals(
                    f.prefix,
                    f.vm.uiState.value.messages
                        .single { it.id == f.liveId }
                        .content,
                )
            }
        }
    }

    private suspend fun completionResumeAck(
        id: String,
        saved: String,
        running: Boolean,
    ) {
        emitEvent(
            WsEvent.RpcResult(
                id,
                mapOf(
                    "session_id" to saved,
                    "resumed" to saved,
                    "running" to running,
                    "status" to if (running) "streaming" else "idle",
                ),
            ),
        )
    }

    @Test fun ordinaryReusedTerminalCannotFoldCanonical() =
        runTest {
            previewedFinalWithSuspendedIdleRest(true, proofVariant = "reused", ordinary = true)
        }

    @Test fun oldUnknownCannotChangeSameSessionRevisitedLifetime() =
        runTest {
            val f = completionPendingFixture()
            stubEmptySessionRests("other-session")
            f.vm.switchSession("other-session")
            runCurrent()
            completionResumeAck(f.resumes.last(), "other-session", false)
            advanceUntilIdle()
            f.vm.switchSession(f.session)
            runCurrent()
            completionResumeAck(f.resumes.last(), f.session, true)
            advanceUntilIdle()
            val before = f.vm.uiState.value
            val stream = f.vm.streamingState.value
            settleCompletionUnknown(f)
            assertEquals(f.session, f.vm.uiState.value.currentSessionId)
            assertEquals(before.isAgentTyping, f.vm.uiState.value.isAgentTyping)
            assertEquals(before.responseStatusUnknown, f.vm.uiState.value.responseStatusUnknown)
            assertEquals(before.responseLedger, f.vm.uiState.value.responseLedger)
            assertEquals(stream, f.vm.streamingState.value)
        }

    @Test fun oldUnknownCannotReopenResponseCompletedBySeparateControlAck() =
        runTest {
            val f = completionPendingFixture()
            fakeSubmission.beforePrompt = null
            // The explicit control ACK provides row 1020 in this fixture. It may legitimately
            // correlate the existing terminal; the later original Unknown must not reopen it.
            f.vm.sendMessage("separate accepted control", steer = true)
            advanceUntilIdle()
            assertTrue(f.vm.streamingState.value.turnEnded)
            val before = f.vm.uiState.value
            val stream = f.vm.streamingState.value
            settleCompletionUnknown(f)
            assertEquals(before.responseLedger, f.vm.uiState.value.responseLedger)
            assertEquals(stream, f.vm.streamingState.value)
            assertFalse(f.vm.uiState.value.isAgentTyping)
            assertFalse(f.vm.uiState.value.responseStatusUnknown)
        }

    @Test fun ordinaryFinalCannotTakeAnotherResponsesRowAlias() = runTest { completionOtherOwner(false) }

    @Test fun previewFinalCannotTakeAnotherResponsesRowAlias() = runTest { completionOtherOwner(true) }

    private suspend fun TestScope.completionOtherOwner(previewed: Boolean) {
        val (vm, session) = createViewModelWithSession()
        fakeSubmission.addressedPromptAcks = true
        vm.sendMessage("first question")
        advanceUntilIdle()
        completionWire(session, "message.start", "{}")
        completeAddressed(vm, session, "First owner's final")
        advanceUntilIdle()
        val first =
            vm.uiState.value.responseLedger.responses
                .single()
        val firstAlias = vm.uiState.value.responseLedger.aliases["1011"]
        assertNotNull(firstAlias)
        vm.sendMessage("second question")
        advanceUntilIdle()
        val secondId = vm.uiState.value.responseLedger.activeId!!
        completionWire(session, "message.start", "{}")
        completionWire(session, "message.delta", """{"text":"Second owner's final"}""")
        val candidateId =
            vm.streamingState.value.streamingMessage!!
                .id
        if (previewed) {
            completionWire(
                session,
                "message.interim",
                """{"text":"Second owner's final","already_streamed":true}""",
            )
        }
        completionWire(
            session,
            "message.complete",
            """{"text":"Second owner's final","response_previewed":$previewed,"status":"complete","persisted_turn":{"row_ids":[1020,1011],"complete":false,"user_row_id":1020,"final_assistant_row_id":1011}}""",
        )
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(firstAlias, state.responseLedger.aliases["1011"])
        assertEquals("First owner's final", state.messages.single { it.id == firstAlias }.content)
        assertEquals("Second owner's final", state.messages.single { it.id == candidateId }.content)
        assertEquals(
            first.messageIds,
            state.responseLedger.responses
                .single { it.id == first.id }
                .messageIds,
        )
        assertTrue(
            candidateId in
                state.responseLedger.responses
                    .single { it.id == secondId }
                    .messageIds,
        )
        assertTrue(state.responseLedger.responses.all { it.authority.revocations.isNotEmpty() })
        assertTrue(vm.footerPayloads().isEmpty())
    }

    @Test fun ordinaryAmbiguousReceiptCannotFinalizeOrBorrowAnotherOpening() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.addressedPromptAcks = true
            vm.sendMessage("first accepted question")
            advanceUntilIdle()
            completionWire(session, "message.start", "{}")
            completeAddressed(vm, session)
            advanceUntilIdle()
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "different-request-same-row",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.STREAMING,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(userRowId = 1010),
                )
            vm.sendMessage("second ambiguous question")
            advanceUntilIdle()
            val secondId = vm.uiState.value.responseLedger.activeId!!
            completionWire(session, "message.start", "{}")
            completionWire(session, "message.delta", """{"text":"Ambiguous prefix"}""")
            val stream = vm.streamingState.value
            completionWire(
                session,
                "message.complete",
                """{"text":"Do not assign this final","status":"complete","persisted_turn":{"row_ids":[1010,1017],"complete":false,"user_row_id":1010,"final_assistant_row_id":1017}}""",
            )
            advanceUntilIdle()
            assertEquals(stream, vm.streamingState.value)
            val second =
                vm.uiState.value.responseLedger.responses
                    .single { it.id == secondId }
            assertNull(second.finishedAt)
            assertTrue(second.authority.terminals.isEmpty())
            assertTrue(second.authority.revocations.isNotEmpty())
            assertNull(vm.uiState.value.responseLedger.aliases["1017"])
            assertFalse(
                vm.uiState.value.messages
                    .any { it.content == "Do not assign this final" },
            )
        }

    // Task-only regression: real 1.0.86 VM, existing fake repositories/transport.
    // ACK silence is NOT a claim that successful history hydration leaves the spinner up.
    @Test
    fun resumeProbe_withheldAckMustHaveBoundedRecovery() =
        runTest {
            resumeProbe(confirmed = false)
        }

    @Test
    fun resumeProbe_confirmedAckDrainsPreservedInput() =
        runTest {
            resumeProbe(confirmed = true)
        }

    private fun resumeProbeField(
        vm: ChatViewModel,
        name: String,
    ): Any? =
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(vm)

    private fun TestScope.resumeProbeSnapshot(
        vm: ChatViewModel,
        phase: String,
        requests: List<String>,
    ) {
        val s = vm.uiState.value
        println(
            "RESUME_PROBE " +
                mapOf(
                    "phase" to phase,
                    "virtualMs" to testScheduler.currentTime,
                    "connection" to s.connectionStatus,
                    "transportReady" to fakeSubmission.ready,
                    "loading" to s.isLoading,
                    "overlayPredicate" to (s.isLoading && s.resumeError == null),
                    "resumeError" to s.resumeError,
                    "retrying" to s.isResumeRetrying,
                    "errorMessage" to s.errorMessage,
                    "selectedSession" to s.currentSessionId,
                    "runtimeSession" to resumeProbeField(vm, "runtimeSessionId"),
                    "activeSessionHolder" to ActiveSessionHolder.activeSessionId.value,
                    "sessionGeneration" to resumeProbeField(vm, "sessionGeneration"),
                    "hydratedGeneration" to resumeProbeField(vm, "hydratedGeneration"),
                    "resumedGeneration" to resumeProbeField(vm, "resumedGeneration"),
                    "queueExecutionIdle" to resumeProbeField(vm, "queueExecutionIdle"),
                    "resumeRequests" to requests.size,
                    "historyBodies" to s.messages.map { it.content },
                    "uiDrafts" to
                        s.outgoingDrafts.map { "${it.state}:${it.savedSessionId}:${it.runtimeSessionId}:${it.text}" },
                    "persistedDrafts" to
                        fakeDraftStore.drafts.value.map {
                            "${it.state}:${it.savedSessionId}:${it.runtimeSessionId}:${it.text}"
                        },
                    "activeDraftIds" to s.activeDraftIds.size,
                    "composerRestore" to s.composerTextToRestore,
                    "draftStorageError" to s.draftStorageError,
                    "agentTyping" to s.isAgentTyping,
                    "responseUnknown" to s.responseStatusUnknown,
                ),
        )
    }

    private suspend fun TestScope.resumeProbe(confirmed: Boolean) {
        val (vm, _) = createViewModelWithSession()
        stubSession456Rests(success = true)
        val api = ApiClient.hermesApi
        coEvery { api.getSessionMessages("session-456", any(), any(), any(), any()) } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    pagination =
                        com.m57.hermescontrol.data.model.PaginationInfo(
                            order = "latest",
                            offset = 0,
                            returned = 1,
                        ),
                    total = 1,
                    messages =
                        listOf(
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = 40,
                                role = "user",
                                content = JsonPrimitive("Preserved history"),
                                timestamp = JsonPrimitive(1),
                            ),
                        ),
                ),
            )
        val requests = captureRecoveryResumes()
        vm.switchSession("session-456")
        // uiState combines flows asynchronously; observe only after draining current tasks.
        runCurrent()
        resumeProbeSnapshot(vm, "hydrated-before-ack", requests)
        assertEquals(1, requests.size)
        assertEquals(ConnectionStatus.CONNECTED, vm.uiState.value.connectionStatus)
        assertTrue(fakeSubmission.isReady(fakeSubmission.scope))
        coVerify(exactly = 1) {
            api.getSessionMessages("session-456", any(), any(), any(), any())
        }
        assertEquals(resumeProbeField(vm, "sessionGeneration"), resumeProbeField(vm, "hydratedGeneration"))
        assertFalse("Successful REST hydration clears spinner even without resume ACK", vm.uiState.value.isLoading)
        assertTrue(
            vm.uiState.value.messages
                .any { it.content == "Preserved history" },
        )
        assertNull(resumeProbeField(vm, "runtimeSessionId"))

        val input = "Keep this exact unsent input 日本語"
        vm.sendMessage(input)
        runCurrent()
        val retained = fakeDraftStore.drafts.value.single()
        assertEquals(input, retained.text)
        assertEquals("session-456", retained.savedSessionId)
        assertEquals(fakeSubmission.scope.draftTargetKey(), retained.targetKey)
        assertNull(retained.runtimeSessionId)
        assertEquals(DraftState.QUEUED, retained.state)
        assertTrue(retained.queued)
        assertEquals(listOf(retained), vm.uiState.value.outgoingDrafts)
        assertFalse(
            vm.uiState.value.messages
                .any { it.content == input },
        )
        verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        resumeProbeSnapshot(vm, "queued-before-ack", requests)

        if (confirmed) {
            emitEvent(
                WsEvent.RpcResult(
                    requests.single(),
                    mapOf(
                        "session_id" to "runtime-456",
                        "resumed" to "session-456",
                        "running" to false,
                        "status" to "idle",
                    ),
                ),
            )
            runCurrent()
        }
        // Fixed bound, not advanceUntilIdle(): silence must not hide infinite retry jobs.
        advanceTimeBy(120_001)
        runCurrent()
        resumeProbeSnapshot(vm, if (confirmed) "confirmed-after-bound" else "silent-after-bound", requests)
        assertEquals(ConnectionStatus.CONNECTED, vm.uiState.value.connectionStatus)
        assertTrue(fakeSubmission.isReady(fakeSubmission.scope))
        assertFalse("ACK silence with hydrated history does not reproduce long loading", vm.uiState.value.isLoading)
        assertTrue(
            vm.uiState.value.messages
                .any { it.content == "Preserved history" },
        )
        assertFalse(vm.uiState.value.draftStorageError)
        if (confirmed) {
            assertEquals("runtime-456", resumeProbeField(vm, "runtimeSessionId"))
            assertEquals("runtime-456", ActiveSessionHolder.activeSessionId.value)
            assertEquals(resumeProbeField(vm, "sessionGeneration"), resumeProbeField(vm, "resumedGeneration"))
            assertNull(vm.uiState.value.resumeError)
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
            assertTrue(
                vm.uiState.value.outgoingDrafts
                    .isEmpty(),
            )
            assertTrue(
                vm.uiState.value.activeDraftIds
                    .isEmpty(),
            )
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.content == input },
            )
            verify(exactly = 1) { HermesWsClient.sendMessage("runtime-456", input, any(), true) }
        } else {
            assertNull(resumeProbeField(vm, "runtimeSessionId"))
            assertNotEquals(resumeProbeField(vm, "sessionGeneration"), resumeProbeField(vm, "resumedGeneration"))
            assertEquals(listOf(retained), fakeDraftStore.drafts.value)
            assertEquals(listOf(retained), vm.uiState.value.outgoingDrafts)
            assertTrue(
                vm.uiState.value.activeDraftIds
                    .isEmpty(),
            )
            assertNull(vm.uiState.value.composerTextToRestore)
            assertFalse(
                vm.uiState.value.messages
                    .any { it.content == input },
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            // Desired bounded recovery contract, intentionally RED on immutable86.
            assertTrue(
                "Silent session.resume must retry or expose a resume error within 120001 virtual ms; history is hydrated and input remains queued",
                requests.size > 1 || vm.uiState.value.resumeError != null,
            )
        }
    }

    @Test
    fun resumeDeadline_silenceExhaustsBudgetWithHydrationAndExactDraft() =
        runTest {
            val (vm, requests) = deadlineSelection()
            val text = "  exact 日本語\nunsent  "
            vm.sendMessage(text)
            runCurrent()
            val draft = fakeDraftStore.drafts.value.single()
            advanceTimeBy(120_001)
            runCurrent()
            assertEquals(1 + ChatViewModel.MAX_RESUME_RETRIES, requests.size)
            assertNotNull(vm.uiState.value.resumeError)
            assertFalse(vm.uiState.value.isResumeRetrying)
            assertFalse(vm.uiState.value.isLoading)
            assertEquals(resumeProbeField(vm, "sessionGeneration"), resumeProbeField(vm, "hydratedGeneration"))
            assertEquals(text, draft.text)
            assertEquals(listOf(draft), fakeDraftStore.drafts.value)
            assertEquals(listOf(draft), vm.uiState.value.outgoingDrafts)
            assertNull(resumeProbeField(vm, "runtimeSessionId"))
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    private suspend fun TestScope.deadlineSelection(): Pair<ChatViewModel, MutableList<String>> {
        val (vm, _) = createViewModelWithSession()
        stubSession456Rests(success = true)
        val requests = captureRecoveryResumes()
        vm.switchSession("session-456")
        runCurrent()
        assertEquals(1, requests.size)
        return vm to requests
    }

    private suspend fun deadlineAck(
        id: String,
        runtime: String = "runtime-456",
    ) {
        emitEvent(
            WsEvent.RpcResult(
                id,
                mapOf(
                    "session_id" to runtime,
                    "resumed" to "session-456",
                    "running" to false,
                    "status" to "idle",
                ),
            ),
        )
    }

    @Test
    fun resumeDeadline_lateExpiredAckCannotBindOrDrainBeforeOrAfterRetry() =
        runTest {
            val (vm, requests) = deadlineSelection()
            vm.sendMessage("Keep until current ACK")
            runCurrent()
            val draft = fakeDraftStore.drafts.value.single()
            val expired = requests.single()
            advanceTimeBy(15_000)
            runCurrent()
            assertTrue(vm.uiState.value.isResumeRetrying)
            assertFalse((resumeProbeField(vm, "idToMethod") as Map<*, *>).containsKey(expired))
            assertFalse((resumeProbeField(vm, "sessionRequestById") as Map<*, *>).containsKey(expired))
            deadlineAck(expired, "expired-before-retry")
            runCurrent()
            assertNull(resumeProbeField(vm, "runtimeSessionId"))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, requests.size)
            deadlineAck(expired, "expired-after-retry")
            runCurrent()
            assertNull(resumeProbeField(vm, "runtimeSessionId"))
            assertEquals(listOf(draft), fakeDraftStore.drafts.value)
            // An unmatched old ACK must not cancel the current deadline.
            advanceTimeBy(15_000)
            runCurrent()
            assertTrue(vm.uiState.value.isResumeRetrying)
            assertEquals(2, resumeProbeField(vm, "resumeRetryAttempt"))
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun resumeDeadline_currentAckCancelsAndDispatchesExactlyOnce() =
        runTest {
            val (vm, requests) = deadlineSelection()
            vm.sendMessage("Once after confirmation")
            runCurrent()
            advanceTimeBy(14_999)
            deadlineAck(requests.single())
            runCurrent()
            deadlineAck(requests.single())
            advanceTimeBy(120_001)
            runCurrent()
            assertEquals(1, requests.size)
            assertNull(vm.uiState.value.resumeError)
            assertEquals("runtime-456", resumeProbeField(vm, "runtimeSessionId"))
            assertTrue(fakeDraftStore.drafts.value.isEmpty())
            verify(exactly = 1) { HermesWsClient.sendMessage("runtime-456", "Once after confirmation", any(), true) }
        }

    @Test
    fun resumeDeadline_sameGenerationPriorAckReconnectSilenceExhaustsBudget() =
        runTest {
            val (vm, requests) = deadlineSelection()
            deadlineAck(requests.single())
            runCurrent()
            val generation = resumeProbeField(vm, "sessionGeneration")
            assertEquals(generation, resumeProbeField(vm, "resumedGeneration"))
            fakeSubmission.ready = false
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            fakeSubmission.ready = true
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            emitEvent(WsEvent.GatewayReady(null))
            runCurrent()
            assertEquals(generation, resumeProbeField(vm, "sessionGeneration"))
            assertEquals(generation, resumeProbeField(vm, "hydratedGeneration"))
            assertNotEquals(generation, resumeProbeField(vm, "resumedGeneration"))
            vm.sendMessage("Retain after previously confirmed generation")
            runCurrent()
            val draft = fakeDraftStore.drafts.value.single()
            advanceTimeBy(120_001)
            runCurrent()
            assertEquals(2 + ChatViewModel.MAX_RESUME_RETRIES, requests.size)
            assertEquals(ChatViewModel.MAX_RESUME_RETRIES, resumeProbeField(vm, "resumeRetryAttempt"))
            assertNotNull(vm.uiState.value.resumeError)
            assertEquals(listOf(draft), fakeDraftStore.drafts.value)
            assertNull(resumeProbeField(vm, "runtimeSessionId"))
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun resumeDeadline_switchInvalidatesOldTimerWithoutCancellingNewTimer() =
        runTest {
            val (vm, requests) = deadlineSelection()
            val old = requests.single()
            advanceTimeBy(10_000)
            stubEmptySessionRests("session-other")
            vm.switchSession("session-other")
            runCurrent()
            deadlineAck(old, "stale-runtime")
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(2, requests.size)
            assertFalse(vm.uiState.value.isResumeRetrying)
            assertNull(resumeProbeField(vm, "runtimeSessionId"))
            advanceTimeBy(9_999)
            runCurrent()
            assertTrue(vm.uiState.value.isResumeRetrying)
            assertEquals("session-other", vm.uiState.value.currentSessionId)
        }

    @Test
    fun resumeDeadline_disconnectInvalidatesTimerAndLateAck() =
        runTest {
            val (vm, requests) = deadlineSelection()
            fakeSubmission.ready = false
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            deadlineAck(requests.single())
            advanceTimeBy(120_001)
            runCurrent()
            assertEquals(1, requests.size)
            assertFalse(vm.uiState.value.isResumeRetrying)
            assertNull(vm.uiState.value.resumeError)
            assertNull(resumeProbeField(vm, "runtimeSessionId"))
        }

    @Test
    fun resumeDeadline_scopeChangeInvalidatesTimerWithoutDisconnectEvent() =
        runTest {
            val (vm, requests) = deadlineSelection()
            fakeSubmission.scope = fakeSubmission.scope.copy(connectionProfileId = "different-profile")
            advanceTimeBy(120_001)
            runCurrent()
            assertEquals(1, requests.size)
            assertNull(vm.uiState.value.resumeError)
            assertFalse(vm.uiState.value.isResumeRetrying)
            assertNull(resumeProbeField(vm, "runtimeSessionId"))
        }

    @Test
    fun resumeDeadline_rpcErrorCancelsOnlyMatchingDeadline() =
        runTest {
            val (vm, requests) = deadlineSelection()
            advanceTimeBy(14_000)
            emitEvent(WsEvent.RpcError(requests.single(), JsonRpcError(-32000, "Temporary resume failure")))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, requests.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, resumeProbeField(vm, "resumeRetryAttempt"))
            assertFalse(vm.uiState.value.isResumeRetrying)
            advanceTimeBy(14_999)
            runCurrent()
            assertEquals(2, resumeProbeField(vm, "resumeRetryAttempt"))
            assertTrue(vm.uiState.value.isResumeRetrying)
        }

    @Test
    fun resumeDeadline_delayedOldOnSentCannotReplaceCurrentTimer() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            stubSession456Rests(success = true)
            val callbacks = mutableListOf<(String) -> Unit>()
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                callbacks += arg<((String) -> Unit)>(2)
                "deferred-${callbacks.size}"
            }
            vm.switchSession("session-456")
            runCurrent()
            vm.retryResumeSession()
            runCurrent()
            assertEquals(2, callbacks.size)
            callbacks[1]("new-request")
            runCurrent()
            advanceTimeBy(10_000)
            callbacks[0]("old-request")
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(vm.uiState.value.isResumeRetrying)
            assertEquals(1, resumeProbeField(vm, "resumeRetryAttempt"))
            assertFalse((resumeProbeField(vm, "idToMethod") as Map<*, *>).containsKey("new-request"))
        }

    @Test
    fun resumeDeadline_consumedAckBeforeMainArmRemainsInert() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            stubSession456Rests(success = true)
            var sends = 0
            val handler =
                ChatViewModel::class.java
                    .getDeclaredMethod("handleRpcResult", String::class.java, Any::class.java)
                    .apply { isAccessible = true }
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                sends++
                val id = "consumed-before-arm-$sends"
                arg<((String) -> Unit)>(2).invoke(id)
                // The fixture IO and Main dispatchers share one scheduler/thread. Consume the
                // synchronous registration in this turn, before the queued Main arm can run.
                assertTrue((resumeProbeField(vm, "idToMethod") as Map<*, *>).containsKey(id))
                assertTrue((resumeProbeField(vm, "sessionRequestById") as Map<*, *>).containsKey(id))
                handler.invoke(
                    vm,
                    id,
                    mapOf(
                        "session_id" to "runtime-456",
                        "resumed" to "session-456",
                        "running" to false,
                    ),
                )
                assertFalse((resumeProbeField(vm, "idToMethod") as Map<*, *>).containsKey(id))
                assertFalse((resumeProbeField(vm, "sessionRequestById") as Map<*, *>).containsKey(id))
                id
            }
            vm.switchSession("session-456")
            runCurrent()
            advanceTimeBy(120_001)
            runCurrent()
            assertEquals(1, sends)
            assertNull(vm.uiState.value.resumeError)
            assertEquals("runtime-456", resumeProbeField(vm, "runtimeSessionId"))
            assertEquals(resumeProbeField(vm, "sessionGeneration"), resumeProbeField(vm, "resumedGeneration"))
        }

    @Test
    fun resumeDeadline_clearCancelsOwnedTimer() =
        runTest {
            val (vm, requests) = deadlineSelection()
            val deadline = resumeProbeField(vm, "resumeDeadlineJob") as kotlinx.coroutines.Job
            assertTrue(deadline.isActive)
            ChatViewModel::class.java
                .getDeclaredMethod("onCleared")
                .apply { isAccessible = true }
                .invoke(vm)
            assertTrue(deadline.isCancelled)
            assertNull(resumeProbeField(vm, "resumeDeadlineId"))
            advanceTimeBy(120_001)
            runCurrent()
            assertEquals(1, requests.size)
            assertNull(vm.uiState.value.resumeError)
        }

    @Test
    fun resumeDeadline_disconnectBeforeDelayedOnSentCannotArm() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            stubSession456Rests(success = true)
            var callback: ((String) -> Unit)? = null
            var sends = 0
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                sends++
                callback = arg<((String) -> Unit)>(2)
                "delayed-disconnect"
            }
            vm.switchSession("session-456")
            runCurrent()
            fakeSubmission.ready = false
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            checkNotNull(callback).invoke("delayed-disconnect")
            runCurrent()
            assertNull(resumeProbeField(vm, "resumeDeadlineId"))
            advanceTimeBy(120_001)
            runCurrent()
            assertEquals(1, sends)
            assertNull(vm.uiState.value.resumeError)
            assertNull(resumeProbeField(vm, "runtimeSessionId"))
        }

    @Test
    fun resumeDeadline_expiryPreservesProvenActiveResponseAndUnknownIntent() =
        runTest {
            val (vm, requests) = interruptedRecovery()
            recoveryAck(requests.last(), false)
            runCurrent()
            fakeSubmission.nextPromptResult = SubmissionResult.Unknown
            vm.sendMessage("Uncertain intent must never replay")
            runCurrent()
            val draft = fakeDraftStore.drafts.value.single()
            assertEquals(DraftState.UNKNOWN, draft.state)
            emitEvent(WsEvent.MessageStart("runtime-recovered"))
            emitEvent(WsEvent.MessageToken("Keep live partial", "runtime-recovered"))
            runCurrent()
            vm.retryResumeSession()
            runCurrent()
            advanceTimeBy(120_001)
            runCurrent()
            assertNotNull(vm.uiState.value.resumeError)
            assertTrue(resumeProbeField(vm, "hasObservedActiveResponse") as Boolean)
            assertTrue(vm.uiState.value.isAgentTyping)
            assertEquals(listOf(draft), fakeDraftStore.drafts.value)
            assertTrue(
                vm.streamingState.value.streamingMessage
                    ?.content
                    ?.contains("Keep live partial") == true ||
                    vm.uiState.value.messages
                        .any { it.content.contains("Keep live partial") },
            )
            verify(
                exactly = 1,
            ) { HermesWsClient.sendMessage(any(), "Uncertain intent must never replay", any(), any()) }
        }

    @Test
    fun loadingWatchdog_redoesGatewayReadyWhenProfileSwitchMissesIt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            mockSwitchFlow.emit("meow")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isLoading)
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            clearMocks(
                HermesWsClient,
                answers = false,
                recordedCalls = true,
                childMocks = false,
                verificationMarks = true,
            )

            assertTrue(viewModel.recoverStuckLoading())
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            verify(atLeast = 1) { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
        }

    @Test
    fun loadingWatchdog_dropsSpinnerWithoutRedialWhenDisconnected() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            mockSwitchFlow.emit("meow")
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            advanceUntilIdle()

            assertTrue(viewModel.recoverStuckLoading())
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.resumeError)
        }

    @Test
    fun loadingWatchdog_retriesStalledResumeOnceThenReportsIt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            advanceUntilIdle()
            @Suppress("UNCHECKED_CAST")
            val state =
                ChatViewModel::class.java
                    .getDeclaredField("_uiState")
                    .apply { isAccessible = true }
                    .get(viewModel) as MutableStateFlow<ChatUiState>
            // The stalled launch-time state: spinner up, its resume never answered.
            state.value = state.value.copy(isLoading = true, resumeError = null, isResumeRetrying = false)
            clearMocks(
                HermesWsClient,
                answers = false,
                recordedCalls = true,
                childMocks = false,
                verificationMarks = true,
            )

            assertFalse(viewModel.recoverStuckLoading())
            advanceUntilIdle()
            // The Retry re-enters the normal bounded resume cycle (deadline + backoff retries).
            verify(atLeast = 1) { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) }

            // Still stalled after the one automatic Retry: report it instead of spinning.
            state.value = state.value.copy(isLoading = true, resumeError = null, isResumeRetrying = false)
            assertTrue(viewModel.recoverStuckLoading())
            assertFalse(state.value.isLoading)
        }

    @Test
    fun busyFollowUp_displaysImmediatelyWithoutQueueAndIsolatesAAndBAnswers() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "rpc-A",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.STREAMING,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(userRowId = 10L, submissionId = "sub-A"),
                )
            vm.sendMessage("Aの重い依頼")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("Aの途中経過", false, session))
            advanceUntilIdle()

            // Send separate topic B while A is still processing.
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "rpc-B",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.QUEUED,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(userRowId = 20L, submissionId = "sub-B"),
                )
            vm.sendMessage("別件Bの質問")
            advanceUntilIdle()

            // B is immediately shown in the same chat, no wait-queue draft remains, and A is not interrupted.
            assertTrue(
                vm.uiState.value.messages
                    .any { it.role == MessageRole.USER && it.content == "別件Bの質問" },
            )
            assertTrue(
                vm.uiState.value.outgoingDrafts
                    .isEmpty(),
            )
            assertTrue(vm.uiState.value.isAgentTyping)
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "別件Bの質問", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }

            // Late interim + final answer for A arriving AFTER B must anchor under A, never under B.
            emitEvent(WsEvent.MessageInterim("Aの追加作業", false, session))
            emitEvent(
                WsEvent.MessageComplete(
                    text = "Aの最終回答",
                    sessionId = session,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            userRowId = 10L,
                            finalAssistantRowId = 11L,
                            rowIds = listOf(10L, 11L),
                            complete = true,
                            submissionId = "sub-A",
                        ),
                ),
            )
            advanceUntilIdle()

            val turnsAfterA =
                com.m57.hermescontrol.ui.chat.fullbleed
                    .groupIntoTurns(
                        vm.uiState.value.messages.filterNot {
                            it.role == MessageRole.SYSTEM &&
                                it.content == "Session created"
                        },
                        vm.uiState.value.responseLedger,
                    )
            assertEquals(
                "Unexpected turns: $turnsAfterA | ledger=${vm.uiState.value.responseLedger.responses}",
                3,
                turnsAfterA.size,
            )
            assertEquals(
                "Aの重い依頼",
                (turnsAfterA[0] as com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn.User).message.content,
            )
            val agentAProse =
                (turnsAfterA[1] as com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn.Agent)
                    .entries
                    .filterIsInstance<com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry.Prose>()
                    .map { it.message.content }
            assertEquals(listOf("Aの途中経過", "Aの追加作業", "Aの最終回答"), agentAProse)
            assertEquals(
                "別件Bの質問",
                (turnsAfterA[2] as com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn.User).message.content,
            )

            // Server picks up B at the turn boundary: B's reply anchors strictly under B.
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(
                WsEvent.MessageComplete(
                    text = "Bの回答",
                    sessionId = session,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            userRowId = 20L,
                            finalAssistantRowId = 21L,
                            rowIds = listOf(20L, 21L),
                            complete = true,
                            submissionId = "sub-B",
                        ),
                ),
            )
            advanceUntilIdle()

            val turnsAfterB =
                com.m57.hermescontrol.ui.chat.fullbleed
                    .groupIntoTurns(
                        vm.uiState.value.messages.filterNot {
                            it.role == MessageRole.SYSTEM &&
                                it.content == "Session created"
                        },
                        vm.uiState.value.responseLedger,
                    )
            assertEquals(4, turnsAfterB.size)
            val agentBProse =
                (turnsAfterB[3] as com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn.Agent)
                    .entries
                    .filterIsInstance<com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry.Prose>()
                    .map { it.message.content }
            assertEquals(listOf("Bの回答"), agentBProse)
        }

    @Test
    fun busyFollowUp_earlyCorrelatedReplyForBStaysUnderBWhileAContinues() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "rpc-A",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.STREAMING,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(userRowId = 10L, submissionId = "sub-A"),
                )
            vm.sendMessage("重い調査A")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageInterim("Aをバックグラウンド実行中", false, session))
            advanceUntilIdle()

            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "rpc-B",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.QUEUED,
                    com.m57.hermescontrol.domain.hermes
                        .PromptEvidence(userRowId = 20L, submissionId = "sub-B"),
                )
            val acceptB = CompletableDeferred<Unit>()
            fakeSubmission.beforePrompt = { acceptB.await() }
            vm.sendMessage("軽い質問B")
            runCurrent()
            assertTrue(
                vm.uiState.value.messages
                    .any { it.role == MessageRole.USER && it.content == "軽い質問B" },
            )

            // B returns early while A is still active, even before its admission ACK.
            emitEvent(
                WsEvent.MessageComplete(
                    text = "Bの即答",
                    sessionId = session,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            userRowId = 20L,
                            finalAssistantRowId = 21L,
                            rowIds = listOf(20L, 21L),
                            complete = true,
                            submissionId = "sub-B",
                        ),
                ),
            )
            runCurrent()
            assertTrue("A must remain active after B completes early", vm.uiState.value.isAgentTyping)
            acceptB.complete(Unit)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "Bの即答" },
            )

            // A completes later and stays under A.
            emitEvent(
                WsEvent.MessageComplete(
                    text = "Aの最終報告",
                    sessionId = session,
                    persistedTurn =
                        com.m57.hermescontrol.data.ws.PersistedTurn(
                            userRowId = 10L,
                            finalAssistantRowId = 12L,
                            rowIds = listOf(10L, 12L),
                            complete = true,
                            submissionId = "sub-A",
                        ),
                ),
            )
            advanceUntilIdle()

            val turns =
                com.m57.hermescontrol.ui.chat.fullbleed
                    .groupIntoTurns(
                        vm.uiState.value.messages.filterNot {
                            it.role == MessageRole.SYSTEM &&
                                it.content == "Session created"
                        },
                        vm.uiState.value.responseLedger,
                    )
            assertEquals(4, turns.size)
            assertEquals(
                listOf("Aをバックグラウンド実行中", "Aの最終報告"),
                (turns[1] as com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn.Agent)
                    .entries
                    .filterIsInstance<com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry.Prose>()
                    .map { it.message.content },
            )
            assertEquals(
                listOf("Bの即答"),
                (turns[3] as com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn.Agent)
                    .entries
                    .filterIsInstance<com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry.Prose>()
                    .map { it.message.content },
            )
        }

    @Test
    fun busyFollowUp_legacyQueueUsesSerialDisplayWithoutGrantingReplyAuthority() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("仕事A")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed("legacy-queue", com.m57.hermescontrol.domain.hermes.PromptAcceptance.QUEUED)
            vm.sendMessage("別件B")
            advanceUntilIdle()
            fakeSubmission.nextPromptResult = null

            vm.sendMessage("/queue C")
            emitEvent(WsEvent.MessageComplete("回答A", session))
            emitEvent(WsEvent.MessageDone(session))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.responseStatusUnknown)
            verify(exactly = 0) { HermesWsClient.sendMessage(session, "C", any(), any()) }

            emitEvent(WsEvent.MessageStart(session))
            emitEvent(WsEvent.MessageComplete("回答B", session))
            advanceUntilIdle()
            val state = vm.uiState.value
            val firstTwo = state.responseLedger.responses.take(2)
            assertEquals(
                listOf("回答A", "回答B"),
                firstTwo.map { response -> state.messages.single { it.id in response.messageIds }.content },
            )
            assertTrue(firstTwo.all { it.authority.terminals.isEmpty() })
            assertTrue(vm.footerPayloads().isEmpty())
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "C", any(), true) }
        }

    @Test
    fun busyFollowUp_definitelyUnsentBDoesNotFenceOffTheObservedAReply() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("仕事A")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))

            fakeSubmission.nextPromptResult = SubmissionResult.NotSent
            vm.sendMessage("別件B")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isAgentTyping)
            assertFalse(
                vm.uiState.value.messages
                    .any { it.role == MessageRole.USER && it.content == "別件B" },
            )

            emitEvent(WsEvent.MessageComplete("回答A", session))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isAgentTyping)
            assertFalse(vm.uiState.value.responseStatusUnknown)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.role == MessageRole.ASSISTANT && it.content == "回答A" },
            )
            assertTrue(vm.footerPayloads().isEmpty())
        }

    @Test
    fun busyFollowUp_explicitStopInterruptsWithoutNewSession() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("処理A")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            advanceUntilIdle()

            vm.sendMessage("止めて")
            advanceUntilIdle()

            verify(
                exactly = 1,
            ) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, mapOf("session_id" to session), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "止めて", any(), any()) }
            assertEquals(session, vm.uiState.value.currentSessionId)
        }

    @Test
    fun busyFollowUp_explicitAmendRedirectsActiveTurnInPlace() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            vm.sendMessage("処理A")
            advanceUntilIdle()
            emitEvent(WsEvent.MessageStart(session))
            advanceUntilIdle()

            fakeSubmission.nextPromptResult =
                SubmissionResult.Confirmed(
                    "rpc-amend",
                    com.m57.hermescontrol.domain.hermes.PromptAcceptance.REDIRECTED,
                )
            vm.sendMessage("今の修正: 箇条書きにして")
            advanceUntilIdle()

            verify(exactly = 1) { HermesWsClient.sendRedirect(session, "今の修正: 箇条書きにして", any()) }
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }
}
