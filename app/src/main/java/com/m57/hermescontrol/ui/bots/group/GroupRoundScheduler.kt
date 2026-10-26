package com.m57.hermescontrol.ui.bots.group

internal data class GroupDiscussionTurn(
    val member: String,
    val round: Int,
)

/** Finite scheduled rounds with temporary mention priority; no transport or history ownership. */
internal class GroupRoundScheduler(
    members: List<String>,
    rounds: Int,
    handoffLimit: Int,
    private val allowMentions: Boolean = true,
) {
    private val order = members.filter { it.isNotBlank() }.distinct()
    private val roundLimit = rounds.coerceIn(1, MAX_ALLOWED_DISCUSSION_ROUNDS)
    private val extraLimit = handoffLimit.coerceIn(0, MAX_ALLOWED_CONTINUATION_PASSES)
    private val remaining = order.toMutableList()
    private val priority = mutableListOf<String>()
    private var round = 1
    private var extraTurns = 0

    val hasPending: Boolean
        get() = order.isNotEmpty() && (remaining.isNotEmpty() || priority.isNotEmpty() || round < roundLimit)

    fun nextTurn(): GroupDiscussionTurn? {
        if (!hasPending) return null
        if (remaining.isEmpty() && priority.isEmpty()) {
            round++
            remaining.addAll(order)
        }
        val member = if (priority.isNotEmpty()) priority.removeAt(0) else remaining.removeAt(0)
        return GroupDiscussionTurn(member, round)
    }

    fun prioritize(
        mentions: Collection<String>,
        speaker: String,
    ) {
        if (!allowMentions) return
        val promoted = mutableListOf<String>()
        for (member in mentions.distinct()) {
            if (member == speaker || member !in order) continue
            if (priority.remove(member) || remaining.remove(member)) {
                promoted.add(member)
            } else if (extraTurns < extraLimit) {
                extraTurns++
                promoted.add(member)
            }
        }
        // A promoted scheduled slot is fulfilled early; other scheduled members are retained.
        priority.addAll(0, promoted)
    }
}
