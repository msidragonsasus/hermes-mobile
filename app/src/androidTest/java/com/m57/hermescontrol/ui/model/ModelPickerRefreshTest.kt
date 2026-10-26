package com.m57.hermescontrol.ui.model

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelPickerRefreshTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun refreshActionDisablesWhileRefreshingAndCachedModelRemainsSelectable() {
        val loading = mutableStateOf(false)
        var refreshes = 0
        var selected: Pair<String, String>? = null
        composeRule.setContent {
            MaterialTheme {
                ModelPickerDialog(
                    providers = listOf(ModelProvider("openai", "OpenAI", models = listOf("existing-model"))),
                    title = "Switch model",
                    isLoading = loading.value,
                    onRefresh = {
                        refreshes++
                        loading.value = true
                    },
                    onSelect = { provider, model -> selected = provider to model },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Refresh models").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Refresh models").assertIsNotEnabled()
        composeRule.onNodeWithText("existing-model").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals("openai" to "existing-model", selected)
        }
    }

    @Test
    fun emptyCatalogFailureShowsErrorAndOffersRefresh() {
        var refreshes = 0
        composeRule.setContent {
            MaterialTheme {
                ModelPickerDialog(
                    providers = emptyList(),
                    title = "Switch model",
                    errorMessage = "Catalog unavailable",
                    onRefresh = { refreshes++ },
                    onSelect = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Catalog unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh models").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, refreshes) }
    }
}
