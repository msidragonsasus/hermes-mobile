package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.domain.hermes.SessionActivity
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic UI only: never contacts a server or sends a chat. */
class ConversationHistoryStatusTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val longTitle = "長いセッション名がドロワーの右端まで続いても状態アイコンとは重ならずに省略されることを確認する会話"
    private val history =
        SessionsUiState(
            sessions =
                listOf(
                    SessionInfo("short", "短い"),
                    SessionInfo("long", longTitle),
                    SessionInfo("approval", longTitle + "（承認）"),
                    SessionInfo("question", "質問"),
                    SessionInfo("stale", "再同期"),
                    SessionInfo("waiting", "確認"),
                    SessionInfo("starting", longTitle + "（開始）"),
                    SessionInfo("background", longTitle + "（バックグラウンド）"),
                    SessionInfo("rechecking", "再確認"),
                ),
        )
    private val activity =
        mapOf(
            "long" to SessionActivity.RUNNING,
            "approval" to SessionActivity.WAITING_APPROVAL,
            "question" to SessionActivity.NEEDS_INPUT,
            "stale" to SessionActivity.UNCERTAIN,
            "waiting" to SessionActivity.WAITING,
            "starting" to SessionActivity.STARTING,
            "background" to SessionActivity.BACKGROUND,
            "rechecking" to SessionActivity.RUNNING,
        )

    private fun render(
        fontScale: Float = 1f,
        width: Int? = null,
        currentSessionId: String? = null,
        unverified: Boolean = false,
        onSelect: (String) -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HermesControlTheme {
                    ConversationMenuSheet(
                        null,
                        {},
                        {},
                        {},
                        {},
                        historyState = history,
                        historyActivity = activity,
                        historyRechecking = setOf("rechecking"),
                        historyStatusUnverified = unverified,
                        currentSessionId = currentSessionId,
                        onSelectSession = onSelect,
                        drawerModifier = width?.let { Modifier.width(it.dp) } ?: Modifier,
                    )
                }
            }
        }
    }

    private fun bounds(tag: String): DpRect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertFixedTrailingBoundary() {
        val ids = history.sessions.map { it.id }
        val slots = ids.map { bounds("conversation_status_$it") }
        // Quiet rows reserve the same slot, so every title ends at one boundary.
        assertEquals(1, slots.map { it.left }.distinct().size)
        assertEquals(1, slots.map { it.right }.distinct().size)
        ids.zip(slots).forEach { (id, slot) ->
            val title = bounds("conversation_title_$id")
            val row = bounds("conversation_session_$id")
            assertTrue("$id title overlaps status", title.right <= slot.left)
            assertTrue("$id title clipped vertically", title.top >= row.top && title.bottom <= row.bottom)
            assertTrue("$id row shorter than 56dp", row.bottom - row.top >= 56.dp)
        }
    }

    private fun hasState(label: Int) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(label))

    @Test
    fun longAndShortTitlesEndAtOneFixedTrailingBoundary() {
        render()
        assertFixedTrailingBoundary()
        compose.onNodeWithTag("conversation_status_long", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun statusIsAnnouncedWithTheFullTitleAndQuietRowsClaimNothing() {
        render()
        compose
            .onNodeWithTag(
                "conversation_session_long",
            ).assert(hasText(longTitle))
            .assert(hasState(R.string.activity_running))
        compose.onNodeWithTag("conversation_session_approval").assert(hasState(R.string.activity_waiting_approval))
        compose.onNodeWithTag("conversation_session_question").assert(hasState(R.string.activity_needs_input))
        compose.onNodeWithTag("conversation_session_waiting").assert(hasState(R.string.agent_history_status_waiting))
        // Queued/admitted is announced as starting, never as already running.
        compose.onNodeWithTag("conversation_session_starting").assert(hasState(R.string.agent_history_status_starting))
        compose
            .onNodeWithTag("conversation_session_background")
            .assert(hasState(R.string.agent_history_status_background))
        compose
            .onNodeWithTag("conversation_session_short")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun unconfirmedStatusIsExplainedInWordsNotByAMysteryIcon() {
        render(unverified = true)
        // The row says what it means; the quiet ring replaces the unexplained sync-problem icon.
        compose
            .onNodeWithTag("conversation_session_stale")
            .assert(hasState(R.string.agent_history_status_unconfirmed))
        compose.onNodeWithTag("conversation_status_unconfirmed_stale", useUnmergedTree = true).assertIsDisplayed()
        // Still-fresh evidence during a failed refresh is announced as rechecking, not as confirmed.
        val rechecking =
            context.getString(R.string.agent_history_status_rechecking, context.getString(R.string.activity_running))
        compose
            .onNodeWithTag("conversation_session_rechecking")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, rechecking))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("conversation_status_notice").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithTag("conversation_status_notice")
            .assertIsDisplayed()
            .assert(hasText(context.getString(R.string.agent_history_status_notice)))
        assertFixedTrailingBoundary()
    }

    @Test
    fun confirmedStatusShowsNoNotice() {
        render()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("conversation_status_notice").assertDoesNotExist()
    }

    @Test
    fun selectedRowStaysDistinguishableAndSelectableWithStatus() {
        var selected: String? = null
        render(currentSessionId = "long", onSelect = { selected = it })
        compose
            .onNodeWithTag(
                "conversation_session_long",
            ).assertIsSelected()
            .assert(hasState(R.string.activity_running))
        compose.onNodeWithTag("conversation_session_approval").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals("approval", selected) }
    }

    @Test
    fun narrowDrawerAtDoubleFontKeepsBoundaryWithoutClipping() {
        render(fontScale = 2f, width = 280)
        assertFixedTrailingBoundary()
    }

    @Test
    fun enlargedFontKeepsBoundaryWithoutClipping() {
        render(fontScale = 1.3f)
        assertFixedTrailingBoundary()
    }
}
