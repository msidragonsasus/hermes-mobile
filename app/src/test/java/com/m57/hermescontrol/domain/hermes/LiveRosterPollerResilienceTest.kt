package com.m57.hermescontrol.domain.hermes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveRosterPollerResilienceTest {
    private val scope = ConnectionScope("server", "connection", "profile", "process", 1)
    private val connected = TransportConnection(scope, LinkState.CONNECTED)
    private val working = LiveSession("runtime", "saved", LiveStatus.WORKING)
    private val idle = working.copy(status = LiveStatus.IDLE)

    @Test
    fun productionDefaultClockUsesMonotonicFreshnessThroughExpiry() =
        runTest {
            val connection = MutableStateFlow(connected)
            val poller = LiveRosterPoller({ LiveRead.Available(it, listOf(working)) })
            val beforeRead = System.nanoTime() / 1_000_000L
            val visible = launch { poller.poll(connection) }
            try {
                runCurrent()
                val afterRead = System.nanoTime() / 1_000_000L
                val roster = requireNotNull(poller.roster.value)
                assertTrue(
                    "Production freshness timestamps must use the monotonic read bracket",
                    roster.readAt in beforeRead..afterRead,
                )
                val fresh = projectHistoryActivity(connected, roster, emptyList(), afterRead)
                assertEquals(SessionActivity.RUNNING, fresh.activity["saved"])
                val deadline = roster.readAt + LIVE_ROSTER_FRESH_MS + 1
                assertEquals(deadline, fresh.validUntil)

                val failed = roster.copy(failure = ReadFailure.TIMEOUT_OR_UNAVAILABLE)
                val retained = projectHistoryActivity(connected, failed, emptyList(), deadline - 1)
                assertEquals(SessionActivity.RUNNING, retained.activity["saved"])
                assertTrue("saved" in retained.rechecking)
                val expired = projectHistoryActivity(connected, failed, emptyList(), deadline)
                assertEquals(SessionActivity.UNCERTAIN, expired.activity["saved"])
                assertTrue(expired.unverified)
                assertTrue(expired.rechecking.isEmpty())
                assertEquals(null, expired.validUntil)
            } finally {
                visible.cancel()
            }
        }

    @Test
    fun closingAfterATransientFailureInvalidatesFreshEvidenceAcrossDelayedAndFailedReopening() =
        runTest {
            val connection = MutableStateFlow(connected)
            var answer: LiveRead = LiveRead.Available(scope, listOf(working))
            var gate: CompletableDeferred<Unit>? = null
            val poller =
                LiveRosterPoller({
                    gate?.await()
                    answer
                }, { currentTime })

            fun status() = projectHistoryActivity(connected, poller.roster.value, emptyList(), currentTime)

            val first = launch { poller.poll(connection) }
            runCurrent()
            answer = LiveRead.Unavailable(ReadFailure.TIMEOUT_OR_UNAVAILABLE)
            advanceTimeBy(4_001)
            assertEquals(SessionActivity.RUNNING, status().activity["saved"])
            assertTrue("saved" in status().rechecking)
            first.cancel()
            runCurrent()
            assertEquals(
                "Closing is not a transient refresh failure",
                SessionActivity.UNCERTAIN,
                status().activity["saved"],
            )
            assertTrue(status().unverified)

            gate = CompletableDeferred()
            val reopened = launch { poller.poll(connection) }
            runCurrent()
            assertEquals(SessionActivity.UNCERTAIN, status().activity["saved"])
            gate.complete(Unit)
            runCurrent()
            assertEquals(
                "A failed first read cannot revive the old proof",
                SessionActivity.UNCERTAIN,
                status().activity["saved"],
            )
            assertTrue(status().rechecking.isEmpty())
            answer = LiveRead.Available(scope, listOf(working))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(SessionActivity.RUNNING, status().activity["saved"])
            reopened.cancel()
        }

    @Test
    fun freshIdleRosterAfterRestartCannotReactivateCachedBackgroundBeforeItsOwnReadReturns() =
        runTest {
            val connection = MutableStateFlow(connected)
            var gate: CompletableDeferred<Unit>? = null
            var background: BackgroundRead = BackgroundRead.Available(true)
            val poller =
                LiveRosterPoller(
                    { LiveRead.Available(it, listOf(idle)) },
                    { currentTime },
                    readBackground = { _, _ ->
                        gate?.await()
                        background
                    },
                    currentRuntime = { "runtime" },
                )

            fun status() = projectHistoryActivity(connected, poller.roster.value, emptyList(), currentTime)

            val first = launch { poller.poll(connection) }
            runCurrent()
            assertEquals(SessionActivity.BACKGROUND, status().activity["saved"])
            first.cancel()
            runCurrent()
            gate = CompletableDeferred()
            val reopened = launch { poller.poll(connection) }
            runCurrent()
            assertEquals(
                "A new foreground read does not renew child-work evidence",
                SessionActivity.UNCERTAIN,
                status().activity["saved"],
            )
            assertTrue(status().unverified)
            background = BackgroundRead.Unavailable(ReadFailure.TIMEOUT_OR_UNAVAILABLE)
            gate.complete(Unit)
            runCurrent()
            assertEquals(SessionActivity.UNCERTAIN, status().activity["saved"])
            assertTrue(status().rechecking.isEmpty())
            gate = null
            background = BackgroundRead.Available(true)
            advanceTimeBy(4_001)
            assertEquals(SessionActivity.BACKGROUND, status().activity["saved"])

            // The superseded owner's finally block must not invalidate the newer owner's proof.
            val newer = launch { poller.poll(connection) }
            runCurrent()
            reopened.cancel()
            runCurrent()
            assertEquals(SessionActivity.BACKGROUND, status().activity["saved"])
            newer.cancel()
        }

    @Test
    fun everyRecentlyBusyIdleOwnerIsCheckedWithoutStarvingBehindConfirmedBackgroundWork() =
        runTest {
            val connection = MutableStateFlow(connected)
            val owners =
                (0 until MAX_BACKGROUND_TARGETS + 2).map {
                    LiveSession(
                        "owner-$it",
                        "saved-$it",
                        LiveStatus.WORKING,
                    )
                }
            var rows = owners
            val reads = mutableListOf<Pair<Long, String>>()
            val poller =
                LiveRosterPoller(
                    { LiveRead.Available(it, rows) },
                    { currentTime },
                    readBackground = { _, id ->
                        reads += currentTime to id
                        BackgroundRead.Available(true)
                    },
                    currentRuntime = { "owner-0" },
                )
            val visible = launch { poller.poll(connection) }
            runCurrent()
            rows = owners.map { it.copy(status = LiveStatus.IDLE) }
            advanceTimeBy(12_001)

            assertEquals(
                "A full batch must not starve other live owners forever",
                owners
                    .map {
                        it.runtimeId
                    }.toSet(),
                reads.map { it.second }.toSet(),
            )
            assertTrue(reads.groupBy { it.first }.values.all { it.size <= MAX_BACKGROUND_TARGETS })
            val result = projectHistoryActivity(connected, poller.roster.value, emptyList(), currentTime)
            owners.forEach { assertEquals(SessionActivity.BACKGROUND, result.activity[it.savedId]) }
            visible.cancel()
        }

    @Test
    fun confirmedIdleOwnerIsRecheckedWhenBusyChurnOverlapsInFlightBatches() =
        runTest {
            val connection = MutableStateFlow(connected)
            val owners =
                (listOf("current", "persistent") + (1..3).map { "churn-$it" }).map {
                    LiveSession(it, "$it-saved", LiveStatus.WORKING)
                }
            val stableOwners = setOf("current", "persistent")
            var rows = owners
            val reads = mutableListOf<Pair<Long, String>>()
            var concurrentReads = 0
            var peakConcurrentReads = 0
            val poller =
                LiveRosterPoller(
                    { LiveRead.Available(it, rows) },
                    { currentTime },
                    readBackground = { _, id ->
                        reads += currentTime to id
                        concurrentReads++
                        peakConcurrentReads = maxOf(peakConcurrentReads, concurrentReads)
                        try {
                            if (currentTime >= 8_000L) delay(5_000L)
                            BackgroundRead.Available(true)
                        } finally {
                            concurrentReads--
                        }
                    },
                    currentRuntime = { "current" },
                )
            val visible = launch { poller.poll(connection) }
            try {
                runCurrent()
                assertTrue(reads.isEmpty())
                rows = owners.map { if (it.runtimeId in stableOwners) it.copy(status = LiveStatus.IDLE) else it }
                advanceTimeBy(4_000L)
                runCurrent()
                assertEquals(listOf("current", "persistent"), reads.map { it.second })
                assertEquals(
                    4_000L,
                    poller.roster.value
                        ?.background
                        ?.get("persistent")
                        ?.confirmedAt,
                )
                assertEquals(
                    SessionActivity.BACKGROUND,
                    projectHistoryActivity(connected, poller.roster.value, emptyList(), currentTime)
                        .activity["persistent-saved"],
                )

                // Idle at 8/16/24/... seconds, busy at 12/20/28/...; five-second reads overlap those polls.
                for (tick in 2L..10L) {
                    rows =
                        owners.map {
                            it.copy(
                                status =
                                    if (it.runtimeId in stableOwners ||
                                        tick % 2L == 0L
                                    ) {
                                        LiveStatus.IDLE
                                    } else {
                                        LiveStatus.WORKING
                                    },
                            )
                        }
                    advanceTimeBy(4_000L)
                    runCurrent()
                }
                assertTrue(
                    "The continuously idle confirmed owner must be rechecked despite churn; attempts: $reads",
                    reads.any { (at, id) -> at >= 8_000L && id == "persistent" },
                )
                assertTrue(
                    requireNotNull(
                        poller.roster.value
                            ?.background
                            ?.get("persistent")
                            ?.confirmedAt,
                    ) > 4_000L,
                )
                assertTrue(peakConcurrentReads <= BACKGROUND_READ_CONCURRENCY)
                assertTrue(reads.groupBy { it.first }.values.all { it.size <= MAX_BACKGROUND_TARGETS })
            } finally {
                visible.cancel()
            }
        }

    @Test
    fun initialFailedBackgroundReadForCurrentIdleOwnerIsExplainedUnknownNotQuiet() =
        runTest {
            val connection = MutableStateFlow(connected)
            listOf(
                ReadFailure.TIMEOUT_OR_UNAVAILABLE,
                ReadFailure.AUTH_REQUIRED,
                ReadFailure.MALFORMED,
                ReadFailure.OFFLINE,
                ReadFailure.SCOPE_CHANGED,
                ReadFailure.UNSUPPORTED,
            ).forEach { failure ->
                val poller =
                    LiveRosterPoller(
                        { LiveRead.Available(it, listOf(idle)) },
                        { currentTime },
                        readBackground = { _, _ -> BackgroundRead.Unavailable(failure) },
                        currentRuntime = { "runtime" },
                    )
                val visible = launch { poller.poll(connection) }
                runCurrent()
                val result = projectHistoryActivity(connected, poller.roster.value, emptyList(), currentTime)
                assertEquals("No child-work proof for $failure", SessionActivity.UNCERTAIN, result.activity["saved"])
                assertTrue(result.unverified)
                assertTrue(result.rechecking.isEmpty())
                visible.cancel()
                runCurrent()
            }
        }

    @Test
    fun initialFailedBackgroundReadForRecentlyBusyOwnerRemainsUnknownUntilConclusiveNone() =
        runTest {
            val connection = MutableStateFlow(connected)
            var row = working
            var answer: BackgroundRead = BackgroundRead.Unavailable(ReadFailure.TIMEOUT_OR_UNAVAILABLE)
            val poller =
                LiveRosterPoller(
                    { LiveRead.Available(it, listOf(row)) },
                    { currentTime },
                    readBackground = { _, _ -> answer },
                )

            fun status() = projectHistoryActivity(connected, poller.roster.value, emptyList(), currentTime)
            val visible = launch { poller.poll(connection) }
            runCurrent()
            row = idle
            advanceTimeBy(4_001)
            assertEquals(SessionActivity.UNCERTAIN, status().activity["saved"])
            assertTrue(status().unverified)
            answer = BackgroundRead.Available(false)
            advanceTimeBy(4_000)
            assertTrue(status().activity.isEmpty())
            visible.cancel()
        }
}
