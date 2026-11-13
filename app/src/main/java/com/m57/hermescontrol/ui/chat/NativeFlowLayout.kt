package com.m57.hermescontrol.ui.chat

/**
 * Rows for the "flow" component: each node sits one row below its deepest parent (longest path),
 * so arrows always point down. Rejects unknown ids, self or duplicate edges and cycles.
 * Nodes at the same depth are ordered by their parents and wrapped to fit the card.
 */
internal object NativeFlowLayout {
    const val MAX_NODES = 16
    const val MAX_PER_ROW = 4

    fun rows(
        nodes: List<FlowNode>,
        edges: List<FlowEdge>,
    ): List<List<Int>> {
        val n = nodes.size
        require(n in 1..MAX_NODES) { "nodes" }
        val index = nodes.withIndex().associate { it.value.id to it.index }
        require(index.size == n) { "duplicate node" }
        val pairs =
            edges.map { edge ->
                val from = index[edge.from] ?: throw IllegalArgumentException("edge from")
                val to = index[edge.to] ?: throw IllegalArgumentException("edge to")
                require(from != to) { "self edge" }
                from to to
            }
        require(pairs.distinct().size == pairs.size) { "duplicate edge" }
        val children = List(n) { mutableListOf<Int>() }
        val parents = List(n) { mutableListOf<Int>() }
        val incoming = IntArray(n)
        pairs.forEach { (from, to) ->
            children[from] += to
            parents[to] += from
            incoming[to]++
        }
        val depth = IntArray(n)
        val queue = ArrayDeque((0 until n).filter { incoming[it] == 0 })
        var seen = 0
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            seen++
            children[v].forEach { c ->
                depth[c] = maxOf(depth[c], depth[v] + 1)
                if (--incoming[c] == 0) queue.addLast(c)
            }
        }
        require(seen == n) { "cycle" }
        val rows = (0..depth.max()).map { d -> (0 until n).filter { depth[it] == d } }.toMutableList()
        for (r in 1 until rows.size) {
            val above = rows[r - 1]
            val row = rows[r]
            // Relative x (0..1) of each node: the mean of its parents in the row above, else where it is now.
            rows[r] =
                row
                    .withIndex()
                    .sortedBy { (i, v) ->
                        val xs = parents[v].mapNotNull { p -> above.indexOf(p).takeIf { it >= 0 } }
                        if (xs.isEmpty()) (i + 0.5) / row.size else xs.average().plus(0.5) / above.size
                    }.map { it.value }
        }
        return rows.flatMap { it.chunked(MAX_PER_ROW) }
    }
}

/** Spoken summary of the arrows, for screen readers. */
internal fun flowDescription(spec: FlowSpec): String {
    val labels = spec.nodes.associate { it.id to it.label }
    val arrows =
        spec.edges.joinToString("、") { e ->
            "${labels[e.from]}から${labels[e.to]}" + if (e.label.isNotBlank()) "（${e.label}）" else ""
        }
    return if (arrows.isEmpty()) "流れ図" else "流れ図。$arrows"
}
