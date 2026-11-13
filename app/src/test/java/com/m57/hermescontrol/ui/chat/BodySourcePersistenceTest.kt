package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Baseline caches source text, not runtime cards or authenticated URLs. */
class BodySourcePersistenceTest {
    private val raw = "Original prose\nMEDIA:/artifacts/original.png"
    private val session = "session"

    @Before fun gateway() {
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } returns "https://fixture.invalid"
        every { AuthManager.getToken() } returns "SYNTHETIC_LIVE_ONLY_TOKEN"
        every { AuthManager.isGatedMode() } returns false
    }

    @After fun release() = unmockkObject(AuthManager)

    @Test fun completionSurvivesDelegateThenObserverCheckpointAndFreshReopen() = checkpoint(false)
    @Test fun messageDoneSurvivesDelegateThenObserverCheckpointAndFreshReopen() = checkpoint(true)

    private fun checkpoint(done: Boolean) = runTest {
        val live = ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = raw, timestamp = 12, isStreaming = true)
        val result = ChatWsEventReducer.reduce(
            ChatUiState(currentSessionId = session), StreamingState(streamingMessage = live),
            if (done) WsEvent.MessageDone(session) else WsEvent.MessageComplete(raw, session), session,
        )
        val persist = result.effects.filterIsInstance<ReducerEffect.PersistMessage>().single()
        assertEquals(raw, persist.message.content)
        assertTrue(result.effects.indexOf(persist) < result.effects.indexOfFirst { it is ReducerEffect.AttachHostMedia })
        val dao = FakeChatMessageDao()
        val repository = ChatPersistenceRepository(dao)
        val (owner, _) = repository.openTranscript("target", session)
        assertTrue(repository.save(repository.prepare(owner, result.state.messages, result.state.responseLedger)))
        val flow = MutableStateFlow(result.state)
        ChatMediaDelegate(flow, { error("No Android context is needed for attachment extraction") }, this, coroutineContext)
            .attachHostMedia(session, persist.message.id)
        val display = flow.value.messages.single()
        assertEquals("Original prose", display.content)
        assertTrue(display.attachments!!.single().uri.contains("SYNTHETIC_LIVE_ONLY_TOKEN"))
        assertTrue(repository.save(repository.prepare(owner, flow.value.messages, flow.value.responseLedger)))
        // Another observer event must not make the source hint a one-shot preservation.
        assertTrue(repository.save(repository.prepare(owner, flow.value.messages.map { it.copy(finishTimestamp = 77) }, flow.value.responseLedger)))
        val (_, reopened) = ChatPersistenceRepository(dao).openTranscript("target", session)
        assertEquals(raw, reopened.messages.single().content)
        assertEquals(display.id, reopened.messages.single().id)
        assertEquals(display.timestamp, reopened.messages.single().timestamp)
        assertEquals(77L, reopened.messages.single().finishTimestamp)
        assertNull(reopened.messages.single().attachments)
        assertFalse(dao.transcriptState("target", session)!!.payload.contains("SYNTHETIC_LIVE_ONLY_TOKEN"))
    }

    @Test fun newerProseMustNotBeReplacedByAnOlderSourceHint() = runTest {
        val message = ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = raw)
        val flow = MutableStateFlow(ChatUiState(messages = listOf(message)))
        ChatMediaDelegate(flow, { error("No context needed") }, this, coroutineContext).attachHostMedia(session, message.id)
        val updated = flow.value.messages.single().copy(content = "Newer prose")
        val dao = FakeChatMessageDao()
        val repo = ChatPersistenceRepository(dao)
        val (owner, _) = repo.openTranscript("target", session)
        repo.save(repo.prepare(owner, listOf(updated), ResponseLedger()))
        val saved = repo.readTranscript("target", session).messages.single()
        assertEquals("Newer prose\nMEDIA:/artifacts/original.png", saved.content)
        assertFalse(saved.content.contains("Original prose"))
    }

    private fun ready(): ChatUiState {
        val user = ChatMessage(id = "user", role = MessageRole.USER, content = "question")
        val answer = ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = raw)
        val ledger = ResponseLedger(listOf(ResponseRecord(id = "A", messageIds = listOf(answer.id))), activeId = "A")
            .acceptInput("attempt", "A", null, true, "rpc", "STREAMING", "target", session, 1L, user.id, 10L, null)
        return ChatUiState(messages = listOf(user, answer), responseLedger = ledger.copy(responses = listOf(
            ledger.responses.single().admitTerminal(TerminalEvidence(77L, 2L, listOf(10L, 11L), 10L, 11L, complete = true)),
        )))
    }

    private fun page(body: String) = listOf(
        SessionMessage(id = 10, role = "user", content = JsonPrimitive("question")),
        SessionMessage(id = 11, role = "assistant", content = JsonPrimitive(body)),
    )
    private fun reconcile(state: ChatUiState, body: String) = reconcileTranscript(state, "target", session, page(body), 0, true)

    @Test fun canonicalSameReferenceRetiresLocalButPreservesOriginalSourceOnReopen() = runTest {
        val canonical = reconcile(ready(), raw)
        assertFalse(canonical.messages.any { it.id == "answer" })
        assertEquals(ReplyAuthorityDecision.ReadyCanonical, canonical.responseLedger.responses.single().authorityDecision(canonical.messages.map { it.id }.toSet()))
        val dao = FakeChatMessageDao()
        val repo = ChatPersistenceRepository(dao)
        val (owner, _) = repo.openTranscript("target", session)
        assertTrue(repo.save(repo.prepare(owner, canonical.messages, canonical.responseLedger)))
        val saved = ChatPersistenceRepository(dao).readTranscript("target", session)
        assertEquals(raw, saved.messages.last().content)
        assertEquals(canonical.messages.map { it.id }, saved.messages.map { it.id })
        assertEquals(77L, saved.payload.ledger.responses.single().finishedAt)
        assertNull(saved.messages.last().attachments)
    }

    @Test fun canonicalMissingReferenceCannotRetireAntecedentOrClaimCoverage() {
        val next = reconcile(ready(), "Newer prose with no file")
        assertTrue(next.messages.any { it.id == "answer" && it.content == raw })
        assertTrue(next.messages.any { it.content == "Newer prose with no file" })
        assertFalse(next.responseLedger.responses.single().coverage)
        assertNotEquals(ReplyAuthorityDecision.ReadyCanonical, next.responseLedger.responses.single().authorityDecision(next.messages.map { it.id }.toSet()))
        assertEquals(next.messages.map { it.id }, reconcile(next, "Newer prose with no file").messages.map { it.id })
        assertEquals(77L, next.responseLedger.responses.single().finishedAt)
    }

    @Test fun exactAliasMissingReferencePreservesBothVersionsWithoutTextDeduplication() {
        val original = ChatMessage(id = "alias", role = MessageRole.ASSISTANT, content = raw)
        val state = ChatUiState(messages = listOf(original), responseLedger = ResponseLedger(aliases = mapOf("11" to "alias")))
        val next = reconcile(state, "Original prose")
        assertTrue(next.messages.any { it.id == original.id && it.content == raw })
        assertTrue(next.messages.any { it.id != original.id && it.content == "Original prose" })
        assertEquals(next.messages.map { it.id }, reconcile(next, "Original prose").messages.map { it.id })
    }

    @Test fun exactCanonicalIdMissingReferenceCannotOverwriteItsStoredAntecedent() {
        val original = ChatMessage(id = canonicalMessageId("target", session, 11), role = MessageRole.ASSISTANT, content = raw)
        val next = reconcile(ChatUiState(messages = listOf(original)), "Original prose")
        assertTrue(next.messages.any { it.id == original.id && it.content == raw })
        assertTrue(next.messages.any { it.id != original.id && it.content == "Original prose" })
    }

    @Test fun equalProseAtDifferentAddressesDoesNotBorrowSourceReferences() = runTest {
        val state = ChatUiState(messages = listOf(ChatMessage(id = "unrelated", role = MessageRole.ASSISTANT, content = raw)))
        val next = reconcile(state, "Original prose")
        val dao = FakeChatMessageDao()
        val repo = ChatPersistenceRepository(dao)
        val (owner, _) = repo.openTranscript("target", session)
        repo.save(repo.prepare(owner, next.messages, next.responseLedger))
        val saved = repo.readTranscript("target", session)
        assertEquals(raw, saved.messages.single { it.id == "unrelated" }.content)
        assertEquals("Original prose", saved.messages.single { it.id == canonicalMessageId("target", session, 11) }.content)
    }

    @Test fun liveCardMetadataVersionRemainsDistinctEvenWhenSourceReferenceIsUnchanged() {
        val canonical = reconcile(ready(), raw)
        val withCustomCard = canonical.copy(messages = canonical.messages.map { message ->
            if (message.role != MessageRole.ASSISTANT) message else message.copy(
                attachments = message.attachments!!.map { it.copy(name = "observed-custom-name.png", size = 42) },
            )
        })
        val contradictory = page(raw).map { row ->
            if (row.id == 10) row.copy(role = "assistant") else row
        }
        val next = reconcileTranscript(withCustomCard, "target", session, contradictory, 0, true)
        val versions = next.messages.filter { it.role == MessageRole.ASSISTANT && it.content == "Original prose" }
        assertEquals(2, versions.size)
        assertTrue(versions.any { it.attachments!!.single().name == "observed-custom-name.png" })
        assertTrue(versions.any { it.attachments!!.single().name == "original.png" })
        assertEquals(77L, next.responseLedger.responses.single().finishedAt)
        assertEquals(next.messages.map { it.id }, reconcileTranscript(next, "target", session, contradictory, 0, true).messages.map { it.id })
    }

    @Test fun exactAliasWithSameReferenceMayRefreshProseWithoutCreatingAuthority() = runTest {
        val original = ChatMessage(id = "alias", role = MessageRole.ASSISTANT, content = raw)
        val state = ChatUiState(messages = listOf(original), responseLedger = ResponseLedger(aliases = mapOf("11" to "alias")))
        val next = reconcile(state, "Updated prose\nMEDIA:/artifacts/original.png")
        assertEquals(1, next.messages.count { it.role == MessageRole.ASSISTANT })
        assertEquals("alias", next.messages.single { it.role == MessageRole.ASSISTANT }.id)
        assertTrue(next.responseLedger.responses.isEmpty())
        val dao = FakeChatMessageDao()
        val repo = ChatPersistenceRepository(dao)
        val (owner, _) = repo.openTranscript("target", session)
        repo.save(repo.prepare(owner, next.messages, next.responseLedger))
        assertEquals("Updated prose\nMEDIA:/artifacts/original.png", repo.readTranscript("target", session).messages.last().content)
    }

    @Test fun missingReferenceAntecedentRemainsIndexedAfterFreshReopen() = runTest {
        val next = reconcile(ready(), "Replacement without original reference")
        val dao = FakeChatMessageDao()
        val repo = ChatPersistenceRepository(dao)
        val (owner, _) = repo.openTranscript("target", session)
        repo.save(repo.prepare(owner, next.messages, next.responseLedger))
        val saved = ChatPersistenceRepository(dao).readTranscript("target", session)
        assertTrue(saved.messages.any { it.id == "answer" && it.content == raw })
        assertTrue(saved.messages.any { it.content == "Replacement without original reference" })
        assertEquals(next.messages.map { it.id }, saved.messages.map { it.id })
        assertEquals(77L, saved.payload.ledger.responses.single().finishedAt)
        assertFalse(saved.payload.ledger.responses.single().coverage)
    }

    @Test fun userProseContainingCredentialWordsIsNotGloballyScrubbed() = runTest {
        val text = "Explain token=SYNTHETIC_PROSE_VALUE without editing my text"
        val dao = FakeChatMessageDao()
        val repo = ChatPersistenceRepository(dao)
        val (owner, _) = repo.openTranscript("target", session)
        repo.save(repo.prepare(owner, listOf(ChatMessage(id = "user", role = MessageRole.USER, content = text)), ResponseLedger()))
        assertEquals(text, repo.readTranscript("target", session).messages.single().content)
    }
}
