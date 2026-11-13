package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.SessionMessage
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/** An address is scoped to a durable target and saved session, never a socket. */
internal fun canonicalMessageId(
    target: String,
    session: String,
    row: Long,
): String =
    "canonical:" +
        listOf(target, session, row.toString()).joinToString(":") {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
        }

/** One identity-only reconcile for hydrate, older pages and sync. No text/clock matching. */
internal fun reconcileTranscript(
    state: ChatUiState,
    target: String,
    session: String,
    raw: List<SessionMessage>,
    offset: Int,
    latest: Boolean,
    older: Boolean = false,
): ChatUiState {
    val addresses = raw.mapNotNull { it.id?.toLong()?.takeIf { row -> row > 0 } }
    if (addresses.size != addresses.distinct().size) return state // Invalid addresses prove nothing.
    // The legacy mapper's latest-mode IDs can collide (row id == null-row
    // index). Use its unique positional IDs only as an internal provenance key;
    // filtering/reasoning folding must not change the raw position association.
    val positionByMapperId = raw.indices.associateBy { "rest-$session-${offset + it}" }
    val reservedIds = (state.messages + state.stagedCanonicalMessages).map { it.id }.toMutableSet()
    val mappedWithPositions =
        mapServerMessages(session, raw, offset, false, emptyList()).map { message ->
            val position = positionByMapperId.getValue(message.id)
            val row = raw[position].id?.toLong()?.takeIf { it > 0 }
            val id =
                if (row != null) {
                    canonicalMessageId(target, session, row)
                } else {
                    // A missing address is a new observation, not identity across
                    // pages. Never overwrite an earlier body by index or prose.
                    val base = "unaddressed:$target:${message.id}"
                    var candidate = base
                    var observation = 0
                    while (candidate in reservedIds) candidate = "$base:observation:${++observation}"
                    reservedIds += candidate
                    candidate
                }
            // Observe the actual assistant source before the legacy mapper's display strip.
            // This is body compatibility, not cached-card hydration or membership proof.
            val source =
                raw[position].displayContentText.takeIf {
                    message.role == MessageRole.ASSISTANT && message.displayKind != "hidden" &&
                        HostMediaExtractor.extract(it).isNotEmpty()
                }
            message.copy(id = id, sourceBody = source) to position
        }
    val mapped = mappedWithPositions.map { it.first }
    val rawPositionById = mappedWithPositions.associate { it.first.id to it.second }
    val addressById = addresses.associateBy { canonicalMessageId(target, session, it) }
    val roles =
        raw
            .mapNotNull { row ->
                row.id
                    ?.toLong()
                    ?.takeIf { it > 0 }
                    ?.let { it to row.role.orEmpty() }
            }.toMap()
    // A newest page is not replacement coverage for offline/older cache rows.
    // Legacy provenance stays unknown, but its original bodies remain accessible.
    var messages = state.messages
    var ledger =
        state.responseLedger.copy(
            canonicalRoles = state.responseLedger.canonicalRoles + roles,
            canonicalVisibleRows =
                (
                    state.responseLedger.canonicalVisibleRows +
                        mapped.mapNotNull {
                            addressById[it.id]
                        }
                ).distinct(),
        )
    // First collect all candidate claims, then quarantine EVERY disputed owner.
    // No record is allowed to consume a row before the union is compared.
    for (record in ledger.responses) {
        var facts = record.authority
        val root = facts.opening?.userRowId
        val final = facts.terminals.mapNotNull { it.finalRowId }.firstOrNull()
        if (raw.any { it.id?.toLong() == root && root != null && (it.role != "user" || it.display_kind != null) }) {
            facts = facts.revoke("normal-opening", root.toString())
        }
        if (raw.any { it.id?.toLong() == final && final != null && it.role != "assistant" }) {
            facts = facts.revoke("terminal-role", final.toString())
        }
        for (terminal in facts.terminals.filter { it.complete == true && it.safe }) {
            val rows = terminal.rowIds
            val witnessed = rows.mapNotNull { id -> raw.singleOrNull { it.id?.toLong() == id } }
            if (witnessed.size == rows.size && rows.isNotEmpty() && terminal.userRowId == rows.first() &&
                terminal.finalRowId == rows.last() && witnessed.first().role == "user" &&
                witnessed.first().display_kind == null && witnessed.last().role == "assistant" &&
                witnessed.count { it.role == "user" } == 1 &&
                (
                    facts.opening != null ||
                        (facts.queued?.userRowId != null && facts.queued?.userRowId == terminal.userRowId)
                )
            ) {
                if (facts.opening ==
                    null
                ) {
                    facts =
                        facts.copy(
                            opening =
                                facts.queued!!.copy(
                                    disposition = "QUEUED_EXECUTION",
                                    userRowId = terminal.userRowId,
                                    rowBindingSource = "complete-manifest",
                                ),
                            deliveryGap = true,
                        )
                }
                val acceptedRoot = facts.opening!!.userRowId
                facts =
                    if (acceptedRoot != null && acceptedRoot != terminal.userRowId) {
                        facts.revoke("opening-binding", acceptedRoot.toString(), terminal.userRowId.toString())
                    } else {
                        facts.copy(
                            opening =
                                facts.opening!!.copy(
                                    userRowId = terminal.userRowId,
                                    rowBindingSource =
                                        facts.opening!!.rowBindingSource ?: "complete-manifest",
                                ),
                        )
                    }
            }
        }
        ledger =
            ledger.update(record.id) {
                it.copy(
                    authority = facts,
                    coverage = it.coverage && facts.revocations.isEmpty(),
                    canonicalWindowVerified =
                        it.canonicalWindowVerified && facts.revocations.isEmpty(),
                )
            }
    }
    val windows = ledger.responses.associate { it.id to verifiedCanonicalWindow(it, ledger, messages, raw) }
    val claims =
        ledger.responses.filter { it.authority.opening != null }.associate { record ->
            record.id to
                (
                    record.authority.terminals.flatMap { it.rowIds } +
                        listOfNotNull(record.authority.opening?.userRowId) +
                        record.authority.controls.mapNotNull { it.userRowId } +
                        windows[record.id].orEmpty() +
                        record.authority.openingObservations.mapNotNull {
                            it.userRowId
                        } +
                        record.authority.canonical
                            ?.rowIds
                            .orEmpty()
                ).toSet()
        }
    val conflicts =
        claims.values
            .flatten()
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    for ((owner, rows) in claims) {
        if (rows.any { it in conflicts }) {
            ledger =
                ledger.update(owner) {
                    it.copy(
                        authority = it.authority.revoke("exclusive-row", rows.intersect(conflicts).toString()),
                        coverage = false,
                        canonicalWindowVerified = false,
                    )
                }
        }
    }
    val consumed = mutableSetOf<Long>()
    val allAddressesById = ledger.canonicalRoles.keys.associateBy { canonicalMessageId(target, session, it) }
    val released = state.stagedCanonicalMessages
    var staged = emptyList<ChatMessage>()
    val visibleCandidates = mapped + released.filter { releasedRow -> mapped.none { it.id == releasedRow.id } }
    // A presentation key can name a real canonical TOOL body, never a receipt row.
    val toolDisplayKeys = selectToolDisplayKeys(state, target, session, mappedWithPositions, raw, ledger, claims)
    // Compare payload versions only AFTER an exact address/alias selected the antecedent.
    // Display projection may replace that physical version, not arbitrary protected prose/cards.
    val physicalSupersededIds =
        mappedWithPositions
            .flatMap { (canonical, position) ->
                val address = addressById[canonical.id]
                val alias = address?.let { ledger.aliases[it.toString()] }
                messages
                    .filter { it.id == canonical.id || (alias != null && it.id == alias) }
                    .filter { old ->
                        address != null &&
                            ledger.aliases.none { (row, id) -> id == old.id && row != address.toString() } &&
                            canonical.supersedesPhysicalSource(old, raw[position])
                    }.map { it.id }
            }.toSet()
    val available = (messages + visibleCandidates).associateBy { it.id }.toMutableMap()
    for ((canonicalId, local) in toolDisplayKeys) {
        val body = available.getValue(canonicalId).withToolPresentation(local)
        available[canonicalId] = body
        available[body.id] = body
    }
    for ((address, alias) in ledger.aliases) {
        val row = address.toLongOrNull() ?: continue
        val id = canonicalMessageId(target, session, row)
        if (id !in available) messages.singleOrNull { it.id == alias }?.let { available[id] = it.copy(id = id) }
    }
    for (record in ledger.responses) {
        val exhaustive = record.authority.terminals.firstOrNull { it.complete == true }
        val rows =
            windows[record.id] ?: exhaustive?.rowIds ?: record.authority.canonical
                ?.rowIds
                .orEmpty()
        val root = record.authority.opening?.userRowId
        val requiredClaims =
            record.authority.terminals.flatMap { it.rowIds } + record.authority.controls.mapNotNull { it.userRowId }
        if (!rows.containsAll(requiredClaims)) continue
        if (rows.isEmpty() || root == null || rows.first() != root ||
            rows.last() !=
            record.authority.terminals
                .mapNotNull { it.finalRowId }
                .firstOrNull() ||
            rows.size != rows.distinct().size || !ledger.canonicalRoles.keys.containsAll(rows) ||
            rows.filter { it in addresses } != addresses.filter { it in rows } ||
            ledger.canonicalRoles[rows.first()] != "user" || ledger.canonicalRoles[rows.last()] != "assistant"
        ) {
            continue
        }
        val canonicalIds =
            rows.filter { it in ledger.canonicalVisibleRows }.map {
                canonicalMessageId(
                    target,
                    session,
                    it,
                )
            }
        if (!available.keys.containsAll(canonicalIds) || canonicalIds.isEmpty()) continue
        val visibleIds = canonicalIds.map { available.getValue(it).id }
        val rootPosition = raw.indexOfFirst { it.id?.toLong() == root }
        val finalPosition = raw.indexOfFirst { it.id?.toLong() == rows.last() }
        val exposedGap =
            rootPosition >= 0 &&
                raw
                    .subList(rootPosition, if (finalPosition > rootPosition) finalPosition + 1 else raw.size)
                    .takeWhile { it === raw[rootPosition] || it.role != "user" || it.display_kind != null }
                    .any { it.role != "user" && it.id?.toLong() !in rows }
        if (exposedGap) {
            ledger =
                ledger.update(record.id) {
                    it.copy(
                        coverage = false,
                        authority =
                            it.authority.copy(
                                local = null,
                                canonical = null,
                                deliveryGap = true,
                                canonicalSuspended = true,
                            ),
                    )
                }
            continue
        }
        if (record.authority.canonicalSuspended && windows[record.id] == null) continue
        val retiringIds = (record.messageIds + record.userIds + visibleIds).toSet()
        val knownReferences =
            messages
                .filter { it.id in retiringIds && it.id !in physicalSupersededIds }
                .flatMap { it.sourceMediaPaths() }
                .toSet()
        val replacementReferences = visibleIds.flatMap { available.getValue(it).sourceMediaPaths() }.toSet()
        if (!replacementReferences.containsAll(knownReferences)) {
            // A complete manifest proves identity, not that a stripped body covers known files.
            // Keep the antecedent readable and use the existing fail-closed coverage state.
            ledger =
                ledger.update(record.id) {
                    it.copy(
                        coverage = false,
                        authority =
                            it.authority.copy(
                                local = null,
                                canonical = null,
                                deliveryGap = true,
                                canonicalSuspended = true,
                            ),
                    )
                }
            continue
        }
        val candidate =
            record.copy(
                authority =
                    record.authority.copy(
                        canonicalSuspended = false,
                        canonical =
                            CoverageEvidence(
                                if (windows[record.id] != null) "canonical-window" else "complete-manifest",
                                visibleIds,
                                rows,
                            ),
                    ),
            )
        ledger = ledger.update(record.id) { candidate }
        if (candidate.authorityDecision(
                available.keys,
                ledger.blocksAuthority(record.id) ||
                    (record.id == ledger.activeId && state.awaitsUserInput()),
            ) !=
            ReplyAuthorityDecision.ReadyCanonical
        ) {
            continue
        }
        val ownedTools =
            messages.filter {
                it.id in record.messageIds && it.role == MessageRole.TOOL &&
                    it.toolCallId.isNotBlank()
            }
        val canonicalTools = visibleIds.map { available.getValue(it) }.filter { it.role == MessageRole.TOOL }
        val replacement =
            visibleIds.map { id ->
                val canonical = available.getValue(id)
                val local =
                    if (canonical.role == MessageRole.TOOL && canonical.toolCallId.isNotBlank() &&
                        canonicalTools.count { it.toolCallId == canonical.toolCallId } == 1
                    ) {
                        ownedTools.singleOrNull { it.toolCallId == canonical.toolCallId }
                    } else {
                        null
                    }
                if (local == null) {
                    canonical
                } else {
                    canonical.copy(
                        toolName = local.toolName ?: canonical.toolName,
                        toolStatus = local.toolStatus?.takeIf { it != ToolStatus.RUNNING } ?: canonical.toolStatus,
                    )
                }
            }
        val previous = (record.messageIds + record.userIds + visibleIds + canonicalIds).toSet()
        val existingSlot = messages.indexOfFirst { it.id in previous }
        // Loss of every old materialization is a coverage gap, not loss of the accepted identity.
        val nextOwnerSlot =
            ledger.responses.dropWhile { it.id != record.id }.drop(1).firstNotNullOfOrNull { next ->
                messages.indexOfFirst { it.id in next.messageIds || it.id in next.userIds }.takeIf { it >= 0 }
            }
        val slot = if (existingSlot >= 0) existingSlot else nextOwnerSlot ?: messages.size
        val before = messages.take(slot).filterNot { it.id in previous }
        val after = messages.drop(slot).filterNot { it.id in previous }
        messages = (before + replacement + after).distinctBy { it.id }
        consumed += rows
        staged = staged.filterNot { it.id in visibleIds || it.id in canonicalIds }
        ledger =
            ledger
                .update(record.id) {
                    it.copy(
                        messageIds = replacement.filter { m -> m.role != MessageRole.USER }.map { m -> m.id },
                        userIds = replacement.filter { m -> m.role == MessageRole.USER }.map { m -> m.id },
                        coverage = true,
                        canonicalRowIds = rows,
                        canonicalWindowVerified = windows[record.id] != null || it.canonicalWindowVerified,
                    )
                }.copy(
                    aliases =
                        ledger.aliases +
                            rows.associate {
                                it.toString() to
                                    (
                                        available[canonicalMessageId(target, session, it)]?.id
                                            ?: canonicalMessageId(target, session, it)
                                    )
                            },
                )
    }
    // Receipt ownership controls metadata/replacement, not whether unrelated server
    // messages may be shown. In particular, another client can append a new reply.
    val ownedIds = ledger.responses.flatMap { it.messageIds + it.userIds }.toSet()
    val quarantined = ledger.responses.filter { it.authority.revocations.isNotEmpty() }
    val protectedIds = quarantined.flatMap { it.messageIds + it.userIds }.toSet()
    val disputedAddresses =
        quarantined
            .flatMap {
                it.canonicalRowIds + it.userRowIds + it.authority.terminals.flatMap { terminal -> terminal.rowIds } +
                    listOfNotNull(it.authority.opening?.userRowId) +
                    it.authority.controls.mapNotNull { control -> control.userRowId } +
                    it.authority.canonical
                        ?.rowIds
                        .orEmpty()
            }.toSet()
    val projectedBySourceId = mutableMapOf<String, ChatMessage>()
    val permittedWithPositions =
        visibleCandidates.filter { allAddressesById[it.id] !in consumed }.map { canonical ->
            val address = allAddressesById[canonical.id]
            val alias = address?.let { ledger.aliases[it.toString()] }
            val local = messages.singleOrNull { it.id == alias } ?: toolDisplayKeys[canonical.id]
            val canonicalAntecedent = messages.singleOrNull { it.id == canonical.id }
            val protectedAntecedents =
                listOfNotNull(local, canonicalAntecedent).filter {
                    it.id in protectedIds || address in disputedAddresses
                }
            val losesSourceReference =
                listOfNotNull(local, canonicalAntecedent).any {
                    it.id !in physicalSupersededIds && !canonical.sourceMediaPaths().containsAll(it.sourceMediaPaths())
                }
            val row =
                if (losesSourceReference ||
                    protectedAntecedents.any {
                        it.id !in physicalSupersededIds && it.retainedBodyVersion() != canonical.retainedBodyVersion()
                    }
                ) {
                    // Quarantine vetoes destructive row upserts as well as whole retirement.
                    // The exact scoped address identifies the row; the fingerprint identifies
                    // its observed payload version, never another equal-text segment.
                    val observedId = disputedBodyId(canonical)
                    val priorObservation = messages.singleOrNull { it.id == observedId }
                    canonical.copy(
                        id = observedId,
                        attachments = canonical.attachments ?: priorObservation?.attachments,
                    )
                } else if (local != null) {
                    (if (canonical.id in toolDisplayKeys) canonical.withToolPresentation(local) else canonical).copy(
                        id = local.id,
                        attachments =
                            if (canonical.hasServerDisplayProjection) {
                                canonical.attachments
                            } else {
                                canonical.attachments
                                    ?: local.attachments
                            },
                        displayKind =
                            if (canonical.hasServerDisplayProjection) {
                                canonical.displayKind
                            } else {
                                canonical.displayKind
                                    ?: local.displayKind
                            },
                        finishTimestamp = local.finishTimestamp,
                    )
                } else {
                    if (canonicalAntecedent?.id in physicalSupersededIds) {
                        canonical.copy(finishTimestamp = canonicalAntecedent?.finishTimestamp)
                    } else {
                        canonical
                    }
                }
            projectedBySourceId[canonical.id] = row
            if (canonical.id in toolDisplayKeys && row.id == local?.id && address != null) {
                ledger = ledger.copy(aliases = ledger.aliases + (address.toString() to row.id))
            }
            Triple(row, rawPositionById[canonical.id], address)
        }
    val permitted = permittedWithPositions.map { it.first }
    for (record in ledger.responses) {
        val members = (record.messageIds + record.userIds).toSet()
        val knownAddresses =
            (
                record.canonicalRowIds + record.userRowIds +
                    listOfNotNull(record.canonicalFinalAssistantRowId) +
                    ledger.aliases
                        .filterValues { it in record.userIds }
                        .keys
                        .mapNotNull { it.toLongOrNull() }
            ).toSet()
        val positions = raw.indices.filter { raw[it].id?.toLong() in knownAddresses }
        val root = positions.firstOrNull { raw[it].role == "user" && raw[it].display_kind == null }
        val possibleSpan =
            if (root != null) {
                val nextUser =
                    (root + 1 until raw.size).firstOrNull {
                        raw[it].role == "user" && raw[it].display_kind == null
                    } ?: raw.size
                val explicitFinal =
                    raw.indices.firstOrNull {
                        raw[it].id?.toLong() == record.canonicalFinalAssistantRowId &&
                            record.canonicalFinalAssistantRowId != null && it > root
                    }
                root..minOf(explicitFinal ?: raw.lastIndex, nextUser - 1)
            } else if (positions.size >= 2) {
                positions.first()..positions.last()
            } else {
                null
            }
        if (permittedWithPositions.any { (row, position, address) ->
                val overlaps =
                    address in record.canonicalRowIds ||
                        (possibleSpan != null && position != null && position in possibleSpan)
                overlaps && row.id !in members && row.role != MessageRole.USER
            }
        ) {
            // Possible overlap is not membership proof. Preserve every body,
            // but withhold prefix-only copy rather than guessing ownership.
            ledger =
                ledger.update(record.id) {
                    it.copy(
                        coverage = false,
                        authority =
                            it.authority.copy(
                                local = null,
                                canonical = null,
                                deliveryGap = true,
                                canonicalSuspended = true,
                            ),
                    )
                }
        }
    }
    if (permitted.any { it.id !in ownedIds && it.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) }) {
        // An existing remote reply also makes a subsequent uncorrelated terminal
        // ambiguous, even when no local response record existed at hydration time.
        ledger = ledger.copy(requiresCorrelation = true)
    }
    val incoming = permitted.associateBy { it.id }
    val ordered = messages.map { incoming[it.id] ?: it }.toMutableList()
    val page =
        visibleCandidates.map { canonical ->
            projectedBySourceId[canonical.id]?.let { return@map it }
            val address = allAddressesById[canonical.id]
            val alias = address?.toString()?.let { ledger.aliases[it] }
            if (alias != null && messages.any { it.id == alias }) canonical.copy(id = alias) else canonical
        }

    fun anchor(row: ChatMessage): IntRange? {
        val exact = ordered.indexOfFirst { it.id == row.id }
        if (exact >= 0) return exact..exact
        val address = allAddressesById[row.id] ?: return null
        val response = ledger.responses.singleOrNull { address in it.canonicalRowIds } ?: return null
        val ids = (response.messageIds + response.userIds).toSet()
        val positions = ordered.indices.filter { ordered[it].id in ids }
        if (positions.isEmpty()) return null
        return positions.first()..positions.last()
    }
    // Weave each genuinely new row into the explicit page order using proven
    // row/response anchors. Never order USER or assistant boundaries by a clock.
    for (row in permitted) {
        if (ordered.any { it.id == row.id }) continue
        val index = page.indexOfFirst { it.id == row.id }
        val before = page.take(index).asReversed().firstNotNullOfOrNull(::anchor)
        val after = page.drop(index + 1).firstNotNullOfOrNull(::anchor)
        val ownedBlock = if (row.role != MessageRole.USER) anchor(row) else null
        val slot =
            if (ownedBlock != null) {
                maxOf(ownedBlock.last + 1, before?.let { it.last + 1 } ?: 0)
            } else {
                before?.let { it.last + 1 } ?: after?.first ?: if (older) 0 else ordered.size
            }
        ordered.add(slot, row)
    }
    val (foldedMessages, foldedLedger) =
        foldUnprovenLocalTurns(
            ordered,
            ledger,
            target,
            session,
            raw,
            older,
            live = state.isAgentTyping || state.responseStatusUnknown,
        )
    return state.copy(messages = foldedMessages, responseLedger = foldedLedger, stagedCanonicalMessages = staged)
}

/**
 * A finished local turn whose stored rows arrived without a complete receipt (interrupted,
 * queued or receipt-less turns) would otherwise show twice: the optimistic bubbles and the same
 * turn from history. Fold local copies into server rows only when:
 * - the turn's user row is PROVEN by an address (guarded ACK row, terminal receipt or exact
 *   alias). Text, position and time never establish which history turn a local turn is; an
 *   unproven local turn stays as an ambiguous observation with its pending input;
 * - each folded message keeps everything it held: a reply folds only into a server row of the
 *   proven span with the same body, reasoning, attachments and display kind (a tool also needs
 *   its unique call id), and nothing local-only is lost.
 * Folded messages are substituted in place, so unfolded local messages (interim commentary, a
 * differently stored reply, cards) keep their order. No reply authority is granted.
 */
private fun foldUnprovenLocalTurns(
    messages: List<ChatMessage>,
    ledger: ResponseLedger,
    target: String,
    session: String,
    raw: List<SessionMessage>,
    older: Boolean,
    live: Boolean,
): Pair<List<ChatMessage>, ResponseLedger> {
    val rows = raw.map { it.id?.toLong()?.takeIf { row -> row > 0 } }
    val positionByRow = rows.withIndex().mapNotNull { (index, row) -> row?.let { it to index } }.toMap()

    fun isOrdinaryUser(position: Int) = raw[position].role == "user" && raw[position].display_kind == null

    fun spanOf(root: Int): IntRange? {
        val end = (root + 1 until raw.size).firstOrNull(::isOrdinaryUser)
        // An older page's last turn may continue on the newer page: never fold half a turn.
        if (end == null && older) return null
        return root until (end ?: raw.size)
    }

    var current = messages
    var result = ledger
    val folded = mutableSetOf<Long>()
    for (initial in ledger.responses) {
        val record = result.responses.singleOrNull { it.id == initial.id } ?: continue
        if (record.authority.revocations.isNotEmpty()) continue // Disputed: keep every body.
        if (record.finishedAt == null && record.id == result.activeId && live) continue // Still running.
        val localUserIds = (record.userIds + result.pendingInputs.filterValues { it == record.id }.keys).toSet()
        val proven =
            (
                record.userRowIds + listOfNotNull(record.authority.opening?.userRowId) +
                    result.aliases
                        .filterValues { it in localUserIds }
                        .keys
                        .mapNotNull { it.toLongOrNull() }
            ).toSet()
        // No address, no identity: never pick a history turn by its words, position or time.
        if (proven.isEmpty()) continue
        val root = proven.mapNotNull { positionByRow[it] }.filter(::isOrdinaryUser).minOrNull() ?: continue
        val span = spanOf(root) ?: continue
        val byId = current.associateBy { it.id }

        fun displayOf(row: Long): String? =
            result.aliases[row.toString()]?.takeIf { it in byId }
                ?: canonicalMessageId(target, session, row).takeIf { it in byId }
        val positionOfDisplay =
            rows.withIndex().mapNotNull { (index, row) -> row?.let(::displayOf)?.let { it to index } }.toMap()

        fun backed(id: String) =
            id in positionOfDisplay || id in result.aliases.values || id.startsWith("canonical:") ||
                id.startsWith("unaddressed:") || id.startsWith("disputed:") || id.startsWith("rest-")
        val otherClaims =
            result.responses
                .filter { it.id != record.id }
                .flatMap { it.claimedRows(result) }
                .toSet()
        val otherMembers =
            result.responses
                .filter { it.id != record.id }
                .flatMap { it.userIds + it.messageIds }
                .toSet()
        if (span.any { rows[it] == null || rows[it] in otherClaims || rows[it] in folded }) continue
        if (span.mapNotNull { rows[it]?.let(::displayOf) }.any { it in otherMembers }) continue
        val substitutes = mutableMapOf<String, ChatMessage>()
        // The proven root replaces the turn's unresolved local user bubble, unless the root is
        // already shown through it (an exact ACK alias) or the bubble holds something the row lacks.
        val rootShown = rows[root]?.let(::displayOf)?.let(byId::get)
        val primary =
            current.firstOrNull {
                it.role == MessageRole.USER && it.id in localUserIds && !backed(it.id) && it.displayKind == null
            }
        if (rootShown != null && rootShown.id !in localUserIds && primary != null &&
            primary.foldsLosslesslyInto(rootShown)
        ) {
            substitutes[primary.id] = rootShown.withLocalPresentation(primary)
        }
        val available =
            span
                .mapNotNull { rows[it]?.let(::displayOf)?.let(byId::get) }
                .filter { it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL }
                .toMutableList()
        val replies =
            current.filter {
                it.id in record.messageIds && (it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL) &&
                    !backed(it.id) && it.approvalInfo == null && it.clarifyInfo == null
            }
        if (replies.any { it.isStreaming || it.toolStatus == ToolStatus.RUNNING }) continue
        for (local in replies) {
            val match =
                available.firstOrNull { server ->
                    local.foldsLosslesslyInto(server) &&
                        (
                            local.role != MessageRole.TOOL ||
                                (
                                    local.toolCallId.isNotBlank() && server.toolCallId == local.toolCallId &&
                                        available.count { it.toolCallId == local.toolCallId } == 1
                                )
                        )
                } ?: continue
            available -= match
            substitutes[local.id] = match.withLocalPresentation(local)
        }
        if (substitutes.isEmpty()) continue
        val absorbedIds = substitutes.values.map { it.id }.toSet()
        current =
            current.mapNotNull { message ->
                substitutes[message.id] ?: message.takeUnless { it.id in absorbedIds }
            }
        val removedIds = substitutes.keys
        val ownedUsers = substitutes.values.filter { it.role == MessageRole.USER }.map { it.id }
        val ownedOthers = substitutes.values.filter { it.role != MessageRole.USER }.map { it.id }
        val ownedRows = substitutes.values.mapNotNull { message -> positionOfDisplay[message.id]?.let { rows[it] } }
        val rootRow = rows[root]
        result =
            result
                .update(record.id) {
                    it.copy(
                        userIds = (it.userIds.filterNot { id -> id in removedIds } + ownedUsers).distinct(),
                        messageIds = (it.messageIds.filterNot { id -> id in removedIds } + ownedOthers).distinct(),
                        userRowIds = (it.userRowIds + ownedRows.filter { row -> row == rootRow }).distinct(),
                        canonicalRowIds = (it.canonicalRowIds + ownedRows.filter { row -> row != rootRow }).distinct(),
                    )
                }.copy(
                    pendingInputs = result.pendingInputs - removedIds,
                    aliases = result.aliases.filterValues { it !in removedIds },
                )
        folded += ownedRows
    }
    return current to result
}

/**
 * True only when [server] keeps everything this local message holds: the same stored body and,
 * when present locally, the same reasoning, attachments and display kind, with no local-only
 * transient data. Equal visible text alone is not proof that nothing is lost.
 */
private fun ChatMessage.foldsLosslesslyInto(server: ChatMessage): Boolean {
    fun normalized(text: String) = text.replace("\r\n", "\n").trim()
    if (role != server.role || normalized(bodyForStorage()).isEmpty()) return false
    if (normalized(bodyForStorage()) != normalized(server.bodyForStorage())) return false
    if (reasoningText.isNotBlank() && normalized(reasoningText) != normalized(server.reasoningText)) return false
    if (displayKind != null && displayKind != server.displayKind) return false
    if (toolCallId.isNotBlank() && toolCallId != server.toolCallId) return false
    if (toolOutputRiskData != null || !progressPreview.isNullOrBlank()) return false
    val kept = server.attachments.orEmpty().map { Triple(it.retainedSourceAddress(), it.name, it.mimeType) }
    return attachments.orEmpty().all { Triple(it.retainedSourceAddress(), it.name, it.mimeType) in kept }
}

/** Local presentation the server row does not carry (tool name/status, finish time). */
private fun ChatMessage.withLocalPresentation(local: ChatMessage): ChatMessage =
    copy(
        toolName = toolName ?: local.toolName,
        toolStatus = toolStatus ?: local.toolStatus,
        finishTimestamp = finishTimestamp ?: local.finishTimestamp,
    )

/** Every row address this response already claims (inputs, receipts, coverage and aliases). */
private fun ResponseRecord.claimedRows(ledger: ResponseLedger): List<Long> =
    canonicalRowIds + userRowIds + listOfNotNull(authority.opening?.userRowId) +
        authority.controls.mapNotNull { it.userRowId } + authority.terminals.flatMap { it.rowIds } +
        authority.canonical?.rowIds.orEmpty() +
        ledger.aliases
            .filterValues { it in userIds || it in messageIds }
            .keys
            .mapNotNull { it.toLongOrNull() }

/** Exact source-version correspondence, not text-based row identity or replacement proof. */
private fun ChatMessage.supersedesPhysicalSource(
    old: ChatMessage,
    source: SessionMessage,
): Boolean {
    if (!hasServerDisplayProjection || role != MessageRole.ASSISTANT || old.role != role ||
        source.role != "assistant" || old.bodyForStorage() != source.contentText
    ) {
        return false
    }
    val paths = HostMediaExtractor.extract(source.contentText).map { it.path }.toSet()
    if (paths.isEmpty()) return false
    // Extra human-supplied cards/metadata remain independent retained data.
    return old.attachments.orEmpty().all {
        val path = it.retainedSourceAddress()
        it.source == AttachmentSource.GATEWAY && path in paths &&
            it.name == mediaNameFromPath(path) && it.mimeType == mediaMimeForPath(path) && it.size == 0L
    }
}

/** Scoped, one-to-one v74 TOOL compatibility. This only chooses a key for an existing raw row. */
private fun selectToolDisplayKeys(
    state: ChatUiState,
    target: String,
    session: String,
    mapped: List<Pair<ChatMessage, Int>>,
    raw: List<SessionMessage>,
    ledger: ResponseLedger,
    claims: Map<String, Set<Long>>,
): Map<String, ChatMessage> {
    if (state.currentSessionId != session) return emptyMap()
    val tools = state.messages.filter { it.role == MessageRole.TOOL }
    val rawTools = raw.filter { it.role == "tool" }

    fun structuredKey(text: String): String? =
        text.trimStart().takeIf { it.startsWith("{") || it.startsWith("[") }?.let(::canonicalToolResultKey)

    fun eligible(
        local: ChatMessage,
        address: Long,
    ): Boolean {
        if (local.id.startsWith("legacy-") || local.approvalInfo != null || local.clarifyInfo != null) return false
        if (ledger.aliases.any { (row, id) -> id == local.id && row != address.toString() }) return false
        val owners = ledger.responses.filter { local.id in it.messageIds || local.id in it.userIds }
        if (owners.size > 1) return false
        return owners.all {
            address in claims[it.id].orEmpty() && it.authority.revocations.isEmpty() &&
                it.authority.opening?.target == target && it.authority.opening?.session == session
        }
    }
    val proposals =
        mapped.mapNotNull { (canonical, position) ->
            if (canonical.role != MessageRole.TOOL || !canonical.hasServerDisplayProjection) return@mapNotNull null
            val source = raw[position]
            val address = source.id?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
            val alias = ledger.aliases[address.toString()]
            val exact = state.messages.filter { it.id == canonical.id || (alias != null && it.id == alias) }
            val local =
                if (exact.isNotEmpty()) {
                    exact.singleOrNull()?.takeIf { it.role == MessageRole.TOOL && eligible(it, address) }
                } else if (alias != null) {
                    null // A dangling or conflicting address must not be rebound through a body hint.
                } else {
                    val candidates =
                        tools.filter {
                            !it.id.startsWith("canonical:") && !it.id.startsWith("rest-") &&
                                !it.id.startsWith("unaddressed:") && !it.id.startsWith("disputed:") &&
                                eligible(it, address)
                        }
                    val call = source.toolCallId
                    val byCall =
                        if (call.isNotBlank() && rawTools.count { it.toolCallId == call } == 1 &&
                            tools.count { it.toolCallId == call } == 1
                        ) {
                            candidates.singleOrNull { it.toolCallId == call }
                        } else {
                            null
                        }
                    if (byCall != null) {
                        byCall
                    } else {
                        val key = structuredKey(source.contentText)
                        val byResult = tools.filter { structuredKey(it.content) == key }
                        byResult.singleOrNull()?.takeIf {
                            key != null && it in candidates && (call.isBlank() || it.toolCallId.isBlank()) &&
                                rawTools.count { structuredKey(it.contentText) == key } == 1 &&
                                (call.isBlank() || tools.none { other -> other.toolCallId == call })
                        }
                    }
                }
            local?.let { canonical.id to it }
        }
    // Even mixed call-ID/result matches may not lend a single presentation to two rows.
    return proposals.filter { (_, local) -> proposals.count { it.second.id == local.id } == 1 }.toMap()
}

private fun ChatMessage.withToolPresentation(local: ChatMessage): ChatMessage =
    preferServerDisplayProjection(this, local).copy(toolCallId = toolCallId.ifBlank { local.toolCallId })

/** Equality checks payload versions only AFTER an exact scoped address has selected a disputed row. */
private fun ChatMessage.retainedBodyVersion(): List<String?> {
    val text = listOf(role.name, bodyForStorage(), reasoningText, displayKind, toolCallId, toolName, toolStatus?.name)
    val sourcePaths = sourceMediaPaths()
    // Source-backed gateway cards are a display projection of this same body. Their runtime
    // credentials/absence after a baseline cold reopen cannot create a new observed version.
    val files =
        attachments.orEmpty().filterNot {
            val path = it.retainedSourceAddress()
            it.source == AttachmentSource.GATEWAY && path in sourcePaths &&
                it.name == mediaNameFromPath(path) && it.mimeType == mediaMimeForPath(path) && it.size == 0L
        }
    if (files.isEmpty()) return text
    return text + listOf("attachments", files.size.toString()) +
        files.flatMap {
            listOf(it.source.name, it.retainedSourceAddress(), it.name, it.mimeType, it.size.toString())
        }
}

/** Gateway credentials refresh independently of the host file identified by `path`. */
private fun Attachment.retainedSourceAddress(): String {
    if (source != AttachmentSource.GATEWAY) return uri
    val transport = gatewayUrl ?: uri
    return runCatching {
        URI(transport)
            .rawQuery
            ?.split('&')
            ?.singleOrNull { it.substringBefore('=') == "path" }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull() ?: transport // Unrecognized sources remain distinct, never discarded.
}

private fun disputedBodyId(canonical: ChatMessage): String {
    val encoded =
        (listOf(canonical.id) + canonical.retainedBodyVersion()).joinToString("") {
            if (it == null) "-1:" else "${it.length}:$it"
        }
    val fingerprint =
        MessageDigest
            .getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    return "disputed:${canonical.id}:$fingerprint"
}

/**
 * Independent coverage proof, not a reinterpretation of receipt.complete=false.
 * An exact normal-input ACK alias and an explicit permitted terminal row bound a
 * single contiguous server page window. Only typed steers may occur inside it.
 */
private fun verifiedCanonicalWindow(
    record: ResponseRecord,
    ledger: ResponseLedger,
    messages: List<ChatMessage>,
    raw: List<SessionMessage>,
): List<Long>? {
    if (record.authority.terminals.isEmpty() || record.authority.terminals.any { !it.safe }) return null
    val final =
        record.authority.terminals
            .mapNotNull { it.finalRowId }
            .firstOrNull() ?: return null
    val root = record.authority.opening?.userRowId ?: return null
    val first = raw.indexOfFirst { it.id?.toLong() == root }
    val last = raw.indexOfFirst { it.id?.toLong() == final }
    if (first < 0 || last <= first || raw[first].role != "user" || raw[first].display_kind != null ||
        raw[last].role != "assistant"
    ) {
        return null
    }
    val window = raw.subList(first, last + 1)
    if (window.any { it.role !in setOf("user", "assistant", "system", "tool") }) return null
    if (window.drop(1).any { it.role == "user" && it.display_kind != "steer" }) return null
    val ids = window.map { it.id?.toLong()?.takeIf { row -> row > 0 } ?: return null }
    if (ids.size != ids.distinct().size) return null
    return ids
}
