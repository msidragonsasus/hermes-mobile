package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

class RenderPlanningCacheTest {
    @Test
    fun repeatedLiveContentUpdatesDoNotRevisitStableHistory() {
        val values =
            List(512) { i ->
                ChatMessage(
                    id = "history-$i",
                    role = if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                    content = "履歴本文-$i",
                    timestamp = 0,
                )
            }
        val messages = CountingMessages(values)
        val lifecycle = ReplyLifecycle(ledger = ResponseLedger())
        val live =
            ChatMessage(id = "live", role = MessageRole.ASSISTANT, content = "開始", timestamp = 0, isStreaming = true)
        val cache = RenderPlanningCache()
        val scope = Any()
        val anchor = cache.plan(messages, live, lifecycle, scope)
        val before = messages.elementReads
        var last = cache.plan(messages, live, lifecycle, scope)
        assertEquals(oracle(values, live, lifecycle), last)
        repeat(63) {
            val next = live.copy(content = "開始" + "文字".repeat(it + 1))
            last = cache.plan(messages, next, lifecycle, scope)
            assertEquals(oracle(values, next, lifecycle), last)
        }
        val reads = messages.elementReads - before
        val lastMessage = ((last.turns.last() as ChatTurn.Agent).entries.last() as AgentEntry.Prose).message
        assertEquals(live.copy(content = "開始" + "文字".repeat(63)), lastMessage)
        assertEquals(512, last.messageIdToLazyIndex["live"])
        assertEquals(true, last.replyFooters.all { it == null })
        assertSame(anchor.replyFooters, last.replyFooters)
        assertSame(anchor.messageIdToLazyIndex, last.messageIdToLazyIndex)
        assertSame(anchor.milestones, last.milestones)
        println("PERF_RENDER_HISTORY history=512 frames=64 element_reads=$reads oracle_includes_milestones=true")
        assertEquals(
            "Live-content-only frames must not re-read unchanged historical rows in the planning pipeline",
            0,
            reads,
        )
    }

    @Test
    fun liveContentHitDoesNotReadStableLedgerOrRebuildMembership() {
        val rows = listOf(message("a"))
        val records =
            List(128) { i ->
                ResponseRecord(
                    id = "r$i",
                    messageIds = listOf("member-$i"),
                    userIds = listOf("user-$i"),
                    authority = acceptedAuthorityFixture(members = listOf("member-$i")),
                )
            }
        val counted = CountingRecords(records)
        val lifecycle = ReplyLifecycle(ledger = ResponseLedger(counted))
        val originalLifecycle = lifecycle.copy(ledger = lifecycle.ledger.copy(responses = records))
        val cache = RenderPlanningCache()
        val scope = Any()
        val live = message("live").copy(isStreaming = true)
        val first = cache.plan(rows, live, lifecycle, scope)
        val before = counted.elementReads
        repeat(64) { i ->
            val next = live.copy(content = "frame-$i")
            // A new effective lifecycle value with the same ledger must also avoid ledger equality scans.
            val output = cache.plan(rows, next, lifecycle.copy(), scope)
            assertEquals(oracle(rows, next, originalLifecycle), output)
            assertSame(first.replyFooters, output.replyFooters)
        }
        assertEquals(0, counted.elementReads - before)
    }

    @Test
    fun ownershipBoundariesAndNullLiveUseOriginalGrouping() {
        val a = message("a")
        val live = message("live").copy(isStreaming = true)
        val cases =
            listOf(
                emptyList<ResponseRecord>(),
                listOf(ResponseRecord(id = "old", messageIds = listOf("a"))),
                listOf(ResponseRecord(id = "current", messageIds = listOf("live"))),
                listOf(ResponseRecord(id = "same", messageIds = listOf("a", "live"))),
                listOf(
                    ResponseRecord(id = "old", messageIds = listOf("a")),
                    ResponseRecord(id = "current", messageIds = listOf("live")),
                ),
            )
        cases.forEach { records ->
            val rows = listOf(a)
            val lifecycle = ReplyLifecycle(ledger = ResponseLedger(records))
            val cache = RenderPlanningCache()
            val scope = Any()
            val first = cache.plan(rows, live, lifecycle, scope)
            val next = live.copy(content = " ")
            val output = cache.plan(rows, next, lifecycle, scope)
            assertEquals(oracle(rows, next, lifecycle), output)
            assertSame(first.replyFooters, output.replyFooters)
            val noLive = cache.plan(rows, null, lifecycle, scope)
            assertEquals(oracle(rows, null, lifecycle), noLive)
            assertNotSame(output, noLive)
            assertEquals(oracle(rows, live, lifecycle), cache.plan(rows, live, lifecycle, scope))
        }
        val cache = RenderPlanningCache()
        val lifecycle = ReplyLifecycle()
        val rows = emptyList<ChatMessage>()
        val scope = Any()
        val empty = cache.plan(rows, null, lifecycle, scope)
        assertEquals(oracle(rows, null, lifecycle), empty)
        assertSame(empty, cache.plan(rows, null, lifecycle, scope))
        val single = cache.plan(rows, live, lifecycle, scope)
        assertEquals(oracle(rows, live, lifecycle), single)
        assertEquals(1, single.turns.size)
    }

    @Test
    fun duplicateHistoryIdsAndLiveCommitRaceKeepHistoryAuthoritative() {
        val live = message("live").copy(isStreaming = true)
        listOf(listOf(live.copy(isStreaming = false)), listOf(message("a"), message("a"), live)).forEach { rows ->
            val counted = CountingMessages(rows)
            val lifecycle = ReplyLifecycle()
            val cache = RenderPlanningCache()
            val scope = Any()
            cache.plan(counted, live, lifecycle, scope)
            val before = counted.elementReads
            val next = live.copy(content = "uncommitted")
            val output = cache.plan(counted, next, lifecycle, scope)
            assertEquals(oracle(rows, next, lifecycle), output)
            assertEquals(
                rows.last(),
                (output.turns.last() as ChatTurn.Agent).entries.last().let {
                    (it as AgentEntry.Prose).message
                },
            )
            assertTrue(counted.elementReads > before)
        }
    }

    @Test
    fun sameIdSameLengthHistoryEditsAndEqualNewReferencesInvalidate() {
        val rows = listOf(message("a", "old"))
        val lifecycle = ReplyLifecycle()
        val live = message("live").copy(isStreaming = true)
        val cache = RenderPlanningCache()
        val scope = Any()
        var previous = cache.plan(rows, live, lifecycle, scope)
        listOf(rows.map { it.copy(content = "new") }, rows.map { it.copy() }, rows.toMutableList()).forEach { changed ->
            val output = cache.plan(changed, live, lifecycle, scope)
            assertEquals(oracle(changed, live, lifecycle), output)
            assertNotSame(previous.replyFooters, output.replyFooters)
            previous = output
        }
        val newLedger = lifecycle.copy(ledger = lifecycle.ledger.copy())
        val output = cache.plan(rows, live, newLedger, scope)
        assertEquals(oracle(rows, live, newLedger), output)
        val sameRows = cache.plan(rows, live, lifecycle, scope)
        assertEquals(oracle(rows, live, lifecycle), sameRows)
        assertNotSame(output.replyFooters, sameRows.replyFooters)
    }

    @Test
    fun everyNoncontentMessageFieldInvalidates() {
        val live = message("live").copy(isStreaming = true)
        val variants =
            listOf(
                live.copy(id = "other"),
                live.copy(role = MessageRole.USER),
                live.copy(role = MessageRole.TOOL),
                live.copy(role = MessageRole.SYSTEM),
                live.copy(reasoningText = "reasoning"),
                live.copy(timestamp = 1),
                live.copy(isStreaming = false),
                live.copy(toolName = "tool"),
                live.copy(toolStatus = ToolStatus.RUNNING),
                live.copy(toolCallId = "call"),
                live.copy(approvalInfo = ApprovalInfo("cmd", null, null)),
                live.copy(clarifyInfo = ClarifyUi("question")),
                live.copy(attachments = listOf(Attachment("content://fixture", "file", "text/plain"))),
                live.copy(toolOutputRiskData = ToolOutputRiskData("medium", listOf("finding"), false)),
                live.copy(progressPreview = "progress"),
                live.copy(displayKind = "notice"),
                live.copy(finishTimestamp = 77),
                live.copy(sourceBody = "MEDIA: /fixture.png"),
                live.copy(hasServerDisplayProjection = true),
            )
        variants.flatMap { listOf(live to it, it to live) }.forEach { (initial, changed) ->
            val rows = listOf(message("a"))
            val counted = CountingMessages(rows)
            val lifecycle = ReplyLifecycle()
            val cache = RenderPlanningCache()
            val scope = Any()
            val first = cache.plan(counted, initial, lifecycle, scope)
            val before = counted.elementReads
            val output = cache.plan(counted, changed, lifecycle, scope)
            assertEquals(oracle(rows, changed, lifecycle), output)
            assertNotSame(first.replyFooters, output.replyFooters)
            assertTrue(counted.elementReads > before)
        }
    }

    @Test
    fun unsupportedMessagesNeverUseContentOnlyHit() {
        val live = message("live").copy(isStreaming = true)
        listOf(
            live.copy(role = MessageRole.USER),
            live.copy(role = MessageRole.TOOL),
            live.copy(role = MessageRole.SYSTEM),
            live.copy(displayKind = "notice"),
            live.copy(isStreaming = false),
        ).forEach { unsupported ->
            val rows = listOf(message("a"))
            val lifecycle = ReplyLifecycle()
            val cache = RenderPlanningCache()
            val scope = Any()
            val first = cache.plan(rows, unsupported, lifecycle, scope)
            val changed = unsupported.copy(content = "changed")
            val output = cache.plan(rows, changed, lifecycle, scope)
            assertEquals(oracle(rows, changed, lifecycle), output)
            assertNotSame(first.replyFooters, output.replyFooters)
        }
    }

    @Test
    fun ordinaryLiveBlankContentAndStableMetadataKeepActivityAndLatestMessage() {
        val rows = listOf(message("u").copy(role = MessageRole.USER))
        val lifecycle = ReplyLifecycle()
        val live =
            message("live", "").copy(
                isStreaming = true,
                reasoningText = "reasoning",
                attachments = listOf(Attachment("content://fixture", "image.png", "image/png")),
                sourceBody = "MEDIA: /fixture.png",
            )
        listOf(emptySet(), setOf("live")).forEach { expanded ->
            val cache = RenderPlanningCache()
            val scope = Any()
            val first = cache.plan(rows, live, lifecycle, scope, expandedTurns = expanded)
            listOf(" ", "answer", "", "**answer**\nMEDIA: /new.png").forEach { content ->
                val next = live.copy(content = content)
                val output = cache.plan(rows, next, lifecycle, scope, expandedTurns = expanded)
                assertEquals(oracle(rows, next, lifecycle, expandedTurns = expanded), output)
                assertSame(next, ((output.turns.last() as ChatTurn.Agent).entries.last() as AgentEntry.Prose).message)
                assertSame(first.messageIdToLazyIndex, output.messageIdToLazyIndex)
                assertSame(first.replyFooters, output.replyFooters)
            }
        }
    }

    @Test
    fun allLifecycleFieldsAndLedgerIdentityInvalidateEvenWhenOutputIsEqual() {
        val rows = listOf(message("a"))
        val live = message("live").copy(isStreaming = true)
        val lifecycle = ReplyLifecycle()
        listOf(
            lifecycle.copy(responseEnded = true),
            lifecycle.copy(activeMessageIds = setOf("live")),
            lifecycle.copy(finalizedMessageId = "live"),
            lifecycle.copy(boundariesReady = false),
            lifecycle.copy(ledger = lifecycle.ledger.copy()),
        ).forEach { changed ->
            val cache = RenderPlanningCache()
            val scope = Any()
            val first = cache.plan(rows, live, lifecycle, scope)
            val output = cache.plan(rows, live, changed, scope)
            assertEquals(oracle(rows, live, changed), output)
            assertNotSame(first.replyFooters, output.replyFooters)
        }
    }

    @Test
    fun terminalMembershipInAllThreeNamespacesExcludesHitEvenWithoutReadyAuthority() {
        val live = message("live").copy(isStreaming = true)
        val facts = acceptedAuthorityFixture(members = listOf("live"))
        val records =
            listOf(
                ResponseRecord(id = "r", messageIds = listOf("live"), authority = facts),
                ResponseRecord(id = "r", userIds = listOf("live"), authority = facts),
                ResponseRecord(id = "r", authority = facts.copy(canonical = CoverageEvidence("test", listOf("live")))),
                ResponseRecord(id = "r", messageIds = listOf("live"), authority = facts.copy(opening = null)),
                ResponseRecord(id = "r", messageIds = listOf("live"), authority = facts.copy(deliveryGap = true)),
            )
        records.forEach { record ->
            val rows = listOf(message("a"))
            val lifecycle = ReplyLifecycle(ledger = ResponseLedger(listOf(record)))
            val cache = RenderPlanningCache()
            val scope = Any()
            val first = cache.plan(rows, live, lifecycle, scope)
            listOf("changed", " ", "", "new\n").forEach { content ->
                val next = live.copy(content = content)
                val output = cache.plan(rows, next, lifecycle, scope)
                assertEquals(oracle(rows, next, lifecycle), output)
                assertNotSame(first.replyFooters, output.replyFooters)
            }
        }
    }

    @Test
    fun canonicalSameTurnFooterWithNonmemberLiveKeepsLastEntryIdentityAndCopy() {
        val answer = message("a", "  **答え**\n\n```kt\nval x = 1\n```\n")
        val rows = listOf(answer)
        val facts =
            acceptedAuthorityFixture(
                members = emptyList(),
            ).copy(canonical = CoverageEvidence("test", listOf("a")))
        val lifecycle = ReplyLifecycle(ledger = ResponseLedger(listOf(ResponseRecord(id = "r", authority = facts))))
        val cache = RenderPlanningCache()
        val scope = Any()
        val live = message("live").copy(isStreaming = true)
        val anchor = cache.plan(rows, live, lifecycle, scope)
        assertEquals(listOf(ReplyFooter(answer.content, 77, "r")), anchor.replyFooters)
        listOf("", " ", "live text", "MEDIA: /fixture.png").forEach { content ->
            val next = live.copy(content = content)
            val output = cache.plan(rows, next, lifecycle, scope)
            assertEquals(oracle(rows, next, lifecycle), output)
            val agent = output.turns.last() as ChatTurn.Agent
            val lastEntry = agent.entries.last()
            assertSame(next, (lastEntry as AgentEntry.Prose).message)
            assertSame(agent, output.turns[output.turns.lastIndex])
            assertSame(agent, output.turns.toList().last())
            assertSame(lastEntry, agent.entries[agent.entries.lastIndex])
            assertSame(lastEntry, agent.entries.toList().last())
            assertEquals(1, agent.entries.count { it === lastEntry })
            assertSame(anchor.replyFooters, output.replyFooters)
            if (content.isBlank()) {
                assertEquals(mapOf("a" to 0), output.messageIdToLazyIndex)
                assertNotSame(anchor.messageIdToLazyIndex, output.messageIdToLazyIndex)
            } else {
                assertSame(anchor.messageIdToLazyIndex, output.messageIdToLazyIndex)
            }
            assertSame(anchor.milestones, output.milestones)
        }
    }

    @Test
    fun activityExpansionLeadingItemsMilestonesAndSearchOffsetsMatchOriginal() {
        val rows =
            listOf(message("u").copy(role = MessageRole.USER), message("a").copy(reasoningText = "reason")) +
                List(5) { message("t$it").copy(role = MessageRole.TOOL) } +
                message("notice").copy(role = MessageRole.USER, displayKind = "notice")
        val lifecycle = ReplyLifecycle()
        val live = message("live").copy(isStreaming = true)
        val cache = RenderPlanningCache()
        val scope = Any()
        var previous: RenderPlan? = null
        listOf(emptySet(), setOf("a"), emptySet<String>()).forEach { expanded ->
            listOf(0, 1, 0).forEach { leading ->
                val output = cache.plan(rows, live, lifecycle, scope, leading, expanded)
                assertEquals(oracle(rows, live, lifecycle, leading, expanded), output)
                assertEquals(mapOf(6 to 5), output.milestones)
                assertEquals(leading, output.messageIdToLazyIndex["u"])
                assertEquals(leading + if (expanded.isEmpty()) 4 else 10, output.messageIdToLazyIndex["live"])
                previous?.let { assertNotSame(it.replyFooters, output.replyFooters) }
                val next = live.copy(content = "A → B → A")
                val hit = cache.plan(rows, next, lifecycle, scope, leading, expanded)
                assertEquals(oracle(rows, next, lifecycle, leading, expanded), hit)
                assertSame(output.messageIdToLazyIndex, hit.messageIdToLazyIndex)
                assertSame(output.milestones, hit.milestones)
                previous = hit
            }
        }
    }

    @Test
    fun scopeChangesResetAndClearReleasePreviousEntry() {
        val rows = listOf(message("a"))
        val live = message("live").copy(isStreaming = true)
        val lifecycle = ReplyLifecycle()
        val cache = RenderPlanningCache()

        data class Scope(
            val session: String,
            val generation: Int,
            val selected: String,
            val configured: String,
        )
        val firstScope = Scope("s", 1, "one", "one")
        var previous = cache.plan(rows, live, lifecycle, firstScope)
        assertSame(previous, cache.plan(rows, live, lifecycle, firstScope.copy()))
        listOf(
            firstScope.copy(session = "other"),
            firstScope.copy(generation = 2),
            firstScope.copy(selected = "two"),
            firstScope.copy(configured = "two"),
            firstScope,
        ).forEach { scope ->
            val output = cache.plan(rows, live, lifecycle, scope)
            assertEquals(oracle(rows, live, lifecycle), output)
            assertNotSame(previous.replyFooters, output.replyFooters)
            previous = output
        }
        cache.clear()
        assertTrue(reachable(cache).none { it is RenderPlan || it is ChatMessage })
        val output = cache.plan(rows, live, lifecycle, firstScope)
        assertEquals(oracle(rows, live, lifecycle), output)
        assertNotSame(previous.replyFooters, output.replyFooters)
        val otherCache = RenderPlanningCache()
        val otherOutput = otherCache.plan(rows, live, lifecycle, firstScope)
        assertEquals(output, otherOutput)
        assertNotSame(output, otherOutput)
    }

    @Test
    fun retainedOutputsUseOneFixedAnchorWithStablePrefixAndNoPreviousPlans() {
        val rows = listOf(message("u").copy(role = MessageRole.USER), message("a"))
        val lifecycle = ReplyLifecycle()
        val cache = RenderPlanningCache()
        val scope = Any()
        val live = message("live").copy(isStreaming = true)
        val anchor = cache.plan(rows, live, lifecycle, scope)
        val outputs = mutableListOf(anchor)
        repeat(256) { i ->
            val next = live.copy(content = "body-$i")
            val output = cache.plan(rows, next, lifecycle, scope)
            assertEquals(oracle(rows, next, lifecycle), output)
            assertSame(anchor.turns.first(), output.turns.first())
            val anchorAgent = anchor.turns.last() as ChatTurn.Agent
            val agent = output.turns.last() as ChatTurn.Agent
            assertSame(anchorAgent.entries.first(), agent.entries.first())
            assertSame(output, cache.plan(rows, next, lifecycle, scope))
            outputs += output
        }
        val retained = reachable(cache)
        assertTrue(retained.contains(anchor))
        assertTrue(retained.contains(outputs.last()))
        outputs.drop(1).dropLast(1).forEach { assertFalse(retained.contains(it)) }
        assertEquals(2, retained.count { it is RenderPlan })
        assertEquals(2, retained.count { it is ChatTurn.Agent })
        assertEquals(3, retained.count { it is AgentEntry.Prose })
        // Two history messages, anchor/current live messages, and the content-free guard template.
        assertEquals(5, retained.count { it is ChatMessage })
        assertEquals(1, retained.count { it is ChatMessage && it.content.isEmpty() })
    }

    private fun message(
        id: String,
        text: String = "answer-$id",
    ) = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = text, timestamp = 0)

    /** Inspect owned links as well as list values, so a hidden previous-output anchor fails this oracle. */
    private fun reachable(root: Any): Set<Any> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun visit(value: Any?) {
            if (value == null || !seen.add(value)) return
            when {
                value is RenderPlanningCache || value is RenderPlan || value is ChatTurn || value is AgentEntry ||
                    value.javaClass.name.startsWith(RenderPlanningCache::class.java.name + "$") -> {
                    value.javaClass.declaredFields
                        .filterNot { Modifier.isStatic(it.modifiers) }
                        .forEach {
                            it.isAccessible = true
                            visit(it.get(value))
                        }
                }

                value is Iterable<*> -> {
                    value.forEach { visit(it) }
                }

                value is Map<*, *> -> {
                    value.forEach { (key, item) ->
                        visit(key)
                        visit(item)
                    }
                }
            }
        }
        visit(root)
        return seen
    }

    private fun oracle(
        messages: List<ChatMessage>,
        live: ChatMessage?,
        lifecycle: ReplyLifecycle,
        leadingItems: Int = 0,
        expandedTurns: Set<String> = emptySet(),
    ): RenderPlan {
        val turns = groupIntoTurnsWithStreaming(messages, live, lifecycle.ledger)
        val footers = turns.replyFooters(lifecycle)
        return RenderPlan(
            turns,
            footers,
            messageIdToLazyIndex(turns, leadingItems, expandedTurns, footers),
            toolCallMilestones(messages),
        )
    }

    private class CountingRecords(
        private val values: List<ResponseRecord>,
    ) : AbstractList<ResponseRecord>() {
        var elementReads = 0
            private set
        override val size: Int get() = values.size

        override fun get(index: Int): ResponseRecord {
            elementReads++
            return values[index]
        }
    }

    private class CountingMessages(
        private val values: List<ChatMessage>,
    ) : AbstractList<ChatMessage>() {
        var elementReads = 0
            private set
        override val size: Int get() = values.size

        override fun get(index: Int): ChatMessage {
            elementReads++
            return values[index]
        }
    }
}
