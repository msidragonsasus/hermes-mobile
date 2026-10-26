package com.m57.hermescontrol.ui.connections

import com.m57.hermescontrol.data.model.McpOAuthFlowResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SlackConnectionTest {
    private class Repository : SlackConnectionRepository {
        var statusValue = SlackConnectionStatus("ready", enabled = true)
        var starts = mutableListOf<String>()
        var cancelled = mutableListOf<String>()
        var polls = 0
        var beginFailure = false
        var flowFailure: Exception? = null
        var initial =
            McpOAuthFlowResponse(
                "synthetic-flow",
                "slack",
                "authorization_required",
                "https://slack.com/oauth/v2_user/authorize?client_id=1.2&state=synthetic",
            )
        var next = McpOAuthFlowResponse("synthetic-flow", "slack", "approved")

        override suspend fun status() = statusValue

        override suspend fun begin(requestId: String): McpOAuthFlowResponse {
            starts.add(requestId)
            if (beginFailure) error("provider-secret-and-code")
            return initial
        }

        override suspend fun flow(id: String): McpOAuthFlowResponse {
            polls++
            flowFailure?.let { throw it }
            if (next.status == "approved") statusValue = statusValue.copy(state = "authorized")
            return next
        }

        override suspend fun cancel(id: String) {
            cancelled.add(id)
        }
    }

    @After fun teardown() = Dispatchers.resetMain()

    @Test fun onlySlackAuthorizationPagesAreAccepted() {
        assertTrue(isSlackAuthorizationUrl("https://slack.com/oauth/v2_user/authorize?state=synthetic"))
        listOf(
            "https://slack.com.attacker.test/oauth/v2_user/authorize",
            "http://slack.com/oauth/v2_user/authorize",
            "https://user:password@slack.com/oauth/v2_user/authorize",
            "https://slack.com:8443/oauth/v2_user/authorize",
            "https://slack.com/api/other",
            "javascript:alert(1)",
        ).forEach { assertFalse(isSlackAuthorizationUrl(it)) }
    }

    @Test fun loadingStatusDoesNotStartAuthorization() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = Repository()
            val model = SlackConnectionViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            assertEquals(
                "ready",
                model.state.value.status
                    ?.state,
            )
            assertTrue(repository.starts.isEmpty())
        }

    @Test fun explicitLoginOpensBrowserOnceAndWaitsForProviderCompletion() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = Repository()
            val model = SlackConnectionViewModel(repository, pollIntervalMs = 1, maxPolls = 2)
            model.refresh()
            advanceUntilIdle()
            var opened = 0
            model.connect { opened++ }
            model.connect { opened++ }
            runCurrent()
            assertTrue(model.state.value.connecting)
            assertEquals(
                "ready",
                model.state.value.status
                    ?.state,
            )
            advanceUntilIdle()
            assertEquals(1, opened)
            assertEquals(1, repository.starts.size)
            assertEquals(
                "authorized",
                model.state.value.status
                    ?.state,
            )
            assertFalse(model.state.value.connecting)
            assertTrue(repository.cancelled.isEmpty())
        }

    @Test fun setupRequiredCannotStartLoginOrRequestSecrets() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = Repository().apply { statusValue = SlackConnectionStatus("setup_required") }
            val model = SlackConnectionViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            model.connect { error("must not open") }
            advanceUntilIdle()
            assertTrue(repository.starts.isEmpty())
        }

    @Test fun lostBeginResponseKeepsItsRequestId() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = Repository().apply { beginFailure = true }
            val model = SlackConnectionViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            model.connect {}
            advanceUntilIdle()
            assertEquals(1, repository.starts.size)
            assertFalse(
                model.state.value.error
                    .orEmpty()
                    .contains("provider-secret"),
            )
            model.connect {}
            advanceUntilIdle()
            assertEquals(repository.starts[0], repository.starts[1])
        }

    @Test fun maliciousAuthorizationUrlIsCancelledWithoutOpeningBrowser() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository =
                Repository().apply {
                    initial =
                        initial.copy(authorizationUrl = "https://attacker.test/oauth")
                }
            val model = SlackConnectionViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            model.connect { error("must not open") }
            advanceUntilIdle()
            assertEquals(listOf("synthetic-flow"), repository.cancelled)
            assertEquals(0, repository.polls)
            assertNull(model.state.value.message)
        }

    @Test fun cancellationStopsPollingAndCancelsOnlyTheOwnedFlow() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = Repository().apply { next = initial }
            val model = SlackConnectionViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            model.connect {}
            runCurrent()
            model.cancel()
            advanceUntilIdle()
            assertEquals(listOf("synthetic-flow"), repository.cancelled)
            assertFalse(model.state.value.connecting)
            assertEquals(0, repository.polls)
        }

    @Test fun completedLastPollIsNotMisreportedAsTimeout() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = Repository()
            val model = SlackConnectionViewModel(repository, pollIntervalMs = 1, maxPolls = 1)
            model.refresh()
            advanceUntilIdle()
            model.connect {}
            advanceUntilIdle()
            assertEquals(
                "authorized",
                model.state.value.status
                    ?.state,
            )
            assertTrue(repository.cancelled.isEmpty())
        }

    @Test fun foreignFlowCannotCompleteTheLogin() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = Repository().apply { next = next.copy(flowId = "foreign-flow") }
            val model = SlackConnectionViewModel(repository, pollIntervalMs = 1, maxPolls = 4)
            model.refresh()
            advanceUntilIdle()
            model.connect {}
            advanceUntilIdle()
            assertNull(model.state.value.message)
            assertFalse(model.state.value.connecting)
        }

    @Test fun expiredFlowAllowsANewExplicitLogin() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = Repository().apply { flowFailure = SlackConnectionFailure("flow_expired") }
            val model = SlackConnectionViewModel(repository, pollIntervalMs = 1, maxPolls = 4)
            model.refresh()
            advanceUntilIdle()
            model.connect {}
            advanceUntilIdle()
            assertEquals(1, repository.polls)
            repository.flowFailure = null
            model.connect {}
            advanceUntilIdle()
            assertFalse(repository.starts[0] == repository.starts[1])
            assertEquals(
                "authorized",
                model.state.value.status
                    ?.state,
            )
        }

    @Test fun uncertainPollingFailureKeepsTheOriginalRequestForExplicitRetry() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = Repository().apply { flowFailure = java.io.IOException("synthetic disconnect") }
            val model = SlackConnectionViewModel(repository, pollIntervalMs = 1, maxPolls = 4)
            model.refresh()
            advanceUntilIdle()
            model.connect {}
            advanceUntilIdle()
            assertEquals(3, repository.polls)
            repository.flowFailure = null
            model.connect {}
            advanceUntilIdle()
            assertEquals(repository.starts[0], repository.starts[1])
            assertEquals(
                "authorized",
                model.state.value.status
                    ?.state,
            )
        }
}
