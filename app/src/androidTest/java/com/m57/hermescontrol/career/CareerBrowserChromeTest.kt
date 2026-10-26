package com.m57.hermescontrol.career

import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.espresso.Espresso
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.theme.HermesControlTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.lang.ref.WeakReference

/** The production screen, with only company/network dependencies stubbed. No portal traffic. */
class CareerBrowserChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val key = CareerMyPageBrowserKey("fixture-company")
    private lateinit var stack: NavBackStack<NavKey>

    @Before
    fun prepare() {
        mockkObject(AuthManager, ApiClient)
        every { AuthManager.baseUrlFlow } returns MutableStateFlow("https://gateway.example.test/")
        every { AuthManager.selectedProfileFlow } returns MutableStateFlow<String?>(null)
        every { AuthManager.activeProfileId } returns MutableStateFlow<String?>("default")
        every { AuthManager.getBaseUrl() } returns "https://gateway.example.test/"
        every { ApiClient.hermesApi } returns mockk(relaxed = true)
        mockkConstructor(CareerCompanyRepository::class)
        coEvery { anyConstructed<CareerCompanyRepository>().company("fixture-company") } returns
            CareerCompanyDetail(
                company =
                    CareerEntity(
                        "companies",
                        "fixture-company",
                        1,
                        buildJsonObject { put("name", "Fixture company") },
                    ),
                thread_id = "company:fixture-company",
            )
        stack = NavBackStack(CareerScreenKey, key)
        NavigationController.attachAuth(stack)
    }

    @After
    fun cleanUp() {
        unmockkConstructor(CareerCompanyRepository::class)
        unmockkObject(AuthManager, ApiClient)
    }

    @Test
    fun browserDoesNotSetScreenshotRestrictionAndCloseKeepsParent() {
        compose.runOnIdle { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.setContent { HermesControlTheme { CareerMyPageBrowserScreen(key) } }
        compose.onNodeWithContentDescription("ブラウザを閉じる").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hermesの保存済みID・パスワードを入力").assertIsDisplayed()
        compose.onNodeWithText("Fixture company").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
        compose.onNodeWithContentDescription("ブラウザを閉じる").performClick()
        compose.runOnIdle { assertEquals(listOf(CareerScreenKey), stack.toList()) }
    }

    @Test
    fun browserNeverClearsAnotherScreensExistingSecureFlag() {
        val shown = mutableStateOf(true)
        compose.runOnIdle { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.setContent { if (shown.value) HermesControlTheme { CareerMyPageBrowserScreen(key) } }
        compose.runOnIdle {
            assertEquals(
                WindowManager.LayoutParams.FLAG_SECURE,
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
            )
            shown.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                WindowManager.LayoutParams.FLAG_SECURE,
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
            )
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Test
    fun reloadActionIsDiscoverableInBrowserMenu() {
        compose.setContent { HermesControlTheme { CareerMyPageBrowserScreen(key) } }
        compose.onNodeWithContentDescription("マイページのその他の操作").performClick()
        compose.onNodeWithText("再読込").assertIsDisplayed()
        compose.onNodeWithText("開き直す").assertIsDisplayed()
    }

    @Test
    fun menuPopupsAreReleasedAfterRepeatedDismissAndScreenNavigation() {
        val shown = mutableStateOf(true)
        val popups = mutableListOf<WeakReference<View>>()
        compose.setContent { if (shown.value) HermesControlTheme { CareerMyPageBrowserScreen(key) } }
        repeat(3) {
            compose.onNodeWithContentDescription("マイページのその他の操作").performClick()
            compose.onNodeWithText("再読込").assertIsDisplayed()
            compose.runOnIdle {
                val roots =
                    WindowInspector.getGlobalWindowViews().filter {
                        it.javaClass.name == "androidx.compose.ui.window.PopupLayout"
                    }
                assertTrue("DropdownMenu must create the actual PopupLayout", roots.isNotEmpty())
                popups.addAll(roots.map { WeakReference(it) })
            }
            Espresso.pressBack()
            compose.waitForIdle()
            compose.runOnIdle {
                assertFalse(compose.activity.isDestroyed)
                assertTrue(
                    WindowInspector.getGlobalWindowViews().none {
                        it.javaClass.name == "androidx.compose.ui.window.PopupLayout" && it.isAttachedToWindow
                    },
                )
            }
        }
        // Test collection while the browser screen and Activity are still alive, matching trace 2.
        compose.waitUntil(10_000) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            popups.all { it.get() == null }
        }
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { shown.value = true }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("マイページのその他の操作").performClick()
        compose.onNodeWithText("再読込").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(compose.activity.isDestroyed)
            assertEquals(listOf(CareerScreenKey, key), stack.toList())
        }
    }
}
