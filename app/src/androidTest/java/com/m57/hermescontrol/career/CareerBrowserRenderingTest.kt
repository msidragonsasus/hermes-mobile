package com.m57.hermescontrol.career

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic HTTPS responses exercise real Chromium/CSP; no portal or credential traffic. */
class CareerBrowserRenderingTest {
    @get:Rule val activityRule = ActivityScenarioRule(ComponentActivity::class.java)
    private lateinit var activity: ComponentActivity

    @Before
    fun attachActivity() {
        activityRule.scenario.onActivity { activity = it }
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }

    private fun waitUntil(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Synthetic WebView condition did not complete", condition())
    }

    @Test
    fun axolStylesRenderAndReloadKeepsSessionAndCurrentAddress() {
        val address = "https://axol.jp/zw/s/fixture_company/mypage/home"
        val policy = checkNotNull(CareerBrowserPolicy.create(address))
        val requests = AtomicInteger()
        val cookie = AtomicReference<String?>(null)
        val finished = AtomicInteger()
        val network =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    cookie.set(chain.request().header("Cookie"))
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Set-Cookie", "fixture=session; Path=/; Secure; SameSite=Lax")
                        .body(
                            """
                            <html><head>
                            <link rel="stylesheet" href="https://static.axol.jp/zw/st/public/css/common.css">
                            </head><body><div id="fixture">Styled page</div></body></html>
                            """.trimIndent().toResponseBody("text/html; charset=utf-8".toMediaType()),
                        ).build()
                }.build()
        lateinit var browser: CareerBrowserWebView
        onMain {
            browser =
                CareerBrowserWebView(
                    activity,
                    policy,
                    {},
                    { _, _ -> finished.incrementAndGet() },
                    {},
                    {},
                    {},
                    network,
                )
            val production = browser.webViewClient
            browser.webViewClient =
                object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView,
                        url: String?,
                        favicon: Bitmap?,
                    ) = production.onPageStarted(view, url, favicon)

                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) = production.onPageFinished(view, url)

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val guarded = production.shouldInterceptRequest(view, request)
                        if (guarded != null) return guarded
                        // Only replace a resource AFTER the production policy has allowed it.
                        return WebResourceResponse(
                            "text/css",
                            "UTF-8",
                            ByteArrayInputStream("#fixture { font-size: 31px; }".toByteArray()),
                        )
                    }
                }
            activity.setContentView(browser)
            browser.start()
        }
        try {
            waitUntil(15_000) { finished.get() == 1 }
            assertEquals("\"31px\"", evaluate(browser, "getComputedStyle(document.getElementById('fixture')).fontSize"))
            var token: String? = null
            onMain {
                token = browser.documentToken
                assertTrue(CookieManager.getInstance().getCookie(address).contains("fixture=session"))
                browser.reloadCurrentPage()
                assertEquals(null, browser.documentToken)
                assertFalse(browser.isDisposed)
            }
            waitUntil(15_000) { finished.get() == 2 }
            assertEquals(2, requests.get())
            assertTrue(checkNotNull(cookie.get()).contains("fixture=session"))
            onMain {
                assertEquals(address, browser.url)
                assertNotEquals(token, browser.documentToken)
            }
            assertEquals("\"31px\"", evaluate(browser, "getComputedStyle(document.getElementById('fixture')).fontSize"))
        } finally {
            onMain { browser.dispose() }
        }
    }

    @Test
    fun formResubmissionIsCancelled() {
        val dontResend = AtomicInteger()
        val resend = AtomicInteger()
        val notices = AtomicInteger()
        onMain {
            val policy = checkNotNull(CareerBrowserPolicy.create("https://portal.example.test/company/login"))
            val browser =
                CareerBrowserWebView(activity, policy, {}, { _, _ -> }, {}, {}, {}, onResubmissionRequired = {
                    notices.incrementAndGet()
                })
            try {
                val handler =
                    Handler(Looper.getMainLooper()) { message ->
                        if (message.what == 1) dontResend.incrementAndGet() else resend.incrementAndGet()
                        true
                    }
                browser.webViewClient.onFormResubmission(
                    browser,
                    Message.obtain(handler, 1),
                    Message.obtain(handler, 2),
                )
            } finally {
                browser.dispose()
            }
        }
        waitUntil(5_000) { dontResend.get() == 1 }
        assertEquals(0, resend.get())
        assertEquals(1, notices.get())
    }

    private fun evaluate(
        browser: CareerBrowserWebView,
        script: String,
    ): String {
        val result = AtomicReference<String?>(null)
        onMain { browser.evaluateJavascript(script) { result.set(it) } }
        waitUntil(5_000) { result.get() != null }
        return checkNotNull(result.get())
    }
}
