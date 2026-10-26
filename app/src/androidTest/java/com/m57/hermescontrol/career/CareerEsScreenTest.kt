package com.m57.hermescontrol.career

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
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
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class CareerEsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val question =
        buildJsonObject {
            put("id", "q1")
            put("version", "v1")
            put("text", "あなたの選社軸とその理由を教えてください。改行を使用せずに入力してください。250文字以下")
            put("input_kind", "text")
            put("max_characters", 250)
            put("no_line_breaks", true)
            put("required", true)
        }
    private val detail =
        CareerCompanyDetail(
            company = CareerEntity("companies", "test-company", 1, buildJsonObject { put("name", "日鉄エンジニアリング株式会社") }),
            offers =
                listOf(
                    CareerEntity(
                        "offers",
                        "test-offer",
                        1,
                        buildJsonObject {
                            put("title", "冬季3daysプログラム")
                            put("portal_state", "available")
                            put("questions", JsonArray(listOf(question)))
                        },
                    ),
                ),
            thread_id = "company:test-company",
            es_workspace = true,
        )

    @Test
    fun listAt360LargeFontOpensQuestionWithoutLongSourceWall() {
        verifyList(360, 1.5f)
    }

    @Test
    fun listAt412StandardFontOpensQuestion() {
        verifyList(412, 1f)
    }

    @Test
    fun listAt360StandardFontOpensQuestion() = verifyList(360, 1f)

    @Test
    fun listAt412LargeFontOpensQuestion() = verifyList(412, 1.5f)

    private fun verifyList(
        width: Int,
        font: Float,
    ) {
        var selected = ""
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                Box(Modifier.width(width.dp)) {
                    MaterialTheme {
                        HermesScaffold(title = { Text("ES作業") }, navigationIcon = NavIcon.Back {}) {
                            CareerEsList(
                                CareerEsState(
                                    initialized = true,
                                    stale = false,
                                    detail = detail,
                                ),
                                "test-offer",
                                {
                                    selected =
                                        it
                                },
                                {},
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("career_es_list").performScrollToNode(
            androidx.compose.ui.test
                .hasText("選社軸と理由"),
        )
        compose.onNodeWithText("選社軸と理由").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("q1", selected) }
        screenshot("es-list-$width-font${(font * 100).toInt()}.png")
        compose.onNodeWithText(question.text("text")!!).assertDoesNotExist()
    }

    @Test
    fun editorAt412RetainsBodyAcrossConsultationFailureAndRecreation() {
        verifyEditor(412, 1f)
    }

    @Test
    fun editorAt360LargeFontRetainsBodyAcrossFailureAndRecreation() {
        verifyEditor(360, 1.5f)
    }

    @Test
    fun editorAt360StandardFontRetainsBody() = verifyEditor(360, 1f)

    @Test
    fun editorAt412LargeFontRetainsBody() = verifyEditor(412, 1.5f)

    private fun verifyEditor(
        width: Int,
        font: Float,
    ) {
        var offline = true
        var savedBody = ""
        var receipt: JsonObject? = null
        val scope = "es-ui-test:" + UUID.randomUUID()
        val local = CareerLocalStore(compose.activity, scope)
        val repository =
            object : CareerEsRepository {
                override suspend fun company(id: String) = detail

                override suspend fun document(
                    id: String,
                    version: Int,
                ) = buildJsonObject { put("body", savedBody) }

                override suspend fun save(payload: JsonObject): JsonObject {
                    if (offline) throw IOException("synthetic offline")
                    savedBody = payload.text("body").orEmpty()
                    return receipt ?: buildJsonObject {
                        put(
                            "work",
                            buildJsonObject {
                                put(
                                    "adopted",
                                    buildJsonObject {
                                        put("id", "saved-doc")
                                        put("version", 1)
                                    },
                                )
                            },
                        )
                        put("document", buildJsonObject { put("version", 1) })
                    }.also { receipt = it }
                }
            }
        val execution = CareerExecution("openai-codex", "synthetic", "medium")
        val coachRepository =
            object : CareerCoachRepository {
                override suspend fun capabilities(refresh: Boolean) =
                    CareerCapabilities(
                        features = listOf("es_workspace", "execution_config"),
                        state = "available",
                        models =
                            listOf(
                                CareerModelOption("openai-codex", "synthetic", reasoning_efforts = listOf("medium")),
                            ),
                        default_execution = execution,
                    )

                override suspend fun dailySettings() = CareerDailySettings()

                override suspend fun conversation(before: Long?) = CareerCoachSnapshot(thread_id = "offer:test-offer")

                override suspend fun events(after: Long) = CareerCoachEvents(thread_id = "offer:test-offer")

                override suspend fun mutate(
                    action: String,
                    payload: JsonObject,
                ) = buildJsonObject {
                    put("id", "synthetic-run")
                    put("state", "queued")
                }
            }
        lateinit var coach: CareerCoachViewModel
        val holder = mutableStateOf<CareerEsViewModel?>(null)
        compose.runOnUiThread {
            holder.value = CareerEsViewModel(repository, local, "test-company", "test-offer")
            coach = CareerCoachViewModel(coachRepository, CareerLocalStore(compose.activity, "$scope/coach"))
        }
        compose.waitUntil(10000) {
            holder.value!!
                .state.value.detail != null &&
                !holder.value!!
                    .state.value.loading
        }
        compose.runOnUiThread { holder.value!!.open("q1") }
        compose.waitUntil(10000) {
            holder.value!!
                .state.value.cache.drafts["q1"] != null
        }
        compose.setContent {
            val model = holder.value!!
            val state by model.state.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                Box(
                    Modifier.width(width.dp),
                ) {
                    MaterialTheme {
                        HermesScaffold(title = { Text("回答の編集") }, navigationIcon = NavIcon.Back {}) {
                            CareerAnswerScreen(state, question, model, scope, coach)
                        }
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("私が大切にしたいのは現場の理解です。")
        compose.waitUntil(10000) {
            holder.value!!
                .state.value.localSaved
        }
        NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
        compose.onNodeWithText("この回答について相談").performClick()
        compose.waitUntil(10000) { NavigationController.backStack?.last() is CareerThreadsKey }
        val route = NavigationController.backStack!!.last() as CareerThreadsKey
        assertEquals("test-company", route.companyId)
        assertEquals("test-offer", route.offerId)
        val handoff = runBlocking { CareerLocalStore(compose.activity, scope).uiValue("handoff/${route.handoffId}") }
        val context = Json.parseToJsonElement(handoff!!) as JsonObject
        assertEquals("q1", context.text("question_id"))
        assertEquals("v1", context.text("question_version"))
        assertEquals("私が大切にしたいのは現場の理解です。", context.text("body"))
        compose.runOnIdle { NavigationController.goBack() }
        screenshot("es-consultation-return-$width-font${(font * 100).toInt()}.png")
        compose.onNodeWithText("私が大切にしたいのは現場の理解です。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("career_answer_save").performScrollTo().performClick()
        compose.onNodeWithText("この本文を保存").performClick()
        compose.waitUntil(10000) {
            holder.value!!
                .state.value.error != null &&
                !holder.value!!
                    .state.value.busy
        }
        compose.onNodeWithText("同じ保存の結果を確認").performScrollTo().assertIsDisplayed()
        screenshot("es-save-failure-$width-font${(font * 100).toInt()}.png")
        compose.runOnUiThread { holder.value = CareerEsViewModel(repository, local, "test-company", "test-offer") }
        compose.waitUntil(10000) {
            holder.value!!
                .state.value.initialized &&
                holder.value!!
                    .state.value.cache.drafts["q1"] != null
        }
        compose.runOnIdle {
            assertEquals(
                "私が大切にしたいのは現場の理解です。",
                holder.value!!
                    .state.value.cache.drafts["q1"]
                    ?.body,
            )
            assertTrue(
                holder.value!!
                    .state.value.cache.pending != null,
            )
        }
        compose.runOnUiThread { holder.value!!.open("q1") }
        compose.waitUntil(10000) {
            holder.value!!
                .state.value.selected == "q1"
        }
        offline = false
        compose.onNodeWithText("同じ保存の結果を確認").performScrollTo().performClick()
        compose.waitUntil(10000) {
            holder.value!!
                .state.value.message != null &&
                !holder.value!!
                    .state.value.busy
        }
        compose.onNodeWithText("サーバーに保存しました。第1版").performScrollTo().assertIsDisplayed()
        screenshot("es-saved-$width-font${(font * 100).toInt()}.png")
        compose.onNodeWithText("この回答について相談").performClick()
        compose.waitUntil(10000) { NavigationController.backStack?.last() is CareerThreadsKey }
        compose.runOnIdle { NavigationController.goBack() }
        compose.runOnUiThread { holder.value = CareerEsViewModel(repository, local, "test-company", "test-offer") }
        compose.waitUntil(10000) {
            holder.value!!
                .state.value.initialized
        }
        compose.runOnIdle {
            assertEquals(
                savedBody,
                holder.value!!
                    .state.value.cache.drafts["q1"]
                    ?.body,
            )
            assertEquals(
                "saved-doc",
                holder.value!!
                    .state.value.cache.drafts["q1"]
                    ?.documentId,
            )
            assertTrue(
                holder.value!!
                    .state.value.cache.pending == null,
            )
            coach.observe(false)
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(
            context.getExternalFilesDir(null),
            name,
        ).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
