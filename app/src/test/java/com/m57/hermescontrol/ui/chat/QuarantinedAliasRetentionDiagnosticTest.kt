package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import com.m57.hermescontrol.ui.chat.fullbleed.ReplyLifecycle
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.replyFooters
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Quarantine preserves both versions; an exact address does not authorize destructive replacement. */
class QuarantinedAliasRetentionDiagnosticTest {
    private var transportToken = "synthetic-quarantine-fixture-a"

    @Before fun configureGateway() {
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } returns "https://unit.invalid"
        every { AuthManager.getToken() } answers { transportToken }
        every { AuthManager.isGatedMode() } returns false
    }

    @After fun releaseGateway() = unmockkObject(AuthManager)

    private fun localReady(): ChatUiState {
        val user = ChatMessage(id = "user", role = MessageRole.USER, content = "antecedent original input")
        val answer = ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "local answer")
        val admitted =
            ResponseLedger(listOf(ResponseRecord(id = "A", messageIds = listOf(answer.id))), activeId = "A")
                .acceptInput(
                    "attempt",
                    "A",
                    null,
                    true,
                    "rpc",
                    "STREAMING",
                    "target",
                    "session",
                    1L,
                    user.id,
                    10L,
                    null,
                )
        val terminal =
            admitted.responses.single().admitTerminal(
                TerminalEvidence(77L, 2L, listOf(10L, 11L), 10L, 11L, complete = true),
            )
        return ChatUiState(
            messages = listOf(user, answer),
            responseLedger = admitted.copy(responses = listOf(terminal)),
        )
    }

    private fun normalPage() =
        listOf(
            SessionMessage(id = 10, role = "user", content = JsonPrimitive("antecedent original input")),
            SessionMessage(id = 11, role = "assistant", content = JsonPrimitive("canonical answer before")),
        )

    private fun contradictoryPage() =
        listOf(
            SessionMessage(id = 10, role = "assistant", content = JsonPrimitive("incoming contradictory body")),
            SessionMessage(id = 11, role = "assistant", content = JsonPrimitive("incoming answer after")),
        )

    private fun reconcile(
        state: ChatUiState,
        page: List<SessionMessage>,
    ) = reconcileTranscript(state, "target", "session", page, 0, true)

    private fun assertQuarantined(state: ChatUiState) {
        assertEquals(
            ReplyAuthorityDecision.Quarantined,
            state.responseLedger.responses
                .single()
                .authorityDecision(state.messages.map { it.id }.toSet()),
        )
        assertTrue(
            groupIntoTurns(state.messages, state.responseLedger)
                .replyFooters(ReplyLifecycle(ledger = state.responseLedger))
                .all { it == null },
        )
        assertEquals(
            77L,
            state.responseLedger.responses
                .single()
                .finishedAt,
        )
    }

    private fun assertBodiesRetained(
        before: ChatUiState,
        after: ChatUiState,
    ) {
        before.messages.forEach { old ->
            assertTrue(
                "Quarantine must retain antecedent ${old.id}",
                after.messages.any { it.id == old.id && it.role == old.role && it.content == old.content },
            )
        }
        assertTrue(after.messages.any { it.content == "incoming contradictory body" })
        assertTrue(after.messages.any { it.content == "incoming answer after" })
        assertQuarantined(after)
    }

    @Test fun aQuarantinedExactAliasMustNotOverwriteItsAntecedentBody() {
        val initial = localReady()
        assertEquals(
            listOf("local answer"),
            groupIntoTurns(initial.messages, initial.responseLedger)
                .replyFooters(ReplyLifecycle(ledger = initial.responseLedger))
                .filterNotNull()
                .map { it.text },
        )
        assertBodiesRetained(initial, reconcile(initial, contradictoryPage()))
    }

    @Test fun previouslyCanonicalMaterializationsRemainAlongsideConflictingVersions() {
        val canonical = reconcile(localReady(), normalPage())
        assertEquals(
            ReplyAuthorityDecision.ReadyCanonical,
            canonical.responseLedger.responses
                .single()
                .authorityDecision(canonical.messages.map { it.id }.toSet()),
        )
        assertBodiesRetained(canonical, reconcile(canonical, contradictoryPage()))
    }

    @Test fun disputedVersionsAndRevocationSurviveColdReopenWithoutGrowingOnIdenticalAddressSnapshots() =
        runTest {
            val canonical = reconcile(localReady(), normalPage())
            val disputed = reconcile(canonical, contradictoryPage())
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("target", "session")
            assertTrue(repo.save(repo.prepare(owner, disputed.messages, disputed.responseLedger)))
            val (_, saved) = ChatPersistenceRepository(dao).openTranscript("target", "session")
            val reopened = ChatUiState(messages = saved.messages, responseLedger = saved.payload.ledger)
            assertBodiesRetained(canonical, reopened)
            val repeated = reconcile(reopened, contradictoryPage())
            assertBodiesRetained(canonical, repeated)
            assertEquals(reopened.messages.map { it.id }, repeated.messages.map { it.id })
        }

    @Test fun equalBodiesAtDifferentCanonicalAddressesRemainSeparateObservations() {
        val canonical = reconcile(localReady(), normalPage())
        val page =
            contradictoryPage() +
                SessionMessage(
                    id = 12,
                    role = "assistant",
                    content = JsonPrimitive("incoming contradictory body"),
                )
        val disputed = reconcile(canonical, page)
        assertBodiesRetained(canonical, disputed)
        assertEquals(2, disputed.messages.count { it.content == "incoming contradictory body" })
        val repeated = reconcile(disputed, page)
        assertEquals(disputed.messages.map { it.id }, repeated.messages.map { it.id })
        assertEquals(2, repeated.messages.count { it.content == "incoming contradictory body" })
    }

    private fun withMedia(
        page: List<SessionMessage>,
        prose: String,
        path: String,
    ) = page.map { row ->
        if (row.id == 11) row.copy(content = JsonPrimitive("$prose\nMEDIA:$path")) else row
    }

    // Baseline cold caches retain original MEDIA text, not serialized runtime cards.
    // The pre-cleanup prototype source is archived in evidence/body-compat-cleanup.
    private fun ChatUiState.mediaRows(prose: String) =
        messages.filter {
            HostMediaExtractor.strip(it.content) == prose &&
                (!it.attachments.isNullOrEmpty() || HostMediaExtractor.extract(it.content).isNotEmpty())
        }

    private fun assertMediaVersions(
        state: ChatUiState,
        prose: String,
    ) {
        val rows = state.mediaRows(prose)
        assertEquals("Both equal-prose attachment versions must remain readable", 2, rows.size)
        assertEquals(2, rows.map { it.id }.distinct().size)
        val sources = rows.flatMap { row ->
            row.attachments.orEmpty().map { it.uri } + HostMediaExtractor.extract(row.content).map { it.path }
        }
        assertTrue("Antecedent file source must survive", sources.any { it.contains("%2Fa%2Fshared.png") || it.endsWith("/a/shared.png") })
        assertTrue("Incoming file source must remain visible", sources.any { it.contains("%2Fb%2Fshared.png") || it.endsWith("/b/shared.png") })
        assertQuarantined(state)
    }

    @Test fun sameProseMediaChangeCannotOverwriteAnAntecedentCanonicalAttachment() =
        runTest {
            val prose = "canonical answer before"
            val canonical = reconcile(localReady(), withMedia(normalPage(), prose, "/artifacts/a/shared.png"))
            assertEquals(1, canonical.mediaRows(prose).size)
            val page = withMedia(contradictoryPage(), prose, "/artifacts/b/shared.png")
            val disputed = reconcile(canonical, page)
            assertMediaVersions(disputed, prose)
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("target", "session")
            assertTrue(repo.save(repo.prepare(owner, disputed.messages, disputed.responseLedger)))
            val (_, saved) = ChatPersistenceRepository(dao).openTranscript("target", "session")
            val reopened = ChatUiState(messages = saved.messages, responseLedger = saved.payload.ledger)
            assertMediaVersions(reopened, prose)
            val repeated = reconcile(reopened, page)
            assertMediaVersions(repeated, prose)
            assertEquals(reopened.messages.map { it.id }, repeated.messages.map { it.id })
        }

    @Test fun sameProseMediaChangeCannotOverwriteAnEarlierDisputedAttachmentVersion() =
        runTest {
            val prose = "incoming answer after"
            val canonical = reconcile(localReady(), normalPage())
            val first = reconcile(canonical, withMedia(contradictoryPage(), prose, "/artifacts/a/shared.png"))
            assertEquals(1, first.mediaRows(prose).size)
            val page = withMedia(contradictoryPage(), prose, "/artifacts/b/shared.png")
            val disputed = reconcile(first, page)
            assertMediaVersions(disputed, prose)
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("target", "session")
            assertTrue(repo.save(repo.prepare(owner, disputed.messages, disputed.responseLedger)))
            val (_, saved) = ChatPersistenceRepository(dao).openTranscript("target", "session")
            val reopened = ChatUiState(messages = saved.messages, responseLedger = saved.payload.ledger)
            assertMediaVersions(reopened, prose)
            val repeated = reconcile(reopened, page)
            assertMediaVersions(repeated, prose)
            assertEquals(reopened.messages.map { it.id }, repeated.messages.map { it.id })
        }

    @Test fun mediaSourceIdentityDoesNotChangeWhenOnlyGatewayTransportTokenRefreshes() =
        runTest {
            val canonical = reconcile(localReady(), normalPage())
            val page = withMedia(contradictoryPage(), "incoming answer after", "/artifacts/a/shared.png")
            val first = reconcile(canonical, page)
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("target", "session")
            assertTrue(repo.save(repo.prepare(owner, first.messages, first.responseLedger)))
            val (_, saved) = ChatPersistenceRepository(dao).openTranscript("target", "session")
            val reopened = ChatUiState(messages = saved.messages, responseLedger = saved.payload.ledger)
            transportToken = "synthetic-quarantine-fixture-b"
            val refreshed = reconcile(reopened, page)
            assertEquals(first.messages.map { it.id }, refreshed.messages.map { it.id })
            assertEquals(1, refreshed.mediaRows("incoming answer after").size)
            assertTrue(
                refreshed
                    .mediaRows("incoming answer after")
                    .single()
                    .attachments!!
                    .single()
                    .uri
                    .endsWith(transportToken),
            )
            assertQuarantined(refreshed)
        }

    @Test fun sourceBodiesRoundTripForIndexedVisibleAndStagedBodiesWithoutCrossTargetLeakage() =
        runTest {
            val prose = "canonical answer before"
            val canonical = reconcile(localReady(), withMedia(normalPage(), prose, "/artifacts/a/shared.png"))
            val media = canonical.mediaRows(prose).single()
            val staged = media.copy(id = "staged-media", content = "staged file")
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("target", "session")
            assertTrue(repo.save(repo.prepare(owner, canonical.messages, canonical.responseLedger, listOf(staged))))
            val reopenedRepo = ChatPersistenceRepository(dao)
            val (_, saved) = reopenedRepo.openTranscript("target", "session")
            assertTrue(media.attachments!!.single().uri.endsWith(transportToken))
            assertEquals("$prose\nMEDIA:/artifacts/a/shared.png", saved.messages.single { it.id == media.id }.content)
            assertEquals("staged file\nMEDIA:/artifacts/a/shared.png", saved.stagedMessages.single().content)
            assertTrue(saved.messages.all { it.attachments == null })
            assertTrue(saved.stagedMessages.all { it.attachments == null })
            val other = reopenedRepo.readTranscript("other-target", "session")
            assertTrue(other.messages.isEmpty())
            assertTrue(other.stagedMessages.isEmpty())
        }
}
