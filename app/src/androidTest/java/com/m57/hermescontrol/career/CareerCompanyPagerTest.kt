package com.m57.hermescontrol.career

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class CareerCompanyPagerTest {
    private val accountId = "account-a-${java.util.UUID.randomUUID()}"

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun navigation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.enableEdgeToEdge() }
        NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
    }

    private fun fixture(
        failed: Boolean = false,
        empty: Boolean = false,
    ): CareerCompanyState {
        val status =
            buildJsonObject {
                put("state", if (failed) "failed" else "updated")
                if (!empty) put("last_success_at", "2026-09-14T11:00:00Z")
            }
        val notice =
            buildJsonObject {
                put("id", "evidence:one")
                put("source_key", "notice:one")
                put("title", "秋季プログラムのご案内")
                put("excerpt", "ログイン後に届いた募集の案内です。応募締切 2026年9月30日")
                put("locator", "https://example.com/mypage")
                put(
                    "deadlines",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("original_text", "応募締切 2026年9月30日")
                                put("date", "2026-09-30")
                                put("precision", "date")
                            },
                        ),
                    ),
                )
            }
        val company = CareerEntity("companies", "company-a", 1, buildJsonObject { put("name", "日鉄エンジニアリング") })
        return CareerCompanyState(
            initialized = true,
            enabled = true,
            stale = false,
            detail =
                CareerCompanyDetail(
                    company = company,
                    thread_id = "company:company-a",
                    mypage =
                        CareerMyPage(
                            status,
                            if (empty) emptyList() else listOf(notice),
                            supported = true,
                            coverage = "ログイン後のホームの案内",
                        ),
                    mail_status = status,
                    mail =
                        if (empty) {
                            emptyList()
                        } else {
                            listOf(
                                CareerMailItem(
                                    "mail1",
                                    "thread1",
                                    "hr@example.com",
                                    "採用担当",
                                    "面談日程について",
                                    "面談日程をご確認ください。",
                                    "面談は9月20日です。",
                                    "https://mail.google.com/",
                                    "2026-09-14T11:00:00Z",
                                    true,
                                    account_id = accountId,
                                ),
                            )
                        },
                ),
        )
    }

    private fun render(
        tab: Int = 0,
        state: CareerCompanyState = fixture(),
        width: Int = 390,
        font: Float = 1f,
        dark: Boolean = false,
        settingsOnly: Boolean = tab == 3,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                    Box(
                        Modifier.width(width.dp),
                    ) {
                        CareerCompanyPager(
                            state,
                            if (settingsOnly) 0 else tab,
                            {},
                            {},
                            {},
                            { _, _ -> },
                            { _, _, _ -> },
                            {},
                            settingsOnly = settingsOnly,
                        )
                    }
                }
            }
        }
    }

    @Test fun tapsSwipeAndTenChangesDoNotStartWork() {
        var operations = 0
        compose.setContent {
            HermesControlTheme {
                CareerCompanyPager(fixture(), 1, {
                    operations++
                }, { operations++ }, { operations++ }, { _, _ -> operations++ }, { _, _, _ -> operations++ }, {})
            }
        }
        compose.onNodeWithText("秋季プログラムのご案内").assertIsDisplayed()
        compose.onNodeWithText("面談日程について").assertDoesNotExist()
        compose.onNodeWithTag("company_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("company_tab_2").assertIsSelected()
        repeat(10) { compose.onNodeWithTag("company_tab_${it % 5}").performScrollTo().performClick() }
        compose.runOnIdle { assertEquals(0, operations) }
    }

    @Test fun noticesWithoutSourceKeysAndWithDuplicateIdsRemainScrollable() {
        val original = fixture()
        val detail = requireNotNull(original.detail)
        val mypage = requireNotNull(detail.mypage)
        val notices =
            (1..3).map { index ->
                buildJsonObject {
                    put("id", "same-id")
                    put("title", "案内 $index")
                    put("excerpt", "保存済みの案内 $index")
                }
            }
        render(tab = 1, state = original.copy(detail = detail.copy(mypage = mypage.copy(notices = notices))))
        notices.forEach { notice ->
            compose.onNodeWithTag("company_page_1").performScrollToNode(hasText(notice.text("title").orEmpty()))
            compose.onNodeWithText(notice.text("title").orEmpty()).assertIsDisplayed()
        }
    }

    @Test fun companyMailActionsAndSavedPaginationStayInMailTab() {
        val state = fixture().let { it.copy(detail = it.detail!!.copy(mail_next_cursor = "next")) }
        var fetched = 0
        var loaded = 0
        compose.setContent {
            HermesControlTheme {
                Box(Modifier.width(390.dp)) {
                    CareerCompanyPager(
                        state,
                        2,
                        {},
                        {},
                        {},
                        { _, _ -> },
                        { _, _, _ -> },
                        {},
                        mailActions = {
                            CareerCompanyMailButtons(
                                enabled = true,
                                busy = false,
                                onRefresh = { fetched++ },
                                onSettings = {},
                            )
                        },
                        onLoadSavedMail = { loaded++ },
                    )
                }
            }
        }
        compose.onNodeWithText("全企業を更新").performClick()
        screenshot("company-gmail-paged-390.png")
        compose.onNodeWithTag("company_page_2").performScrollToNode(hasText("保存済みメールをさらに表示"))
        compose.onNodeWithText("保存済みメールをさらに表示").performClick()
        compose.runOnIdle {
            assertEquals(1, fetched)
            assertEquals(1, loaded)
        }
    }

    @Test fun mailReferenceReachesExistingCompanyChat() {
        render(tab = 2)
        compose.onNodeWithText("面談日程について").performClick()
        compose.onNodeWithText("面談は9月20日です。").assertIsDisplayed()
        compose.onNodeWithText("この内容について相談").performClick()
        compose.runOnIdle {
            val route = NavigationController.backStack!!.last() as CareerThreadsKey
            assertEquals("company-a", route.companyId)
            assertEquals("company-a", route.target!!.id)
            assertEquals(CareerSourceRef("mail", "mail1", accountId), route.target!!.source_refs.single())
        }
    }

    @Test fun queryAndFilterSurviveTabChangesAndRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesControlTheme { CareerCompanyPager(fixture(), 2, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, {}) }
        }
        compose.onNodeWithText("メールを検索").performTextInput("面談")
        compose.onNodeWithText("未読のみ").performClick()
        compose.onNodeWithTag("company_tab_0").performScrollTo().performClick()
        compose.onNodeWithTag("company_tab_2").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("未読のみ").assertIsSelected()
        compose.onNodeWithText("面談").assertIsDisplayed()
        compose.onNodeWithText("面談日程について").assertIsDisplayed()
    }

    @Test fun failureWithCacheRetainsBodyAndSuccessTime() {
        render(tab = 2, state = fixture(failed = true))
        compose.onNodeWithText("メール を更新できませんでした。前回取得した情報を表示しています。").assertIsDisplayed()
        compose.onNodeWithText("面談日程について").assertIsDisplayed()
        screenshot("company-mail-failed-cache.png")
    }

    @Test fun failureWithoutCacheNeverSaysZero() {
        render(tab = 2, state = fixture(failed = true, empty = true))
        compose.onNodeWithText("メール を取得できませんでした。件数は不明です。").assertIsDisplayed()
        compose.onNodeWithText("0件", substring = true).assertDoesNotExist()
        screenshot("company-mail-failed-unknown.png")
    }

    @Test fun normalFiveTabs390() {
        render()
        assertTabsReachable()
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag("company_tab_label_0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        repeat(5) { tab ->
            compose.onNodeWithTag("company_tab_$tab").performScrollTo().performClick()
            compose.onNodeWithTag("company_page_$tab").assertIsDisplayed()
            screenshot("company-tab-$tab-390.png")
        }
    }

    @Test fun largeFont360DarkTabsRemainReachable() {
        render(width = 360, font = 2f, dark = true)
        assertTabsReachable()
        repeat(5) { tab ->
            compose.onNodeWithTag("company_tab_$tab").performScrollTo().performClick()
            compose.onNodeWithTag("company_page_$tab").assertIsDisplayed()
            screenshot("company-tab-$tab-360-font200-dark.png")
        }
    }

    @Test fun width430Font130() {
        render(width = 430, font = 1.3f)
        assertTabsReachable()
        repeat(5) { tab ->
            compose.onNodeWithTag("company_tab_$tab").performScrollTo().performClick()
            compose.onNodeWithTag("company_page_$tab").assertIsDisplayed()
            screenshot("company-tab-$tab-430-font130.png")
        }
    }

    @Test fun selectionPreferencesSurviveRestorationWithoutSaving() {
        var saves = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesControlTheme {
                CareerCompanyPager(
                    fixture(),
                    3,
                    {},
                    {},
                    {},
                    { _, _ -> },
                    { _, _, _ -> saves++ },
                    {},
                    settingsOnly = false,
                )
            }
        }
        compose.onNodeWithText("変更を保存").assertDoesNotExist()
        compose.onNodeWithText("志望度").performClick()
        compose.onNodeWithText("高", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("company_page_3").performScrollToNode(hasText("未保存の変更"))
        compose.onNodeWithText("未保存の変更").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("company_page_3").performScrollToNode(hasText("企業全体の志望度・進捗"))
        compose.onNodeWithText("高").assertIsDisplayed()
        compose.onNodeWithTag("company_page_3").performScrollToNode(hasText("変更を保存"))
        compose.onNodeWithText("変更を保存").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithText("元に戻す").performClick()
        compose.onNodeWithText("変更を保存").assertDoesNotExist()
    }

    @Test fun unsavedBackAsksBeforeDiscarding() {
        render(tab = 3, settingsOnly = false)
        compose.onNodeWithText("志望度").performClick()
        compose.onNodeWithText("高", useUnmergedTree = true).performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("未保存の変更があります").assertIsDisplayed()
        compose.onNodeWithText("編集を続ける").performClick()
        compose.onNodeWithText("高").assertIsDisplayed()
    }

    @Test fun inlineSenderEditorValidatesAndKeepsFailedInput() {
        var operations = 0
        compose.setContent {
            HermesControlTheme {
                CareerCompanyPager(
                    fixture().copy(senderWrite = CompanyWriteState(CompanyWritePhase.UNKNOWN, "保存結果を確認できません。")),
                    0,
                    {},
                    {},
                    {},
                    { _, _ -> operations++ },
                    { _, _, _ -> },
                    {},
                    settingsOnly = true,
                )
            }
        }
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasText("送信元メールアドレス"))
        compose.onNodeWithText("送信元メールアドレス").performTextInput("hr@")
        compose.onNodeWithText("送信元を追加").assertIsNotEnabled()
        compose.onNodeWithText("メールアドレスを確認してください。例：recruit@example.com").assertIsDisplayed()
        compose.onNodeWithText("送信元メールアドレス").performTextInput("example.com")
        compose
            .onNodeWithText("送信元を追加")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText("hr@example.com").assertIsDisplayed()
        compose.onNodeWithText("保存結果を確認できません。").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, operations) }
        screenshot("company-manage-senders.png")
    }

    @Test fun manageAuthenticationIsScopedAndTechnicalCodeIsHidden() {
        render(
            tab = 3,
            state = fixture().copy(connections = CareerConnections(mail_status = "authentication_required")),
        )
        screenshot("company-manage-top-390.png")
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasText("メール送信元"))
        compose.onNodeWithText("サーバーでの自動取得").assertDoesNotExist()
        compose.onNodeWithText("サーバーの自動取得にはGoogle認証の確認が必要です。").assertDoesNotExist()
        compose.onNodeWithText("メール送信元").assertIsDisplayed()
        compose.onNodeWithText("authentication_required", substring = true).assertDoesNotExist()
        screenshot("company-manage-mail-390.png")
        compose.onNodeWithText("履歴の詳細を見る").assertDoesNotExist()
        compose.onNodeWithText("技術詳細").assertDoesNotExist()
    }

    @Test fun selectionLargeFontRetainsPreferenceSave() {
        render(tab = 3, width = 360, font = 2f, dark = true, settingsOnly = false)
        compose.onNodeWithText("志望度").performClick()
        compose.onNodeWithText("高", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("company_page_3").performScrollToNode(hasText("変更を保存"))
        compose.onNodeWithText("変更を保存").assertIsDisplayed()
        screenshot("company-selection-edit-360-font200.png")
    }

    @Test fun companySettingsLargeFontReachesEveryConnectionAction() {
        render(tab = 3, width = 360, font = 2f, dark = true)
        compose.onNodeWithText("志望度").assertDoesNotExist()
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasText("Gmail接続設定"))
        compose.onNodeWithText("Gmail接続設定").assertIsDisplayed()
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasText("企業を削除"))
        compose.onNodeWithText("企業を削除").assertIsDisplayed()
    }

    @Test fun managementActionsKeepIdentityAndSenderScope() {
        var connections = 0
        val rule = CareerMailSenderRule("sender", 1, "company-a", "hr@example.com")
        val current =
            androidx.compose.runtime.mutableStateOf(
                fixture().let {
                    it.copy(
                        loginAccounts =
                            listOf(
                                ShukatsuLoginAccount("login", "company-a", "企業", "i-web", "本選考", "local-test-id"),
                            ),
                        detail =
                            it.detail!!.copy(
                                mail_senders = listOf(rule),
                                mypage = it.detail.mypage?.copy(bot_available = true, connection_version = 1),
                            ),
                    )
                },
            )
        val changes = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            HermesControlTheme {
                CareerCompanyPager(current.value, 0, {}, {}, {}, { address, enabled ->
                    changes.add(address to enabled)
                    current.value =
                        current.value.copy(
                            detail = current.value.detail!!.copy(mail_senders = listOf(rule.copy(enabled = enabled))),
                        )
                }, { _, _, _ -> }, { connections++ }, settingsOnly = true)
            }
        }
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasText("local-test-id"))
        compose.onNodeWithText("local-test-id").assertIsDisplayed()
        compose.onNodeWithText("ログインIDを隠す").assertDoesNotExist()
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasText("Gmail接続設定"))
        compose.onNodeWithText("Gmail接続設定").performClick()
        compose.runOnIdle { assertEquals(1, connections) }
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasText("送信元メールアドレス"))
        compose
            .onNodeWithContentDescription("hr@example.comからの取得")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithContentDescription("hr@example.comからの取得").assertIsOff()
        compose
            .onNodeWithContentDescription("hr@example.comからの取得")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithContentDescription("hr@example.comからの取得").assertIsOn()
        compose.runOnIdle { assertEquals(listOf(rule.address to false, rule.address to true), changes) }
    }

    @Test fun acknowledgedSenderClearsOnlyItsOwnInputAcrossRestoration() {
        val current = androidx.compose.runtime.mutableStateOf(fixture())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesControlTheme {
                CareerCompanyPager(current.value, 0, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, {}, settingsOnly = true)
            }
        }
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasText("送信元メールアドレス"))
        compose.onNodeWithText("送信元メールアドレス").performTextInput("hr@example.com")
        compose.runOnIdle {
            current.value =
                current.value.copy(
                    senderWrite =
                        CompanyWriteState(
                            CompanyWritePhase.SAVED,
                            address = "hr@example.com",
                            requestId = "first-save",
                        ),
                )
        }
        compose.onNodeWithText("hr@example.com").assertDoesNotExist()
        compose.onNodeWithText("送信元メールアドレス").performTextInput("hr@example.com")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("hr@example.com").assertIsDisplayed()
        compose.runOnIdle {
            current.value =
                current.value.copy(
                    senderWrite =
                        CompanyWriteState(
                            CompanyWritePhase.SAVED,
                            address = "hr@example.com",
                            requestId = "new-process-save",
                        ),
                )
        }
        compose.onNodeWithText("hr@example.com").assertDoesNotExist()
    }

    private fun assertTabsReachable() {
        repeat(5) { index ->
            compose.onNodeWithTag("company_tab_$index").performScrollTo().assertIsDisplayed()
            val tab = compose.onNodeWithTag("company_tab_$index").fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("Tabs retain a 48dp touch target", tab.height >= 48 * density - 1)
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "company-redesign")
        check(directory.isDirectory || directory.mkdirs()) {
            "Screenshot directory unavailable: ${directory.absolutePath}, uid=${android.os.Process.myUid()}"
        }
        File(directory, name).outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(
                Bitmap.CompressFormat.PNG,
                100,
                it,
            )
        }
    }
}
