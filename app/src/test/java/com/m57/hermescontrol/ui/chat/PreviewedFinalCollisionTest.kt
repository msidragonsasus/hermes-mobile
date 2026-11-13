package com.m57.hermescontrol.ui.chat

import org.junit.Assert.*
import org.junit.Test

/** Compatibility is checked only after ViewModel's scoped terminal proof, never by global prose. */
class PreviewedFinalCollisionTest {
    private val local =
        ChatMessage(
            id = "preview",
            role = MessageRole.ASSISTANT,
            content = "answer",
            timestamp = 20,
            finishTimestamp = 30,
        )
    private val canonicalId = canonicalMessageId("target", "session", 1017)
    private val canonical = local.copy(id = canonicalId, timestamp = 10, finishTimestamp = null)
    private val commentary = local.copy(id = "commentary")
    private val owner =
        ResponseRecord(
            id = "owner",
            messageIds = listOf(commentary.id, local.id),
            coverage = false,
            authority = ReplyAuthorityFacts(deliveryGap = true, canonicalSuspended = true),
        )
    private val ledger = ResponseLedger(responses = listOf(owner), activeId = owner.id)

    private fun fold(
        visible: List<ChatMessage> = listOf(canonical, commentary, local),
        staged: List<ChatMessage> = emptyList(),
        facts: ResponseLedger = ledger,
    ) = foldPreviewedFinalCollision(visible, staged, facts, local.id, canonicalId)

    @Test
    fun exactAddressFoldKeepsLocalOrderAndAllAuthorityFences() {
        val folded = fold()!!
        assertEquals(listOf(commentary, local), folded.messages)
        assertEquals(emptyList<ChatMessage>(), folded.stagedMessages)
        assertEquals(local, folded.finalizedMessage)
        assertEquals(ledger, folded.ledger) // Caller alone atomically installs the proven row alias.
    }

    @Test
    fun stagedExactAddressIsRetiredButOtherRowsAndEqualProseRemain() {
        val unrelated = canonical.copy(id = canonicalMessageId("target", "session", 1018))
        val folded = fold(visible = listOf(commentary, local, unrelated), staged = listOf(canonical))!!
        assertEquals(listOf(commentary, local, unrelated), folded.messages)
        assertTrue(folded.stagedMessages.isEmpty())
        assertNull(fold(visible = listOf(commentary, local, unrelated)))
    }

    @Test
    fun ambiguousIdsRolesAndMembershipAreNeverFolded() {
        assertNull(fold(visible = listOf(canonical, commentary, local, local)))
        assertNull(fold(staged = listOf(canonical)))
        assertNull(fold(visible = listOf(canonical.copy(role = MessageRole.USER), commentary, local)))
        assertNull(
            fold(facts = ledger.copy(responses = listOf(owner.copy(messageIds = owner.messageIds + canonicalId)))),
        )
        assertNull(
            fold(
                facts =
                    ledger.copy(
                        responses =
                            ledger.responses + ResponseRecord(messageIds = listOf(canonicalId)),
                    ),
            ),
        )
        assertNull(fold(facts = ledger.copy(aliases = mapOf("1018" to canonicalId))))
    }

    @Test
    fun disputedPayloadVersionsAreNeverFolded() {
        assertNull(fold(visible = listOf(canonical.copy(content = "different"), commentary, local)))
        assertNull(
            fold(visible = listOf(canonical, canonical.copy(id = "disputed:$canonicalId:disputed"), commentary, local)),
        )
    }

    @Test
    fun staleSourceHintsAndSameFilenameDifferentPathsAreNotDeliveryProof() {
        val raw = "answer\nMEDIA:/host/a/report.pdf"
        val preview = local.copy(content = raw)
        val same = canonical.copy(content = "answer", sourceBody = raw)
        assertNull(fold(visible = listOf(same, commentary, preview))) // Raw/stripped display is not lossless.
        assertNull(
            fold(visible = listOf(same.copy(sourceBody = "answer\nMEDIA:/host/b/report.pdf"), commentary, preview)),
        )
        assertNull(
            fold(visible = listOf(canonical.copy(sourceBody = "older\nMEDIA:/host/a/report.pdf"), commentary, local)),
        )
        assertNull(fold(visible = listOf(same, commentary, local.copy(sourceBody = "older\nMEDIA:/host/a/report.pdf"))))
    }

    @Test
    fun canonicalReasoningIsPreservedOrConflictingTraceRefusesFold() {
        val incoming = canonical.copy(reasoningText = "canonical trace")
        val folded = fold(visible = listOf(incoming, commentary, local))!!
        assertEquals("canonical trace", folded.finalizedMessage.reasoningText)
        assertNull(fold(visible = listOf(incoming, commentary, local.copy(reasoningText = "other trace"))))
    }

    @Test
    fun independentAttachmentsAreRetainedOrTheCollisionIsRefused() {
        val attachment =
            com.m57.hermescontrol.data.model.Attachment(
                "/host/a/report.pdf",
                "report.pdf",
                "application/pdf",
            )
        val incoming = canonical.copy(attachments = listOf(attachment))
        assertEquals(
            listOf(attachment),
            fold(visible = listOf(incoming, commentary, local))!!.finalizedMessage.attachments,
        )
        val other = attachment.copy(uri = "/host/b/report.pdf")
        assertNull(fold(visible = listOf(incoming, commentary, local.copy(attachments = listOf(other)))))
        val raw = "answer\nMEDIA:/host/a/report.pdf"
        assertNull(
            fold(
                visible =
                    listOf(
                        canonical.copy(sourceBody = raw, attachments = listOf(attachment)),
                        commentary,
                        local.copy(content = raw),
                    ),
            ),
        )
    }

    @Test
    fun independentDisplayAndToolMetadataRefuseFold() {
        for (incoming in listOf(
            canonical.copy(displayKind = "hidden", hasServerDisplayProjection = true),
            canonical.copy(toolName = "independent tool"),
            canonical.copy(progressPreview = "independent progress"),
            canonical.copy(finishTimestamp = 99),
            canonical.copy(isStreaming = true),
        )) {
            assertNull(fold(visible = listOf(incoming, commentary, local)))
        }
        assertNull(
            fold(
                visible = listOf(canonical.copy(hasServerDisplayProjection = true), commentary, local),
            ),
        )
    }

    @Test
    fun projectedPayloadMustBeExactAndProjectionAuthorityCannotCrossEitherDirection() {
        val projected = canonical.copy(hasServerDisplayProjection = true)
        val preview = local.copy(hasServerDisplayProjection = true)
        assertEquals(preview, fold(visible = listOf(projected, commentary, preview))!!.finalizedMessage)
        assertNull(fold(visible = listOf(canonical, commentary, preview)))
        assertNull(fold(visible = listOf(projected, commentary, local)))
        for ((incoming, candidate) in listOf(
            projected to preview.copy(reasoningText = "local trace"),
            projected.copy(reasoningText = "canonical trace") to preview,
            projected.copy(sourceBody = "answer") to preview,
            projected.copy(sourceBody = "answer\nMEDIA:/host/a/report.pdf") to
                preview.copy(content = "answer\nMEDIA:/host/a/report.pdf"),
        )) {
            assertNull(fold(visible = listOf(incoming, commentary, candidate)))
        }
    }

    @Test
    fun oneSidedGatewayAttachmentsCannotBeImportedOrErased() {
        val gateway =
            com.m57.hermescontrol.data.model.Attachment(
                uri = "gateway:/host/a/report.pdf",
                name = "report.pdf",
                mimeType = "application/pdf",
                source = com.m57.hermescontrol.data.model.AttachmentSource.GATEWAY,
            )
        assertNull(fold(visible = listOf(canonical.copy(attachments = listOf(gateway)), commentary, local)))
        assertNull(fold(visible = listOf(canonical, commentary, local.copy(attachments = listOf(gateway)))))
        val preview = local.copy(attachments = listOf(gateway))
        assertEquals(
            preview,
            fold(
                visible = listOf(canonical.copy(attachments = listOf(gateway)), commentary, preview),
            )!!.finalizedMessage,
        )
        val projected = canonical.copy(hasServerDisplayProjection = true, attachments = listOf(gateway))
        assertNull(fold(visible = listOf(projected, commentary, local.copy(hasServerDisplayProjection = true))))
    }
}
