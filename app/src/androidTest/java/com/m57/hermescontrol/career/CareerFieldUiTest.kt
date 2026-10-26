package com.m57.hermescontrol.career

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CareerFieldUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun arrayFieldRetainsNewlineAcrossRestorationAndProjectsMultipleEntries() {
        val value = mutableStateOf<JsonElement>(JsonArray(listOf(JsonPrimitive("first"))))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { CareerField("open_questions", value.value) { value.value = it } }
        }
        compose.onNode(hasSetTextAction()).performTextInput("\n")
        compose.onNode(hasSetTextAction()).assertTextEquals("first\n")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextEquals("first\n").performTextInput("second")
        compose.runOnIdle {
            assertEquals(JsonArray(listOf(JsonPrimitive("first"), JsonPrimitive("second"))), value.value)
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("first\n\nsecond\n")
        compose.onNode(hasSetTextAction()).assertTextEquals("first\n\nsecond\n")
        compose.runOnIdle {
            assertEquals(JsonArray(listOf(JsonPrimitive("first"), JsonPrimitive("second"))), value.value)
            value.value = JsonArray(listOf(JsonPrimitive("external")))
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("external")
    }
}
