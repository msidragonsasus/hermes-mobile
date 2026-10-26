package com.m57.hermescontrol.career

import android.webkit.ValueCallback
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CareerBrowserReadinessTest {
    private val policy = checkNotNull(CareerBrowserPolicy.create("https://portal.example.test/company/login"))

    @Test
    fun waitsForDelayedFormWithoutRetrievingOrEmbeddingCredentials() =
        runTest {
            val browser = mockk<CareerBrowserWebView>()
            var calls = 0
            every { browser.evaluateJavascript(any(), any()) } answers {
                secondArg<ValueCallback<String>>().onReceiveValue(if (++calls < 3) "\"pending\"" else "\"ready\"")
            }
            assertEquals("\"ready\"", awaitCareerBrowserForm(browser, policy, "dummy-document") { true })
            assertEquals(3, calls)
        }

    @Test
    fun navigationCancelsPollingBeforeAnotherJavascriptCall() =
        runTest {
            val browser = mockk<CareerBrowserWebView>()
            var current = true
            every { browser.evaluateJavascript(any(), any()) } answers {
                current = false
                secondArg<ValueCallback<String>>().onReceiveValue("\"pending\"")
            }
            assertEquals("\"blocked\"", awaitCareerBrowserForm(browser, policy, "dummy-document") { current })
            verify(exactly = 1) { browser.evaluateJavascript(any(), any()) }
        }

    @Test
    fun occupiedFormIsNeverRetried() =
        runTest {
            val browser = mockk<CareerBrowserWebView>()
            every { browser.evaluateJavascript(any(), any()) } answers {
                secondArg<ValueCallback<String>>().onReceiveValue("\"occupied\"")
            }
            assertEquals("\"occupied\"", awaitCareerBrowserForm(browser, policy, "dummy-document") { true })
            verify(exactly = 1) { browser.evaluateJavascript(any(), any()) }
        }

    @Test
    fun lostCallbackTimesOutAndLateCallbackCannotResumeAgain() =
        runTest {
            val browser = mockk<CareerBrowserWebView>()
            lateinit var callback: ValueCallback<String>
            every { browser.evaluateJavascript(any(), any()) } answers {
                callback = secondArg()
            }
            assertEquals("\"unsupported\"", awaitCareerBrowserForm(browser, policy, "dummy-document") { true })
            callback.onReceiveValue("\"ready\"")
            verify(exactly = 1) { browser.evaluateJavascript(any(), any()) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun callbackFromBeforeAppSwitchIsBlockedEvenAfterQuickResume() =
        runTest {
            val browser = mockk<CareerBrowserWebView>()
            val gate = CareerBrowserSessionGate(initiallyForeground = true)
            val epoch = gate.epoch
            lateinit var callback: ValueCallback<String>
            every { browser.evaluateJavascript(any(), any()) } answers { callback = secondArg() }
            val result = async { awaitCareerBrowserForm(browser, policy, "dummy-document") { gate.isCurrent(epoch) } }
            runCurrent()
            gate.pause()
            gate.resume()
            callback.onReceiveValue("\"ready\"")
            assertEquals("\"blocked\"", result.await())
            callback.onReceiveValue("\"ready\"")
            verify(exactly = 1) { browser.evaluateJavascript(any(), any()) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledBackgroundPollingIgnoresLateCallbackAndDoesNotEvaluateAgain() =
        runTest {
            val browser = mockk<CareerBrowserWebView>()
            lateinit var callback: ValueCallback<String>
            every { browser.evaluateJavascript(any(), any()) } answers { callback = secondArg() }
            val result = async { awaitCareerBrowserForm(browser, policy, "dummy-document") { true } }
            runCurrent()
            result.cancel()
            callback.onReceiveValue("\"ready\"")
            runCurrent()
            assertEquals(true, result.isCancelled)
            verify(exactly = 1) { browser.evaluateJavascript(any(), any()) }
        }

    @Test
    fun perpetuallyMissingFormHasBoundedPolling() =
        runTest {
            val browser = mockk<CareerBrowserWebView>()
            every { browser.evaluateJavascript(any(), any()) } answers {
                secondArg<ValueCallback<String>>().onReceiveValue("\"pending\"")
            }
            assertEquals("\"pending\"", awaitCareerBrowserForm(browser, policy, "dummy-document") { true })
            verify(exactly = 40) { browser.evaluateJavascript(any(), any()) }
        }
}
