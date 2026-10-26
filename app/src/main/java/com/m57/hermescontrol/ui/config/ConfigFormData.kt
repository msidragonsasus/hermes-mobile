package com.m57.hermescontrol.ui.config

import com.m57.hermescontrol.data.model.SchemaField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One renderable row of the config form: a schema-driven field or an
 * uncovered ("Other") config path. All rows are FLAT dot-paths — the nested
 * config response is flattened once on load so the form does O(1) lookups.
 */
data class ConfigRow(
    val key: String,
    val field: SchemaField?,
    val value: JsonElement?,
) {
    /** Human label: last path segment, underscores → spaces. */
    val label: String =
        key
            .split(".")
            .last()
            .replace("_", " ")
            .replaceFirstChar { it.uppercase() }

    /** Search/display text of the current value. */
    val valueText: String = value?.let(::jsonText) ?: ""

    val category: String = if (field == null) "Other" else field.category ?: "general"

    val isUncovered: Boolean = field == null
}

/** Flatten a nested config map into dot-path → leaf-value pairs. */
fun flattenConfig(nested: Map<String, JsonElement>): Map<String, JsonElement> {
    val flat = mutableMapOf<String, JsonElement>()

    fun walk(
        prefix: String,
        map: Map<String, JsonElement>,
    ) {
        for ((key, value) in map) {
            val dotPath = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is JsonObject) {
                walk(dotPath, value)
            } else {
                flat[dotPath] = value
            }
        }
    }
    walk("", nested)
    return flat
}

/** True when [dotPath] is a schema key or lives under one (ancestor-or-self). */
fun isCoveredBySchema(
    dotPath: String,
    schemaKeys: Set<String>,
): Boolean = schemaKeys.any { dotPath == it || dotPath.startsWith("$it.") }

/** Flat dot-paths present in config but not covered by the schema (sorted). */
fun collectUncoveredPaths(
    flatValues: Map<String, JsonElement>,
    schemaKeys: Set<String>,
): List<String> =
    flatValues.keys
        .filterNot { isCoveredBySchema(it, schemaKeys) }
        .sorted()

/**
 * Match a row against a search query. Covers the key, human label, schema
 * description, category, the CURRENT VALUE, and every select option — so
 * searching "gpt-4o", "daytona" or "kittentts" finds the right field even
 * though none of them appear in a key or description.
 */
fun rowMatchesQuery(
    row: ConfigRow,
    query: String,
): Boolean {
    if (query.isBlank()) return true
    val q = query.lowercase()
    if (row.key.lowercase().contains(q)) return true
    if (row.label.lowercase().contains(q)) return true
    row.field?.let { field ->
        if (field.description?.lowercase()?.contains(q) == true) return true
        if (field.category?.lowercase()?.contains(q) == true) return true
        field.options?.let { options ->
            if (options.any { it.lowercase().contains(q) }) return true
        }
    }
    return row.valueText.lowercase().contains(q)
}

/** Stable text form of a JSON value for display and search. */
fun jsonText(value: JsonElement): String =
    when (value) {
        is JsonPrimitive -> value.content
        is JsonArray -> value.toString()
        is JsonObject -> value.toString()
    }

/** Build a nested JSON changeset from dot-path changes; the server deep-merges it over disk. */
fun buildChangeset(changes: Map<String, JsonElement>): Map<String, JsonElement> {
    val root = mutableMapOf<String, Any>()
    for ((dotPath, value) in changes) {
        val parts = dotPath.split(".")
        var current = root
        for (i in 0 until parts.size - 1) {
            val key = parts[i]
            val existing = current[key]
            if (existing !is MutableMap<*, *>) {
                val newMap = mutableMapOf<String, Any>()
                current[key] = newMap
                current = newMap
            } else {
                @Suppress("UNCHECKED_CAST")
                current = existing as MutableMap<String, Any>
            }
        }
        current[parts.last()] = value
    }

    fun toJsonObject(map: Map<String, Any>): Map<String, JsonElement> =
        map.mapValues { (_, v) ->
            if (v is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                JsonObject(toJsonObject(v as Map<String, Any>))
            } else {
                v as JsonElement
            }
        }

    return toJsonObject(root)
}

/**
 * Loose equality for config leaves: `null`/JSON null are equal, and numbers compare by value so a
 * server `180` matches a preset written as `180.0`. Strings and booleans compare by content.
 */
fun sameConfigValue(
    a: JsonElement?,
    b: JsonElement?,
): Boolean {
    val aNull = a == null || a is JsonNull
    val bNull = b == null || b is JsonNull
    if (aNull || bNull) return aNull && bNull
    if (a is JsonPrimitive && b is JsonPrimitive) {
        val an = a.content.toDoubleOrNull()
        val bn = b.content.toDoubleOrNull()
        if (!a.isString && !b.isString && an != null && bn != null) return an == bn
        return a.content == b.content
    }
    return a == b
}
