package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Android WebView and production native document identity; only synthetic responses/values. */
class CareerBrowserGenericFillTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = CareerBrowserSessionGate(initiallyForeground = true)
    private val loaded = AtomicBoolean(false)
    private val unexpected = AtomicInteger(0)
    private val currentUrl = AtomicReference<String?>(null)
    private var serial = 0
    private var shown = false

    private data class Fixture(
        val serial: Int,
        val url: String,
        val body: String,
    )

    private val fixture = mutableStateOf(Fixture(0, TARGET, ""))
    private lateinit var browser: CareerBrowserWebView
    private lateinit var policy: CareerBrowserPolicy

    @After fun cleanup() {
        scope.cancel()
        if (::browser.isInitialized) compose.runOnIdle { browser.dispose() }
        assertEquals("No real network/submission was delegated by the fixture", 0, unexpected.get())
    }

    private fun show(
        body: String,
        url: String = TARGET,
    ) {
        loaded.set(false)
        val next = Fixture(++serial, url, body)
        if (!shown) {
            fixture.value = next
            shown = true
            compose.setContent {
                val current = fixture.value
                key(current.serial) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().testTag("generic_browser_fixture"),
                        factory = { context ->
                            policy = checkNotNull(CareerBrowserPolicy.create(current.url))
                            CareerBrowserHost(context).also { host ->
                                val network =
                                    OkHttpClient
                                        .Builder()
                                        .addInterceptor { chain ->
                                            val request = chain.request()
                                            val accepted =
                                                request.method == "GET" && request.url.toString() == current.url
                                            if (!accepted) unexpected.incrementAndGet()
                                            Response
                                                .Builder()
                                                .request(request)
                                                .protocol(Protocol.HTTP_1_1)
                                                .code(if (accepted) 200 else 403)
                                                .message("Synthetic fixture")
                                                .body(
                                                    (if (accepted) page(current.body) else "Blocked synthetic request")
                                                        .toResponseBody("text/html; charset=utf-8".toMediaType()),
                                                ).build()
                                        }.build()
                                val view =
                                    CareerBrowserWebView(
                                        context,
                                        policy,
                                        {},
                                        { finished, _ ->
                                            if (fixture.value.serial == current.serial && !finished.isDisposed &&
                                                finished.documentToken != null
                                            ) {
                                                loaded.set(true)
                                            }
                                        },
                                        {},
                                        {},
                                        {},
                                        networkClient = network,
                                        onStateChanged = { view ->
                                            currentUrl.set(view.url)
                                            gate.invalidate()
                                        },
                                        onDocumentChanged = { view ->
                                            currentUrl.set(view.url)
                                            gate.invalidate()
                                        },
                                    )
                                browser = view
                                host.attachBrowser(view)
                                view.start()
                            }
                        },
                        onRelease = { it.disposeBrowser() },
                    )
                }
            }
        } else {
            compose.runOnIdle { fixture.value = next }
        }
        compose.waitUntil(20_000) { loaded.get() }
        val painted = AtomicBoolean(false)
        compose.runOnIdle {
            browser.postVisualStateCallback(
                1,
                object : android.webkit.WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        painted.set(true)
                    }
                },
            )
        }
        compose.waitUntil(10_000) { painted.get() }
        compose.runOnIdle {
            currentUrl.set(browser.url)
            assertTrue(policy.allowsFill(browser.url))
        }
        assertTrue(checkNotNull(browser.documentToken).isNotBlank())
    }

    private fun evaluate(script: String): String {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle { browser.evaluateJavascript(script) { result.set(it) } }
        compose.waitUntil(10_000) { result.get() != null }
        return checkNotNull(result.get())
    }

    private fun probe(
        continuation: Boolean = false,
        token: String = checkNotNull(browser.documentToken),
    ) = evaluate(CareerBrowserFill.readinessScript(policy, token, continuation))

    private fun fill(
        step: CareerBrowserFillStep = CareerBrowserFillStep.COMBINED,
        token: String = checkNotNull(browser.documentToken),
    ): String =
        CareerBrowserCredentials(USER.toCharArray(), PASSWORD.toCharArray()).use {
            evaluate(CareerBrowserFill.script(policy, it, token, step))
        }

    private fun assertEmptyPassword() {
        assertEquals(
            "true",
            evaluate("[...document.querySelectorAll('input[type=password]')].every(e => e.value === '')"),
        )
    }

    private fun assertNoEvents() {
        assertEquals("[]", evaluate("window.fixtureEvents"))
        assertEquals(
            "GET",
            evaluate(
                "performance.getEntriesByType('navigation')[0].serverTiming.length ? 'GET' : 'NO_NATIVE_TOKEN'",
            ).trim('"'),
        )
    }

    @Test fun definitionListIdentifierAndSameTenantAuthenticationEndpointFillWithoutEvents() {
        show(
            form(
                "<dl><dt>ID</dt><dd><input name='gksid' id='gksid'></dd></dl>" +
                    "<dl><dt>PASSWORD</dt><dd><input name='gkspw' id='!gkspw' type='password'></dd></dl>",
                "/jr-central2028/applicant/login/check",
            ),
            "https://mypage.3070.i-webs.jp/jr-central2028/",
        )
        assertEquals("\"ready\"", probe())
        assertEquals("\"filled\"", fill())
        assertEquals(
            "[true,true]",
            evaluate(
                "[document.getElementById('gksid').value === '$USER', " +
                    "document.getElementById('!gkspw').value === '$PASSWORD']",
            ),
        )
        assertNoEvents()
        assertEquals("\"occupied\"", fill())
    }

    @Test fun legacyEntryAndUnrelatedProviderUseTheirOwnRegisteredForm() {
        show(
            form(
                "<dl><dt>ID</dt><dd><input name='gksid' id='gksid'></dd></dl><input name='gkspw' id='!gkspw' type='password'>",
                "/pwc_newgrads28/applicant/login/check",
                "id='formLogin' name='formLogin'",
            ),
            "https://mypage.3030.i-webs.jp/pwc_newgrads28/applicant/login/baitai-entry/entrycd/C00",
        )
        assertEquals("\"filled\"", fill())
        assertNoEvents()
        show(
            form(
                "<label for='opaque'>メールアドレス</label><input id='opaque' name='x1'><input type='password' autocomplete='current-password'>",
                "/platform/company/mypage/auth",
            ),
            "https://provider.example.test/platform/company/mypage/login",
        )
        assertEquals("\"filled\"", fill())
        assertNoEvents()
    }

    @Test fun unsafeAmbiguousHiddenOtpAndOccupiedFormsNeverReceivePassword() {
        val cases =
            listOf(
                form(action = "https://foreign.example.test/tenant/login") to "unsafe_action",
                form(action = "/other/login") to "unsafe_action",
                form(action = "/tenant/auth?tenant=other") to "unsafe_action",
                form(extra = "target='other'") to "unsafe_action",
                form("<input name='username'><input type='hidden' name='username'><input type='password'>") to
                    "ambiguous",
                form("<input name='username'><input name='email'><input type='password'>") to "ambiguous",
                form("<input name='username' style='position:absolute;left:9999px'><input type='password'>") to
                    "pending",
                form("<input name='otp' autocomplete='one-time-code'><input type='password'>") to "ambiguous",
                form("<input name='username' value='manual-account'><input type='password'>") to "occupied",
                form("<input name='username'><input type='password' autocomplete='new-password'>") to "unsupported",
            )
        for ((html, expected) in cases) {
            show(html)
            assertEquals("\"$expected\"", fill())
            assertEmptyPassword()
            assertNoEvents()
        }
    }

    @Test fun identifierPasswordAndDisabledIdentifierContinuationAreBounded() {
        show(form("<input name='username'>"))
        assertEquals("\"ready_id\"", probe())
        CareerBrowserCredentials(USER.toCharArray(), PASSWORD.toCharArray()).use {
            val script =
                CareerBrowserFill.script(
                    policy,
                    it,
                    checkNotNull(browser.documentToken),
                    CareerBrowserFillStep.IDENTIFIER,
                )
            assertFalse(script.contains(PASSWORD))
            assertEquals("\"filled_id\"", evaluate(script))
        }
        evaluate(
            "document.querySelector('form').innerHTML = '<input type=\"password\" autocomplete=\"current-password\"><button>Login</button>'",
        )
        assertEquals("\"pending\"", probe())
        assertEquals("\"ready_password\"", probe(true))
        CareerBrowserCredentials(USER.toCharArray(), PASSWORD.toCharArray()).use {
            val script =
                CareerBrowserFill.script(
                    policy,
                    it,
                    checkNotNull(browser.documentToken),
                    CareerBrowserFillStep.PASSWORD,
                )
            assertFalse(script.contains(USER))
            assertEquals("\"filled_password\"", evaluate(script))
        }
        assertNoEvents()
        for (disabled in listOf("readonly", "disabled")) {
            show(form("<input name='username' value='$USER' $disabled><input type='password'>"))
            assertEquals("\"ready_password_with_id\"", probe(true))
            assertEquals("\"filled_password\"", fill(CareerBrowserFillStep.PASSWORD_WITH_IDENTIFIER))
            show(form("<input name='username' value='different-account' $disabled><input type='password'>"))
            assertEquals("\"occupied\"", fill(CareerBrowserFillStep.PASSWORD_WITH_IDENTIFIER))
            assertEmptyPassword()
        }
    }

    @Test fun actionChangesAndHistoryReloadInvalidateOldDocumentAuthority() {
        show(
            form() + """<button type="button" style="position:fixed;bottom:0;left:0;width:100%;height:45vh"
            onclick="history.pushState({}, '', '/tenant/other')">Synthetic history step</button>""",
        )
        val oldToken = checkNotNull(browser.documentToken)
        assertEquals("\"ready\"", probe())
        evaluate("document.querySelector('form').action = 'https://foreign.example.test/login'")
        assertEquals("\"unsafe_action\"", fill())
        assertEmptyPassword()
        evaluate("document.querySelector('form').action = '/tenant/auth'")
        val epoch = gate.epoch
        compose.onNodeWithTag("generic_browser_fixture").performTouchInput {
            click(Offset(center.x, center.y * 1.5f))
        }
        compose.waitUntil(10_000) { compose.runOnIdle { !policy.allowsFill(browser.url) } }
        assertEquals("\"blocked\"", fill(token = oldToken))
        compose.waitUntil(5_000) { !gate.isCurrent(epoch) }
        assertFalse(gate.isCurrent(epoch))
        compose.waitUntil(10_000) { compose.runOnIdle { browser.canGoBack() } }
        compose.runOnIdle { browser.goBack() }
        compose.waitUntil(10_000) { compose.runOnIdle { policy.allowsFill(browser.url) } }
        assertEmptyPassword()
        compose.runOnIdle { browser.goForward() }
        compose.waitUntil(10_000) { compose.runOnIdle { !policy.allowsFill(browser.url) } }
        assertEquals("\"blocked\"", probe(token = oldToken))
        compose.runOnIdle { browser.goBack() }
        compose.waitUntil(10_000) { compose.runOnIdle { policy.allowsFill(browser.url) } }
        loaded.set(false)
        compose.runOnIdle { browser.reloadCurrentPage() }
        compose.waitUntil(20_000) { loaded.get() }
        assertNotEquals(oldToken, browser.documentToken)
        assertEquals("\"blocked\"", probe(token = oldToken))
        assertEquals("\"ready\"", probe())
        assertEmptyPassword()
    }

    @Test fun productionReadinessPollingWaitsForDynamicFormWithoutCredentials() {
        val delayed = JsonPrimitive(form()).toString()
        show(
            "<div id='mount'></div><script>setTimeout(() => document.getElementById('mount').innerHTML = $delayed, 600);</script>",
        )
        val result = AtomicReference<String?>(null)
        val token = checkNotNull(browser.documentToken)
        val epoch = gate.epoch
        compose.runOnIdle {
            scope.launch {
                result.set(
                    awaitCareerBrowserForm(browser, policy, token) {
                        gate.isCurrent(epoch) &&
                            browser.documentToken == token
                    },
                )
            }
        }
        compose.waitUntil(10_000) { result.get() != null }
        assertEquals("\"ready\"", result.get())
        assertEmptyPassword()
        assertEquals("\"ready\"", probe())
        assertNoEvents()
    }

    @Test fun pauseCancelsPendingReadinessAndLateResultsCannotResumeAutomaticFill() {
        show("<div id='mount'></div>")
        val result = AtomicReference<String?>(null)
        val token = checkNotNull(browser.documentToken)
        val epoch = gate.epoch
        compose.runOnIdle {
            scope.launch {
                result.set(
                    awaitCareerBrowserForm(browser, policy, token) {
                        gate.isCurrent(epoch) &&
                            browser.documentToken == token
                    },
                )
            }
            gate.pause()
            browser.onPause()
        }
        compose.waitUntil(10_000) { result.get() != null }
        assertEquals("\"blocked\"", result.get())
        compose.runOnIdle {
            gate.resume()
            browser.onResume()
        }
        evaluate("document.getElementById('mount').innerHTML = ${JsonPrimitive(form())}")
        assertFalse(gate.isCurrent(epoch))
        assertFalse(gate.shouldAutomaticallyFill(true, 0, 1))
        assertEmptyPassword()
        assertEquals("\"ready\"", probe())
        assertNoEvents()
    }

    companion object {
        private const val TARGET = "https://portal.example.test/tenant/login"
        private const val USER = "synthetic-account"
        private const val PASSWORD = "synthetic-password"

        private fun form(
            inner: String = "<input name='username'><input type='password'>",
            action: String = "/tenant/auth",
            extra: String = "",
        ) = "<form method='post' action='$action' $extra>$inner<button type='submit'>Login</button></form>"

        private fun page(body: String) =
            "<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>input,button{width:180px;height:32px}body{margin:8px}</style>$body" +
                "<script>window.fixtureEvents=[];for(const type of ['input','change','submit'])document.addEventListener(type,e=>{window.fixtureEvents.push(type);e.preventDefault()});</script>"
    }
}
