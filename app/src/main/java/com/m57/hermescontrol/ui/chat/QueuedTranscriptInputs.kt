package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Queue provenance travels with the scoped transcript index. The body stays in its existing cache row. */
@Serializable
data class QueuedTranscriptInput(
    val rowId: Long,
    val uid: String?,
    val bodyDigest: String,
    val bubbleId: String,
    val finalRowId: Long? = null,
    val supersededRows: Set<Long> = emptySet(),
    val replacementRowId: Long? = null,
    val discarded: Boolean = false,
)

internal data class QueuedInputProjection(
    val state: ChatUiState,
    val waiting: List<ChatMessage> = emptyList(),
    val repositionIds: Set<String> = emptySet(),
    val boundIds: Set<String> = emptySet(),
)

internal fun reconcileQueuedInputs(
    state: ChatUiState,
    target: String,
    session: String,
    raw: List<SessionMessage>,
    newest: Boolean,
): QueuedInputProjection {
    val marked = raw.filter { it.isQueuedPrompt }
    val ledger = state.responseLedger
    if (marked.isEmpty() && ledger.queuedTranscriptInputs.isEmpty() &&
        ledger.responses.none { it.authority.queued != null }
    ) {
        return QueuedInputProjection(state)
    }
    val messages = state.messages.associateByTo(linkedMapOf()) { it.id }
    val staged = state.stagedCanonicalMessages.associateByTo(linkedMapOf()) { it.id }
    val entries = ledger.queuedTranscriptInputs.toMutableList()
    val aliases = ledger.aliases.toMutableMap()
    val reposition = mutableSetOf<String>()
    val bound = entries.filter { it.replacementRowId != null }.mapTo(mutableSetOf()) { it.bubbleId }
    val pageIds = raw.mapNotNull { it.id?.toLong() }.toSet()
    val orderedPage = raw.all { it.id != null } && raw.zipWithNext().all { (a, b) -> a.id!! < b.id!! }
    val ordinary = raw.filterNot { it.isQueuedPrompt }
    val ordinaryRoots = ordinary.filter { it.isOrdinaryQueueSlot() }
    val localQueue =
        ledger.responses.mapIndexedNotNull { index, record ->
            val ack = record.authority.queued ?: return@mapIndexedNotNull null
            if (ack.target != target || ack.session != session || record.authority.revocations.isNotEmpty()) {
                return@mapIndexedNotNull null
            }
            val bubble = record.userIds.singleOrNull()?.let(messages::get) ?: return@mapIndexedNotNull null
            val previous =
                if (ack.continuationId != null) {
                    ledger.responses.singleOrNull { it.id == ack.continuationId }
                } else {
                    ledger.responses.take(index).lastOrNull { it.authority.queued == null }
                }
            LocalQueuedInput(ack, record, bubble, previous)
        }

    fun show(row: SessionMessage): ChatMessage? =
        mapServerMessages(session, listOf(row), 0, false, emptyList()).singleOrNull()

    fun observedRootIndex(owner: ResponseRecord): Int {
        val root = owner.steerRoot() ?: owner.queueUserRow(ledger) ?: return -1
        val uid = ledger.canonicalUids[root]
        return ordinaryRoots.indexOfFirst { it.id?.toLong() == root || (uid != null && it.message_uid == uid) }
    }

    fun putEntry(entry: QueuedTranscriptInput) {
        val index = entries.indexOfFirst { it.rowId == entry.rowId }
        if (index < 0) entries += entry else entries[index] = entry
    }

    fun bind(
        row: SessionMessage,
        bubble: ChatMessage,
        acceptedRowId: Long?,
    ): Boolean {
        val rowId = row.id?.toLong() ?: return false
        val server = show(row) ?: return false
        val canonical = canonicalMessageId(target, session, rowId)
        val priorAlias = aliases[rowId.toString()]
        if (priorAlias != null && priorAlias != canonical && priorAlias != bubble.id) return false
        if (aliases.any { (key, id) -> id == bubble.id && key.toLongOrNull() !in listOf(acceptedRowId, rowId) }) {
            return false
        }
        val cached = listOfNotNull(messages[canonical], staged[canonical]).filter { it.id != bubble.id }
        if (ledger.responses.any {
                it.id != localQueue.find { local -> local.bubble.id == bubble.id }?.record?.id &&
                    (rowId in it.userRowIds || it.authority.opening?.userRowId == rowId || canonical in it.userIds)
            }
        ) {
            return false
        }
        val retained =
            (cached + server).fold(bubble) { kept, observed ->
                kept.mergeQueuedUser(observed) ?: return false
            }
        aliases[rowId.toString()] = bubble.id
        acceptedRowId?.let { aliases.remove(it.toString()) }
        cached.forEach {
            messages.remove(it.id)
            staged.remove(it.id)
        }
        messages[bubble.id] = retained
        bound += bubble.id
        if (priorAlias != bubble.id || cached.isNotEmpty()) reposition += bubble.id
        return true
    }

    val localByAccept = mutableMapOf<Long, ChatMessage>()
    for (row in marked) {
        val rowId = row.id?.toLong() ?: continue
        localQueue.singleOrNull { it.ack.userRowId == rowId }?.let { localByAccept[rowId] = it.bubble }
    }
    val demotedOwners = mutableMapOf<String, String>()
    val healedIds = mutableMapOf<String, String>()
    if (newest && orderedPage) {
        val steerBindings = ledger.steerTranscriptRows.flatMapTo(mutableSetOf()) { it.bubbleIds }
        for (owner in ledger.responses) {
            val opening = owner.authority.opening
            if (owner.authority.revocations.isNotEmpty() ||
                (opening != null && (opening.target != target || opening.session != session))
            ) {
                continue
            }
            val steers =
                owner.authority.controls
                    .filter { it.disposition == "STEERED" }
                    .mapNotNull { it.localUserId }
                    .distinct()
                    .filterNot { it in steerBindings || it in ledger.stoppedSteerIds }
                    .mapNotNull(messages::get)
            val queued = localQueue.filter { it.previous?.id == owner.id }
            if (steers.isEmpty() && queued.isEmpty()) continue
            val rootIndex = observedRootIndex(owner)
            if (rootIndex < 0) continue
            val root = ordinaryRoots[rootIndex].id!!.toLong()
            val nextRoot = ordinaryRoots.getOrNull(rootIndex + 1)?.id?.toLong() ?: Long.MAX_VALUE
            val batch = raw.filter { it.id!!.toLong() in (root + 1) until nextRoot && it.isQueuedPrompt }
            val cached =
                entries.filter { entry ->
                    !entry.discarded && entry.rowId in (root + 1) until nextRoot &&
                        batch.none {
                            it.id?.toLong() == entry.rowId ||
                                (entry.uid != null && it.message_uid == entry.uid)
                        }
                }
            val slots = (batch.map { it.id!!.toLong() } + cached.map { it.rowId }).sorted()
            if (slots.isEmpty()) continue

            // History (or a prior observation of the same marked row) establishes the
            // FIFO slots. Never skip a slot based on its payload or borrow one from a
            // different turn. An unbound real steer row makes the batch ambiguous.
            if (steers.isNotEmpty() &&
                ordinary.any { row ->
                    row.display_kind == "steer" && row.id!!.toLong() in (root + 1) until nextRoot &&
                        ledger.steerTranscriptRows.none { it.matches(row) }
                }
            ) {
                continue
            }
            val candidates = (steers + queued.map { it.bubble }).sortedBy { it.timestamp }
            if (candidates.size != slots.size) continue
            for ((rowId, bubble) in slots.zip(candidates)) {
                val row = batch.singleOrNull { it.id?.toLong() == rowId }
                val entry =
                    entries.singleOrNull {
                        it.rowId == rowId || (row?.message_uid != null && it.uid == row.message_uid)
                    }
                if (entry?.discarded == true || entries.any { it.bubbleId == bubble.id && it != entry } ||
                    localByAccept[rowId]?.let { it.id != bubble.id } == true ||
                    queued.singleOrNull { it.bubble.id == bubble.id }?.ack?.userRowId?.let {
                        it != rowId && it != entry?.rowId && it !in entry?.supersededRows.orEmpty()
                    } == true
                ) {
                    break
                }
                val legacy = steers.any { it.id == bubble.id }
                val presentation = if (legacy) bubble.copy(displayKind = null) else bubble
                val previous = entry?.bubbleId?.takeIf { it != bubble.id }
                val cachedBubble = previous?.let(messages::get)
                if (previous != null) {
                    val acceptIds = (entry.supersededRows + entry.rowId).map { canonicalMessageId(target, session, it) }
                    if (cachedBubble == null || previous !in acceptIds) break
                }
                val observed =
                    row?.let(::show) ?: cachedBubble ?: messages[bubble.id]?.takeIf { entry?.bubbleId == it.id }
                val retained = observed?.let(presentation::mergeQueuedUser) ?: break
                val merged = if (cachedBubble != null) retained.mergeQueuedUser(cachedBubble) ?: break else retained
                if (entry?.replacementRowId != null && previous != null) {
                    val replacement = entry.replacementRowId.toString()
                    if (aliases[replacement] != previous) break
                    aliases[replacement] = bubble.id
                    bound -= previous
                    bound += bubble.id
                    reposition += bubble.id
                }
                if (previous != null) {
                    messages.remove(previous)
                    staged.remove(previous)
                    healedIds[previous] = bubble.id
                }
                if (entry != null) putEntry(entry.copy(bubbleId = bubble.id))
                messages[bubble.id] = merged
                localByAccept[rowId] = merged
                if (legacy) demotedOwners[bubble.id] = owner.id
            }
        }
    }

    // The first post-final user row is the first drain slot. A payload mismatch pins the queue
    // at that slot. Never search ahead for an equal body, even if a later prompt is identical.
    if (newest && orderedPage) {
        var previousDrain: Long? = null
        val waiting = entries.filter { !it.discarded && it.replacementRowId == null }.sortedBy { it.rowId }
        for ((index, entry) in waiting.withIndex()) {
            if (entry.rowId in pageIds || marked.any { entry.uid != null && it.message_uid == entry.uid }) break
            val previous =
                localQueue.singleOrNull { it.bubble.id == entry.bubbleId }?.previous
                    ?: ledger.responses.singleOrNull { owner ->
                        owner.authority.controls.any { it.disposition == "STEERED" && it.localUserId == entry.bubbleId }
                    }
            // Compression can rewrite the owner and drain the queue between two history reads.
            val observedRoot = previous?.let { ordinaryRoots.getOrNull(observedRootIndex(it))?.id?.toLong() }
            val floor = maxOf(entry.rowId, previousDrain ?: observedRoot ?: entry.rowId)
            val slot = ordinary.indexOfFirst { it.isOrdinaryQueueSlot() && it.id!!.toLong() > floor }
            val successor = ordinary.getOrNull(slot)
            val final = ordinary.getOrNull(slot - 1)
            val expectedFinal =
                entry.finalRowId ?: if (previousDrain == null) previous?.queueFinalRow() else null
            val boundary = final?.id?.toLong() ?: expectedFinal
            val covered =
                raw
                    .firstOrNull()
                    ?.id
                    ?.toLong()
                    ?.let { it <= floor } == true ||
                    (boundary == expectedFinal && expectedFinal != null) ||
                    (
                        boundary != null && boundary > floor &&
                            ledger.canonicalRoles[boundary - 1] in setOf("assistant", "tool") &&
                            ledger.canonicalRoles.none { (row, role) ->
                                row in (floor + 1) until boundary && role == "user"
                            }
                    )
            val afterFinal =
                boundary != null && boundary > floor &&
                    (final?.isQueueFinal(boundary == expectedFinal) ?: (successor?.id?.toLong() == boundary + 1))
            val bubble = messages[entry.bubbleId]
            val nextId = successor?.id?.toLong()
            if (!covered || successor == null || !afterFinal || successor.queueBodyDigest() != entry.bodyDigest ||
                bubble == null || nextId == null || !bind(successor, bubble, entry.rowId)
            ) {
                if (covered && (successor != null || ordinary.any { it.id!!.toLong() > floor })) {
                    // Covered disappearance with no proven replacement: retain the accepted text.
                    // A future send of the same text must not absorb this discarded input.
                    waiting
                        .drop(index)
                        .filter { pending ->
                            pending.rowId !in pageIds &&
                                marked.none { pending.uid != null && it.message_uid == pending.uid }
                        }.forEach { putEntry(it.copy(discarded = true)) }
                }
                break
            }
            putEntry(entry.copy(replacementRowId = nextId))
            previousDrain = nextId

            // Draining the head can also re-place every still-marked item, in queue order.
            // Their UIDs change too. Only adopt a complete, positional batch after this drain.
            val relocated = raw.dropWhile { it.id != successor.id }.drop(1).takeWhile { it.isQueuedPrompt }
            val remaining = waiting.drop(index + 1)
            if (relocated.isNotEmpty() && relocated.size == remaining.size &&
                remaining.zip(relocated).all { (old, fresh) ->
                    old.rowId !in pageIds && fresh.queueBodyDigest() == old.bodyDigest &&
                        entries.none { it.rowId == fresh.id?.toLong() }
                }
            ) {
                for ((old, fresh) in remaining.zip(relocated)) {
                    val oldIndex = entries.indexOfFirst { it.rowId == old.rowId }
                    entries[oldIndex] =
                        old.copy(
                            rowId = fresh.id!!.toLong(),
                            uid = fresh.message_uid,
                            finalRowId = null,
                            supersededRows = old.supersededRows + old.rowId,
                        )
                }
                break
            }
        }
    }

    val unaddressed = mutableListOf<ChatMessage>()
    for (row in marked) {
        val server = show(row) ?: continue
        val rowId = row.id?.toLong()?.takeIf { it > 0 }
        if (rowId == null) {
            val base = "unaddressed-queued:$target:$session"
            val key =
                generateSequence(messages.size + unaddressed.size) { it + 1 }
                    .map { "$base:$it" }
                    .first { it !in messages && unaddressed.none { row -> row.id == it } }
            unaddressed += server.copy(id = key)
            continue
        }
        val prior = entries.singleOrNull { it.rowId == rowId || (row.message_uid != null && row.message_uid == it.uid) }
        if (prior?.replacementRowId != null || prior?.discarded == true ||
            entries.any { rowId in it.supersededRows }
        ) {
            continue
        }
        val canonical = canonicalMessageId(target, session, rowId)
        val existing = prior?.bubbleId?.let(messages::get)
        val local = localByAccept[rowId]
        val cached = listOfNotNull(existing, messages[canonical], staged[canonical]).distinctBy { it.id }
        val preferred = local ?: existing ?: server.copy(id = canonical)
        val merged =
            (cached + server).fold<ChatMessage, ChatMessage?>(preferred) { kept, observed ->
                kept?.mergeQueuedUser(observed)
            }
        if (merged != null) {
            cached.filter { it.id != merged.id }.forEach { messages.remove(it.id) }
            staged.remove(canonical)
            messages[merged.id] = merged
        } else {
            messages[canonical] = messages[canonical] ?: server.copy(id = canonical)
        }
        if (prior != null && prior.rowId != rowId) entries.remove(prior)
        val following = ordinary.dropWhile { (it.id?.toLong() ?: 0L) <= rowId }.takeWhile { !it.isOrdinaryQueueSlot() }
        val seenFinal =
            following
                .lastOrNull()
                ?.takeIf { orderedPage && it.isQueueFinal(false) }
                ?.id
                ?.toLong()
        putEntry(
            QueuedTranscriptInput(
                rowId,
                row.message_uid,
                row.queueBodyDigest(),
                merged?.id ?: canonical,
                finalRowId = seenFinal ?: prior?.finalRowId,
                supersededRows = prior?.supersededRows.orEmpty() + listOfNotNull(prior?.rowId?.takeIf { it != rowId }),
            ),
        )
    }

    // Resume can miss the accept row entirely. An exact predecessor final (from the
    // guarded terminal) and an adjacent history row provide a slot for its first queued ACK.
    if (newest && orderedPage) {
        for ((previous, locals) in localQueue.groupBy { it.previous }) {
            val finalId = previous?.queueFinalRow() ?: continue
            val previousRoot = previous.queueUserRow(ledger)
            if (entries.any {
                    it.rowId in ((previousRoot ?: Long.MIN_VALUE) + 1) until finalId &&
                        it.replacementRowId == null && locals.none { local -> local.bubble.id == it.bubbleId }
                }
            ) {
                continue
            }
            var last = finalId
            for (local in locals) {
                val entry = entries.singleOrNull { it.bubbleId == local.bubble.id }
                if (entry != null) {
                    last = entry.replacementRowId ?: break
                    continue
                }
                val rootIndex = ordinary.indexOfFirst { it.isOrdinaryQueueSlot() && it.id!!.toLong() > last }
                val row = ordinary.getOrNull(rootIndex) ?: break
                val final = ordinary.getOrNull(rootIndex - 1)
                val anchored =
                    if (last == finalId) {
                        final?.id?.toLong() == finalId || (rootIndex == 0 && row.id!!.toLong() == finalId + 1)
                    } else {
                        final?.isQueueFinal(false) == true &&
                            ordinary.take(rootIndex).any { it.id?.toLong() == last }
                    }
                if (!anchored || (local.ack.userRowId != null && local.ack.userRowId != row.id!!.toLong()) ||
                    entries.any { it.replacementRowId == row.id!!.toLong() } || marked.any { it.id!! < row.id!! } ||
                    !bind(row, local.bubble, null)
                ) {
                    break
                }
                last = row.id!!.toLong()
            }
        }
    }

    val acceptRows = entries.flatMapTo(mutableSetOf()) { it.supersededRows + it.rowId }
    val waiting =
        entries
            .filter { it.replacementRowId == null && !it.discarded }
            .sortedBy { it.rowId }
            .mapNotNull { messages.remove(it.bubbleId) } + unaddressed
    return QueuedInputProjection(
        state.copy(
            messages = messages.values.toList(),
            stagedCanonicalMessages = staged.values.toList(),
            responseLedger =
                ledger.copy(
                    queuedTranscriptInputs = entries,
                    aliases = aliases,
                    responses =
                        ledger.responses.map { response ->
                            val users = response.userIds.filterNot { demotedOwners[it] == response.id }
                            val coverage = response.authority.canonical
                            response.copy(
                                userIds = users.map { healedIds[it] ?: it }.distinct(),
                                authority =
                                    response.authority.copy(
                                        canonical =
                                            coverage?.copy(
                                                memberIds =
                                                    coverage.memberIds.map {
                                                        healedIds[it]
                                                            ?: it
                                                    },
                                            ),
                                    ),
                            )
                        },
                    canonicalRoles = ledger.canonicalRoles - acceptRows,
                    canonicalUids = ledger.canonicalUids - acceptRows,
                    canonicalVisibleRows = ledger.canonicalVisibleRows.filterNot { it in acceptRows },
                ),
        ),
        waiting,
        reposition,
        bound,
    )
}

private data class LocalQueuedInput(
    val ack: OpeningEvidence,
    val record: ResponseRecord,
    val bubble: ChatMessage,
    val previous: ResponseRecord?,
)

private fun ResponseRecord.queueUserRow(ledger: ResponseLedger): Long? =
    authority.opening?.userRowId ?: userRowIds.singleOrNull()
        ?: ledger.aliases
            .filterValues { it in userIds }
            .keys
            .singleOrNull()
            ?.toLongOrNull()

private fun ResponseRecord.queueFinalRow(): Long? =
    authority.terminals
        .filter { it.safe }
        .mapNotNull { it.finalRowId }
        .distinct()
        .singleOrNull()

private fun SessionMessage.isOrdinaryQueueSlot() =
    role == "user" && display_kind == null && !isModelOnly && !isQueuedPrompt

private fun SessionMessage.isQueueFinal(knownTerminal: Boolean) =
    role == "assistant" && display_kind == null && !isModelOnly &&
        (knownTerminal || finish_reason == "stop") && displayContentText.isNotBlank()

private fun SessionMessage.queueBodyDigest(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(content.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Called only after selecting a scoped row/queue slot. Keep local files and any stored reasoning. */
private fun ChatMessage.mergeQueuedUser(observed: ChatMessage): ChatMessage? {
    if (role != MessageRole.USER || observed.role != role || content != observed.content ||
        observed.displayKind != displayKind || observed.hasServerDisplayProjection ||
        (reasoningText.isNotBlank() && observed.reasoningText.isNotBlank() && reasoningText != observed.reasoningText)
    ) {
        return null
    }
    return copy(
        attachments = (attachments.orEmpty() + observed.attachments.orEmpty()).distinct().takeIf { it.isNotEmpty() },
        reasoningText = reasoningText.ifBlank { observed.reasoningText },
    )
}
