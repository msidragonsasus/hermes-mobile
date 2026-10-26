package com.m57.hermescontrol.openclaw

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.StorageStartup
import com.m57.hermescontrol.data.local.StorageStartupState
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in physical-device validation. Never sends a paid chat prompt or changes Gateway configuration. */
class OpenClawLivePhoneTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before
    fun requireExplicitLiveOptIn() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("openclawLive") == "true")
            withTimeout(60_000) { StorageStartup.state.first { it == StorageStartupState.Ready } }
            assertFalse("The existing account must remain logged in", AuthManager.getToken().isNullOrBlank())
        }

    @Test
    fun savedOpenRouterKeyProducesAFreeTitleWithoutReentry() =
        runBlocking {
            val metadata = ApiClient.hermesApi.getEnvVars()
            assertTrue("Existing key metadata must be reachable", metadata.isSuccessful)
            val configured = metadata.body()?.get("OPENROUTER_API_KEY")?.isSet == true
            result("key_configured", configured.toString())
            assertTrue("Existing OpenRouter key must be configured", configured)
            val owner = OpenClawViewModel.ownerScope()
            val key = resolveClawTitleKey(null, { owner == OpenClawViewModel.ownerScope() })
            assertNotNull("Existing key should be usable without displaying or copying it", key)
            val title =
                try {
                    OpenClawTitles().generate(requireNotNull(key), "明日の旅行に持っていく物を整理したい")
                } catch (failure: ClawTitleUnavailable) {
                    result("free_title_failure", "${failure.reason}:${failure.status}:${failure.finishReason}")
                    throw failure
                }
            assertTrue("The free route should return a short title", title.isNotBlank() && title.length <= 40)
            result("free_title", title)
        }

    @Test
    fun appReconnectsWithoutButtonAndNotificationReturnsToItsThread() =
        runBlocking {
            val app = context.applicationContext as Application
            val vm = withContext(Dispatchers.Main) { OpenClawRuntime.model(app).also { it.ensureConnected() } }
            withTimeout(60_000) { vm.uiState.first { it.connection == ClawConnection.CONNECTED && !it.loading } }
            val key = vm.uiState.value.sessionKey
            val beforeMessages = vm.uiState.value.messages.size
            val field = OpenClawViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }
            val old = withContext(Dispatchers.Main) { field.get(vm) as OpenClawClient }
            withContext(Dispatchers.Main) { old.close() }
            withTimeout(60_000) {
                vm.uiState.first {
                    it.connection == ClawConnection.CONNECTED && !it.loading && field.get(vm) !== old
                }
            }
            assertEquals("Reconnection must retain the selected thread", key, vm.uiState.value.sessionKey)
            assertEquals("Reconnection must retain history", beforeMessages, vm.uiState.value.messages.size)
            result("automatic_reconnect", "passed")

            val manager = context.getSystemService(NotificationManager::class.java)
            val store = OpenClawStore(context, OpenClawViewModel.ownerScope())
            val notice = ClawNotice(key, "run:phone-check-${UUID.randomUUID()}", "接続テスト", "再接続と通知の動作確認")
            val tag = store.sessionName("notification", key)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (manager.activeNotifications.none { it.id == OpenClawNotifications.SERVICE_ID } &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    SystemClock.sleep(100)
                }
                assertTrue(
                    "Background remote messaging listener should be active",
                    manager.activeNotifications.any {
                        it.id ==
                            OpenClawNotifications.SERVICE_ID
                    },
                )
                withContext(Dispatchers.Main) {
                    OpenClawRuntime.visibleSession = null
                    OpenClawRuntime.setForeground(false)
                    OpenClawNotifications.show(context, store, notice, "動作確認")
                }
                val notificationDeadline = SystemClock.elapsedRealtime() + 10_000
                while (manager.activeNotifications.none { it.tag == tag } &&
                    SystemClock.elapsedRealtime() < notificationDeadline
                ) {
                    SystemClock.sleep(100)
                }
                val posted = manager.activeNotifications.firstOrNull { it.tag == tag }?.notification
                assertNotNull("A private notification must be delivered", posted)
                assertEquals(Notification.VISIBILITY_PRIVATE, posted!!.visibility)
                assertFalse(
                    posted.publicVersion.extras
                        .getCharSequence(Notification.EXTRA_TITLE)
                        .toString()
                        .contains("動作確認"),
                )
                assertNotNull(posted.contentIntent)
                // The host controller (or a human) expands the system shade and taps this actual notification.
                // Sending its PendingIntent from the background would not model Android's user-gesture privileges.
                result(
                    "notification_ready",
                    InstrumentationRegistry.getArguments().getString("openclawRunToken") ?: "manual",
                )
                withTimeout(60_000) {
                    combine(vm.uiState, OpenClawRuntime.foregroundState) { state, foreground ->
                        state.sessionKey == key && !state.loading && foreground
                    }.first { it }
                }
                val routeDeadline = SystemClock.elapsedRealtime() + 10_000
                while (SystemClock.elapsedRealtime() < routeDeadline) {
                    var routed = false
                    instrumentation.runOnMainSync {
                        routed = com.m57.hermescontrol.NavigationController.backStack
                            ?.lastOrNull() == OpenClawChatKey
                    }
                    if (routed) break
                    SystemClock.sleep(100)
                }
                instrumentation.runOnMainSync {
                    assertEquals(
                        OpenClawChatKey,
                        com.m57.hermescontrol.NavigationController.backStack
                            ?.lastOrNull(),
                    )
                }
                result("notification_route", "passed")
                result("history_count", beforeMessages.toString())
                manager.cancel(tag, 0)
            }
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName))
        }

    private fun result(
        name: String,
        value: String,
    ) {
        // Only public test outcomes, counts and the title generated from our synthetic prompt.
        File(context.getExternalFilesDir(null), "openclaw-phone-$name.txt").writeText(value, Charsets.UTF_8)
    }
}
