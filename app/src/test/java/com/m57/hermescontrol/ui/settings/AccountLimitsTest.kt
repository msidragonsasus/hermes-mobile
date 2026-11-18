package com.m57.hermescontrol.ui.settings

import com.m57.hermescontrol.data.model.AccountLimitWindow
import com.m57.hermescontrol.data.model.AccountLimitsResponse
import com.m57.hermescontrol.ui.settings.limits.AccountLimitsViewModel
import com.m57.hermescontrol.ui.settings.limits.LimitsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.time.Instant

class AccountLimitsTest {
    private val now = Instant.parse("2026-11-17T01:00:00Z")
    private val good =
        AccountLimitsResponse(
            available = true,
            provider = "openai-codex",
            fetched_at = now.toString(),
            windows = listOf(AccountLimitWindow("Weekly", 25.0, "2026-11-18T01:00:00Z")),
        )

    @Test
    fun unknownAndInvalidPercentagesNeverBecomeFullAllowance() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 100.1).forEach {
            assertNull(AccountLimitWindow("Session", it).remainingPercent)
        }
        assertEquals(100.0, AccountLimitWindow("Session", 0.0).remainingPercent!!, 0.0)
        assertEquals(0.0, AccountLimitWindow("Session", 100.0).remainingPercent!!, 0.0)
        assertEquals(75.0, good.windows.single().remainingPercent!!, 0.0)
    }

    @Test
    fun countdownRoundsUpAndElapsedResetDoesNotCreateNewQuota() {
        assertEquals(1L, AccountLimitWindow("Session", reset_at = now.plusSeconds(1).toString()).resetMinutesAt(now))
        assertEquals(
            61L,
            AccountLimitWindow("Session", reset_at = now.plusSeconds(3601).toString()).resetMinutesAt(now),
        )
        assertEquals(0L, AccountLimitWindow("Session", 100.0, now.toString()).resetMinutesAt(now))
        assertNull(AccountLimitWindow("Session", reset_at = "bad time").resetMinutesAt(now))
    }

    @Test
    fun missingStaleAndFutureSamplesAreNotCurrent() {
        assertTrue(good.isFreshAt(now.plusSeconds(300)))
        assertFalse(good.isFreshAt(now.plusSeconds(301)))
        assertFalse(good.isFreshAt(now.minusSeconds(31)))
        assertFalse(good.copy(fetched_at = null).isFreshAt(now))
    }

    @Test
    fun failedRefreshClearsPreviousAccountAndUnsupportedIsDistinct() =
        runTest {
            val model = AccountLimitsViewModel()
            model.refresh { Response.success(good) }
            assertEquals(LimitsStatus.READY, model.state.value.status)
            model.refresh { Response.error(503, "private-provider-error".toResponseBody()) }
            assertEquals(LimitsStatus.ERROR, model.state.value.status)
            assertNull(model.state.value.snapshot)
            model.refresh { Response.error(404, "".toResponseBody()) }
            assertEquals(LimitsStatus.UNAVAILABLE, model.state.value.status)
            model.refresh { Response.success(good.copy(available = false)) }
            assertNull(model.state.value.snapshot)
        }

    @Test
    fun lateRefreshCannotReplaceNewerResultAndCancellationPropagates() =
        runTest {
            val model = AccountLimitsViewModel()
            val old = CompletableDeferred<Response<AccountLimitsResponse>>()
            val started = CompletableDeferred<Unit>()
            val job =
                launch {
                    model.refresh {
                        started.complete(Unit)
                        old.await()
                    }
                }
            started.await()
            val latest = good.copy(plan = "latest")
            model.refresh { Response.success(latest) }
            old.complete(Response.success(good))
            job.join()
            assertEquals(
                "latest",
                model.state.value.snapshot
                    ?.plan,
            )
            try {
                model.refresh { throw CancellationException("stopped") }
                throw AssertionError("Cancellation swallowed")
            } catch (_: CancellationException) {
                assertNull(model.state.value.snapshot)
            }
        }
}
