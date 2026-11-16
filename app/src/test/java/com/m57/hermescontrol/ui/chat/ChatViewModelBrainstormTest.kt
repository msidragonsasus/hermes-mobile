package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.BrainstormSnapshot
import com.m57.hermescontrol.data.model.BrainstormSpeakerSnapshot
import com.m57.hermescontrol.data.model.BrainstormsListResponse
import com.m57.hermescontrol.data.model.PaginationInfo
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.transport.HermesAttentionTransport
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.ScopedRpcSubmission
import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.fakes.FakeOutgoingDraftStore
import com.m57.hermescontrol.ui.chat.fakes.FakeSlashUsageStore
import com.m57.hermescontrol.ui.chat.fakes.FakeSubmissionTransport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelBrainstormTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockScopedEvents = MutableSharedFlow<ScopedWsEvent>(extraBufferCapacity = 64)
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
    private val activeProfileFlow = MutableStateFlow<String?>("default")
    private lateinit var mockApi: HermesApiService
    private lateinit var app: Application
    private lateinit var fakeSubmission: FakeSubmissionTransport
    private var reqCount = 0

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
        every { AuthManager.uiPreferenceScope() } returns "test-scope"
        every { AuthManager.readUiPreference(any(), any()) } returns null
        every { AuthManager.writeUiPreference(any(), any(), any()) } returns Unit
        every { AuthManager.getPinnedModels() } returns emptyList()
        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.activeProfileId } returns activeProfileFlow
        every { AuthManager.connectionEpoch } returns MutableStateFlow(1L)
        every { AuthManager.isTypingEffectEnabled() } returns false
        every { AuthManager.getTypingEffectDelayMs() } returns 0
        every { AuthManager.isAutoReconnect() } returns false

        mockkObject(HermesWsClient)
        fakeSubmission = FakeSubmissionTransport()
        every { HermesWsClient.events } returns mockEventsFlow
        every { HermesWsClient.scopedEvents } returns mockScopedEvents
        every { HermesWsClient.connectionStatus } returns mockConnectionStatus
        every { AuthManager.wsUrl() } returns "ws://test.local/ws"
        every { HermesWsClient.connect() } returns Unit
        every { HermesWsClient.disconnect() } returns Unit
        every { HermesWsClient.disconnect(any()) } returns Unit
        every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.connectionScope } returns MutableStateFlow(fakeSubmission.scope)
        every { HermesWsClient.configuredScope() } returns fakeSubmission.scope
        every { HermesWsClient.readSubagents(any(), any()) } returns CompletableDeferred<Any?>(Unit)
        every { HermesWsClient.writeScoped(any(), any(), any(), any()) } answers {
            ScopedRpcSubmission("cmd", true, HermesWsClient.request(arg(1), arg(2), arg(3)))
        }
        every { HermesWsClient.request(any(), any(), any()) } answers {
            HermesWsClient.send(arg(0), arg(1)) {}
            CompletableDeferred<Any?>(Unit)
        }
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }

        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns MutableSharedFlow()
        every { ProfileSwitchCoordinator.connectionSwitched } returns MutableSharedFlow()

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery {
            mockApi.getSessionMessages(any(), any(), any(), any(), any())
        } returns
            Response.success(
                SessionMessagesResponse(emptyList(), pagination = PaginationInfo(order = "latest", offset = 0)),
            )

        mockkObject(HermesDatabase)
        ActiveSessionHolder.set(null)
        app = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        ActiveSessionHolder.set(null)
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): ChatViewModel {
        val attention = mockk<HermesAttentionTransport>(relaxed = true)
        coEvery { attention.acknowledgeApproval(any()) } returns SubmissionResult.Confirmed("ok", true)
        return ChatViewModel(
            application = app,
            startCleanup = false,
            repo = FakeChatPersistenceRepository(),
            slashUsageStore = FakeSlashUsageStore(),
            searchDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            draftStore = FakeOutgoingDraftStore(),
            submissionTransport = fakeSubmission,
            attentionTransport = attention,
            clock = { 1000L },
            streamingTokenClock = { 1000L },
            specialistsRepositoryOverride = SpecialistsRepository { mockApi },
        )
    }

    @Test
    fun loadSessionMessagesHydratesBrainstormsAndLiveEventsPollAndSettle() =
        runTest(testDispatcher) {
            val sessionId = "sess-main-1"
            coEvery { mockApi.getBrainstorms(profile = "default", sessionId = sessionId) } returns
                Response.success(
                    BrainstormsListResponse(
                        items =
                            listOf(
                                BrainstormSnapshot(
                                    id = "bs-hydrated",
                                    session_id = sessionId,
                                    status = "completed",
                                    speakers =
                                        listOf(
                                            BrainstormSpeakerSnapshot(
                                                profile = "scout",
                                                name = "Scout",
                                                status = "settled",
                                                reply = "Hydrated insight",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                )

            val vm = buildViewModel()
            vm.switchSession(sessionId)
            advanceUntilIdle()

            coVerify(atLeast = 1) { mockApi.getBrainstorms(profile = "default", sessionId = sessionId) }
            assertEquals(
                "completed",
                vm.uiState.value.brainstormsById["bs-hydrated"]
                    ?.status,
            )

            // Live ToolStart triggers polling until settled
            coEvery { mockApi.getBrainstorm(id = "bs-live", profile = "default", sessionId = sessionId) } returnsMany
                listOf(
                    Response.success(
                        BrainstormSnapshot(
                            id = "bs-live",
                            session_id = sessionId,
                            status = "completed",
                            speakers =
                                listOf(
                                    BrainstormSpeakerSnapshot(
                                        profile = "dev",
                                        name = "Dev",
                                        status = "settled",
                                        reply = "Live settled",
                                    ),
                                ),
                        ),
                    ),
                )

            vm.onMessageAgentToolStart(
                mapOf(
                    "brainstorm_id" to "bs-live",
                    "status" to "queued",
                    "fan_out" to
                        listOf(
                            mapOf("to" to "@dev", "status" to "queued"),
                        ),
                ),
            )
            runCurrent()
            assertEquals(
                "running",
                vm.uiState.value.brainstormsById["bs-live"]
                    ?.status,
            )

            advanceTimeBy(1_600L)
            runCurrent()
            assertEquals(
                "completed",
                vm.uiState.value.brainstormsById["bs-live"]
                    ?.status,
            )
            assertEquals(
                "Live settled",
                vm.uiState.value.brainstormsById["bs-live"]
                    ?.speakers
                    ?.single()
                    ?.reply,
            )

            // Explicit reset clears brainstorm map
            vm.onMessageAgentToolComplete(
                mapOf(
                    "type" to "hermes.brainstorm.v1",
                    "brainstorm_id" to "bs-live",
                    "status" to "completed",
                ),
            )
            advanceUntilIdle()
            assertTrue("bs-live" in vm.uiState.value.brainstormsById)
        }
}
