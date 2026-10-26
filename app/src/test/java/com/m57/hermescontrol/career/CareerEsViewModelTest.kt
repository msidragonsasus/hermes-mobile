package com.m57.hermescontrol.career

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CareerEsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<CareerEsRepository>()
    private val local = mockk<CareerLocalStore>()
    private var saved: String? = null
    private val question =
        buildJsonObject {
            put("id", "q1")
            put("version", "v1")
            put("text", "選社軸（改行を使用せずに入力）")
            put("max_characters", 50)
        }
    private val detail =
        CareerCompanyDetail(
            company = CareerEntity("companies", "company", 1, buildJsonObject { put("name", "検証企業") }),
            offers =
                listOf(
                    CareerEntity(
                        "offers",
                        "offer",
                        1,
                        buildJsonObject {
                            put("title", "検証募集")
                            put("questions", JsonArray(listOf(question)))
                        },
                    ),
                ),
            thread_id = "company:company",
            es_workspace = true,
        )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { local.esCache("offer") } answers { saved }
        coEvery { local.saveEsCache("offer", any()) } answers { saved = secondArg() }
        coEvery { local.companyCache("company") } returns Json.encodeToString(detail)
        coEvery { local.saveCompanyCache(any(), any()) } returns Unit
        coEvery { repository.company("company") } returns detail
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun model() = CareerEsViewModel(repository, local, "company", "offer")

    @Test
    fun unsavedBodySurvivesLeavingAndRestartWithoutSending() =
        runTest(dispatcher) {
            val one = model()
            advanceUntilIdle()
            one.open("q1")
            advanceUntilIdle()
            one.changeBody("入力途中の回答")
            advanceUntilIdle()
            var left = false
            one.leave { left = true }
            advanceUntilIdle()
            assertTrue(left)
            val two = model()
            advanceUntilIdle()
            two.open("q1")
            advanceUntilIdle()
            assertEquals(
                "入力途中の回答",
                two.state.value.cache.drafts["q1"]
                    ?.body,
            )
            assertEquals("q1", two.state.value.cache.lastQuestion)
            coVerify(exactly = 0) { repository.save(any()) }
        }

    @Test
    fun failedSaveRetainsExactPendingOperationAndBody() =
        runTest(dispatcher) {
            coEvery { repository.save(any()) } throws IOException()
            val model = model()
            advanceUntilIdle()
            model.open("q1")
            advanceUntilIdle()
            model.changeBody("消さない本文")
            advanceUntilIdle()
            model.save()
            advanceUntilIdle()
            val operation = model.state.value.cache.pending
            assertNotNull(operation)
            assertEquals(
                "消さない本文",
                model.state.value.cache.drafts["q1"]
                    ?.body,
            )
            model.retry()
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.save(operation!!.payload) }
            assertNull(model.state.value.message)
        }

    @Test
    fun failedLocalPersistencePreventsNetworkAndNavigation() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.open("q1")
            advanceUntilIdle()
            coEvery { local.saveEsCache(any(), any()) } throws IOException()
            model.changeBody("端末に保存できない本文")
            advanceUntilIdle()
            var left = false
            model.leave { left = true }
            advanceUntilIdle()
            assertFalse(left)
            assertFalse(model.state.value.localSaved)
            model.save()
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.save(any()) }
        }

    @Test
    fun refreshKeepsDirtyBufferAndItsOriginalQuestionVersion() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.open("q1")
            advanceUntilIdle()
            model.changeBody("以前の設問に書いた内容")
            advanceUntilIdle()
            val changed =
                detail.copy(
                    offers =
                        listOf(
                            detail.offers.single().copy(
                                data =
                                    buildJsonObject {
                                        put(
                                            "questions",
                                            JsonArray(
                                                listOf(
                                                    question.with(
                                                        "version" to kotlinx.serialization.json.JsonPrimitive("v2"),
                                                    ),
                                                ),
                                            ),
                                        )
                                    },
                            ),
                        ),
                )
            coEvery { repository.company("company") } returns changed
            model.refresh()
            advanceUntilIdle()
            assertEquals(
                "以前の設問に書いた内容",
                model.state.value.cache.drafts["q1"]
                    ?.body,
            )
            assertEquals(
                "v1",
                model.state.value.cache.drafts["q1"]
                    ?.questionVersion,
            )
            model.rebase()
            advanceUntilIdle()
            assertEquals(
                "v2",
                model.state.value.cache.drafts["q1"]
                    ?.questionVersion,
            )
            assertEquals(
                "以前の設問に書いた内容",
                model.state.value.cache.drafts["q1"]
                    ?.body,
            )
        }

    @Test
    fun serverSuccessWithLocalFailureCanBeReconciledWithoutANewSave() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.open("q1")
            advanceUntilIdle()
            model.changeBody("サーバーに保存した本文")
            advanceUntilIdle()
            val receipt =
                buildJsonObject {
                    put(
                        "work",
                        buildJsonObject {
                            put(
                                "adopted",
                                buildJsonObject {
                                    put("id", "doc")
                                    put("version", 1)
                                },
                            )
                        },
                    )
                    put("document", buildJsonObject { put("version", 1) })
                }
            coEvery { repository.save(any()) } answers {
                coEvery { local.saveEsCache(any(), any()) } throws IOException("synthetic disk full")
                receipt
            }
            model.save()
            advanceUntilIdle()
            val pending = model.state.value.cache.pending!!
            assertNull(model.state.value.message)
            assertEquals(
                "サーバーに保存した本文",
                model.state.value.cache.drafts["q1"]
                    ?.body,
            )
            coEvery { local.saveEsCache("offer", any()) } answers { saved = secondArg() }
            coEvery { repository.save(pending.payload) } returns receipt
            model.retry()
            advanceUntilIdle()
            assertNull(model.state.value.cache.pending)
            assertNotNull(model.state.value.message)
            coVerify(exactly = 2) { repository.save(pending.payload) }
            val reopened = model()
            advanceUntilIdle()
            assertEquals(
                "doc",
                reopened.state.value.cache.drafts["q1"]
                    ?.documentId,
            )
            assertEquals(
                1,
                reopened.state.value.cache.drafts["q1"]
                    ?.documentVersion,
            )
        }

    @Test
    fun constraintsAndDateDisplayPreserveMeaning() {
        assertEquals("2026-09-30 23:59", careerDisplayTime("2026-09-30T23:59"))
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        assertEquals("2026/09/30 23:59", careerDisplayTime("2026-09-30T23:59:10+09:00", tokyo))
        assertEquals("2026/10/01 08:59", careerDisplayTime("2026-09-30T23:59:10Z", tokyo))
        assertEquals("2026/10/01 08:59", careerDisplayTime("2026-09-30T23:59:10+00:00", tokyo))
        assertNotNull(answerConstraint(question, "あ\nい"))
        assertNotNull(answerConstraint(question, "あ".repeat(51)))
        assertNull(answerConstraint(question, "😀".repeat(50)))
        assertTrue(questionConstraints(question).contains("必須条件は未確認"))
        assertNotNull(answerConstraint(JsonObject(emptyMap()), "あ".repeat(20001)))
    }

    @Test
    fun pastedTextIsRetainedEvenWhenItExceedsSaveLimit() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.open("q1")
            advanceUntilIdle()
            val body = "あ".repeat(20001) + "😀"
            model.changeBody(body)
            advanceUntilIdle()
            assertEquals(
                body,
                model.state.value.cache.drafts["q1"]
                    ?.body,
            )
            assertEquals(body, Json.decodeFromString<CareerEsCache>(saved!!).drafts["q1"]?.body)
        }
}
