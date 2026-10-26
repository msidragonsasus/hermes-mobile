package com.m57.hermescontrol.terminal

import android.util.Base64
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class TerminalUiTest {
    @get:Rule val compose = createComposeRule()
    private val actions =
        object : TerminalActions {
            val sent = mutableListOf<Pair<String, Boolean>>()

            override fun resume() {}

            override fun pause() {}

            override fun create(kind: TerminalKind) {}

            override fun select(id: String) {}

            override fun showHome() {}

            override fun send(
                text: String,
                appendEnter: Boolean,
            ) {
                sent.add(text to appendEnter)
            }

            override fun resize(
                newCols: Int,
                newRows: Int,
            ) {}

            override fun closeSession() {}

            override fun report(value: String) {}
        }

    @Test
    fun normalEditorSendsCommittedUnicodeAndExplicitControlKeys() {
        var keyboard: SoftwareKeyboardController? = null
        var keyboardBottom = 0
        compose.setContent {
            keyboard = LocalSoftwareKeyboardController.current
            keyboardBottom = WindowInsets.ime.getBottom(LocalDensity.current)
            HermesControlTheme {
                TerminalContent(TerminalUiState(selected = "fixture", connected = true, enabled = true), actions)
            }
        }
        compose.onNodeWithTag("terminal_input").performClick().performTextInput("日本語と paste")
        compose.runOnIdle { keyboard?.show() }
        compose.waitUntil(10000) { keyboardBottom > 0 }
        saveScreenshot("terminal-keyboard.png")
        compose.onNodeWithTag("send_button").performClick()
        compose.onNodeWithText("Ctrl+C").performClick()
        compose.runOnIdle { assertEquals(listOf("日本語と paste" to true, "\u0003" to false), actions.sent) }
    }

    @Test
    fun bundledRendererDisplaysAnsiAndReturnsPlainTextWithoutNetwork() {
        val loginUrl = "https://example.test/auth?state=" + "fixture".repeat(30)
        val raw = ("\r\n".repeat(5) + "\u001b[32mPTY_RENDER_OK\u001b[0m\r\n日本語\r\n$loginUrl\u001b[6n").toByteArray()
        val state =
            TerminalUiState(
                selected = "fixture",
                connected = true,
                enabled = true,
                offset = raw.size.toLong(),
                chunks = listOf(TerminalChunk(0, raw.size.toLong(), Base64.encodeToString(raw, Base64.NO_WRAP))),
            )
        var view: TerminalDisplay? = null
        val text = AtomicReference("")
        val report = AtomicReference("")
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.DARK) {
                TerminalDisplay(state, 14, Modifier.fillMaxSize(), { _, _ -> }, { view = it }, {}, { report.set(it) })
            }
        }
        compose.waitUntil(15000) { view?.ready == true }
        compose.waitUntil(15000) {
            compose.runOnIdle { view?.text { text.set(it) } }
            text.get().contains("PTY_RENDER_OK") && text.get().contains("日本語") && text.get().contains(loginUrl)
        }
        compose.waitUntil(15000) { validTerminalReport(report.get()) }
        val metrics = AtomicReference("")
        compose.runOnIdle {
            view?.evaluateJavascript(
                "JSON.stringify({width:innerWidth,height:innerHeight,screen:document.querySelector('.xterm-screen').getBoundingClientRect().toJSON(),visibility:document.visibilityState})",
            ) {
                metrics.set(it)
            }
        }
        compose.waitUntil(5000) { metrics.get().isNotEmpty() }
        java.io
            .File(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "terminal-metrics.json",
            ).writeText(metrics.get())
        saveScreenshot("terminal-renderer.png")
    }

    @Test
    fun largeTextKeepsHomeActionsAndOfflineMessageVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                HermesControlTheme { TerminalContent(TerminalUiState(error = "接続を確認してください。"), actions) }
            }
        }
        compose.onNodeWithText("接続を確認してください。").assertIsDisplayed()
        compose.onNodeWithTag("terminal_create").assertIsDisplayed()
        compose.onNodeWithTag("terminal_create_keiya").assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var window: android.view.Window? = null
        var secure = false
        compose.runOnIdle {
            window =
                ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .first()
                    .window
            secure = window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            // Synthetic fixture data only; production screen always keeps FLAG_SECURE.
            window!!.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        try {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name)
            file.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        } finally {
            compose.runOnIdle { if (secure) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
}
