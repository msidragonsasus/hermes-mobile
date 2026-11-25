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

/** Shared reconciliation for hydrate, older pages and sync. */
internal fun reconcileTranscript(
    input: ChatUiState,
    target: String,
    session: String,
    raw: List<SessionMessage>,
    offset: Int,
    latest: Boolean,
    older: Boolean = false,
    recoveringReply: ChatMessage? = null,
): ChatUiState {
    val addresses = raw.mapNotNull { it.id?.toLong()?.takeIf { row -> row > 0 } }
    if (addresses.size != addresses.distinct().size) return input
    val queue = reconcileQueuedInputs(input, target, session, raw, latest && offset == 0 && !older)
    val transcript =
        reconcileOrdinaryTranscript(
            queue.state,
            target,
            session,
            raw.filterNot { it.isQueuedPrompt },
            offset,
            latest,
            older,
            recoveringReply,
            queue.repositionIds,
            queue.boundIds,
        )
    val steers = reconcileSteerInputs(queue.state, transcript, target, session, raw.filterNot { it.isQueuedPrompt })
    return if (queue.waiting.isEmpty()) steers else steers.copy(messages = steers.messages + queue.waiting)
}

private fun reconcileOrdinaryTranscript(
    input: ChatUiState,
    target: String,
    session: String,
    raw: List<SessionMessage>,
    offset: Int,
    latest: Boolean,
    older: Boolean,
    recoveringReply: ChatMessage?,
    repositionIds: Set<String>,
    queuedBubbleIds: Set<String>,
): ChatUiState {
    val state = adoptRewrittenRows(input, target, session, raw, newestPage = latest && offset == 0 && !older)
    val addresses = raw.mapNotNull { it.id?.toLong()?.takeIf { row -> row > 0 } }
    // The legacy mapper's latest-mode IDs can collide (row id == null-row
    // index). Use its unique positional IDs only as an internal provenance key;
    // filtering/reasoning folding must not change the raw position association.
    val positionByMapperId = raw.indices.associateBy { "rest-$session-${offset + it}" }
    val reservedIds = (state.messages + state.stagedCanonicalMessages).map { it.id }.toMutableSet()
    // Unique-id lookups (a duplicated id resolves to nothing, like singleOrNull); linear scans per
    // row made a resume reconcile take seconds on the main thread.
    val stateIndex = state.messages.uniqueById()
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
            // History may omit streamed reasoning. An exact scoped row/ACK alias lets us
            // retain it without using the reasoning or prose to establish identity.
            val alias = row?.let { state.responseLedger.aliases[it.toString()] }
            val antecedent = alias?.let(stateIndex::get) ?: stateIndex[id]
            val body = message.copy(id = id, sourceBody = source).withRetainedReasoning(antecedent)
            val retained =
                if (antecedent != null && alias in queuedBubbleIds &&
                    body.role == MessageRole.USER && !body.hasServerDisplayProjection &&
                    body.content == antecedent.content
                ) {
                    body.copy(
                        sourceBody = antecedent.sourceBody,
                        attachments =
                            (body.attachments.orEmpty() + antecedent.attachments.orEmpty())
                                .distinct()
                                .takeIf { it.isNotEmpty() },
                    )
                } else {
                    body
                }
            retained to position
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
    val windows = ledger.responses.associate { it.id to verifiedCanonicalWindow(it, target, session, raw) }
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
    messages = coalesceAcknowledgedUsers(messages, stateIndex, mapped, addressById, ledger)
    // A presentation key can name a real canonical TOOL body, never a receipt row.
    val toolDisplayKeys = selectToolDisplayKeys(state, target, session, mappedWithPositions, raw, ledger, claims)
    // Compare payload versions only AFTER an exact address/alias selected the antecedent.
    // Display projection may replace that physical version, not arbitrary protected prose/cards.
    val messagesById = messages.groupBy { it.id }
    val physicalSupersededIds =
        mappedWithPositions
            .flatMap { (canonical, position) ->
                val address = addressById[canonical.id]
                val alias = address?.let { ledger.aliases[it.toString()] }
                (
                    messagesById[canonical.id].orEmpty() +
                        alias?.takeIf { it != canonical.id }?.let(messagesById::get).orEmpty()
                ).filter { old ->
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
    val aliasIndex = messages.uniqueById()
    for ((address, alias) in ledger.aliases) {
        val row = address.toLongOrNull() ?: continue
        val id = canonicalMessageId(target, session, row)
        if (id !in available) aliasIndex[alias]?.let { available[id] = it.copy(id = id) }
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
                    .takeWhile {
                        finalPosition > rootPosition || it === raw[rootPosition] || it.role != "user" ||
                            it.display_kind != null || it.id?.toLong() in rows
                    }.any { it.id?.toLong() !in rows }
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
        val preserveControlInputs =
            record.authority.controls.isNotEmpty() ||
                record.authority.terminals.any { it.userRowIds.orEmpty().size > 1 }
        val knownReferences =
            messages
                .filter { it.id in retiringIds && it.id !in physicalSupersededIds }
                .flatMap { it.sourceMediaPaths() }
                .toSet()
        val replacementReferences = visibleIds.flatMap { available.getValue(it).sourceMediaPaths() }.toSet()
        if (!preserveControlInputs && !replacementReferences.containsAll(knownReferences)) {
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
        val recordMessageIds = record.messageIds.toHashSet()
        val ownedTools =
            messages.filter {
                it.id in recordMessageIds && it.role == MessageRole.TOOL &&
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
        val (presentation, presentationAliases) =
            if (!preserveControlInputs) {
                replacement to emptyMap()
            } else {
                controlledTurnPresentation(
                    record,
                    ledger,
                    messages,
                    replacement,
                    rows.filter { it in ledger.canonicalVisibleRows },
                )
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
        messages = (before + presentation + after).distinctBy { it.id }
        consumed += rows
        staged = staged.filterNot { it.id in visibleIds || it.id in canonicalIds }
        ledger =
            ledger
                .update(record.id) {
                    it.copy(
                        messageIds = presentation.filter { m -> m.role != MessageRole.USER }.map { m -> m.id },
                        userIds = presentation.filter { m -> m.role == MessageRole.USER }.map { m -> m.id },
                        coverage = true,
                        canonicalRowIds = rows,
                        canonicalWindowVerified = windows[record.id] != null || it.canonicalWindowVerified,
                        authority =
                            it.authority.copy(
                                canonical =
                                    it.authority.canonical?.copy(
                                        memberIds =
                                            canonicalIds.zip(visibleIds).map { (id, displayed) ->
                                                presentationAliases[allAddressesById[id]] ?: displayed
                                            },
                                    ),
                            ),
                    )
                }.copy(
                    aliases =
                        ledger.aliases +
                            rows.associate {
                                it.toString() to
                                    (
                                        presentationAliases[it]
                                            ?: available[canonicalMessageId(target, session, it)]?.id
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
    val messageIndex = messages.uniqueById()
    val permittedWithPositions =
        visibleCandidates.filter { allAddressesById[it.id] !in consumed }.map { canonical ->
            val address = allAddressesById[canonical.id]
            val alias = address?.let { ledger.aliases[it.toString()] }
            val local = alias?.let(messageIndex::get) ?: toolDisplayKeys[canonical.id]
            val canonicalAntecedent = messageIndex[canonical.id]
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
                    val priorObservation = messageIndex[observedId]
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
    val historyAnswer =
        if (older) {
            ledger.approvalHistoryAnswer
        } else {
            activeApprovalHistoryAnswer(state, ledger, raw, permittedWithPositions)
        }
    val historyAnswerId = historyAnswer?.messageId
    if (permitted.any {
            it.id !in ownedIds && it.id != historyAnswerId &&
                it.role in setOf(MessageRole.USER, MessageRole.ASSISTANT)
        }
    ) {
        // An existing remote reply also makes a subsequent uncorrelated terminal
        // ambiguous, even when no local response record existed at hydration time.
        ledger = ledger.copy(requiresCorrelation = true)
    }
    val incoming = permitted.associateBy { it.id }
    val ordered =
        messages
            .filterNot { it.id in repositionIds && it.id in incoming }
            .map { incoming[it.id] ?: it }
            .toMutableList()
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
    // The running turn's remaining rows stay above a discarded input. The next user turn starts below it.
    val discardedQueueBoundaries =
        ledger.queuedTranscriptInputs
            .filter { it.discarded }
            .mapNotNull { entry ->
                raw
                    .firstOrNull {
                        it.role == "user" && it.display_kind == null && !it.isModelOnly &&
                            (it.id?.toLong() ?: Long.MIN_VALUE) > entry.rowId
                    }?.id
                    ?.toLong()
                    ?.let { entry.bubbleId to it }
            }.toMap()
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
        // A discarded queued input has no history row to anchor it. Later rows stay below it.
        var placed = slot
        allAddressesById[row.id]?.let { address ->
            while (placed < ordered.size &&
                discardedQueueBoundaries[ordered[placed].id]?.let { it <= address } == true
            ) {
                placed++
            }
        }
        ordered.add(placed, row)
    }
    val (foldedMessages, foldedLedger) =
        foldUnprovenLocalTurns(
            ordered,
            ledger,
            target,
            session,
            raw,
            older,
            live = state.isAgentTyping || state.responseStatusUnknown || state.awaitsUserInput(),
            recoveringReply = recoveringReply,
        )
    val visible =
        if (latest && offset == 0 && !older && !state.isAgentTyping && !state.responseStatusUnknown) {
            dropAbsorbedOrphans(foldedMessages, foldedLedger)
        } else {
            foldedMessages
        }
    val toolAnswerIds = recoveredToolAnswerIds(page.subList(0, mapped.size), visible, foldedLedger)
    val serverTimes =
        permittedWithPositions.mapNotNull { (message, position, _) ->
            position?.let { raw[it].timestampEpochMs }?.let { message.id to it }
        }.toMap()
    val queuePositions = foldedLedger.queuedTranscriptInputs.mapTo(HashSet()) { it.bubbleId } + queuedBubbleIds
    return state.copy(
        messages = placeUnmatchedCachedRows(visible, serverTimes, foldedLedger.aliases.values.toSet(), queuePositions),
        responseLedger = foldedLedger.copy(toolAnswerIds = toolAnswerIds, approvalHistoryAnswer = historyAnswer),
        stagedCanonicalMessages = staged,
    )
}

/**
 * Page weaving establishes server order through row and response anchors. A cached bubble
 * without a server slot can still be stranded below a newer page row. Use its timestamp
 * for placement only, preserving the order of both the server rows and the local bubbles.
 */
private fun placeUnmatchedCachedRows(
    messages: List<ChatMessage>,
    serverTimes: Map<String, Long>,
    aliasedIds: Set<String>,
    queuedBubbleIds: Set<String>,
): List<ChatMessage> {
    fun isServerRow(message: ChatMessage) =
        message.id in serverTimes || message.id in aliasedIds || message.id.startsWith("canonical:") ||
            message.id.startsWith("unaddressed:") || message.id.startsWith("disputed:") ||
            message.id.startsWith("rest-")

    val ordered = messages.toMutableList()
    var localBoundary = 0
    for (index in ordered.indices) {
        val local = ordered[index]
        if (isServerRow(local)) continue
        if (local.id !in queuedBubbleIds && local.displayKind != "steer" && local.timestamp > 0) {
            val slot = (localBoundary until index).firstOrNull {
                serverTimes[ordered[it].id]?.let { timestamp -> timestamp > local.timestamp } == true
            }
            if (slot != null) {
                ordered.removeAt(index)
                ordered.add(slot, local)
                localBoundary = slot + 1
                continue
            }
        }
        localBoundary = index + 1
    }
    return ordered
}

/**
 * A running turn can see its stored answer before the socket terminal. Scope the preview to the
 * acknowledged user row and a shared unique tool call, without conferring receipt authority.
 */
private fun activeApprovalHistoryAnswer(
    state: ChatUiState,
    ledger: ResponseLedger,
    raw: List<SessionMessage>,
    permitted: List<Triple<ChatMessage, Int?, Long?>>,
): ApprovalHistoryAnswer? {
    val active = ledger.responses.singleOrNull { it.id == ledger.activeId && it.finishedAt == null } ?: return null
    if (active.authority.revocations.isNotEmpty() ||
        ledger.approvalPlacements.values.none { it.responseId == active.id }
    ) {
        return null
    }
    val root = raw.indexOfLast { it.role == "user" && it.display_kind == null }
    if (root < 0) return null
    val userRow = raw[root].id?.toLong() ?: return null
    if (userRow !in active.userRowIds && userRow != active.authority.opening?.userRowId) return null
    val (answer, position, rowId) = permitted.lastOrNull { it.second == raw.lastIndex } ?: return null
    if (position == null || position <= root || rowId == null || answer.role != MessageRole.ASSISTANT ||
        answer.displayKind != null || answer.hasServerDisplayProjection || answer.content.isBlank() ||
        !answer.id.startsWith("canonical:") || answer.id in active.messageIds ||
        ledger.responses.any { it.id != active.id && (rowId in it.claimedRows(ledger) || answer.id in it.messageIds) }
    ) {
        return null
    }
    return ApprovalHistoryAnswer(active.id, answer.id).takeIf {
        sharesUniqueToolCall(state.messages, active.messageIds, raw.subList(root + 1, position))
    }
}

private fun sharesUniqueToolCall(
    messages: List<ChatMessage>,
    memberIds: Collection<String>,
    history: List<SessionMessage>,
): Boolean {
    val calls =
        history
            .filter { it.role == "tool" && it.toolCallId.isNotBlank() }
            .groupingBy { it.toolCallId }
            .eachCount()
    val cachedCalls =
        messages
            .filter {
                it.role == MessageRole.TOOL && it.toolCallId in calls
            }.groupBy { it.toolCallId }
    return calls.any { (call, count) -> count == 1 && cachedCalls[call]?.singleOrNull()?.id in memberIds }
}

/** Recover presentation links from the server order, independently of receipt ownership. */
private fun recoveredToolAnswerIds(
    page: List<ChatMessage>,
    visible: List<ChatMessage>,
    ledger: ResponseLedger,
): Map<String, String> {
    if (ledger.responses.isEmpty()) return emptyMap()
    val visibleIds = visible.mapTo(HashSet()) { it.id }
    val owners = ledger.responses.flatMap { response -> response.messageIds.map { it to response.id } }.toMap()

    fun canLink(
        tool: String,
        answer: String,
    ): Boolean =
        tool in visibleIds && answer in visibleIds && owners[answer] != null &&
            (owners[tool] == null || owners[tool] == owners[answer])

    val links =
        ledger.toolAnswerIds.filterTo(mutableMapOf()) { (tool, answer) ->
            canLink(tool, answer)
        }
    var answerId: String? = null
    for (row in page.asReversed()) {
        if (row.role == MessageRole.TOOL && row.displayKind == null) {
            if (answerId != null && canLink(row.id, answerId) && !row.id.startsWith("disputed:")) {
                links[row.id] = answerId
            }
        } else {
            answerId =
                row.id.takeIf {
                    row.role == MessageRole.ASSISTANT && row.displayKind == null &&
                        it in owners && it in visibleIds && !it.startsWith("disputed:")
                }
        }
    }
    return links
}

/**
 * A streamed reply that never got tied to a turn (its completion was lost, or it was pushed while
 * the socket reconnected) stays as an ownerless local bubble after the server row arrives. On the
 * newest page such a bubble is dropped only when a server row of the same role already holds its
 * whole body (equal, or the bubble is a cut-off prefix) and is not older than it, so nothing is lost.
 */
private fun dropAbsorbedOrphans(
    messages: List<ChatMessage>,
    ledger: ResponseLedger,
): List<ChatMessage> {
    val owned = ledger.responses.flatMap { it.messageIds + it.userIds }.toHashSet() + ledger.aliases.values

    fun normalized(text: String) = text.replace("\r\n", "\n").trim()
    val servers =
        messages
            .filter { it.id.startsWith("canonical:") && it.role == MessageRole.ASSISTANT }
            .map { it to normalized(it.content) }
            .filter { (_, body) -> body.isNotEmpty() }
    val absorbed = mutableSetOf<String>()
    val used = mutableSetOf<String>()
    for (local in messages) {
        if (local.role != MessageRole.ASSISTANT || local.id in owned || local.isStreaming) continue
        // Only live stream bubbles (random UUID ids): server-derived or legacy ids, whose
        // provenance is unknown, are never matched by text.
        if (!LIVE_BUBBLE_ID.matches(local.id)) continue
        if (local.approvalInfo != null || local.clarifyInfo != null || !local.attachments.isNullOrEmpty()) continue
        if (local.displayKind != null || local.toolOutputRiskData != null) continue
        val body = normalized(local.content)
        if (body.isEmpty()) continue
        val server =
            servers.firstOrNull { (server, serverBody) ->
                server.id !in used && server.timestamp >= local.timestamp - ORPHAN_CLOCK_SKEW_MS &&
                    serverBody.startsWith(body)
            } ?: continue
        used += server.first.id
        absorbed += local.id
    }
    return if (absorbed.isEmpty()) messages else messages.filterNot { it.id in absorbed }
}

private val LIVE_BUBBLE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Device and server clocks differ; a server copy may carry a slightly earlier timestamp. */
private const val ORPHAN_CLOCK_SKEW_MS = 10 * 60 * 1000L

/**
 * Context compression re-inserts the kept transcript under NEW row ids and deactivates the old
 * rows (same `message_uid`, same content and timestamp). Row identity alone then shows every
 * rewritten message twice: the cached old row and the new one. Before reconciling:
 * - a row whose uid was seen under an older row id takes that older identity's place
 *   (messages, aliases and every receipt/authority reference move to the new row id);
 * - a turn that was submitted before the rewrite and completed after it has an opening row
 *   that no longer exists; when its complete receipt is fully witnessed on the newest page and
 *   that page also holds rows older than the opening row, the opening row is the rewritten
 *   user row and is rebound to the receipt's user row instead of quarantining the turn;
 * - on the newest page, a cached server row that lies between returned rows but is no longer
 *   returned was deactivated by a rewrite or rewind and is dropped.
 */
private fun adoptRewrittenRows(
    state: ChatUiState,
    target: String,
    session: String,
    raw: List<SessionMessage>,
    newestPage: Boolean,
): ChatUiState {
    val rawRows = raw.mapNotNull { it.id?.toLong()?.takeIf { row -> row > 0 } }.toSet()
    if (rawRows.isEmpty()) return state
    val uidByRow =
        raw
            .mapNotNull { row ->
                val id = row.id?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
                row.message_uid?.takeIf(String::isNotBlank)?.let { id to it }
            }.toMap()
    var ledger = state.responseLedger
    val remap = mutableMapOf<Long, Long>()
    for ((row, uid) in uidByRow) {
        ledger.canonicalUids
            .filter { (old, oldUid) -> oldUid == uid && old != row && old !in rawRows }
            .keys
            .forEach { remap[it] = row }
    }
    if (newestPage) {
        val lowest = rawRows.min()
        for (record in ledger.responses) {
            val opening = record.authority.opening?.userRowId ?: continue
            if (opening in rawRows || opening <= lowest || opening in remap) continue
            val terminal =
                record.authority.terminals.firstOrNull {
                    it.complete == true && it.safe && it.userRowId != null && it.userRowId != opening &&
                        it.rowIds.isNotEmpty() && rawRows.containsAll(it.rowIds)
                } ?: continue
            val user = terminal.userRowId ?: continue
            if (raw.singleOrNull { it.id?.toLong() == user }?.let { it.role == "user" && it.display_kind == null } !=
                true
            ) {
                continue
            }
            remap[opening] = user
        }
    }

    fun moved(row: Long) = remap[row] ?: row

    // References are row ids or row-id lists rendered as text ("10", "[10, 11]").
    val rowListRef = Regex("""\[?\d+(, \d+)*]?""")

    fun movedRef(ref: String) =
        if (!rowListRef.matches(ref)) {
            ref
        } else {
            Regex("""\d+""").replace(ref) { m ->
                m.value
                    .toLongOrNull()
                    ?.let(remap::get)
                    ?.toString() ?: m.value
            }
        }

    fun rowsOf(ref: String) =
        if (!rowListRef.matches(ref)) {
            emptyList()
        } else {
            Regex("""\d+""").findAll(ref).mapNotNull { it.value.toLongOrNull() }.toList()
        }
    var messages = state.messages
    if (remap.isNotEmpty()) {
        val renamed =
            remap.entries.associate { (old, new) ->
                canonicalMessageId(target, session, old) to canonicalMessageId(target, session, new)
            }
        val present = messages.map { it.id }.toSet()
        messages =
            messages.mapNotNull { message ->
                val next = renamed[message.id] ?: return@mapNotNull message
                if (next in present) null else message.copy(id = next)
            }

        fun facts(f: ReplyAuthorityFacts): ReplyAuthorityFacts {
            val opening = f.opening?.let { it.copy(userRowId = it.userRowId?.let(::moved)) }
            val terminals =
                f.terminals.map {
                    it.copy(
                        rowIds = it.rowIds.map(::moved).distinct(),
                        userRowId = it.userRowId?.let(::moved),
                        finalRowId = it.finalRowId?.let(::moved),
                        userRowIds = it.userRowIds?.map(::moved)?.distinct(),
                    )
                }
            // A binding or exhaustiveness conflict that only existed between a row and its
            // rewritten copy is gone once both name the same row.
            val witnessed = terminals.filter { it.complete == true && it.safe }.flatMap { it.rowIds }.toSet()
            val revocations =
                f.revocations
                    .map { it.copy(references = it.references.map(::movedRef)) }
                    .filterNot { revocation ->
                        val rows = revocation.references.flatMap(::rowsOf)
                        when (revocation.reason) {
                            "opening-binding" -> {
                                rows.size == revocation.references.size && rows.size >= 2 &&
                                    rows.distinct().size == 1
                            }

                            "complete-exhaustiveness" -> {
                                rows.isNotEmpty() && witnessed.containsAll(rows)
                            }

                            else -> {
                                false
                            }
                        }
                    }
            return f.copy(
                opening = opening,
                openingObservations = f.openingObservations.map { it.copy(userRowId = it.userRowId?.let(::moved)) },
                controls = f.controls.map { it.copy(userRowId = it.userRowId?.let(::moved)) },
                queued = f.queued?.let { it.copy(userRowId = it.userRowId?.let(::moved)) },
                terminals = terminals,
                local = f.local?.let { it.copy(rowIds = it.rowIds.map(::moved).distinct()) },
                canonical = f.canonical?.let { it.copy(rowIds = it.rowIds.map(::moved).distinct()) },
                revocations = revocations,
            )
        }
        ledger =
            ledger.copy(
                responses =
                    ledger.responses.map { record ->
                        record.copy(
                            messageIds = record.messageIds.map { renamed[it] ?: it }.distinct(),
                            userIds = record.userIds.map { renamed[it] ?: it }.distinct(),
                            userRowIds = record.userRowIds.map(::moved).distinct(),
                            canonicalRowIds = record.canonicalRowIds.map(::moved).distinct(),
                            canonicalFinalAssistantRowId = record.canonicalFinalAssistantRowId?.let(::moved),
                            authority = facts(record.authority),
                        )
                    },
                aliases =
                    ledger.aliases.entries
                        .sortedBy { (row, _) -> if (row.toLongOrNull() in remap) 1 else 0 }
                        .associate { (row, id) -> movedRef(row) to (renamed[id] ?: id) },
                canonicalRoles = ledger.canonicalRoles.mapKeys { (row, _) -> moved(row) },
                canonicalVisibleRows = ledger.canonicalVisibleRows.map(::moved).distinct(),
                canonicalUids = ledger.canonicalUids.filterKeys { it !in remap },
                pendingInputs = ledger.pendingInputs,
            )
    }
    if (newestPage && rewriteSeen(raw)) {
        val lowest = rawRows.min()
        val highest = rawRows.max()

        fun gone(row: Long) = row in lowest..highest && row !in rawRows
        val dead = mutableSetOf<String>()
        messages.forEach { message ->
            if (canonicalRowOf(message.id, target, session)?.let(::gone) == true) dead += message.id
        }
        // A local bubble shown for a row (exact ACK/alias) goes with that row.
        val goneAliases = ledger.aliases.filterKeys { it.toLongOrNull()?.let(::gone) == true }
        dead += goneAliases.values
        // A turn whose user row was rewritten or rewound away keeps no local copy:
        // the newest page already holds its current rows (or the turn was removed).
        // A turn counts as running only while a reply is actually streaming; a turn whose
        // completion was never received (socket dropped, app backgrounded) is not running.
        val live = state.isAgentTyping || state.responseStatusUnknown
        for (record in ledger.responses) {
            if (live && (record.finishedAt == null || record.id == ledger.activeId)) continue
            val userRows = record.userRowIds + listOfNotNull(record.authority.opening?.userRowId)
            if (userRows.isEmpty() || !userRows.all(::gone)) continue
            dead += record.userIds + record.messageIds
        }
        val liveIds = rawRows.map { canonicalMessageId(target, session, it) }.toSet()
        dead.removeAll(liveIds)
        // A drain can retain an accept row's old ID as a presentation key.
        val drainedIds =
            ledger.queuedTranscriptInputs.filter { it.replacementRowId != null }.mapTo(
                HashSet(),
            ) { it.bubbleId }
        dead.removeAll(
            ledger.aliases
                .filterKeys { it.toLongOrNull() in rawRows }
                .values
                .filter { it in drainedIds }
                .toSet(),
        )
        if (dead.isNotEmpty()) {
            messages = messages.filterNot { it.id in dead }
            ledger =
                ledger.copy(
                    aliases = ledger.aliases - goneAliases.keys,
                    pendingInputs = ledger.pendingInputs - dead,
                    responses =
                        ledger.responses.map {
                            it.copy(
                                userIds = it.userIds.filterNot { id -> id in dead },
                                messageIds = it.messageIds.filterNot { id -> id in dead },
                            )
                        },
                )
        }
    }
    ledger = ledger.copy(canonicalUids = ledger.canonicalUids + uidByRow)
    return if (messages === state.messages && ledger == state.responseLedger) {
        state
    } else {
        state.copy(messages = messages, responseLedger = ledger)
    }
}

/**
 * Rows are inserted in time order, so on a page sorted by row id the timestamps only move
 * forward. A compression rewrite re-inserts kept messages with their ORIGINAL timestamps after
 * newer rows; a clear step back in time is the visible trace that rows were rewritten, and only
 * then may a missing row inside the page be treated as deactivated.
 */
private fun rewriteSeen(raw: List<SessionMessage>): Boolean {
    var newest: Long? = null
    for (row in raw.filter { (it.id ?: 0) > 0 }.sortedBy { it.id }) {
        val at = row.timestampEpochMs ?: continue
        if (newest != null && at < newest - REWRITE_CLOCK_STEP_MS) return true
        newest = maxOf(newest ?: at, at)
    }
    return false
}

/** A step back in row time larger than ordinary clock jitter. */
private const val REWRITE_CLOCK_STEP_MS = 60 * 1000L

/** The row of a [canonicalMessageId] for this target and session, or null. */
private fun canonicalRowOf(
    id: String,
    target: String,
    session: String,
): Long? {
    val prefix = canonicalMessageId(target, session, 0).substringBeforeLast(":") + ":"
    if (!id.startsWith(prefix)) return null
    return runCatching {
        String(Base64.getUrlDecoder().decode(id.removePrefix(prefix)), Charsets.UTF_8).toLong()
    }.getOrNull()
}

/**
 * A finished local turn whose stored rows arrived without a complete receipt (interrupted,
 * queued or receipt-less turns) would otherwise show twice: the optimistic bubbles and the same
 * turn from history. Fold local copies into server rows only when:
 * - the user row has an exact address (ACK, receipt or alias), or a receipt-less serial follow-up
 *   matches the complete history turn immediately after its addressed predecessor;
 * - each folded message keeps everything it held: a reply folds only into a server row of the
 *   proven span with the same body, compatible reasoning, attachments and display kind (a tool
 *   also needs its unique call id). Missing unprojected history reasoning is retained from the local row.
 * Proven busy rows take their history slots. Other folds preserve the order of unfolded local
 * messages (interim commentary, a differently stored reply, cards). No reply authority is granted.
 */
private fun foldUnprovenLocalTurns(
    messages: List<ChatMessage>,
    ledger: ResponseLedger,
    target: String,
    session: String,
    raw: List<SessionMessage>,
    older: Boolean,
    live: Boolean,
    recoveringReply: ChatMessage?,
): Pair<List<ChatMessage>, ResponseLedger> {
    val rows = raw.map { it.id?.toLong()?.takeIf { row -> row > 0 } }
    val positionByRow = rows.withIndex().mapNotNull { (index, row) -> row?.let { it to index } }.toMap()

    fun isOrdinaryUser(position: Int) = raw[position].role == "user" && raw[position].display_kind == null

    fun spanOf(root: Int): IntRange? {
        val end =
            (root + 1 until raw.size).firstOrNull {
                isOrdinaryUser(it) || (raw[it].role == "user" && raw[it].display_kind == "internal_notification")
            }
        // An older page's last turn may continue on the newer page: never fold half a turn.
        if (end == null && older) return null
        return root until (end ?: raw.size)
    }

    var current = messages
    var result = ledger
    val folded = mutableSetOf<Long>()
    var claimsVersion: ResponseLedger? = null
    var claimsByRecord: Map<String, Set<Long>> = emptyMap()
    var membersByRecord: Map<String, Set<String>> = emptyMap()
    var claimCounts: Map<Long, Int> = emptyMap()
    var memberCounts: Map<String, Int> = emptyMap()
    for ((index, initial) in ledger.responses.withIndex()) {
        val record = result.responses.singleOrNull { it.id == initial.id } ?: continue
        if (record.authority.revocations.isNotEmpty()) continue // Disputed: keep every body.
        val hasApproval = result.approvalPlacements.values.any { it.responseId == record.id }
        val running = record.finishedAt == null && record.id == result.activeId && live
        if (running && !hasApproval && recoveringReply == null) continue
        val localUserIds = (record.userIds + result.pendingInputs.filterValues { it == record.id }.keys).toSet()
        val proven =
            (
                record.userRowIds + listOfNotNull(record.authority.opening?.userRowId) +
                    result.aliases
                        .filterValues { it in localUserIds }
                        .keys
                        .mapNotNull { it.toLongOrNull() }
            ).toSet()
        if (proven.isEmpty() && (older || !record.serverFollowUp || record.finishedAt == null)) continue
        val byId = current.associateBy { it.id }

        fun displayOf(row: Long): String? =
            result.aliases[row.toString()]?.takeIf { it in byId }
                ?: canonicalMessageId(target, session, row).takeIf { it in byId }

        val root =
            proven.mapNotNull { positionByRow[it] }.filter(::isOrdinaryUser).minOrNull()
                ?: if (proven.isEmpty()) {
                    legacyFollowUpRoot(
                        record,
                        result.responses.getOrNull(index - 1),
                        target,
                        session,
                        raw,
                        byId,
                    ) { position -> rows[position]?.let(::displayOf)?.let(byId::get) }
                } else {
                    null
                } ?: continue
        val span = spanOf(root) ?: continue
        val positionOfDisplay =
            rows.withIndex().mapNotNull { (index, row) -> row?.let(::displayOf)?.let { it to index } }.toMap()

        fun backed(id: String) =
            id in positionOfDisplay || id in result.aliases.values || id.startsWith("canonical:") ||
                id.startsWith("unaddressed:") || id.startsWith("disputed:") || id.startsWith("rest-")
        // Counted once per ledger version: rebuilding every other record's claims for every
        // record made a resume reconcile quadratic in the turn history (seconds on the main thread).
        if (claimsVersion !== result) {
            claimsVersion = result
            claimsByRecord = result.responses.associate { it.id to it.claimedRows(result).toHashSet() }
            membersByRecord = result.responses.associate { it.id to (it.userIds + it.messageIds).toHashSet() }
            claimCounts =
                HashMap<Long, Int>().also { counts ->
                    claimsByRecord.values.forEach { claims -> claims.forEach { counts.merge(it, 1, Int::plus) } }
                }
            memberCounts =
                HashMap<String, Int>().also { counts ->
                    membersByRecord.values.forEach { members -> members.forEach { counts.merge(it, 1, Int::plus) } }
                }
        }
        val ownClaims = claimsByRecord[record.id].orEmpty()
        val ownMembers = membersByRecord[record.id].orEmpty()

        fun claimedByOther(row: Long) = (claimCounts[row] ?: 0) - (if (row in ownClaims) 1 else 0) > 0

        fun memberOfOther(id: String) = (memberCounts[id] ?: 0) - (if (id in ownMembers) 1 else 0) > 0
        if (span.any { rows[it] == null || claimedByOther(rows[it]!!) || rows[it] in folded }) continue
        if (span.mapNotNull { rows[it]?.let(::displayOf) }.any(::memberOfOther)) continue
        val substitutes = mutableMapOf<String, ChatMessage>()
        // The proven root replaces the turn's unresolved local user bubble, unless the root is
        // already shown through it (an exact ACK alias) or the bubble holds something the row lacks.
        val rootShown = rows[root]?.let(::displayOf)?.let(byId::get)
        val primary =
            current.firstOrNull {
                it.role == MessageRole.USER && it.id in localUserIds && !backed(it.id) && it.displayKind == null
            }
        if (!running && rootShown != null && rootShown.id !in localUserIds && primary != null &&
            primary.foldsLosslesslyInto(rootShown)
        ) {
            substitutes[primary.id] = rootShown.withLocalPresentation(primary)
        }
        val available =
            span
                .mapNotNull { rows[it]?.let(::displayOf)?.let(byId::get) }
                .filter { it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL }
                .toMutableList()
        val storedFinal =
            available.lastOrNull()?.takeIf {
                it.role == MessageRole.ASSISTANT && it.displayKind == null && !it.hasServerDisplayProjection &&
                    positionOfDisplay[it.id] == span.last
            }
        if (running && !hasApproval) {
            val preview = recoveringReply ?: continue
            val finalRow = rows[span.last] ?: continue
            if (storedFinal != null && LIVE_BUBBLE_ID.matches(preview.id) && !memberOfOther(preview.id) &&
                !backed(preview.id) && storedFinal.id == canonicalMessageId(target, session, finalRow) &&
                preview.isCoveredByStoredReply(storedFinal) &&
                available.count { preview.isCoveredByStoredReply(it) } == 1
            ) {
                // Bind the visible preview to the stored final inside the addressed active turn.
                // Its stable id lets the ViewModel retire the stream without claiming a terminal.
                val recovered = storedFinal.withLocalPresentation(preview).copy(id = preview.id)
                current =
                    current.filterNot { it.id == preview.id }.map {
                        if (it.id == storedFinal.id) recovered else it
                    }
                result =
                    result.observe(listOf(preview.id)).copy(
                        aliases =
                            result.aliases + (finalRow.toString() to preview.id),
                    )
                folded += finalRow
            }
            continue
        }
        val replies =
            current.filter {
                it.id in record.messageIds && (it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL) &&
                    !backed(it.id) && it.approvalInfo == null && it.clarifyInfo == null
            }
        if (!hasApproval && replies.any { it.isStreaming || it.toolStatus == ToolStatus.RUNNING }) continue
        val shareApprovalTrace =
            hasApproval && record.finishedAt != null &&
                sharesUniqueToolCall(current, ownMembers, raw.subList(span.first, span.last + 1))
        val localFinal = replies.lastOrNull { it.role == MessageRole.ASSISTANT }
        // Live streaming attaches the turn's reasoning to the final answer; history stores it on
        // the row that produced it (often the tool-call row). Within this proven span that trace
        // is kept, so it does not make the answer look lossy.
        val spanTrace =
            span
                .filter { raw[it].role == "assistant" }
                .map { raw[it].reasoningText }
                .filter { it.isNotBlank() }

        fun compact(text: String) = text.filterNot(Char::isWhitespace)

        fun traceKeptInSpan(local: ChatMessage): Boolean {
            val trace = compact(local.reasoningText)
            return trace.isNotEmpty() &&
                (trace == compact(spanTrace.joinToString("")) || spanTrace.any { compact(it) == trace })
        }
        val stoppedReply =
            storedFinal?.takeIf {
                !running && !older && record.finishedAt == null &&
                    raw[span.last].finish_reason == "stop" && it.content.isNotBlank()
            }
        var recoveredStop: ChatMessage? = null
        for (local in replies) {
            if (local.isStreaming || local.toolStatus == ToolStatus.RUNNING ||
                (running && local.role != MessageRole.TOOL)
            ) {
                continue
            }
            val approvalFinal = storedFinal.takeIf { shareApprovalTrace && local.id == localFinal?.id }
            // The mapper carries a reasoning-only tool round into the next answer.
            // Retire the sealed trace only when this addressed reply already renders it.
            val recoveredFinal =
                stoppedReply?.takeIf { server ->
                    server in available && local.role == MessageRole.ASSISTANT &&
                        local.bodyForStorage().isBlank() && local.reasoningText.isNotBlank() &&
                        local.reasoningText == server.reasoningText &&
                        local.copy(content = server.content).foldsLosslesslyInto(server)
                }
            val match =
                recoveredFinal
                    ?: approvalFinal?.takeIf { it in available && local.foldsLosslesslyInto(it, traceKeptElsewhere = true) }
                    ?: available.firstOrNull { server ->
                        val uniqueCall =
                            local.toolCallId.isNotBlank() && server.toolCallId == local.toolCallId &&
                                available.count { it.toolCallId == local.toolCallId } == 1
                        if (local.role == MessageRole.TOOL) {
                            uniqueCall &&
                                (local.foldsLosslesslyInto(server) || local.finishedToolFoldsByCallInto(server))
                        } else {
                            local.foldsLosslesslyInto(server, traceKeptElsewhere = traceKeptInSpan(local))
                        }
                    } ?: continue
            available -= match
            // Live tool presentation (name, status, finish time) is not in history; adopting it
            // would change on the next sync, so a folded tool is shown exactly as stored.
            substitutes[local.id] =
                when {
                    recoveredFinal != null -> {
                        recoveredStop = recoveredFinal
                        recoveredFinal
                    }

                    match == approvalFinal -> {
                        match.withLocalPresentation(local).copy(
                            reasoningText = mergeObservedReasoning(local.reasoningText, match.reasoningText),
                        )
                    }

                    local.role == MessageRole.TOOL && !running -> {
                        match
                    }

                    else -> {
                        match.withLocalPresentation(local)
                    }
                }
        }
        if (substitutes.isEmpty()) continue
        val absorbedIds = substitutes.values.map { it.id }.toSet()
        // History may have inserted a later user turn between the locally
        // cached prompt and answer. Use the exact server slot for folded rows
        // that would otherwise stay on the wrong side of that user boundary.
        val displayRoot = current.indexOfFirst { it.id == rootShown?.id }
        val nextDisplayUser =
            (displayRoot + 1 until current.size).firstOrNull {
                val message = current[it]
                message.role == MessageRole.USER && message.displayKind == null && message.id !in substitutes
            }
        val serverSlotReplacements =
            if (proven.isEmpty() || recoveredStop != null) {
                // Keep the durable interleaving of the prompt, tool rows and prose.
                substitutes
            } else if (displayRoot >= 0 && nextDisplayUser != null) {
                val turnIds = current.subList(displayRoot + 1, nextDisplayUser).mapTo(HashSet()) { it.id }
                val laterIds = current.subList(nextDisplayUser, current.size).mapTo(HashSet()) { it.id }
                substitutes.filter { (localId, server) -> localId in laterIds && server.id in turnIds }
            } else {
                emptyMap()
            }
        val replacementsByServerId = serverSlotReplacements.values.associateBy { it.id }
        current =
            current.mapNotNull { message ->
                when {
                    message.id in serverSlotReplacements -> null
                    message.id in replacementsByServerId -> replacementsByServerId[message.id]
                    else -> substitutes[message.id] ?: message.takeUnless { it.id in absorbedIds }
                }
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
                        finishedAt = it.finishedAt ?: recoveredStop?.timestamp,
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
 * A receipt addresses the server turn. An addressless control ACK only addresses its local input.
 * Pair occurrences in the receipt's user sequence, then check disposition and payload parity.
 * Keep an accepted input with no proved row at its local slot.
 */
private fun controlledTurnPresentation(
    record: ResponseRecord,
    ledger: ResponseLedger,
    messages: List<ChatMessage>,
    canonical: List<ChatMessage>,
    rows: List<Long>,
): Pair<List<ChatMessage>, Map<Long, String>> {
    val members = (record.userIds + record.messageIds).toSet()
    val local = messages.filter { it.id in members }
    val server = rows.zip(canonical).toMap().toMutableMap()
    val aliases = mutableMapOf<Long, String>()
    val matched = mutableMapOf<String, Long>()
    val controlsById =
        record.authority.controls
            .mapNotNull { control ->
                control.localUserId?.let { it to control }
            }.toMap()

    val legacyControls = record.authority.controls.filter { it.localUserId == null && it.userRowId == null }
    val legacyUsers =
        local.filter {
            it.displayKind == "steer" && it.id !in controlsById &&
                it.id !in ledger.aliases.values
        }
    val legacyDisposition =
        legacyControls
            .map { it.disposition }
            .distinct()
            .singleOrNull()
            ?.takeIf { legacyUsers.size == legacyControls.size }

    fun merge(
        row: Long,
        prior: ChatMessage,
        replacement: ChatMessage,
        keepId: Boolean = false,
    ) {
        server[row] = replacement.withLocalPresentation(prior).let { if (keepId) it.copy(id = prior.id) else it }
        matched[prior.id] = row
        if (keepId) aliases[row] = prior.id
    }

    for ((row, stored) in rows.zip(canonical)) {
        val alias = ledger.aliases[row.toString()]
        val prior = local.singleOrNull { it.id == stored.id || it.id == alias } ?: continue
        val projected = stored.copy(displayKind = stored.displayKind ?: prior.displayKind)
        if (prior.foldsLosslesslyInto(projected, traceKeptElsewhere = true) || prior == projected) {
            val retained =
                projected.copy(
                    reasoningText = mergeObservedReasoning(prior.reasoningText, projected.reasoningText),
                )
            merge(row, prior, retained, keepId = prior.role == MessageRole.USER)
        }
    }

    val receiptUsers = record.receiptUsers()
    if (receiptUsers != null) {
        val pending =
            local.filter { it.id !in matched }.mapNotNull { prior ->
                val control = controlsById[prior.id]
                val disposition =
                    control?.takeIf { it.userRowId == null }?.disposition
                        ?: legacyDisposition?.takeIf { prior in legacyUsers }
                disposition?.let { prior to it }
            }
        val stored =
            receiptUsers.drop(1).mapNotNull { row ->
                server[row]
                    ?.takeIf {
                        it.role == MessageRole.USER && row !in matched.values && ledger.aliases[row.toString()] == null
                    }?.let { row to it }
            }
        val pairs = pending.zip(stored)
        if (pending.size == stored.size &&
            pairs.all { (accepted, addressed) ->
                val (prior, disposition) = accepted
                val canonicalUser = addressed.second
                // Redirect ACKs can also result in a steer when the agent is executing tools.
                val compatible =
                    canonicalUser.displayKind == "steer" ||
                        (canonicalUser.displayKind == null && disposition == "REDIRECTED")
                compatible && prior.foldsLosslesslyInto(canonicalUser.copy(displayKind = "steer"))
            }
        ) {
            pairs.forEach { (accepted, addressed) ->
                merge(addressed.first, accepted.first, addressed.second.copy(displayKind = "steer"), keepId = true)
            }
        }
    }

    val final = local.lastOrNull { it.role == MessageRole.ASSISTANT }
    for (prior in local.filterNot { it.id in matched }) {
        if (prior.role == MessageRole.USER || prior.isStreaming || prior.approvalInfo != null ||
            prior.clarifyInfo != null
        ) {
            continue
        }
        val candidates = if (prior == final) rows.takeLast(1) else rows.dropLast(1)
        val row =
            candidates.singleOrNull { row ->
                val stored = server.getValue(row)
                if (row in matched.values || stored.id in members) return@singleOrNull false
                when (prior.role) {
                    MessageRole.TOOL -> {
                        prior.toolCallId.isNotBlank() && prior.toolCallId == stored.toolCallId &&
                            (prior.foldsLosslesslyInto(stored) || prior.finishedToolFoldsByCallInto(stored))
                    }

                    MessageRole.ASSISTANT -> {
                        prior.foldsLosslesslyInto(stored, traceKeptElsewhere = true) ||
                            (
                                prior == final &&
                                    prior.copy(isStreaming = true, reasoningText = "").isCoveredByStoredReply(stored)
                            )
                    }

                    else -> {
                        false
                    }
                }
            } ?: continue
        val stored = server.getValue(row)
        merge(
            row,
            prior,
            stored.copy(reasoningText = mergeObservedReasoning(prior.reasoningText, stored.reasoningText)),
        )
    }

    // Previously materialized canonical rows can also have a local-only payload version.
    // Keep it unless the stored version above was able to absorb it.
    val canonicalById = canonical.associateBy { it.id }
    val extras =
        local.withIndex().filter { (_, message) ->
            message.id !in matched &&
                canonicalById[message.id] != message
        }
    val before =
        extras.groupBy(
            keySelector = { (index, _) ->
                local.asSequence().drop(index + 1).firstNotNullOfOrNull { matched[it.id] } ?: rows.last()
            },
            valueTransform = { (_, message) -> message },
        )
    val presentation =
        rows.flatMap { row ->
            val stored = server.getValue(row)
            val retained = before[row].orEmpty()
            // If the same stable id holds a distinct payload, keep the local id and give the server
            // observation its own physical-version key.
            val observed =
                if (local.any {
                        it.id == stored.id && it.id !in matched && it != stored
                    }
                ) {
                    disputedBodyId(stored)
                } else {
                    stored.id
                }
            if (observed != stored.id) aliases[row] = observed
            retained + stored.copy(id = observed)
        }
    return presentation to aliases
}

/** Recover one unaddressed, finished queue reply using its immediate predecessor's exact final row. */
private fun legacyFollowUpRoot(
    record: ResponseRecord,
    predecessor: ResponseRecord?,
    target: String,
    session: String,
    raw: List<SessionMessage>,
    messages: Map<String, ChatMessage>,
    displayedAt: (Int) -> ChatMessage?,
): Int? {
    val facts = record.authority
    val queued = facts.queued ?: return null
    if (!record.serverFollowUp || record.finishedAt == null || facts.opening != null ||
        queued.target != target || queued.session != session || record.canonicalRowIds.isNotEmpty()
    ) {
        return null
    }
    val predecessorFinal = predecessor?.canonicalFinalAssistantRowId ?: return null
    if (predecessor.authority.revocations.isNotEmpty() ||
        predecessor.authority.terminals.none {
            it.finalRowId == predecessorFinal && it.safe
        }
    ) {
        return null
    }
    val previous = raw.indexOfFirst { it.id?.toLong() == predecessorFinal && it.role == "assistant" }
    val root = previous + 1
    if (previous < 0 || root >= raw.lastIndex || raw[root].role != "user" || raw[root].display_kind != null) return null
    val end = ((root + 1 until raw.size).firstOrNull { raw[it].role == "user" } ?: raw.size) - 1
    if (end == root || raw[end].role != "assistant" || raw[end].display_kind != null ||
        raw.getOrNull(end + 1)?.display_kind != null ||
        (root + 1 until end).any { raw[it].role !in setOf("assistant", "tool", "system") }
    ) {
        return null
    }
    if (record.authority.terminals.any {
            !it.safe || it.userRowId != raw[root].id?.toLong() || it.finalRowId != raw[end].id?.toLong() ||
                it.rowIds != raw.subList(root, end + 1).map { row -> row.id?.toLong() }
        }
    ) {
        return null
    }
    val user = record.userIds.singleOrNull()?.let(messages::get) ?: return null
    val reply =
        record.messageIds.mapNotNull(messages::get).lastOrNull { it.role == MessageRole.ASSISTANT } ?: return null
    val storedUser = displayedAt(root) ?: return null
    val storedReply = displayedAt(end) ?: return null
    return root.takeIf {
        LIVE_BUBBLE_ID.matches(reply.id) && reply.finishTimestamp != null && !reply.isStreaming &&
            user.displayKind == null && reply.displayKind == null && !storedReply.hasServerDisplayProjection &&
            user.foldsLosslesslyInto(storedUser) && reply.foldsLosslesslyInto(storedReply)
    }
}

/** History may materialize a user row before the ACK supplies the alias to its local bubble. */
private fun coalesceAcknowledgedUsers(
    messages: List<ChatMessage>,
    byId: Map<String, ChatMessage>,
    mapped: List<ChatMessage>,
    addresses: Map<String, Long>,
    ledger: ResponseLedger,
): List<ChatMessage> {
    val replacements = mutableMapOf<String, ChatMessage>()
    val retired = mutableSetOf<String>()
    for ((position, row) in mapped.withIndex()) {
        if (row.role != MessageRole.USER) continue
        val address = addresses[row.id]?.toString() ?: continue
        val alias = ledger.aliases[address]?.takeIf { it != row.id } ?: continue
        val observed = byId[row.id] ?: continue
        val local = byId[alias] ?: continue
        if (ledger.aliases.any { (key, id) -> (key != address && id == alias) || id == row.id } ||
            ledger.responses.any {
                row.id in it.userIds || (alias in it.userIds && it.authority.revocations.isNotEmpty())
            }
        ) {
            continue
        }
        val projected = row.copy(displayKind = row.displayKind ?: local.displayKind).withLocalPresentation(local)
        if (local.foldsLosslesslyInto(projected) && observed.foldsLosslesslyInto(projected) &&
            (observed.reasoningText.isBlank() || projected.reasoningText.isNotBlank())
        ) {
            val observedIndex = messages.indexOf(observed)
            // A partial page may have appended the stored copy below its own reply.
            // Only move the optimistic row later when history anchors a reply below that slot.
            val useHistorySlot =
                observedIndex < messages.indexOf(local) ||
                    mapped
                        .asSequence()
                        .drop(position + 1)
                        .takeWhile { it.role != MessageRole.USER || it.displayKind != null }
                        .filter { it.role == MessageRole.ASSISTANT }
                        .any { reply ->
                            val replyAlias = addresses[reply.id]?.let { ledger.aliases[it.toString()] }
                            messages.indexOfFirst { it.id == (replyAlias ?: reply.id) } > observedIndex
                        }
            replacements[if (useHistorySlot) row.id else local.id] = projected.copy(id = alias)
            retired += if (useHistorySlot) local.id else row.id
        }
    }
    if (replacements.isEmpty()) return messages
    return messages.mapNotNull { replacements[it.id] ?: it.takeUnless { it.id in retired } }
}

private fun ChatMessage.isCoveredByStoredReply(server: ChatMessage): Boolean {
    if (foldsLosslesslyInto(server)) return true
    // History can include the suffix after the last token we received. For a plain live
    // preview, verify the prefix first and then check that the rest of its payload is kept.
    return isStreaming && content.isNotBlank() && sourceBody == null && server.sourceBody == null &&
        server.content.trim().startsWith(content.trim()) && copy(content = server.content).foldsLosslesslyInto(server)
}

/**
 * True only when [server] keeps everything this local message holds: the same stored body and,
 * when present locally, compatible reasoning, the same attachments and display kind, with no
 * local-only transient data. Missing reasoning is carried forward by [withLocalPresentation].
 * Equal visible text alone is not proof that nothing is lost.
 */
private fun ChatMessage.foldsLosslesslyInto(
    server: ChatMessage,
    traceKeptElsewhere: Boolean = false,
): Boolean {
    fun normalized(text: String) = text.replace("\r\n", "\n").trim()
    if (role != server.role || normalized(bodyForStorage()).isEmpty()) return false
    if (normalized(bodyForStorage()) != normalized(server.bodyForStorage())) return false
    if (!traceKeptElsewhere && reasoningText.isNotBlank() &&
        (server.reasoningText.isNotBlank() || server.hasServerDisplayProjection) &&
        normalized(reasoningText) != normalized(server.reasoningText)
    ) {
        return false
    }
    if (displayKind != null && displayKind != server.displayKind) return false
    if (toolCallId.isNotBlank() && toolCallId != server.toolCallId) return false
    if (toolOutputRiskData != null || !progressPreview.isNullOrBlank()) return false
    val kept = server.attachments.orEmpty().map { Triple(it.retainedSourceAddress(), it.name, it.mimeType) }
    return attachments.orEmpty().all { Triple(it.retainedSourceAddress(), it.name, it.mimeType) in kept }
}

/**
 * A finished live tool bubble holds the tool.complete event payload, never the stored result, so
 * its body cannot equal the history row. Inside a proven turn the unique call id (checked by the
 * caller) identifies the row; risk findings and attachments stay local-only and refuse the fold.
 */
private fun ChatMessage.finishedToolFoldsByCallInto(server: ChatMessage): Boolean =
    role == MessageRole.TOOL && server.role == MessageRole.TOOL &&
        (toolStatus == ToolStatus.COMPLETED || toolStatus == ToolStatus.FAILED) &&
        toolOutputRiskData == null && attachments.isNullOrEmpty() &&
        (displayKind == null || displayKind == server.displayKind)

/** Only call after an exact row/alias match, or a fold within a proven turn. */
private fun ChatMessage.withRetainedReasoning(local: ChatMessage?): ChatMessage {
    if (local == null || hasServerDisplayProjection || reasoningText.isNotBlank() || role != local.role) return this

    fun normalized(text: String) = text.replace("\r\n", "\n").trim()
    if (normalized(bodyForStorage()) != normalized(local.bodyForStorage())) return this
    return copy(reasoningText = local.reasoningText)
}

/** Local presentation the server row does not carry (reasoning, tool name/status, finish time). */
private fun ChatMessage.withLocalPresentation(local: ChatMessage): ChatMessage =
    withRetainedReasoning(local).copy(
        toolName = toolName ?: local.toolName,
        toolStatus = toolStatus ?: local.toolStatus,
        finishTimestamp = finishTimestamp ?: local.finishTimestamp,
    )

/** Every row address this response already claims (inputs, receipts, coverage and aliases). */
private fun ResponseRecord.claimedRows(ledger: ResponseLedger): List<Long> {
    val members = (userIds + messageIds).toHashSet()
    return canonicalRowIds + userRowIds + listOfNotNull(authority.opening?.userRowId) +
        authority.controls.mapNotNull { it.userRowId } + authority.terminals.flatMap { it.rowIds } +
        authority.canonical?.rowIds.orEmpty() +
        ledger.aliases
            .filterValues { it in members }
            .keys
            .mapNotNull { it.toLongOrNull() }
}

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
 * single contiguous server page window. Untyped users need explicit turn membership.
 */
private fun verifiedCanonicalWindow(
    record: ResponseRecord,
    target: String,
    session: String,
    raw: List<SessionMessage>,
): List<Long>? {
    if (record.authority.terminals.isEmpty() || record.authority.terminals.any { !it.safe }) return null
    val final =
        record.authority.terminals
            .mapNotNull { it.finalRowId }
            .firstOrNull() ?: return null
    val opening = record.authority.opening ?: return null
    if (opening.target != target || opening.session != session) return null
    val root = opening.userRowId ?: return null
    val first = raw.indexOfFirst { it.id?.toLong() == root }
    val last = raw.indexOfFirst { it.id?.toLong() == final }
    if (first < 0 || last <= first || raw[first].role != "user" || raw[first].display_kind != null ||
        raw[last].role != "assistant"
    ) {
        return null
    }
    val window = raw.subList(first, last + 1)
    if (window.any { it.role !in setOf("user", "assistant", "system", "tool") }) return null
    val ids = window.map { it.id?.toLong()?.takeIf { row -> row > 0 } ?: return null }
    if (ids.size != ids.distinct().size) return null
    val terminalUsers = record.receiptUsers()
    val visibleUsers = window.filter { it.role == "user" && it.display_kind != "hidden" }.map { it.id!!.toLong() }
    val addressedWindow =
        record.authority.terminals.any {
            it.userRowId == root && it.finalRowId == final && terminalUsers == visibleUsers &&
                (it.userRowIds == null || it.userRowIds == terminalUsers) && it.rowIds.containsAll(visibleUsers)
        }
    val exactControls =
        record.authority.controls
            .mapNotNull { it.userRowId }
            .toSet()
    if (window.drop(1).any {
            it.role == "user" && it.display_kind != "steer" && it.id?.toLong() !in exactControls &&
                (!addressedWindow || it.display_kind != null)
        }
    ) {
        return null
    }
    return ids
}

/** Earlier cache entries kept the receipt's user addresses on the response record. */
private fun ResponseRecord.receiptUsers(): List<Long>? {
    val lists = authority.terminals.mapNotNull { it.userRowIds }.distinct()
    if (lists.isNotEmpty()) return lists.singleOrNull()
    val terminal = authority.terminals.singleOrNull() ?: return null
    return userRowIds.takeIf { it.firstOrNull() == terminal.userRowId && terminal.rowIds.containsAll(it) }
}

/** Messages by id; an id that occurs more than once maps to nothing (as `singleOrNull` would). */
private fun List<ChatMessage>.uniqueById(): Map<String, ChatMessage> {
    val counts = HashMap<String, Int>(size)
    forEach { counts.merge(it.id, 1, Int::plus) }
    return HashMap<String, ChatMessage>(size).also { index ->
        forEach { if (counts[it.id] == 1) index[it.id] = it }
    }
}
