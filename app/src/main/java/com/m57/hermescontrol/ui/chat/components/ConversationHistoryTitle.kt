package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.ui.chat.RichAnswerPrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A display-only title. Stored titles, transcript text and session IDs are never changed. */
internal fun conversationHistoryTitle(
    title: String?,
    preview: String?,
    fallback: String,
): String =
    sequenceOf(title, preview)
        .filterNotNull()
        .map { readableConversationTitle(RichAnswerPrompt.previewText(it)) }
        .firstOrNull { it.isNotBlank() }
        ?: fallback

private val envelopeHeader = Regex("""^\s*\[(?:slack\b|message\s+from\b|file\s*:)[^\]]*]\s*""", RegexOption.IGNORE_CASE)
private val metadataLine =
    Regex(
        """^\s*(?:(?:channel|user|message|thread)(?:_id|_ts)?|ts|timestamp|file(?:_path)?|path)\s*[:=]\s*\S+\s*$""",
        RegexOption.IGNORE_CASE,
    )
private val slackMention = Regex("""<(?:@[UW][A-Z0-9]+|#[CG][A-Z0-9]+)(?:\|[^>]+)?>""")
private val slackId = Regex("""\b[CUWGD][A-Z0-9]{8,}\b""")
private val absolutePath =
    Regex(
        """(?<![\p{L}\p{N}:])(?:[A-Za-z]:[\\/]|\\\\|/(?:home|Users|tmp|var|mnt|data|storage|sdcard|workspace|app|root|opt)/)[^\s<>\"']+""",
    )
private val whitespace = Regex("""\s+""")

private fun readableConversationTitle(raw: String): String {
    // Only unwrap JSON that identifies a Slack transport envelope. Arbitrary
    // JSON/code is legitimate conversation text and is left alone.
    val envelope = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    val isSlackEnvelope =
        envelope != null &&
            ("channel" in envelope || "channel_id" in envelope) &&
            ("user" in envelope || "user_id" in envelope || "ts" in envelope)
    val content =
        if (isSlackEnvelope) {
            (envelope?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        } else {
            raw
        }
    val text =
        content
            .lineSequence()
            .map { envelopeHeader.replace(it, "") }
            .filterNot { metadataLine.matches(it) }
            .joinToString(" ")
            .let { slackMention.replace(it, "") }
            .let { slackId.replace(it, "") }
            .let { absolutePath.replace(it, "") }
            .let { whitespace.replace(it, " ").trim().trim(':', '-', '|', ',', '[', ']', ' ') }
    if (text.none { it.isLetterOrDigit() }) return ""
    val codePoints = text.codePointCount(0, text.length)
    return if (codePoints > 96) text.substring(0, text.offsetByCodePoints(0, 96)) + "…" else text
}
