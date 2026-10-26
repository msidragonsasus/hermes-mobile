package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.UUID

class CareerCompanySettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val company =
        CareerEntity(
            "companies",
            "settings-fixture",
            1,
            buildJsonObject {
                put("name", "企業名")
                put("notes", "元のメモ")
                put("retained_field", "保持")
            },
        )

    private class Repository(
        var entity: CareerEntity,
    ) : CareerRepository {
        var fail = false
        var sent: JsonObject? = null

        override suspend fun snapshot() = CareerSnapshot(1, listOf(entity))

        override suspend fun mutate(
            action: String,
            payload: JsonObject,
        ): JsonObject {
            sent = payload
            if (fail) throw IOException("offline")
            entity = entity.copy(version = entity.version + 1, data = payload["data"] as JsonObject)
            return JsonObject(emptyMap())
        }

        override suspend fun document(
            id: String,
            version: Int,
        ) = JsonObject(emptyMap())

        override suspend fun migrationPreview() = JsonObject(emptyMap())
    }

    private fun model(repository: Repository): CareerViewModel {
        val local = mockk<CareerLocalStore>(relaxed = true)
        coEvery { local.enabled() } returns true
        coEvery { local.pending() } returns null
        coEvery { local.cached() } returns CareerSnapshot(1, listOf(company))
        return CareerViewModel(repository, local)
    }

    @Test fun directEditorRetainsFailureAndRetriesSameOperation() {
        val repository = Repository(company).apply { fail = true }
        lateinit var model: CareerViewModel
        compose.runOnUiThread { model = model(repository) }
        compose.setContent {
            val state by model.state.collectAsState()
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyEntityEditor(company, state, model, "基本情報", {}, { _, _ -> })
                }
            }
        }
        compose.onNodeWithTag("company_edit_name").performTextReplacement("変更した企業")
        compose.onNodeWithTag("company_edit_save").performScrollTo().performClick()
        compose.waitUntil { model.state.value.pending != null && !model.state.value.busy }
        val operation = repository.sent!!.text("operation_id")
        compose.onNodeWithTag("company_edit_name").assertTextContains("変更した企業")
        repository.fail = false
        compose.onNodeWithText("保存結果を再確認").performScrollTo().performClick()
        compose.waitUntil { model.state.value.pending == null && !model.state.value.busy }
        compose.onNodeWithText("保存しました。").performScrollTo().assertIsDisplayed()
        assertEquals(operation, repository.sent!!.text("operation_id"))
        assertEquals("変更した企業", repository.entity.data.text("name"))
        assertEquals("保持", repository.entity.data.text("retained_field"))
    }

    @Test fun mypageUrlEditorHidesLabelAndPreservesItWhenSaving() {
        val link =
            CareerEntity(
                "company_links",
                "link",
                3,
                buildJsonObject {
                    put("company_id", company.id)
                    put("type", "MyPage")
                    put("label", "既存の登録先")
                    put("url", "https://example.com/old")
                },
            )
        val repository = Repository(link)
        lateinit var model: CareerViewModel
        compose.runOnUiThread { model = model(repository) }
        compose.setContent {
            val state by model.state.collectAsState()
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyEntityEditor(link, state, model, "マイページURL", {}, { _, _ -> })
                }
            }
        }
        compose.onNodeWithTag("company_edit_label").assertDoesNotExist()
        compose.onNodeWithText("登録先の名称").assertDoesNotExist()
        compose.onNodeWithTag("company_edit_url").performTextReplacement("https://example.com/new")
        compose.onNodeWithTag("company_edit_save").performScrollTo().performClick()
        compose.waitUntil { repository.sent != null && !model.state.value.busy }
        assertEquals("https://example.com/new", repository.entity.data.text("url"))
        assertEquals("既存の登録先", repository.entity.data.text("label"))
        assertEquals(company.id, repository.entity.data.text("company_id"))
    }

    @Test fun concurrentUpdateRetainsInputAndRefreshesUntouchedFields() {
        val repository = Repository(company)
        lateinit var model: CareerViewModel
        compose.runOnUiThread { model = model(repository) }
        compose.setContent {
            val state by model.state.collectAsState()
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CompanyEntityEditor(company, state, model, "基本情報", {}, { _, _ -> })
                }
            }
        }
        compose.onNodeWithTag("company_edit_name").performTextReplacement("編集中の企業")
        compose.runOnIdle {
            repository.entity =
                company.copy(
                    version = 2,
                    data =
                        buildJsonObject {
                            put("name", "別端末の企業名")
                            put("notes", "別端末のメモ")
                            put("retained_field", "最新版")
                        },
                )
            model.refresh()
        }
        compose.waitUntil {
            model.state.value.snapshot
                ?.entities
                ?.first()
                ?.version == 2
        }
        compose.onNodeWithTag("company_edit_name").assertTextContains("編集中の企業")
        compose.onNodeWithText("入力を保持して最新版を基準にする").performScrollTo().performClick()
        compose.onNodeWithTag("company_edit_save").performScrollTo().performClick()
        compose.waitUntil { repository.sent != null && !model.state.value.busy }
        assertEquals("2", repository.sent!!.text("expected_version"))
        assertEquals("編集中の企業", repository.entity.data.text("name"))
        assertEquals("別端末のメモ", repository.entity.data.text("notes"))
        assertEquals("最新版", repository.entity.data.text("retained_field"))
    }

    @Test fun directEditorDraftSurvivesRestorationAt360AndLargeDarkFont() {
        val repository = Repository(company)
        lateinit var model: CareerViewModel
        compose.runOnUiThread { model = model(repository) }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val state by model.state.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                HermesControlTheme(themePreference = ThemePreference.DARK) {
                    Box(Modifier.width(360.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            CompanyEntityEditor(company, state, model, "基本情報", {}, { _, _ -> })
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("company_edit_name").performTextReplacement("保持する入力")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("company_edit_name").assertTextContains("保持する入力")
        compose.onNodeWithTag("company_edit_save").performScrollTo().assertIsDisplayed()
    }

    @Test fun mypageActionIsLargeAndLoginIdIsVisibleWithoutReveal() {
        val link =
            CareerEntity(
                "company_links",
                "link",
                1,
                buildJsonObject {
                    put("type", "MyPage")
                    put("label", "2028卒")
                    put("url", "https://example.com/login")
                },
            )
        compose.setContent {
            HermesControlTheme {
                Box(Modifier.width(360.dp)) {
                    CompanyPortalActions(
                        CareerCompanyDetail(company = company, thread_id = "t", links = listOf(link)),
                        listOf(ShukatsuLoginAccount("a", company.id, company.title, "MyPage", "応募用", "ID-2028")),
                        {},
                    )
                }
            }
        }
        compose
            .onNodeWithTag("company_open_mypage")
            .assertHeightIsAtLeast(64.dp)
        compose.onNodeWithText("ID-2028").assertIsDisplayed()
        compose.onNodeWithText("IDをコピー").performClick()
        compose.onNodeWithText("ログインIDを隠す").assertDoesNotExist()
    }

    @Test fun loginStorageRetainsLegacyAccountsAndSupportsNewCompaniesAndDeletion() =
        runBlocking {
            val scope = "settings-test-${UUID.randomUUID()}"
            val local = CareerLocalStore(compose.activity, scope)
            val legacy = ShukatsuLoginAccount("a", "old", "企業", "MyPage", "応募用", "legacy-id")
            local.saveImportedLoginAccounts(listOf(legacy))
            assertEquals(listOf(legacy), local.companyLoginAccounts("shukatsu:Company:old"))
            val updated = legacy.copy(username = "updated-id")
            local.saveCompanyLoginAccounts("shukatsu:Company:old", listOf(updated))
            local.saveCompanyLoginAccounts("company:new", listOf(updated.copy(companySourceId = "company:new")))
            val reopened = CareerLocalStore(compose.activity, scope)
            assertEquals("updated-id", reopened.companyLoginAccounts("company:new").single().username)
            assertEquals("updated-id", reopened.companyLoginAccounts("shukatsu:Company:old").single().username)
            reopened.saveCompanyLoginAccounts("shukatsu:Company:old", emptyList())
            assertTrue(CareerLocalStore(compose.activity, scope).companyLoginAccounts("shukatsu:Company:old").isEmpty())
            assertEquals(listOf(legacy), reopened.importedLoginAccounts())
            assertTrue(
                CareerLocalStore(compose.activity, scope + "-other").companyLoginAccounts("company:new").isEmpty(),
            )
        }

    @Test fun renderedMailBodyPersistsReadWithoutChangingGmailUnread() {
        val message = UUID.randomUUID().toString()
        val mail =
            CareerMailItem(
                message,
                "thread",
                "sender@example.com",
                "採用担当",
                "選考のご案内",
                "本文",
                "本文と https://example.com/info",
                "https://mail.google.com",
                "2026-09-20T01:00:00Z",
                true,
                account_id = "fixture",
            )
        compose.setContent { HermesControlTheme { CareerMailBodySheet(mail, {}, {}) } }
        compose.onNodeWithTag("career_mail_body").assertIsDisplayed()
        val app = compose.activity.application as HermesControlApp
        compose.waitUntil {
            runBlocking {
                app.homeRepository.dao.reads(AuthManager.uiPreferenceScope()).first().any {
                    it.key ==
                        "career:${mail.readIdentity}"
                }
            }
        }
        assertTrue(mail.gmail_unread)
        val keys =
            runBlocking {
                app.homeRepository.dao
                    .reads(AuthManager.uiPreferenceScope())
                    .first()
                    .map { it.key }
                    .toSet()
            }
        assertFalse(mail.isUnread(keys))
    }
}
