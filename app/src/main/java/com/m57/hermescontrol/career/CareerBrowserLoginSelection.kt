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

    /** Compatibility for a missing optional locator, never a substitute for credential authorization. */
    fun legacySelection(): CareerBrowserLoginSelection? {
        if (fields.size !in 2..64 || signature != fields.toString()) return null
        val nodes = fields.map { it as? JsonObject ?: return null }
        val pass = nodes.singleOrNull { it["type"] == JsonPrimitive("password") } ?: return null
        val form = (pass["form"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
        val texts =
            nodes.filter {
                it["form"] == JsonPrimitive(form) &&
                    (it["type"] as? JsonPrimitive)?.contentOrNull in setOf("text", "email", "tel")
            }
        val login = texts.singleOrNull() ?: return null
        if (listOf(login, pass).any { it["occupied"] != JsonPrimitive(false) }) return null
        val loginAutocomplete = (login["autocomplete"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val passAutocomplete = (pass["autocomplete"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (loginAutocomplete in setOf("one-time-code", "new-password", "current-password") ||
            passAutocomplete in setOf("one-time-code", "new-password")
        ) {
            return null
        }

        fun metadata(node: JsonObject): String =
            listOf("name", "id", "labelHints").joinToString(" ") { node[it]?.toString().orEmpty() }
        if (Regex(
                "password|passwd|captcha|one.?time|otp|verification|verifycode|search|companyid|tenant|パスワード|認証コード|確認コード|検索",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(metadata(login)) ||
            Regex(
                "newpassword|confirmpassword|repeatpassword|resetpassword|captcha|one.?time|otp|認証コード|確認コード|再設定|再入力|変更",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(metadata(pass))
        ) {
            return null
        }
        val loginIndex = (login["index"] as? JsonPrimitive)?.intOrNull ?: return null
        val passwordIndex = (pass["index"] as? JsonPrimitive)?.intOrNull ?: return null
        return selection(
            buildJsonObject {
                put("login_index", loginIndex)
                put("password_index", passwordIndex)
            },
        )
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
