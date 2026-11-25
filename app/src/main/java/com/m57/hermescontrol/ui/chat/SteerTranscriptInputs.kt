package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.domain.hermes.DraftState
import kotlinx.serialization.Serializable

/** Several sends can occupy one physical server row. Keep the presentation keys in the scoped cache. */
@Serializable
data class SteerTranscriptRow(
    val rowId: Long,
    val uid: String?,
    val bubbleIds: List<String>,
    val nextTurn: Boolean,
)

internal fun ChatUiState.pendingSteerInputs(): Map<String, String> =
    messages
        .mapNotNull { message ->
            responseLedger.pendingInputs[message.id]
                ?.takeIf {
                    message.displayKind == "steer"
                }?.let { message.id to it }
        }.toMap()

internal fun ResponseLedger.blockingAcceptedSteerIds(owner: String? = null): Set<String> =
    unresolvedSteerIds(owner) - settledSteerIds

internal fun ChatUiState.blockingSteerIds(owner: String? = null): Set<String> {
    val ledger = responseLedger
    val pending = pendingSteerInputs()
    val pendingIds =
        if (owner == null) {
            pending.keys
        } else {
            val provisionalIds =
                ledger.possibleContinuations
                    .mapNotNull { (attempt, predecessor) ->
                        ledger.pendingSubmissions[attempt].takeIf { predecessor == owner }
                    }.toSet()
            pending.filterValues { it in provisionalIds }.keys
        }
    return ledger.blockingAcceptedSteerIds(owner) + (pendingIds - ledger.settledSteerIds - ledger.stoppedSteerIds)
}

internal fun ChatUiState.settleSteerOrdering(): ChatUiState {
    val ids = blockingSteerIds()
    if (ids.isEmpty()) return this
    return copy(responseLedger = responseLedger.copy(settledSteerIds = responseLedger.settledSteerIds + ids))
}

internal fun ChatUiState.hasUncertainSteer(): Boolean {
    val ledger = responseLedger
    if (ledger.pendingSubmissions.isEmpty()) return false
    val pending =
        pendingSteerInputs().filterKeys { it !in ledger.settledSteerIds && it !in ledger.stoppedSteerIds }.values
    return outgoingDrafts.any { draft ->
        draft.state == DraftState.UNKNOWN && draft.id !in activeDraftIds &&
            ledger.pendingSubmissions[draft.id] in pending
    }
}

internal fun ResponseLedger.mayDrainSteer(owner: String): Boolean =
    unresolvedSteerIds(owner).isNotEmpty() ||
        possibleContinuations.any { (attempt, predecessor) ->
            predecessor == owner &&
                responses.any {
                    it.id == pendingSubmissions[attempt] && it.id != owner && !it.serverFollowUp
                }
        }

internal fun ResponseLedger.unresolvedSteerIds(owner: String? = null): Set<String> {
    val delivered =
        steerTranscriptRows.flatMapTo(mutableSetOf()) { it.bubbleIds } +
            queuedTranscriptInputs.map { it.bubbleId }
    return responses.filter { owner == null || it.id == owner }.flatMapTo(mutableSetOf()) { response ->
        response.authority.controls.mapNotNull { control ->
            control.localUserId?.takeIf {
                control.disposition == "STEERED" && it !in delivered && it !in stoppedSteerIds
            }
        }
    }
}

/** Fold only anchored occurrences. Reply coverage still uses the original physical server rows. */
internal fun reconcileSteerInputs(
    input: ChatUiState,
    transcript: ChatUiState,
    target: String,
    session: String,
    history: List<SessionMessage>,
): ChatUiState {
    val ledger = transcript.responseLedger
    if (history.isEmpty() || history.any { it.id == null } ||
        history.zipWithNext().any { (a, b) -> a.id!! >= b.id!! }
    ) {
        return transcript
    }
    val bindings = ledger.steerTranscriptRows.toMutableList()
    val local = (transcript.messages + input.messages).associateBy { it.id }
    val bound = bindings.flatMapTo(mutableSetOf()) { it.bubbleIds }
    bound += ledger.queuedTranscriptInputs.map { it.bubbleId }
    for (owner in ledger.responses) {
        val opening = owner.authority.opening
        if (owner.authority.revocations.isNotEmpty() ||
            (opening != null && (opening.target != target || opening.session != session))
        ) {
            continue
        }
        val accepted =
            owner.authority.controls
                .filter { it.disposition == "STEERED" }
                .mapNotNull { it.localUserId }
        val pending =
            ledger.pendingSubmissions
                .filterKeys { ledger.possibleContinuations[it] == owner.id }
                .values
                .flatMap { provisional -> ledger.pendingInputs.filterValues { it == provisional }.keys }
                .filter { local[it]?.displayKind == "steer" }
        val remaining =
            (accepted + pending)
                .distinct()
                .filterNot { it in bound }
                .mapNotNull(local::get)
                .toMutableList()
        if (remaining.isEmpty()) continue
        val rootId = owner.steerRoot() ?: continue
        val rest = history.dropWhile { it.id!!.toLong() <= rootId }
        val rootVisible = history.any { it.id?.toLong() == rootId && it.isSteerDrainRoot() }
        if (!rootVisible) {
            // Latest history can begin mid-turn. A receipt can replace the missing root anchor
            // only if it accounts for every stored steer row, including rows already bound.
            val users =
                owner.authority.terminals
                    .filter { it.safe }
                    .mapNotNull { it.userRowIds }
                    .distinct()
                    .singleOrNull()
            val available = rest.mapTo(mutableSetOf()) { it.id!!.toLong() } + bindings.map { it.rowId }
            if (users?.firstOrNull() != rootId || !available.containsAll(users.drop(1))) continue
        }
        val nextRoot = rest.indexOfFirst { it.isSteerDrainRoot() }.let { if (it < 0) rest.size else it }
        for (row in rest.take(nextRoot).filter { it.role == "user" && it.display_kind == "steer" }) {
            if (bindings.any { it.matches(row) }) continue
            val id = row.id!!.toLong()
            val alias = ledger.aliases[id.toString()]
            if (alias != null && alias != canonicalMessageId(target, session, id) &&
                remaining.none { it.id == alias }
            ) {
                break
            }
            val group = remaining.matchSteerGroup(row.displayContentText, "\n") ?: break
            bindings += SteerTranscriptRow(id, row.message_uid, group, nextTurn = false)
            bound += group
            remaining.subList(0, group.size).clear()
        }

        // The finalizer joins remaining steers with a single newline. Compression demotions are
        // regular next-turn queue entries and merge with a blank line. In either case the whole
        // first adjacent user row must match all accepted, still-unbound occurrences in order.
        val finalId = owner.steerFinal() ?: continue
        val successor = rest.getOrNull(nextRoot) ?: continue
        val previous = rest.getOrNull(nextRoot - 1) ?: continue
        val successorId = successor.id!!.toLong()
        val canonicalId = canonicalMessageId(target, session, successorId)
        val observedSuccessor =
            ledger.responses.any {
                it.steerPredecessorId == owner.id && it.steerRoot() == successorId
            }
        if (remaining.isEmpty() || (!observedSuccessor && remaining.any { it.id !in accepted }) ||
            previous.id?.toLong() != finalId || previous.role != "assistant" ||
            bindings.any { it.matches(successor) } ||
            ledger.aliases[successorId.toString()]?.let { it != canonicalId } == true
        ) {
            continue
        }
        if (!remaining.matchesSteerDrain(successor.contentText)) continue
        val group = remaining.map { it.id }
        bindings += SteerTranscriptRow(successorId, successor.message_uid, group, nextTurn = true)
        bound += group
    }
    if (bindings.isEmpty()) return transcript

    var result = transcript
    for (row in history) {
        val binding = bindings.singleOrNull { it.matches(row) } ?: continue
        val rowId = row.id!!.toLong()
        val canonical = canonicalMessageId(target, session, rowId)
        val alias = result.responseLedger.aliases[rowId.toString()]
        val group = binding.bubbleIds.mapNotNull(local::get)
        if (group.size != binding.bubbleIds.size) continue
        val matches =
            if (binding.nextTurn) {
                group.matchesSteerDrain(row.contentText)
            } else {
                group.matchSteerGroup(row.displayContentText, "\n") == binding.bubbleIds
            }
        if (!matches) continue
        val anchor =
            result.messages.indexOfFirst {
                it.id == canonical ||
                    (it.id == alias && it.id !in binding.bubbleIds)
            }
        val first = group.first().id
        // The regular reconciler may already have taken over a single row with its local id.
        val position = if (anchor >= 0) anchor else result.messages.indexOfFirst { it.id == first }
        if (position < 0) continue
        val replaced = binding.bubbleIds.toSet() + canonical + listOfNotNull(alias)
        val projected =
            group.mapIndexed { index, bubble ->
                bubble.copy(displayKind = if (binding.nextTurn && index == 0) null else "steer")
            }
        val projection = result.responseLedger
        val owner =
            projection.responses.singleOrNull { response ->
                rowId in response.canonicalRowIds ||
                    (binding.nextTurn && response.steerRoot() == rowId)
            }
        result =
            result.copy(
                messages =
                    result.messages.take(position).filterNot { it.id in replaced } +
                        projected + result.messages.drop(position).filterNot { it.id in replaced },
                stagedCanonicalMessages = result.stagedCanonicalMessages.filterNot { it.id in replaced },
                responseLedger =
                    projection.copy(
                        aliases = projection.aliases + (rowId.toString() to first),
                        responses =
                            projection.responses.map { response ->
                                val filtered = response.userIds.filterNot { it in replaced }
                                val keepsTypedRow =
                                    !binding.nextTurn && owner == null &&
                                        response.userIds.any { it in binding.bubbleIds }
                                val owns = response.id == owner?.id || keepsTypedRow
                                val coverage = response.authority.canonical
                                response.copy(
                                    userIds = if (owns) filtered + binding.bubbleIds else filtered,
                                    authority =
                                        response.authority.copy(
                                            canonical =
                                                coverage?.copy(
                                                    memberIds =
                                                        coverage.memberIds.map {
                                                            if (it in
                                                                replaced
                                                            ) {
                                                                first
                                                            } else {
                                                                it
                                                            }
                                                        },
                                                ),
                                        ),
                                )
                            },
                    ),
            )
        if (rowId != binding.rowId) bindings[bindings.indexOf(binding)] = binding.copy(rowId = rowId)
    }
    return result.copy(responseLedger = result.responseLedger.copy(steerTranscriptRows = bindings))
}

internal fun ResponseRecord.steerRoot(): Long? =
    authority.opening?.userRowId ?: authority.terminals
        .mapNotNull { it.userRowId }
        .distinct()
        .singleOrNull()
        ?: userRowIds.singleOrNull()

private fun ResponseRecord.steerFinal(): Long? =
    authority.terminals
        .filter { it.safe }
        .mapNotNull { it.finalRowId }
        .distinct()
        .singleOrNull()
        ?: canonicalFinalAssistantRowId

internal fun SteerTranscriptRow.matches(row: SessionMessage): Boolean =
    row.role == "user" && (if (nextTurn) row.isSteerDrainRoot() else row.display_kind == "steer") &&
        (if (uid != null && row.message_uid != null) uid == row.message_uid else rowId == row.id?.toLong())

private fun SessionMessage.isSteerDrainRoot(): Boolean =
    role == "user" && display_kind == null && !isModelOnly && !isQueuedPrompt

private fun List<ChatMessage>.matchesSteerDrain(text: String): Boolean {
    val parts = map { it.content.trim() }
    if (text == parts.joinToString("\n") || text == parts.joinToString("\n\n")) return true
    // Compression can put newer demotions in the queue before the finalizer appends older
    // leftovers. Recover the send order only if the entire adjacent row covers every input.
    return (1 until parts.size).any { split ->
        text == parts.drop(split).joinToString("\n\n") + "\n\n" + parts.take(split).joinToString("\n")
    }
}

private fun List<ChatMessage>.matchSteerGroup(
    text: String,
    separator: String,
): List<String>? {
    val ids = mutableListOf<String>()
    var offset = 0
    for (bubble in this) {
        val part = bubble.content.trim()
        if (part.isEmpty() || !text.startsWith(part, offset)) return null
        offset += part.length
        ids += bubble.id
        if (offset == text.length) return ids
        if (!text.startsWith(separator, offset)) return null
        offset += separator.length
    }
    return null
}
