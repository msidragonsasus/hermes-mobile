package com.m57.hermescontrol.career

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.NavigationController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class CareerCompanyScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun window() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.enableEdgeToEdge() }
        NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
    }

    @Composable
    private fun NarrowLargeFont(content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply { fontScale = 1.5f }
        val context = ContextThemeWrapper(LocalContext.current, 0).apply { applyOverrideConfiguration(configuration) }
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides Density(density.density, 1.5f),
        ) {
            Box(Modifier.width(360.dp)) { MaterialTheme { content() } }
        }
    }

    @Test
    fun companyCanBeAddedFromEmptyListWithoutConversation() {
        var submitted = ""
        compose.setContent {
            NarrowLargeFont {
                CareerCompanyContent(
                    state = CareerCompanyState(initialized = true, enabled = true, stale = false),
                    companyId = null,
                    onEnable = {},
                    onRefresh = {},
                    onCollect = {},
                    onAddCompany = { name, _ -> submitted = name },
                )
            }
        }
        compose.onNodeWithTag("company_add").performClick()
        compose.onNodeWithTag("company_add_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("company_add_name").performTextInput("  新しい企業  ")
        compose.onNodeWithTag("company_add_confirm").performClick()
        compose.runOnIdle { assertEquals("新しい企業", submitted) }
    }

    @Test
    fun deletionRequiresExplicitConfirmationInCompanyManagement() {
        var deleted = 0
        val detail =
            CareerCompanyDetail(
                company = CareerEntity("companies", "company", 7, buildJsonObject { put("name", "対象企業") }),
                thread_id = "company:company",
            )
        compose.setContent {
            NarrowLargeFont {
                CareerCompanyContent(
                    state = CareerCompanyState(initialized = true, enabled = true, stale = false, detail = detail),
                    companyId = "company",
                    onEnable = {},
                    onRefresh = {},
                    onCollect = {},
                    settingsOnly = true,
                    onDeleteCompany = { version, _ -> deleted = version },
                )
            }
        }
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasTestTag("company_delete"))
        compose.onNodeWithTag("company_delete").performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithText("キャンセル").performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithTag("company_delete").performClick()
        compose.onNodeWithTag("company_delete_confirm").performClick()
        compose.runOnIdle { assertEquals(7, deleted) }
    }

    @Test
    fun twentyCompaniesRemainSearchableWithLongNamesAndLargeFont() {
        val rows =
            (0..19).map {
                CareerCompanyRow(
                    "c$it",
                    "企業$it・長い名称のインフラエンジニアリング",
                    next_action = "エントリーシートの準備",
                    new_count =
                        if (it ==
                            19
                        ) {
                            1
                        } else {
                            0
                        },
                )
            }
        compose.setContent {
            NarrowLargeFont {
                CareerCompanyContent(
                    CareerCompanyState(
                        initialized = true,
                        enabled = true,
                        stale = false,
                        companies = CareerCompanies(companies = rows),
                    ),
                    null,
                    {
                    },
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("career_company_list").performScrollToNode(hasText(rows.last().name))
        compose.onNodeWithText(rows.last().name).assertIsDisplayed()
        compose.onNodeWithTag("career_company_list").performScrollToNode(hasText("企業を探す"))
        compose.onNodeWithText("企業を探す").performTextInput("企業19")
        compose.onNodeWithText(rows.last().name).assertIsDisplayed()
        compose.onNodeWithText(rows.first().name).assertDoesNotExist()
        screenshot("career-company-list-360-font150.png")
    }

    @Test
    fun selectedProgramIsCarriedToEsWorkspace() {
        val company = CareerEntity("companies", "company", 1, buildJsonObject { put("name", "日鉄エンジニアリング") })

        fun offer(
            id: String,
            title: String,
        ) = CareerEntity(
            "offers",
            id,
            1,
            buildJsonObject {
                put("company_id", company.id)
                put("title", title)
                put("deadline", "2026-09-30T23:59")
                put(
                    "questions",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", "q$id")
                                put("text", "学生時代に力を入れたこと")
                                put("max_characters", 400)
                            },
                        ),
                    ),
                )
            },
        )
        val detail =
            CareerCompanyDetail(
                company = company,
                offers = listOf(offer("a", "夏季プログラム"), offer("b", "冬季3daysプログラム")),
                thread_id = "company:company",
            )
        compose.setContent {
            NarrowLargeFont {
                CareerCompanyContent(
                    CareerCompanyState(
                        initialized = true,
                        enabled = true,
                        stale = false,
                        detail = detail,
                    ),
                    company.id,
                    {
                    },
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("company_tab_1").performScrollTo().performClick()
        compose.onNodeWithTag("company_page_1").performScrollToNode(hasTestTag("company_es_b"))
        screenshot("career-company-detail-360-font150.png")
        compose.onNodeWithTag("company_es_b").performClick()
        compose.runOnIdle {
            val destination = NavigationController.backStack?.last() as CareerEsKey
            assertEquals("b", destination.offerId)
            assertEquals(company.id, destination.companyId)
        }
    }

    @Test
    fun greetingHasNoFormalProposalOrWaitingBanner() {
        val snapshot =
            CareerCoachSnapshot(
                checkpoint =
                    buildJsonObject {
                        put("next_action", "今日の架空のタスク")
                    },
                runs =
                    listOf(
                        CareerCoachRun("waiting", "message", "waiting_user"),
                    ),
                messages = listOf(CareerCoachMessage("greeting", "waiting", "assistant", "こんにちは。")),
            )
        compose.setContent {
            MaterialTheme {
                CareerCoachPanel(
                    CareerCoachUiState(
                        initialized = true,
                        snapshot = snapshot,
                        stale = false,
                    ),
                    null,
                    CareerCoachActions(),
                    {
                    },
                )
            }
        }
        compose.onNodeWithText("こんにちは。").assertIsDisplayed()
        compose.onNodeWithText("今日の提案").assertDoesNotExist()
        compose.onNodeWithTag("career_run_waiting").assertDoesNotExist()
        compose.onNodeWithTag("career_coach_input").assertIsDisplayed()
    }

    @Test
    fun publicRecruitmentAndEsAreAvailableInOffersWithoutExposingCredentials() {
        val company = CareerEntity("companies", "company", 1, buildJsonObject { put("name", "日鉄エンジニアリング") })
        val offer =
            CareerEntity(
                "offers",
                "offer",
                1,
                buildJsonObject {
                    put("company_id", company.id)
                    put("title", "技術系本選考")
                    put("target_year", 2028)
                    put("deadline", "2026-10-01")
                    put("deadline_evidence", "応募締切 2026年10月1日")
                    put("source_url", "https://example.com/recruit")
                    put("is_current", true)
                },
            )
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(
                    CareerCompanyState(
                        initialized = true,
                        enabled = true,
                        detail =
                            CareerCompanyDetail(
                                company = company,
                                offers = listOf(offer),
                                thread_id = "company:company",
                                current_offer_count = 1,
                            ),
                    ),
                    company.id,
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("技術系本選考").assertDoesNotExist()
        compose.onNodeWithText("募集の締切 2026-10-01").assertDoesNotExist()
        compose.onNodeWithText("公式の募集ページを開く").assertDoesNotExist()
        compose.onNodeWithTag("company_tab_1").performScrollTo().performClick()
        compose.onNodeWithText("技術系本選考").assertIsDisplayed()
        compose.onNodeWithTag("company_page_1").performScrollToNode(hasTestTag("company_es_offer"))
        compose.onNodeWithTag("company_es_offer").assertIsDisplayed()
        compose.onNodeWithText("AXOLからES設問を取得").assertDoesNotExist()
        compose.onNodeWithText("マイページID").assertDoesNotExist()
        compose.onNodeWithText("パスワード").assertDoesNotExist()
    }

    @Test
    fun connectedWorkspaceOffersAutomaticAccountWideCollection() {
        var changed = false
        val reference = "gmail-" + "a".repeat(32)
        compose.setContent {
            MaterialTheme {
                CareerMailSettingsContent(
                    state =
                        CareerCompanyState(
                            initialized = true,
                            enabled = true,
                            stale = false,
                            companies = CareerCompanies(),
                            connections =
                                CareerConnections(
                                    accounts = listOf(reference),
                                    account_details =
                                        listOf(
                                            CareerWorkspaceAccount(
                                                id = reference,
                                                email = "jobhunt@example.com",
                                                provider = "hermes-google-workspace",
                                                read_surface = "gmail.readonly",
                                            ),
                                        ),
                                    independent_channels = true,
                                    mail_status = "ready",
                                    mail_senders =
                                        listOf(
                                            CareerMailSenderRule("sender", 1, "company", "recruit@example.com"),
                                        ),
                                ),
                        ),
                    progress = CareerMailProgress(buildJsonObject { put("version", 2) }),
                    onRefresh = {},
                    onCollect = {},
                    onAutomatic = { changed = it },
                    onNotifications = {},
                )
            }
        }
        compose.onNodeWithText("jobhunt@example.com").assertIsDisplayed()
        screenshot("career-gmail-connection-after-connected.png")
        compose.onNodeWithText("マイページID").assertDoesNotExist()
        compose.onNodeWithText("パスワード").assertDoesNotExist()
        compose.onNodeWithTag("mail_collection_switch").assertIsDisplayed().performClick()
        compose.onNodeWithText("全企業を更新").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(changed)
        }
    }

    @Test
    fun missingWorkspaceConnectionExplainsServerSetup() {
        compose.setContent {
            MaterialTheme {
                CareerMailSettingsContent(
                    state =
                        CareerCompanyState(
                            initialized = true,
                            enabled = true,
                            stale = false,
                            companies = CareerCompanies(),
                            connections =
                                CareerConnections(
                                    independent_channels = true,
                                    mail_status = "workspace_connection_unavailable",
                                ),
                        ),
                    progress = CareerMailProgress(buildJsonObject { put("version", 2) }),
                    onRefresh = {},
                    onCollect = {},
                    onAutomatic = {},
                    onNotifications = {},
                )
            }
        }
        screenshot("career-gmail-connection-after-missing.png")
        compose.onNodeWithText("サーバーのGoogle Workspace接続を確認してください。").assertIsDisplayed()
        compose.onNodeWithTag("workspace_google_connect").assertDoesNotExist()
        compose.onNodeWithTag("mail_collection_switch").assertIsNotEnabled()
        compose.onNodeWithTag("gmail_collect_now").assertIsNotEnabled()
    }

    @Test
    fun importedLoginIdsAreScopedInEncryptedLocalStorage() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scope = "synthetic-login-${UUID.randomUUID()}"
            val account = ShukatsuLoginAccount("login", "company", "企業", "i-web", "本選考", "local-id")
            CareerLocalStore(context, scope).saveImportedLoginAccounts(listOf(account))
            assertEquals(listOf(account), CareerLocalStore(context, scope).importedLoginAccounts())
            assertTrue(CareerLocalStore(context, "$scope-other").importedLoginAccounts().isEmpty())
        }

    @Test
    fun notificationTokenSurvivesReopenButCannotRouteAnotherProfile() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scope = "synthetic-notice-${UUID.randomUUID()}"
            val local = CareerLocalStore(context, scope)
            val notice =
                CareerNotice(
                    1,
                    "synthetic",
                    "synthetic-company",
                    kind = "source_received",
                    title = "synthetic",
                    created_at = "2026-09-09",
                )
            val token = local.notificationRoute(notice)
            assertEquals(token, local.notificationRoute(notice))
            assertEquals("synthetic-company", CareerLocalStore(context, scope).resolveNotificationRoute(token))
            assertNull(CareerLocalStore(context, "$scope-other").resolveNotificationRoute(token))
            assertNull(local.resolveNotificationRoute(UUID.randomUUID().toString()))
        }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(400)
        val directory =
            File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
        directory.mkdirs()
        File(directory, name).outputStream().use { output ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(
                Bitmap.CompressFormat.PNG,
                100,
                output,
            )
        }
    }
}
