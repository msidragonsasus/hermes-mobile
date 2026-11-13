package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.domain.hermes.BackgroundRead
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.LiveRead
import com.m57.hermescontrol.domain.hermes.LiveSession
import com.m57.hermescontrol.domain.hermes.LiveStatus
import com.m57.hermescontrol.domain.hermes.ReadFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Read-only `session.active_list` on the fixed scope. Never resumes, creates or queues. */
suspend fun readLiveSessions(scope: ConnectionScope): LiveRead {
    if (!HermesWsClient.matchesCurrentScope(scope)) return LiveRead.Unavailable(ReadFailure.SCOPE_CHANGED)
    if (HermesWsClient.connectionStatus.value != ConnectionStatus.CONNECTED) {
        return LiveRead.Unavailable(ReadFailure.OFFLINE)
    }
    return try {
        val result = HermesWsClient.readActiveSessions(scope).await()
        if (!HermesWsClient.matchesCurrentScope(scope)) {
            LiveRead.Unavailable(ReadFailure.SCOPE_CHANGED)
        } else {
            decodeLiveSessions(scope, result as? JsonElement)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HermesWsClient.HermesRpcException) {
        LiveRead.Unavailable(rpcFailure(e.code))
    }
}

private fun rpcFailure(code: Int?): ReadFailure =
    when (code) {
        -32601 -> ReadFailure.UNSUPPORTED

        401, 4401 -> ReadFailure.AUTH_REQUIRED

        // Includes 4001 "session not found or not owned": unknown, never "nothing running".
        else -> ReadFailure.TIMEOUT_OR_UNAVAILABLE
    }

/**
 * Read-only, owner-scoped child work of one runtime ([runtimeId] is the gateway session id) on the
 * fixed scope: `subagent.list` and `process.list` side by side. Never resumes, activates or controls.
 * Never `session.usage.active_subagents`: that count is global and would mark unrelated chats busy.
 */
suspend fun readBackgroundWork(
    scope: ConnectionScope,
    runtimeId: String,
): BackgroundRead {
    if (!HermesWsClient.matchesCurrentScope(scope)) return BackgroundRead.Unavailable(ReadFailure.SCOPE_CHANGED)
    if (HermesWsClient.connectionStatus.value != ConnectionStatus.CONNECTED) {
        return BackgroundRead.Unavailable(ReadFailure.OFFLINE)
    }
    return coroutineScope {
        val subagents =
            async { readOwned(scope, ::decodeSubagentWork) { HermesWsClient.readSubagents(scope, runtimeId) } }
        val processes =
            async { readOwned(scope, ::decodeProcessWork) { HermesWsClient.readProcesses(scope, runtimeId) } }
        combineBackgroundWork(subagents.await(), processes.await())
    }
}

private suspend fun readOwned(
    scope: ConnectionScope,
    decode: (JsonElement?) -> BackgroundRead,
    request: () -> CompletableDeferred<Any?>,
): BackgroundRead =
    try {
        val result = request().await()
        if (!HermesWsClient.matchesCurrentScope(scope)) {
            BackgroundRead.Unavailable(ReadFailure.SCOPE_CHANGED)
        } else {
            decode(result as? JsonElement)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HermesWsClient.HermesRpcException) {
        BackgroundRead.Unavailable(rpcFailure(e.code))
    }

/** `subagent.list` rows: any own child running/queued/steered; `delegations` never mean "none running". */
internal fun decodeSubagentWork(result: JsonElement?): BackgroundRead =
    decodeWork(
        result,
        "subagents",
        running = setOf("running", "queued", "steered"),
        ended =
            setOf(
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
            ),
    )

/** `process.list` rows (`status` running/exited) owned by this session. */
internal fun decodeProcessWork(result: JsonElement?): BackgroundRead =
    decodeWork(result, "processes", running = setOf("running"), ended = setOf("exited"))

/** One running record decides; "none" needs every record readable and ended. */
private fun decodeWork(
    result: JsonElement?,
    key: String,
    running: Set<String>,
    ended: Set<String>,
): BackgroundRead {
    val unknown = BackgroundRead.Unavailable(ReadFailure.MALFORMED)
    val rows = (result as? JsonObject)?.get(key) as? JsonArray ?: return unknown
    var conclusive = true
    rows.forEach { row ->
        val status = (row as? JsonObject)?.text("status").orEmpty()
        when (status) {
            in running -> return BackgroundRead.Available(true)
            in ended -> Unit
            else -> conclusive = false
        }
    }
    return if (conclusive) BackgroundRead.Available(false) else unknown
}

/** Running wins; "none" needs both reads conclusive; otherwise unknown, never an empty answer. */
internal fun combineBackgroundWork(
    subagents: BackgroundRead,
    processes: BackgroundRead,
): BackgroundRead {
    val reads = listOf(subagents, processes)
    if (BackgroundRead.Available(true) in reads) return BackgroundRead.Available(true)
    if (reads.all { it == BackgroundRead.Available(false) }) return BackgroundRead.Available(false)
    val failures = reads.filterIsInstance<BackgroundRead.Unavailable>()
    return failures.firstOrNull { it.reason != ReadFailure.UNSUPPORTED }
        ?: BackgroundRead.Unavailable(
            if (failures.size == reads.size) ReadFailure.UNSUPPORTED else ReadFailure.TIMEOUT_OR_UNAVAILABLE,
        )
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)

internal fun decodeLiveSessions(
    scope: ConnectionScope,
    result: JsonElement?,
): LiveRead {
    val malformed = LiveRead.Unavailable(ReadFailure.MALFORMED)
    val rows = (result as? JsonObject)?.get("sessions") as? JsonArray ?: return malformed
    val ids = mutableSetOf<String>()
    val sessions =
        rows.map { row ->
            val item = row as? JsonObject ?: return malformed
            val id = item.text("id")?.takeIf(ids::add) ?: return malformed
            val status =
                when (item.text("status")) {
                    "working" -> LiveStatus.WORKING
                    "starting" -> LiveStatus.STARTING
                    "waiting" -> LiveStatus.WAITING
                    "idle" -> LiveStatus.IDLE
                    else -> LiveStatus.UNKNOWN
                }
            LiveSession(id, item.text("session_key"), status)
        }
    return LiveRead.Available(scope, sessions)
}
