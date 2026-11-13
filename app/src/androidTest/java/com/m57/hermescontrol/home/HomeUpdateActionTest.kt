package com.m57.hermescontrol.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendering/interaction checks; installed-version policy is tested at the controller boundary. */
@RunWith(AndroidJUnit4::class)
class HomeUpdateActionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun noConfirmedCandidateRendersNoHomeUpdateAction() {
        compose.setContent { MaterialTheme { HomeUpdateAction(null, {}) } }
        compose.onNodeWithTag("home_update_button").assertDoesNotExist()
    }

    @Test
    fun confirmedCandidateRendersOneActionAndOneTapCallsTheSuppliedUpdater() {
        var opens = 0
        compose.setContent { MaterialTheme { HomeUpdateAction("1.0.82", { opens++ }) } }
        compose.onNodeWithTag("home_update_button").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun invalidatedCandidateRemovesTheActionInsteadOfLeavingAStaleTapTarget() {
        val version = mutableStateOf<String?>("1.0.82")
        compose.setContent { MaterialTheme { HomeUpdateAction(version.value, {}) } }
        compose.onNodeWithTag("home_update_button").assertIsDisplayed()
        compose.runOnIdle { version.value = null }
        compose.onNodeWithTag("home_update_button").assertDoesNotExist()
    }

    @Test
    fun enlargedTextPreservesAVisibleAccessibleTouchTarget() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme { HomeUpdateAction("1.0.82", {}, Modifier) }
            }
        }
        compose.onNodeWithTag("home_update_button").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
}
