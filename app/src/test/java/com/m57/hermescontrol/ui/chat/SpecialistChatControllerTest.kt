package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SpecialistCapabilitiesResponse
import com.m57.hermescontrol.data.model.SpecialistPendingTurn
import com.m57.hermescontrol.data.model.SpecialistSettingsPreview
import com.m57.hermescontrol.data.model.SpecialistSetupApplyRequest
import com.m57.hermescontrol.data.model.SpecialistSetupApplyResponse
import com.m57.hermescontrol.data.model.SpecialistSetupConfirmRequest
import com.m57.hermescontrol.data.model.SpecialistSetupDraft
import com.m57.hermescontrol.data.model.SpecialistSetupMessage
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.data.model.SpecialistSetupTurn
import com.m57.hermescontrol.data.model.SpecialistSetupTurnRequest
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SpecialistChatControllerTest {
    private val target = SpecialistChatTarget("server-a:bot", 2L, "bot")
    private val greeting = SpecialistSetupMessage("specialist-greeting:bot", "assistant", "何を担当しましょうか？")
    private val initial = SpecialistSetupSnapshot("bot", greeting = greeting)
    private val setupDraft = SpecialistSetupDraft("draft-setup", description = "技術調査", autonomy = "外部送信は確認")
    private val supported =
        SpecialistCapabilitiesResponse(setup_conversation = true, setup_apply = true, edit_conversation = true)
    private val api = mockk<HermesApiService>()
    private val storage = mutableMapOf<String, SpecialistChatRequest>()
    private var currentTarget = target
    private var snapshot = initial
    private var resolveCount = 0

    private fun TestScope.controller(): SpecialistChatController =
        SpecialistChatController(
            scope = this,
            repository = SpecialistsRepository { api },
            isCurrent = { it == currentTarget },
            readRequest = { storage[it] },
            writeRequest = { scope, request ->
                if (request == null) storage.remove(scope) else storage[scope] = request
            },
            resolveSession = { resolveCount++ },
            refreshRoster = {},
            nowSeconds = { 100.0 },
        )

    private fun setUpApi() {
        coEvery { api.getSpecialistCapabilities(any()) } returns Response.success(supported)
        coEvery { api.getSpecialistSetup("bot", any()) } answers { Response.success(snapshot) }
    }

    @Test
    fun greetingTurnConfirmAndApplyUseOneBotChat() =
        runTest {
            setUpApi()
            val gate = CompletableDeferred<Unit>()
            coEvery { api.getSpecialistSetup("bot", any()) } coAnswers {
                gate.await()
                Response.success(snapshot)
            }
            val chat = controller()
            chat.bind(target, null, seed = initial)
            runCurrent()
            assertEquals(
                greeting.content,
                chat.state.value
                    .projectMessages(emptyList())
                    .first()
                    .content,
            )
            assertTrue(chat.state.value.showsFooter)
            assertFalse(chat.state.value.canEnterEdit)
            assertFalse(chat.submit("まだ正規会話がない"))
            gate.complete(Unit)
            runCurrent()
            chat.bind(target, "bot-chat")
            runCurrent()

            coEvery { api.sendSpecialistSetupTurn("bot", any(), any()) } answers {
                val request = secondArg<SpecialistSetupTurnRequest>()
                assertEquals("bot-chat", request.session_id)
                assertEquals(0, request.expected_revision)
                assertEquals(0, request.expected_conversation_revision)
                assertEquals("setup", request.mode)
                val turn = request.result(setupDraft, 1, 1)
                snapshot =
                    initial.copy(
                        revision = 1,
                        draft = setupDraft,
                        conversation_revision = 1,
                        conversation_turns = listOf(turn),
                    )
                Response.success(turn)
            }
            assertTrue(chat.submit("技術調査を担当して"))
            assertFalse(chat.state.value.canAct)
            runCurrent()
            assertEquals(setupDraft, chat.state.value.activeDraft)
            assertFalse(chat.state.value.showsFooter)
            assertEquals(
                1,
                chat.state.value
                    .projectMessages(emptyList())
                    .count { it.id == SPECIALIST_SETUP_REVIEW },
            )
            assertNull(chat.state.value.request)

            coEvery { api.confirmSpecialistSetup("bot", any(), any()) } answers {
                val request = secondArg<SpecialistSetupConfirmRequest>()
                assertEquals("draft-setup", request.draft_id)
                assertEquals(1, request.expected_revision)
                snapshot =
                    snapshot.copy(
                        setup_completed = true,
                        activation = "pending_apply",
                        revision = 2,
                        settings_revision = 2,
                    )
                Response.success(snapshot.copy(conversation_turns = emptyList()))
            }
            chat.confirm(setupDraft.id, 0)
            coVerify(exactly = 0) { api.confirmSpecialistSetup(any(), any(), any()) }
            chat.confirm(setupDraft.id, 1)
            runCurrent()
            assertTrue(chat.state.value.pendingApply)
            assertTrue(chat.state.value.showsFooter)
            assertTrue(chat.state.value.blocksNormalInput)
            assertFalse(
                chat.state.value.snapshot!!
                    .routing_ready,
            )
            assertNull(chat.state.value.activeDraft)

            coEvery { api.applySpecialistSetup("bot", any(), any()) } answers {
                val request = secondArg<SpecialistSetupApplyRequest>()
                assertEquals("bot-chat", request.session_id)
                assertEquals(2, request.expected_revision)
                snapshot = snapshot.copy(activation = "applied", applied_revision = 2, routing_ready = true)
                Response.success(SpecialistSetupApplyResponse("applied", "bot-chat", 2, 2))
            }
            chat.apply()
            runCurrent()
            assertFalse(chat.state.value.blocksNormalInput)
            assertFalse(chat.state.value.showsFooter)
            assertTrue(chat.state.value.canEnterEdit)
            chat.setEditing(true)
            assertEquals("edit", chat.state.value.turnMode)
            assertTrue(chat.state.value.showsFooter)
            chat.setEditing(false)
            assertFalse(chat.state.value.showsFooter)
            assertTrue(
                chat.state.value.snapshot!!
                    .routing_ready,
            )
            assertEquals(
                2,
                chat.state.value.snapshot
                    ?.applied_revision,
            )
            assertEquals("bot-chat", chat.state.value.sessionId)

            val restored = controller()
            restored.bind(target, "bot-chat")
            runCurrent()
            val resumed = restored.state.value.projectMessages(emptyList())
            assertEquals(listOf(greeting.content, "技術調査を担当して", "下書きを作成しました"), resumed.take(3).map { it.content })
            assertEquals(resumed.map { it.id }.distinct().size, resumed.size)
        }

    @Test
    fun completedSetupFooterOnlyShowsWhenThereIsSomethingToHandle() {
        val completed =
            SpecialistChatState(
                sessionId = "bot-chat",
                capabilities = supported,
                snapshot = initial.copy(setup_completed = true, activation = "applied", applied_revision = 3),
            )
        assertFalse(completed.showsFooter)
        assertFalse(completed.copy(loading = true).showsFooter)
        val waitingForNextSession = completed.snapshot?.copy(activation = "next_session_boundary")
        assertFalse(completed.copy(snapshot = waitingForNextSession).showsFooter)
        assertTrue(completed.copy(sessionId = null).showsFooter)
        assertTrue(completed.copy(error = "uncertain").showsFooter)
        assertTrue(completed.copy(busy = true).showsFooter)
        assertTrue(completed.copy(request = SpecialistChatRequest(rejected = true)).showsFooter)
        val pending = SpecialistPendingTurn("pending", "設定を変更して", 0.0)
        val withPending = completed.copy(snapshot = completed.snapshot?.copy(pending_turn = pending))
        assertTrue(withPending.showsFooter)
        assertFalse(withPending.copy(dismissedPendingId = pending.request_id).showsFooter)
    }

    @Test
    fun editToolRefreshesServerDiffAndUsesEditConfirm() =
        runTest {
            setUpApi()
            snapshot =
                initial.copy(
                    setup_completed = true,
                    revision = 2,
                    activation = "applied",
                    applied_revision = 2,
                    settings_revision = 2,
                    routing_ready = true,
                )
            val chat = controller()
            chat.bind(target, "bot-chat")
            runCurrent()
            assertFalse(
                chat.state.value
                    .projectMessages(emptyList())
                    .any { it.content == greeting.content },
            )
            val before = SpecialistSettingsPreview(description = "技術調査", skills = listOf("research"))
            val after = before.copy(skills = listOf("notes"))
            val proposal = SpecialistSetupDraft("edit-id", kind = "edit", before = before, after = after)
            snapshot = snapshot.copy(revision = 3, draft = proposal)
            chat.onEditProposal(
                SpecialistEditReference(true, "specialist_edit_proposal", proposal.id, 3, "unrelated-chat"),
            )
            runCurrent()
            assertNull(chat.state.value.activeDraft)

            val ref =
                SpecialistEditReference.parse(
                    """
                    {"ok":true,"kind":"specialist_edit_proposal","draft_id":"edit-id","revision":3,"session_id":"bot-chat"}
                    """.trimIndent(),
                )!!
            snapshot = snapshot.copy(draft = proposal.copy(id = "different-id"))
            chat.onEditProposal(ref)
            runCurrent()
            assertNull(chat.state.value.activeDraft)
            snapshot = snapshot.copy(draft = proposal)
            chat.refresh()
            runCurrent()
            assertFalse(chat.state.value.editing)
            assertTrue(chat.state.value.showsFooter)
            assertFalse(chat.state.value.showsFooterActions)
            assertEquals(
                before,
                chat.state.value.activeDraft
                    ?.before,
            )
            assertEquals(
                after,
                chat.state.value.activeDraft
                    ?.after,
            )
            coEvery { api.confirmSpecialistEdit("bot", any(), any()) } answers {
                val request = secondArg<SpecialistSetupConfirmRequest>()
                assertEquals(3, request.expected_revision)
                assertEquals(proposal.id, request.draft_id)
                snapshot = snapshot.copy(revision = 4, settings_revision = 4, activation = "pending_apply")
                Response.success(snapshot)
            }
            chat.confirm(proposal.id, 3)
            runCurrent()
            coVerify(exactly = 0) { api.confirmSpecialistSetup(any(), any(), any()) }
            assertTrue(chat.state.value.pendingApply)
        }

    @Test
    fun staleRevisionKeepsInputAndResendsOnlyWithNewVersionAndId() =
        runTest {
            setUpApi()
            val requests = mutableListOf<SpecialistSetupTurnRequest>()
            coEvery { api.sendSpecialistSetupTurn("bot", capture(requests), any()) } answers {
                if (requests.size == 1) {
                    snapshot = initial.copy(revision = 1)
                    failure(409, "stale_setup_revision")
                } else {
                    val turn = requests.last().result(setupDraft, 2, 1)
                    snapshot =
                        snapshot.copy(
                            revision = 2,
                            draft = setupDraft,
                            conversation_revision = 1,
                            conversation_turns = listOf(turn),
                        )
                    Response.success(turn)
                }
            }
            val chat = controller()
            chat.bind(target, "bot-chat")
            runCurrent()
            assertTrue(chat.submit("公開資料の調査"))
            runCurrent()
            assertEquals("stale_setup_revision", chat.state.value.error)
            assertTrue(
                chat.state.value.request!!
                    .rejected,
            )
            assertEquals(
                "公開資料の調査",
                chat.state.value.request
                    ?.turn
                    ?.text,
            )
            assertEquals(
                1,
                chat.state.value.snapshot
                    ?.revision,
            )
            chat.retry()
            runCurrent()
            assertEquals(1, requests.size)
            chat.recoverText()
            runCurrent()
            assertTrue(chat.submit("公開資料の調査"))
            runCurrent()
            assertNotEquals(requests[0].request_id, requests[1].request_id)
            assertEquals(1, requests[1].expected_revision)
            assertEquals(setupDraft, chat.state.value.activeDraft)
        }

    @Test
    fun lostEditTurnRetriesExactBodyAfterReturningToProfile() =
        runTest {
            setUpApi()
            snapshot =
                initial.copy(
                    setup_completed = true,
                    revision = 4,
                    activation = "applied",
                    settings_revision = 4,
                    applied_revision = 4,
                )
            val before = SpecialistSettingsPreview(description = "旧設定")
            val proposal =
                SpecialistSetupDraft(
                    "edit-rest",
                    kind = "edit",
                    before = before,
                    after = before.copy(description = "新設定"),
                )
            val requests = mutableListOf<SpecialistSetupTurnRequest>()
            coEvery { api.sendSpecialistSetupTurn("bot", capture(requests), any()) } answers {
                if (requests.size == 1) {
                    snapshot =
                        snapshot.copy(
                            pending_turn = SpecialistPendingTurn(requests[0].request_id, requests[0].text, 0.0),
                        )
                    failure(503, "specialist_model_unavailable")
                } else {
                    val turn = requests.last().result(proposal, 5, 1)
                    snapshot =
                        snapshot.copy(
                            revision = 5,
                            draft = proposal,
                            conversation_revision = 1,
                            conversation_turns = listOf(turn),
                            pending_turn = null,
                        )
                    Response.success(turn)
                }
            }
            val chat = controller()
            chat.bind(target, "bot-chat")
            runCurrent()
            chat.setEditing(true)
            assertTrue(chat.submit("新設定に変更して"))
            runCurrent()
            assertEquals("edit", requests.single().mode)

            val other = SpecialistChatTarget("server-b:bot", 4L, "bot")
            currentTarget = other
            val returned = controller()
            val original = snapshot
            snapshot = initial
            returned.bind(other, "bot-chat")
            runCurrent()
            assertNull(returned.state.value.request)
            currentTarget = target.copy(epoch = 6)
            snapshot = original
            returned.bind(currentTarget, "bot-chat")
            runCurrent()
            returned.retry()
            runCurrent()
            assertEquals(requests[0], requests[1])
            assertEquals(
                proposal.after,
                returned.state.value.activeDraft
                    ?.after,
            )
            assertEquals(
                1,
                returned.state.value
                    .projectMessages(emptyList())
                    .count { it.content == "新設定に変更して" },
            )
            assertNull(returned.state.value.request)
        }

    @Test
    fun questionFailureKeepsDraftConfirmableAndRetriesTheSameRequest() =
        runTest {
            setUpApi()
            val draftTurn = SpecialistSetupTurnRequest("draft-turn", "bot-chat", 0, 0, "技術調査を担当して", "setup")
            snapshot =
                initial.copy(
                    revision = 1,
                    draft = setupDraft,
                    conversation_revision = 1,
                    conversation_turns = listOf(draftTurn.result(setupDraft, 1, 1)),
                )
            val requests = mutableListOf<SpecialistSetupTurnRequest>()
            val model = CompletableDeferred<Unit>()
            val reply = "投稿検索に対応しています。\n\n**X APIの認証**と検索権限が必要です。"
            coEvery { api.sendSpecialistSetupTurn("bot", capture(requests), any()) } coAnswers {
                val request = requests.last()
                if (requests.size == 1) {
                    model.await()
                    snapshot =
                        snapshot.copy(pending_turn = SpecialistPendingTurn(request.request_id, request.text, 0.0))
                    failure(502, "invalid_specialist_model_output")
                } else {
                    val turn =
                        request.result(null, 1, 2).let {
                            it.copy(assistant = it.assistant.copy(content = reply))
                        }
                    snapshot =
                        snapshot.copy(
                            conversation_revision = 2,
                            conversation_turns = snapshot.conversation_turns + turn,
                            pending_turn = null,
                        )
                    Response.success(turn)
                }
            }
            val chat = controller()
            chat.bind(target, "bot-chat")
            runCurrent()
            assertTrue(chat.submit("xurlで本当に取れる？"))
            runCurrent()
            assertTrue(chat.state.value.hasInFlightTurn())
            assertFalse(chat.state.value.canConfirm())
            chat.confirm(setupDraft.id, 1)
            coVerify(exactly = 0) { api.confirmSpecialistSetup(any(), any(), any()) }

            model.complete(Unit)
            runCurrent()
            assertFalse(chat.state.value.hasInFlightTurn())
            assertTrue(chat.state.value.canConfirm())
            assertEquals("invalid_specialist_model_output", chat.state.value.error)
            assertEquals(setupDraft, chat.state.value.activeDraft)
            chat.retry()
            runCurrent()
            assertEquals(listOf(requests.first(), requests.first()), requests)
            assertTrue(chat.state.value.canAct)
            assertEquals(setupDraft, chat.state.value.activeDraft)
            assertTrue(
                chat.state.value
                    .projectMessages(emptyList())
                    .any { it.content == reply },
            )
        }

    @Test
    fun confirmingCurrentDraftDismissesFailedTurnAndAllowsApply() =
        runTest {
            setUpApi()
            snapshot =
                initial.copy(
                    revision = 1,
                    draft = setupDraft,
                    pending_turn = SpecialistPendingTurn("failed-turn", "確認したいこと", 0.0),
                )
            coEvery { api.confirmSpecialistSetup("bot", any(), any()) } answers {
                snapshot =
                    snapshot.copy(
                        setup_completed = true,
                        activation = "pending_apply",
                        revision = 2,
                        settings_revision = 2,
                    )
                Response.success(snapshot.copy(conversation_turns = emptyList(), pending_turn = null))
            }
            val chat = controller()
            chat.bind(target, "bot-chat")
            runCurrent()
            assertTrue(chat.state.value.canConfirm())
            chat.confirm(setupDraft.id, 1)
            runCurrent()
            coVerify(exactly = 1) { api.confirmSpecialistSetup("bot", any(), any()) }
            assertTrue(chat.state.value.pendingApply)
            assertTrue(chat.state.value.canAct)
        }

    @Test
    fun staleApplyResolvesCanonicalTipBeforeSendingNewRequest() =
        runTest {
            setUpApi()
            snapshot =
                initial.copy(setup_completed = true, revision = 9, activation = "pending_apply", settings_revision = 8)
            val requests = mutableListOf<SpecialistSetupApplyRequest>()
            coEvery { api.applySpecialistSetup("bot", capture(requests), any()) } answers {
                if (requests.size == 1) {
                    failure(409, "stale_bot_chat_session")
                } else {
                    snapshot = snapshot.copy(activation = "applied", applied_revision = 8)
                    Response.success(SpecialistSetupApplyResponse("applied", "tip", 8, 8))
                }
            }
            val chat = controller()
            chat.bind(target, "root")
            runCurrent()
            chat.apply()
            runCurrent()
            assertEquals(1, resolveCount)
            chat.apply()
            runCurrent()
            assertEquals(1, requests.size)
            chat.bind(target, "tip")
            runCurrent()
            chat.apply()
            runCurrent()
            assertEquals("tip", requests[1].session_id)
            assertEquals(8, requests[1].expected_revision)
            assertNotEquals(requests[0].request_id, requests[1].request_id)
        }

    @Test
    fun conflictingFilesCanBeReviewedAgainInEditModeWhileApplyIsPending() =
        runTest {
            setUpApi()
            snapshot =
                initial.copy(setup_completed = true, revision = 2, activation = "pending_apply", settings_revision = 2)
            coEvery { api.applySpecialistSetup("bot", any(), any()) } returns failure(409, "stale_settings_files")
            val currentSettings = SpecialistSettingsPreview(description = "外部で更新された設定")
            val newDraft =
                SpecialistSetupDraft(
                    "revised",
                    kind = "edit",
                    before = currentSettings,
                    after = currentSettings.copy(description = "最新の役割"),
                )
            coEvery { api.sendSpecialistSetupTurn("bot", any(), any()) } answers {
                val request = secondArg<SpecialistSetupTurnRequest>()
                assertEquals("edit", request.mode)
                val turn = request.result(newDraft, 3, 1)
                snapshot =
                    snapshot.copy(
                        draft = newDraft,
                        revision = 3,
                        conversation_revision = 1,
                        conversation_turns = listOf(turn),
                    )
                Response.success(turn)
            }
            val chat = controller()
            chat.bind(target, "bot-chat")
            runCurrent()
            assertFalse(chat.state.value.canEnterEdit)
            chat.apply()
            runCurrent()
            assertTrue(chat.state.value.canEnterEdit)
            chat.setEditing(true)
            assertTrue(chat.submit("最新の役割に変更して"))
            runCurrent()
            assertEquals(newDraft, chat.state.value.activeDraft)
            assertTrue(chat.state.value.blocksNormalInput)
        }

    @Test
    fun legacyFallbackRequiresMissingFlagsOrEndpoint() =
        runTest {
            coEvery { api.getSpecialistCapabilities(any()) } returnsMany
                listOf(
                    failure(503, "specialist_storage_unavailable"),
                    Response.success(SpecialistCapabilitiesResponse(setup_conversation = true)),
                    failure(404, "not_found"),
                )
            val chat = controller()
            chat.bind(target, "bot-chat")
            runCurrent()
            assertFalse(chat.state.value.legacy)
            chat.retry()
            runCurrent()
            assertTrue(chat.state.value.legacy)
            val older = controller()
            older.bind(target, "bot-chat")
            runCurrent()
            assertTrue(older.state.value.legacy)
            coVerify(exactly = 0) { api.getSpecialistSetup(any(), any()) }
        }

    private fun SpecialistSetupTurnRequest.result(
        card: SpecialistSetupDraft?,
        revision: Int,
        conversationRevision: Int,
    ): SpecialistSetupTurn =
        SpecialistSetupTurn(
            request_id,
            session_id,
            mode,
            conversationRevision,
            revision,
            SpecialistSetupMessage("$request_id:user", "user", text),
            SpecialistSetupMessage("$request_id:assistant", "assistant", "下書きを作成しました"),
            card,
        )

    private fun <T> failure(
        status: Int,
        code: String,
    ): Response<T> = Response.error(status, """{"detail":{"code":"$code"}}""".toResponseBody())
}
