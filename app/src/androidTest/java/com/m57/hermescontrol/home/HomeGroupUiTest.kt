package com.m57.hermescontrol.home

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeGroupUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun groupedActionsExpandAndOpenTheExactItemAtLargeText() {
        val actions =
            listOf("one", "two").map { id ->
                HomeItem(id, "1", "career", "action", "相談への回答が必要です", HomeTarget("thread", thread_id = id))
            }
        val expanded = mutableStateOf(false)
        var opened: HomeItem? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                HermesControlTheme(themePreference = ThemePreference.DARK) {
                    Surface(Modifier.fillMaxSize()) {
                        HomeGroupEntry(
                            group = actions,
                            expanded = expanded.value,
                            labelOf = { item, _ -> "相談 ${item.id}" },
                            onToggle = { expanded.value = !expanded.value },
                            onOpen = { opened = it },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("相談 two").assertDoesNotExist()
        compose.onNodeWithText("相談への回答が必要です").performClick()
        compose.onNodeWithText("相談 one").assertIsDisplayed()
        compose.onNodeWithText("相談 two").assertIsDisplayed().performClick()
        assertEquals(actions[1], opened)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "home-group-dark-200.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("相談への回答が必要です").performClick()
        compose.onNodeWithText("相談 two").assertDoesNotExist()
    }
}
