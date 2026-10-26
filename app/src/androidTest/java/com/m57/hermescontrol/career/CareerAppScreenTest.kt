package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.home.HomePage
import com.m57.hermescontrol.theme.HermesControlTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CareerAppScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val api = mockk<HermesApiService>()
    private val profile = MutableStateFlow<String?>("a")

    @Before fun setup() {
        mockkObject(AuthManager, ApiClient)
        every { AuthManager.baseUrlFlow } returns MutableStateFlow("https://${UUID.randomUUID()}.invalid")
        every { AuthManager.selectedProfileFlow } returns MutableStateFlow<String?>("synthetic")
        every { AuthManager.activeProfileId } returns profile
        every { AuthManager.isGatedMode() } returns true
        every { AuthManager.uiPreferenceScope() } returns "synthetic-home"
        every { ApiClient.hermesApi } returns api
        coEvery { api.careerSnapshot() } returns
            CareerSnapshot(
                1,
                listOf(
                    CareerEntity(
                        "facts",
                        "fact",
                        1,
                        buildJsonObject {
                            put("statement", "本人が確認した経験")
                            put("status", "CONFIRMED")
                        },
                    ),
                ),
            )
        coEvery { api.careerHome(any(), any()) } returns HomePage()
        coEvery { api.careerCompanies(any()) } returns
            CareerCompanies(
                companies =
                    listOf(
                        CareerCompanyRow("a", "企業A", application_counts = mapOf("in_process" to 1)),
                        CareerCompanyRow("b", "企業B", application_counts = mapOf("offered" to 1)),
                    ),
            )
        coEvery { api.careerConnections() } returns CareerConnections()
        coEvery { api.careerCoachCapabilities(any()) } returns
            CareerCapabilities(features = listOf("conversation_threads"))
        coEvery { api.careerThreads(any()) } answers
            { CareerThreads(profile = firstArg(), threads = listOf(CareerThread("thread:a", "面接対策"))) }
        runBlocking { CareerLocalStore(compose.activity, careerScope()).setEnabled(true) }
        NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
    }

    @After fun cleanup() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        unmockkAll()
    }

    @Test fun entityConsultationDoesNotPopTheNewThreadPicker() {
        NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey, CareerEntityKey("fact")))
        compose.setContent { HermesControlTheme { CareerEntityScreen("fact") } }
        compose.waitUntil(10000) { compose.onAllNodes(hasText("この情報で相談する")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("この情報で相談する").performClick()
        compose.runOnIdle {
            val route = NavigationController.backStack!!.last() as CareerThreadsKey
            assertEquals("fact", route.target?.id)
            NavigationController.goBack()
            assertEquals(CareerEntityKey("fact"), NavigationController.backStack!!.last())
        }
    }

    @Test fun fourAreasKeepCompanySearchAndOpenSettingsWithoutStartingWork() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HermesControlTheme { CareerAppScreen() } }
        compose.waitUntil(10000) { compose.onAllNodes(hasText("回答・判断待ち")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("企業", substring = false).performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("企業を探す")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("企業を探す").performTextInput("企業B")
        compose.onNodeWithText("資料", substring = false).performClick()
        compose.onNodeWithText("本人情報").performScrollTo().performClick()
        compose.onNodeWithText("本人が確認した経験").assertIsDisplayed()
        compose.onNodeWithText("相談", substring = false).performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("面接対策")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("面接対策").assertIsDisplayed()
        compose.onNodeWithText("企業", substring = false).performClick()
        compose.onNodeWithText("企業A").assertDoesNotExist()
        compose.onNodeWithTag("career_company_b").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("企業A").assertDoesNotExist()
        compose.onNodeWithTag("career_company_b").assertIsDisplayed()
        compose.onNodeWithText("設定").performClick()
        compose.runOnIdle {
            assertEquals(CareerSettingsKey, NavigationController.backStack!!.last())
            coVerify(exactly = 0) { api.careerMutation(any(), any()) }
            coVerify(exactly = 0) { api.careerCollection(any(), any(), any()) }
            coVerify(exactly = 0) { api.careerCreateThread(any(), any()) }
        }
    }

    @Test fun lastAreaIsScopedToTheConnectionAndProfile() {
        runBlocking {
            CareerLocalStore(compose.activity, careerScope()).saveUiValue("area", "資料")
            val secondScope = careerScope().substringBeforeLast('\n') + "\nb"
            CareerLocalStore(compose.activity, secondScope).setEnabled(true)
        }
        compose.setContent { HermesControlTheme { CareerAppScreen() } }
        compose.waitUntil(10000) { compose.onAllNodes(hasText("自己分析")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { profile.value = "b" }
        compose.waitUntil(10000) { compose.onAllNodes(hasText("回答・判断待ち")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("自己分析").assertDoesNotExist()
    }
}
