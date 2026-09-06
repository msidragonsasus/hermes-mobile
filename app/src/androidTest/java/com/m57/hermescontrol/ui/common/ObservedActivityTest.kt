package com.m57.hermescontrol.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.ExecutionState
import com.m57.hermescontrol.domain.hermes.ObservationKind
import com.m57.hermescontrol.domain.hermes.ObservedRun
import com.m57.hermescontrol.domain.hermes.RunSnapshot
import com.m57.hermescontrol.domain.hermes.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ObservedActivityTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = ConnectionScope("synthetic", "connection", "profile", "process", 1)

    @Test
    fun confirmedResponseLeavesNeedsYouWithoutClaimingTheRunCompleted() {
        val ref =
            com.m57.hermescontrol.domain.hermes
                .RequestRef(scope, SessionRef("runtime"), "request")
        val item =
            com.m57.hermescontrol.domain.hermes.AttentionItem(
                ref,
                com.m57.hermescontrol.domain.hermes.AttentionKind.APPROVAL,
                "Synthetic approval",
            )
        val attention = mutableStateOf(listOf(item))
        val run =
            ObservedRun(scope, ref.session, ExecutionState.WAITING_APPROVAL, ObservationKind.APPROVAL, 1, request = ref)
        val snapshot =
            RunSnapshot(
                connection =
                    com.m57.hermescontrol.domain.hermes.TransportConnection(
                        scope,
                        com.m57.hermescontrol.domain.hermes.LinkState.CONNECTED,
                    ),
                runs = listOf(run),
            )
        compose.setContent { MaterialTheme { ObservedActivityContent(snapshot, true, attention.value) } }
        compose.onNodeWithText(context.getString(R.string.attention_open_details)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            attention.value =
                listOf(item.copy(status = com.m57.hermescontrol.domain.hermes.AttentionStatus.CONFIRMED))
        }
        compose.onNodeWithText(context.getString(R.string.attention_open_details)).assertDoesNotExist()
        compose
            .onNodeWithText(
                context.getString(R.string.activity_no_observed_requests),
            ).performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                context.getString(R.string.activity_last_confirmed),
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun offlineObservationShowsUncertaintyAndOpensExactSavedSession() {
        val run =
            ObservedRun(
                scope,
                SessionRef("runtime", "saved"),
                ExecutionState.RUNNING,
                ObservationKind.TOOL_STARTED,
                1,
                uncertain = true,
            )
        compose.setContent { MaterialTheme { ObservedActivityContent(RunSnapshot(runs = listOf(run)), today = false) } }
        compose.onNodeWithText(context.getString(R.string.activity_offline)).assertIsDisplayed()
        compose
            .onNodeWithText(
                context.getString(R.string.activity_last_confirmed),
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.activity_open_session)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("saved", NavigationController.consumePendingSessionId())
        }
    }

    @Test
    fun runtimeIdentifierAloneDoesNotPretendToBeSavedSession() {
        val run =
            ObservedRun(
                scope,
                SessionRef("runtime"),
                ExecutionState.WAITING_APPROVAL,
                ObservationKind.APPROVAL,
                1,
            )
        compose.setContent { MaterialTheme { ObservedActivityContent(RunSnapshot(runs = listOf(run)), today = true) } }
        compose
            .onNodeWithText(
                context.getString(R.string.activity_session_unavailable),
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.activity_open_session)).performScrollTo().assertIsNotEnabled()
    }
}
