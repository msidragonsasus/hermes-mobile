package com.m57.hermescontrol.data.local

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.DraftState
import com.m57.hermescontrol.domain.hermes.OutgoingDraft
import com.m57.hermescontrol.domain.hermes.draftTargetKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingDraftRepositoryTest {
    private val scope = ConnectionScope("synthetic-server", "connection", "profile", "process", 3)

    private class FakeDao : OutgoingDraftDao {
        val rows = MutableStateFlow<List<OutgoingDraftEntity>>(emptyList())

        override fun observe(targetKey: String) = rows.map { list -> list.filter { it.targetKey == targetKey } }

        override suspend fun save(draft: OutgoingDraftEntity) {
            rows.value = rows.value.filterNot { it.id == draft.id } + draft
        }

        override suspend fun delete(
            id: String,
            targetKey: String,
        ) {
            rows.value = rows.value.filterNot { it.id == id && it.targetKey == targetKey }
        }
    }

    @Test
    fun `uncertain draft survives repository and process recreation without changing target`() =
        runTest {
            val dao = FakeDao()
            val draft =
                OutgoingDraft(
                    "draft",
                    scope,
                    "saved",
                    "runtime",
                    "SYNTHETIC_PRIVATE_本文",
                    listOf(Attachment("content://synthetic/file", "添付.txt", "text/plain", 12)),
                    123L,
                    DraftState.UNKNOWN,
                )
            OutgoingDraftRepository(dao).save(draft)
            val restored = OutgoingDraftRepository(dao)
            assertEquals(listOf(draft), restored.observe(scope.copy(processId = "new", generation = 9)).first())
            val other = scope.copy(serverProfileId = "other")
            assertTrue(restored.observe(other).first().isEmpty())
            restored.delete(other, draft.id)
            assertEquals(listOf(draft), restored.observe(scope).first())
            restored.delete(scope, draft.id)
            assertTrue(restored.observe(scope).first().isEmpty())
        }

    @Test
    fun `queue intent survives recreation and legacy drafts default to ordinary prompts`() =
        runTest {
            val dao = FakeDao()
            val draft = OutgoingDraft("queued", scope, "saved", "runtime", "/help", emptyList(), 123, queued = true)
            OutgoingDraftRepository(dao).save(draft)
            assertEquals(draft, OutgoingDraftRepository(dao).observe(scope).first().single())
            // Exact prior payload shape: absence of the new optional field must remain readable.
            dao.rows.value = dao.rows.value.map { it.copy(payload = it.payload.replace(",\"queued\":true", "")) }
            assertEquals(draft.copy(queued = false), OutgoingDraftRepository(dao).observe(scope).first().single())
        }

    @Test
    fun `stable target distinguishes missing empty and delimiter containing identifiers`() {
        assertNotEquals(
            scope.copy(connectionProfileId = null).draftTargetKey(),
            scope.copy(connectionProfileId = "").draftTargetKey(),
        )
        assertNotEquals(
            scope.copy(connectionProfileId = "a:b", serverProfileId = "c").draftTargetKey(),
            scope.copy(connectionProfileId = "a", serverProfileId = "b:c").draftTargetKey(),
        )
    }

    @Test
    fun `corruption is not silently presented as no drafts`() =
        runTest {
            val dao = FakeDao()
            dao.rows.value = listOf(OutgoingDraftEntity("draft", scope.draftTargetKey(), 123, "{broken"))
            val result = runCatching { OutgoingDraftRepository(dao).observe(scope).first() }
            assertTrue(result.isFailure)
        }
}
