package com.m57.hermescontrol.career

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class CareerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun homeDeadlineOpensTheExactRecordWithoutCompletingIt() {
        var writes = 0
        val task =
            CareerEntity(
                "tasks",
                "home-task",
                3,
                buildJsonObject {
                    put("title", "ホームから開いた提出期限")
                    put("status", "todo")
                    put("deadline", "2026-09-18")
                },
            )
        compose.setContent {
            MaterialTheme {
                CareerContent(
                    CareerUiState(
                        initialized = true,
                        enabled = true,
                        snapshot = CareerSnapshot(1, listOf(task)),
                        stale = false,
                    ),
                    {},
                    {},
                    { _, _ -> writes++ },
                    {},
                    {},
                    initialEntityId = task.id,
                )
            }
        }
        compose.onNodeWithText("ホームから開いた提出期限").assertIsDisplayed()
        compose.onNodeWithText("編集").performClick()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Before
    fun matchProductionWindow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply { fontScale = 2f }
        val context = ContextThemeWrapper(LocalContext.current, 0).apply { applyOverrideConfiguration(configuration) }
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides Density(density.density, 2f),
            content = content,
        )
    }

    @Test
    fun settingsRemainScrollableWithLargeFontAndDoNotShowAnEmptyRecordList() {
        var disabled = false
        compose.setContent {
            LargeFont {
                MaterialTheme {
                    CareerContent(
                        CareerUiState(initialized = true, enabled = true, snapshot = CareerSnapshot(1)),
                        { disabled = !it },
                        {},
                        { _, _ -> },
                        {},
                        {},
                        initialTab = "記録", // A tab retained by the old mixed settings screen must not win.
                        settingsOnly = true,
                    )
                }
            }
        }
        compose.onNodeWithText("就活の設定").assertIsDisplayed()
        compose.onNodeWithText("相談").assertDoesNotExist()
        compose.onNodeWithText("記録").assertDoesNotExist()
        compose.onNodeWithText("設定").assertDoesNotExist()
        compose.onNodeWithText("まだ項目がありません").assertDoesNotExist()
        compose.onNodeWithTag("career_settings").performScrollToNode(hasText("就活Appを無効にする"))
        compose.onNodeWithText("就活Appを無効にする").performClick()
        compose.runOnIdle { assertEquals(false, disabled) }
        compose.onNodeWithText("戻る").performClick()
        compose.onNodeWithTag("career_settings").performScrollToNode(hasText("データの同期"))
        compose.onNodeWithText("データの同期").assertIsDisplayed()
        compose.onNodeWithText("データの取り込み").assertDoesNotExist()
        compose.onNodeWithText("ファイルを取り込む").assertDoesNotExist()
        screenshot("settings-font200.png")
    }

    @Test
    fun enableExplainsStorageBeforeAnyRequest() {
        var enabled = false
        var writes = 0
        compose.setContent {
            MaterialTheme {
                CareerContent(CareerUiState(initialized = true), { enabled = it }, {}, { _, _ -> writes++ }, {}, {})
            }
        }
        compose.onNodeWithTag("career_enable").performClick()
        compose.runOnIdle {
            assertEquals(true, enabled)
            assertEquals(0, writes)
        }
    }

    @Test
    fun offerOnlyCreatesApplicationAfterExplicitChoice() {
        val offer =
            CareerEntity(
                "offers",
                "test-offer",
                1,
                buildJsonObject {
                    put("title", "テスト募集")
                    put("target_year", 2027)
                },
            )
        val snapshot = CareerSnapshot(1, entities = listOf(offer))
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CareerContent(
                    CareerUiState(
                        initialized = true,
                        enabled = true,
                        snapshot = snapshot,
                    ),
                    {},
                    {},
                    { action, _ ->
                        actions +=
                            action
                    },
                    {},
                    {},
                    initialEntityId = offer.id,
                )
            }
        }
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
        compose.onNodeWithText("応募予定へ追加").performClick()
        compose.runOnIdle { assertEquals(listOf("decide"), actions) }
    }

    @Test
    fun editingShowsDiffAndDoesNotWriteUntilSave() {
        val entity = CareerEntity("companies", "sample", 2, buildJsonObject { put("name", "変更前の企業") })
        val writes = mutableListOf<JsonObject>()
        compose.setContent {
            MaterialTheme {
                CareerEntityDialog(entity, CareerSnapshot(1), true, {}, { _, payload -> writes += payload })
            }
        }
        compose.onNodeWithText("編集").performClick()
        compose.onNodeWithText("名称").performTextReplacement("変更後の企業")
        compose.onNodeWithText("差分を確認").performClick()
        compose.onNodeWithText("変更前: 変更前の企業").assertIsDisplayed()
        compose.onNodeWithText("変更後: 変更後の企業").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, writes.size) }
        compose.onNodeWithText("確認して保存").performClick()
        compose.runOnIdle { assertEquals(1, writes.size) }
    }

    @Test
    fun largeFontEmptyScreenRemainsNavigable() {
        compose.setContent {
            LargeFont {
                MaterialTheme {
                    CareerContent(CareerUiState(initialized = true, enabled = true, snapshot = CareerSnapshot(1)), {
                    }, {}, { _, _ -> }, {}, {})
                }
            }
        }
        compose.onNodeWithTag("career_coach_conversation").performScrollToNode(hasText("就活コーチに話しかける"))
        compose.onNodeWithText("就活コーチに話しかける").assertIsDisplayed()
        compose.onNodeWithTag("career_coach_input").assertIsDisplayed()
    }

    @Test
    fun coachHomeSendsRequestInsideApp() {
        val sent = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CareerContent(
                    CareerUiState(initialized = true, enabled = true, snapshot = CareerSnapshot(1)),
                    {},
                    {},
                    { _, _ -> },
                    {},
                    {},
                    CareerCoachUiState(
                        initialized = true,
                        draft = "ESを一緒に仕上げたい",
                        snapshot = CareerCoachSnapshot(),
                        capabilitiesChecked = true,
                        execution = CareerExecution("openai-codex", "test-model", "medium"),
                        capabilities =
                            CareerCapabilities(
                                state = "available",
                                features = listOf("execution_config"),
                                models =
                                    listOf(
                                        CareerModelOption(
                                            "openai-codex",
                                            "test-model",
                                            reasoning_efforts = listOf("medium"),
                                        ),
                                    ),
                            ),
                    ),
                    CareerCoachActions(send = { sent += it }),
                )
            }
        }
        compose.onNodeWithText("今日の提案").assertDoesNotExist()
        compose.onNodeWithTag("career_coach_send").performClick()
        compose.runOnIdle { assertEquals(listOf("ESを一緒に仕上げたい"), sent) }
        screenshot("career-coach-home.png")
    }

    @Test
    fun savedArtifactOpensAndReturnsToSameCoachForRevision() {
        val document =
            CareerEntity(
                "documents",
                "DOC-001",
                1,
                buildJsonObject {
                    put("title", "自己PRの下書き")
                    put("body", "これは保存した本文です。")
                    put("status", "drafting")
                },
            )
        var target: CareerEntity? = null
        val result =
            buildJsonObject {
                put(
                    "artifact_ids",
                    kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("DOC-001"))),
                )
            }
        compose.setContent {
            MaterialTheme {
                CareerContent(
                    CareerUiState(
                        initialized = true,
                        enabled = true,
                        snapshot = CareerSnapshot(1, entities = listOf(document)),
                    ),
                    {},
                    {},
                    { _, _ -> },
                    {},
                    {},
                    CareerCoachUiState(
                        initialized = true,
                        snapshot =
                            CareerCoachSnapshot(
                                messages = listOf(CareerCoachMessage("reply", "run-1", "assistant", "下書きを保存しました。")),
                                runs = listOf(CareerCoachRun("run-1", "message", "completed", result)),
                            ),
                    ),
                    CareerCoachActions(consult = { target = it }),
                )
            }
        }
        compose.onNodeWithTag("career_coach_conversation").performScrollToNode(hasText("成果物を開く: 自己PRの下書き"))
        compose.onNodeWithTag("career_artifact_DOC-001").performClick()
        compose.onNodeWithText("これは保存した本文です。").assertIsDisplayed()
        screenshot("career-coach-artifact.png")
        compose.onNodeWithText("この文書について相談").performClick()
        compose.onNodeWithTag("career_coach_input").assertIsDisplayed()
        compose.runOnIdle { assertEquals(document, target) }
    }

    @Test
    fun modelAndReasoningSelectionUsePublishedOptions() {
        val chosen = mutableListOf<CareerExecution>()
        val capabilities =
            CareerCapabilities(
                state = "available",
                features = listOf("execution_config"),
                models =
                    listOf(
                        CareerModelOption("openai-codex", "test-model", reasoning_efforts = listOf("low", "high")),
                    ),
            )
        compose.setContent {
            MaterialTheme {
                CareerExecutionControls(null, capabilities, true, chosen::add, {})
            }
        }
        compose.onNodeWithTag("career_会話_model").performClick()
        compose.onNodeWithText("test-model").performClick()
        compose.onNodeWithTag("career_effort_high").performClick()
        compose.runOnIdle { assertEquals(listOf(CareerExecution("openai-codex", "test-model", "high")), chosen) }
    }

    @Test
    fun automaticResearchCanSelectAnotherProviderWithoutChangingConversationModel() {
        val chosen = mutableListOf<CareerExecution>()
        var conversationChanged = false
        val capabilities =
            CareerCapabilities(
                state = "available",
                features = listOf("execution_config", "daily_settings"),
                models = listOf(CareerModelOption("anthropic", "test-claude", reasoning_efforts = listOf("default"))),
            )
        compose.setContent {
            MaterialTheme {
                CareerContent(
                    CareerUiState(initialized = true, enabled = true, snapshot = CareerSnapshot(1)),
                    {},
                    {},
                    { _, _ -> },
                    {},
                    {},
                    coachState = CareerCoachUiState(capabilities = capabilities, capabilitiesChecked = true),
                    coachActions =
                        CareerCoachActions(
                            saveDailyExecution = chosen::add,
                            selectExecution = { conversationChanged = true },
                        ),
                    initialTab = "設定",
                    settingsOnly = true,
                )
            }
        }
        compose.onNodeWithTag("career_settings").performScrollToNode(hasText("自動調査の設定"))
        compose.onNodeWithTag("career_自動調査_model").performClick()
        compose.onNodeWithText("anthropic").assertIsDisplayed()
        compose.onNodeWithText("test-claude").performClick()
        compose.onNodeWithTag("career_effort_default").performClick()
        compose.runOnIdle {
            assertEquals(listOf(CareerExecution("anthropic", "test-claude", "default")), chosen)
            assertEquals(false, conversationChanged)
        }
    }

    @Test
    fun savedReceiptRevisionKeepsBodyVisibleWithLargeFontAndKeyboard() {
        val artifact =
            CareerArtifact("DOC-010", 3, title = "自己PR", path = "document-v3", characters = 13, server_saved = true)
        val state = mutableStateOf(CareerCoachUiState(initialized = true))
        val revisions = mutableListOf<Pair<CareerArtifact, String>>()
        compose.setContent {
            LargeFont {
                MaterialTheme {
                    CareerSavedArtifactDialog(
                        artifact,
                        state.value,
                        CareerCoachActions(
                            revisionDraft = { receipt, text ->
                                state.value =
                                    state.value.copy(revisionDrafts = mapOf("${receipt.id}:${receipt.version}" to text))
                            },
                            revise = { receipt, text -> revisions += receipt to text },
                        ),
                        loadDocument = { id, version ->
                            assertEquals("DOC-010", id)
                            assertEquals(3, version)
                            buildJsonObject { put("body", "この本文を見ながら直します。") }
                        },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithText("この本文を見ながら直します。").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText("この本文を見ながら直します。")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        compose.runOnIdle {
            assertEquals(
                2f,
                layouts
                    .single()
                    .layoutInput.density.fontScale,
            )
        }
        compose.onNodeWithTag("career_revision_input").performClick().performTextInput("結びを簡潔に")
        waitForKeyboard()
        compose.onNodeWithTag("career_revision_input").assertIsDisplayed()
        compose.onNodeWithTag("career_revision_send").assertIsDisplayed()
        compose.onNodeWithTag("career_artifact_body").assertIsDisplayed()
        screenshot("career-artifact-font200-ime.png")
        compose.onNodeWithTag("career_revision_send").performClick()
        compose.runOnIdle { assertEquals(listOf(artifact to "結びを簡潔に"), revisions) }
    }

    @Test
    fun conversationComposerStaysVisibleWithKeyboardAndLargeFont() {
        val state = mutableStateOf(CareerCoachUiState(initialized = true, snapshot = CareerCoachSnapshot()))
        compose.setContent {
            LargeFont {
                MaterialTheme {
                    CareerContent(
                        CareerUiState(initialized = true, enabled = true, snapshot = CareerSnapshot(1)),
                        {},
                        {},
                        { _, _ -> },
                        {},
                        {},
                        state.value,
                        CareerCoachActions(draft = { state.value = state.value.copy(draft = it) }),
                    )
                }
            }
        }
        compose.onNodeWithTag("career_coach_input").performClick().performTextInput("相談したい")
        waitForKeyboard()
        compose.onNodeWithTag("career_coach_input").assertIsDisplayed()
        compose.onNodeWithTag("career_coach_send").assertIsDisplayed()
        screenshot("career-conversation-font200-ime.png")
    }

    private fun waitForKeyboard() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WindowInspector
                .getGlobalWindowViews()
                .first { it.hasWindowFocus() }
                .windowInsetsController
                ?.show(WindowInsets.Type.ime())
        }
        compose.waitUntil(10_000) {
            WindowInspector.getGlobalWindowViews().any {
                it.hasWindowFocus() && it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true &&
                    (it.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0) > 0
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val directory =
            File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
        directory.mkdirs()
        File(directory, name).outputStream().use { output ->
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
