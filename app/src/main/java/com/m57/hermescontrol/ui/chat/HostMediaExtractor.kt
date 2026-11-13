package com.m57.hermescontrol.ui.chat

/**
 * Issue #724: parse agent-delivered `MEDIA:<path>` directives out of message
 * text so they can be attached (and rendered) as real files.
 *
 * The gateway's WebSocket stream delivers the raw `MEDIA:<path>` directive the
 * desktop app resolves via `mediaExternalUrl` — an authenticated
 * `/api/files/download?path=<enc>&token=<enc>` URL that streams the host file's
 * bytes over HTTP. Mobile turns each directive into an [Attachment] (see
 * [com.m57.hermescontrol.ui.chat.ChatViewModel.attachHostMedia]) so the
 * renderer can show images inline and offer every other file type as a
 * tappable, fetchable attachment — including on a *remote* phone.
 *
 * Matches ANY absolute (or `~/`) host path regardless of extension, so images,
 * audio, video, CSV, PDF and arbitrary files are all supported. The pure parse
 * logic lives here so it can be unit-tested without an Android context.
 */
internal object HostMediaExtractor {
    /** A single `MEDIA:<path>` directive found in message text. */
    data class Item(
        val match: String, // the full matched directive (for stripping)
        val path: String, // normalized absolute path
    )

    /** Matches `MEDIA:<path>` (any extension), supporting quotes/backticks around the path. */
    private val MEDIA_TAG_RE =
        Regex(
            """[`"']?MEDIA:\s*(?:"([^"]+)"|'([^']+)'|`([^`]+)`|((?:~|/|[A-Za-z]:)[^\s`"')]*))[`"']?""",
            RegexOption.IGNORE_CASE,
        )

    /** Extract every `MEDIA:<path>` directive from [text] as a normalized item. */
    fun extract(text: String): List<Item> {
        if (!text.contains("MEDIA:")) return emptyList()
        return MEDIA_TAG_RE
            .findAll(text)
            .mapNotNull { m ->
                val raw = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                val path = normalizePath(raw) ?: return@mapNotNull null
                Item(match = m.value, path = path)
            }.toList()
    }

    /** Remove all `MEDIA:<path>` directives from [text] (leaves other content). */
    fun strip(text: String): String {
        if (!text.contains("MEDIA:")) return text
        return MEDIA_TAG_RE
            .replace(text) { "" }
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    /** Preserve the gateway's ~/ anchor; never expand it using the phone's HOME. */
    internal fun normalizePath(raw: String): String? =
        com.m57.hermescontrol.data.remote.GatewayFileClient
            .normalizePath(raw)
}
