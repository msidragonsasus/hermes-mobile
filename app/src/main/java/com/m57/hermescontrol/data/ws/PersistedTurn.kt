package com.m57.hermescontrol.data.ws

import kotlinx.serialization.Serializable

/** Individual addresses remain authoritative even when exhaustive coverage is false. */
@Serializable
data class PersistedTurn(
    val rowIds: List<Long>,
    val complete: Boolean,
    val userRowId: Long? = null,
    val userRowIds: List<Long>? = null,
    val finalAssistantRowId: Long? = null,
    val submissionId: String? = null,
) {
    companion object {
        fun parse(value: Any?): PersistedTurn? {
            val map = value as? Map<*, *> ?: return null

            fun ids(value: Any?): List<Long>? {
                val values = value as? List<*> ?: return null
                val ids = values.map { positiveRowId(it) ?: return null }
                return ids.takeIf { it.size == it.distinct().size }
            }
            val rows = ids(map["row_ids"]) ?: return null
            if (rows.isEmpty()) return null
            val complete = map["complete"] as? Boolean ?: return null
            val user = map["user_row_id"]?.let { positiveRowId(it) ?: return null }
            val users = map["user_row_ids"]?.let { ids(it) ?: return null }
            val final = map["final_assistant_row_id"]?.let { positiveRowId(it) ?: return null }
            if ((listOfNotNull(user, final) + users.orEmpty()).any { it !in rows }) return null
            val submission = map["submission_id"]?.let { (it as? String)?.takeIf(String::isNotBlank) ?: return null }
            if (complete && (user == null || final == null)) return null
            return PersistedTurn(rows, complete, user, users, final, submission)
        }
    }
}

internal fun positiveRowId(value: Any?): Long? =
    when (value) {
        is Int -> value.toLong().takeIf { it > 0 }
        is Long -> value.takeIf { it > 0 }
        else -> null // No floats, booleans, numeric strings, truncation or normalization.
    }
