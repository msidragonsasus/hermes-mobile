package com.m57.hermescontrol.career

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.viewinterop.AndroidView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Synthetic pages only. No real portal, credentials or user files. */
class CareerBrowserTabsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val address = "https://portal.example.test/company/login"
    private val policy = checkNotNull(CareerBrowserPolicy.create(address))

    @Test
    fun realUserPopupKeepsRootFormAndSharesCookieSession() {
        lateinit var host: CareerBrowserHost
        lateinit var root: CareerBrowserWebView
        val ready = AtomicBoolean(false)
        compose.setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("popup_fixture"),
                factory = { context ->
                    host = CareerBrowserHost(context)

                    fun make(popup: Boolean): CareerBrowserWebView =
                        CareerBrowserWebView(
                            context,
                            policy,
                            {},
                            { _, _ -> ready.set(true) },
                            {},
                            {},
                            {},
                            networkClient = fixtureNetwork(),
                            allowsCredentialFill = !popup,
                            onCreateTab = { opener, result -> host.openPopup(opener, result) { make(true) } },
                            onCloseTab = { host.closeTab(it) },
                        )
                    root = make(false)
                    host.attachBrowser(root)
                    root.start()
                    host
                },
                onRelease = { it.disposeBrowser() },
            )
        }
        compose.waitUntil(10_000) { ready.get() }
        assertEquals("true", evaluate(root, "!!document.querySelector('button') && !!document.getElementById('draft')"))
        compose.runOnIdle {
            CookieManager.getInstance().setCookie(address, "fixture=shared; Secure; Path=/")
            CookieManager.getInstance().flush()
        }
        val painted = AtomicBoolean(false)
        compose.runOnIdle {
            root.postVisualStateCallback(
                1,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        painted.set(true)
                    }
                },
            )
        }
        compose.waitUntil(10_000) { painted.get() }
        // AndroidView has no Compose click action: inject a real touch after WebView visual readiness.
        compose.onNodeWithTag("popup_fixture").performTouchInput { click(center) }
        compose.waitUntil(10_000) { host.tabs.size == 2 }
        compose.runOnIdle {
            val child = checkNotNull(host.activeBrowser)
            assertNotSame(root, child)
            assertFalse(child.allowsCredentialFill)
            assertSame(root, host.browser)
            assertTrue(
                CookieManager
                    .getInstance()
                    .getCookie(address)
                    .orEmpty()
                    .contains("fixture=shared"),
            )
            assertTrue(host.closeTab(child))
            assertTrue(child.isDisposed)
            assertSame(root, host.activeBrowser)
            assertFalse(root.isDisposed)
            assertTrue(
                CookieManager
                    .getInstance()
                    .getCookie(address)
                    .orEmpty()
                    .contains("fixture=shared"),
            )
        }
        assertEquals("\"unsent draft\"", evaluate(root, "document.getElementById('draft').value"))
        compose.runOnIdle {
            host.disposeBrowser()
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    @Test
    fun popupLimitBackgroundAndCloseAreBoundedAndIdempotent() {
        compose.runOnIdle {
            val host = CareerBrowserHost(compose.activity)

            fun make(popup: Boolean) =
                CareerBrowserWebView(
                    compose.activity,
                    policy,
                    {},
                    { _, _ -> },
                    {},
                    {},
                    {},
                    allowsCredentialFill = !popup,
                )
            val root = make(false)
            host.attachBrowser(root)

            fun message(): Message =
                Message.obtain(Handler(Looper.getMainLooper())).apply {
                    obj = root.WebViewTransport()
                }
            try {
                host.setForeground(false)
                assertFalse(host.openPopup(root, message()) { make(true) })
                host.setForeground(true)
                repeat(CareerBrowserHost.MAX_TABS - 1) {
                    assertTrue(host.openPopup(checkNotNull(host.activeBrowser), message()) { make(true) })
                }
                assertEquals(CareerBrowserHost.MAX_TABS, host.tabs.size)
                assertFalse(host.openPopup(checkNotNull(host.activeBrowser), message()) { make(true) })
                assertFalse(host.closeTab(root))
                val child = checkNotNull(host.activeBrowser)
                assertTrue(host.closeTab(child))
                assertFalse(host.closeTab(child))
                assertFalse(host.openPopup(child, message()) { make(true) })
                val pages = host.tabs
                host.disposeBrowser()
                assertTrue(pages.all { it.isDisposed })
                assertTrue(host.tabs.isEmpty())
                host.disposeBrowser()
            } finally {
                host.disposeBrowser()
            }
        }
    }

    @Test
    fun mobileRenderingDefaultsAndPopupGestureGuardRemainSafe() {
        compose.runOnIdle {
            val browser = CareerBrowserWebView(compose.activity, policy, {}, { _, _ -> }, {}, {}, {})
            try {
                assertTrue(browser.settings.javaScriptEnabled)
                assertTrue(browser.settings.domStorageEnabled)
                assertTrue(browser.settings.useWideViewPort)
                assertTrue(browser.settings.loadWithOverviewMode)
                assertTrue(browser.settings.builtInZoomControls)
                assertFalse(browser.settings.displayZoomControls)
                assertTrue(browser.settings.supportMultipleWindows())
                assertFalse(browser.settings.allowFileAccess)
                assertFalse(browser.settings.javaScriptCanOpenWindowsAutomatically)
                assertTrue(browser.settings.safeBrowsingEnabled)
                assertFalse(
                    checkNotNull(browser.webChromeClient).onCreateWindow(browser, false, false, Message.obtain()),
                )
                assertEquals(
                    "https://another.example.test",
                    careerBrowserDisplayOrigin("https://another.example.test/path?token=hidden"),
                )
                assertEquals(null, careerBrowserDisplayOrigin("about:blank"))
            } finally {
                browser.dispose()
            }
        }
    }

    private fun fixtureNetwork(): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        <html><meta name="viewport" content="width=device-width,initial-scale=1">
                        <body style="margin:0"><input id="draft" value="unsent draft" style="position:absolute;top:0">
                        <button style="width:100%;height:100vh" onclick="window.open('about:blank','_blank')">Open</button>
                        </body></html>
                        """.trimIndent().toResponseBody("text/html; charset=utf-8".toMediaType()),
                    ).build()
            }.build()

    private fun evaluate(
        browser: CareerBrowserWebView,
        script: String,
    ): String {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle { browser.evaluateJavascript(script) { result.set(it) } }
        compose.waitUntil(5_000) { result.get() != null }
        return checkNotNull(result.get())
    }
}
