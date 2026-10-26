package com.m57.hermescontrol.ui.connections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleConnectionViewModelTest {
    private class Repository : GoogleConnectionRepository {
        var begins = 0
        var completes = 0
        var fail = false
        var current = GoogleConnectionStatus("disconnected")
        var requestedScope = GoogleConnectionViewModel.GMAIL_READONLY
        var selectedAccount: String? = null

        override suspend fun status() = current

        override suspend fun begin(accountId: String?): GoogleConnectionFlow {
            begins++
            selectedAccount = accountId
            return GoogleConnectionFlow("synthetic-flow", "synthetic-client", requestedScope)
        }

        override suspend fun manage(
            action: String,
            accountId: String,
            label: String?,
        ): GoogleConnectionStatus {
            current =
                current.copy(
                    accounts =
                        current.accounts.mapNotNull {
                            when {
                                it.id != accountId -> it
                                action == "disconnect" -> null
                                else -> it.copy(label = label.orEmpty())
                            }
                        },
                )
            return current
        }

        override suspend fun complete(
            flow: GoogleConnectionFlow,
            code: String,
        ): GoogleConnectionStatus {
            completes++
            if (fail) error("private-access-token-and-provider-error")
            return GoogleConnectionStatus("connected", "jobs@example.test")
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun reconnectAndManagementUseOnlyTheSelectedAccount() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo =
                Repository().apply {
                    current =
                        GoogleConnectionStatus(
                            "connected",
                            accounts =
                                listOf(
                                    GoogleConnectedAccount("first", "jobs@example.test"),
                                    GoogleConnectedAccount("second", "personal@example.test"),
                                ),
                            can_add = true,
                            multi_account = true,
                        )
                }
            val model = GoogleConnectionViewModel(repo)
            model.refresh()
            advanceUntilIdle()
            model.rename("second", "個人")
            advanceUntilIdle()
            assertEquals(
                "",
                model.state.value.status
                    ?.accounts
                    ?.first()
                    ?.label,
            )
            assertEquals(
                "個人",
                model.state.value.status
                    ?.accounts
                    ?.last()
                    ?.label,
            )
            model.begin("second") { null }
            advanceUntilIdle()
            assertEquals("second", repo.selectedAccount)
            model.cancel()
            model.disconnect("second")
            advanceUntilIdle()
            assertEquals(
                listOf("first"),
                model.state.value.status
                    ?.accounts
                    ?.map { it.id },
            )
        }

    @Test
    fun nativeConsentMustFinishBeforeConnectedAndDoubleTapIsIgnored() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo = Repository()
            val model = GoogleConnectionViewModel(repo)
            model.refresh()
            advanceUntilIdle()
            model.begin { null }
            model.begin { error("Duplicate consent") }
            advanceUntilIdle()
            assertEquals(1, repo.begins)
            assertTrue(model.state.value.busy)
            assertEquals(
                "disconnected",
                model.state.value.status
                    ?.state,
            )
            assertEquals(0, repo.completes)
            model.complete("synthetic-code")
            advanceUntilIdle()
            assertEquals(
                "jobs@example.test",
                model.state.value.status
                    ?.email,
            )
            assertEquals(
                "connected",
                model.state.value.status
                    ?.state,
            )
            assertFalse(model.state.value.busy)
            model.complete("replayed-code")
            advanceUntilIdle()
            assertEquals(1, repo.completes)
        }

    @Test
    fun cancelAndLeavingScreenIgnoreLateNativeResult() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo = Repository()
            val model = GoogleConnectionViewModel(repo)
            model.begin { null }
            advanceUntilIdle()
            model.cancel()
            model.complete("late-code")
            advanceUntilIdle()
            assertEquals(0, repo.completes)
            assertNull(model.state.value.status)
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.error)
        }

    @Test
    fun immediateGoogleResultAlsoWaitsForServerVerification() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo = Repository()
            val model = GoogleConnectionViewModel(repo)
            model.begin { "immediate-code" }
            advanceUntilIdle()
            assertEquals(1, repo.completes)
            assertEquals(
                "connected",
                model.state.value.status
                    ?.state,
            )
            assertFalse(model.state.value.busy)
        }

    @Test
    fun failedExchangeDoesNotExposeExceptionOrShowConnected() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo = Repository().apply { fail = true }
            val model = GoogleConnectionViewModel(repo)
            model.begin { "synthetic-code" }
            advanceUntilIdle()
            assertNull(model.state.value.status)
            assertFalse(model.state.value.busy)
            assertFalse(
                model.state.value.error
                    .orEmpty()
                    .contains("private-access-token"),
            )
            model.complete("no-automatic-retry")
            advanceUntilIdle()
            assertEquals(1, repo.completes)
        }

    @Test
    fun unexpectedScopeNeverOpensGoogleConsent() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo = Repository().apply { requestedScope = "https://www.googleapis.com/auth/gmail.send" }
            val model = GoogleConnectionViewModel(repo)
            model.begin { error("Must not open consent") }
            advanceUntilIdle()
            assertEquals(0, repo.completes)
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.status)
        }
}
