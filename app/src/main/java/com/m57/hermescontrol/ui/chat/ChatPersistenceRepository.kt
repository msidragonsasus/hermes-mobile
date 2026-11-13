package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.ChatTranscriptState
import com.m57.hermescontrol.data.local.TranscriptRows
import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class TranscriptPayload(
    val version: Int = 2,
    val orderedIds: List<String> = emptyList(),
    val stagedIds: List<String> = emptyList(),
    val ledger: ResponseLedger = ResponseLedger(),
    val finishes: Map<String, Long> = emptyMap(),
    /** Missing provenance stays unknown, even when a legacy body is preserved in a snapshot. */
    val provenance: Map<String, String> = emptyMap(),
)

data class TranscriptSnapshot(
    val messages: List<ChatMessage>,
    val payload: TranscriptPayload,
    val stagedMessages: List<ChatMessage> = emptyList(),
)

class TranscriptOwner(
    val target: String,
    val session: String,
    val epoch: Long,
) {
    private val revision = AtomicLong()

    fun nextRevision(): Long = revision.incrementAndGet()

    @Volatile var valid: Boolean = true
}

data class TranscriptWrite(
    val owner: TranscriptOwner,
    val revision: Long,
    val snapshot: TranscriptSnapshot,
)

/** Bodies, order, response membership, aliases and terminal proof have one commit boundary. */
open class ChatPersistenceRepository(
    private val dao: ChatMessageDao,
) {
    // Serializes transitions including reads/claim. Room also fences competing repository instances.
    private val mutex = Mutex()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun openTranscript(
        target: String,
        session: String,
    ): Pair<TranscriptOwner, TranscriptSnapshot> =
        mutex.withLock {
            val rows = dao.claimTranscript(target, session)
            TranscriptOwner(target, session, requireNotNull(rows.state).writeEpoch) to decode(target, session, rows)
        }

    suspend fun readTranscript(
        target: String,
        session: String,
    ): TranscriptSnapshot =
        mutex.withLock {
            decode(target, session, dao.readTranscript(target, session))
        }

    /** Called synchronously by the structural coordinator, before launching storage. */
    fun prepare(
        owner: TranscriptOwner,
        messages: List<ChatMessage>,
        ledger: ResponseLedger,
        staged: List<ChatMessage> = emptyList(),
    ): TranscriptWrite {
        val durable = messages.filterNot { it.id in ledger.pendingInputs }
        return TranscriptWrite(
            owner,
            owner.nextRevision(),
            TranscriptSnapshot(
                durable,
                TranscriptPayload(
                    orderedIds = durable.map { it.id },
                    ledger = ledger,
                    stagedIds = staged.map { it.id },
                    finishes = durable.mapNotNull { m -> m.finishTimestamp?.let { m.id to it } }.toMap(),
                    provenance =
                        (durable + staged).associate {
                            it.id to
                                if (it.id.startsWith("legacy-")) "unknown" else owner.target
                        },
                ),
                staged,
            ),
        )
    }

    suspend fun save(write: TranscriptWrite): Boolean =
        mutex.withLock {
            val owner = write.owner
            if (!owner.valid) return@withLock false
            val state =
                ChatTranscriptState(
                    owner.target,
                    owner.session,
                    owner.epoch,
                    write.revision,
                    json.encodeToString(write.snapshot.payload),
                )
            dao.saveTranscript(
                state,
                (write.snapshot.messages + write.snapshot.stagedMessages).distinctBy { it.id }.map {
                    it.toEntity(owner.session).copy(
                        id = bodyId(owner.target, owner.session, it.id),
                        content = it.bodyForStorage(),
                    )
                },
            )
        }

    private fun decode(
        target: String,
        session: String,
        rows: TranscriptRows,
    ): TranscriptSnapshot {
        val payload =
            rows.state?.payload?.takeIf { it.isNotEmpty() }?.let {
                json
                    .decodeFromString<TranscriptPayload>(it)
                    .let { decoded ->
                        decoded.copy(
                            version =
                                json
                                    .parseToJsonElement(it)
                                    .jsonObject["version"]
                                    ?.jsonPrimitive
                                    ?.intOrNull ?: 1,
                        )
                    }.also { decoded ->
                        require(
                            decoded.version in 1..2 && decoded.orderedIds.size == decoded.orderedIds.distinct().size,
                        ) {
                            "Unsupported transcript index; stored data was preserved"
                        }
                    }
            }
        if (payload == null) {
            // Original cache remains intact and readable, but has no scope or response proof.
            val legacy =
                rows.rows.filterNot { it.id.startsWith("scoped:") }.map {
                    it.toUiModel().copy(id = "legacy-${it.id}", finishTimestamp = null)
                }
            return TranscriptSnapshot(
                legacy,
                TranscriptPayload(
                    orderedIds = legacy.map { it.id },
                    provenance = legacy.associate { it.id to "unknown" },
                ),
            )
        }
        val byId = rows.rows.associateBy { it.id }
        val messages =
            payload.orderedIds.mapNotNull { id ->
                byId[
                    bodyId(
                        target,
                        session,
                        id,
                    ),
                ]?.toUiModel()?.copy(id = id, finishTimestamp = payload.finishes[id], isStreaming = false)
            }
        val staged =
            payload.stagedIds.mapNotNull { id ->
                byId[bodyId(target, session, id)]?.toUiModel()?.copy(id = id, isStreaming = false)
            }
        val compatibleLedger =
            if (payload.version == 1) {
                payload.ledger.copy(
                    responses =
                        payload.ledger.responses.map {
                            it.copy(authority = ReplyAuthorityFacts())
                        },
                )
            } else {
                payload.ledger
            }
        val ledger =
            if (messages.size == payload.orderedIds.size && staged.size == payload.stagedIds.size) {
                compatibleLedger
            } else {
                compatibleLedger.copy(
                    responses =
                        compatibleLedger.responses.map {
                            it.copy(
                                coverage = false,
                                authority = it.authority.copy(local = null, canonical = null, deliveryGap = true),
                            )
                        },
                )
            }
        val readable =
            if (payload.version ==
                1
            ) {
                recoverLegacyStaging(messages, staged, ledger, target, session)
            } else {
                messages
            }
        return TranscriptSnapshot(
            readable,
            payload.copy(
                version = 2,
                orderedIds = readable.map { it.id },
                ledger = ledger,
                stagedIds =
                    if (payload.version ==
                        1
                    ) {
                        emptyList()
                    } else {
                        payload.stagedIds
                    },
            ),
            if (payload.version == 1) emptyList() else staged,
        )
    }

    /** Legacy addresses may order readable bodies; they never confer reply authority. */
    private fun recoverLegacyStaging(
        messages: List<ChatMessage>,
        staged: List<ChatMessage>,
        ledger: ResponseLedger,
        target: String,
        session: String,
    ): List<ChatMessage> {
        val readable = messages.toMutableList()
        for (body in staged) {
            if (readable.any { it.id == body.id }) continue
            val orders =
                ledger.responses
                    .map { it.canonicalRowIds }
                    .filter { rows ->
                        rows.isNotEmpty() && rows.all { it > 0 } && rows.distinct().size == rows.size
                    }.map { rows -> rows.map { canonicalMessageId(target, session, it) } }
                    .filter { body.id in it }
            val order = orders.singleOrNull()
            val knownPositions =
                order.orEmpty().mapNotNull { id ->
                    readable.indexOfFirst { it.id == id }.takeIf {
                        it >=
                            0
                    }
                }
            val valid = knownPositions.zipWithNext().all { (a, b) -> a < b }
            val index = order?.indexOf(body.id) ?: -1
            val next =
                if (valid && index >= 0) {
                    order!!.drop(index + 1).firstNotNullOfOrNull { id ->
                        readable.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                    }
                } else {
                    null
                }
            val previous =
                if (valid && index >= 0) {
                    order!!.take(index).asReversed().firstNotNullOfOrNull { id ->
                        readable.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                    }
                } else {
                    null
                }
            readable.add(next ?: previous?.plus(1) ?: readable.size, body)
        }
        return readable
    }

    private fun bodyId(
        target: String,
        session: String,
        id: String,
    ): String =
        "scoped:" +
            listOf(target, session, id).joinToString(":") {
                Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
            }

    // Legacy API remains for unrelated callers and fixtures; the indexed VM never writes here.
    suspend fun persistMessage(
        message: ChatMessage,
        sessionId: String,
    ) {
        dao.upsert(message.toEntity(sessionId))
    }

    suspend fun persistMessages(
        messages: List<ChatMessage>,
        sessionId: String,
    ) {
        dao.upsertAll(messages.map { it.toEntity(sessionId) })
    }

    suspend fun loadMessages(sessionId: String): List<ChatMessage> =
        dao.getMessagesForSession(sessionId).map {
            it.toUiModel()
        }

    suspend fun clearMessagesForSession(sessionId: String) {
        dao.deleteMessagesForSession(sessionId)
    }
}
