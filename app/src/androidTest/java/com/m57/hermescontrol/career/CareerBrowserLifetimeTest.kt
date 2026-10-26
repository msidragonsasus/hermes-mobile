package com.m57.hermescontrol.career

import android.net.Uri
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Modifier

/** Offline lifetime regressions for the process-wide native ServiceWorker root. */
class CareerBrowserLifetimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val policy = checkNotNull(CareerBrowserPolicy.create("https://portal.example.test/company/login"))

    private fun browser(context: android.content.Context) =
        CareerBrowserWebView(context, policy, {}, { _, _ -> }, {}, {}, {})

    private fun assertGuard() {
        val client = CareerBrowserServiceWorkerGuard.client
        // This is the exact object held by the native process root, not an unrelated fake client.
        assertTrue(client.javaClass.declaredFields.none { !Modifier.isStatic(it.modifiers) })
        val request =
            object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse("https://portal.example.test/worker.js")

                override fun isForMainFrame(): Boolean = false

                override fun isRedirect(): Boolean = false

                override fun hasGesture(): Boolean = false

                override fun getMethod(): String = "GET"

                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            }
        checkNotNull(client.shouldInterceptRequest(request)).also {
            assertEquals(403, it.statusCode)
            assertEquals("no-store", it.responseHeaders["Cache-Control"])
            assertEquals(-1, it.data.read())
            it.data.close()
        }
        ServiceWorkerController.getInstance().serviceWorkerWebSettings.also {
            assertFalse(it.allowFileAccess)
            assertFalse(it.allowContentAccess)
            assertTrue(it.blockNetworkLoads)
        }
    }

    @Test
    fun closingAndReopeningBrowserPreservesActivityAndStatelessGuard() {
        val shown = mutableStateOf(true)
        lateinit var current: CareerBrowserWebView
        compose.setContent {
            if (shown.value) {
                AndroidView(
                    factory = { context ->
                        CareerBrowserHost(context).also {
                            current = browser(context)
                            it.attachBrowser(current)
                        }
                    },
                    onRelease = { it.disposeBrowser() },
                )
            }
        }
        val client = CareerBrowserServiceWorkerGuard.client
        repeat(3) {
            lateinit var removed: CareerBrowserWebView
            compose.runOnIdle {
                removed = current
                shown.value = false
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(removed.isDisposed)
                assertFalse(compose.activity.isFinishing)
                assertFalse(compose.activity.isDestroyed)
                assertSame(client, CareerBrowserServiceWorkerGuard.client)
                assertGuard()
                shown.value = true
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertNotSame(removed, current)
                assertFalse(current.isDisposed)
                assertGuard()
            }
        }
    }

    @Test
    fun activityRecreationDisposesOldBrowserAndNewActivityReusesContextFreeGuard() {
        lateinit var old: CareerBrowserWebView
        compose.setContent {
            AndroidView(
                factory = { context ->
                    CareerBrowserHost(context).also {
                        old = browser(context)
                        it.attachBrowser(old)
                    }
                },
                onRelease = { it.disposeBrowser() },
            )
        }
        val oldActivity = compose.activity
        val client = CareerBrowserServiceWorkerGuard.client
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            assertNotSame(oldActivity, activity)
            assertTrue(oldActivity.isDestroyed)
            assertTrue(old.isDisposed)
            val replacement = browser(activity)
            try {
                assertSame(client, CareerBrowserServiceWorkerGuard.client)
                assertGuard()
                assertFalse(replacement.isDisposed)
                assertFalse(activity.isDestroyed)
            } finally {
                replacement.dispose()
            }
        }
    }
}
