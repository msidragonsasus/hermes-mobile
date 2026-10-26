package com.m57.hermescontrol.career

import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CareerNewConversationTest {
    private val dispatcher = StandardTestDispatcher()
    private val registry = mockk<CareerLocalStore>()
    private val draft = mockk<CareerLocalStore>()
    private val actual = mockk<CareerLocalStore>()
    private val remote = mockk<CareerCoachRepository>()
    private val values = mutableMapOf<String, String?>()
    private var draftCache: CareerCoachCache? = null
    private var actualCache: CareerCoachCache? = null
    private val creations = mutableListOf<JsonObject>()
    private var offlineCreate = false
    private val execution = CareerExecution("fixture", "dummy", "medium")
    private val entry = CareerNewConversation(id = "fixture-draft")
    private val created = CareerThread("thread:fixture", "相談を始めたい")
    private val capabilities =
        CareerCapabilities(
            features = listOf("execution_config", "conversation_threads", "company_threads"),
            state = "available",
            default_execution = execution,
            models = listOf(CareerModelOption("fixture", "dummy", reasoning_efforts = listOf("medium"))),
        )

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { draft.uiValue(any()) } answers { values["draft/" + firstArg<String>()] }
        coEvery { draft.saveUiValue(any(), any()) } answers
            {
                values["draft/" + firstArg<String>()] = secondArg()
                Unit
            }
        coEvery { registry.uiValue(any()) } answers { values["registry/" + firstArg<String>()] }
        coEvery { registry.saveUiValue(any(), any()) } answers
            {
                values["registry/" + firstArg<String>()] = secondArg()
                Unit
            }
        values["registry/" + entry.registryKey] = Json.encodeToString(entry)
        coEvery { draft.coach() } answers { draftCache }
        coEvery { draft.saveCoach(any()) } answers {
            draftCache = firstArg()
            Unit
        }
        coEvery { actual.coach() } answers { actualCache }
        coEvery { actual.saveCoach(any()) } answers {
            actualCache = firstArg()
            Unit
        }
        coEvery { remote.capabilities(any()) } returns capabilities
        coEvery { remote.conversation(any()) } returns CareerCoachSnapshot(thread_id = created.id)
        coEvery { remote.events(any()) } returns CareerCoachEvents(thread_id = created.id)
        coEvery { remote.mutate(any(), any()) } coAnswers {
            buildJsonObject { put("execution", secondArg<JsonObject>().getValue("execution")) }
        }
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    private fun repository(
        spec: CareerNewConversation = entry,
        result: CareerThread = created,
    ) = CareerNewConversationRepository(
        spec,
        "fixture",
        registry,
        draft,
        { actual },
        { remote },
        { payload ->
            creations += payload
            if (offlineCreate) throw IOException("fixture offline")
            result
        },
    )

    @Test fun openingTypingAndLeavingNeverCreatesAndDraftSurvivesRecreation() =
        runTest(dispatcher) {
            val first = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            first.refresh()
            first.changeDraft("未送信の相談")
            advanceUntilIdle()
            val restored = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            restored.refresh()
            advanceUntilIdle()
            assertEquals("未送信の相談", restored.state.value.draft)
            assertTrue(creations.isEmpty())
            coVerify(exactly = 0) { remote.mutate(any(), any()) }
            coVerify(exactly = 0) { remote.conversation(any()) }
        }

    @Test fun backBeforeDraftDebounceFlushesTheLatestInputWithoutCreatingOrSending() =
        runTest(dispatcher) {
            val store = ViewModelStore()
            val model = CareerCoachViewModel(repository(), draft)
            store.put("fixture", model)
            advanceUntilIdle()
            model.changeDraft("入力直後に戻る下書き")
            store.clear()
            advanceUntilIdle()
            assertEquals("入力直後に戻る下書き", draftCache!!.draft)
            val reopened = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            assertEquals("入力直後に戻る下書き", reopened.state.value.draft)
            assertTrue(creations.isEmpty())
            coVerify(exactly = 0) { remote.mutate(any(), any()) }
        }

    @Test fun doubleTapCreatesOnceTitlesFromFirstMessageAndMovesPendingToRealThread() =
        runTest(dispatcher) {
            val model = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            model.changeDraft("**相談を始めたい**\nこれからの就活")
            model.send()
            model.send()
            advanceUntilIdle()
            assertEquals(1, creations.size)
            assertEquals("相談を始めたい これからの就活", creations.single().text("title"))
            assertNull(creations.single()["company_id"])
            assertNull(creations.single()["offer_id"])
            assertEquals("", actualCache!!.draft)
            assertNull(actualCache!!.pending)
            assertEquals(
                created.id,
                model.state.value.snapshot!!
                    .thread_id,
            )
            coVerify(exactly = 1) { remote.mutate("message", any()) }
        }

    @Test fun uncertainCreationAndMessageReuseExactOperationsAfterRestartWithoutAutoSend() =
        runTest(dispatcher) {
            offlineCreate = true
            val first = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            first.changeDraft("相談を始めたい")
            first.send()
            advanceUntilIdle()
            val operation = first.state.value.pending!!
            val createOperation = creations.single()
            offlineCreate = false
            coEvery { remote.mutate(any(), any()) } throws IOException("fixture response lost")
            val restored = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            assertEquals(1, creations.size)
            restored.retry()
            advanceUntilIdle()
            assertEquals(createOperation, creations.last())
            assertEquals(operation, actualCache!!.pending)
            val reopened = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            assertEquals(operation, reopened.state.value.pending)
            assertEquals(2, creations.size)
            coEvery { remote.mutate(any(), any()) } coAnswers
                { buildJsonObject { put("execution", secondArg<JsonObject>().getValue("execution")) } }
            reopened.retry()
            advanceUntilIdle()
            assertEquals(2, creations.size)
            assertNull(actualCache!!.pending)
            coVerify(exactly = 2) { remote.mutate("message", operation.payload) }
        }

    @Test fun changedCompanyReceiptNeverSendsToWrongThread() =
        runTest(dispatcher) {
            val companyEntry = entry.copy(companyId = "company-a", offerId = "offer-a")
            values["registry/" + companyEntry.registryKey] = Json.encodeToString(companyEntry)
            val model =
                CareerCoachViewModel(
                    repository(companyEntry, created.copy(company_id = "company-b", offer_id = "offer-a")),
                    draft,
                )
            advanceUntilIdle()
            model.changeDraft("企業の相談")
            model.send()
            advanceUntilIdle()
            assertEquals("company-a", creations.single().text("company_id"))
            assertEquals("offer-a", creations.single().text("offer_id"))
            assertNotNull(model.state.value.pending)
            assertNull(actualCache)
            assertNull(values["registry/" + companyEntry.registryKey])
            assertEquals(listOf(companyEntry), CareerNewConversationRecovery.entries(registry))
            coVerify(exactly = 0) { remote.mutate(any(), any()) }
        }

    @Test fun unreadableDraftOrFailedLocalWritePreventsCreation() =
        runTest(dispatcher) {
            coEvery { draft.coach() } throws IOException("fixture cannot decrypt")
            val unreadable = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            unreadable.changeDraft("相談")
            unreadable.send()
            advanceUntilIdle()
            assertTrue(creations.isEmpty())
            coEvery { draft.coach() } returns null
            coEvery { draft.saveCoach(any()) } throws IOException("fixture disk full")
            val diskFull = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            diskFull.changeDraft("相談")
            diskFull.send()
            advanceUntilIdle()
            assertTrue(creations.isEmpty())
            coVerify(exactly = 0) { remote.mutate(any(), any()) }
        }

    @Test fun previewAndTitleRemoveSyntaxAndDoNotSplitEmoji() {
        assertEquals("見出し 大切な 相談 公式", careerThreadPreview("## 見出し\n- **大切な** _相談_ [公式](https://example.invalid)"))
        val title = careerConversationTitle("😀".repeat(40))
        assertEquals(36, title.codePointCount(0, title.length))
        assertTrue(title.endsWith("…"))
        assertEquals("新しい相談", careerConversationTitle("**\u0000**"))
        assertEquals(entry, CareerNewConversation.fromThreadId(entry.threadId))
        assertNull(CareerNewConversation.fromThreadId("draft:not-valid-base64!"))
        assertEquals("a".repeat(34) + "…", careerConversationTitle("a".repeat(34) + "🇯🇵" + "続き"))
    }

    @Test fun completionReleasesOnlyItsOwnDraftAndPreservesCreatedConversation() =
        runTest(dispatcher) {
            val repo = repository()
            values["registry/" + entry.registryKey] = Json.encodeToString(entry)
            val model = CareerCoachViewModel(repo, draft)
            advanceUntilIdle()
            model.changeDraft("相談を始めたい")
            model.send()
            advanceUntilIdle()
            assertEquals(created, repo.complete())
            assertNull(values["registry/" + entry.registryKey])
            assertNotNull(actualCache)
            assertSame(actual, repo.localStore().local)
            val anotherDraft = entry.copy(id = "another-fixture")
            values["registry/" + entry.registryKey] = Json.encodeToString(anotherDraft)
            repo.complete()
            assertEquals(Json.encodeToString(anotherDraft), values["registry/" + entry.registryKey])
            assertEquals(1, creations.size)
        }

    @Test fun blankSendAndReconnectDoNotCreateOrAutomaticallyRetry() =
        runTest(dispatcher) {
            val model = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            model.send("   ")
            model.refresh()
            advanceUntilIdle()
            assertTrue(creations.isEmpty())
            offlineCreate = true
            model.changeDraft("相談")
            model.send()
            advanceUntilIdle()
            val pending = model.state.value.pending
            offlineCreate = false
            model.refresh()
            advanceUntilIdle()
            assertEquals(1, creations.size)
            assertEquals(pending, model.state.value.pending)
            coVerify(exactly = 0) { remote.mutate(any(), any()) }
        }

    @Test fun updateTimeUsesPhoneZoneAndSafeFallback() {
        val zone = ZoneId.of("Asia/Tokyo")
        val clock = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), zone)
        assertEquals("今日 09:30", careerThreadUpdatedAt("2026-10-02T00:30:00+00:00", clock))
        assertEquals("昨日 23:30", careerThreadUpdatedAt("2026-10-01T14:30:00Z", clock))
        assertEquals("2025年10月2日 09:00", careerThreadUpdatedAt("2025-10-02T00:00:00Z", clock))
        assertEquals("更新日時を確認できません", careerThreadUpdatedAt("broken", clock))
        assertEquals("", careerThreadUpdatedAt("", clock))
    }

    @Test fun existingRealThreadDraftAndPendingAreNeverOverwrittenAfterLostCreation() =
        runTest(dispatcher) {
            val otherOperation =
                CareerPendingOperation("message", buildJsonObject { put("operation_id", "other-fixture-send") })
            val existing = CareerCoachCache(draft = "別の画面の下書き", pending = otherOperation)
            actualCache = existing
            offlineCreate = true
            val model = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            model.changeDraft("今回の相談")
            model.send()
            advanceUntilIdle()
            assertNotNull(values["registry/" + entry.registryKey])
            val firstCreate = creations.single()
            offlineCreate = false
            model.retry()
            advanceUntilIdle()
            assertEquals(firstCreate, creations.last())
            assertEquals(existing, actualCache)
            assertNotNull(model.state.value.pending)
            assertNull(values["registry/" + entry.registryKey])
            assertNotNull(values["draft/new-conversation-receipt"])
            assertEquals("今回の相談", draftCache!!.draft)
            assertEquals(listOf(entry), CareerNewConversationRecovery.entries(registry))
            assertTrue(
                model.state.value.error!!
                    .contains("別の下書き"),
            )
            coVerify(exactly = 0) { remote.mutate(any(), any()) }
        }

    @Test fun recoveringAfterOtherDraftCompletesPreservesCachedHistoryEvenIfRefreshFails() =
        runTest(dispatcher) {
            val snapshot = CareerCoachSnapshot(thread_id = created.id, cursor = 9)
            actualCache = CareerCoachCache(draft = "別の下書き", snapshot = snapshot)
            val repo =
                CareerNewConversationRepository(
                    entry,
                    "fixture",
                    registry,
                    draft,
                    { actual },
                    { remote },
                    { created },
                    scope = "recover-history-fixture",
                )
            val model = CareerCoachViewModel(repo, draft)
            advanceUntilIdle()
            model.changeDraft("今回の相談")
            model.send()
            advanceUntilIdle()
            assertEquals(listOf(entry), CareerNewConversationRecovery.entries(registry))
            assertEquals("別の下書き", actualCache!!.draft)
            actualCache = actualCache!!.copy(draft = "")
            coEvery { remote.conversation(any()) } throws IOException("fixture reconnect interrupted")
            model.retry()
            advanceUntilIdle()
            assertNull(actualCache!!.pending)
            assertEquals(snapshot, actualCache!!.snapshot)
            assertEquals("", actualCache!!.draft)
            assertTrue(CareerNewConversationRecovery.entries(registry).isEmpty())
            model.changeDraft("切断中も続きの相談を入力")
            advanceUntilIdle()
            assertEquals(snapshot, actualCache!!.snapshot)
            assertEquals("切断中も続きの相談を入力", actualCache!!.draft)
            model.selectExecution(execution)
            advanceUntilIdle()
            assertEquals(snapshot, actualCache!!.snapshot)
            coVerify(exactly = 1) { remote.mutate(any(), any()) }
        }

    @Test fun unregisteredScopeAndArchivedThreadFailWithoutSending() =
        runTest(dispatcher) {
            values.remove("registry/" + entry.registryKey)
            val wrongScope = CareerCoachViewModel(repository(), draft)
            advanceUntilIdle()
            wrongScope.changeDraft("相談")
            wrongScope.send()
            advanceUntilIdle()
            assertTrue(creations.isEmpty())
            assertTrue(
                wrongScope.state.value.error!!
                    .contains("別の接続先"),
            )
            values["registry/" + entry.registryKey] = Json.encodeToString(entry)
            val archived = CareerCoachViewModel(repository(result = created.copy(archived = true)), draft)
            advanceUntilIdle()
            archived.retry()
            advanceUntilIdle()
            assertTrue(
                archived.state.value.error!!
                    .contains("復元"),
            )
            coVerify(exactly = 0) { remote.mutate(any(), any()) }
        }

    @Test fun generationHandoverStopsAnOlderActualScreenFromReplacingThePendingSend() =
        runTest(dispatcher) {
            val storageKey = "fixture-scope/thread/thread:fixture"
            val older = CareerCoachViewModel(remote, actual, storageKey = storageKey)
            advanceUntilIdle()
            // A live screen can exist even before its first delayed write; the migration checks disk too.
            actualCache = null
            val repo =
                CareerNewConversationRepository(entry, "fixture", registry, draft, {
                    actual
                }, { remote }, { created }, scope = "fixture-scope")
            coEvery { remote.mutate(any(), any()) } throws IOException("fixture response lost")
            val new = CareerCoachViewModel(repo, draft)
            advanceUntilIdle()
            new.changeDraft("今回の相談")
            new.send()
            advanceUntilIdle()
            val migrated = actualCache!!
            older.changeDraft("別の入力")
            advanceUntilIdle()
            assertEquals(migrated, actualCache)
            older.send("古い画面から明示した本文")
            advanceUntilIdle()
            assertEquals(migrated, actualCache)
            coVerify(exactly = 1) { remote.mutate(any(), any()) }
            assertEquals(1L, CareerCoachStorage.generation(storageKey))
        }

    @Test fun inMemoryTypingBeforeDebounceStopsMigrationAndPreservesBothDrafts() =
        runTest(dispatcher) {
            val storageKey = "typing-fixture/thread/thread:fixture"
            val older = CareerCoachViewModel(remote, actual, storageKey = storageKey)
            advanceUntilIdle()
            older.changeDraft("まだ保存待ちの入力")
            val repo =
                CareerNewConversationRepository(entry, "fixture", registry, draft, {
                    actual
                }, { remote }, { created }, scope = "typing-fixture")
            val new = CareerCoachViewModel(repo, draft)
            runCurrent()
            new.changeDraft("新しい相談の入力")
            new.send()
            runCurrent()
            assertEquals("", actualCache!!.draft)
            assertNotNull(new.state.value.pending)
            advanceUntilIdle()
            assertEquals("まだ保存待ちの入力", actualCache!!.draft)
            assertEquals("新しい相談の入力", draftCache!!.draft)
            assertNotNull(draftCache!!.pending)
            coVerify(exactly = 0) { remote.mutate(any(), any()) }
        }

    @Test fun emptyAlreadyOpenedThreadCanAcceptMigrationWithoutLosingExistingEdits() =
        runTest(dispatcher) {
            val storageKey = "empty-fixture/thread/thread:fixture"
            coEvery { remote.conversation(any()) } returns CareerCoachSnapshot(thread_id = created.id)
            val older = CareerCoachViewModel(remote, actual, storageKey = storageKey)
            advanceUntilIdle()
            assertNotNull(actualCache)
            val repo =
                CareerNewConversationRepository(entry, "fixture", registry, draft, {
                    actual
                }, { remote }, { created }, scope = "empty-fixture")
            val new = CareerCoachViewModel(repo, draft)
            advanceUntilIdle()
            new.changeDraft("新しい相談の入力")
            new.send()
            advanceUntilIdle()
            assertNull(actualCache!!.pending)
            assertEquals("", actualCache!!.draft)
            assertEquals(1L, CareerCoachStorage.generation(storageKey))
            older.changeDraft("古い画面からの入力")
            advanceUntilIdle()
            assertEquals("", actualCache!!.draft)
            assertEquals("", older.state.value.draft)
            coVerify(exactly = 1) { remote.mutate(any(), any()) }
        }

    @Test fun anotherScreenTakingMigratedPendingStopsTheDraftWriterFromOverwritingIt() =
        runTest(dispatcher) {
            val storageKey = "handover-fixture/thread/thread:fixture"
            var actualScreen: CareerCoachViewModel? = null
            coEvery { remote.mutate(any(), any()) } coAnswers {
                actualScreen = CareerCoachViewModel(remote, actual, storageKey = storageKey)
                runCurrent()
                buildJsonObject { put("execution", secondArg<JsonObject>().getValue("execution")) }
            }
            val repo =
                CareerNewConversationRepository(
                    entry,
                    "fixture",
                    registry,
                    draft,
                    { actual },
                    { remote },
                    { created },
                    scope = "handover-fixture",
                )
            val starter = CareerCoachViewModel(repo, draft)
            advanceUntilIdle()
            starter.changeDraft("今回の相談")
            starter.send()
            advanceUntilIdle()
            assertEquals("今回の相談", actualCache!!.draft)
            assertNotNull(actualCache!!.pending)
            assertEquals(actualCache!!.pending, actualScreen!!.state.value.pending)
            assertNotNull(starter.state.value.pending)
            coVerify(exactly = 1) { remote.mutate(any(), any()) }
        }

    @Test fun migrationBetweenConstructionAndInitializationCannotActivateAnOldWriter() =
        runTest(dispatcher) {
            val storageKey = "init-fixture/thread/thread:fixture"
            val older = CareerCoachViewModel(remote, actual, storageKey = storageKey)
            CareerCoachStorage.migrated(storageKey)
            actualCache = CareerCoachCache(draft = "引継ぎ済みの入力")
            advanceUntilIdle()
            older.changeDraft("古い画面からの入力")
            older.send("古い画面からの本文")
            advanceUntilIdle()
            assertEquals("引継ぎ済みの入力", actualCache!!.draft)
            coVerify(exactly = 0) { actual.saveCoach(any()) }
            coVerify(exactly = 0) { remote.mutate(any(), any()) }
        }
}
