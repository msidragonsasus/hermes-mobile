package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.management.ManagementFactory

/** Synthetic server-JVM allocation evidence only; no Compose, APK, or phone-frame claims. */
class RenderPlanningCacheAllocationTest {
    @Test
    fun completedHistoryPreservesWholeResponseFootersAndBoundsPlanningAllocations() {
        val responseCount = 128
        val rows = mutableListOf<ChatMessage>()
        val records = mutableListOf<ResponseRecord>()
        repeat(responseCount) { r ->
            rows += ChatMessage(id = "u$r", role = MessageRole.USER, content = "質問$r", timestamp = 0)
            val ids = (0..1).map { "a$r-$it" }
            ids.forEachIndexed { i, id ->
                rows +=
                    ChatMessage(
                        id = id,
                        role = MessageRole.ASSISTANT,
                        content = "応答$r-$i " + "あいうえお".repeat(100),
                        timestamp = 0,
                    )
            }
            records +=
                ResponseRecord(
                    id = "r$r",
                    messageIds = ids,
                    finishedAt = 77,
                    authority = acceptedAuthorityFixture(time = 77, members = ids),
                )
        }
        val messages = rows.toList()
        val lifecycle = ReplyLifecycle(ledger = ResponseLedger(responses = records))
        val frames =
            List(64) {
                ChatMessage(
                    id = "live",
                    role = MessageRole.ASSISTANT,
                    content = "受信本文-$it",
                    timestamp = 0,
                    isStreaming = true,
                )
            }

        fun original(live: ChatMessage): RenderPlan {
            val turns = groupIntoTurnsWithStreaming(messages, live, lifecycle.ledger)
            val footers = turns.replyFooters(lifecycle)
            return RenderPlan(
                turns,
                footers,
                messageIdToLazyIndex(turns, replyFooters = footers),
                toolCallMilestones(messages),
            )
        }
        val cache = RenderPlanningCache()
        val scope = Any()

        fun cached(live: ChatMessage) = cache.plan(messages, live, lifecycle, scope)
        val anchor = cached(frames.first())
        frames.forEach { live ->
            val output = cached(live)
            assertEquals(original(live), output)
            assertSame(live, ((output.turns.last() as ChatTurn.Agent).entries.last() as AgentEntry.Prose).message)
            assertSame(anchor.replyFooters, output.replyFooters)
            assertSame(anchor.messageIdToLazyIndex, output.messageIdToLazyIndex)
            assertSame(anchor.milestones, output.milestones)
        }
        assertEquals(responseCount, anchor.replyFooters.count { it != null })
        assertEquals(messages.size + responseCount, anchor.messageIdToLazyIndex["live"])
        repeat(64) {
            original(frames[it])
            cached(frames[it])
        }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        fun measure(
            label: String,
            operation: (ChatMessage) -> RenderPlan,
        ): Long {
            val allocations = mutableListOf<Long>()
            val times = mutableListOf<Long>()
            var last = anchor
            repeat(5) {
                val before = bean.getThreadAllocatedBytes(threadId)
                val started = System.nanoTime()
                frames.forEach { last = operation(it) }
                times += (System.nanoTime() - started) / frames.size
                allocations += (bean.getThreadAllocatedBytes(threadId) - before) / frames.size
            }
            assertEquals(original(frames.last()), last)
            println(
                "PERF_RENDER_ALLOCATION pipeline=$label rows=${messages.size} responses=$responseCount " +
                    "frames=${frames.size} trials=5 median_bytes_per_frame=${allocations.sorted()[2]} " +
                    "median_ns_per_frame=${times.sorted()[2]} bytes=$allocations ns=$times device_measurement=false",
            )
            return allocations.sorted()[2]
        }
        val originalBytes = measure("original", ::original)
        val cachedBytes = measure("cache", ::cached)
        assertTrue(
            "Planning hits must allocate less than the original pipeline on the same workload",
            cachedBytes < originalBytes,
        )
    }
}
