package com.m57.hermescontrol.ui.chat

/** Add display capabilities without changing the stored user text or slash commands. */
internal object RichAnswerPrompt {
    private const val MARKER = "\n\n<hermes-mobile-display-context>\n"
    private const val END = "\n</hermes-mobile-display-context>"
    private val contextStart = Regex("\\r?\\n\\r?\\n<hermes-mobile-display-context>\\r?\\n")

    // Keep this identifying preamble stable so changes to the appended guides do not expose old history.
    private val legacyInstructions =
        """
        The user is reading this conversation in Hermes Mobile, which can render interactive visual answers.
        When it helps explain or compare something, or the user asks for a chart, diagram, calculator, game,
        or interactive tool, include a self-contained HTML/CSS/JavaScript fragment in a fenced hermes-ui block.
        Use inline SVG/canvas for charts and diagrams, accessible labels, responsive layout and rounded controls.
        The preview is offline and isolated: no external scripts/libraries, fetch, network, storage, forms that
        submit externally, native bridge, or tool calls. Use inline event handlers or inline scripts for local
        interactions. CSS --hermes-primary and the host light/dark color-scheme are available.
        Keep simple answers as text. Explain assumptions outside the UI. Never request credentials in the UI.
        To SHOW an image, include Markdown ![description](<https://...>) or ![description](</absolute/host/path>).
        Agent host files are downloaded through the authenticated gateway; do not use a phone-local file URI.
        Only show real images you found/created, and do not invent image URLs or claim an unavailable image exists.
        This is client display context; the user's request above remains the task.
        """.trimIndent()

    private val imageInstructions =
        legacyInstructions + "\n" +
            """
            Mobile image delivery: This conversation is displayed in the graphical Hermes Mobile app, not a
            text-only terminal. CLI/TUI wording in tools or older conversation history does not prevent images
            from being shown here. When the user asks to see an existing screenshot or image, include an actual
            Markdown image in the answer, for example ![Screenshot](</absolute/host/screenshot.png>).
            Do not stop at a code-formatted file path or say this TUI cannot attach images. The client resolves
            the host path through its authenticated managed-file endpoint. If that endpoint refuses or cannot
            find a file, report that specific limitation; do not infer delivery succeeded or that the client
            lacks image support. Do not repeat browser actions or authentication attempts just to share an
            already-saved screenshot. Check screenshots for passwords or one-time codes before sharing them.
            """.trimIndent()

    private val instructions = NativeUiInstructions.descriptor

    fun append(text: String): String =
        if (text.isBlank() || text.trimStart().startsWith("/") || text.endsWith(END)) {
            text
        } else {
            text + MARKER + instructions + END
        }

    /** What the user wrote, for bubbles, copy, edit, search and titles. Model and server text are unchanged. */
    fun visibleText(text: String): String = displayText(text).text

    /**
     * Removes what the app and server add around a user message: the server's trailing
     * "--- Attached Context ---" section, the app's display-context block (any catalog version),
     * and the leading attachment reference lines the app prefixes. Returns those references
     * so history rows can show them as attachment cards. Anything else stays as typed.
     */
    fun displayText(text: String): DisplayedUserText {
        var body = text
        var attachedRefs = emptySet<String>()
        val serverSections = stripServerSections(body)
        serverSections?.let { (rest, refs) ->
            body = rest
            attachedRefs = refs
        }
        val withoutContext = stripDisplayContext(body)
        // Without the app's block or the server's section this is plain user text: keep it whole.
        if (withoutContext == null && serverSections == null) return DisplayedUserText(text, emptyList())
        if (withoutContext != null) body = withoutContext
        return stripLeadingRefs(body, attachedRefs)
    }

    /**
     * Lenient form for server session previews, which are truncated anywhere: cuts at the start of
     * the app block or server section even when its end is missing, then drops leading refs.
     */
    fun previewText(text: String): String {
        val cut =
            listOfNotNull(
                previewBlockStart.find(text)?.range?.first,
                previewSectionStart.find(text)?.range?.first,
            ).minOrNull()
        val head = if (cut != null) text.substring(0, cut) else text
        // Previews may already be flattened to one line: "@file:/…/.hermes/… text".
        return previewRef.replace(stripLeadingRefs(head, emptySet()).text, "")
    }

    private fun stripDisplayContext(text: String): String? {
        val trimmed = text.trimEnd()
        if (!trimmed.endsWith("</hermes-mobile-display-context>")) return null
        val start = contextStart.findAll(trimmed).lastOrNull() ?: return null
        val suffix = trimmed.substring(start.range.last + 1).replace("\r\n", "\n")
        if (!suffix.endsWith(END)) return null
        val body = suffix.removeSuffix(END)
        // Recognize only the app's preamble, not an arbitrary user-authored tagged block. Every
        // catalog version, including unknown future ones, starts with the same first sentence.
        if (body != instructions &&
            body !in NativeUiInstructions.previousDescriptors &&
            body != legacyInstructions &&
            !body.startsWith(PREAMBLE + "\n")
        ) {
            return null
        }
        return text.substring(0, start.range.first)
    }

    /**
     * The Hermes core's @-reference expansion appends "--- Context Warnings ---" and then
     * "--- Attached Context ---" to the stored message. Each is removed from the end only when
     * it describes @-references, so a heading the user typed stays.
     */
    private fun stripServerSections(text: String): Pair<String, Set<String>>? {
        var body = text
        val refs = mutableSetOf<String>()
        var stripped = false
        for (marker in listOf(attachedContextStart, contextWarningsStart)) {
            val start = marker.findAll(body).lastOrNull() ?: continue
            val section = body.substring(start.range.last + 1)
            val found = sectionRef.findAll(section).map { it.value }.toSet()
            if (found.isEmpty() && "@ context injection" !in section) continue
            refs += found
            body = body.substring(0, start.range.first)
            stripped = true
        }
        return if (stripped) body to refs else null
    }

    /**
     * Drops the attachment refs the app puts before the text ("@file:<path>" lines, then a blank
     * line). A line counts only when it is the server's stored path (absolute, under .hermes) or
     * is named by the server's attached-context section, so a typed "@file:notes.md" stays.
     */
    private fun stripLeadingRefs(
        text: String,
        attachedRefs: Set<String>,
    ): DisplayedUserText {
        val lines = text.replace("\r\n", "\n").split('\n')
        val refs = mutableListOf<String>()
        for (line in lines) {
            val ref = leadingRef.matchEntire(line) ?: break
            val path = ref.groupValues[2]
            if (line !in attachedRefs && !(path.startsWith("/") && "/.hermes/" in path)) break
            refs += line
        }
        if (refs.isEmpty()) return DisplayedUserText(text, emptyList())
        val rest = lines.drop(refs.size)
        if (rest.isNotEmpty() && rest.first().isNotEmpty()) return DisplayedUserText(text, emptyList())
        return DisplayedUserText(rest.drop(1).joinToString("\n"), refs.map { it.substringAfter(':') })
    }

    private const val PREAMBLE =
        "The user is reading this conversation in Hermes Mobile, which can render interactive visual answers."
    private val attachedContextStart = Regex("\\r?\\n\\r?\\n--- Attached Context ---\\r?\\n")
    private val contextWarningsStart = Regex("\\r?\\n\\r?\\n--- Context Warnings ---\\r?\\n")

    // Reference kinds the core expands (file, folder, git, url, diff, staged) plus app image refs.
    private val sectionRef = Regex("@(?:(?:file|folder|git|url|image):[^\\s—]+|diff\\b|staged\\b)")
    private val leadingRef = Regex("@(file|image):(\\S+)")
    private val previewBlockStart = Regex("\\r?\\n\\s*<hermes-mobile-display-con")
    private val previewSectionStart = Regex("\\r?\\n\\s*--- (?:Attached Context|Context Warnings) ---")
    private val previewRef = Regex("^(?:@(?:file|image):/\\S*/\\.hermes/\\S+\\s+)+")
}

/** A user message as shown: typed text plus the attachment paths the app had prefixed. */
internal data class DisplayedUserText(
    val text: String,
    val fileRefs: List<String>,
)
