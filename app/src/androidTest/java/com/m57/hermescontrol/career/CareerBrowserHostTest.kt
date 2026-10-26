package com.m57.hermescontrol.career

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real WebView/AndroidView ownership tests; no portal requests or credentials are used. */
class CareerBrowserHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val policy = checkNotNull(CareerBrowserPolicy.create("https://portal.example.test/company/login"))

    @Test
    fun appSwitchKeepsSameBrowserFormAndHistoryButActualRemovalDisposes() {
        val shown = mutableStateOf(true)
        val loaded = AtomicBoolean(false)
        val gate = CareerBrowserSessionGate(initiallyForeground = true)
        lateinit var host: CareerBrowserHost
        lateinit var current: CareerBrowserWebView
        var attached: CareerBrowserHost? = null
        var cancelled = false
        compose.setContent {
            if (shown.value) {
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val observer =
                        careerBrowserLifecycleObserver(
                            pauseSession = {
                                gate.pause()
                                cancelled = true
                            },
                            resumeSession = {
                                gate.resume()
                                true
                            },
                            browser = { attached?.browser },
                            disposeSession = { attached?.disposeBrowser() },
                        )
                    owner.lifecycle.addObserver(observer)
                    onDispose { owner.lifecycle.removeObserver(observer) }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize().testTag("history_fixture"),
                    factory = { context ->
                        CareerBrowserHost(context).also { container ->
                            val browser =
                                CareerBrowserWebView(
                                    context,
                                    policy,
                                    onStarted = {},
                                    onFinished = { _, _ -> loaded.set(true) },
                                    onBlocked = {},
                                    onManualPage = {},
                                    networkClient =
                                        fixtureNetwork(
                                            """
                                            <html><meta name="viewport" content="width=device-width,initial-scale=1">
                                            <body style="margin:0">
                                            <form><input id="draft" style="position:absolute;top:0">
                                            <textarea id="notes" style="position:absolute;top:40px"></textarea></form>
                                            <button type="button" style="width:100%;height:100vh"
                                            onclick="history.pushState({}, '', '#step-two')">Next</button>
                                            </body></html>
                                            """.trimIndent(),
                                        ),
                                    onGone = {},
                                )
                            container.attachBrowser(browser)
                            current = browser
                            host = container
                            attached = container
                            // Exercise the production native GET path with an entirely local response.
                            browser.start()
                        }
                    },
                    onRelease = { it.disposeBrowser() },
                )
            }
        }
        compose.waitUntil(10_000) { loaded.get() }
        val painted = AtomicBoolean(false)
        compose.runOnIdle {
            current.postVisualStateCallback(
                1,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        painted.set(true)
                    }
                },
            )
        }
        compose.waitUntil(10_000) { painted.get() }
        compose.runOnIdle {
            assertEquals(1, current.copyBackForwardList().size)
            assertEquals(0, current.copyBackForwardList().currentIndex)
        }
        assertEquals(
            "[true,true]",
            evaluate(current, "[!!document.getElementById('draft'), !!document.getElementById('notes')]"),
        )
        evaluate(
            current,
            "document.getElementById('draft').value = 'manual-entry'; document.getElementById('notes').value = 'unsent notes';",
        )
        // Chromium skips script-created history without user activation. Exercise real navigation input.
        compose.onNodeWithTag("history_fixture").performTouchInput { click(center) }
        assertEquals(
            "[true,true,true]",
            evaluate(
                current,
                "[history.length >= 2, location.hash === '#step-two', navigator.userActivation.hasBeenActive]",
            ),
        )
        // The JavaScript callback precedes Chromium committing same-document history.
        try {
            compose.waitUntil(10_000) { compose.runOnIdle { current.canGoBack() } }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            var nativeState = ""
            compose.runOnIdle {
                val history = current.copyBackForwardList()
                nativeState =
                    "entries=" + history.size + ", index=" + history.currentIndex + ", canGoBack=" + current.canGoBack()
            }
            throw AssertionError(
                "Synthetic pushState did not produce native back history: " + nativeState +
                    ", js=" +
                    evaluate(
                        current,
                        "[history.length, location.hash === '#step-two', document.readyState === 'complete']",
                    ),
                failure,
            )
        }
        val original = current
        var historySize = 0
        var originalUrl: String? = null
        compose.runOnIdle {
            assertTrue(current.canGoBack())
            historySize = current.copyBackForwardList().size
            originalUrl = current.url
        }
        repeat(2) {
            // Real Activity pause/stop/resume, rather than manually invoking lifecycle event callbacks.
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(cancelled)
                assertFalse(gate.isForeground)
                assertSame(original, host.browser)
                assertFalse(original.isDisposed)
                assertEquals(1, host.childCount)
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(gate.isForeground)
                assertSame(original, current)
                assertFalse(current.isDisposed)
                assertEquals(historySize, current.copyBackForwardList().size)
                assertEquals(originalUrl, current.url)
                assertTrue(current.canGoBack())
            }
            assertEquals(
                "[\"manual-entry\",\"unsent notes\"]",
                evaluate(current, "[document.getElementById('draft').value, document.getElementById('notes').value]"),
            )
        }
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(original.isDisposed)
            assertEquals(0, host.childCount)
            assertFalse(compose.activity.isFinishing)
        }
    }

    private fun fixtureNetwork(html: String): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }.build()

    private fun evaluate(
        browser: CareerBrowserWebView,
        script: String,
    ): String {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle { browser.evaluateJavascript(script) { result.set(it) } }
        compose.waitUntil(10_000) { result.get() != null }
        return checkNotNull(result.get())
    }

    @Test
    fun simulatedRendererCallbackAndLatePageStartedLeaveHostAndActivityAlive() {
        var gone = 0
        var started = 0
        lateinit var host: CareerBrowserHost
        lateinit var browser: CareerBrowserWebView
        compose.setContent {
            AndroidView(
                factory = { context ->
                    CareerBrowserHost(context).also {
                        host = it
                        browser = CareerBrowserWebView(context, policy, { started++ }, { _, _ -> }, {}, {}, { gone++ })
                        it.attachBrowser(browser)
                    }
                },
                onRelease = { it.disposeBrowser() },
            )
        }
        compose.runOnIdle {
            val client = browser.webViewClient
            val detail =
                object : RenderProcessGoneDetail() {
                    override fun didCrash(): Boolean = true

                    override fun rendererPriorityAtExit(): Int = WebView.RENDERER_PRIORITY_IMPORTANT
                }
            assertTrue(client.onRenderProcessGone(browser, detail))
            assertTrue(client.onRenderProcessGone(browser, detail))
            client.onPageStarted(browser, policy.loginUrl.toString(), null)
            assertEquals(1, gone)
            assertEquals(0, started)
            assertTrue(browser.isDisposed)
            assertEquals(0, host.childCount)
            assertTrue(host.isAttachedToWindow)
            assertFalse(compose.activity.isFinishing)
        }
    }
}
