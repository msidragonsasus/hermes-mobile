package com.m57.hermescontrol.career

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class CareerEntity(
    val kind: String,
    val id: String,
    val version: Int,
    val data: JsonObject,
    val updated_at: String = "",
    val dirty: Int = 0,
) {
    val title: String get() =
        when (kind) {
            "profile" -> {
                "プロフィール"
            }

            "companies" -> {
                data.text("name", "title") ?: id
            }

            "applications" -> {
                data.text("title")?.let { if (it == "旧Shukatsuからの応募・選考") "応募・選考" else it } ?: "応募・選考"
            }

            // A proposal's own id is a UUID; name it by what it would save instead.
            "proposals" -> {
                val target = data.text("kind")?.let(::careerKind) ?: "データ"
                val subject =
                    (data["data"] as? JsonObject)?.text("title", "name", "claim", "statement", "alias", "label")
                "${target}の保存案" + (subject?.let { "：$it" } ?: "")
            }

            else -> {
                data.text("title", "name", "claim", "statement", "alias", "label") ?: id
            }
        }
}

@Serializable
data class CareerSnapshot(
    val api_version: Int,
    val entities: List<CareerEntity> = emptyList(),
    val notices: List<JsonObject> = emptyList(),
    val conflicts: List<JsonObject> = emptyList(),
    val sync: JsonObject = JsonObject(emptyMap()),
    val research: JsonObject = JsonObject(emptyMap()),
)

fun JsonObject.text(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { (get(it) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

fun JsonObject.with(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(this + fields)

fun careerScope(): String =
    listOf(
        com.m57.hermescontrol.data.local.AuthManager.baseUrlFlow.value,
        com.m57.hermescontrol.data.local.AuthManager.selectedProfileFlow.value
            .orEmpty(),
        com.m57.hermescontrol.data.local.AuthManager.activeProfileId.value
            .orEmpty(),
    ).joinToString("\n")
