package com.m57.hermescontrol.career

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
class CareerThreadsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val local = mockk<CareerLocalStore>()
    private val api = mockk<HermesApiService>()
    private val storage = mutableMapOf<String, String?>()
    private val profile = MutableStateFlow<String?>("profile-a")
    private val created = CareerThread("thread:new", "面接対策", "company-a")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AuthManager, ApiClient)
        every { AuthManager.baseUrlFlow } returns MutableStateFlow("https://synthetic.invalid")
        every { AuthManager.selectedProfileFlow } returns MutableStateFlow<String?>("connection-a")
        every { AuthManager.activeProfileId } returns profile
        every { AuthManager.isGatedMode() } returns true
        every { ApiClient.hermesApi } returns api
        coEvery { local.uiValue(any()) } answers { storage[firstArg()] }
        coEvery { local.saveUiValue(any(), any()) } answers {
            storage[firstArg()] = secondArg()
            Unit
        }
        coEvery { local.hasPendingThread(any()) } returns false
        coEvery { local.legacyDraftThreads() } returns emptyList()
        coEvery { local.cached() } returns null
        coEvery { api.careerCoachCapabilities("profile-a") } returns
            CareerCapabilities(features = listOf("conversation_threads"))
        coEvery { api.careerThreads("profile-a") } returns
            CareerThreads(profile = "profile-a", threads = listOf(created))
        coEvery { api.careerCreateThread("profile-a", any()) } returns created
        coEvery { api.careerUpdateThread(any(), "profile-a", any()) } returns created
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test fun preUpgradeControlRetryPreservesTheExactRequestFingerprint() =
        runTest(dispatcher) {
            val old =
                buildJsonObject {
                    put("operation_id", "old-control")
                    put("id", "old-run")
                    put("control", "stop")
                }
            coEvery { api.careerThreadMutation("control", "profile-a", any()) } returns JsonObject(emptyMap())
            DashboardCareerCoachRepository(careerScope(), "company:a").mutate("control", old)
            coVerify(exactly = 1) { api.careerThreadMutation("control", "profile-a", old) }
            val newer = old.with("thread_id" to kotlinx.serialization.json.JsonPrimitive("company:a"))
            DashboardCareerCoachRepository(careerScope(), "company:a").mutate("control", newer)
            coVerify(exactly = 1) { api.careerThreadMutation("control", "profile-a", newer) }
        }

    @Test fun startingAndReturningToDraftSkipsServerCreationAndPreservesCompanyContext() =
        runTest(dispatcher) {
            NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
            val model = CareerThreadsViewModel(careerScope(), local)
            advanceUntilIdle()
            val key = CareerThreadsKey("company-a", "offer-a")
            model.startConversation(key)
            model.startConversation(key)
            advanceUntilIdle()
            val first = NavigationController.backStack!!.last() as CareerConversationKey
            val draft = CareerNewConversation.fromThreadId(first.threadId)!!
            assertEquals("company-a", draft.companyId)
            assertEquals("offer-a", draft.offerId)
            NavigationController.goBack()
            model.startConversation(key)
            advanceUntilIdle()
            assertEquals(first, NavigationController.backStack!!.last())
            coVerify(exactly = 0) { api.careerCreateThread(any(), any()) }
            coVerify(exactly = 0) { api.careerThreadMutation(any(), any(), any()) }
            assertEquals(2, NavigationController.backStack!!.size)
        }

    @Test fun cachedThreadSupportReopensLocalDraftOfflineWithoutMutation() =
        runTest(dispatcher) {
            NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
            val online = CareerThreadsViewModel(careerScope(), local)
            advanceUntilIdle()
            online.startConversation(CareerThreadsKey())
            advanceUntilIdle()
            val route = NavigationController.backStack!!.last()
            NavigationController.goBack()
            coEvery { api.careerCoachCapabilities(any()) } throws IOException("fixture offline")
            val offline = CareerThreadsViewModel(careerScope(), local)
            advanceUntilIdle()
            assertTrue(offline.state.value.stale)
            offline.startConversation(CareerThreadsKey())
            advanceUntilIdle()
            assertEquals(route, NavigationController.backStack!!.last())
            coVerify(exactly = 0) { api.careerCreateThread(any(), any()) }
        }

    @Test fun uncertainCreationSurvivesRecreationAndRetriesOnlyWithSameOperation() =
        runTest(dispatcher) {
            coEvery { api.careerCreateThread(any(), any()) } throws IOException("offline")
            val first = CareerThreadsViewModel(careerScope(), local)
            advanceUntilIdle()
            first.create("面接対策", "company-a", null)
            advanceUntilIdle()
            val operation = first.state.value.pending!!
            assertNotNull(operation.payload.text("operation_id"))
            val restored = CareerThreadsViewModel(careerScope(), local)
            advanceUntilIdle()
            assertEquals(operation, restored.state.value.pending)
            coVerify(exactly = 1) { api.careerCreateThread(any(), any()) }
            coEvery { api.careerCreateThread("profile-a", operation.payload) } returns created
            restored.retry()
            advanceUntilIdle()
            coVerify(exactly = 2) { api.careerCreateThread("profile-a", operation.payload) }
            assertNull(restored.state.value.pending)
            assertEquals(created, restored.state.value.created)
        }

    @Test fun unreadableMetadataIsNeverOverwritten() =
        runTest(dispatcher) {
            coEvery { local.uiValue(any()) } throws IOException("cannot decrypt")
            val model = CareerThreadsViewModel(careerScope(), local)
            advanceUntilIdle()
            model.refresh()
            model.create("新規", null, null)
            advanceUntilIdle()
            assertNotNull(model.state.value.error)
            coVerify(exactly = 0) { local.saveUiValue(any(), any()) }
            coVerify(exactly = 0) { api.careerCreateThread(any(), any()) }
        }

    @Test fun localWriteFailurePreventsSendingAndKeepsPendingInMemory() =
        runTest(dispatcher) {
            val model = CareerThreadsViewModel(careerScope(), local)
            advanceUntilIdle()
            coEvery { local.saveUiValue("thread-operation", any()) } throws IOException("disk full")
            model.create("下書き", null, null)
            advanceUntilIdle()
            assertNotNull(model.state.value.pending)
            coVerify(exactly = 0) { api.careerCreateThread(any(), any()) }
        }

    @Test fun profileSwitchAndUnconfirmedMessagePreventMutation() =
        runTest(dispatcher) {
            val model = CareerThreadsViewModel(careerScope(), local)
            advanceUntilIdle()
            coEvery { local.hasPendingThread(created.id) } returns true
            model.update(created, archived = true)
            advanceUntilIdle()
            coVerify(exactly = 0) { api.careerUpdateThread(any(), any(), any()) }
            model.refresh()
            advanceUntilIdle()
            profile.value = "profile-b"
            model.create("別の会話", null, null)
            advanceUntilIdle()
            coVerify(exactly = 0) { api.careerCreateThread(any(), any()) }
            assertNotNull(model.state.value.error)
        }

    @Test fun unsentLegacyCompanyDraftRemainsReachableWithoutServerHistory() =
        runTest(dispatcher) {
            coEvery { local.legacyDraftThreads() } returns listOf("company:legacy")
            val model = CareerThreadsViewModel(careerScope(), local)
            advanceUntilIdle()
            val legacy =
                model.state.value.threads
                    .first { it.id == "company:legacy" }
            assertEquals("legacy", legacy.company_id)
            assertTrue(legacy.local_only)
            coVerify(exactly = 0) { api.careerCreateThread(any(), any()) }
        }

    @Test fun oldServerKeepsLegacyConversationWithoutClaimingThreadSupport() =
        runTest(dispatcher) {
            coEvery { api.careerCoachCapabilities(any()) } returns CareerCapabilities()
            val model = CareerThreadsViewModel(careerScope(), local)
            advanceUntilIdle()
            assertEquals(
                listOf("global"),
                model.state.value.threads
                    .map { it.id },
            )
            assertTrue(model.state.value.capabilitiesChecked)
            assertEquals(false, model.state.value.supported)
            model.create("新規", null, null)
            coVerify(exactly = 0) { api.careerCreateThread(any(), any()) }
        }

    @Test fun completedReceiptStartsFreshOfflineButUnresolvedMessageResumesSameDraft() =
        runTest(dispatcher) {
            val draftLocal = mockk<CareerLocalStore>()
            val previous = CareerNewConversation(id = "completed-fixture")
            storage[previous.registryKey] = Json.encodeToString(previous)
            val receipt =
                CareerNewConversationReceipt(
                    buildJsonObject {
                        put("operation_id", "fixture-create")
                    },
                    CareerThread("thread:completed-fixture", "以前の相談"),
                )
            coEvery { draftLocal.uiValue("new-conversation-receipt") } returns Json.encodeToString(receipt)
            coEvery { local.hasPendingThread(receipt.thread!!.id) } returns true
            NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
            val model = CareerThreadsViewModel(careerScope(), local) { draftLocal }
            advanceUntilIdle()
            model.startConversation(CareerThreadsKey())
            advanceUntilIdle()
            assertEquals(previous.threadId, (NavigationController.backStack!!.last() as CareerConversationKey).threadId)
            NavigationController.goBack()
            coEvery { local.hasPendingThread(receipt.thread!!.id) } returns false
            coEvery { api.careerCoachCapabilities(any()) } throws IOException("fixture offline")
            model.refresh()
            advanceUntilIdle()
            model.startConversation(CareerThreadsKey())
            advanceUntilIdle()
            val fresh =
                CareerNewConversation.fromThreadId(
                    (NavigationController.backStack!!.last() as CareerConversationKey).threadId,
                )!!
            assertTrue(previous.id != fresh.id)
            assertEquals(Json.encodeToString(fresh), storage[fresh.registryKey])
            coVerify(exactly = 0) { api.careerCreateThread(any(), any()) }
            coVerify(exactly = 0) { api.careerThreadMutation(any(), any(), any()) }
        }

    @Test fun releasedConflictingDraftRemainsReachableOfflineWithoutBlockingNew() =
        runTest(dispatcher) {
            val draftLocal = mockk<CareerLocalStore>()
            val recoverable =
                CareerNewConversation(id = "recovery-fixture", companyId = "company-a", offerId = "offer-a")
            storage["threads-supported"] = "true"
            CareerNewConversationRecovery.remember(local, recoverable)
            coEvery { draftLocal.coach() } returns
                CareerCoachCache(
                    draft = "未確認の本文",
                    pending =
                        CareerPendingOperation(
                            "message",
                            buildJsonObject { put("operation_id", "fixture-send") },
                        ),
                )
            coEvery { draftLocal.uiValue(any()) } returns
                Json.encodeToString(
                    CareerNewConversationReceipt(buildJsonObject { put("operation_id", "fixture-create") }),
                )
            coEvery { api.careerCoachCapabilities(any()) } throws IOException("fixture offline")
            NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
            val model = CareerThreadsViewModel(careerScope(), local) { draftLocal }
            advanceUntilIdle()
            val row =
                model.state.value.threads
                    .single { it.id == recoverable.threadId }
            assertTrue(row.local_only)
            assertTrue(row.title.contains("端末の下書き"))
            assertEquals("未確認の本文", row.last_message)
            assertEquals("company-a", row.company_id)
            assertEquals("offer-a", row.offer_id)
            model.startConversation(CareerThreadsKey("company-a", "offer-a"))
            advanceUntilIdle()
            val route = NavigationController.backStack!!.last() as CareerConversationKey
            assertTrue(route.threadId != recoverable.threadId)
            assertEquals(listOf(recoverable), CareerNewConversationRecovery.entries(local))
            coVerify(exactly = 0) { api.careerCreateThread(any(), any()) }
        }
}
