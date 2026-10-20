package com.m57.hermescontrol.ui.chat.components

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.DraftState
import com.m57.hermescontrol.domain.hermes.OutgoingDraft
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class OutgoingDraftPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private fun draft(state: DraftState) =
        OutgoingDraft(
            id = "fixture",
            scope = ConnectionScope("fixture-server", null, null, "fixture-process", 1),
            savedSessionId = "fixture-conversation",
            runtimeSessionId = "fixture-runtime",
            text = "送信内容を確認するための下書きです。",
            attachments = emptyList(),
            createdAt = 1,
            state = state,
        )

    @Test
    fun unknownHasNoRestoreActionAndOriginalConversationRemainsReachableAtLargeFont() {
        var opened: String? = null
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.JAPANESE) }
            val context = LocalContext.current.createConfigurationContext(config)
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalContext provides context,
                LocalResources provides context.resources,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
            ) {
                HermesControlTheme {
                    OutgoingDraftPanel(
                        listOf(draft(DraftState.UNKNOWN)),
                        emptySet(),
                        false,
                        onRestore = { error("UNKNOWN must not restore") },
                        onOpenConversation = { opened = it },
                        onDiscard = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("outgoing-drafts").performClick()
        compose.onNodeWithTag("draft-restore-fixture").assertDoesNotExist()
        compose.onNodeWithText("送信結果が不明").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("元の会話を開く").performScrollTo().assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "pr06a-drafts-ja-large.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        compose.onNodeWithText("元の会話を開く").performClick()
        compose.runOnIdle { assertEquals("fixture", opened) }
    }

    @Test
    fun pendingDraftCannotBeEditedOrDeleted() {
        compose.setContent {
            HermesControlTheme {
                OutgoingDraftPanel(
                    listOf(draft(DraftState.DRAFT)),
                    setOf("fixture"),
                    false,
                    onRestore = { error("Pending draft must not restore") },
                    onOpenConversation = {},
                    onDiscard = { error("Pending draft must not delete") },
                )
            }
        }
        compose.onNodeWithTag("outgoing-drafts").performClick()
        compose.onNodeWithTag("draft-restore-fixture").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("draft-discard-fixture").performScrollTo().assertIsNotEnabled()
    }
}
