package com.m57.hermescontrol.career

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerBrowserSessionGateTest {
    @Test
    fun backgroundStartCannotAuthorizeUntilResumed() {
        val gate = CareerBrowserSessionGate(initiallyForeground = false)
        assertFalse(gate.isCurrent(gate.epoch))
        gate.resume()
        assertTrue(gate.isCurrent(gate.epoch))
    }

    @Test
    fun pauseAndQuickResumeNeverRevivePriorFillOrApprovalCallbacks() {
        val gate = CareerBrowserSessionGate(initiallyForeground = true)
        repeat(3) {
            val captured = gate.epoch
            val lateCallback = { gate.isCurrent(captured) }
            assertTrue(lateCallback())
            gate.pause()
            assertFalse(lateCallback())
            assertFalse(gate.isCurrent(gate.epoch))
            gate.resume()
            assertFalse(lateCallback())
            assertTrue(gate.isCurrent(gate.epoch))
        }
    }

    @Test
    fun navigationOrExplicitCancellationInvalidatesWithoutClosingForegroundPage() {
        val gate = CareerBrowserSessionGate(initiallyForeground = true)
        val captured = gate.epoch
        gate.invalidate()
        assertTrue(gate.isForeground)
        assertFalse(gate.isCurrent(captured))
        assertTrue(gate.isCurrent(gate.epoch))
    }

    @Test
    fun pauseAndStopCancelWorkWithoutDisposingBrowserAndResumeRevalidatesFirst() {
        val browser = mockk<CareerBrowserWebView>(relaxed = true)
        val owner = mockk<LifecycleOwner>()
        val gate = CareerBrowserSessionGate(initiallyForeground = true)
        var cancelled = false
        var revalidated = false
        every { browser.isDisposed } returns false
        every { browser.onPause() } answers { assertTrue(cancelled) }
        every { browser.onResume() } answers { assertTrue(revalidated) }
        val observer =
            careerBrowserLifecycleObserver(
                pauseSession = {
                    gate.pause()
                    cancelled = true
                },
                resumeSession = {
                    revalidated = true
                    gate.resume()
                    true
                },
                browser = { browser },
                disposeSession = { browser.dispose() },
            )
        observer.onStateChanged(owner, Lifecycle.Event.ON_PAUSE)
        observer.onStateChanged(owner, Lifecycle.Event.ON_STOP)
        assertFalse(gate.isForeground)
        verify(exactly = 2) { browser.onPause() }
        verify(exactly = 0) { browser.dispose() }
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        verify(exactly = 1) { browser.onResume() }
        observer.onStateChanged(owner, Lifecycle.Event.ON_DESTROY)
        verify(exactly = 1) { browser.dispose() }
    }

    @Test
    fun initialResumeBeforeBrowserAttachmentAllowsFreshWorkWithoutReloading() {
        val owner = mockk<LifecycleOwner>()
        val gate = CareerBrowserSessionGate(initiallyForeground = false)
        var attached: CareerBrowserWebView? = null
        val observer =
            careerBrowserLifecycleObserver(
                pauseSession = { gate.pause() },
                resumeSession = {
                    gate.resume()
                    true
                },
                browser = { attached },
                disposeSession = { attached?.dispose() },
            )
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        assertTrue(gate.isCurrent(gate.epoch))
        val browser = mockk<CareerBrowserWebView>(relaxed = true)
        every { browser.isDisposed } returns false
        attached = browser
        observer.onStateChanged(owner, Lifecycle.Event.ON_PAUSE)
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        verify(exactly = 1) { browser.onPause() }
        verify(exactly = 1) { browser.onResume() }
        verify(exactly = 0) { browser.start() }
        verify(exactly = 0) { browser.dispose() }
    }

    @Test
    fun initialStartedDocumentFinishingBeforeOrAfterResumeGetsExactlyOneAttempt() {
        for (finishedBeforeResume in listOf(true, false)) {
            val gate = CareerBrowserSessionGate(initiallyForeground = false)
            val owner = mockk<LifecycleOwner>()
            val browser = mockk<CareerBrowserWebView>(relaxed = true)
            var ready = false
            var attempted = -1
            val generation = 1
            var requests = 0
            var webViewResumed = false
            every { browser.isDisposed } returns false
            every { browser.onResume() } answers { webViewResumed = true }

            fun requestAutomaticFill() {
                if (gate.shouldAutomaticallyFill(ready, attempted, generation)) {
                    assertTrue(webViewResumed)
                    attempted = generation
                    requests++
                }
            }
            val observer =
                careerBrowserLifecycleObserver(
                    pauseSession = { gate.pause() },
                    resumeSession = {
                        gate.resume()
                        true
                    },
                    browser = { browser },
                    disposeSession = {},
                    onResumed = { requestAutomaticFill() },
                )
            if (finishedBeforeResume) {
                ready = true
                requestAutomaticFill()
                assertEquals(-1, attempted)
            }
            observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
            if (!finishedBeforeResume) {
                assertEquals(0, requests)
                ready = true
                requestAutomaticFill()
            }
            assertEquals(1, requests)
            // A duplicate finished callback or repeated RESUME cannot repeat the same initial attempt.
            requestAutomaticFill()
            observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
            assertEquals(1, requests)
        }
    }

    @Test
    fun realAppSwitchDoesNotReviveAutomaticStartupAttemptOrOldEpoch() {
        val gate = CareerBrowserSessionGate(initiallyForeground = false)
        val priorEpoch = gate.epoch
        gate.pause()
        gate.resume()
        assertFalse(gate.shouldAutomaticallyFill(true, -1, 1))
        assertFalse(gate.isCurrent(priorEpoch))
        // Only a new explicit key/reopen action can allow a fresh attempt.
        gate.allowAutomaticFill()
        assertTrue(gate.shouldAutomaticallyFill(true, -1, 1))
        assertFalse(gate.isCurrent(priorEpoch))
    }

    @Test
    fun changedScopeOrClosedSessionCannotResumeRetainedBrowser() {
        val browser = mockk<CareerBrowserWebView>(relaxed = true)
        val owner = mockk<LifecycleOwner>()
        var requested = false
        val observer =
            careerBrowserLifecycleObserver(
                pauseSession = {},
                resumeSession = { false },
                browser = { browser },
                disposeSession = {},
                onResumed = { requested = true },
            )
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        verify(exactly = 0) { browser.onResume() }
        assertFalse(requested)
    }
}
