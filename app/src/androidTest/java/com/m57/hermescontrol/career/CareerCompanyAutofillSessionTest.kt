package com.m57.hermescontrol.career

import android.util.SparseArray
import android.view.View
import android.view.autofill.AutofillValue
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.HermesControlTheme
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises Android's virtual-view fill callback, including a password-picker round trip. */
class CareerCompanyAutofillSessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val token = MutableStateFlow<String?>("fixture-ticket-one")
    private var cookie: String? = "fixture-dashboard-session"
    private var gated = true
    private lateinit var host: View
    private val repository = mockk<CareerCompanyRepository>()
    private val local = mockk<CareerLocalStore>(relaxed = true)
    private val detail =
        CareerCompanyDetail(
            company = CareerEntity("companies", "fixture-company", 1, buildJsonObject { put("name", "Fixture") }),
            thread_id = "company:fixture-company",
            mypage = CareerMyPage(bot_available = true, portal_url = "https://portal.example.test/login"),
        )

    @Before
    fun prepareSession() {
        mockkObject(AuthManager)
        every { AuthManager.tokenFlow } returns token
        every { AuthManager.baseUrlFlow } returns MutableStateFlow("https://hermes.example.test")
        every { AuthManager.selectedProfileFlow } returns MutableStateFlow("fixture-connection")
        every { AuthManager.activeProfileId } returns MutableStateFlow("default")
        every { AuthManager.isGatedMode() } answers { gated }
        every { AuthManager.getSessionCookie() } answers { cookie }
    }

    @After
    fun cleanUp() {
        unmockkObject(AuthManager)
    }

    private fun showEditor() {
        compose.setContent {
            host = LocalView.current
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyLoginEditor(detail, emptyList(), {}, { _, _ -> }, local, repository)
                }
            }
        }
        compose.onNodeWithTag("mypage_login_id").performScrollTo()
    }

    private fun fieldIds(): Pair<Int, Int> =
        compose.onNodeWithTag("mypage_login_id").fetchSemanticsNode().id to
            compose.onNodeWithTag("mypage_password").fetchSemanticsNode().id

    private fun fillFromAndroid(ids: Pair<Int, Int>) {
        compose.runOnIdle {
            host.autofill(
                SparseArray<AutofillValue>().apply {
                    put(ids.first, AutofillValue.forText("fixture-login"))
                    put(ids.second, AutofillValue.forText("fixture-password"))
                },
            )
        }
        compose.onNodeWithTag("mypage_login_id").assertTextContains("fixture-login")
        compose.onNodeWithTag("mypage_password").assertTextContains("fixture-password")
        coVerify(exactly = 0) { repository.mutate(any(), any()) }
    }

    @Test
    fun androidAutofillUpdatesBothFieldsWithoutSaving() {
        showEditor()
        fillFromAndroid(fieldIds())
    }

    @Test
    fun ticketRefreshDuringPickerKeepsOriginalAutofillTargets() {
        showEditor()
        val ids = fieldIds()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        token.value = "fixture-ticket-two"
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals("A WS ticket refresh must preserve the picker targets", ids, fieldIds())
        fillFromAndroid(ids)
    }

    @Test
    fun changingDashboardSessionDiscardsCredentialDrafts() {
        showEditor()
        fillFromAndroid(fieldIds())
        compose.runOnIdle {
            cookie = "fixture-other-dashboard-session"
            token.value = "fixture-ticket-two"
        }
        assertCredentialsEmpty()
    }

    @Test
    fun replacingBearerTokenDiscardsCredentialDrafts() {
        gated = false
        showEditor()
        fillFromAndroid(fieldIds())
        compose.runOnIdle { token.value = "fixture-other-bearer" }
        assertCredentialsEmpty()
    }

    @Test
    fun missingDashboardCookieKeepsTokenChangeBoundary() {
        cookie = null
        showEditor()
        fillFromAndroid(fieldIds())
        compose.runOnIdle { token.value = "fixture-other-ticket" }
        assertCredentialsEmpty()
    }

    @Test
    fun logoutDiscardsCredentialDraftsEvenIfCookieRemains() {
        showEditor()
        fillFromAndroid(fieldIds())
        compose.runOnIdle { token.value = null }
        assertCredentialsEmpty()
    }

    private fun assertCredentialsEmpty() {
        compose.waitForIdle()
        listOf("mypage_login_id", "mypage_password").forEach { tag ->
            compose.onNodeWithTag(tag).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
        }
    }
}
