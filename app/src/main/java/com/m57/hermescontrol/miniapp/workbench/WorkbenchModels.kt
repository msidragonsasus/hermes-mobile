package com.m57.hermescontrol.miniapp.workbench

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

@Serializable
data class CareerMyPageWorkbenchKey(
    val companyId: String,
) : NavKey

internal val workbenchFiles = listOf("protocol.json", "mobile_screen.json", "mypage_handler.py")
internal val workbenchActions =
    setOf("request", "edit", "test", "apply", "apply_reviewed", "rollback", "cancel", "propose", "cancel_editor")

internal data class WorkbenchSnapshot(
    val active: String,
    val previous: String?,
    val revision: Int,
    val draft: Boolean,
    val goal: String,
    val files: Map<String, String>,
    val diff: String,
    val diffTruncated: Boolean,
    val tested: Boolean,
)

internal data class WorkbenchPending(
    val operationId: String,
    val requestHash: String,
    val action: String,
    val active: String,
    val revision: Int,
) {
    fun fence(): JsonObject =
        buildJsonObject {
            put("protocol_version", 1)
            put("operation_id", operationId)
            put("action", "fence")
            put("expected_active", active)
            put("revision", revision)
            put("request_hash", requestHash)
            put("original_action", action)
        }
}

internal data class WorkbenchReceipt(
    val state: String,
    val code: String,
)

internal object WorkbenchParser {
    private val release = Regex("wb-[a-f0-9]{48}")
    private val hash = Regex("[a-f0-9]{64}")
    private val operation = Regex("[A-Za-z0-9_-]{16,80}")

    private fun JsonObject.text(name: String): String {
        val value = getValue(name).jsonPrimitive
        require(value.isString)
        return value.content
    }

    private fun JsonObject.scope(
        company: String,
        profile: String,
    ) {
        require(
            !getValue("protocol_version").jsonPrimitive.isString && getValue("protocol_version").jsonPrimitive.int == 1,
        )
        require(text("company_id") == company && text("profile") == profile)
    }

    fun snapshot(
        body: String,
        company: String,
        profile: String,
    ): WorkbenchSnapshot {
        val json = Json.parseToJsonElement(body).jsonObject
        require(
            json.keys ==
                setOf(
                    "protocol_version",
                    "company_id",
                    "profile",
                    "active",
                    "previous",
                    "revision",
                    "draft",
                    "goal",
                    "files",
                    "diff",
                    "diff_truncated",
                    "tested",
                ),
        )
        json.scope(company, profile)
        val active = json.text("active")
        require(release.matches(active))
        val previous = (json["previous"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(previous == null || release.matches(previous))
        require(previous != null || json["previous"].toString() == "null")
        require(!json.getValue("revision").jsonPrimitive.isString)
        val revision = json.getValue("revision").jsonPrimitive.int
        require(revision >= 0)
        val goal = json.text("goal")
        val diff = json.text("diff")
        require(goal.length <= 2000 && diff.length <= 16384)
        val files = json.getValue("files").jsonObject
        require(files.keys == workbenchFiles.toSet())
        val values =
            files.mapValues { (_, value) ->
                val primitive = value.jsonPrimitive
                require(
                    primitive.isString && primitive.content.isNotEmpty() &&
                        primitive.content.toByteArray().size <= 65536,
                )
                primitive.content
            }
        require(listOf("draft", "diff_truncated", "tested").all { !json.getValue(it).jsonPrimitive.isString })
        return WorkbenchSnapshot(
            active,
            previous,
            revision,
            json.getValue("draft").jsonPrimitive.boolean,
            goal,
            values,
            diff,
            json.getValue("diff_truncated").jsonPrimitive.boolean,
            json.getValue("tested").jsonPrimitive.boolean,
        )
    }

    fun receipt(
        body: String,
        company: String,
        profile: String,
        pending: WorkbenchPending,
    ): WorkbenchReceipt {
        val json = Json.parseToJsonElement(body).jsonObject
        require(
            json.keys ==
                setOf("protocol_version", "company_id", "profile", "operation_id", "state", "request_hash", "code"),
        )
        json.scope(company, profile)
        require(json.text("operation_id") == pending.operationId)
        val state = json.text("state")
        require(state in setOf("done", "refused", "cancelled", "unknown"))
        require(json.text("request_hash") == if (state == "unknown") "" else pending.requestHash)
        val code = json.text("code")
        require(code.isEmpty() || Regex("[a-z_]{1,80}").matches(code))
        return WorkbenchReceipt(state, code)
    }

    fun pending(body: String): WorkbenchPending {
        val json = Json.parseToJsonElement(body).jsonObject
        require(json.keys == setOf("operation", "hash", "action", "active", "revision"))
        val pending =
            WorkbenchPending(
                json.text("operation"),
                json.text("hash"),
                json.text("action"),
                json.text("active"),
                json.getValue("revision").jsonPrimitive.int,
            )
        require(
            operation.matches(pending.operationId) && hash.matches(pending.requestHash) &&
                pending.action in workbenchActions &&
                release.matches(pending.active) &&
                pending.revision >= 0,
        )
        return pending
    }

    fun encode(pending: WorkbenchPending): String =
        buildJsonObject {
            put("operation", pending.operationId)
            put("hash", pending.requestHash)
            put("action", pending.action)
            put("active", pending.active)
            put("revision", pending.revision)
        }.toString()

    fun digest(value: JsonElement): String {
        fun ordered(element: JsonElement): JsonElement =
            when (element) {
                is JsonObject -> JsonObject(element.toSortedMap().mapValues { ordered(it.value) })
                else -> element
            }
        return MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(ordered(value).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") {
                "%02x".format(it)
            }
    }
}
