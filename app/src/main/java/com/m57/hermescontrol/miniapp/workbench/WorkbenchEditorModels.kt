package com.m57.hermescontrol.miniapp.workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class EditorExecution(
    val provider: String,
    val model: String,
    val effort: String,
)

internal data class EditorJob(
    val id: String,
    val state: String,
    val base: String,
    val execution: EditorExecution?,
    val proposalHash: String?,
    val testedHash: String?,
    val appliedHash: String?,
    val error: String,
) {
    val active: Boolean get() = state in setOf("awaiting_executor", "queued", "running")
}

internal data class EditorSnapshot(
    val executorConfigured: Boolean,
    val autoRunEnabled: Boolean,
    val job: EditorJob?,
)

internal object WorkbenchEditorParser {
    private fun JsonObject.text(key: String): String {
        val value = getValue(key).jsonPrimitive
        require(value.isString)
        return value.content
    }

    private fun JsonObject.hash(key: String): String? {
        if (getValue(key) == JsonNull) return null
        return text(key).also { require(Regex("[a-f0-9]{64}").matches(it)) }
    }

    fun parse(
        body: String,
        company: String,
        profile: String,
    ): EditorSnapshot {
        val json = Json.parseToJsonElement(body).jsonObject
        require(
            json.keys ==
                setOf("protocol_version", "company_id", "profile", "executor_configured", "auto_run_enabled", "job"),
        )
        val version = json.getValue("protocol_version").jsonPrimitive
        require(
            !version.isString && version.int == 1 && json.text("company_id") == company &&
                json.text("profile") == profile,
        )
        val configured = json.getValue("executor_configured").jsonPrimitive
        val autoRun = json.getValue("auto_run_enabled").jsonPrimitive
        require(!configured.isString && !autoRun.isString)
        val job =
            if (json.getValue("job") == JsonNull) {
                null
            } else {
                val value = json.getValue("job").jsonObject
                require(
                    value.keys ==
                        setOf(
                            "id",
                            "state",
                            "base",
                            "execution",
                            "proposal_hash",
                            "tested_hash",
                            "applied_hash",
                            "error",
                            "created_at",
                            "updated_at",
                        ),
                )
                val id = value.text("id")
                val state = value.text("state")
                val base = value.text("base")
                require(Regex("[A-Za-z0-9_-]{16,80}").matches(id) && Regex("wb-[a-f0-9]{48}").matches(base))
                require(
                    state in
                        setOf(
                            "awaiting_executor",
                            "queued",
                            "running",
                            "proposed",
                            "tested",
                            "applied",
                            "rolled_back",
                            "discarded",
                            "failed",
                            "cancelled",
                            "conflict",
                        ),
                )
                require(listOf("created_at", "updated_at").all { value.text(it).length in 1..80 })
                val execution =
                    if (value.getValue("execution") == JsonNull) {
                        null
                    } else {
                        val details = value.getValue("execution").jsonObject
                        require(details.keys == setOf("provider", "model", "reasoning_effort"))
                        require(details.keys.all { details.text(it).isNotBlank() && details.text(it).length <= 200 })
                        EditorExecution(
                            details.text("provider"),
                            details.text("model"),
                            details.text("reasoning_effort"),
                        )
                    }
                val error = value.text("error")
                require(error.isEmpty() || Regex("[a-z_]{1,80}").matches(error))
                EditorJob(
                    id,
                    state,
                    base,
                    execution,
                    value.hash("proposal_hash"),
                    value.hash("tested_hash"),
                    value.hash("applied_hash"),
                    error,
                )
            }
        return EditorSnapshot(configured.boolean, autoRun.boolean, job)
    }
}
