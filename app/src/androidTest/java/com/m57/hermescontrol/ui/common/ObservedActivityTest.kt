package com.m57.hermescontrol.ui.common

import androidx.compose.material3.MaterialTheme
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
