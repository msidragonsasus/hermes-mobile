package com.m57.hermescontrol.ui.config

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AgentSettingsNoticeEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun consumedNoticeRemainsActionableAndNextNoticeWaitsForUndo() {
        val first = AgentSettingsNotice(1, "First saved", mapOf("setting" to JsonPrimitive(false)))
        val second = AgentSettingsNotice(2, "Second saved", mapOf("setting" to JsonPrimitive(true)))
        val notice = mutableStateOf<AgentSettingsNotice?>(first)
        val consumed = mutableListOf<Long>()
        val undone = mutableListOf<Long>()
        compose.setContent {
            MaterialTheme {
                val snackbar = remember { SnackbarHostState() }
                AgentSettingsNoticeEffect(notice.value, snackbar, {
                    consumed += it
                    if (notice.value?.id == it) notice.value = null
                }, { undone += it.id })
                SnackbarHost(snackbar)
            }
        }
        compose.onNodeWithText("First saved").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(notice.value)
            notice.value = second
        }
        compose.onNodeWithText("First saved").assertIsDisplayed()
        compose.onNodeWithText("元に戻す").performClick()
        compose.onNodeWithText("Second saved").assertIsDisplayed()
        compose.onNodeWithText("元に戻す").performClick()
        compose.runOnIdle {
            assertEquals(listOf(1L, 2L), consumed)
            assertEquals(listOf(1L, 2L), undone)
        }
    }
}
