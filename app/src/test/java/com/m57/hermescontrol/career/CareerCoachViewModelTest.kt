package com.m57.hermescontrol.career

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CareerCoachViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val local = mockk<CareerLocalStore>()
    private val repository = mockk<CareerCoachRepository>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { local.coach() } returns null
        coEvery { local.saveCoach(any()) } returns Unit
        coEvery { repository.conversation(any()) } returns CareerCoachSnapshot()
        coEvery { repository.events(any()) } returns CareerCoachEvents()
        coEvery { repository.capabilities(any()) } returns testCapabilities()
        coEvery { repository.dailySettings() } returns testCapabilities().daily_settings
        acknowledge()
    }

    private fun testCapabilities(): CareerCapabilities {
        val execution = CareerExecution("openai-codex", "test-model", "medium")
        return CareerCapabilities(
            features = listOf("execution_config", "daily_settings", "document_revision", "artifact_receipts"),
            state = "available",
            default_execution = execution,
            models =
                listOf(
                    CareerModelOption(
                        "openai-codex",
                        "test-model",
                        reasoning_efforts = listOf("low", "medium", "high"),
                    ),
                    CareerModelOption("openai-codex", "other-model", reasoning_efforts = listOf("medium", "high")),
                ),
            daily_settings = CareerDailySettings(execution = execution),
        )
    }

    private fun acknowledge() {
        coEvery { repository.mutate(any(), any()) } coAnswers {
            val payload = secondArg<JsonObject>()
            buildJsonObject { payload["execution"]?.let { put("execution", it) } }
        }
    }

    @Test
    fun answerContextSurvivesRecreationAndDoesNotOverwriteAnotherQuestionDraft() =
        runTest(dispatcher) {
            var cache: CareerCoachCache? = null
            coEvery { local.coach() } answers { cache }
            coEvery { local.saveCoach(any()) } answers {
                cache = firstArg()
                Unit
            }
            val context =
                buildJsonObject {
                    put("offer_id", "offer-a")
                    put("question_id", "q1")
                    put("question_version", "v3")
                    put("body", "編集中の回答")
                }
            val first = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            first.prepareAnswerContext(context)
            first.changeDraft("この回答を相談したい")
            advanceUntilIdle()
            val restored = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            assertEquals(context, restored.state.value.answerContext)
            restored.prepareAnswerContext(context.with("question_id" to kotlinx.serialization.json.JsonPrimitive("q2")))
            advanceUntilIdle()
            assertEquals(context, restored.state.value.answerContext)
            assertEquals("この回答を相談したい", restored.state.value.draft)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
            restored.send()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.mutate("message", match { it["answer_context"] == context }) }
        }

    @Test fun esHandoffDoesNotReplaceExistingUnrelatedDraftOrItsTarget() =
        runTest(dispatcher) {
            val oldTarget = CareerTarget("documents", "DOC-OLD", 1)
            coEvery { local.coach() } returns CareerCoachCache(draft = "未送信の相談", target = oldTarget)
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.prepareAnswerConversation(
                "ESの相談",
                CareerTarget("offers", "new"),
                buildJsonObject { put("question_id", "q2") },
            )
            advanceUntilIdle()
            assertEquals("未送信の相談", model.state.value.draft)
            assertEquals(oldTarget, model.state.value.target)
            assertNull(model.state.value.answerContext)
            assertNotNull(model.state.value.error)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test fun newQuestionReplacesOnlyEmptyContextAndRemovingReferenceClearsFrozenAnswer() =
        runTest(dispatcher) {
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            val offer = CareerTarget("offers", "same-offer")
            model.prepareAnswerConversation("", offer, buildJsonObject { put("question_id", "q1") })
            model.prepareAnswerConversation("次の設問", offer, buildJsonObject { put("question_id", "q2") })
            advanceUntilIdle()
            assertEquals(
                "q2",
                model.state.value.answerContext
                    ?.text("question_id"),
            )
            assertEquals("次の設問", model.state.value.draft)
            model.target(null)
            advanceUntilIdle()
            assertNull(model.state.value.answerContext)
            assertEquals("次の設問", model.state.value.draft)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun sourceHandoffKeepsExistingDraftAndDocumentAndIsIncludedInAcceptedSend() =
        runTest(dispatcher) {
            val previous = CareerTarget("documents", "DOC-001", 1)
            val ref = CareerSourceRef("mail", "message-a", "account-a")
            coEvery { local.coach() } returns CareerCoachCache(draft = "書きかけの相談", target = previous)
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.prepare("", CareerTarget("companies", "company-a", source_refs = listOf(ref)))
            advanceUntilIdle()
            assertEquals("書きかけの相談", model.state.value.draft)
            assertEquals(previous.copy(source_refs = listOf(ref)), model.state.value.target)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
            model.send()
            advanceUntilIdle()
            coVerify(exactly = 1) {
                repository.mutate(
                    "message",
                    match {
                        (it["target"] as? JsonObject)?.get("source_refs")?.toString()?.contains("message-a") == true
                    },
                )
            }
        }

    @Test
    fun emptyPromptStillPreparesExplicitCompanyTargetWithoutSending() =
        runTest(dispatcher) {
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            val target =
                CareerTarget("companies", "company-a", source_refs = listOf(CareerSourceRef("mypage", "evidence-a")))
            model.prepare("", target)
            advanceUntilIdle()
            assertEquals(target, model.state.value.target)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun unreadableCacheIsNeverOverwrittenOrUsedToSend() =
        runTest(dispatcher) {
            coEvery { local.coach() } throws IOException("unreadable encrypted cache")
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.refreshCapabilities()
            advanceUntilIdle()
            model.changeDraft("入力を消さない")
            advanceUntilIdle()
            model.send()
            advanceUntilIdle()
            assertEquals("入力を消さない", model.state.value.draft)
            assertNotNull(model.state.value.error)
            coVerify(exactly = 0) { local.saveCoach(any()) }
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun openingAnotherDocumentPreservesUnsentDraftAndTarget() =
        runTest(dispatcher) {
            val oldTarget = CareerTarget("documents", "DOC-001", 1)
            coEvery { local.coach() } returns CareerCoachCache(draft = "入力中の相談", target = oldTarget)
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.prepare("別のESの修正", CareerTarget("documents", "DOC-002", 2))
            advanceUntilIdle()
            assertEquals("入力中の相談", model.state.value.draft)
            assertEquals(oldTarget, model.state.value.target)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun changingSelectionNeverChangesPendingExecution() =
        runTest(dispatcher) {
            coEvery { repository.mutate(any(), any()) } throws IOException()
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.send("依頼")
            advanceUntilIdle()
            val pending = requireNotNull(model.state.value.pending)
            val second = CareerExecution("openai-codex", "other-model", "high")
            model.selectExecution(second)
            advanceUntilIdle()
            assertEquals(pending, model.state.value.pending)
            assertEquals(second, model.state.value.execution)
            acknowledge()
            model.retry()
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.mutate("message", pending.payload) }
            assertEquals(second, model.state.value.execution)
        }

    @Test
    fun cachedCapabilitiesDoNotAuthorizeModelSendToOldServer() =
        runTest(dispatcher) {
            coEvery { local.coach() } returns
                CareerCoachCache(
                    draft = "消さない",
                    capabilities = testCapabilities(),
                    execution = testCapabilities().default_execution,
                )
            coEvery { repository.capabilities(any()) } throws IOException()
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.send()
            advanceUntilIdle()
            assertEquals("消さない", model.state.value.draft)
            assertNull(model.state.value.pending)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun missingFeatureAndUnsupportedEffortPreventNewSend() =
        runTest(dispatcher) {
            coEvery { repository.capabilities(any()) } returns testCapabilities().copy(features = emptyList())
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.send("未対応サーバーには送らない")
            model.selectExecution(CareerExecution("openai-codex", "test-model", "ultra"))
            advanceUntilIdle()
            assertEquals(
                "medium",
                model.state.value.execution
                    ?.reasoning_effort,
            )
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun dailySettingsUseServerVersionAndKeepManualSelection() =
        runTest(dispatcher) {
            coEvery { repository.capabilities(any()) } returns
                testCapabilities().copy(daily_settings = CareerDailySettings(7, testCapabilities().default_execution))
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.saveDailyExecution(CareerExecution("openai-codex", "other-model", "high"))
            advanceUntilIdle()
            coVerify {
                repository.mutate(
                    "daily-settings",
                    match {
                        it.text("expected_version") == "7" &&
                            (it["execution"] as JsonObject).text("model") == "other-model"
                    },
                )
            }
            assertEquals(
                "test-model",
                model.state.value.execution
                    ?.model,
            )
        }

    @Test
    fun artifactRevisionPinsDisplayedVersionAndPreservesConversationDraft() =
        runTest(dispatcher) {
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            val artifact = CareerArtifact("DOC-001", 3, title = "下書き", path = "saved-v3")
            model.changeDraft("別の相談を途中まで入力")
            model.changeRevision(artifact, "結びを直す")
            advanceUntilIdle()
            model.revise(artifact, "結びを直す")
            advanceUntilIdle()
            assertEquals("別の相談を途中まで入力", model.state.value.draft)
            assertTrue(
                model.state.value.revisionDrafts
                    .isEmpty(),
            )
            coVerify {
                repository.mutate(
                    "message",
                    match {
                        (it["target"] as JsonObject).text("version") == "3" &&
                            (it["target"] as JsonObject).text("id") == "DOC-001"
                    },
                )
            }
        }

    @Test
    fun revisionConflictKeepsExactVersionAndRevisionDraft() =
        runTest(dispatcher) {
            coEvery { repository.mutate(any(), any()) } throws IOException()
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            val artifact = CareerArtifact("DOC-001", 2, title = "下書き", path = "saved-v2")
            model.changeRevision(artifact, "直したい")
            model.revise(artifact, "直したい")
            advanceUntilIdle()
            assertEquals("直したい", model.state.value.revisionDrafts["DOC-001:2"])
            assertEquals(
                "2",
                (
                    model.state.value.pending
                        ?.payload
                        ?.get("target") as JsonObject
                ).text("version"),
            )
            coVerify { local.saveCoach(match { it.revisionDrafts["DOC-001:2"] == "直したい" }) }
        }

    @Test
    fun legacyResumeRequiresExplicitSelectionWhileRecordedResumeKeepsConfiguration() =
        runTest(dispatcher) {
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.control(CareerCoachRun("legacy", "message", "paused"), "resume")
            advanceUntilIdle()
            coVerify { repository.mutate("control", match { it.text("id") == "legacy" && it["execution"] != null }) }
            model.control(
                CareerCoachRun(
                    "recorded",
                    "message",
                    "paused",
                    execution = CareerExecution("openai-codex", "other-model", "high"),
                ),
                "resume",
            )
            advanceUntilIdle()
            coVerify { repository.mutate("control", match { it.text("id") == "recorded" && it["execution"] == null }) }
        }

    @Test
    fun acknowledgementMustMatchTheSubmittedModelAndEffort() =
        runTest(dispatcher) {
            coEvery { repository.mutate(any(), any()) } returns
                buildJsonObject { put("execution", buildJsonObject { put("model", "wrong") }) }
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.changeDraft("入力を保持")
            model.send()
            advanceUntilIdle()
            assertNotNull(model.state.value.pending)
            assertEquals("入力を保持", model.state.value.draft)
            assertTrue(
                model.state.value.error
                    .orEmpty()
                    .contains("照合"),
            )
        }

    private fun page(
        first: Int,
        last: Int,
        before: Long? = null,
        cursor: Long = 1,
    ): CareerCoachSnapshot =
        CareerCoachSnapshot(
            messages =
                (first..last).map {
                    CareerCoachMessage(
                        "message-$it",
                        "run-$it",
                        "assistant",
                        "返信$it",
                        seq = it.toLong(),
                    )
                },
            older_before = before,
            cursor = cursor,
        )

    @Test
    fun refreshKeepsLoadedHistoryAndExhaustedOlderCursorWithLatestServerMetadata() =
        runTest(dispatcher) {
            var cache: CareerCoachCache? = null
            coEvery { local.coach() } answers { cache }
            coEvery { local.saveCoach(any()) } answers {
                cache = firstArg()
                Unit
            }
            coEvery { repository.conversation(null) } returns page(81, 160, before = 81)
            coEvery { repository.conversation(81) } returns page(1, 80)
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.refresh()
            advanceUntilIdle()
            model.older()
            advanceUntilIdle()
            assertEquals(
                160,
                model.state.value.snapshot
                    ?.messages
                    ?.size,
            )
            assertNull(
                model.state.value.snapshot
                    ?.older_before,
            )
            assertTrue(model.state.value.historyExpanded)
            val fresh = page(82, 161, before = 82, cursor = 9).copy(archived = true, checked_at = "fresh")
            coEvery { repository.conversation(null) } returns fresh
            model.refresh()
            advanceUntilIdle()
            val snapshot = requireNotNull(model.state.value.snapshot)
            assertEquals((1..161).map { "message-$it" }, snapshot.messages.map { it.id })
            assertEquals(9L, snapshot.cursor)
            assertEquals("fresh", snapshot.checked_at)
            assertTrue(snapshot.archived)
            assertNull(snapshot.older_before)
            val restored = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            restored.refresh()
            advanceUntilIdle()
            assertEquals(
                161,
                restored.state.value.snapshot
                    ?.messages
                    ?.size,
            )
            assertNull(
                restored.state.value.snapshot
                    ?.older_before,
            )
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun pollKeepsLoadedHistoryAndDoesNotReintroduceAlreadyExhaustedPagination() =
        runTest(dispatcher) {
            coEvery { repository.conversation(null) } returns page(81, 160, before = 81)
            coEvery { repository.conversation(81) } returns page(1, 80)
            coEvery { repository.events(any()) } returns
                CareerCoachEvents(events = listOf(CareerCoachEvent(2, "run", "working", JsonObject(emptyMap()))))
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.refresh()
            advanceUntilIdle()
            model.older()
            advanceUntilIdle()
            coEvery { repository.conversation(null) } returns page(82, 161, before = 82, cursor = 3)
            model.observe(true)
            runCurrent()
            advanceTimeBy(2600)
            runCurrent()
            model.observe(false)
            advanceUntilIdle()
            assertEquals(
                161,
                model.state.value.snapshot
                    ?.messages
                    ?.size,
            )
            assertEquals(
                "message-1",
                model.state.value.snapshot
                    ?.messages
                    ?.first()
                    ?.id,
            )
            assertEquals(
                "message-161",
                model.state.value.snapshot
                    ?.messages
                    ?.last()
                    ?.id,
            )
            assertNull(
                model.state.value.snapshot
                    ?.older_before,
            )
            assertEquals("保存情報を使って作業", model.state.value.progress["run"])
            coVerify(atLeast = 1) { repository.events(any()) }
        }

    @Test
    fun ordinarySendKeepsSelectedCompanyAndSourcesForFollowingTurnsUntilExplicitlyRemoved() =
        runTest(dispatcher) {
            val target =
                CareerTarget(
                    "companies",
                    "company-a",
                    source_refs = listOf(CareerSourceRef("mail", "mail-a", "account-a")),
                )
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.prepare("この企業の相談", target)
            model.send()
            advanceUntilIdle()
            assertEquals("", model.state.value.draft)
            assertEquals(target, model.state.value.target)
            model.send("続けて教えて")
            advanceUntilIdle()
            coVerify(exactly = 2) {
                repository.mutate("message", match { (it["target"] as? JsonObject)?.text("id") == "company-a" })
            }
            model.target(null)
            advanceUntilIdle()
            assertNull(model.state.value.target)
        }

    @Test
    fun archivedSnapshotCannotAuthorizeNewSendEvenWithAvailableModels() =
        runTest(dispatcher) {
            coEvery { repository.conversation(null) } returns CareerCoachSnapshot(archived = true)
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.refresh()
            advanceUntilIdle()
            model.send("閲覧だけの相談")
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test
    fun offlineMessageKeepsExactRequestAcrossRetry() =
        runTest(dispatcher) {
            coEvery { repository.mutate(any(), any()) } throws IOException()
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.changeDraft("続きから相談したい")
            model.send()
            advanceUntilIdle()
            val pending = requireNotNull(model.state.value.pending)
            assertNotNull(pending.payload.text("operation_id"))
            assertEquals("続きから相談したい", model.state.value.draft)
            model.refresh()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.mutate(any(), any()) }
            acknowledge()
            model.retry()
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.mutate("message", pending.payload) }
            assertNull(model.state.value.pending)
            assertEquals("", model.state.value.draft)
        }

    @Test
    fun restartRestoresDraftAndTargetWithoutAutoSending() =
        runTest(dispatcher) {
            val target = CareerTarget("documents", "DOC-001")
            val pending = CareerPendingOperation("message", buildJsonObject { put("operation_id", "same-request") })
            coEvery { local.coach() } returns CareerCoachCache(draft = "まだ送らない", target = target, pending = pending)
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.refresh()
            advanceUntilIdle()
            assertEquals("まだ送らない", model.state.value.draft)
            assertEquals(target, model.state.value.target)
            assertEquals(pending, model.state.value.pending)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun localJournalFailurePreventsNetworkSend() =
        runTest(dispatcher) {
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            coEvery { local.saveCoach(any()) } throws IOException()
            model.send("ESを作って")
            advanceUntilIdle()
            assertNotNull(model.state.value.error)
            assertNotNull(model.state.value.pending)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun selectingArtifactPreservesUnsentTextAndPassesTargetOnly() =
        runTest(dispatcher) {
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.changeDraft("この表現を直したい")
            model.target(CareerEntity("documents", "DOC-001", 5, buildJsonObject { put("body", "全文を重ねて送らない") }))
            advanceUntilIdle()
            assertEquals("この表現を直したい", model.state.value.draft)
            model.send()
            advanceUntilIdle()
            coVerify {
                repository.mutate(
                    "message",
                    match {
                        it["target"] ==
                            buildJsonObject {
                                put("kind", "documents")
                                put("id", "DOC-001")
                            } &&
                            !it.toString().contains("全文を重ねて送らない")
                    },
                )
            }
        }

    @Test
    fun leavingScreenStopsObservationAndNeverCancelsAcceptedWork() =
        runTest(dispatcher) {
            val model = CareerCoachViewModel(repository, local)
            advanceUntilIdle()
            model.observe(true)
            runCurrent()
            advanceTimeBy(2600)
            runCurrent()
            model.observe(false)
            advanceUntilIdle()
            coVerify(atLeast = 1) { repository.events(any()) }
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun reconnectRecoversReplyAndRefreshesArtifactsWithoutResubmission() =
        runTest(dispatcher) {
            val old = CareerCoachSnapshot(cursor = 1)
            val fresh =
                CareerCoachSnapshot(
                    cursor = 3,
                    messages = listOf(CareerCoachMessage("reply", "run", "assistant", "保存しました")),
                )
            coEvery { local.coach() } returns CareerCoachCache(snapshot = old)
            coEvery { repository.conversation(any()) } returnsMany listOf(old, fresh)
            coEvery { repository.events(1) } returns
                CareerCoachEvents(
                    events = listOf(CareerCoachEvent(2, "run", "saved", JsonObject(emptyMap()))),
                    cursor = 2,
                )
            var refreshes = 0
            val model = CareerCoachViewModel(repository, local) { refreshes++ }
            advanceUntilIdle()
            model.observe(true)
            runCurrent()
            advanceTimeBy(2600)
            runCurrent()
            model.observe(false)
            advanceUntilIdle()
            assertEquals(
                "保存しました",
                model.state.value.snapshot
                    ?.messages
                    ?.single()
                    ?.text,
            )
            assertTrue(refreshes > 0)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }
}
