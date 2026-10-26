package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.FillableData
import androidx.compose.ui.autofill.createFromText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.HermesControlTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class CareerMyPageSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = mockk<CareerCompanyRepository>()
    private val detail =
        CareerCompanyDetail(
            company = CareerEntity("companies", "fixture-ey", 1, buildJsonObject { put("name", "EY") }),
            thread_id = "company:fixture-ey",
            mypage = CareerMyPage(bot_available = true, portal_url = "https://portal.example.test/login"),
        )

    private val account = ShukatsuLoginAccount("account", "fixture-ey", "EY", "MyPage", "既存の名称", "saved-id")

    @Test
    fun oneAutofillLoginIsUsedForBothConnectionAndLocalDisplay() {
        val local = mockk<CareerLocalStore>(relaxed = true)
        coEvery { repository.mutate("mypage-settings", any()) } returns
            buildJsonObject {
                put("mypage", Json.encodeToJsonElement(detail.mypage!!.copy(connection_version = 1)))
            }
        compose.setContent {
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyLoginEditor(detail, listOf(account), {}, { _, _ -> }, local, repository)
                }
            }
        }
        compose.onNodeWithText("アカウント名").assertDoesNotExist()
        compose.onNodeWithTag("company_login_account").assertDoesNotExist()
        compose.onNodeWithText("ログインIDを保存").assertDoesNotExist()
        compose.onNodeWithText("ログインIDを追加").assertDoesNotExist()
        compose
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Username))
            .assertCountEquals(1)
        val loginBounds = compose.onNodeWithTag("mypage_login_id").getUnclippedBoundsInRoot()
        val passwordBounds = compose.onNodeWithTag("mypage_password").getUnclippedBoundsInRoot()
        assertTrue(passwordBounds.top - loginBounds.bottom in 0.dp..16.dp)
        compose
            .onNodeWithTag("mypage_login_id")
            .assertTextContains("saved-id")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Username))
            .performSemanticsAction(
                SemanticsActions.OnFillData,
            ) { it(requireNotNull(FillableData.createFromText("filled-id"))) }
        compose
            .onNodeWithTag("mypage_password")
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Password))
            .performSemanticsAction(SemanticsActions.OnFillData) {
                it(requireNotNull(FillableData.createFromText("filled-secret")))
            }
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) {
            repository.mutate(
                "mypage-settings",
                match {
                    it.text("login_id") == "filled-id" && it.text("password") == "filled-secret"
                },
            )
            local.saveCompanyLoginAccounts("fixture-ey", listOf(account.copy(username = "filled-id")))
        }
        compose.onNodeWithTag("mypage_login_id").assertTextContains("filled-id")
        compose
            .onNodeWithTag("mypage_password")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun failedSharedLoginSaveKeepsDraftAndDoesNotOverwriteLocalAccount() {
        val local = mockk<CareerLocalStore>(relaxed = true)
        coEvery { repository.mutate("mypage-settings", any()) } throws IOException()
        compose.setContent {
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyLoginEditor(detail, listOf(account), {}, { _, _ -> }, local, repository)
                }
            }
        }
        compose.onNodeWithTag("mypage_login_id").performTextReplacement("edited-id")
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-secret")
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mypage_login_id").assertTextContains("edited-id")
        compose.onNodeWithTag("mypage_password").assertTextContains("fixture-secret")
        coVerify(exactly = 0) { local.saveCompanyLoginAccounts(any(), any()) }
        compose.onNodeWithTag("mypage_save").performScrollTo().assertIsEnabled()
    }

    @Test
    fun choosingAnAccountPreservesOtherIdsAndRequiresAnExplicitSelection() {
        val local = mockk<CareerLocalStore>(relaxed = true)
        val second = account.copy(sourceId = "second", username = "second-id")
        coEvery { repository.mutate("mypage-settings", any()) } returns
            buildJsonObject {
                put("mypage", Json.encodeToJsonElement(detail.mypage!!.copy(connection_version = 1)))
            }
        compose.setContent {
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyLoginEditor(detail, listOf(account, second), {}, { _, _ -> }, local, repository)
                }
            }
        }
        compose.onNodeWithTag("mypage_login_id").assertIsNotEnabled()
        compose.onNodeWithTag("mypage_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("mypage_account_account").performScrollTo().performClick()
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-secret")
        compose.onNodeWithTag("mypage_account_second").performScrollTo().performClick()
        compose
            .onNodeWithTag("mypage_password")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .performScrollTo()
            .performTextReplacement("second-secret")
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) {
            repository.mutate("mypage-settings", match { it.text("login_id") == "second-id" })
            local.saveCompanyLoginAccounts("fixture-ey", listOf(account, second))
        }
    }

    @Test
    fun companyWithoutSavedLoginCanAutofillAndSaveBothFields() {
        val local = mockk<CareerLocalStore>(relaxed = true)
        coEvery { repository.mutate("mypage-settings", any()) } returns
            buildJsonObject {
                put("mypage", Json.encodeToJsonElement(detail.mypage!!.copy(connection_version = 1)))
            }
        compose.setContent {
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyLoginEditor(detail, emptyList(), {}, { _, _ -> }, local, repository)
                }
            }
        }
        compose
            .onNodeWithTag("mypage_password")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnFillData) {
                it(requireNotNull(FillableData.createFromText("new-secret")))
            }
        compose
            .onNodeWithTag("mypage_login_id")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnFillData) {
                it(requireNotNull(FillableData.createFromText("new-id")))
            }
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) {
            repository.mutate(
                "mypage-settings",
                match {
                    it.text("login_id") == "new-id" && it.text("password") == "new-secret"
                },
            )
            local.saveCompanyLoginAccounts(
                "fixture-ey",
                match {
                    it.size == 1 && it.single().username == "new-id" && it.single().companySourceId == "fixture-ey"
                },
            )
        }
    }

    @Test
    fun credentialsClearAfterSaveAndLargeTextCanReachButton() {
        coEvery { repository.mutate("mypage-settings", any()) } returns
            buildJsonObject {
                put(
                    "mypage",
                    Json.encodeToJsonElement(detail.mypage!!.copy(connection_version = 1, automation = "enabled")),
                )
            }
        compose.setContent {
            HermesControlTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        CompanyMyPageSettings(detail, {}, { _, _ -> }, repository)
                    }
                }
            }
        }
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("fixture-id")
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-secret")
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) {
            repository.mutate(
                "mypage-settings",
                match {
                    it.text("password") == "fixture-secret" &&
                        it.text("company_id") == "fixture-ey"
                },
            )
        }
        compose
            .onNodeWithTag(
                "mypage_password",
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("保存しました。「今すぐ確認」で確認を始められます。").performScrollTo().assertIsDisplayed()
        // The parent deliberately keeps its pre-save detail; the server acknowledgement
        // must enable checking immediately and supply the version for subsequent saves.
        // Saving is offered only again after a change, so edit the login ID first.
        compose.onNodeWithTag("mypage_save").assertDoesNotExist()
        compose.onNodeWithTag("mypage_check").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("fixture-id-2")
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) {
            repository.mutate(
                "mypage-settings",
                match { it.text("expected_version") == "1" && it["password"] == JsonPrimitive("") },
            )
        }
    }

    @Test
    fun passwordIsNotRestoredAfterRecreationAndFailureKeepsCurrentDraft() {
        coEvery { repository.mutate("mypage-settings", any()) } throws IOException()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesControlTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyMyPageSettings(detail, {}, { _, _ -> }, repository)
                }
            }
        }
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("fixture-id")
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-secret")
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mypage_password").assertTextContains("fixture-secret")
        restoration.emulateSavedInstanceStateRestore()
        compose
            .onNodeWithTag(
                "mypage_password",
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun changingSavedUrlRequiresBothCredentialsBeforeSendingRequest() {
        val savedDetail = detail.copy(mypage = detail.mypage!!.copy(connection_version = 2))
        compose.setContent {
            HermesControlTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyMyPageSettings(savedDetail, {}, { _, _ -> }, repository)
                }
            }
        }
        // A saved connection with no changes offers checking, not saving.
        compose.onNodeWithTag("mypage_save").assertDoesNotExist()
        compose.onNodeWithTag("mypage_check").performScrollTo().assertIsEnabled()
        compose
            .onNodeWithTag(
                "mypage_login_url",
            ).performScrollTo()
            .performTextReplacement("https://portal.example.test/new-login")
        compose.onNodeWithTag("mypage_credentials_hint", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithTag("mypage_save")
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("fixture-id")
        compose
            .onNodeWithTag("mypage_save")
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        coVerify(exactly = 0) { repository.mutate(any(), any()) }
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-secret")
        compose.onNodeWithTag("mypage_save").performScrollTo().assertIsEnabled()
    }

    @Test
    fun serverConflictDisplaysSpecificRecoveryAndKeepsCredentials() {
        coEvery { repository.mutate("mypage-settings", any()) } throws
            HttpException(
                Response.error<JsonObject>(
                    409,
                    """{"detail":{"code":"version_conflict","message":"private-response"}}""".toResponseBody(),
                ),
            )
        compose.setContent {
            HermesControlTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyMyPageSettings(detail, {}, { _, _ -> }, repository)
                }
            }
        }
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("fixture-id")
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-secret")
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        compose
            .onNodeWithText("接続設定が更新されています。「保存した設定を読み直す」で最新版を確認してから保存してください。")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("mypage_password").assertTextContains("fixture-secret")
        compose.onNodeWithText("private-response").assertDoesNotExist()
    }

    @Test
    fun acceptedSaveWithUnreadableReceiptDoesNotClaimRequestFailedOrKeepSecrets() {
        coEvery { repository.mutate("mypage-settings", any()) } returns JsonObject(emptyMap())
        compose.setContent {
            HermesControlTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyMyPageSettings(detail, {}, { _, _ -> }, repository)
                }
            }
        }
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("fixture-id")
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-secret")
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        compose
            .onNodeWithText("保存は受け付けましたが、表示を更新できません。「保存した設定を読み直す」を押してください。")
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithTag("mypage_password")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        coVerify(exactly = 1) { repository.mutate("mypage-settings", any()) }
    }
}
