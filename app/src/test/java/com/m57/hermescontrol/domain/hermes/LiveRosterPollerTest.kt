package com.m57.hermescontrol.domain.hermes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveRosterPollerTest {
    private val scopeA = ConnectionScope("server", "connection", "profile-a", "process", 1)
    private val scopeB = scopeA.copy(serverProfileId = "profile-b", generation = 2)
    private val working = listOf(LiveSession("rt", "saved", LiveStatus.WORKING))

    @Test
    fun pollsOnlyWhileVisibleAndConnectedWithOneReadInFlight() =
        runTest {
            val reads = mutableListOf<ConnectionScope>()
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller =
                LiveRosterPoller({
                    reads += it
                    LiveRead.Available(it, working)
                }, { currentTime })
            val visible = launch { poller.poll(connection) }
            runCurrent()
            advanceTimeBy(8_001)
            assertEquals(3, reads.size)
            assertEquals(LiveRoster(scopeA, working, 8_000), poller.roster.value)

            connection.value = TransportConnection(null, LinkState.RECONNECTING)
            advanceTimeBy(20_000)
            assertEquals(3, reads.size)

            connection.value = TransportConnection(scopeA, LinkState.CONNECTED)
            runCurrent()
            visible.cancel()
            advanceTimeBy(20_000)
            // Hidden drawer: no lingering background loop.
            assertEquals(4, reads.size)
        }

    @Test
    fun lateResultFromThePreviousScopeIsDiscardedAfterSwitching() =
        runTest {
            val late = CompletableDeferred<LiveRead>()
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller =
                LiveRosterPoller({ scope ->
                    if (scope == scopeA) late.await() else LiveRead.Available(scope, emptyList())
                }, { currentTime })
            val visible = launch { poller.poll(connection) }
            runCurrent()
            connection.value = TransportConnection(scopeB, LinkState.CONNECTED)
            runCurrent()
            late.complete(LiveRead.Available(scopeA, working))
            runCurrent()
            assertEquals(LiveRoster(scopeB, emptyList(), 0), poller.roster.value)
            // A -> B -> A on a new socket generation never reuses A's old rows as fresh.
            val scopeA2 = scopeA.copy(generation = 3)
            connection.value = TransportConnection(scopeA2, LinkState.CONNECTED)
            runCurrent()
            assertEquals(scopeA2, poller.roster.value?.scope)
            assertEquals(emptyList<LiveSession>(), poller.roster.value?.sessions)
            visible.cancel()
        }

    @Test
    fun closingMakesRowsUncertainAndReopeningStaysUncertainUntilTheNewReadReturns() =
        runTest {
            var gate: CompletableDeferred<Unit>? = null
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller =
                LiveRosterPoller({
                    gate?.await()
                    LiveRead.Available(it, working)
                }, { currentTime })

            fun saved() =
                projectSessionActivity(connection.value, poller.roster.value, emptyList(), currentTime)["saved"]
            val first = launch { poller.poll(connection) }
            runCurrent()
            assertEquals(SessionActivity.RUNNING, saved())

            // Drawer closed / app backgrounded: the cached rows stop being liveness evidence at once.
            first.cancel()
            runCurrent()
            assertEquals(SessionActivity.UNCERTAIN, saved())

            // Reopened after the fresh window on the same scope; the refresh read is still in flight.
            advanceTimeBy(13_000)
            val opened = CompletableDeferred<Unit>()
            gate = opened
            val reopened = launch { poller.poll(connection) }
            runCurrent()
            assertEquals(SessionActivity.UNCERTAIN, saved())
            opened.complete(Unit)
            runCurrent()
            assertEquals(SessionActivity.RUNNING, saved())

            // A newer owner keeps its fresh rows when the older owner's cleanup runs late.
            val newer = launch { poller.poll(connection) }
            runCurrent()
            reopened.cancel()
            runCurrent()
            assertEquals(SessionActivity.RUNNING, saved())
            newer.cancel()
            runCurrent()
            assertEquals(SessionActivity.UNCERTAIN, saved())
        }

    @Test
    fun timeoutKeepsLastRowsMarksTheRosterFailedAndExpiresThemAfterTheFreshWindow() =
        runTest {
            var hang = false
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller =
                LiveRosterPoller({
                    if (hang) awaitCancellation() else LiveRead.Available(it, working)
                }, { currentTime }, intervalMs = 4_000, timeoutMs = 3_000)
            val visible = launch { poller.poll(connection) }
            runCurrent()
            hang = true
            advanceTimeBy(7_001)
            assertEquals(LiveRoster(scopeA, working, 0, ReadFailure.TIMEOUT_OR_UNAVAILABLE), poller.roster.value)
            val retained = projectHistoryActivity(connection.value, poller.roster.value, emptyList(), currentTime)
            assertEquals(SessionActivity.RUNNING, retained.activity["saved"])
            assertEquals(setOf("rt", "saved"), retained.rechecking)
            assertEquals(LIVE_ROSTER_FRESH_MS + 1, retained.validUntil)
            // Still failing at the deadline: no longer evidence, even though no new value was emitted.
            advanceTimeBy(5_000)
            val expired = projectHistoryActivity(connection.value, poller.roster.value, emptyList(), currentTime)
            assertEquals(SessionActivity.UNCERTAIN, expired.activity["saved"])
            assertEquals(emptySet<String>(), expired.rechecking)
            assertTrue(expired.unverified)
            visible.cancel()
        }

    @Test
    fun oneRpcTimeoutDoesNotEraseFreshWorkingAndAnEarlyRetryConfirmsIt() =
        runTest {
            // Installed timings: RPC rejects after 5 s, outer guard 6 s, cadence 4 s.
            var calls = 0
            var failing = setOf(2)
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller =
                LiveRosterPoller({
                    calls++
                    if (calls in failing) {
                        delay(5_000)
                        LiveRead.Unavailable(ReadFailure.TIMEOUT_OR_UNAVAILABLE)
                    } else {
                        LiveRead.Available(it, working)
                    }
                }, { currentTime })

            fun status() = projectHistoryActivity(connection.value, poller.roster.value, emptyList(), currentTime)
            val visible = launch { poller.poll(connection) }
            runCurrent()
            advanceTimeBy(9_001)
            // Last confirmed evidence is only 9 s old: still shown, flagged as rechecking, not confirmed.
            assertEquals(SessionActivity.RUNNING, status().activity["saved"])
            assertEquals(setOf("rt", "saved"), status().rechecking)
            assertFalse(status().unverified)
            // Retry after a short backoff instead of the full cadence, before the 12 s deadline.
            advanceTimeBy(1_000)
            assertEquals(3, calls)
            assertEquals(SessionActivity.RUNNING, status().activity["saved"])
            assertEquals(emptySet<String>(), status().rechecking)

            // Disconnect or a lifecycle stop never keep the fresh claim during a failed refresh.
            failing = setOf(4)
            advanceTimeBy(9_000)
            assertEquals(setOf("rt", "saved"), status().rechecking)
            connection.value = TransportConnection(scopeA, LinkState.RECONNECTING)
            runCurrent()
            assertEquals(SessionActivity.UNCERTAIN, status().activity["saved"])
            assertTrue(status().unverified)
            visible.cancel()
        }

    @Test
    fun nonTransientFailuresAndStoppedObservationAreNeverRetainedAsRunning() =
        runTest {
            var next: LiveRead? = null
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller = LiveRosterPoller({ next ?: LiveRead.Available(it, working) }, { currentTime })

            fun saved() =
                projectSessionActivity(connection.value, poller.roster.value, emptyList(), currentTime)["saved"]
            val visible = launch { poller.poll(connection) }
            runCurrent()
            next = LiveRead.Unavailable(ReadFailure.AUTH_REQUIRED)
            advanceTimeBy(4_001)
            assertEquals(SessionActivity.UNCERTAIN, saved())
            next = null
            advanceTimeBy(1_000)
            assertEquals(SessionActivity.RUNNING, saved())
            visible.cancel()
            runCurrent()
            assertEquals(SessionActivity.UNCERTAIN, saved())
        }

    @Test
    fun repeatedFastFailuresRetryOnceEarlyThenKeepTheNormalCadence() =
        runTest {
            val reads = mutableListOf<Long>()
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller =
                LiveRosterPoller({
                    reads += currentTime
                    if (reads.size ==
                        1
                    ) {
                        LiveRead.Available(it, working)
                    } else {
                        LiveRead.Unavailable(ReadFailure.TIMEOUT_OR_UNAVAILABLE)
                    }
                }, { currentTime })
            val visible = launch { poller.poll(connection) }
            advanceTimeBy(20_001)
            assertEquals(listOf(0L, 4_000, 5_000, 9_000, 13_000, 17_000), reads)
            visible.cancel()
        }

    @Test
    fun idleCurrentChatWithItsOwnRunningChildIsBackgroundWithoutSpillover() =
        runTest {
            var childRunning = true
            val checked = mutableListOf<Pair<ConnectionScope, String>>()
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller =
                LiveRosterPoller(
                    {
                        LiveRead.Available(
                            it,
                            listOf(
                                LiveSession("rt-cur", "saved-cur", LiveStatus.IDLE),
                                LiveSession("rt-other", "saved-other", LiveStatus.IDLE),
                            ),
                        )
                    },
                    { currentTime },
                    readBackground = { scope, id ->
                        checked += scope to id
                        BackgroundRead.Available(childRunning)
                    },
                    currentRuntime = { if (it == scopeA) "rt-cur" else null },
                )

            fun status() = projectHistoryActivity(connection.value, poller.roster.value, emptyList(), currentTime)
            val visible = launch { poller.poll(connection) }
            runCurrent()
            // Foreground idle is not overall completion; only this owner's own child work counts.
            assertEquals(
                mapOf("rt-cur" to SessionActivity.BACKGROUND, "saved-cur" to SessionActivity.BACKGROUND),
                status().activity,
            )
            assertEquals(listOf(scopeA to "rt-cur"), checked)

            childRunning = false
            advanceTimeBy(4_001)
            assertEquals(emptyMap<String, SessionActivity>(), status().activity)

            // Another profile's roster never borrows this scope's current chat or evidence.
            connection.value = TransportConnection(scopeB, LinkState.CONNECTED)
            runCurrent()
            assertEquals(listOf(scopeA to "rt-cur", scopeA to "rt-cur"), checked)
            assertEquals(emptyMap<String, BackgroundWork>(), poller.roster.value?.background)
            visible.cancel()
        }

    @Test
    fun ownerThatWentIdleIsCheckedAndFailedChecksStayUnknownNeverEmpty() =
        runTest {
            var foreground = LiveStatus.WORKING
            var answer: BackgroundRead = BackgroundRead.Available(true)
            val checked = mutableListOf<String>()
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller =
                LiveRosterPoller(
                    {
                        LiveRead.Available(
                            it,
                            listOf(
                                LiveSession("rt-a", "saved-a", foreground),
                                LiveSession("rt-b", "saved-b", foreground),
                                LiveSession("rt-quiet", "saved-quiet", LiveStatus.IDLE),
                            ),
                        )
                    },
                    { currentTime },
                    readBackground = { _, id ->
                        checked += id
                        if (id == "rt-b") BackgroundRead.Unavailable(ReadFailure.TIMEOUT_OR_UNAVAILABLE) else answer
                    },
                )

            fun status() = projectHistoryActivity(connection.value, poller.roster.value, emptyList(), currentTime)
            val visible = launch { poller.poll(connection) }
            runCurrent()
            assertEquals(emptyList<String>(), checked)

            foreground = LiveStatus.IDLE
            advanceTimeBy(4_001)
            assertEquals(SessionActivity.BACKGROUND, status().activity["saved-a"])
            // Never confirmed: a failed check is not "running", and not a fake "finished" either.
            assertEquals(SessionActivity.UNCERTAIN, status().activity["saved-b"])
            assertEquals(setOf("rt-a", "rt-b"), checked.toSet())

            answer = BackgroundRead.Unavailable(ReadFailure.TIMEOUT_OR_UNAVAILABLE)
            advanceTimeBy(4_000)
            assertEquals(SessionActivity.BACKGROUND, status().activity["saved-a"])
            assertTrue("saved-a" in status().rechecking)
            // Last confirmation at 4 s has expired by 16 s: unknown, never idle.
            advanceTimeBy(8_000)
            assertEquals(SessionActivity.UNCERTAIN, status().activity["saved-a"])

            answer = BackgroundRead.Available(false)
            advanceTimeBy(4_000)
            assertEquals(null, status().activity["saved-a"])
            val settled = checked.count { it == "rt-a" }
            advanceTimeBy(12_000)
            // A finished, non-current owner is no longer polled; a never-busy idle row never was.
            assertEquals(settled, checked.count { it == "rt-a" })
            assertFalse("rt-quiet" in checked)
            visible.cancel()
        }

    @Test
    fun backgroundChecksAreCappedSerializedAndNeverHoldTheRosterOrLandOnAnotherScope() =
        runTest {
            var foreground = LiveStatus.WORKING
            val gate = CompletableDeferred<Unit>()
            var inFlight = 0
            var maxInFlight = 0
            val started = mutableListOf<String>()
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller =
                LiveRosterPoller(
                    { scope ->
                        LiveRead.Available(scope, (1..10).map { LiveSession("rt-$it", "saved-$it", foreground) })
                    },
                    { currentTime },
                    readBackground = { _, id ->
                        started += id
                        inFlight++
                        maxInFlight = maxOf(maxInFlight, inFlight)
                        try {
                            // A late answer that ignores cancellation.
                            withContext(NonCancellable) { gate.await() }
                            BackgroundRead.Available(true)
                        } finally {
                            inFlight--
                        }
                    },
                )
            val visible = launch { poller.poll(connection) }
            runCurrent()
            foreground = LiveStatus.IDLE
            advanceTimeBy(4_001)
            assertEquals(BACKGROUND_READ_CONCURRENCY, started.size)

            // The roster keeps refreshing while background reads hang; no second batch starts.
            advanceTimeBy(4_000)
            assertEquals(8_000L, poller.roster.value?.readAt)
            assertEquals(BACKGROUND_READ_CONCURRENCY, started.size)

            connection.value = TransportConnection(scopeB, LinkState.CONNECTED)
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(scopeB, poller.roster.value?.scope)
            assertEquals(emptyMap<String, BackgroundWork>(), poller.roster.value?.background)
            assertTrue(maxInFlight <= BACKGROUND_READ_CONCURRENCY)
            assertTrue(started.distinct().size <= MAX_BACKGROUND_TARGETS)
            visible.cancel()
        }

    @Test
    fun unsupportedChildRostersStopBackgroundChecksForThatScope() =
        runTest {
            var calls = 0
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller =
                LiveRosterPoller(
                    { LiveRead.Available(it, listOf(LiveSession("rt-cur", "saved-cur", LiveStatus.IDLE))) },
                    { currentTime },
                    readBackground = { _, _ ->
                        calls++
                        BackgroundRead.Unavailable(ReadFailure.UNSUPPORTED)
                    },
                    currentRuntime = { "rt-cur" },
                )
            val visible = launch { poller.poll(connection) }
            advanceTimeBy(20_000)
            assertEquals(1, calls)
            assertEquals(
                SessionActivity.UNCERTAIN,
                projectSessionActivity(connection.value, poller.roster.value, emptyList(), currentTime)["saved-cur"],
            )
            visible.cancel()
        }

    @Test
    fun aliasesSurviveOnlyForTheSameBusyRuntimeInTheSameExactScope() =
        runTest {
            var rows = listOf(LiveSession("runtime-stable", "saved-root", LiveStatus.WORKING))
            val connection = MutableStateFlow(TransportConnection(scopeA, LinkState.CONNECTED))
            val poller = LiveRosterPoller({ LiveRead.Available(it, rows) }, { currentTime })

            fun status() = projectSessionActivity(connection.value, poller.roster.value, emptyList(), currentTime)
            val visible = launch { poller.poll(connection) }
            runCurrent()

            // Rotation keeps the confirmed alias; repeated rotation stays bounded.
            fun next(vararg sessions: LiveSession) {
                rows = sessions.toList()
                advanceTimeBy(4_000)
                runCurrent()
            }
            repeat(20) { index -> next(LiveSession("runtime-stable", "saved-$index", LiveStatus.WORKING)) }
            val aliases =
                poller.roster.value!!
                    .sessions!!
                    .single()
                    .aliases
            assertTrue(aliases.size <= MAX_LIVE_ALIASES)
            assertTrue("saved-18" in aliases)
            assertEquals(SessionActivity.RUNNING, status()["saved-18"])

            // Another runtime now owns an earlier key directly: its own idle wins, the alias is dropped.
            next(
                LiveSession("runtime-stable", "saved-tip", LiveStatus.WORKING),
                LiveSession("runtime-new", "saved-18", LiveStatus.IDLE),
            )
            assertEquals(null, status()["saved-18"])
            assertEquals(SessionActivity.RUNNING, status()["saved-19"])

            // Removal settles the owner; a runtime that reappears with the same id is new.
            next()
            next(LiveSession("runtime-stable", "saved-again", LiveStatus.WORKING))
            assertEquals(
                emptySet<String>(),
                poller.roster.value!!
                    .sessions!!
                    .single()
                    .aliases,
            )
            assertEquals(null, status()["saved-tip"])

            // A new socket generation (or profile) never inherits the previous scope's aliases.
            rows = listOf(LiveSession("runtime-stable", "saved-rotated", LiveStatus.WORKING))
            connection.value = TransportConnection(scopeA.copy(generation = 2), LinkState.CONNECTED)
            runCurrent()
            assertEquals(
                emptySet<String>(),
                poller.roster.value!!
                    .sessions!!
                    .single()
                    .aliases,
            )
            assertEquals(null, status()["saved-again"])
            visible.cancel()
        }
}
