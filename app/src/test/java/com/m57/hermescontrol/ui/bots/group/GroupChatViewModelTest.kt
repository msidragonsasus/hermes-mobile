package com.m57.hermescontrol.ui.bots.group

import android.util.Log
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.BotRosterMeta
import com.m57.hermescontrol.data.model.GroupChatRoomLease
import com.m57.hermescontrol.data.model.GroupChatRoomMeta
import com.m57.hermescontrol.data.model.GroupChatSyncFrom
import com.m57.hermescontrol.data.model.GroupChatSyncLogEntry
import com.m57.hermescontrol.data.model.GroupChatSyncSnapshot
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.ws.GroupHistoryIssue
import com.m57.hermescontrol.data.ws.GroupHistoryRead
import com.m57.hermescontrol.data.ws.GroupMemberSession
import com.m57.hermescontrol.data.ws.GroupSessionAccess
import com.m57.hermescontrol.data.ws.GroupSessionTransport
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.OfflineSendPolicy
import com.m57.hermescontrol.data.ws.ScopedRpcSubmission
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.util.captureDiagnosticLogs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private val eventsFlow = MutableSharedFlow<WsEvent>()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var transport: FakeGroupTransport

    private val botA =
        ProfileInfo(
            name = "scout",
            ui_meta =
                mapOf(
                    "hermes-bots" to
                        json.encodeToJsonElement(
                            BotRosterMeta(
                                title = "Scout Bot",
                                groups = listOf("Dev Team"),
                                avatar = BotAvatarMeta(icon = "science"),
                            ),
                        ),
                ),
        )

    private val botB =
        ProfileInfo(
            name = "coder",
            ui_meta =
                mapOf(
                    "hermes-bots" to
                        json.encodeToJsonElement(
                            BotRosterMeta(
                                title = "Dev Bot",
                                groups = listOf("Dev Team"),
                                avatar = BotAvatarMeta(icon = "code"),
                            ),
                        ),
                ),
        )

    @Test
    fun automaticTitleSurvivesOldServerSnapshotsAndViewModelRecreation() =
        runTest(testDispatcher) {
            val first = "グループチャットの履歴を安定させる方法を相談したいです"
            val message = GroupChatMessage("user", "user", "You", true, text = first)
            val room =
                GroupConversation(id = "saved", title = first.take(60), messages = listOf(message), draft = "keep")
            val store = MemoryGroupConversationStore()
            store.write(
                "scope\u0000Dev Team",
                GroupConversationArchive(activeId = room.id, conversations = listOf(room)),
            )
            val server = mockk<GroupServerTransport>(relaxed = true)
            coEvery { server.read(any(), any()) } returns
                GroupServerSnapshot("saved", "Dev Team", title = first, messages = listOf(message))
            coEvery { server.rename(any(), any()) } throws java.io.IOException("old server")
            var calls = 0

            fun viewModel() =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = transport,
                    conversationStore = store,
                    conversationScope = { "scope" },
                    serverTransport = server,
                    titleGenerator = {
                        calls++
                        "グループ履歴の安定化"
                    },
                )
            val vm = viewModel()
            testScheduler.runCurrent()
            vm.refreshServerConversation()
            testScheduler.runCurrent()
            assertEquals(
                "グループ履歴の安定化",
                vm.uiState.value.conversations
                    .single()
                    .title,
            )
            assertEquals("keep", vm.uiState.value.draft)
            val reopened = viewModel()
            testScheduler.runCurrent()
            assertEquals(
                "グループ履歴の安定化",
                reopened.uiState.value.conversations
                    .single()
                    .title,
            )
            assertEquals(1, calls)
            io.mockk.coVerify(
                atLeast = 1,
            ) { server.rename("saved", GroupServerTitleRequest("Dev Team", "グループ履歴の安定化", first)) }
            io.mockk.coVerify(exactly = 0) { server.send(any(), any()) }
            io.mockk.coVerify(exactly = 0) { server.stop(any(), any()) }
        }

    @Test
    fun aLateTitleIsSavedToItsOriginalSessionWithoutChangingTheSelectedDraft() =
        runTest(testDispatcher) {
            val one =
                GroupConversation(
                    id = "one",
                    title = "one prompt",
                    messages = listOf(GroupChatMessage("one-user", "user", "You", true, text = "one prompt")),
                )
            val two =
                GroupConversation(
                    id = "two",
                    title = "two prompt",
                    messages = listOf(GroupChatMessage("two-user", "user", "You", true, text = "two prompt")),
                    draft = "second draft",
                )
            val store = MemoryGroupConversationStore()
            store.write(
                "scope\u0000Dev Team",
                GroupConversationArchive(activeId = one.id, conversations = listOf(one, two)),
            )
            val result = CompletableDeferred<String>()
            val vm =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = transport,
                    conversationStore = store,
                    conversationScope = { "scope" },
                    titleGenerator = {
                        if (it ==
                            "one prompt"
                        ) {
                            result.await()
                        } else {
                            "二つ目の相談"
                        }
                    },
                )
            testScheduler.runCurrent()
            vm.openConversation("Dev Team", "two", null)
            testScheduler.runCurrent()
            result.complete("最初の相談")
            testScheduler.runCurrent()
            assertEquals("two", vm.uiState.value.conversationId)
            assertEquals("second draft", vm.uiState.value.draft)
            assertEquals(
                listOf("最初の相談", "二つ目の相談"),
                vm.uiState.value.conversations
                    .map { it.title },
            )
        }

    @Test
    fun aTitleResultFromAnotherConnectionScopeIsDiscarded() =
        runTest(testDispatcher) {
            val room =
                GroupConversation(
                    id = "one",
                    title = "prompt",
                    messages = listOf(GroupChatMessage("user", "user", "You", true, text = "prompt")),
                )
            val store = MemoryGroupConversationStore()
            store.write(
                "original\u0000Dev Team",
                GroupConversationArchive(activeId = room.id, conversations = listOf(room)),
            )
            var owner = "original"
            val result = CompletableDeferred<String>()
            GroupChatViewModel(
                "Dev Team",
                testDispatcher,
                groupTransport = transport,
                conversationStore = store,
                conversationScope = { owner },
                titleGenerator = { result.await() },
            )
            testScheduler.runCurrent()
            owner = "other"
            result.complete("must not apply")
            testScheduler.runCurrent()
            assertNull(
                store
                    .read("original\u0000Dev Team")!!
                    .conversations
                    .single()
                    .generatedTitle,
            )
            assertNull(store.read("other\u0000Dev Team"))
        }

    @Test
    fun titleFailureKeepsFallbackAndDoesNotRetryOnEveryPoll() =
        runTest(testDispatcher) {
            val message = GroupChatMessage("user", "user", "You", true, text = "original prompt")
            val room = GroupConversation(id = "saved", title = message.text, messages = listOf(message))
            val store = MemoryGroupConversationStore()
            store.write(
                "scope\u0000Dev Team",
                GroupConversationArchive(activeId = room.id, conversations = listOf(room)),
            )
            val server = mockk<GroupServerTransport>(relaxed = true)
            coEvery { server.read(any(), any()) } returns
                GroupServerSnapshot("saved", "Dev Team", title = message.text, messages = listOf(message))
            var clock = 10_000L
            var calls = 0
            val vm =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = transport,
                    conversationStore = store,
                    conversationScope = { "scope" },
                    turnClock = { clock },
                    serverTransport = server,
                    titleGenerator = {
                        calls++
                        error("title unavailable")
                    },
                )
            testScheduler.runCurrent()
            repeat(3) {
                vm.refreshServerConversation()
                testScheduler.runCurrent()
            }
            assertEquals(1, calls)
            assertEquals(
                "original prompt",
                vm.uiState.value.conversations
                    .single()
                    .title,
            )
            clock += 3_600_001L
            vm.refreshServerConversation()
            testScheduler.runCurrent()
            assertEquals(2, calls)
            io.mockk.coVerify(exactly = 0) { server.send(any(), any()) }
        }

    @Test
    fun outgoingReaderCannotDetachTheNewHistoryReaderInTheSameGroup() =
        runTest(testDispatcher) {
            val server = mockk<GroupServerTransport>()
            val read = CompletableDeferred<GroupServerSnapshot>()
            coEvery { server.read(any(), any()) } answers { GroupServerSnapshot(firstArg(), secondArg()) }
            coEvery { server.read("second", "Dev Team") } coAnswers { read.await() }
            val vm =
                GroupChatViewModel(
                    ioDispatcher = testDispatcher,
                    groupTransport = transport,
                    serverTransport = server,
                )
            vm.openConversation("Dev Team", "first", null, "outgoing")
            testScheduler.runCurrent()
            vm.openConversation("Dev Team", "second", null, "incoming")
            testScheduler.runCurrent()
            vm.updateDraft("kept draft", "incoming", "second")
            vm.updateDraft("outgoing draft", "outgoing", "first")
            vm.updateDraft("previous conversation draft", "incoming", "first")
            vm.leaveGroup("Dev Team", "outgoing")
            read.complete(
                GroupServerSnapshot(
                    "second",
                    "Dev Team",
                    messages = listOf(GroupChatMessage("reply", "coder", "Coder", false, text = "incoming history")),
                ),
            )
            testScheduler.runCurrent()
            assertEquals("second", vm.uiState.value.conversationId)
            assertEquals(
                "incoming history",
                vm.uiState.value.messages
                    .single()
                    .text,
            )
            assertEquals("kept draft", vm.uiState.value.draft)
            assertFalse(vm.uiState.value.isReconciling)
            io.mockk.coVerify(exactly = 0) { server.stop(any(), any()) }
        }

    @Test
    fun remoteHistorySwitchKeepsTheRequestedConversationWhileReading() =
        runTest(testDispatcher) {
            val server = mockk<GroupServerTransport>()
            val firstRead = CompletableDeferred<GroupServerSnapshot>()
            coEvery { server.read(any(), any()) } answers {
                GroupServerSnapshot(firstArg(), secondArg())
            }
            coEvery { server.read("first", "Dev Team") } coAnswers { firstRead.await() }
            coEvery { server.read("second", "Dev Team") } returns
                GroupServerSnapshot(
                    "second",
                    "Dev Team",
                    messages = listOf(GroupChatMessage("reply", "coder", "Coder", false, text = "second history")),
                )
            val vm =
                GroupChatViewModel(
                    ioDispatcher = testDispatcher,
                    groupTransport = transport,
                    serverTransport = server,
                )
            vm.openConversation("Dev Team", "first", null)
            testScheduler.runCurrent()
            assertEquals("first", vm.uiState.value.conversationId)
            assertTrue(vm.uiState.value.isReconciling)
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            vm.openConversation("Dev Team", "second", null)
            // The loading state must be visible before the asynchronous read starts.
            assertEquals("second", vm.uiState.value.conversationId)
            assertTrue(vm.uiState.value.isReconciling)
            testScheduler.runCurrent()
            firstRead.complete(GroupServerSnapshot("first", "Dev Team"))
            testScheduler.runCurrent()
            assertEquals("second", vm.uiState.value.conversationId)
            assertEquals(
                "second history",
                vm.uiState.value.messages
                    .single()
                    .text,
            )
            assertFalse(vm.uiState.value.isReconciling)
            io.mockk.coVerify(exactly = 0) { server.send(any(), any()) }
            io.mockk.coVerify(exactly = 0) { server.stop(any(), any()) }
        }

    @Test
    fun draftBurstWritesOnceAndLeavingFlushesTheLatestDraftImmediately() =
        runTest(testDispatcher) {
            val memory = MemoryGroupConversationStore()
            var writes = 0
            val store =
                object : GroupConversationStore {
                    override fun read(key: String) = memory.read(key)

                    override fun write(
                        key: String,
                        archive: GroupConversationArchive,
                    ) {
                        writes++
                        memory.write(key, archive)
                    }
                }
            val vm =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = transport,
                    conversationStore = store,
                    conversationScope = { "scope" },
                )
            testScheduler.runCurrent()
            writes = 0
            repeat(100) { vm.updateDraft("draft $it") }
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(499)
            testScheduler.runCurrent()
            assertEquals(0, writes)
            assertEquals("draft 99", vm.uiState.value.draft)
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()
            assertEquals(1, writes)
            assertEquals(
                "draft 99",
                memory
                    .read("scope\u0000Dev Team")!!
                    .conversations
                    .last()
                    .draft,
            )
            vm.updateDraft("final draft")
            vm.leaveGroup("Dev Team")
            val flushed = writes
            assertEquals(
                "final draft",
                memory
                    .read("scope\u0000Dev Team")!!
                    .conversations
                    .last()
                    .draft,
            )
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(flushed, writes)
        }

    @Test
    fun groupSwitchAndViewModelClearFlushPendingDraftsToTheirOriginalArchive() =
        runTest(testDispatcher) {
            val store = MemoryGroupConversationStore()
            val vm =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = transport,
                    conversationStore = store,
                    conversationScope = { "scope" },
                )
            testScheduler.runCurrent()
            vm.updateDraft("original group")
            vm.setGroup("Other Team")
            testScheduler.runCurrent()
            assertEquals(
                "original group",
                store
                    .read("scope\u0000Dev Team")!!
                    .conversations
                    .last()
                    .draft,
            )
            vm.updateDraft("other group")
            androidx.lifecycle.ViewModelStore().apply {
                put("test", vm)
                clear()
            }
            assertEquals(
                "other group",
                store
                    .read("scope\u0000Other Team")!!
                    .conversations
                    .last()
                    .draft,
            )
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(
                "original group",
                store
                    .read("scope\u0000Dev Team")!!
                    .conversations
                    .last()
                    .draft,
            )
        }

    @Test
    fun directRouteOpensSelectedSession() =
        runTest(testDispatcher) {
            val store = MemoryGroupConversationStore()
            val vm =
                GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport, conversationStore = store)
            testScheduler.runCurrent()
            val first = vm.uiState.value.conversationId
            vm.updateDraft("first draft")
            vm.newConversation()
            val second = vm.uiState.value.conversationId
            vm.updateDraft("second draft")
            vm.leaveGroup("Dev Team")
            val reopened =
                GroupChatViewModel(
                    ioDispatcher = testDispatcher,
                    groupTransport = FakeGroupTransport(eventsFlow),
                    conversationStore = store,
                )
            reopened.openConversation("Dev Team", first, null)
            testScheduler.runCurrent()
            assertEquals(first, reopened.uiState.value.conversationId)
            assertEquals("first draft", reopened.uiState.value.draft)
            reopened.openConversation("Dev Team", second, null)
            assertEquals(second, reopened.uiState.value.conversationId)
            assertEquals("second draft", reopened.uiState.value.draft)
            assertTrue(transport.submissions.isEmpty())
        }

    @Test
    fun newRouteCreatesAnEmptySessionOnceAndPreservesLegacyHistory() =
        runTest(testDispatcher) {
            serveRoom(
                fourRoom().copy(
                    log = listOf(GroupChatSyncLogEntry(text = "legacy", from = GroupChatSyncFrom(kind = "user"))),
                ),
            )
            val store = MemoryGroupConversationStore()
            val vm =
                GroupChatViewModel(ioDispatcher = testDispatcher, groupTransport = transport, conversationStore = store)
            vm.openConversation("Four", null, "new-route")
            testScheduler.runCurrent()
            assertEquals("new-route", vm.uiState.value.conversationId)
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            assertEquals(2, vm.uiState.value.conversations.size)
            assertEquals(
                "legacy",
                vm.uiState.value.conversations
                    .first()
                    .messages
                    .single()
                    .text,
            )
            vm.updateDraft("new draft")
            vm.openConversation("Four", null, "new-route")
            assertEquals(2, vm.uiState.value.conversations.size)
            assertEquals("new draft", vm.uiState.value.draft)
            assertTrue(transport.submissions.isEmpty())
        }

    @Test
    fun renamedGroupPreservesSessionAndDraftOffline() =
        runTest(testDispatcher) {
            serveRoom(fourRoom())
            var saved: GroupChatSyncSnapshot? = null
            every {
                HermesWsClient.request(
                    com.m57.hermescontrol.data.ws.WsMethods.PROFILES_CONFIGURE,
                    any(),
                    any(),
                )
            } answers
                {
                    val payload = secondArg<Map<String, Any?>>()["ui_meta"] as Map<*, *>
                    saved =
                        json.decodeFromJsonElement<GroupChatSyncSnapshot>(payload["hermes-bots-groups"].toJsonElement())
                    CompletableDeferred(emptyMap<String, Any>())
                }
            val store = MemoryGroupConversationStore()
            val vm = GroupChatViewModel("Four", testDispatcher, groupTransport = transport, conversationStore = store)
            testScheduler.runCurrent()
            vm.updateDraft("keep this draft")
            val id = vm.uiState.value.conversationId
            vm.updateGroupLimits(6, 2, displayName = "  開発会議  ")
            testScheduler.runCurrent()
            assertEquals("開発会議", vm.uiState.value.displayName)
            assertEquals("Four", vm.uiState.value.groupName)
            assertEquals(id, vm.uiState.value.conversationId)
            assertEquals("keep this draft", vm.uiState.value.draft)
            assertEquals("Four", saved!!.rooms!!["name:Four"]!!.name)
            assertEquals("開発会議", saved!!.rooms!!["name:Four"]!!.displayName)
            coEvery { mockApi.getProfiles() } throws IllegalStateException("offline")
            val restored =
                GroupChatViewModel(
                    "Four",
                    testDispatcher,
                    groupTransport = FakeGroupTransport(eventsFlow),
                    conversationStore = store,
                )
            testScheduler.runCurrent()
            assertEquals("開発会議", restored.uiState.value.displayName)
            assertEquals(id, restored.uiState.value.conversationId)
            assertEquals("keep this draft", restored.uiState.value.draft)
            assertTrue(transport.submissions.isEmpty())
        }

    @Test
    fun testAcceptedSendPendingAckConnectionChange() =
        runTest(testDispatcher) {
            val ack = CompletableDeferred<Any?>()
            transport.submitResponse = ack
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@all synthetic unresolved ACK")
            testScheduler.runCurrent()
            assertEquals(listOf("scout"), transport.submissions.map { it.first })
            transport.connectionScope.value = transport.connectionScope.value!!.copy(generation = 2)
            testScheduler.runCurrent()
            assertFalse(vm.uiState.value.isSending)
            assertEquals(GroupSendIssue.CONNECTION_CHANGED, vm.uiState.value.sendIssue)
            assertEquals(
                mapOf("scout" to GroupDelivery.UNKNOWN, "coder" to GroupDelivery.NOT_SENT),
                vm.uiState.value.messages
                    .first()
                    .deliveries,
            )
            ack.complete(emptyMap<String, Any>())
            eventsFlow.emit(WsEvent.MessageComplete(text = "late response", sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            assertEquals(1, transport.submissions.size)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.text == "late response" },
            )
            assertEquals(
                GroupDelivery.COMPLETE,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
        }

    @Test
    fun testPendingAckStopLeaveAndReentrantStopNeverClaimUnsent() =
        runTest(testDispatcher) {
            for (mode in listOf("stop", "leave", "reentrant")) {
                transport = FakeGroupTransport(eventsFlow)
                val ack = CompletableDeferred<Any?>()
                transport.submitResponse = ack
                val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
                testScheduler.runCurrent()
                if (mode == "reentrant") transport.onSubmit = { vm.stopGeneration() }
                vm.sendMessage("@all synthetic $mode")
                testScheduler.runCurrent()
                if (mode == "stop") vm.stopGeneration()
                if (mode == "leave") vm.leaveGroup("Dev Team")
                testScheduler.runCurrent()
                assertEquals(
                    mapOf("scout" to GroupDelivery.UNKNOWN, "coder" to GroupDelivery.NOT_SENT),
                    vm.uiState.value.messages
                        .first()
                        .deliveries,
                )
                assertFalse(vm.uiState.value.isSending)
                ack.complete(emptyMap<String, Any>())
                eventsFlow.emit(WsEvent.MessageComplete(text = "late $mode", sessionId = "session-scout-1"))
                testScheduler.runCurrent()
                assertEquals(1, transport.submissions.size)
                assertFalse(
                    vm.uiState.value.messages
                        .any { it.text == "late $mode" },
                )
            }
        }

    @Test
    fun testPendingAckBecomesAcceptedOnlyAfterAckThenComplete() =
        runTest(testDispatcher) {
            val ack = CompletableDeferred<Any?>()
            transport.submitResponse = ack
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout synthetic ACK")
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.UNKNOWN,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            ack.complete(emptyMap<String, Any>())
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.ACCEPTED,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            eventsFlow.emit(WsEvent.MessageComplete(text = "completed", sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.COMPLETE,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            assertEquals(1, transport.submissions.size)
        }

    @Test
    fun testPersistedTwoRoundsMustExecuteSecondCycleBeforeSummary() =
        runTest(testDispatcher) {
            val snapshot = GroupChatSyncSnapshot(version = 3, rooms = mapOf("name:Four" to fourRoom(limit = 12)))
            val encoded =
                json
                    .encodeToJsonElement(
                        snapshot,
                    ).toString()
                    .replace(Regex(""",?"discussionRounds":\d+"""), "")
                    .replace("\"maxBotMessages\":12", "\"maxBotMessages\":12,\"discussionRounds\":2")
            assertEquals(1, Regex(""""discussionRounds"""").findAll(encoded).count())
            val default =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta =
                        mapOf("hermes-bots-groups" to Json.parseToJsonElement(encoded)),
                )
            coEvery { mockApi.getProfiles() } returns
                Response.success(
                    ProfilesResponse(
                        profiles =
                            listOf(default) + listOf("deny", "idea2", "boss", "idea1").map { ProfileInfo(name = it) },
                    ),
                )
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@all two complete rounds")
            testScheduler.runCurrent()
            listOf("boss", "idea1", "idea2", "deny").forEach { completeTurn(it) }
            assertEquals("boss", transport.submissions.last().first)
            assertFalse(
                "Second cycle must precede summary",
                transport.submissions
                    .last()
                    .second
                    .contains("Final summary turn:"),
            )
            listOf("boss", "idea1", "idea2", "deny").forEach { completeTurn(it) }
            assertTrue(
                transport.submissions
                    .last()
                    .second
                    .contains("Final summary turn:"),
            )
            completeTurn("boss", "final two-round summary")
            assertEquals(
                listOf(
                    "boss",
                    "idea1",
                    "idea2",
                    "deny",
                    "boss",
                    "idea1",
                    "idea2",
                    "deny",
                    "boss",
                ),
                transport.submissions.map {
                    it.first
                },
            )
            assertFalse(vm.uiState.value.isSending)
        }

    @Test
    fun testControlledOneTwoThreeRoundsFollowConfiguredOrderBeforeSingleSummary() =
        runTest(testDispatcher) {
            val order = listOf("idea2", "deny", "boss", "idea1")
            for (rounds in 1..3) {
                transport = FakeGroupTransport(eventsFlow)
                serveRoom(fourRoom(order = order, limit = 20, rounds = rounds), order.reversed())
                val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
                testScheduler.runCurrent()
                vm.sendMessage("whole group discussion: original-topic-sentinel")
                testScheduler.runCurrent()
                repeat(rounds) { index ->
                    order.forEach { member ->
                        assertEquals(member, transport.submissions.last().first)
                        assertTrue(
                            transport.submissions
                                .last()
                                .second
                                .contains("Discussion round ${index + 1} of $rounds:"),
                        )
                        assertFalse(
                            transport.submissions
                                .last()
                                .second
                                .contains("Final summary turn:"),
                        )
                        completeTurn(member, "$member contribution round ${index + 1}")
                    }
                }
                assertTrue(
                    transport.submissions
                        .last()
                        .second
                        .contains("Final summary turn:"),
                )
                completeTurn("boss", "summary @idea2")
                assertEquals(List(rounds) { order }.flatten() + "boss", transport.submissions.map { it.first })
                assertEquals(1, transport.submissions.count { it.second.contains("Final summary turn:") })
                assertFalse(vm.uiState.value.isSending)
                vm.leaveGroup("Four")
            }
        }

    @Test
    fun testCyclicMentionsHaveBoundedExtraTurnsAndResumeRemainingOrderAcrossRounds() =
        runTest(testDispatcher) {
            serveRoom(fourRoom(limit = 20, rounds = 2).copy(maxContinuationPasses = 2))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@all cyclic priority test")
            testScheduler.runCurrent()
            completeTurn("boss", "@deny @deny @boss @missing")
            completeTurn("deny", "@boss")
            completeTurn("boss", "@deny")
            completeTurn("deny", "@boss")
            completeTurn("idea1", "@idea1 @missing")
            completeTurn("idea2", "round one ends")
            listOf("boss", "idea1", "idea2", "deny").forEach { completeTurn(it) }
            completeTurn("boss", "summary @idea1")
            assertEquals(
                listOf(
                    "boss",
                    "deny",
                    "boss",
                    "deny",
                    "idea1",
                    "idea2",
                    "boss",
                    "idea1",
                    "idea2",
                    "deny",
                    "boss",
                ),
                transport.submissions.map {
                    it.first
                },
            )
            assertFalse(vm.uiState.value.isSending)
            assertEquals(1, transport.submissions.count { it.second.contains("Final summary turn:") })
        }

    @Test
    fun testZeroExtraBudgetStillAllowsEarlyMentionAndCompletesEveryScheduledSlot() =
        runTest(testDispatcher) {
            serveRoom(fourRoom(rounds = 2, limit = 12).copy(maxContinuationPasses = 0))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@all early mention with zero extra budget")
            testScheduler.runCurrent()
            completeTurn("boss", "@deny")
            completeTurn("deny", "@boss")
            completeTurn("idea1")
            completeTurn("idea2")
            listOf("boss", "idea1", "idea2", "deny").forEach { completeTurn(it) }
            completeTurn("boss", "summary")
            assertEquals(
                listOf(
                    "boss",
                    "deny",
                    "idea1",
                    "idea2",
                    "boss",
                    "idea1",
                    "idea2",
                    "deny",
                    "boss",
                ),
                transport.submissions.map {
                    it.first
                },
            )
            assertFalse(vm.uiState.value.isSending)
        }

    @Test
    fun testPassesCountTowardGlobalTurnCapAndCannotPreventFiniteRoundTermination() =
        runTest(testDispatcher) {
            for (capped in listOf(false, true)) {
                transport = FakeGroupTransport(eventsFlow)
                serveRoom(fourRoom(rounds = 3, limit = if (capped) 5 else 20))
                val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
                testScheduler.runCurrent()
                vm.sendMessage("@all pass budget")
                testScheduler.runCurrent()
                val order = List(3) { listOf("boss", "idea1", "idea2", "deny") }.flatten().take(if (capped) 5 else 12)
                order.forEach { member ->
                    assertEquals(member, transport.submissions.last().first)
                    completeTurn(member, "(pass)")
                }
                assertEquals(order, transport.submissions.map { it.first })
                assertFalse(vm.uiState.value.isSending)
                assertFalse(transport.submissions.any { it.second.contains("Final summary turn:") })
                assertEquals(
                    capped,
                    vm.uiState.value.messages
                        .any { it.text == CAPPED_SYSTEM_TEXT },
                )
                vm.leaveGroup("Four")
            }
        }

    @Test
    fun testFinalSummaryConsumesLastGlobalTurnAndDoesNotRepeat() =
        runTest(testDispatcher) {
            serveRoom(fourRoom(rounds = 2, limit = 9))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@all exactly nine turns")
            testScheduler.runCurrent()
            repeat(2) { listOf("boss", "idea1", "idea2", "deny").forEach { completeTurn(it) } }
            assertEquals(9, transport.submissions.size)
            assertTrue(
                transport.submissions
                    .last()
                    .second
                    .contains("Final summary turn:"),
            )
            completeTurn("boss", "summary @all @deny")
            assertFalse(vm.uiState.value.isSending)
            assertEquals(9, transport.submissions.size)
            assertFalse(
                vm.uiState.value.messages
                    .any { it.text == CAPPED_SYSTEM_TEXT },
            )
        }

    @Test
    fun testSecondRoundFailureStopsRemainingMembersAndSummaryWithoutRetry() =
        runTest(testDispatcher) {
            serveRoom(fourRoom(rounds = 3, limit = 20))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@all second round failure")
            testScheduler.runCurrent()
            listOf("boss", "idea1", "idea2").forEach { completeTurn(it) }
            transport.submitFailure = IllegalStateException("synthetic failure")
            completeTurn("deny")
            assertFalse(vm.uiState.value.isSending)
            assertEquals(listOf("boss", "idea1", "idea2", "deny", "boss"), transport.submissions.map { it.first })
            assertFalse(transport.submissions.any { it.second.contains("Final summary turn:") })
            assertEquals(
                GroupDelivery.UNKNOWN,
                vm.uiState.value.messages
                    .first()
                    .deliveries["boss"],
            )
            completeTurn("boss", "late failure response")
            assertTrue(
                vm.uiState.value.messages
                    .any { it.text == "late failure response" },
            )
        }

    @Test
    fun testSecondRoundUnknownAckStopOrReconnectNeverReplaysOrStartsSummary() =
        runTest(testDispatcher) {
            for (reconnect in listOf(false, true)) {
                transport = FakeGroupTransport(eventsFlow)
                serveRoom(fourRoom(rounds = 3, limit = 20))
                val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
                testScheduler.runCurrent()
                vm.sendMessage("@all pending second round ACK")
                testScheduler.runCurrent()
                listOf("boss", "idea1", "idea2").forEach { completeTurn(it) }
                val ack = CompletableDeferred<Any?>()
                transport.submitResponse = ack
                completeTurn("deny")
                assertEquals(
                    GroupDelivery.UNKNOWN,
                    vm.uiState.value.messages
                        .first()
                        .deliveries["boss"],
                )
                if (reconnect) {
                    transport.connectionScope.value =
                        transport.connectionScope.value!!.copy(
                            generation = 2,
                        )
                } else {
                    vm.stopGeneration()
                }
                testScheduler.runCurrent()
                ack.complete(emptyMap<String, Any>())
                completeTurn("boss", "late round ACK response")
                assertEquals(5, transport.submissions.size)
                assertFalse(vm.uiState.value.isSending)
                assertEquals(
                    reconnect,
                    vm.uiState.value.messages
                        .any { it.text == "late round ACK response" },
                )
                assertFalse(transport.submissions.any { it.second.contains("Final summary turn:") })
                assertEquals(
                    if (reconnect) GroupDelivery.COMPLETE else GroupDelivery.UNKNOWN,
                    vm.uiState.value.messages
                        .first()
                        .deliveries["boss"],
                )
            }
        }

    @Test
    fun testRoundDefaultsAndMalformedStoredValuesAreBoundedAndSaveFailureRollsBack() =
        runTest(testDispatcher) {
            for ((stored, expected) in listOf(null to 1, -7 to 1, 0 to 1, 999 to 10, 3 to 3)) {
                transport = FakeGroupTransport(eventsFlow)
                serveRoom(fourRoom().copy(discussionRounds = stored))
                val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
                testScheduler.runCurrent()
                assertEquals(expected, vm.uiState.value.discussionRounds)
                vm.leaveGroup("Four")
            }
            serveRoom(fourRoom(rounds = 2))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.updateGroupLimits(20, 2, discussionRounds = 999)
            testScheduler.runCurrent()
            assertEquals(10, vm.uiState.value.discussionRounds)
            vm.updateGroupLimits(20, 2, discussionRounds = -3)
            testScheduler.runCurrent()
            assertEquals(1, vm.uiState.value.discussionRounds)
            every {
                HermesWsClient.request(
                    com.m57.hermescontrol.data.ws.WsMethods.PROFILES_CONFIGURE,
                    any(),
                    any(),
                )
            } returns
                CompletableDeferred<Any?>().also {
                    it.completeExceptionally(
                        IllegalStateException("synthetic save failure"),
                    )
                }
            vm.updateGroupLimits(20, 2, discussionRounds = 3)
            testScheduler.runCurrent()
            assertEquals(1, vm.uiState.value.discussionRounds)
            assertTrue(vm.uiState.value.settingsSaveFailed)
        }

    private fun fourRoom(
        order: List<String>? = listOf("boss", "idea1", "idea2", "deny"),
        summary: String? = "boss",
        limit: Int = 6,
        rounds: Int = 1,
    ): GroupChatRoomMeta =
        GroupChatRoomMeta(
            name = "Four",
            members =
                listOf("boss", "idea1", "idea2", "deny").map {
                    JsonPrimitive(it)
                },
            speakingOrder = order,
            finalSummaryMember = summary,
            maxBotMessages = limit,
            discussionRounds = rounds,
        )

    private fun serveRoom(
        room: GroupChatRoomMeta,
        profileOrder: List<String> = listOf("deny", "idea2", "boss", "idea1"),
    ) {
        val snapshot =
            GroupChatSyncSnapshot(
                version = 3,
                rooms =
                    mapOf(
                        "name:Four" to room,
                        "name:Other" to GroupChatRoomMeta(name = "Other", members = listOf(JsonPrimitive("untouched"))),
                    ),
            )
        val default =
            ProfileInfo(
                name = "default",
                is_default = true,
                ui_meta =
                    mapOf("hermes-bots-groups" to json.encodeToJsonElement(snapshot)),
            )
        coEvery { mockApi.getProfiles() } returns
            Response.success(ProfilesResponse(profiles = listOf(default) + profileOrder.map { ProfileInfo(name = it) }))
    }

    private suspend fun completeTurn(
        member: String,
        reply: String = "reply by $member",
    ) {
        val index = if (transport.uniqueSessions) transport.creates.count { it == member } else 1
        eventsFlow.emit(WsEvent.MessageComplete(text = reply, sessionId = "session-$member-$index"))
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun testSavedRosterOrderIgnoresProfileListPermutationsWithoutEnablingControl() =
        runTest(testDispatcher) {
            val names = listOf("boss", "idea1", "idea2", "deny")
            for (permutation in listOf(names.reversed(), listOf("idea2", "deny", "idea1", "boss"))) {
                transport = FakeGroupTransport(eventsFlow)
                serveRoom(fourRoom(order = null, summary = null), permutation)
                val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
                testScheduler.runCurrent()
                assertEquals(
                    names,
                    vm.uiState.value.members
                        .map { it.name },
                )
                assertNull(vm.uiState.value.speakingOrder)
                vm.sendMessage("@all ordered roster")
                testScheduler.runCurrent()
                names.forEach { completeTurn(it) }
                assertEquals(names, transport.submissions.map { it.first })
                assertFalse(vm.uiState.value.isSending)
                vm.leaveGroup("Four")
            }
        }

    @Test
    fun testControlledEarlyMentionTakesPriorityThenResumesOrderAndSummarizesExactlyOnce() =
        runTest(testDispatcher) {
            serveRoom(fourRoom())
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@all synthetic discussion")
            testScheduler.runCurrent()
            for (member in listOf("boss", "deny", "idea1", "idea2")) {
                assertEquals(member, transport.submissions.last().first)
                completeTurn(member, if (member == "boss") "boss @deny" else "reply by $member")
            }
            assertEquals("boss", transport.submissions.last().first)
            assertTrue(
                transport.submissions
                    .last()
                    .second
                    .contains("Final summary turn:"),
            )
            assertTrue(
                transport.submissions
                    .last()
                    .second
                    .contains("reply by idea2"),
            )
            completeTurn("boss", "summary @idea1 @deny")
            assertFalse(vm.uiState.value.isSending)
            assertEquals(listOf("boss", "deny", "idea1", "idea2", "boss"), transport.submissions.map { it.first })
            assertEquals(
                5,
                vm.uiState.value.messages
                    .count { !it.isUser && !it.isSystem },
            )
            assertTrue(
                transport.snapshots.all { snapshot ->
                    val decoded = json.decodeFromJsonElement<GroupChatSyncSnapshot>(snapshot.toJsonElement())
                    decoded.rooms!!["name:Four"]!!.speakingOrder == listOf("boss", "idea1", "idea2", "deny") &&
                        decoded.rooms!!["name:Other"]!!.memberNames == listOf("untouched")
                },
            )
        }

    @Test
    fun testControlledDirectMentionsUseSavedSubsetOrderWithoutSummaryOrHandoff() =
        runTest(testDispatcher) {
            serveRoom(fourRoom(rounds = 3, limit = 20))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@deny @idea1 synthetic direct question")
            testScheduler.runCurrent()
            completeTurn("idea1", "reply @boss")
            completeTurn("deny", "reply @idea2")
            assertFalse(vm.uiState.value.isSending)
            assertEquals(listOf("idea1", "deny"), transport.submissions.map { it.first })
            assertFalse(transport.submissions.any { it.second.contains("Final summary turn:") })
        }

    @Test
    fun testSummaryRespectsBudgetAndInitialFailure() =
        runTest(testDispatcher) {
            for (failure in listOf(false, true)) {
                transport = FakeGroupTransport(eventsFlow)
                serveRoom(fourRoom(limit = if (failure) 6 else 4))
                if (failure) transport.createFailures.add("idea2")
                val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
                testScheduler.runCurrent()
                vm.sendMessage("@all bounded discussion")
                testScheduler.runCurrent()
                listOf(
                    "boss",
                    "idea1",
                    "idea2",
                    "deny",
                ).take(if (failure) 2 else 4).forEach { completeTurn(it) }
                assertFalse(vm.uiState.value.isSending)
                assertEquals(
                    if (failure) {
                        listOf(
                            "boss",
                            "idea1",
                        )
                    } else {
                        listOf("boss", "idea1", "idea2", "deny")
                    },
                    transport.submissions.map {
                        it.first
                    },
                )
                assertFalse(transport.submissions.any { it.second.contains("Final summary turn:") })
                if (!failure) {
                    assertTrue(
                        vm.uiState.value.messages
                            .any { it.isSystem && it.text == CAPPED_SYSTEM_TEXT },
                    )
                }
                vm.leaveGroup("Four")
            }
        }

    @Test
    fun testControlledCancelAndReconnectNeverStartSummaryOrReplay() =
        runTest(testDispatcher) {
            for (reconnect in listOf(false, true)) {
                transport = FakeGroupTransport(eventsFlow)
                serveRoom(fourRoom())
                val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
                testScheduler.runCurrent()
                vm.sendMessage("@all cancel discussion")
                testScheduler.runCurrent()
                completeTurn("boss")
                if (reconnect) {
                    transport.connectionScope.value =
                        transport.connectionScope.value!!.copy(
                            generation = 2,
                        )
                } else {
                    vm.stopGeneration()
                }
                testScheduler.runCurrent()
                completeTurn("idea1", "late controlled reply")
                assertEquals(listOf("boss", "idea1"), transport.submissions.map { it.first })
                assertFalse(vm.uiState.value.isSending)
                assertEquals(
                    reconnect,
                    vm.uiState.value.messages
                        .any { it.text == "late controlled reply" },
                )
                assertFalse(transport.submissions.any { it.second.contains("Final summary turn:") })
                assertEquals(
                    if (reconnect) GroupDelivery.COMPLETE else GroupDelivery.UNKNOWN,
                    vm.uiState.value.messages
                        .first()
                        .deliveries["idea1"],
                )
                assertEquals(
                    GroupDelivery.NOT_SENT,
                    vm.uiState.value.messages
                        .first()
                        .deliveries["deny"],
                )
            }
        }

    @Test
    fun testOrderAndSummaryRoundTripPreserveRawRosterHistoryAndOtherRoom() =
        runTest(testDispatcher) {
            val raw =
                listOf(
                    JsonPrimitive("boss"),
                    JsonPrimitive("idea1"),
                    JsonPrimitive("idea2"),
                    JsonPrimitive("deny"),
                    JsonPrimitive(""),
                    Json.parseToJsonElement("{\"name\":\"\",\"handle\":\"boss\",\"future\":7}"),
                )
            val room =
                fourRoom(
                    order = null,
                    summary = null,
                ).copy(
                    members = raw,
                    log = listOf(GroupChatSyncLogEntry(id = "old", text = "old history")),
                    revision = 9,
                )
            serveRoom(room)
            var saved: GroupChatSyncSnapshot? = null
            every {
                HermesWsClient.request(
                    com.m57.hermescontrol.data.ws.WsMethods.PROFILES_CONFIGURE,
                    any(),
                    any(),
                )
            } answers
                {
                    val params = secondArg<Map<String, Any?>>()
                    val payload = params["ui_meta"] as Map<*, *>
                    saved =
                        json.decodeFromJsonElement<GroupChatSyncSnapshot>(payload["hermes-bots-groups"].toJsonElement())
                    CompletableDeferred(emptyMap<String, Any>())
                }
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            val order = listOf("idea2", "boss", "deny", "idea1")
            vm.updateGroupLimits(15, 3, "keep instructions", order, "boss", discussionRounds = 3)
            testScheduler.runCurrent()
            val updated = requireNotNull(saved).rooms!!["name:Four"]!!
            assertEquals(order, updated.speakingOrder)
            assertEquals("boss", updated.finalSummaryMember)
            assertEquals(3, updated.discussionRounds)
            assertEquals(raw, updated.members)
            assertEquals(room.log, updated.log)
            assertEquals(9L, updated.revision)
            assertEquals(listOf("untouched"), saved!!.rooms!!["name:Other"]!!.memberNames)
            serveRoom(
                json.decodeFromString<GroupChatRoomMeta>(Json.encodeToString(GroupChatRoomMeta.serializer(), updated)),
            )
            val reopened =
                GroupChatViewModel(
                    "Four",
                    ioDispatcher = testDispatcher,
                    groupTransport = FakeGroupTransport(eventsFlow),
                )
            testScheduler.runCurrent()
            assertEquals(
                order,
                reopened.uiState.value.members
                    .map { it.name },
            )
            assertEquals(order, reopened.uiState.value.speakingOrder)
            assertEquals("boss", reopened.uiState.value.finalSummaryMember)
            assertEquals(3, reopened.uiState.value.discussionRounds)
        }

    @Test
    fun testInvalidOrderAndSummaryCannotCreateRecipients() =
        runTest(testDispatcher) {
            serveRoom(fourRoom(order = listOf("", "missing", "deny", "deny", "idea1"), summary = "missing"))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            assertEquals(
                listOf("deny", "idea1", "boss", "idea2"),
                vm.uiState.value.members
                    .map { it.name },
            )
            assertNull(vm.uiState.value.finalSummaryMember)
            vm.sendMessage("@all sanitized discussion")
            testScheduler.runCurrent()
            vm.uiState.value.members
                .forEach { completeTurn(it.name) }
            assertEquals(listOf("deny", "idea1", "boss", "idea2"), transport.submissions.map { it.first })
            assertFalse(vm.uiState.value.isSending)
        }

    @Test
    fun testOrderSaveFailureKeepsPreviousOrderAndReportsFailure() =
        runTest(testDispatcher) {
            serveRoom(fourRoom())
            every {
                HermesWsClient.request(
                    com.m57.hermescontrol.data.ws.WsMethods.PROFILES_CONFIGURE,
                    any(),
                    any(),
                )
            } returns
                CompletableDeferred<Any?>().also {
                    it.completeExceptionally(
                        IllegalStateException("synthetic configure failure"),
                    )
                }
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.updateGroupLimits(
                6,
                2,
                speakingOrder = listOf("deny", "idea2", "idea1", "boss"),
                finalSummaryMember = "deny",
            )
            testScheduler.runCurrent()
            assertTrue(vm.uiState.value.settingsSaveFailed)
            assertEquals(listOf("boss", "idea1", "idea2", "deny"), vm.uiState.value.speakingOrder)
            assertEquals("boss", vm.uiState.value.finalSummaryMember)
            assertEquals(0, transport.submissions.size)
        }

    @Test
    fun testDisablingOrderWritesExplicitNullsWithoutChangingOtherRoom() =
        runTest(testDispatcher) {
            serveRoom(fourRoom(order = listOf("deny", "idea2", "idea1", "boss"), summary = "deny"))
            var saved: Map<String, Any?>? = null
            every {
                HermesWsClient.request(
                    com.m57.hermescontrol.data.ws.WsMethods.PROFILES_CONFIGURE,
                    any(),
                    any(),
                )
            } answers
                {
                    saved = secondArg<Map<String, Any?>>()
                    CompletableDeferred(emptyMap<String, Any>())
                }
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.updateGroupLimits(6, 2, speakingOrder = null, finalSummaryMember = null)
            testScheduler.runCurrent()
            val rooms = ((saved!!["ui_meta"] as Map<*, *>)["hermes-bots-groups"] as Map<*, *>)["rooms"] as Map<*, *>
            val room = rooms["name:Four"] as Map<*, *>
            assertTrue(room.containsKey("speakingOrder"))
            assertNull(room["speakingOrder"])
            assertTrue(room.containsKey("finalSummaryMember"))
            assertNull(room["finalSummaryMember"])
            val other = rooms["name:Other"] as Map<*, *>
            assertFalse(other.containsKey("speakingOrder"))
            assertFalse(other.containsKey("finalSummaryMember"))
            assertNull(vm.uiState.value.speakingOrder)
            assertEquals(
                listOf("boss", "idea1", "idea2", "deny"),
                vm.uiState.value.members
                    .map { it.name },
            )
        }

    @Test
    fun testPendingSettingsSaveBlocksSendAndConnectionChangeKeepsPreviousOrder() =
        runTest(testDispatcher) {
            serveRoom(fourRoom())
            val ack = CompletableDeferred<Any?>()
            every {
                HermesWsClient.request(
                    com.m57.hermescontrol.data.ws.WsMethods.PROFILES_CONFIGURE,
                    any(),
                    any(),
                )
            } returns
                ack
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.updateGroupLimits(
                6,
                2,
                speakingOrder = listOf("deny", "idea2", "idea1", "boss"),
                finalSummaryMember = "deny",
            )
            testScheduler.runCurrent()
            assertTrue(vm.uiState.value.isSavingGroupSettings)
            vm.sendMessage("@all must stay a draft")
            testScheduler.runCurrent()
            assertEquals(0, transport.submissions.size)
            assertEquals(
                0,
                vm.uiState.value.messages
                    .count { it.isUser },
            )
            transport.connectionScope.value = transport.connectionScope.value!!.copy(generation = 2)
            ack.complete(emptyMap<String, Any>())
            testScheduler.runCurrent()
            assertFalse(vm.uiState.value.isSavingGroupSettings)
            assertTrue(vm.uiState.value.settingsSaveFailed)
            assertEquals(listOf("boss", "idea1", "idea2", "deny"), vm.uiState.value.speakingOrder)
            assertEquals("boss", vm.uiState.value.finalSummaryMember)
            assertEquals(0, transport.submissions.size)
        }

    @Test
    fun conversationsMigrateOnceAndPreserveMoreThan32Messages() =
        runTest(testDispatcher) {
            val room =
                GroupChatRoomMeta(
                    name = "Dev Team",
                    log =
                        (1..70).map {
                            GroupChatSyncLogEntry(
                                id = "legacy-$it",
                                text = "legacy $it",
                                from = GroupChatSyncFrom(kind = "user"),
                            )
                        },
                )
            val metadata =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta =
                        mapOf(
                            "hermes-bots-groups" to
                                json.encodeToJsonElement(GroupChatSyncSnapshot(rooms = mapOf("Dev Team" to room))),
                        ),
                )
            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(listOf(metadata, botA, botB)))
            val store = MemoryGroupConversationStore()
            val vm =
                GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport, conversationStore = store)
            testScheduler.runCurrent()
            val legacyId = vm.uiState.value.conversationId
            assertEquals(70, vm.uiState.value.messages.size)
            vm.updateDraft("original draft")
            vm.newConversation()
            val newId = vm.uiState.value.conversationId
            assertTrue(newId != legacyId)
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            vm.loadGroup()
            testScheduler.runCurrent()
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            vm.selectConversation(legacyId)
            assertEquals(70, vm.uiState.value.messages.size)
            assertEquals("original draft", vm.uiState.value.draft)
            vm.leaveGroup("Dev Team")
            val restored =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = FakeGroupTransport(eventsFlow),
                    conversationStore = store,
                )
            testScheduler.runCurrent()
            assertEquals(legacyId, restored.uiState.value.conversationId)
            assertEquals(70, restored.uiState.value.messages.size)
            assertTrue(transport.submissions.isEmpty())
        }

    @Test
    fun switchingWhileAckPendingKeepsUnknownAndRoutesLateReplyToOriginalConversation() =
        runTest(testDispatcher) {
            transport.uniqueSessions = true
            transport.submitResponse = CompletableDeferred()
            val store = MemoryGroupConversationStore()
            val vm =
                GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport, conversationStore = store)
            testScheduler.runCurrent()
            vm.sendMessage("@scout original")
            testScheduler.runCurrent()
            val originalId = vm.uiState.value.conversationId
            vm.newConversation()
            val newId = vm.uiState.value.conversationId
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            transport.submitResponse = null
            vm.sendMessage("@scout separate")
            testScheduler.runCurrent()
            assertFalse(
                transport.submissions
                    .last()
                    .second
                    .contains("original"),
            )
            eventsFlow.emit(WsEvent.MessageComplete("original late reply", sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            assertEquals(newId, vm.uiState.value.conversationId)
            assertFalse(
                vm.uiState.value.messages
                    .any { it.text == "original late reply" },
            )
            eventsFlow.emit(WsEvent.MessageComplete("separate final", sessionId = "session-scout-2"))
            testScheduler.runCurrent()
            vm.selectConversation(originalId)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.text == "original late reply" },
            )
            assertEquals(
                GroupDelivery.COMPLETE,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            assertEquals(2, transport.submissions.size)
            assertTrue(transport.interrupts.isEmpty())
        }

    @Test
    fun restartAndReconnectReconcileExactHistoryWithoutResubmit() =
        runTest(testDispatcher) {
            val store = MemoryGroupConversationStore()
            transport.submitResponse = CompletableDeferred()
            val vm =
                GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport, conversationStore = store)
            testScheduler.runCurrent()
            vm.sendMessage("@scout recover")
            testScheduler.runCurrent()
            val prompt = transport.submissions.single().second
            vm.leaveGroup("Dev Team")
            val recoveredTransport = FakeGroupTransport(eventsFlow)
            val recovered =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = recoveredTransport,
                    conversationStore = store,
                )
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.UNKNOWN,
                recovered.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            recovered.sendMessage("must not send while unknown")
            testScheduler.runCurrent()
            assertTrue(recoveredTransport.submissions.isEmpty())
            recoveredTransport.historyRows =
                listOf(
                    SessionMessage(role = "user", content = JsonPrimitive(prompt)),
                    SessionMessage(role = "assistant", content = JsonPrimitive("recovered final")),
                )
            recoveredTransport.connectionScope.value = recoveredTransport.connectionScope.value!!.copy(generation = 2)
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.COMPLETE,
                recovered.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            assertTrue(
                recovered.uiState.value.messages
                    .any { it.text == "recovered final" },
            )
            assertTrue(recoveredTransport.submissions.isEmpty())
            assertEquals(listOf("scout" to "stored-scout-1"), recoveredTransport.historyReads.takeLast(1))
            recovered.reconcilePending()
            testScheduler.runCurrent()
            assertEquals(
                1,
                recovered.uiState.value.messages
                    .count { it.text == "recovered final" },
            )
        }

    @Test
    fun diagnosticsReadOnlyTheSelectedPostAndNeverWriteReconcileOrSubmit() =
        runTest(testDispatcher) {
            val delegate = MemoryGroupConversationStore()
            var writes = 0
            val store =
                object : GroupConversationStore {
                    override fun read(key: String) = delegate.read(key)

                    override fun write(
                        key: String,
                        archive: GroupConversationArchive,
                    ) {
                        writes++
                        delegate.write(key, archive)
                    }
                }
            var scope = "https://synthetic.test:9119/proxy/\u0000connection\u0000default"
            transport.submitResponse = CompletableDeferred()
            val vm =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = transport,
                    conversationStore = store,
                    conversationScope = { scope },
                )
            testScheduler.runCurrent()
            vm.sendMessage("@scout PRIVATE_DIAGNOSTIC_TEST_BODY")
            testScheduler.runCurrent()
            val key = "$scope\u0000Dev Team"
            val before = store.read(key)
            val stateBefore = vm.uiState.value
            val writesBefore = writes
            val submissionsBefore = transport.submissions.toList()
            val readsBefore = transport.historyReads.toList()
            val interruptsBefore = transport.interrupts.toList()
            val post =
                vm.uiState.value.messages
                    .first { it.isUser }
            repeat(3) {
                val diagnostic =
                    vm
                        .sendDiagnostics(post.id)!!
                        .choices
                        .single()
                        .diagnostic
                assertEquals("scout", diagnostic.bot)
                assertFalse(diagnostic.copyText().contains("PRIVATE_DIAGNOSTIC_TEST_BODY"))
            }
            assertNull(vm.sendDiagnostics("a different post"))
            assertEquals(before, store.read(key))
            assertEquals(stateBefore, vm.uiState.value)
            assertEquals(writesBefore, writes)
            assertEquals(submissionsBefore, transport.submissions)
            assertEquals(readsBefore, transport.historyReads)
            assertEquals(interruptsBefore, transport.interrupts)
            scope = "https://different.test\u0000connection\u0000default"
            assertNull(vm.sendDiagnostics(post.id))
            assertEquals(writesBefore, writes)
        }

    @Test
    fun storageFailureAndConcurrentSendCannotSubmitUnjournaledPrompt() =
        runTest(testDispatcher) {
            val failing =
                object : GroupConversationStore {
                    override fun read(key: String): GroupConversationArchive? = null

                    override fun write(
                        key: String,
                        archive: GroupConversationArchive,
                    ) {
                        error("synthetic storage failure")
                    }
                }
            val vm =
                GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport, conversationStore = failing)
            testScheduler.runCurrent()
            vm.sendMessage("@scout blocked")
            vm.sendMessage("@scout duplicate")
            testScheduler.runCurrent()
            assertEquals(GroupSendIssue.STORAGE_UNAVAILABLE, vm.uiState.value.sendIssue)
            assertTrue(transport.submissions.isEmpty())
        }

    @Test
    fun timeoutAckStillObservesCompletionAndDefinitiveRejectionIsDifferentFromUnknown() =
        runTest(testDispatcher) {
            transport.submitFailure = HermesWsClient.HermesRpcException("synthetic timeout", -1)
            val vm = GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout delayed ACK")
            testScheduler.runCurrent()
            assertTrue(vm.uiState.value.isSending)
            eventsFlow.emit(WsEvent.MessageComplete("observed complete", sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.COMPLETE,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            transport.submitFailure = HermesWsClient.HermesRpcException("synthetic invalid params", -32602)
            vm.sendMessage("@scout rejected")
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.FAILED,
                vm.uiState.value.messages
                    .last { it.isUser }
                    .deliveries["scout"],
            )
            assertFalse(vm.uiState.value.isSending)
            assertEquals(2, transport.submissions.size)
        }

    @Test
    fun journalCommitFailureAfterSessionCreateStillCannotSubmit() =
        runTest(testDispatcher) {
            val durable = MemoryGroupConversationStore()
            val store =
                object : GroupConversationStore {
                    override fun read(key: String) = durable.read(key)

                    override fun write(
                        key: String,
                        archive: GroupConversationArchive,
                    ) {
                        check(archive.conversations.none { it.turns.isNotEmpty() })
                        durable.write(key, archive)
                    }
                }
            val vm =
                GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport, conversationStore = store)
            testScheduler.runCurrent()
            vm.sendMessage("@scout journal failure")
            testScheduler.runCurrent()
            assertEquals(1, transport.creates.size)
            assertTrue(transport.submissions.isEmpty())
            assertEquals(GroupSendIssue.STORAGE_UNAVAILABLE, vm.uiState.value.sendIssue)
            assertFalse(vm.uiState.value.isSending)
        }

    @Test
    fun connectionIdentityChangePreservesOriginalArchiveAndDoesNotReadItOnAnotherServer() =
        runTest(testDispatcher) {
            val store = MemoryGroupConversationStore()
            transport.submitResponse = CompletableDeferred()
            val vm =
                GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport, conversationStore = store)
            testScheduler.runCurrent()
            vm.sendMessage("@scout server-a-only")
            testScheduler.runCurrent()
            val original = vm.uiState.value.conversationId
            transport.connectionScope.value =
                transport.connectionScope.value!!.copy(serverIdentity = "another-server", generation = 2)
            testScheduler.runCurrent()
            assertFalse(vm.uiState.value.conversationId == original)
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            assertTrue(transport.historyReads.isEmpty())
            transport.connectionScope.value =
                transport.connectionScope.value!!.copy(serverIdentity = "synthetic", generation = 3)
            testScheduler.runCurrent()
            assertEquals(original, vm.uiState.value.conversationId)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.text == "@scout server-a-only" },
            )
            assertEquals(
                GroupDelivery.UNKNOWN,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            assertEquals(1, transport.submissions.size)
        }

    @Test
    fun conversationSwitchDuringHistoryReadCannotApplyResponseToNewConversation() =
        runTest(testDispatcher) {
            transport.submitResponse = CompletableDeferred()
            val vm = GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout original pending read")
            testScheduler.runCurrent()
            val original = vm.uiState.value.conversationId
            val prompt = transport.submissions.single().second
            vm.newConversation()
            transport.historyResponse = CompletableDeferred()
            vm.selectConversation(original)
            testScheduler.runCurrent()
            assertTrue(vm.uiState.value.isReconciling)
            vm.newConversation()
            transport.historyResponse!!.complete(
                listOf(
                    SessionMessage(role = "user", content = JsonPrimitive(prompt)),
                    SessionMessage(role = "assistant", content = JsonPrimitive("late read result")),
                ),
            )
            testScheduler.runCurrent()
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            assertFalse(vm.uiState.value.isReconciling)
            assertEquals(1, transport.submissions.size)
        }

    @Test
    fun reusedSessionIdCannotSendIntoAnotherConversation() =
        runTest(testDispatcher) {
            transport.uniqueSessions = false
            val vm = GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout first")
            testScheduler.runCurrent()
            eventsFlow.emit(WsEvent.MessageComplete("first final", sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            vm.newConversation()
            vm.sendMessage("@scout second")
            testScheduler.runCurrent()
            assertEquals(2, transport.creates.size)
            assertEquals(1, transport.submissions.size)
            assertEquals(
                GroupDelivery.NOT_SENT,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
        }

    @Test
    fun reconciliationReportsReadFailureWithoutChangingUnknownToSuccess() =
        runTest(testDispatcher) {
            for ((issue, expected) in listOf(
                GroupHistoryIssue.AUTH_REQUIRED to GroupReconciliationStatus.AUTH_REQUIRED,
                GroupHistoryIssue.NOT_FOUND to GroupReconciliationStatus.SESSION_NOT_FOUND,
                GroupHistoryIssue.PROFILE_NOT_FOUND to GroupReconciliationStatus.PROFILE_NOT_FOUND,
                GroupHistoryIssue.ENDPOINT_UNAVAILABLE to GroupReconciliationStatus.HISTORY_ENDPOINT_UNAVAILABLE,
                GroupHistoryIssue.ID_MISMATCH to GroupReconciliationStatus.SESSION_ID_MISMATCH,
                GroupHistoryIssue.UNAVAILABLE to GroupReconciliationStatus.READ_UNAVAILABLE,
            )) {
                transport = FakeGroupTransport(eventsFlow)
                transport.submitResponse = CompletableDeferred()
                val vm = GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport)
                testScheduler.runCurrent()
                vm.sendMessage("@scout diagnostic")
                testScheduler.runCurrent()
                vm.stopGeneration()
                transport.historyIssue = issue
                vm.reconcilePending()
                testScheduler.runCurrent()
                assertEquals(expected, vm.uiState.value.reconciliationStatus)
                assertEquals(
                    GroupDelivery.UNKNOWN,
                    vm.uiState.value.messages
                        .first()
                        .deliveries["scout"],
                )
                assertEquals(1, transport.submissions.size)
            }
        }

    @Test
    fun incompleteHistoryRetainsUnknownAndNeverResubmits() =
        runTest(testDispatcher) {
            transport.submitResponse = CompletableDeferred()
            val vm = GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout synthetic older request")
            testScheduler.runCurrent()
            vm.stopGeneration()
            transport.historyRows = emptyList()
            transport.historyComplete = false
            vm.reconcilePending()
            testScheduler.runCurrent()
            assertEquals(GroupReconciliationStatus.HISTORY_INCOMPLETE, vm.uiState.value.reconciliationStatus)
            assertEquals(
                GroupDelivery.UNKNOWN,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            assertEquals(1, transport.submissions.size)
        }

    @Test
    fun socketBecomingReadyAfterScopeChangeReconcilesWithoutAnotherPrompt() =
        runTest(testDispatcher) {
            transport.submitResponse = CompletableDeferred()
            val vm = GroupChatViewModel("Dev Team", testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout reconnect readiness")
            testScheduler.runCurrent()
            val prompt = transport.submissions.single().second
            transport.readReadiness.value = false
            transport.connectionScope.value = transport.connectionScope.value!!.copy(generation = 2)
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.UNKNOWN,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            transport.historyRows =
                listOf(
                    SessionMessage(role = "user", content = JsonPrimitive(prompt)),
                    SessionMessage(role = "assistant", content = JsonPrimitive("read after ready")),
                )
            transport.readReadiness.value = true
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.COMPLETE,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            assertEquals(GroupReconciliationStatus.COMPLETED, vm.uiState.value.reconciliationStatus)
            assertEquals(1, transport.submissions.size)
        }

    @Test
    fun reasoningActivityExtendsObservationAndStoredOnlyCompletionIsMatched() =
        runTest(testDispatcher) {
            val vm =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = transport,
                    turnClock = { testScheduler.currentTime },
                )
            testScheduler.runCurrent()
            vm.sendMessage("@scout synthetic reasoning")
            testScheduler.runCurrent()
            repeat(4) {
                testScheduler.advanceTimeBy(50_000)
                eventsFlow.emit(WsEvent.ReasoningDelta("synthetic", "session-scout-1"))
                testScheduler.runCurrent()
            }
            assertTrue(vm.uiState.value.isSending)
            eventsFlow.emit(
                WsEvent.MessageComplete("stored completion", sessionId = null, storedSessionId = "stored-scout-1"),
            )
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.COMPLETE,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            assertEquals(1, transport.submissions.size)
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        coEvery { mockApi.getProfiles() } returns
            Response.success(
                ProfilesResponse(
                    profiles = listOf(botA, botB),
                ),
            )

        transport = FakeGroupTransport(eventsFlow)
        mockkObject(HermesWsClient)
        every { HermesWsClient.events } returns eventsFlow
        every { HermesWsClient.send(any(), any(), any()) } returns "req-1"
        every { HermesWsClient.sendMessage(any(), any(), any()) } returns "req-msg"

        val createDeferred = CompletableDeferred<Any?>()
        createDeferred.complete(mapOf("session_id" to "session-scout-1"))
        every { HermesWsClient.request(any(), any(), any()) } returns createDeferred
    }

    @Test
    fun serverDiscussionDetachesWithoutInterruptAndReopensTheSameConversation() =
        runTest(testDispatcher) {
            val store = MemoryGroupConversationStore()
            var snapshot: GroupServerSnapshot? = null
            var stops = 0
            val server =
                object : GroupServerTransport {
                    override suspend fun read(
                        id: String,
                        group: String,
                    ): GroupServerSnapshot = snapshot ?: GroupServerSnapshot(id, group)

                    override suspend fun send(
                        id: String,
                        request: GroupServerRequest,
                    ): GroupServerSnapshot {
                        snapshot =
                            GroupServerSnapshot(
                                id = id,
                                groupName = request.groupName,
                                runId = request.requestId,
                                state = "running",
                                messages =
                                    listOf(
                                        GroupChatMessage(request.requestId, "user", "You", true, text = request.text),
                                    ),
                            )
                        return requireNotNull(snapshot)
                    }

                    override suspend fun stop(
                        id: String,
                        group: String,
                    ): GroupServerSnapshot {
                        stops++
                        return requireNotNull(snapshot).copy(state = "stopped")
                    }
                }
            val vm =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = transport,
                    conversationStore = store,
                    serverTransport = server,
                )
            testScheduler.runCurrent()
            val id = vm.uiState.value.conversationId
            vm.sendMessage("議論してください")
            testScheduler.runCurrent()
            assertTrue(vm.uiState.value.isSending)
            vm.leaveGroup("Dev Team")
            snapshot =
                requireNotNull(snapshot).copy(
                    state = "complete",
                    messages =
                        requireNotNull(snapshot).messages +
                            GroupChatMessage("answer", "coder", "Dev Bot", false, text = "サーバーで完了"),
                )
            vm.openConversation("Dev Team", id, null)
            testScheduler.runCurrent()
            assertEquals(
                "サーバーで完了",
                vm.uiState.value.messages
                    .last()
                    .text,
            )
            assertFalse(vm.uiState.value.isSending)
            assertEquals(0, stops)
            assertTrue(transport.submissions.isEmpty())
            assertTrue(transport.interrupts.isEmpty())
        }

    @Test
    fun uncertainServerAcceptanceReusesTheSavedRequestIdAfterRecreation() =
        runTest(testDispatcher) {
            val requests = mutableListOf<GroupServerRequest>()
            val store = MemoryGroupConversationStore()
            val server =
                object : GroupServerTransport {
                    override suspend fun read(
                        id: String,
                        group: String,
                    ) = GroupServerSnapshot(id, group)

                    override suspend fun send(
                        id: String,
                        request: GroupServerRequest,
                    ): GroupServerSnapshot {
                        requests += request
                        throw java.io.IOException("synthetic disconnect")
                    }

                    override suspend fun stop(
                        id: String,
                        group: String,
                    ) = GroupServerSnapshot(id, group)
                }
            val vm =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = transport,
                    conversationStore = store,
                    serverTransport = server,
                )
            testScheduler.runCurrent()
            vm.sendMessage("元の依頼")
            testScheduler.runCurrent()
            val original = requests.last()
            vm.leaveGroup("Dev Team")
            val reopened =
                GroupChatViewModel(
                    "Dev Team",
                    testDispatcher,
                    groupTransport = FakeGroupTransport(eventsFlow),
                    conversationStore = store,
                    serverTransport = server,
                )
            testScheduler.runCurrent()
            assertTrue(reopened.uiState.value.serverPending)
            assertEquals(original, requests.last())
            assertEquals(1, requests.map { it.requestId }.distinct().size)
        }

    @After
    fun tearDown() {
        unmockkObject(ApiClient)
        unmockkObject(HermesWsClient)
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    @Test
    fun testDiagnosticsKeepConversationButExcludeBodyAndRpcErrors() =
        runTest(testDispatcher) {
            val logs = captureDiagnosticLogs()
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            val body = "SYNTHETIC_PRIVATE_secret_sudo_token_ticket_cookie_本文"
            viewModel.sendMessage("@scout $body")
            testScheduler.runCurrent()
            eventsFlow.emit(WsEvent.MessageComplete(text = body, sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.text == body },
            )
            assertTrue(logs.any { it.startsWith("MessageComplete chars=") })

            transport.configureFailure = IllegalStateException(body)
            viewModel.sendMessage("@scout $body")
            testScheduler.runCurrent()
            assertTrue(logs.any { it.contains("failed:") })
            assertFalse(logs.joinToString("\n").contains("SYNTHETIC_PRIVATE"))
        }

    @Test
    fun testLoadGroup_resolvesMembers() =
        runTest(testDispatcher) {
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNull(state.errorMessage)
            assertEquals(2, state.members.size)
            assertEquals("scout", state.members[0].name)
            assertEquals("coder", state.members[1].name)
        }

    @Test
    fun testLoadGroup_hydratesFromSyncSnapshot() =
        runTest(testDispatcher) {
            val syncSnapshot =
                GroupChatSyncSnapshot(
                    version = 3,
                    updatedAt = 1724000000L,
                    rooms =
                        mapOf(
                            "name:Dev Team" to
                                GroupChatRoomMeta(
                                    name = "Dev Team",
                                    members = listOf(JsonPrimitive("scout"), JsonPrimitive("coder")),
                                    log =
                                        listOf(
                                            GroupChatSyncLogEntry(
                                                id = "msg-prev-1",
                                                from = GroupChatSyncFrom(kind = "user", name = "You"),
                                                text = "Initial design spec",
                                                at = 1724000000L,
                                            ),
                                            GroupChatSyncLogEntry(
                                                id = "msg-prev-2",
                                                from = GroupChatSyncFrom(kind = "member", name = "scout"),
                                                text = "Looks solid, ready to build.",
                                                at = 1724000010L,
                                            ),
                                        ),
                                ),
                        ),
                )

            val defaultProfileWithSnapshot =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta = mapOf("hermes-bots-groups" to json.encodeToJsonElement(syncSnapshot)),
                )

            coEvery { mockApi.getProfiles() } returns
                Response.success(
                    ProfilesResponse(
                        profiles = listOf(defaultProfileWithSnapshot, botA, botB),
                    ),
                )

            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(2, state.messages.size)
            assertEquals("msg-prev-1", state.messages[0].id)
            assertTrue(state.messages[0].isUser)
            assertEquals("Initial design spec", state.messages[0].text)

            assertEquals("msg-prev-2", state.messages[1].id)
            assertFalse(state.messages[1].isUser)
            assertEquals("scout", state.messages[1].senderName)
            assertEquals("Looks solid, ready to build.", state.messages[1].text)
        }

    @Test
    fun testSendMessage_streamsTokensAndCompletes() =
        runTest(testDispatcher) {
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()

            // Send message targeting only @scout
            viewModel.sendMessage("@scout what's the status?")
            testScheduler.runCurrent()

            // State should contain user message
            val userMsg =
                viewModel.uiState.value.messages
                    .first()
            assertTrue(userMsg.isUser)
            assertEquals("@scout what's the status?", userMsg.text)

            // Stream tokens
            eventsFlow.emit(WsEvent.MessageToken(token = "Building ", sessionId = "session-scout-1"))
            testScheduler.runCurrent()

            val streamingMsg =
                viewModel.uiState.value.messages
                    .last()
            assertFalse(streamingMsg.isUser)
            assertEquals("scout", streamingMsg.senderName)
            assertEquals("Building ", streamingMsg.text)
            assertTrue(streamingMsg.isStreaming)

            eventsFlow.emit(WsEvent.MessageToken(token = "the APK...", sessionId = "session-scout-1"))
            testScheduler.runCurrent()

            val updatedStreaming =
                viewModel.uiState.value.messages
                    .last()
            assertEquals("Building the APK...", updatedStreaming.text)

            // Complete turn
            eventsFlow.emit(
                WsEvent.MessageComplete(
                    text = "Building the APK now! Everything looks green.",
                    sessionId = "session-scout-1",
                ),
            )
            testScheduler.runCurrent()

            val finalState = viewModel.uiState.value
            assertEquals(2, finalState.messages.size)
            val botMsg = finalState.messages[1]
            assertEquals("Building the APK now! Everything looks green.", botMsg.text)
            assertFalse(botMsg.isStreaming)
            assertNull(finalState.activeSpeaker)
        }

    @Test
    fun testSendMessage_filtersOutPass() =
        runTest(testDispatcher) {
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()

            viewModel.sendMessage("@scout any updates?")
            testScheduler.runCurrent()

            eventsFlow.emit(
                WsEvent.MessageComplete(
                    text = "(pass)",
                    sessionId = "session-scout-1",
                ),
            )
            testScheduler.runCurrent()

            val finalState = viewModel.uiState.value
            // User message plus the visual pass indicator
            assertEquals(2, finalState.messages.size)
            assertTrue(finalState.messages.first().isUser)
            val passMsg = finalState.messages.last()
            assertTrue(passMsg.isSystem)
            assertTrue(passMsg.isPass)
            assertEquals("Scout Bot", passMsg.senderDisplayName)
            assertEquals("Scout Bot passed", passMsg.text)

            // LLM prompt history only considers non-system messages
            assertEquals(1, finalState.messages.filter { !it.isSystem }.size)
        }

    @Test
    fun testSendMessage_reactiveContinuationHandoff() =
        runTest(testDispatcher) {
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()

            // User prompts scout
            viewModel.sendMessage("@scout please review the specs")
            testScheduler.runCurrent()

            // Scout finishes turn and tags @coder
            eventsFlow.emit(
                WsEvent.MessageComplete(
                    text = "Specs look good, @coder please implement this!",
                    sessionId = "session-scout-1",
                ),
            )
            testScheduler.runCurrent()

            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "scout",
                viewModel.uiState.value.messages[1]
                    .senderName,
            )

            // Continuation pass triggers coder
            eventsFlow.emit(
                WsEvent.MessageComplete(
                    text = "On it! Writing unit tests now.",
                    sessionId = "session-coder-1",
                ),
            )
            testScheduler.runCurrent()

            val messages = viewModel.uiState.value.messages
            assertEquals(3, messages.size)
            assertEquals("coder", messages[2].senderName)
            assertEquals("On it! Writing unit tests now.", messages[2].text)
        }

    @Test
    fun testSendMessage_passiveListenerWhenLeaseHeldByOtherDevice() =
        runTest(testDispatcher) {
            val activeLeaseSnapshot =
                GroupChatSyncSnapshot(
                    version = 3,
                    rooms =
                        mapOf(
                            "name:Dev Team" to
                                GroupChatRoomMeta(
                                    name = "Dev Team",
                                    members = listOf(JsonPrimitive("scout"), JsonPrimitive("coder")),
                                    lease =
                                        GroupChatRoomLease(
                                            driverId = "desktop_client_99",
                                            expiresAt = System.currentTimeMillis() + 60_000L,
                                        ),
                                ),
                        ),
                )

            val defaultProfileWithLease =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta = mapOf("hermes-bots-groups" to json.encodeToJsonElement(activeLeaseSnapshot)),
                )

            coEvery { mockApi.getProfiles() } returns
                Response.success(
                    ProfilesResponse(
                        profiles = listOf(defaultProfileWithLease, botA, botB),
                    ),
                )

            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            advanceUntilIdle()

            viewModel.sendMessage("@scout check this")
            testScheduler.runCurrent()

            // Because lease is held by desktop_client_99, mobile only posts the user message and yields
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertTrue(
                viewModel.uiState.value.messages
                    .first()
                    .isUser,
            )
        }

    @Test
    fun testLoadGroup_loadsCustomPerGroupLimits() =
        runTest(testDispatcher) {
            val customLimitsSnapshot =
                GroupChatSyncSnapshot(
                    version = 3,
                    rooms =
                        mapOf(
                            "name:Dev Team" to
                                GroupChatRoomMeta(
                                    name = "Dev Team",
                                    members = listOf(JsonPrimitive("scout"), JsonPrimitive("coder")),
                                    maxBotMessages = 12,
                                    maxContinuationPasses = 5,
                                    systemPrompt = "You are elite software architects.",
                                ),
                        ),
                )

            val defaultProfileWithCustomLimits =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta = mapOf("hermes-bots-groups" to json.encodeToJsonElement(customLimitsSnapshot)),
                )

            coEvery { mockApi.getProfiles() } returns
                Response.success(
                    ProfilesResponse(
                        profiles = listOf(defaultProfileWithCustomLimits, botA, botB),
                    ),
                )

            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(12, state.maxBotMessages)
            assertEquals(5, state.maxContinuationPasses)
            assertEquals("You are elite software architects.", state.systemPrompt)
        }

    @Test
    fun testUpdateGroupLimits_updatesStateAndClamps() =
        runTest(testDispatcher) {
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            advanceUntilIdle()

            viewModel.updateGroupLimits(
                maxMessages = 15,
                maxPasses = 4,
                systemPrompt = "Custom instructions for this group",
            )
            testScheduler.runCurrent()

            assertEquals(15, viewModel.uiState.value.maxBotMessages)
            assertEquals(4, viewModel.uiState.value.maxContinuationPasses)
            assertEquals("Custom instructions for this group", viewModel.uiState.value.systemPrompt)

            // Extended limits within new range
            viewModel.updateGroupLimits(
                maxMessages = 45,
                maxPasses = 18,
            )
            testScheduler.runCurrent()
            assertEquals(45, viewModel.uiState.value.maxBotMessages)
            assertEquals(18, viewModel.uiState.value.maxContinuationPasses)

            // Exceeding ceiling clamps to MAX_ALLOWED_BOT_MESSAGES (50) and MAX_ALLOWED_CONTINUATION_PASSES (20)
            viewModel.updateGroupLimits(
                maxMessages = 999,
                maxPasses = 999,
            )
            testScheduler.runCurrent()
            assertEquals(MAX_ALLOWED_BOT_MESSAGES, viewModel.uiState.value.maxBotMessages)
            assertEquals(MAX_ALLOWED_CONTINUATION_PASSES, viewModel.uiState.value.maxContinuationPasses)

            // Under floor clamps to 1 and 0
            viewModel.updateGroupLimits(
                maxMessages = -10,
                maxPasses = -5,
            )
            testScheduler.runCurrent()
            assertEquals(1, viewModel.uiState.value.maxBotMessages)
            assertEquals(0, viewModel.uiState.value.maxContinuationPasses)
        }

    @Test
    fun testBuildTurnPrompt_includesCustomRoomInstructions() {
        val prompt =
            GroupChatMentions.buildTurnPrompt(
                groupName = "Dev Team",
                viewer = botA,
                peers = listOf(botB),
                recentLog =
                    listOf(
                        GroupChatMessage(
                            id = "1",
                            senderName = "user",
                            senderDisplayName = "User",
                            isUser = true,
                            text = "hello team",
                        ),
                    ),
                customRoomPrompt = "You are sarcastic AI buddies.",
            )

        assertTrue(prompt.contains("Room Instructions:\nYou are sarcastic AI buddies."))
        assertTrue(prompt.contains("@scout (Scout Bot)"))
        assertTrue(prompt.contains("@coder (Dev Bot)"))
    }

    @Test
    fun testToolEvents_rendersAndCompletesToolCall() =
        runTest(testDispatcher) {
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()

            viewModel.sendMessage("@scout check time")
            testScheduler.runCurrent()

            // Emit ToolStart
            eventsFlow.emit(
                WsEvent.ToolStart(
                    name = "terminal",
                    data = mapOf("command" to "date", "tool_id" to "tool-123"),
                    sessionId = "session-scout-1",
                ),
            )
            testScheduler.runCurrent()

            val msgWithTool =
                viewModel.uiState.value.messages
                    .last()
            assertEquals(1, msgWithTool.toolCalls.size)
            val toolCall = msgWithTool.toolCalls.first()
            assertEquals("terminal", toolCall.name)
            assertEquals("date", toolCall.summary)
            assertEquals("date", toolCall.command)
            assertTrue(toolCall.isRunning)

            // Emit ToolComplete
            eventsFlow.emit(
                WsEvent.ToolComplete(
                    name = "terminal",
                    data =
                        mapOf(
                            "tool_id" to "tool-123",
                            "output" to "Sat Aug 29 03:30:00 UTC 2026",
                            "exit_code" to 0,
                        ),
                    sessionId = "session-scout-1",
                ),
            )
            testScheduler.runCurrent()

            val msgAfterToolDone =
                viewModel.uiState.value.messages
                    .last()
            val completedTool = msgAfterToolDone.toolCalls.first()
            assertFalse(completedTool.isRunning)
            assertEquals("Sat Aug 29 03:30:00 UTC 2026", completedTool.output)
            assertEquals(0, completedTool.exitCode)
            assertFalse(completedTool.isError)

            // Complete message
            eventsFlow.emit(
                WsEvent.MessageComplete(
                    text = "Current time is Sat Aug 29",
                    sessionId = "session-scout-1",
                ),
            )
            testScheduler.runCurrent()

            val finalMsg =
                viewModel.uiState.value.messages
                    .last()
            assertEquals("Current time is Sat Aug 29", finalMsg.text)
            assertEquals(1, finalMsg.toolCalls.size)
            assertFalse(finalMsg.toolCalls.first().isRunning)
        }

    @Test
    fun testStopCommand_stopsGenerationAndAppendsSystemBanner() =
        runTest(testDispatcher) {
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()

            // Trigger a message
            viewModel.sendMessage("@scout long task")
            testScheduler.runCurrent()

            // Emit a streaming fragment
            eventsFlow.emit(
                WsEvent.MessageToken(
                    token = "Thinking about...",
                    sessionId = "session-scout-1",
                ),
            )
            testScheduler.runCurrent()

            // Execute /stop command
            viewModel.sendMessage("/stop")
            testScheduler.runCurrent()

            val state = viewModel.uiState.value
            assertNull(state.activeSpeaker)
            val lastMsg = state.messages.last()
            assertTrue(lastMsg.isSystem)
            assertEquals("Discussion stopped", lastMsg.text)
        }

    @Test
    fun testDefaultProfileFourMembersReceiveMentionlessAndAllInput() =
        runTest(testDispatcher) {
            val members = listOf("boss", "idea1", "idea2", "deny").map { ProfileInfo(name = it) }
            val snapshot =
                GroupChatSyncSnapshot(
                    version = 3,
                    rooms =
                        mapOf(
                            "name:Four" to
                                GroupChatRoomMeta(name = "Four", members = members.map { JsonPrimitive(it.name) }),
                        ),
                )
            val default =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta = mapOf("hermes-bots-groups" to json.encodeToJsonElement(snapshot)),
                )
            coEvery { mockApi.getProfiles() } returns
                Response.success(ProfilesResponse(profiles = listOf(default) + members))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            for (input in listOf("hello team", "@all second input")) {
                vm.sendMessage(input)
                testScheduler.runCurrent()
                for (member in members) {
                    assertEquals(member.name, vm.uiState.value.activeSpeaker)
                    eventsFlow.emit(
                        WsEvent.MessageComplete(
                            text = "reply by ${member.name}",
                            sessionId = "session-${member.name}-${transport.creates.count { it == member.name }}",
                        ),
                    )
                    testScheduler.runCurrent()
                }
                assertFalse(vm.uiState.value.isSending)
                assertEquals(
                    members.map { it.name }.associateWith { GroupDelivery.COMPLETE },
                    vm.uiState.value.messages
                        .last { it.isUser }
                        .deliveries,
                )
            }
            assertEquals(
                listOf(
                    "boss",
                    "idea1",
                    "idea2",
                    "deny",
                    "boss",
                    "idea1",
                    "idea2",
                    "deny",
                ),
                transport.submissions.map {
                    it.first
                },
            )
        }

    @Test
    fun testBlankLegacyMemberDoesNotBlockFourBotsAndKeepsRawRosterAndHistory() =
        runTest(testDispatcher) {
            val names = listOf("boss", "idea1", "idea2", "deny")
            val rawMembers = names.map { JsonPrimitive(it) } + JsonPrimitive("")
            val oldLog =
                GroupChatSyncLogEntry(
                    id = "legacy-history",
                    from = GroupChatSyncFrom(kind = "member", name = "boss"),
                    text = "preserved synthetic history",
                    at = 1L,
                )
            val room = GroupChatRoomMeta(name = "Four", members = rawMembers, log = listOf(oldLog), revision = 7)
            val snapshot = GroupChatSyncSnapshot(version = 3, rooms = mapOf("name:Four" to room))
            val default =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta = mapOf("hermes-bots-groups" to json.encodeToJsonElement(snapshot)),
                )
            coEvery { mockApi.getProfiles() } returns
                Response.success(ProfilesResponse(profiles = listOf(default) + names.map { ProfileInfo(name = it) }))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            assertEquals(
                names,
                vm.uiState.value.members
                    .map { it.name },
            )
            assertEquals(
                "preserved synthetic history",
                vm.uiState.value.messages
                    .single()
                    .text,
            )
            assertTrue(transport.submissions.isEmpty())
            for ((input, expected) in listOf("hello team" to names, "@boss second input" to listOf("boss"))) {
                vm.sendMessage(input)
                testScheduler.runCurrent()
                for (member in expected) {
                    assertEquals(member, vm.uiState.value.activeSpeaker)
                    completeTurn(member, "reply $member")
                }
                assertFalse(vm.uiState.value.isSending)
                assertNull(vm.uiState.value.sendIssue)
                assertEquals(
                    expected.associateWith { GroupDelivery.COMPLETE },
                    vm.uiState.value.messages
                        .last { it.isUser }
                        .deliveries,
                )
            }
            assertEquals(names + "boss", transport.submissions.map { it.first })
            assertTrue(
                vm.uiState.value.messages
                    .any { it.id == "legacy-history" },
            )
            val written = json.decodeFromJsonElement<GroupChatSyncSnapshot>(transport.snapshots.first().toJsonElement())
            assertEquals(rawMembers, written.rooms!!["name:Four"]!!.members)
            assertEquals(listOf(oldLog), written.rooms!!["name:Four"]!!.log)
            assertEquals(7L, written.rooms!!["name:Four"]!!.revision)
            vm.leaveGroup("Four")
            testScheduler.runCurrent()
        }

    @Test
    fun unknownGroupNeverSeatsAllBotsOrCreatesAConversation() =
        runTest(testDispatcher) {
            val vm = GroupChatViewModel("Unknown Group", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            assertTrue(vm.uiState.value.members.isEmpty())
            assertEquals(GroupSendIssue.INVALID_MEMBERS, vm.uiState.value.sendIssue)
            vm.sendMessage("@all synthetic input")
            testScheduler.runCurrent()
            assertTrue(transport.creates.isEmpty())
            assertTrue(transport.submissions.isEmpty())
            assertTrue(transport.snapshots.isEmpty())
        }

    @Test
    fun testAllMalformedMembersNeverFallBackToDefaultOrEraseHistory() =
        runTest(testDispatcher) {
            val room =
                json.decodeFromString<GroupChatRoomMeta>(
                    """{"name":"Four","members":["",null,{},123],"log":[{"id":"old","text":"kept"}]}""",
                )
            val snapshot = GroupChatSyncSnapshot(rooms = mapOf("name:Four" to room))
            val default =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta = mapOf("hermes-bots-groups" to json.encodeToJsonElement(snapshot)),
                )
            coEvery { mockApi.getProfiles() } returns
                Response.success(ProfilesResponse(listOf(default, ProfileInfo(name = "boss"))))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            assertTrue(
                vm.uiState.value.members
                    .isEmpty(),
            )
            assertEquals(
                "kept",
                vm.uiState.value.messages
                    .single()
                    .text,
            )
            assertEquals(GroupSendIssue.INVALID_MEMBERS, vm.uiState.value.sendIssue)
            vm.sendMessage("@all synthetic input")
            testScheduler.runCurrent()
            assertTrue(transport.creates.isEmpty())
            assertTrue(transport.submissions.isEmpty())
            assertTrue(transport.snapshots.isEmpty())
        }

    @Test
    fun testMissingProfileHasDistinctFailureAndCannotRouteToDefault() =
        runTest(testDispatcher) {
            val room =
                GroupChatRoomMeta(name = "Four", members = listOf(JsonPrimitive("boss"), JsonPrimitive("missing-peer")))
            val snapshot = GroupChatSyncSnapshot(rooms = mapOf("name:Four" to room))
            val default =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta =
                        mapOf("hermes-bots-groups" to json.encodeToJsonElement(snapshot)),
                )
            coEvery { mockApi.getProfiles() } returns
                Response.success(ProfilesResponse(listOf(default, ProfileInfo(name = "boss"))))
            val vm = GroupChatViewModel("Four", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            assertEquals(
                listOf("boss", "missing-peer"),
                vm.uiState.value.members
                    .map { it.name },
            )
            vm.sendMessage("@boss synthetic input")
            testScheduler.runCurrent()
            assertEquals(GroupSendIssue.MEMBERS_MISSING, vm.uiState.value.sendIssue)
            assertTrue(transport.creates.isEmpty())
            assertTrue(transport.submissions.isEmpty())
            assertTrue(transport.snapshots.isEmpty())
            assertEquals(
                mapOf("boss" to GroupDelivery.NOT_SENT),
                vm.uiState.value.messages
                    .last()
                    .deliveries,
            )
        }

    @Test
    fun testRefreshedProfileListFailureDiffersFromSocketFailure() =
        runTest(testDispatcher) {
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(emptyList()))
            vm.sendMessage("synthetic input")
            testScheduler.runCurrent()
            assertEquals(GroupSendIssue.PROFILE_LIST_UNAVAILABLE, vm.uiState.value.sendIssue)
            assertTrue(transport.creates.isEmpty())
            assertTrue(transport.submissions.isEmpty())
            assertTrue(transport.snapshots.isEmpty())
        }

    @Test
    fun testPartialCreateFailureIsVisibleAndOtherMemberStillResponds() =
        runTest(testDispatcher) {
            transport.createFailures.add("scout")
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("hello team")
            testScheduler.runCurrent()
            assertEquals("Dev Bot", vm.uiState.value.activeSpeaker)
            eventsFlow.emit(WsEvent.MessageComplete(text = "@scout response", sessionId = "session-coder-1"))
            testScheduler.runCurrent()
            assertEquals(listOf("coder"), transport.submissions.map { it.first })
            assertEquals(1, transport.creates.count { it == "scout" })
            assertEquals(
                mapOf("scout" to GroupDelivery.NOT_SENT, "coder" to GroupDelivery.COMPLETE),
                vm.uiState.value.messages
                    .first()
                    .deliveries,
            )
            assertEquals(GroupSendIssue.MEMBER_FAILED, vm.uiState.value.sendIssue)
            assertNull(vm.uiState.value.activeSpeaker)
            assertFalse(vm.uiState.value.isSending)
        }

    @Test
    fun testUnknownSubmissionIsNotRecreatedOrIncludedInFreshInput() =
        runTest(testDispatcher) {
            transport.submitFailure = HermesWsClient.HermesRpcException("SYNTHETIC_PRIVATE_ack_body")
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout first-unknown-private-input")
            testScheduler.runCurrent()
            assertEquals(1, transport.creates.size)
            assertEquals(1, transport.submissions.size)
            assertEquals(
                GroupDelivery.UNKNOWN,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            assertEquals(GroupSendIssue.UNKNOWN, vm.uiState.value.sendIssue)
            transport.submitFailure = null
            vm.sendMessage("blocked while unresolved")
            testScheduler.runCurrent()
            assertEquals(1, transport.submissions.size)
            vm.newConversation()
            vm.sendMessage("@scout fresh-approved-input")
            testScheduler.runCurrent()
            assertFalse(
                transport.submissions
                    .last()
                    .second
                    .contains("first-unknown-private-input"),
            )
            eventsFlow.emit(WsEvent.MessageComplete(text = "fresh response", sessionId = "session-scout-2"))
            testScheduler.runCurrent()
            assertEquals(2, transport.submissions.size)
        }

    @Test
    fun testNotSentSubmitDoesNotRetryAndClearsSpeaker() =
        runTest(testDispatcher) {
            transport.submitFailure = HermesWsClient.HermesRpcException("Request not sent", OfflineSendPolicy.NOT_SENT)
            transport.submitAccepted = false
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout hello")
            testScheduler.runCurrent()
            assertEquals(
                GroupDelivery.NOT_SENT,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            assertEquals(1, transport.submissions.size)
            assertEquals(1, transport.creates.size)
            assertNull(vm.uiState.value.activeSpeaker)
            assertFalse(vm.uiState.value.isSending)
        }

    @Test
    fun testLeaseWriteFailureStopsBeforeAnySessionOrPrompt() =
        runTest(testDispatcher) {
            transport.configureFailure = IllegalStateException("SYNTHETIC_PRIVATE_lease_body")
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("hello")
            testScheduler.runCurrent()
            assertTrue(transport.creates.isEmpty())
            assertTrue(transport.submissions.isEmpty())
            assertEquals(GroupSendIssue.LEASE_UNCONFIRMED, vm.uiState.value.sendIssue)
            assertTrue(
                vm.uiState.value.messages
                    .first()
                    .deliveries.values
                    .all { it == GroupDelivery.NOT_SENT },
            )
            assertFalse(vm.uiState.value.isSending)
        }

    @Test
    fun testOtherClientLeaseIsNeverOverwritten() =
        runTest(testDispatcher) {
            val snapshot =
                GroupChatSyncSnapshot(
                    version = 3,
                    rooms =
                        mapOf(
                            "name:Dev Team" to
                                GroupChatRoomMeta(
                                    name = "Dev Team",
                                    members = listOf(JsonPrimitive("scout"), JsonPrimitive("coder")),
                                    lease =
                                        GroupChatRoomLease(
                                            driverId = "other-client",
                                            expiresAt =
                                                System.currentTimeMillis() + 60_000L,
                                        ),
                                ),
                        ),
                )
            val default =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta = mapOf("hermes-bots-groups" to json.encodeToJsonElement(snapshot)),
                )
            coEvery { mockApi.getProfiles() } returns
                Response.success(ProfilesResponse(profiles = listOf(default, botA, botB)))
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@all hello")
            testScheduler.runCurrent()
            assertTrue(transport.snapshots.isEmpty())
            assertTrue(transport.creates.isEmpty())
            assertEquals(GroupSendIssue.LEASE_HELD, vm.uiState.value.sendIssue)
            assertFalse(vm.uiState.value.isSending)
        }

    @Test
    fun testCancelPreservesPartialTextAndInterruptsOnlyOwnedRuntime() =
        runTest(testDispatcher) {
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout hello")
            testScheduler.runCurrent()
            eventsFlow.emit(WsEvent.MessageToken(token = "partial confirmed text", sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            vm.stopGeneration()
            testScheduler.runCurrent()
            assertEquals(listOf("session-scout-1"), transport.interrupts)
            assertTrue(
                vm.uiState.value.messages.any {
                    it.text == "partial confirmed text" && it.isIncomplete &&
                        !it.isStreaming
                },
            )
            assertEquals(
                GroupDelivery.UNKNOWN,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            eventsFlow.emit(WsEvent.MessageComplete(text = "late response", sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            assertFalse(
                vm.uiState.value.messages
                    .any { it.text == "late response" },
            )
        }

    @Test
    fun testReconnectCancelsUnknownTurnWithoutReplay() =
        runTest(testDispatcher) {
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout old input")
            testScheduler.runCurrent()
            transport.connectionScope.value = transport.connectionScope.value!!.copy(generation = 2)
            testScheduler.runCurrent()
            assertFalse(vm.uiState.value.isSending)
            assertEquals(GroupSendIssue.CONNECTION_CHANGED, vm.uiState.value.sendIssue)
            assertEquals(
                GroupDelivery.UNKNOWN,
                vm.uiState.value.messages
                    .first()
                    .deliveries["scout"],
            )
            assertEquals(1, transport.submissions.size)
            vm.sendMessage("blocked while unresolved")
            testScheduler.runCurrent()
            assertEquals(1, transport.submissions.size)
            vm.newConversation()
            vm.sendMessage("@scout new approved input")
            testScheduler.runCurrent()
            assertFalse(
                transport.submissions
                    .last()
                    .second
                    .contains("old input"),
            )
            eventsFlow.emit(WsEvent.MessageComplete(text = "new response", sessionId = "session-scout-2"))
            testScheduler.runCurrent()
            assertEquals(2, transport.submissions.size)
        }

    @Test
    fun testWrongSessionEventsAndRepeatedSendCannotCrossCurrentTurn() =
        runTest(testDispatcher) {
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout one input")
            testScheduler.runCurrent()
            vm.sendMessage("duplicate while busy")
            eventsFlow.emit(WsEvent.MessageComplete(text = "unrelated response", sessionId = "ordinary-session"))
            testScheduler.runCurrent()
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.isUser },
            )
            assertFalse(
                vm.uiState.value.messages
                    .any { it.text == "unrelated response" },
            )
            eventsFlow.emit(WsEvent.MessageComplete(text = "correct response", sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            assertEquals(1, transport.submissions.size)
            assertFalse(vm.uiState.value.isSending)
        }

    @Test
    fun testGroupSwitchCancelsOldStateAndLateResponse() =
        runTest(testDispatcher) {
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout old group")
            testScheduler.runCurrent()
            vm.setGroup("New Group")
            testScheduler.runCurrent()
            eventsFlow.emit(WsEvent.MessageComplete(text = "late old group response", sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            assertEquals("New Group", vm.uiState.value.groupName)
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            assertFalse(vm.uiState.value.isSending)
        }

    @Test
    fun testLeavingGroupRetainsPartialAndRequiresFreshInputOnReturn() =
        runTest(testDispatcher) {
            val vm = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher, groupTransport = transport)
            testScheduler.runCurrent()
            vm.sendMessage("@scout unfinished-old-input")
            testScheduler.runCurrent()
            eventsFlow.emit(WsEvent.MessageToken(token = "partial observed text", sessionId = "session-scout-1"))
            testScheduler.runCurrent()
            vm.leaveGroup("Dev Team")
            testScheduler.runCurrent()
            vm.setGroup("Dev Team")
            testScheduler.runCurrent()
            assertFalse(vm.uiState.value.isSending)
            assertEquals(1, transport.submissions.size)
            assertEquals(listOf("session-scout-1"), transport.interrupts)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.text == "partial observed text" && it.isIncomplete },
            )
            vm.sendMessage("blocked while unresolved")
            testScheduler.runCurrent()
            assertEquals(1, transport.submissions.size)
            vm.newConversation()
            vm.sendMessage("@scout fresh-return-input")
            testScheduler.runCurrent()
            assertFalse(
                transport.submissions
                    .last()
                    .second
                    .contains("unfinished-old-input"),
            )
            assertFalse(
                transport.submissions
                    .last()
                    .second
                    .contains("partial observed text"),
            )
            eventsFlow.emit(WsEvent.MessageComplete(text = "fresh complete response", sessionId = "session-scout-2"))
            testScheduler.runCurrent()
            assertEquals(2, transport.submissions.size)
            assertFalse(vm.uiState.value.isSending)
        }
}

private class FakeGroupTransport(
    override val events: MutableSharedFlow<WsEvent>,
) : GroupSessionTransport {
    override val connectionScope =
        MutableStateFlow<ConnectionScope?>(ConnectionScope("synthetic", null, "default", "test", 1))
    override val readReadiness = MutableStateFlow(true)
    private var access: GroupSessionAccess? = null
    override val isCurrent get() = access?.scope == connectionScope.value && access != null
    val creates = mutableListOf<String>()
    val submissions = mutableListOf<Pair<String, String>>()
    val interrupts = mutableListOf<String>()
    val snapshots = mutableListOf<Map<String, Any?>>()
    val createFailures = mutableSetOf<String>()
    var submitFailure: Exception? = null
    var submitResponse: CompletableDeferred<Any?>? = null
    var onSubmit: (() -> Unit)? = null
    var submitAccepted = true
    var configureFailure: Exception? = null
    var uniqueSessions = true
    var historyRows: List<SessionMessage>? = null
    var historyIssue: GroupHistoryIssue? = null
    var historyComplete = true
    var historyResponse: CompletableDeferred<List<SessionMessage>?>? = null
    val historyReads = mutableListOf<Pair<String, String>>()

    override suspend fun readHistory(
        member: String,
        storedSessionId: String,
    ): GroupHistoryRead =
        historyIssue?.let { GroupHistoryRead(issue = it) }
            ?: super.readHistory(member, storedSessionId).copy(completeHistory = historyComplete)

    override suspend fun history(
        member: String,
        storedSessionId: String,
    ): List<SessionMessage>? {
        historyReads.add(member to storedSessionId)
        return historyResponse?.await() ?: historyRows
    }

    override fun open(
        room: String,
        members: Set<String>,
        metadataProfile: String,
    ): Boolean {
        if (!readReadiness.value) return false
        access = GroupSessionAccess(requireNotNull(connectionScope.value), room, members, metadataProfile)
        return true
    }

    override suspend fun create(member: String): GroupMemberSession {
        creates.add(member)
        if (member in createFailures) throw IllegalStateException("Synthetic create failure")
        val index = if (uniqueSessions) creates.count { it == member } else 1
        return GroupMemberSession(member, "session-$member-$index", "stored-$member-$index", requireNotNull(access))
    }

    override fun submit(
        session: GroupMemberSession,
        text: String,
    ): ScopedRpcSubmission {
        submissions.add(session.member to text)
        val result =
            submitResponse ?: CompletableDeferred<Any?>().also { deferred ->
                submitFailure?.let { deferred.completeExceptionally(it) } ?: deferred.complete(emptyMap<String, Any>())
            }
        onSubmit?.invoke()
        return ScopedRpcSubmission("synthetic", submitAccepted, result)
    }

    override fun interrupt(session: GroupMemberSession): ScopedRpcSubmission {
        interrupts.add(session.runtimeSessionId)
        return ScopedRpcSubmission("synthetic", true, CompletableDeferred(emptyMap<String, Any>()))
    }

    override suspend fun configureSnapshot(snapshot: Map<String, Any?>) {
        configureFailure?.let { throw it }
        snapshots.add(snapshot)
    }

    override fun close() {
        access = null
    }
}
