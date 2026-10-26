package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class CareerMyPageSettingsNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = mockk<CareerCompanyRepository>()
    private val dirty = mutableMapOf<String, Boolean>()
    private val detail =
        CareerCompanyDetail(
            company = CareerEntity("companies", "fixture-company", 1, buildJsonObject { put("name", "企業") }),
            thread_id = "company:fixture-company",
            mypage =
                CareerMyPage(
                    bot_available = true,
                    portal_url = "https://portal.example.test/login",
                    connection_version = 1,
                ),
        )

    private fun show(
        sharedLogin: String? = null,
        onCredentialsSaved: suspend () -> Unit = {},
        onBusyChanged: (Boolean) -> Unit = {},
        onShowResults: () -> Unit = {},
    ) {
        compose.setContent {
            HermesControlTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyMyPageSettings(
                        detail = detail,
                        onRefresh = {},
                        onDirty = { section, value -> dirty[section] = value },
                        repository = repository,
                        sharedLogin = sharedLogin,
                        onBusyChanged = onBusyChanged,
                        onCredentialsSaved = onCredentialsSaved,
                        onShowResults = onShowResults,
                    )
                }
            }
        }
    }

    @Test
    fun credentialSaveBlocksOnlyUntilAcknowledgedEvenWhenParentDoesNotRefresh() {
        val receipt = CompletableDeferred<JsonObject>()
        coEvery { repository.mutate("mypage-settings", any()) } coAnswers { receipt.await() }
        show()
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("fixture-id")
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-password")
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(dirty["mypage-bot"] == true)
            assertTrue(dirty["mypage-saving"] == true)
        }
        compose.onNodeWithTag("mypage_show_results").performScrollTo().assertIsNotEnabled()
        receipt.complete(
            buildJsonObject {
                put("mypage", Json.encodeToJsonElement(detail.mypage!!.copy(connection_version = 2)))
            },
        )
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(dirty.values.any { it }) }
        compose.onNodeWithTag("mypage_check").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("mypage_show_results").performScrollTo().assertIsEnabled()
    }

    @Test
    fun checkReceiptReleasesControlsAndResultsRemainReachableWithoutPolling() {
        val receipt = CompletableDeferred<JsonObject>()
        coEvery { repository.mutate("mypage-check", any()) } coAnswers { receipt.await() }
        var openedResults = false
        var managementBusy = false
        show(onBusyChanged = { managementBusy = it }, onShowResults = { openedResults = true })
        compose.onNodeWithTag("mypage_check").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(dirty.values.any { it })
            assertFalse(managementBusy)
        }
        compose.onNodeWithText("確認開始をサーバーに依頼しています。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mypage_show_results").performScrollTo().assertIsEnabled()
        receipt.complete(
            buildJsonObject {
                put("run_id", "fixture-run")
                put("state", "queued")
            },
        )
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(dirty.values.any { it })
            assertFalse(managementBusy)
        }
        compose.onNodeWithTag("mypage_check").performScrollTo().assertIsEnabled()
        compose
            .onNodeWithText("サーバーで確認を受け付けました。この画面を離れても続行します。取得結果から進捗と案内を確認できます。")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("mypage_show_results").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(openedResults) }
        coVerify(exactly = 0) { repository.company(any()) }
    }

    @Test
    fun restoringOriginalUrlClearsUnsavedFlagWithoutSavingOrChecking() {
        show()
        compose
            .onNodeWithTag("mypage_login_url")
            .performScrollTo()
            .performTextReplacement("https://portal.example.test/changed")
        compose.runOnIdle { assertTrue(dirty["mypage-bot"] == true) }
        compose.onNodeWithTag("mypage_login_url").performTextReplacement(detail.mypage!!.portal_url!!)
        compose.runOnIdle { assertFalse(dirty.values.any { it }) }
        compose.onNodeWithTag("mypage_check").performScrollTo().assertIsEnabled()
        coVerify(exactly = 0) { repository.mutate(any(), any()) }
    }

    @Test
    fun restoredLoginDraftStillOffersSaveAndClearsUnsavedAfterAcknowledgement() {
        val restoration = StateRestorationTester(compose)
        val local = mockk<CareerLocalStore>(relaxed = true)
        val source = mockk<CareerBrowserCredentialSource>()
        coEvery { source.savedLoginId(any(), any(), any()) } returns null
        coEvery { repository.mutate("mypage-settings", any()) } returns
            buildJsonObject {
                put("mypage", Json.encodeToJsonElement(detail.mypage!!.copy(connection_version = 2)))
            }
        restoration.setContent {
            HermesControlTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyLoginEditor(
                        detail,
                        listOf(
                            ShukatsuLoginAccount("fixture-account", "fixture-company", "企業", "MyPage", "", "saved-id"),
                        ),
                        {},
                        { section, value -> dirty[section] = value },
                        local,
                        repository,
                        savedLoginSource = source,
                    )
                }
            }
        }
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("edited-id")
        compose.onNodeWithTag("mypage_save").performScrollTo().assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("mypage_login_id").performScrollTo().assertTextContains("edited-id")
        compose.runOnIdle { assertTrue(dirty["logins"] == true) }
        compose
            .onNodeWithTag("mypage_save")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(dirty.values.any { it }) }
        compose.onNodeWithTag("mypage_check").performScrollTo().assertIsEnabled()
        coVerify(exactly = 1) {
            repository.mutate("mypage-settings", match { it.text("login_id") == "edited-id" })
            local.saveCompanyLoginAccounts("fixture-company", match { it.single().username == "edited-id" })
        }
    }

    @Test
    fun restoredUrlDraftKeepsSaveReachableWithoutRestoringPassword() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesControlTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyMyPageSettings(detail, {}, { section, value -> dirty[section] = value }, repository)
                }
            }
        }
        compose
            .onNodeWithTag("mypage_login_url")
            .performScrollTo()
            .performTextReplacement("https://portal.example.test/changed")
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("fixture-id")
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-password")
        restoration.emulateSavedInstanceStateRestore()
        compose
            .onNodeWithTag("mypage_login_url")
            .performScrollTo()
            .assertTextContains("https://portal.example.test/changed")
        compose.onNodeWithTag("mypage_login_id").assertTextContains("fixture-id")
        compose.onNodeWithTag("mypage_password").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.runOnIdle { assertTrue(dirty["mypage-bot"] == true) }
        compose.onNodeWithTag("mypage_save").performScrollTo().assertIsNotEnabled()
        coVerify(exactly = 0) { repository.mutate(any(), any()) }
    }

    @Test
    fun saveFailureReleasesWriteGuardButKeepsCredentialDraftUnsaved() {
        coEvery { repository.mutate("mypage-settings", any()) } throws IOException()
        show()
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("fixture-id")
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-password")
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(dirty["mypage-bot"] == true)
            assertFalse(dirty["mypage-saving"] == true)
        }
        compose.onNodeWithTag("mypage_password").assertTextContains("fixture-password")
        compose.onNodeWithTag("mypage_save").performScrollTo().assertIsEnabled()
    }

    @Test
    fun failedLocalIdSaveRetriesLocallyWithoutResendingServerCredentials() {
        coEvery { repository.mutate("mypage-settings", any()) } returns
            buildJsonObject {
                put("mypage", Json.encodeToJsonElement(detail.mypage!!.copy(connection_version = 2)))
            }
        var managementBusy = false
        var localSaveAttempts = 0
        show(
            sharedLogin = "fixture-id",
            onCredentialsSaved = {
                localSaveAttempts++
                if (localSaveAttempts == 1) throw IOException()
            },
            onBusyChanged = { managementBusy = it },
        )
        compose.onNodeWithTag("mypage_password").performScrollTo().performTextReplacement("fixture-password")
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(dirty.values.any { it })
            assertFalse(managementBusy)
        }
        compose
            .onNodeWithText("接続設定は保存しました。端末へのID保存に失敗しました。「端末にIDを保存」で再試行できます。")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("mypage_check").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("mypage_retry_local_login").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("ログインIDを端末に保存しました。").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(localSaveAttempts == 2)
            assertFalse(dirty.values.any { it })
            assertFalse(managementBusy)
        }
        coVerify(exactly = 1) { repository.mutate("mypage-settings", any()) }
    }

    @Test
    fun lateSavedAccountReadDoesNotMarkTheSavedLoginUnsaved() {
        val sharedLogin = mutableStateOf("")
        val savedAccountRead = mutableStateOf("[]")
        compose.setContent {
            HermesControlTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyMyPageSettings(
                        detail = detail,
                        onRefresh = {},
                        onDirty = { section, value -> dirty[section] = value },
                        repository = repository,
                        sharedLogin = sharedLogin.value,
                        sharedLoginReadKey = savedAccountRead.value,
                    )
                }
            }
        }
        compose.runOnIdle {
            sharedLogin.value = "fixture-saved-id"
            savedAccountRead.value = "saved-account-read"
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(dirty.values.any { it }) }
        compose.onNodeWithTag("mypage_login_id").performScrollTo().assertTextContains("fixture-saved-id")
        compose.onNodeWithTag("mypage_check").performScrollTo().assertIsEnabled()
        coVerify(exactly = 0) { repository.mutate(any(), any()) }
    }

    @Test
    fun typedLoginAndManualAccountSelectionStayUnsavedUntilAcknowledged() {
        val sharedLogin = mutableStateOf("fixture-first-id")
        val accountKey = mutableStateOf("fixture-first-account")
        coEvery { repository.mutate("mypage-settings", any()) } returns
            buildJsonObject {
                put("mypage", Json.encodeToJsonElement(detail.mypage!!.copy(connection_version = 2)))
            }
        compose.setContent {
            HermesControlTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyMyPageSettings(
                        detail = detail,
                        onRefresh = {},
                        onDirty = { section, value -> dirty[section] = value },
                        repository = repository,
                        sharedLogin = sharedLogin.value,
                        sharedAccountKey = accountKey.value,
                        onSharedLoginChange = { sharedLogin.value = it },
                        sharedLoginReadKey = "same-saved-accounts",
                    )
                }
            }
        }
        compose.onNodeWithTag("mypage_login_id").performScrollTo().performTextReplacement("fixture-typed-id")
        compose.runOnIdle { assertTrue(dirty["mypage-bot"] == true) }
        compose.onNodeWithTag("mypage_login_id").performTextReplacement("fixture-first-id")
        compose.runOnIdle { assertFalse(dirty.values.any { it }) }
        // Choosing an already-saved account changes selection, not the saved-account read revision.
        compose.runOnIdle {
            sharedLogin.value = "fixture-second-id"
            accountKey.value = "fixture-second-account"
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(dirty["mypage-bot"] == true) }
        compose.onNodeWithTag("mypage_save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(dirty.values.any { it }) }
        compose.onNodeWithTag("mypage_check").performScrollTo().assertIsEnabled()
        coVerify(exactly = 1) {
            repository.mutate("mypage-settings", match { it.text("login_id") == "fixture-second-id" })
        }
    }
}
