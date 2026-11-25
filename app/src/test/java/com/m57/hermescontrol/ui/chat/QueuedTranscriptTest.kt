package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueuedTranscriptTest {
    private val target = "test-target"
    private val session = "test-session"
    private val json = Json { ignoreUnknownKeys = true }
    private val initial =
        """{"id":71563,"role":"user","content":"first","timestamp":1795433431,"message_uid":"first"}"""
    private val accept =
        """{"id":71564,"role":"user","content":"queued","timestamp":1795433436,"message_uid":"42fa07",
            "display_kind":null,"display_metadata":{"_queued_prompt":true}}"""
    private val call =
        """{"id":71568,"role":"assistant","content":"","timestamp":1795433437,
            "reasoning":"working","tool_calls":[{"id":"call-1","function":{"name":"test","arguments":"{}"}}]}"""
    private val tool =
        """{"id":71593,"role":"tool","content":"result","tool_call_id":"call-1","timestamp":1795433576}"""
    private val final =
        """{"id":71594,"role":"assistant","content":"first answer","timestamp":1795433577,"finish_reason":"stop"}"""
    private val replacement =
        """{"id":71595,"role":"user","content":"queued","timestamp":1795433577,"message_uid":"02bffd"}"""
    private val answer =
        """{"id":71596,"role":"assistant","content":"queued answer","timestamp":1795433578,"finish_reason":"stop"}"""
    private val during = page(initial, accept, call, tool)
    private val drained = page(initial, call, tool, final, replacement, answer)

    private fun page(vararg rows: String): List<SessionMessage> =
        json
            .decodeFromString<SessionMessagesResponse>(
                """{"messages":[${rows.joinToString()}],"offset":0,"pagination":{"order":"latest","offset":0}}""",
            ).messages

    private fun id(row: Long) = canonicalMessageId(target, session, row)

    private fun reconcile(
        state: ChatUiState,
        rows: List<SessionMessage>,
    ) = reconcileTranscript(state, target, session, rows, offset = 0, latest = true)

    private fun localState(): ChatUiState =
        ChatUiState(
            isAgentTyping = true,
            messages = listOf(ChatMessage(id = "local-queued", role = MessageRole.USER, content = "queued")),
            responseLedger =
                ResponseLedger(
                    activeId = "first",
                    responses =
                        listOf(
                            ResponseRecord(id = "first", userRowIds = listOf(71563L)),
                            ResponseRecord(
                                id = "follow-up",
                                userIds = listOf("local-queued"),
                                serverFollowUp = true,
                                authority =
                                    ReplyAuthorityFacts(
                                        queued = OpeningEvidence("attempt", "request", "QUEUED", target, session, 1),
                                    ),
                            ),
                        ),
                ),
        )

    @Test
    fun queuedHistoryUsesTheOptimisticBubbleWithoutSplittingTheRunningTurn() {
        val state = reconcile(localState(), during)

        assertEquals(listOf(id(71563), id(71593), "local-queued"), state.messages.map { it.id })
        assertEquals(listOf("local-queued"), state.messages.filter { it.content == "queued" }.map { it.id })
    }

    @Test
    fun drainTakesOverTheBubbleAfterThePriorFinalAndKeepsItsAnswerBelow() {
        val queued = reconcile(localState(), during)
        val state = reconcile(queued, drained)

        assertEquals(
            listOf(id(71563), id(71593), id(71594), "local-queued", id(71596)),
            state.messages.map { it.id },
        )
        assertEquals("working", state.messages.single { it.id == id(71594) }.reasoningText)
        assertEquals(state.messages, reconcile(state, drained).messages)
    }

    @Test
    fun remoteQueueSurvivesCacheResumeAndReusesItsAcceptBubble() =
        runTest {
            val queued = reconcile(ChatUiState(), during)
            val state = reconcile(restore(queued), drained.drop(1))

            assertEquals(listOf(id(71563), id(71593), id(71594), id(71564), id(71596)), state.messages.map { it.id })
            assertEquals(state.messages, reconcile(state, drained).messages)
        }

    @Test
    fun discardedAcceptStaysVisibleAndFreshInstallUsesTheActiveHistory() {
        val queued = reconcile(localState(), during)
        val discarded = reconcile(queued, drained.dropLast(2))

        assertEquals(listOf(id(71563), id(71593), id(71594), "local-queued"), discarded.messages.map { it.id })
        assertEquals(
            listOf(id(71563), id(71593), id(71594), id(71595), id(71596)),
            reconcile(ChatUiState(), drained).messages.map { it.id },
        )
    }

    @Test
    fun discardedAcceptKeepsItsPlaceAboveLaterTurns() {
        val discarded = reconcile(reconcile(localState(), during), drained.dropLast(2))
        val laterUser = """{"id":71700,"role":"user","content":"later","timestamp":1795433600,"message_uid":"later"}"""
        val laterAnswer =
            """{"id":71701,"role":"assistant","content":"later answer","timestamp":1795433601,"finish_reason":"stop"}"""
        val expected = listOf(id(71563), id(71593), id(71594), "local-queued", id(71700), id(71701))

        val later = page(initial, call, tool, final, laterUser, laterAnswer)
        val state = reconcile(discarded, later)

        assertEquals(expected, state.messages.map { it.id })
        assertEquals(expected, reconcile(state, later).messages.map { it.id })
    }

    @Test
    fun cachedCanonicalAcceptHealsWhenItsStringEncodedMarkerIsSeenAgain() {
        val local = localState()
        val cached =
            local.copy(
                messages =
                    local.messages + ChatMessage(id = id(71564), role = MessageRole.USER, content = "queued"),
            )
        val stringMarker = accept.replace("""{"_queued_prompt":true}""", """"{\"_queued_prompt\":true}"""")
        val state = reconcile(cached, page(initial, stringMarker, call, tool))
        assertEquals(listOf(id(71563), id(71593), "local-queued"), state.messages.map { it.id })
        assertEquals(1, reconcile(state, drained).messages.count { it.content == "queued" })
    }

    @Test
    fun identicalQueuedPromptsKeepTwoStableBubblesAcrossServerRepositionAndDrain() {
        val secondAccept = accept.replace("71564", "71565").replace("42fa07", "second-accept")
        val secondRelocated =
            secondAccept
                .replace("71565", "71596")
                .replace("second-accept", "second-relocated")
                .replace("1795433436", "1795433577")
        val secondReplacement = replacement.replace("71595", "71599").replace("02bffd", "second-drain")
        val firstAnswer = answer.replace("71596", "71598")
        val secondAnswer = answer.replace("71596", "71600").replace("queued answer", "second answer")
        val queued = reconcile(ChatUiState(), page(initial, accept, secondAccept, call, tool))
        assertEquals(listOf(id(71564), id(71565)), queued.messages.filter { it.content == "queued" }.map { it.id })

        val firstDrain = reconcile(queued, page(initial, call, tool, final, replacement, secondRelocated))
        assertEquals(listOf(id(71564), id(71565)), firstDrain.messages.filter { it.content == "queued" }.map { it.id })

        val allDrained = page(initial, call, tool, final, replacement, firstAnswer, secondReplacement, secondAnswer)
        val expected = listOf(id(71563), id(71593), id(71594), id(71564), id(71598), id(71565), id(71600))
        assertEquals(expected, reconcile(firstDrain, allDrained).messages.map { it.id })
        assertEquals(expected, reconcile(queued, allDrained).messages.map { it.id })
    }

    @Test
    fun mismatchCannotSkipTheFirstDrainSlotAndSwallowALaterEqualPrompt() {
        val queued = reconcile(localState(), during)
        val unrelated = replacement.replace("\"queued\"", "\"different\"")
        val sameLater = replacement.replace("71595", "71597").replace("02bffd", "later")
        val state = reconcile(queued, page(initial, call, tool, final, unrelated, answer, sameLater))
        assertEquals(listOf("local-queued", id(71597)), state.messages.filter { it.content == "queued" }.map { it.id })
        assertEquals(id(71595), state.messages.single { it.content == "different" }.id)
    }

    @Test
    fun resumeWithOnlyReplacementUsesThePreviouslyWitnessedFinal() =
        runTest {
            val queued = reconcile(localState(), page(initial, accept, call, tool, final))
            val state = reconcile(restore(queued), drained.takeLast(2))
            assertEquals(
                listOf(id(71563), id(71593), id(71594), "local-queued", id(71596)),
                state.messages.map { it.id },
            )
        }

    @Test
    fun resumeWithoutEverSeeingAcceptUsesTheScopedPredecessorTerminal() {
        val local = localState()
        val ledger =
            local.responseLedger.update("first") {
                it.copy(
                    authority = ReplyAuthorityFacts(terminals = listOf(TerminalEvidence(1, 1, finalRowId = 71594))),
                )
            }
        val state = reconcile(local.copy(responseLedger = ledger), drained)
        assertEquals(listOf(id(71563), id(71593), id(71594), "local-queued", id(71596)), state.messages.map { it.id })
    }

    @Test
    fun queueBindingKeepsLocalFilesAndStoredReasoning() {
        val local = localState()
        val file = Attachment("content://selected/file", "file.txt", "text/plain")
        val storedTrace = accept.replace("\"display_kind\":null", "\"reasoning\":\"user trace\"")
        val queued =
            reconcile(
                local.copy(messages = local.messages.map { it.copy(attachments = listOf(file)) }),
                page(initial, storedTrace, call, tool),
            )
        val prompt = reconcile(queued, drained).messages.single { it.id == "local-queued" }
        assertEquals(listOf(file), prompt.attachments)
        assertEquals("user trace", prompt.reasoningText)
    }

    private val compressionRoot =
        """{"id":71837,"role":"user","content":"A","timestamp":1795433434,"message_uid":"root-A"}"""
    private val compressionAccept =
        """{"id":71838,"role":"user","content":"B","timestamp":1795433438,"message_uid":"52f56e",
            "active":0,"display_metadata":{"_queued_prompt":true}}"""
    private val compressionCall = call.replace("71568", "71839")
    private val compressionTools =
        arrayOf(tool.replace("71593", "71840"), tool.replace("71593", "71841"))
    private val compressionFinal = final.replace("71594", "71842")
    private val compressionReplacement =
        """{"id":71843,"role":"user","content":"B","timestamp":1795433793,"message_uid":"fd2000"}"""
    private val compressionAnswer = answer.replace("71596", "71844")

    private fun legacySteer(
        root: Long = 71837L,
        ids: List<String> = listOf("B-local"),
    ): ChatUiState =
        ChatUiState(
            messages = ids.map { ChatMessage(id = it, role = MessageRole.USER, content = "B", displayKind = "steer") },
            responseLedger =
                ResponseLedger(
                    responses =
                        listOf(
                            ResponseRecord(
                                id = "owner-A",
                                userRowIds = listOf(root),
                                userIds = ids,
                                authority =
                                    ReplyAuthorityFacts(
                                        controls = ids.map { ControlInputEvidence(it, it, "STEERED", null, null, it) },
                                    ),
                            ),
                        ),
                    activeId = "owner-A",
                ),
        )

    @Test
    fun legacyDemotionKeepsOneBubbleThroughCompressionRewriteDrainAndResume() =
        runTest {
            val oldRoot = compressionRoot.replace("71837", "71779")
            val oldAccept = compressionAccept.replace("71838", "71780").replace("\"active\":0", "\"active\":1")
            val during = reconcile(legacySteer(71779), page(oldRoot, oldAccept))
            assertEquals(listOf("B-local"), during.messages.filter { it.content == "B" }.map { it.id })
            assertNull(during.messages.last().displayKind)

            val rewritten = page(compressionRoot, compressionAccept, compressionCall, *compressionTools)
            val reinserted = reconcile(during, rewritten)
            assertEquals(
                "B-local",
                reinserted.responseLedger.queuedTranscriptInputs
                    .single()
                    .bubbleId,
            )
            assertEquals(
                71838L,
                reinserted.responseLedger.queuedTranscriptInputs
                    .single()
                    .rowId,
            )
            assertEquals(
                setOf(71780L),
                reinserted.responseLedger.queuedTranscriptInputs
                    .single()
                    .supersededRows,
            )
            assertEquals(listOf("B-local"), reinserted.messages.filter { it.content == "B" }.map { it.id })

            val drainedPage =
                page(
                    compressionRoot,
                    compressionCall,
                    *compressionTools,
                    compressionFinal,
                    compressionReplacement,
                    compressionAnswer,
                )
            val expected = listOf(id(71837), id(71840), id(71841), id(71842), "B-local", id(71844))
            val drained = reconcile(reinserted, drainedPage)
            assertEquals(expected, drained.messages.map { it.id })
            assertEquals("B-local", drained.responseLedger.aliases["71843"])
            assertEquals(expected, reconcile(restore(reinserted), drainedPage).messages.map { it.id })
            assertEquals(expected, reconcile(restore(during), drainedPage).messages.map { it.id })
            assertEquals(expected, reconcile(restore(drained), drainedPage).messages.map { it.id })
        }

    @Test
    fun restoredLegacyDuplicatesHealWithPreviouslyObservedAcceptAndDrain() =
        runTest {
            val observed =
                reconcile(ChatUiState(), page(compressionRoot, compressionAccept, compressionCall, *compressionTools))
            val drained =
                page(
                    compressionRoot,
                    compressionCall,
                    *compressionTools,
                    compressionFinal,
                    compressionReplacement,
                    compressionAnswer,
                )
            val cached = reconcile(observed, drained)
            val legacy = legacySteer()
            val duplicate =
                cached.copy(
                    messages = cached.messages + legacy.messages,
                    responseLedger =
                        cached.responseLedger.copy(
                            responses = cached.responseLedger.responses + legacy.responseLedger.responses,
                        ),
                )

            val healed = reconcile(restore(duplicate), drained)
            assertEquals(listOf("B-local"), healed.messages.filter { it.content == "B" }.map { it.id })
            assertEquals("B-local", healed.responseLedger.aliases["71843"])
            assertEquals(
                "B-local",
                healed.responseLedger.queuedTranscriptInputs
                    .single()
                    .bubbleId,
            )
        }

    @Test
    fun legacyResumeCanBindDirectlyToTheFirstDrainAfterItsProvenTerminal() =
        runTest {
            val local = legacySteer()
            val terminal = TerminalEvidence(1, 1, userRowId = 71837, finalRowId = 71842)
            val ledger =
                local.responseLedger.update("owner-A") {
                    it.copy(authority = it.authority.copy(terminals = listOf(terminal)))
                }
            val drained =
                page(
                    compressionRoot,
                    compressionCall,
                    *compressionTools,
                    compressionFinal,
                    compressionReplacement,
                    compressionAnswer,
                )
            val restored = reconcile(restore(local.copy(responseLedger = ledger)), drained)

            assertEquals(listOf("B-local"), restored.messages.filter { it.content == "B" }.map { it.id })
            assertEquals("B-local", restored.responseLedger.aliases["71843"])
            assertNull(restored.messages.single { it.id == "B-local" }.displayKind)
        }

    @Test
    fun identicalLegacySendsUseSeparateSlotsAndDoNotSkipAMismatch() {
        val root = compressionRoot.replace("71837", "71779")
        val first = compressionAccept.replace("71838", "71780")
        val second = first.replace("71780", "71781").replace("52f56e", "second-accept")
        val local = legacySteer(71779, listOf("B-local", "B-second"))

        val matched = reconcile(local, page(root, first, second))
        assertEquals(listOf("B-local", "B-second"), matched.messages.filter { it.content == "B" }.map { it.id })
        assertEquals(listOf("B-local", "B-second"), matched.responseLedger.queuedTranscriptInputs.map { it.bubbleId })

        val mismatched = reconcile(local, page(root, first.replace("\"B\"", "\"other\""), second))
        assertEquals(listOf(id(71780), id(71781)), mismatched.responseLedger.queuedTranscriptInputs.map { it.bubbleId })
        assertEquals(3, mismatched.messages.count { it.content == "B" })
    }

    private suspend fun restore(state: ChatUiState): ChatUiState {
        val cache = ChatPersistenceRepository(FakeChatMessageDao())
        val (owner, _) = cache.openTranscript(target, session)
        check(cache.save(cache.prepare(owner, state.messages, state.responseLedger, state.stagedCanonicalMessages)))
        val stored = cache.readTranscript(target, session)
        return ChatUiState(messages = stored.messages, responseLedger = stored.payload.ledger)
    }
}
