package com.m57.hermescontrol.openclaw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

internal data class ClawPendingRun(
    val id: String,
    val message: String,
    val previousMatches: Int = 0,
)

internal fun clawPendingRuns(encoded: String?): MutableMap<String, ClawPendingRun> =
    runCatching {
        val data = Json.parseToJsonElement(encoded ?: "{}") as JsonObject
        data
            .mapNotNull { (key, value) ->
                val run = value as? JsonObject ?: return@mapNotNull null
                val id = run.string("id") ?: return@mapNotNull null
                val message = run.string("message") ?: return@mapNotNull null
                key to ClawPendingRun(id, message, run.string("previousMatches")?.toIntOrNull() ?: 0)
            }.toMap()
            .toMutableMap()
    }.getOrElse { mutableMapOf() }

internal fun encodeClawPendingRuns(runs: Map<String, ClawPendingRun>): String =
    JsonObject(
        runs.mapValues { (_, run) ->
            obj(
                "id" to value(run.id),
                "message" to value(run.message),
                "previousMatches" to JsonPrimitive(run.previousMatches),
            )
        },
    ).toString()

internal fun clawRecoveredNotice(
    key: String,
    run: ClawPendingRun,
    history: JsonObject,
): ClawNotice? {
    if (run.id.isBlank() || clawPendingRunActive(run, history)) return null
    val session = history.obj("sessionInfo")?.string("key")
    if (session != null && session != key) return null
    val terminal =
        history.array("messages").mapNotNull { it as? JsonObject }.lastOrNull { message ->
            val metadata = message.obj("__openclaw") ?: return@lastOrNull false
            val sequence = metadata["seq"] as? JsonPrimitive
            val sequenceNumber = sequence?.takeUnless { it.isString }?.longOrNull
            val receipt = metadata["id"] as? JsonPrimitive
            val position = metadata.obj("transcriptPosition")
            val positionSource = position?.get("source") as? JsonPrimitive
            val positionSequence = position?.get("rawSeq") as? JsonPrimitive
            val canonicalPosition =
                positionSource?.let { it.isString && it.content.isNotBlank() && it.content.length <= 128 } == true &&
                    (positionSequence?.takeUnless { it.isString }?.longOrNull ?: -1) in 0..9_007_199_254_740_991L
            val imported =
                !canonicalPosition &&
                    listOf("importedFrom", "cliSessionId", "externalId").any {
                        !metadata.string(it).isNullOrBlank()
                    }
            message.string("role") == "assistant" &&
                metadata.string("runId") == run.id &&
                !imported &&
                (metadata["runTerminal"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true &&
                (
                    receipt?.let { it.isString && it.content.isNotBlank() } == true ||
                        (sequenceNumber ?: 0) in 1..9_007_199_254_740_991L
                )
        } ?: return null
    return ClawNotice(
        key,
        "run:${run.id}",
        "処理の終了を確認しました",
        messageText(terminal).take(180).ifBlank { "会話を開いて結果を確認してください。" },
    )
}

internal fun clawPendingRunActive(
    run: ClawPendingRun,
    history: JsonObject,
): Boolean =
    history.obj("inFlightRun")?.string("runId") == run.id ||
        run.id in strings(history.obj("sessionInfo")?.get("activeRunIds"))

internal fun clawWaitRecoveredNotice(
    key: String,
    run: ClawPendingRun,
    result: JsonObject,
): ClawNotice? {
    if (run.id.isBlank() || result.string("runId") != run.id) return null
    val endedAt = result["endedAt"] as? JsonPrimitive ?: return null
    if (endedAt.isString || (endedAt.longOrNull ?: -1) < 0) return null
    if ((result["pendingError"] as? JsonPrimitive)?.booleanOrNull == true ||
        (result["yielded"] as? JsonPrimitive)?.booleanOrNull == true ||
        result.string("timeoutPhase") in listOf("queue", "gateway_draining")
    ) {
        return null
    }
    val status = result.string("status")
    if (status !in listOf("ok", "error") && !(status == "timeout" && result.string("stopReason") == "timeout")) {
        return null
    }
    return ClawNotice(key, "run:${run.id}", "処理の終了を確認しました", "会話を開いて結果を確認してください。")
}
