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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CareerNoteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val local = mockk<CareerLocalStore>()
    private val company = CareerEntity("companies", "a", 3, buildJsonObject { put("notes", "保存済み") })
    private var stored: String? = null

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { local.uiValue(any()) } answers { stored }
        coEvery { local.saveUiValue(any(), any()) } answers {
            stored = secondArg()
            Unit
        }
    }

    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun rapidEditsRestoreLatestBodyWithOriginalVersion() =
        runTest(dispatcher) {
            val model = CareerNoteViewModel(company, local)
            advanceUntilIdle()
            model.change("編集中")
            model.change("最後の入力")
            advanceUntilIdle()
            val restored = CareerNoteViewModel(company.copy(version = 9), local)
            advanceUntilIdle()
            assertEquals("最後の入力", restored.state.value.body)
            assertEquals(3, restored.state.value.version)
        }

    @Test fun diskFailureRetainsTextAndDoesNotContinueToServerSave() =
        runTest(dispatcher) {
            val model = CareerNoteViewModel(company, local)
            advanceUntilIdle()
            coEvery { local.saveUiValue(any(), any()) } throws IOException()
            model.change("消してはいけない下書き")
            var sent = false
            model.persist { sent = true }
            advanceUntilIdle()
            assertEquals("消してはいけない下書き", model.state.value.body)
            assertNotNull(model.state.value.error)
            assertFalse(sent)
        }

    @Test fun unreadableOldDraftIsNotOverwritten() =
        runTest(dispatcher) {
            coEvery { local.uiValue(any()) } throws IOException()
            val model = CareerNoteViewModel(company, local)
            advanceUntilIdle()
            model.change("新しい入力")
            model.persist()
            advanceUntilIdle()
            assertFalse(model.state.value.loaded)
            coVerify(exactly = 0) { local.saveUiValue(any(), any()) }
        }
}
