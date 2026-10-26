package com.m57.hermescontrol.agents

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsGoogleConnection
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test

/** Opens Google's real chooser, then cancels without choosing an account or approving access. */
class GoogleAccountPickerInstalledTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun addAccountOpensGoogleAccountSelectionInsteadOfReusingThePreviousAccount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(
            "Unlock the phone for Google account selection",
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        )
        val automation = instrumentation.uiAutomation
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20_000) { NavigationController.backStack != null }
            compose.runOnIdle { NavigationController.navigateTo(SettingsGoogleConnection) }
            compose.waitUntil(20_000) {
                compose.onAllNodes(hasTestTag("google_connect")).fetchSemanticsNodes().any { node ->
                    !node.config.contains(SemanticsProperties.Disabled)
                }
            }
            compose.onNodeWithTag("google_connect").performScrollTo().performClick()
            compose.onNodeWithTag("connect_sheet_proceed").performClick()
            var chooser = false
            var accountRows = 0
            val deadline = SystemClock.uptimeMillis() + 30_000
            try {
                while (!chooser && SystemClock.uptimeMillis() < deadline) {
                    val root = automation.rootInActiveWindow
                    if (root?.packageName?.toString() == "com.google.android.gms") {
                        val texts = visibleTexts(root)
                        accountRows = texts.filter { it.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) }.distinct().size
                        val heading =
                            texts.any {
                                it.contains("アカウントの選択") || it.contains("アカウントを選択") ||
                                    it.contains("Choose an account", ignoreCase = true)
                            }
                        chooser = heading && accountRows >= 1
                    }
                    if (!chooser) SystemClock.sleep(150)
                }
                assertTrue("Google account selection did not appear", chooser)
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("google_real_account_picker_visible", "true")
                        putString("google_picker_account_rows", accountRows.toString())
                        putString("google_account_selected_by_test", "false")
                        putString("google_permission_approved_by_test", "false")
                        putString("mailbox_contents_read", "false")
                    },
                )
            } finally {
                if (automation.rootInActiveWindow?.packageName?.toString() == "com.google.android.gms") {
                    automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    instrumentation.waitForIdleSync()
                }
            }
        }
    }

    private fun visibleTexts(node: AccessibilityNodeInfo): List<String> =
        buildList {
            if (node.isVisibleToUser) node.text?.toString()?.let(::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let { addAll(visibleTexts(it)) }
        }
}
