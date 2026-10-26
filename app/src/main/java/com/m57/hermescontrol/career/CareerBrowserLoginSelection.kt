package com.m57.hermescontrol.career

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Transient structural metadata only. Input values and hidden controls never enter this snapshot. */
internal class CareerBrowserLoginSnapshot(
    val fields: JsonArray,
    val signature: String,
) {
    fun selection(value: JsonObject): CareerBrowserLoginSelection? {
        val login = (value["login_index"] as? JsonPrimitive)?.intOrNull ?: return null
        val password = (value["password_index"] as? JsonPrimitive)?.intOrNull
        val id =
            fields.map { it.jsonObject }.singleOrNull { it["index"]?.jsonPrimitive?.intOrNull == login } ?: return null
        if (id["type"]?.jsonPrimitive?.content !in setOf("text", "email", "tel") ||
            id["occupied"] != JsonPrimitive(false)
        ) {
            return null
        }
        if (password != null) {
            val pass =
                fields.map { it.jsonObject }.singleOrNull { it["index"]?.jsonPrimitive?.intOrNull == password }
                    ?: return null
            if (pass["type"] != JsonPrimitive("password") || pass["occupied"] != JsonPrimitive(false) ||
                pass["form"] != id["form"]
            ) {
                return null
            }
        } else if (fields.any { it.jsonObject["type"] == JsonPrimitive("password") }) {
            return null
        }
        return CareerBrowserLoginSelection(login, password, signature)
    }

    companion object {
        fun parse(result: String): CareerBrowserLoginSnapshot? =
            runCatching {
                val value = Json.parseToJsonElement(Json.parseToJsonElement(result).jsonPrimitive.content).jsonObject
                val fields = value["fields"] as? JsonArray ?: return null
                val signature = (value["signature"] as? JsonPrimitive)?.contentOrNull ?: return null
                if (fields.size !in 1..64 || signature.length > 16_000 || fields.toString() != signature) return null
                CareerBrowserLoginSnapshot(fields, signature)
            }.getOrNull()
    }
}

internal class CareerBrowserLoginSelection(
    private val login: Int,
    private val password: Int?,
    private val signature: String,
) {
    fun json(): JsonObject =
        buildJsonObject {
            put("loginIndex", login)
            put("passwordIndex", password)
            put("signature", signature)
        }
}
