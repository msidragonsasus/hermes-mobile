package com.m57.hermescontrol.ui.chat.components

internal data class QuotedReply(
    val quote: String,
    val body: String,
)

/** Decode the same leading quote block written by [quotedReplyMessage], including older history. */
internal fun parseQuotedReply(text: String): QuotedReply? {
    if (!text.startsWith("> ")) return null
    val lines = text.lines()
    val quoteEnd = lines.indexOfFirst { it != ">" && !it.startsWith("> ") }.takeIf { it >= 0 } ?: lines.size
    val quote = lines.subList(0, quoteEnd).joinToString("\n") { it.removePrefix(">").removePrefix(" ") }
    if (quote.isBlank()) return null

    // Consume the wire separator once. Any other whitespace belongs to the user's draft.
    val bodyStart = quoteEnd + if (lines.getOrNull(quoteEnd)?.isBlank() == true) 1 else 0
    return QuotedReply(quote, lines.subList(bodyStart, lines.size).joinToString("\n"))
}

/** Nested or historical quote markers are presentation syntax in both previews. */
internal fun quotePreviewText(quote: String): String {
    if ('>' !in quote) return quote
    return quote.lineSequence().joinToString("\n") { line ->
        var text = line
        while (text == ">" || text.startsWith("> ")) {
            text = text.removePrefix(">").removePrefix(" ")
        }
        text
    }
}
