package com.m57.hermescontrol.ui.attention

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.AttentionDetails
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.AttentionKind
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AttentionDetailTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ref =
        RequestRef(
            ConnectionScope("synthetic-server", "connection", "profile", "process", 1),
            SessionRef("runtime"),
            "original-request",
        )

    @Test
    fun largeFontDetailsKeepDecisionControlsVisibleAndBlockRepeatWhileWaitingForAck() {
        val item = mutableStateOf(AttentionItem(ref, AttentionKind.APPROVAL, "合成テストの公開操作"))
        val choices = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    AttentionDetailContent(ref, item.value, true, onApprove = {
                        choices.add(it)
                        item.value = item.value.copy(status = AttentionStatus.SUBMITTING)
                    }, onClarify = {}, onBack = {})
                }
            }
        }
        compose.onNodeWithTag("attention_once").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.attention_impact)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("original-request").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("attention_once").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("attention_deny").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("once"), choices) }
    }

    @Test
    fun staleConnectionDisablesBothDecisions() {
        compose.setContent {
            MaterialTheme {
                AttentionDetailContent(ref, AttentionItem(ref, AttentionKind.APPROVAL, "Synthetic"), false, {}, {}, {})
            }
        }
        compose.onNodeWithTag("attention_once").assertIsNotEnabled()
        compose.onNodeWithTag("attention_deny").assertIsNotEnabled()
    }

    @Test
    fun coldStartWithoutObservedRequestHasNoApprovalActions() {
        compose.setContent { MaterialTheme { AttentionDetailContent(ref, null, true, {}, {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.attention_missing)).assertIsDisplayed()
        compose.onNodeWithTag("attention_once").assertDoesNotExist()
        compose.onNodeWithTag("attention_deny").assertDoesNotExist()
    }

    @Test
    fun clarificationChoiceUsesExistingQuestionControl() {
        var answer: String? = null
        val request = ref.copy(questionId = "q2")
        val item =
            AttentionItem(
                request,
                AttentionKind.CLARIFICATION,
                "Which repository?",
                details = AttentionDetails(options = listOf("Repository A", "Repository B")),
            )
        compose.setContent { MaterialTheme { AttentionDetailContent(request, item, true, {}, { answer = it }, {}) } }
        compose.onNodeWithText("Repository B").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Repository B", answer) }
    }
}
