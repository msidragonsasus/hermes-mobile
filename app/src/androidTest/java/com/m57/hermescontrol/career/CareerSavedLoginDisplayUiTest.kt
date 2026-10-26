package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.m57.hermescontrol.theme.HermesControlTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test

class CareerSavedLoginDisplayUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val local = mockk<CareerLocalStore>(relaxed = true)
    private val repository = mockk<CareerCompanyRepository>(relaxed = true)
    private val source = mockk<CareerBrowserCredentialSource>()
    private val detail = CareerCompanyDetail(
        company = CareerEntity("companies", "dummy-company", 1, buildJsonObject { put("name", "Fixture") }),
        thread_id = "fixture",
        mypage = CareerMyPage(
            bot_available = true, portal_url = "https://portal.example.test/login", connection_version = 1,
        ),
    )

    private fun show(accounts: List<ShukatsuLoginAccount> = emptyList()) {
        compose.setContent {
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyLoginEditor(
                        detail, accounts, {}, { _, _ -> }, local, repository, savedLoginSource = source,
                    )
                }
            }
        }
    }

    @Test fun serverOnlyIdIsShownPlainWithoutSavingOrRequestingPassword() {
        coEvery { source.savedLoginId(any(), any(), any()) } returns "dummy-server-id"
        show()
        compose.onNodeWithTag("mypage_login_id").performScrollTo().assertTextContains("dummy-server-id")
        compose.onNodeWithTag("mypage_password").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        coVerify(exactly = 0) { local.saveCompanyLoginAccounts(any(), any()) }
        coVerify(exactly = 0) { repository.mutate(any(), any()) }
    }

    @Test fun failedReadIsQuietWhenLocalIdIsShown() {
        coEvery { source.savedLoginId(any(), any(), any()) } returns null
        show(listOf(ShukatsuLoginAccount("local", "dummy-company", "Fixture", "MyPage", "", "dummy-local-id")))
        compose.onNodeWithTag("mypage_login_id").performScrollTo().assertTextContains("dummy-local-id")
        compose.onNodeWithTag("mypage_id_read_failed").assertDoesNotExist()
        coVerify(exactly = 0) { local.saveCompanyLoginAccounts(any(), any()) }
    }

    @Test fun failedReadWithoutLocalIdShowsRetry() {
        coEvery { source.savedLoginId(any(), any(), any()) } returnsMany listOf(null, "dummy-server-id")
        show()
        compose.onNodeWithTag("mypage_id_read_failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("IDを再読込").performScrollTo().performClick()
        compose.onNodeWithTag("mypage_login_id").performScrollTo().assertTextContains("dummy-server-id")
        coVerify(exactly = 0) { local.saveCompanyLoginAccounts(any(), any()) }
    }

    @Test fun delayedReadDoesNotReplaceUserInput() {
        val pending = CompletableDeferred<String?>()
        coEvery { source.savedLoginId(any(), any(), any()) } coAnswers { pending.await() }
        show()
        compose.onNodeWithTag("mypage_id_loading").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("user-edit")
        compose.runOnIdle { pending.complete("dummy-server-id") }
        compose.onNodeWithTag("mypage_login_id").assertTextContains("user-edit")
    }
    @Test fun delayedReadDoesNotClearTypedPassword() {
        val pending = CompletableDeferred<String?>()
        coEvery { source.savedLoginId(any(), any(), any()) } coAnswers { pending.await() }
        show()
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-pass")
        compose.runOnIdle { pending.complete("dummy-server-id") }
        compose.onNodeWithTag("mypage_password").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("fixture-pass")),
        )
    }

    @Test fun revertedPasswordEditDoesNotLeaveMissingSelectionDisabled() {
        val pending = CompletableDeferred<String?>()
        coEvery { source.savedLoginId(any(), any(), any()) } coAnswers { pending.await() }
        show()
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-pass")
        compose.onNodeWithTag("mypage_password").performTextReplacement("")
        compose.runOnIdle { pending.complete("dummy-server-id") }
        compose.onNodeWithTag("mypage_login_id").performScrollTo()
            .assertIsEnabled().assertTextContains("dummy-server-id")
    }

}
