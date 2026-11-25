package com.m57.hermescontrol.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.ui.chat.components.ChatComposer
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainDrawerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fourMeasuredRowsFitAndOnlyTheBotsScroll() {
        showLayout(height = 580.dp, botCount = 7, fontScale = 2f)

        val header = bounds("header")
        val settings = bounds("settings")
        val create = bounds("create")
        repeat(4) { index -> compose.onNodeWithTag("bot$index").assertIsDisplayed() }
        val tallRow = bounds("bot1")
        val regularRow = bounds("bot0")
        assertTrue(
            "Expected a taller row. Tall bounds $tallRow, regular bounds $regularRow",
            tallRow.bottom - tallRow.top > regularRow.bottom - regularRow.top,
        )
        assertEquals(bounds("bot3").bottom, create.top)
        compose.onNodeWithTag("bot4").assertIsNotDisplayed()
        val initialEdges = botViewportEdgePixels()
        assertNotEquals(initialEdges.first, initialEdges.second)

        compose.onNodeWithTag("bot6").performScrollTo().assertIsDisplayed()
        val scrolledEdges = botViewportEdgePixels()
        assertEquals(scrolledEdges.first, scrolledEdges.second)
        assertEquals(header, bounds("header"))
        assertEquals(create, bounds("create"))
        assertEquals(settings, bounds("settings"))
        assertSettingsAboveBottomInset()
    }

    @Test
    fun fewBotsLeaveTheCreateRowImmediatelyAfterTheList() {
        showLayout(height = 420.dp, botCount = 2)

        assertEquals(bounds("bot1").bottom, bounds("create").top)
        assertSettingsAboveBottomInset()
        val edges = botViewportEdgePixels()
        assertEquals(edges.first, edges.second)
    }

    @Test
    fun overflowThumbStartsAtTheTrailingEdgeInRtl() {
        showLayout(height = 580.dp, botCount = 7, darkTheme = true, direction = LayoutDirection.Rtl)

        val edges = botViewportEdgePixels()
        assertNotEquals(edges.first, edges.second)
        assertTrue(edges.first.luminance() > edges.second.luminance())
    }

    @Test
    fun shortLargeTextDrawerKeepsEverySectionReachable() {
        showLayout(height = 185.dp, botCount = 7, fontScale = 2f, darkTheme = true)

        val settings = bounds("settings")
        compose.onNodeWithTag("create").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("bot6").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("header").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings").assertIsDisplayed()
        assertEquals(settings, bounds("settings"))
        assertSettingsAboveBottomInset()
    }

    @Test
    fun buttonAndSwipeOpeningClearFocusAndKeepTheDraft() {
        val keyboard = RecordingKeyboard()
        compose.setContent {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            var draft by remember { mutableStateOf("") }
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                MaterialTheme {
                    DismissImeOnDrawerOpen(drawerState)
                    ModalNavigationDrawer(
                        modifier = Modifier.testTag("modal"),
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet {
                                MainDrawerLayout(
                                    header = { RowItem("header") },
                                    botRows = { repeat(7) { RowItem("bot$it") } },
                                    createBot = { RowItem("create") },
                                    settings = { RowItem("settings") },
                                )
                            }
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            ChatComposer(
                                value = draft,
                                onValueChange = { draft = it },
                                onSend = {},
                                inputTestTag = "draft",
                            )
                            TextButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.align(Alignment.BottomCenter).testTag("open"),
                            ) { Text("Open") }
                        }
                    }
                }
            }
        }

        val draft = compose.onNodeWithTag("draft")
        draft.performClick().performTextInput("Draft text")
        draft.assertIsFocused()
        val hidesBeforeOpen = compose.runOnIdle { keyboard.hideCalls }
        compose.onNodeWithTag("open").performClick()
        compose.onNodeWithTag("settings").assertIsDisplayed()
        draft.assertIsNotFocused().assertTextEquals("Draft text")
        val hidesAfterButton = compose.runOnIdle { keyboard.hideCalls }
        assertTrue(hidesAfterButton > hidesBeforeOpen)

        // A horizontal gesture over the vertically scrollable bot rows closes the drawer.
        compose.onNodeWithTag("bot1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("settings").assertIsNotDisplayed()
        draft.assertIsNotFocused().assertTextEquals("Draft text")
        assertEquals(hidesAfterButton, compose.runOnIdle { keyboard.hideCalls })

        draft.performClick().assertIsFocused()
        compose.onNodeWithTag("modal").performTouchInput {
            swipe(start = Offset(1f, centerY), end = Offset(width * 0.9f, centerY))
        }
        compose.onNodeWithTag("settings").assertIsDisplayed()
        draft.assertIsNotFocused().assertTextEquals("Draft text")
        assertTrue(compose.runOnIdle { keyboard.hideCalls } > hidesAfterButton)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun botViewportEdgePixels(): Pair<Color, Color> {
        val drawer = bounds("drawer")
        val header = bounds("main")
        val image = compose.onNodeWithTag("drawer").captureToImage().toPixelMap()
        val density = image.height / (drawer.bottom - drawer.top).value
        val y = ((header.bottom - drawer.top + 10.dp).value * density).toInt()
        val left = ((header.left - drawer.left + 3.5.dp).value * density).toInt()
        val right = ((header.right - drawer.left - 3.5.dp).value * density).toInt()
        return image[left, y] to image[right, y]
    }

    private fun assertSettingsAboveBottomInset() {
        val inset = bounds("drawer").bottom - bounds("settings").bottom
        assertEquals(24f, inset.value, 1f)
    }

    private fun showLayout(
        height: Dp,
        botCount: Int,
        fontScale: Float = 1f,
        darkTheme: Boolean = false,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.height(height).testTag("drawer")) {
                        ModalDrawerSheet(windowInsets = WindowInsets(bottom = 24.dp)) {
                            MainDrawerLayout(
                                header = {
                                    RowItem("header")
                                    RowItem("main")
                                },
                                botRows = { repeat(botCount) { RowItem("bot$it", tall = it == 1) } },
                                createBot = { RowItem("create") },
                                settings = { RowItem("settings") },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RowItem(
        tag: String,
        tall: Boolean = false,
    ) {
        Box(Modifier.heightIn(min = if (tall) 96.dp else 0.dp).testTag(tag)) {
            NavigationDrawerItem(
                label = { Text(tag, maxLines = 1) },
                onClick = {},
                selected = false,
            )
        }
    }

    private class RecordingKeyboard : SoftwareKeyboardController {
        var hideCalls = 0

        override fun show() = Unit

        override fun hide() {
            hideCalls++
        }
    }
}
