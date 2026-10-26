package com.m57.hermescontrol.career

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CareerSourceReaderTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loadingBecomesInlineBodyAndKeepsActionsWithoutDialog() {
        val body = mutableStateOf<String?>(null)
        var origins = 0
        var consultations = 0
        var backs = 0
        compose.setContent {
            MaterialTheme {
                CareerSourceReaderScreen(
                    "Synthetic subject",
                    body.value,
                    null,
                    { backs++ },
                    { origins++ },
                    { consultations++ },
                    {},
                )
            }
        }
        compose.onNodeWithTag("loading_state").assertIsDisplayed()
        compose.onNodeWithTag("career_reader_origin").assertIsEnabled().performClick()
        compose.onNodeWithTag("career_reader_consult").assertIsEnabled().performClick()
        compose.runOnIdle { body.value = "Synthetic complete notice body" }
        compose.onNodeWithTag("career_reader_body").assertIsDisplayed()
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("back_button", useUnmergedTree = true).onParent().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1, 1), listOf(origins, consultations, backs)) }
    }

    @Test
    fun failureRetriesInlineWhileOriginAndConsultRemainEnabled() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                CareerSourceReaderScreen(
                    "Synthetic failure",
                    null,
                    "Synthetic read unavailable",
                    {},
                    {},
                    {},
                    { retries++ },
                )
            }
        }
        compose.onNodeWithText("Synthetic read unavailable").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_retry)).performClick()
        compose.onNodeWithTag("career_reader_origin").assertIsEnabled()
        compose.onNodeWithTag("career_reader_consult").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun longBodyScrollsFullWidthAtLargeFontAndActionsStayVisible() {
        val body =
            (1..90).joinToString("\n\n") {
                "Synthetic notice paragraph $it. The full original content remains readable."
            }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    CareerSourceReaderScreen("Synthetic long subject ".repeat(12), body, null, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("career_reader_scroll").performScrollToNode(hasTestTag("career_reader_end"))
        compose.onNodeWithTag("career_reader_scroll").performTouchInput { swipeUp() }
        // The end marker is an intentionally zero-width Spacer. Check its vertical position,
        // rather than assertIsDisplayed(), which correctly rejects a zero-area node.
        val viewport = compose.onNodeWithTag("career_reader_scroll").getUnclippedBoundsInRoot()
        val end = compose.onNodeWithTag("career_reader_end").getUnclippedBoundsInRoot()
        assertTrue(
            "End $end must be inside scroll viewport $viewport",
            end.top >= viewport.top && end.bottom <= viewport.bottom,
        )
        compose.onNodeWithTag("career_reader_origin").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("career_reader_consult").assertIsDisplayed().assertIsEnabled()
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun expandedSubjectSurvivesSavedStateRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                CareerSourceReaderScreen("Synthetic subject ".repeat(20), "Synthetic body", null, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("career_reader_expand_subject").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.getString(R.string.career_reader_collapse_subject)).assertIsDisplayed()
        compose.onNodeWithTag("career_reader_consult").assertIsDisplayed()
    }

    @Test
    fun originalLinkUsesExistingUriHandlerWithoutNetworkOrModel() {
        val opened = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            opened.add(uri)
                        }
                    },
            ) {
                MaterialTheme {
                    CareerSourceReaderScreen("Synthetic link", "https://example.test/body", null, {
                    }, {}, {}, {}, url = "https://example.test/source")
                }
            }
        }
        compose.onNodeWithTag("career_reader_source_link").performClick()
        compose.runOnIdle { assertEquals(listOf("https://example.test/source"), opened) }
    }

    @Test
    fun emptyBodyRemainsOnTheSameReaderScreen() {
        compose.setContent { MaterialTheme { CareerSourceReaderScreen("Synthetic empty", "", null, {}, {}, {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.career_reader_empty)).assertIsDisplayed()
        compose.onNodeWithTag("career_reader_origin").assertIsEnabled()
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
    }
}
