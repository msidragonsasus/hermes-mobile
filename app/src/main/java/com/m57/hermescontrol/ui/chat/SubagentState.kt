package com.m57.hermescontrol.ui.chat

/** A read-only live roster repairs missed lifecycle events; malformed reads are not empty rosters. */
internal fun reconcileSubagentSnapshot(
    current: List<SubagentIndicator>,
    snapshot: Any?,
): List<SubagentIndicator>? {
    val rows = (snapshot as? Map<*, *>)?.get("subagents") as? List<*> ?: return null
    val live =
        rows.map { row ->
            val record = row as? Map<*, *> ?: return null
            val id = (record["subagent_id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val status = record["status"] as? String ?: return null
            if (status !in
                setOf(
                    "running",
                    "queued",
                    "steered",
                    "completed",
                    "done",
                    "success",
                    "failed",
                    "error",
                    "timeout",
                    "interrupted",
                    "cancelled",
                    "canceled",
                    "stopped",
                )
            ) {
                return null
            }
            SubagentIndicator(
                type = "subagent.snapshot",
                subagentId = id,
                goal = record["goal"] as? String,
                status = status,
                model = record["model"] as? String,
            )
        }
    val remaining = live.toMutableList()
    return buildList {
        for (indicator in current) {
            val match = remaining.indexOfFirst { it.subagentId == indicator.subagentId }
            if (match >= 0) {
                val record = remaining.removeAt(match)
                // A completion already observed for this identity must never be resurrected by a read.
                add(
                    if (indicator.isTerminal) {
                        indicator
                    } else {
                        indicator.copy(
                            status = record.status,
                            goal = record.goal ?: indicator.goal,
                            model = record.model ?: indicator.model,
                        )
                    },
                )
            } else if (indicator.isTerminal) {
                add(indicator)
            }
            // Absent live entries have ended. Do not invent a successful result for a missed completion.
        }
        addAll(remaining)
    }
}
