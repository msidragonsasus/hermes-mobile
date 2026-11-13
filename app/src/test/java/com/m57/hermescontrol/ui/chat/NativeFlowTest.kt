package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFlowTest {
    private fun node(
        id: String,
        label: String = id,
        extra: String = "",
    ) = """{"id":"$id","label":"$label"$extra}"""

    private fun edge(
        from: String,
        to: String,
        label: String? = null,
    ) = """{"from":"$from","to":"$to"${label?.let { ",\"label\":\"$it\"" } ?: ""}}"""

    private fun flow(
        nodes: List<String>,
        edges: List<String>,
        extra: String = "",
    ) = """{"v":1,"id":"roles","type":"flow","title":"役割分担","nodes":[${nodes.joinToString(",")}],""" +
        """"edges":[${edges.joinToString(",")}]$extra}"""

    private fun parse(record: String) = NativeUiProtocol.parse(record, true)

    /** The role split from the reported answer: one fork into two workers that merge again. */
    private val roles =
        flow(
            listOf(
                node("you", "あなた", ",\"sub\":\"要望・進捗確認・承認\",\"accent\":true"),
                node("hermes", "サーバーのHermes", ",\"sub\":\"要件整理・作業分割・検証・統合\""),
                node("codex", "Codex App Server", ",\"sub\":\"実装担当\""),
                node("claude", "Claude Code", ",\"sub\":\"別視点のレビュー等\""),
                node("build", "テスト・Androidビルド"),
                node("apk", "更新用APK"),
            ),
            listOf(
                edge("you", "hermes", "Mobileから"),
                edge("hermes", "codex"),
                edge("hermes", "claude"),
                edge("codex", "build"),
                edge("claude", "build"),
                edge("build", "apk"),
            ),
        )

    @Test fun roleSplitParsesIntoTopDownRows() {
        val parsed = parse(roles)
        assertFalse(parsed.rejected)
        val spec = parsed.components.single() as FlowSpec
        val ids = spec.rows.map { row -> row.map { spec.nodes[it].id } }
        assertEquals(
            listOf(listOf("you"), listOf("hermes"), listOf("codex", "claude"), listOf("build"), listOf("apk")),
            ids,
        )
        assertTrue(spec.nodes.first().accent)
        assertEquals("Mobileから", spec.edges.first().label)
    }

    @Test fun edgesAreOptionalAndUnlinkedNodesShareTheFirstRow() {
        val spec = parse(flow(listOf(node("a"), node("b")), emptyList())).components.single() as FlowSpec
        assertEquals(listOf(listOf(0, 1)), spec.rows)
        val noKey = """{"v":1,"id":"x","type":"flow","title":"t","nodes":[${node("a")},${node("b")}]}"""
        assertEquals(1, parse(noKey).components.size)
    }

    @Test fun nodeSitsBelowItsDeepestParent() {
        // a -> b -> c and a -> c: c must be below b, not beside it.
        val spec =
            parse(flow(listOf(node("a"), node("b"), node("c")), listOf(edge("a", "b"), edge("b", "c"), edge("a", "c"))))
                .components
                .single() as FlowSpec
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), spec.rows)
    }

    @Test fun childrenFollowTheirParentsOrderToAvoidCrossings() {
        // Parents p1, p2 on the top row; children are declared in the opposite order.
        val spec =
            parse(
                flow(
                    listOf(node("p1"), node("p2"), node("c2"), node("c1")),
                    listOf(edge("p1", "c1"), edge("p2", "c2")),
                ),
            ).components.single() as FlowSpec
        assertEquals(listOf("c1", "c2"), spec.rows[1].map { spec.nodes[it].id })
    }

    @Test fun invalidGraphsAreRejected() {
        val ab = listOf(node("a"), node("b"))
        listOf(
            flow(ab, listOf(edge("a", "b"), edge("b", "a"))),
            flow(ab, listOf(edge("a", "a"))),
            flow(ab, listOf(edge("a", "zz"))),
            flow(ab, listOf(edge("a", "b"), edge("a", "b"))),
            flow(listOf(node("a"), node("a")), emptyList()),
            flow(listOf(node("a")), emptyList()),
            flow(listOf(node("a"), node("b", extra = ",\"href\":\"https://x\"")), emptyList()),
            flow(listOf(node("a"), node("b", label = " ")), emptyList()),
            flow(listOf(node("a"), node("b", label = "あ".repeat(41))), emptyList()),
            flow(ab, listOf(edge("a", "b", "あ".repeat(17)))),
            flow(ab, emptyList(), ",\"layout\":\"left\""),
            flow((1..17).map { node("n$it") }, emptyList()),
        ).forEach { assertTrue(it, parse(it).rejected) }
    }

    @Test fun wideLevelsWrapAndKeepEveryEdge() {
        val five = (1..5).map { node("c$it") }
        val fanout = parse(flow(listOf(node("top")) + five, (1..5).map { edge("top", "c$it") }))
        assertFalse(fanout.rejected)
        val spec = fanout.components.single() as FlowSpec
        assertEquals(listOf(listOf(0), listOf(1, 2, 3, 4), listOf(5)), spec.rows)
        assertEquals(5, spec.edges.size)

        val parallel = parse(flow((1..16).map { node("c$it") }, emptyList()))
        val parallelSpec = parallel.components.single() as FlowSpec
        assertEquals((0..15).chunked(4), parallelSpec.rows)
    }

    @Test fun skipRowArrowUsesGutterOutsideTheIntermediateBox() {
        val box = Rect(0f, 60f, 110f, 100f)
        val points = flowRoutePoints(Offset(30f, 30f), Offset(90f, 125f), 45f, 115f, 112f)
        assertEquals(Offset(115f, 45f), points[2])
        assertEquals(Offset(115f, 112f), points[3])
        points.zipWithNext().forEach { (start, end) ->
            val overlapsVertically = maxOf(start.y, end.y) > box.top && minOf(start.y, end.y) < box.bottom
            assertFalse(overlapsVertically && minOf(start.x, end.x) < box.right)
        }
    }

    @Test fun descriptionNamesEveryArrow() {
        val spec = parse(roles).components.single() as FlowSpec
        val text = flowDescription(spec)
        assertTrue(text.startsWith("流れ図。"))
        assertTrue(text.contains("あなたからサーバーのHermes（Mobileから）"))
        assertTrue(text.contains("Claude Codeからテスト・Androidビルド"))
    }
}
