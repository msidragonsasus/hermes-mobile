package com.m57.hermescontrol.data.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Process-wide invalidation: a cached result for another connection is dropped even with no ViewModel alive. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateConnectionWatcherTest {
    private lateinit var scopes: FakeUpdateScopes

    private fun available(scope: UpdateScope) =
        AppUpdateState.UpdateAvailable(
            UpdateCandidate(
                UpdateSource.CONNECTED_SERVER,
                scope,
                AppUpdateCache.generation,
                "1.0.2",
                2,
                "0".repeat(64),
                1,
                null,
                "http://x/",
            ),
        )

    @Before
    fun setUp() {
        AppUpdateCache.reset()
        scopes = FakeUpdateScopes()
    }

    @Test
    fun switchingConnectionInvalidatesCacheAndBumpsGeneration() =
        runTest {
            AppUpdateCache.update(available(TEST_SERVER_SCOPE))
            backgroundScope.launch { UpdateConnectionWatcher.watch(scopes) }
            runCurrent()
            val before = AppUpdateCache.generation
            assertEquals("same connection: kept", "1.0.2", AppUpdateCache.state.value.releaseTag())

            scopes.connect(TEST_SERVER_SCOPE.copy(connectionEpoch = 8))
            runCurrent()

            assertEquals(AppUpdateState.Idle, AppUpdateCache.state.value)
            assertEquals(before + 1, AppUpdateCache.generation)
        }

    @Test
    fun unchangedConnectionDoesNotBumpGeneration() {
        AppUpdateCache.update(available(TEST_SERVER_SCOPE))
        val before = AppUpdateCache.generation
        UpdateConnectionWatcher.invalidateIfStale(scopes)
        assertEquals(before, AppUpdateCache.generation)
        scopes.connect(null)
        UpdateConnectionWatcher.invalidateIfStale(scopes)
        assertEquals(before + 1, AppUpdateCache.generation)
        UpdateConnectionWatcher.invalidateIfStale(scopes)
        assertEquals("already idle: no further bump", before + 1, AppUpdateCache.generation)
    }
}
