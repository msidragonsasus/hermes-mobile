package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.remote.HermesApiService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody

/** Read only, exact profile/DB key. Never search other profiles or substitute a runtime ID. */
internal class GroupHistoryReader(
    private val api: HermesApiService,
) {
    suspend fun read(
        member: String,
        storedId: String,
        isCurrent: () -> Boolean,
    ): GroupHistoryRead {
        var rows = emptyList<SessionMessage>()
        var previous = emptyList<SessionMessage>()
        var direction: String? = null
        repeat(MAX_PAGES) { page ->
            if (!isCurrent()) return issue(GroupHistoryIssue.CONNECTION_CHANGED)
            val offset = page * PAGE_SIZE
            val response =
                api.getSessionMessages(
                    storedId,
                    limit = PAGE_SIZE,
                    offset = offset,
                    order = "latest",
                    profile = member,
                )
            if (!isCurrent()) return issue(GroupHistoryIssue.CONNECTION_CHANGED)
            when (response.code()) {
                401, 403 -> return issue(GroupHistoryIssue.AUTH_REQUIRED)
                404 -> return diagnose404(member, storedId, detail(response.errorBody()), isCurrent)
            }
            if (!response.isSuccessful) return issue(GroupHistoryIssue.UNAVAILABLE)
            val body = response.body() ?: return issue(GroupHistoryIssue.UNAVAILABLE)
            if (!body.session_id.isNullOrBlank() && body.session_id != storedId) {
                // Resume/compaction aliases need lineage proof. Do not silently adopt another ID.
                return issue(GroupHistoryIssue.ID_MISMATCH)
            }
            val echo = body.pagination
            if ((echo?.offset != null && echo.offset != offset) ||
                (echo?.returned != null && echo.returned != body.messages.size)
            ) {
                return issue(GroupHistoryIssue.UNAVAILABLE)
            }
            if (page == 0) direction = echo?.order?.takeIf { it == "latest" || it == "oldest" }
            if (page > 0 && (echo?.order != direction || body.messages == previous)) {
                return GroupHistoryRead(messages = rows, completeHistory = false)
            }
            previous = body.messages
            rows = if (direction == "latest") body.messages + rows else rows + body.messages
            if (body.messages.size < PAGE_SIZE) {
                return GroupHistoryRead(
                    messages = rows,
                    completeHistory = body.total == null || rows.size >= body.total,
                )
            }
            if (direction == null || body.messages.size > PAGE_SIZE) {
                // Old servers may ignore order/offset. Their first page is useful evidence,
                // but is not proof that an absent request never existed.
                return GroupHistoryRead(messages = rows, completeHistory = false)
            }
        }
        return GroupHistoryRead(messages = rows, completeHistory = false)
    }

    private suspend fun diagnose404(
        member: String,
        storedId: String,
        messageDetail: String?,
        isCurrent: () -> Boolean,
    ): GroupHistoryRead {
        if (messageDetail == "Profile '$member' does not exist.") return issue(GroupHistoryIssue.PROFILE_NOT_FOUND)
        if (!isCurrent()) return issue(GroupHistoryIssue.CONNECTION_CHANGED)
        // A route/proxy 404 is not a missing session. Corroborate with the exact-key detail
        // endpoint in the SAME profile; never enumerate sessions or guess an alternative home.
        val response = api.getSessionDetail(storedId, profile = member)
        if (!isCurrent()) return issue(GroupHistoryIssue.CONNECTION_CHANGED)
        if (response.code() == 401 || response.code() == 403) return issue(GroupHistoryIssue.AUTH_REQUIRED)
        val sessionDetail = detail(response.errorBody())
        return when {
            response.code() == 404 && sessionDetail == "Profile '$member' does not exist." -> {
                issue(GroupHistoryIssue.PROFILE_NOT_FOUND)
            }

            response.code() == 404 && messageDetail == "Session not found" && sessionDetail == "Session not found" -> {
                issue(GroupHistoryIssue.NOT_FOUND)
            }

            response.code() == 404 || response.isSuccessful -> {
                issue(GroupHistoryIssue.ENDPOINT_UNAVAILABLE)
            }

            else -> {
                issue(GroupHistoryIssue.UNAVAILABLE)
            }
        }
    }

    private fun issue(value: GroupHistoryIssue) = GroupHistoryRead(issue = value)

    /** Bound parsing; only recognized protocol literals escape this function, never raw server text. */
    private fun detail(body: ResponseBody?): String? =
        body?.use {
            runCatching {
                val buffer = CharArray(4097)
                var count = 0
                val reader = it.charStream()
                while (count < buffer.size) {
                    val read = reader.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > 4096) return@runCatching null
                Json
                    .parseToJsonElement(String(buffer, 0, count))
                    .jsonObject["detail"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
        }

    private companion object {
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 5
    }
}
