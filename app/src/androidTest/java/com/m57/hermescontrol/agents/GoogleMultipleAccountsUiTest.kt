package com.m57.hermescontrol.agents

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.career.CareerCompanyState
import com.m57.hermescontrol.career.CareerConnections
import com.m57.hermescontrol.career.CareerMailProgress
import com.m57.hermescontrol.career.CareerMailSettingsContent
import com.m57.hermescontrol.career.CareerWorkspaceAccount
import com.m57.hermescontrol.ui.connections.GoogleConnectedAccount
import com.m57.hermescontrol.ui.connections.GoogleConnectionContent
import com.m57.hermescontrol.ui.connections.GoogleConnectionStatus
import com.m57.hermescontrol.ui.connections.GoogleConnectionUiState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic rows only; never opens account choice or changes installed credentials. */
class GoogleMultipleAccountsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun careerUsesTheSavedAccountAndExplicitSelectionChoosesTheOtherOne() {
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                CareerMailSettingsContent(
                    state =
                        CareerCompanyState(
                            initialized = true,
                            connections =
                                CareerConnections(
                                    accounts = listOf("first", "second"),
                                    account_details =
                                        listOf(
                                            CareerWorkspaceAccount("first", "jobs@example.test", label = "就活"),
                                            CareerWorkspaceAccount("second", "personal@example.test", label = "個人"),
                                        ),
                                    mail =
                                        buildJsonObject {
                                            put("account_id", "second")
                                            put("enabled", false)
                                        },
                                    mail_status = "ready",
                                ),
                        ),
                    progress = CareerMailProgress(buildJsonObject { put("version", 2) }),
                    onRefresh = {},
                    onCollect = {},
                    onAutomatic = {},
                    onNotifications = {},
                    onSelectAccount = { selected = it },
                )
            }
        }
        compose.onNodeWithText("personal@example.test").assertIsDisplayed()
        compose.onNodeWithTag("mail_account_picker").performClick()
        compose.onNodeWithText("jobs@example.test · 就活").performClick()
        assertEquals("first", selected)
    }

    @Test
    fun showsFiveAccountsAndAddsWithoutSelectingAnExistingOne() {
        var called = false
        var selected: String? = "unchanged"
        show(onConnect = {
            called = true
            selected = it
        })
        screenshot("google-multi-accounts-top.png")
        compose.onNodeWithText("jobs@example.test").assertExists()
        compose.onNodeWithText("fifth@example.test").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("google_connect").performScrollTo().performClick()
        screenshot("google-multi-accounts-add.png")
        assertEquals(false, called)
        compose.onNodeWithTag("connect_sheet_proceed").performClick()
        assertEquals(true, called)
        assertNull(selected)
    }

    @Test
    fun selectedAccountRenameAndDisconnectRequireTheirOwnInteraction() {
        var renamed: Pair<String, String>? = null
        var disconnected: String? = null
        show(onRename = { id, label -> renamed = id to label }, onDisconnect = { disconnected = it })
        compose.onNodeWithTag("google_account_menu_first").performClick()
        compose.onNodeWithText("用途名を変更").performClick()
        compose.onNodeWithTag("google_account_label").performTextReplacement("就活")
        compose.onNodeWithText("保存").performClick()
        assertEquals("first" to "就活", renamed)
        compose.onNodeWithTag("google_account_menu_second").performClick()
        compose.onNodeWithText("切断").performClick()
        assertNull(disconnected)
        compose.onNodeWithText("キャンセル").performClick()
        assertNull(disconnected)
        compose.onNodeWithTag("google_account_menu_second").performClick()
        compose.onNodeWithText("切断").performClick()
        compose.onNodeWithText("切断").performClick()
        assertEquals("second", disconnected)
    }

    private fun screenshot(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        file.outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun show(
        onConnect: (String?) -> Unit = {},
        onRename: (String, String) -> Unit = { _, _ -> },
        onDisconnect: (String) -> Unit = {},
    ) {
        val rows =
            listOf(
                GoogleConnectedAccount("first", "jobs@example.test", "就活"),
                GoogleConnectedAccount("second", "personal@example.test", "個人"),
                GoogleConnectedAccount("third", "school@example.test", "学校"),
                GoogleConnectedAccount("fourth", "fourth@example.test", "個人"),
                GoogleConnectedAccount("fifth", "fifth@example.test", "個人"),
            )
        compose.setContent {
            MaterialTheme {
                GoogleConnectionContent(
                    GoogleConnectionUiState(
                        status =
                            GoogleConnectionStatus(
                                "connected",
                                accounts = rows,
                                can_add = true,
                                multi_account = true,
                            ),
                    ),
                    onRefresh = {},
                    onConnect = onConnect,
                    onRename = onRename,
                    onDisconnect = onDisconnect,
                )
            }
        }
    }
}
