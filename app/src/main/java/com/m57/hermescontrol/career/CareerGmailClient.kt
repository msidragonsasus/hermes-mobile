package com.m57.hermescontrol.career

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.coroutines.coroutineContext

internal class CareerGmailClient(
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun verifyAccount(
        accessToken: String,
        expectedEmail: String,
    ) = withContext(Dispatchers.IO) {
        val profile = requestJson(accessToken, "profile")
        check(profile.string("emailAddress").equals(expectedEmail, ignoreCase = true)) {
            "選択したGoogleアカウントを確認できませんでした。"
        }
    }

    suspend fun listPage(
        accessToken: String,
        senders: List<String>,
        pageToken: String?,
    ): CareerGmailIdPage =
        withContext(Dispatchers.IO) {
            require(senders.isNotEmpty()) { "この企業の送信元メールアドレスが登録されていません。" }
            require(senders.size <= MAX_SENDERS) { "送信元メールアドレスは100件まで取得できます。" }
            val senderQuery = senders.sorted().joinToString(prefix = "{", postfix = "}", separator = " ") { "from:$it" }
            val query = URLEncoder.encode("$senderQuery -in:spam -in:trash", StandardCharsets.UTF_8.name())
            val token =
                pageToken
                    ?.let {
                        "&pageToken=" +
                            URLEncoder.encode(
                                it,
                                StandardCharsets.UTF_8.name(),
                            )
                    }.orEmpty()
            val listing = requestJson(accessToken, "messages?maxResults=25&q=$query$token")
            CareerGmailIdPage(
                listing["messages"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull {
                        it.jsonObject.string("id").takeIf(MESSAGE_ID::matches)
                    }.distinct(),
                listing.string("nextPageToken").takeIf(String::isNotBlank),
            )
        }

    private suspend fun requestJson(
        accessToken: String,
        resource: String,
    ): JsonObject {
        // Pace all company jobs (serialized by CareerGmailSync) below per-user burst limits.
        delay(250)
        return getJson(accessToken, resource)
    }

    internal suspend fun readMessage(
        accessToken: String,
        messageId: String,
        senders: Set<String>,
    ): CareerClientMailMessage? {
        val metadata = requestJson(accessToken, "messages/$messageId?format=metadata&metadataHeaders=From")
        val metadataSender = metadata.headers()["from"].orEmpty().emailAddress()
        if (metadataSender !in senders || metadata.labels().any { it == "SPAM" || it == "TRASH" }) return null
        val message = requestJson(accessToken, "messages/$messageId?format=full")
        val headers = message.headers()
        val fullSender = headers["from"].orEmpty().emailAddress()
        check(fullSender == metadataSender && fullSender in senders) { "メールの送信元を再確認してください。" }
        val receivedAt =
            message.string("internalDate").toLongOrNull()
                ?: throw IllegalStateException("メールの受信時刻を確認できませんでした。")
        val payload = message["payload"]?.jsonObject ?: JsonObject(emptyMap())
        val snippet = message.string("snippet").normalizeWhitespace().take(MAX_SNIPPET)
        val body = payload.readableBody().ifBlank { snippet }.take(MAX_BODY)
        return CareerClientMailMessage(
            id = messageId,
            thread_id = message.string("threadId").ifBlank { messageId },
            sender_address = fullSender,
            from_text = headers["from"].orEmpty().take(MAX_HEADER),
            subject = headers["subject"].orEmpty().ifBlank { "(件名なし)" }.take(MAX_HEADER),
            snippet = snippet,
            body = body,
            locator = "https://mail.google.com/mail/#all/$messageId",
            received_at = Instant.ofEpochMilli(receivedAt).toString(),
            gmail_unread = "UNREAD" in message.labels(),
        )
    }

    internal suspend fun getJson(
        accessToken: String,
        resource: String,
    ): JsonObject {
        val request =
            Request
                .Builder()
                .url("$GMAIL_API_BASE$resource")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .build()
        for (attempt in 0..6) {
            coroutineContext.ensureActive()
            val waitMillis =
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (response.isSuccessful) return json.parseToJsonElement(body).jsonObject
                    val retryable =
                        response.code in setOf(429, 500, 502, 503, 504) ||
                            (response.code == 403 && gmailApiReasons(body).any { it in GMAIL_RATE_LIMIT_REASONS })
                    val retryAfter =
                        response.header("Retry-After")?.let { value ->
                            value
                                .toLongOrNull()
                                ?.coerceAtLeast(0)
                                ?.takeIf { it <= 60 }
                                ?.times(1000)
                                ?: runCatching {
                                    val until =
                                        ZonedDateTime
                                            .parse(
                                                value,
                                                DateTimeFormatter.RFC_1123_DATE_TIME,
                                            ).toInstant()
                                    (until.toEpochMilli() - Instant.now().toEpochMilli()).coerceAtLeast(0)
                                }.getOrDefault(61_000L)
                        } ?: 0L
                    if (!retryable || attempt >= 6 || retryAfter > 60_000L) {
                        throw CareerGmailHttpException(response.code, gmailApiErrorMessage(response.code, body))
                    }
                    maxOf(1000L shl attempt, retryAfter)
                }
            // Close the failed response before waiting; retry this GET, not the entire mailbox.
            delay(waitMillis)
        }
        error("Gmailの再試行回数を超えました。時間をおいて再試行してください。")
    }

    private fun JsonObject.headers(): Map<String, String> =
        get("payload")
            ?.jsonObject
            ?.get("headers")
            ?.jsonArray
            .orEmpty()
            .mapNotNull { value ->
                val header = value.jsonObject
                val name = header.string("name").lowercase()
                val content = header.string("value")
                if (name.isBlank()) null else name to content
            }.toMap()

    private fun JsonObject.labels(): List<String> =
        get("labelIds")?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }

    private fun JsonObject.string(name: String): String = get(name)?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.readableBody(): String {
        findBodyData(
            "text/plain",
        )?.decodeBase64Url()?.normalizeMailBody()?.takeIf { it.isNotBlank() }?.let { return it }
        findBodyData(
            "text/html",
        )?.decodeBase64Url()?.stripHtml()?.normalizeMailBody()?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        return get("body")
            ?.jsonObject
            ?.string("data")
            .orEmpty()
            .decodeBase64Url()
            .normalizeMailBody()
    }

    private fun JsonObject.findBodyData(targetMimeType: String): String? {
        if (string("mimeType").equals(targetMimeType, ignoreCase = true)) {
            get("body")
                ?.jsonObject
                ?.string("data")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        val parts = get("parts") as? JsonArray ?: return null
        parts.forEach { part -> part.jsonObject.findBodyData(targetMimeType)?.let { return it } }
        return null
    }

    private companion object {
        const val GMAIL_API_BASE = "https://gmail.googleapis.com/gmail/v1/users/me/"
        const val MAX_SENDERS = 100
        const val MAX_BODY = 30_000
        const val MAX_HEADER = 300
        const val MAX_SNIPPET = 500
        val MESSAGE_ID = Regex("[a-fA-F0-9]{1,100}")
    }
}

internal fun gmailApiErrorMessage(
    code: Int,
    body: String,
): String {
    // Only classify known reason codes; never display Google's raw response or token details.
    val reasons = gmailApiReasons(body)
    val guidance =
        when {
            reasons.any { it in setOf("accessNotConfigured", "SERVICE_DISABLED") } -> {
                "Gmail APIが無効です。Google CloudのGmail API設定を確認してください。"
            }

            reasons.any { it in setOf("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT") } -> {
                "Gmailの読み取り許可が不足しています。Googleアカウントを選び直し、読み取りを許可してください。"
            }

            code == 429 || reasons.any { it in GMAIL_RATE_LIMIT_REASONS } -> {
                "Googleの一時的な取得上限に達しました。時間をおいて再試行してください。"
            }

            "dailyLimitExceeded" in reasons -> {
                "Googleの1日の取得上限に達しました。翌日以降に再試行してください。"
            }

            "domainPolicy" in reasons -> {
                "Googleアカウントの管理者によりGmail連携が制限されています。"
            }

            code == 401 -> {
                "Googleの認証が切れました。Googleアカウントを選び直してください。"
            }

            else -> {
                "Google連携を確認してください。"
            }
        }
    return "Gmailを読み取れませんでした（$code）。$guidance"
}

private val GMAIL_RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "RATE_LIMIT_EXCEEDED")

private fun gmailApiReasons(body: String): List<String> =
    runCatching {
        val error = Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        listOf("errors", "details").flatMap { key ->
            (error?.get(key) as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull
            }
        }
    }.getOrDefault(emptyList())

private fun String.emailAddress(): String = substringAfterLast('<', this).substringBefore('>', this).trim().lowercase()

private fun String.decodeBase64Url(): String =
    runCatching { String(Base64.getUrlDecoder().decode(this), StandardCharsets.UTF_8) }.getOrDefault("")

private fun String.stripHtml(): String =
    replace(Regex("(?is)<style.*?>.*?</style>"), " ")
        .replace(Regex("(?is)<script.*?>.*?</script>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n")
        .replace(Regex("<[^>]+>"), " ")

private fun String.normalizeMailBody(): String =
    lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString("\n")
        .trim()

private fun String.normalizeWhitespace(): String = replace(Regex("\\s+"), " ").trim()
