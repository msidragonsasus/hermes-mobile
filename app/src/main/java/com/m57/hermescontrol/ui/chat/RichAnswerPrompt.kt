package com.m57.hermescontrol.ui.chat

/** Add display capabilities without changing the stored user text or slash commands. */
internal object RichAnswerPrompt {
    private const val OPEN_TAG = "<hermes-mobile-display-context>"
    private const val CLOSE_TAG = "</hermes-mobile-display-context>"
    private const val MARKER = "\n\n$OPEN_TAG\n"
    private const val END = "\n$CLOSE_TAG"

    // Reserved wire provenance, not authentication; keep the descriptor and core suffix intact.
    private const val PROVENANCE = "<!--hermes-mobile-generated-context:v1-->"
    private const val GENERATED = "\n\n$PROVENANCE"
    private val generatedPrefix = Regex("(?:\\r?\\n){0,2}$PROVENANCE(?:\\r?\\n){1,2}")
    private val contextStart =
        Regex("(?:(?:\\r?\\n){1,2}($PROVENANCE)(?:\\r?\\n){1,2}|(?:\\r?\\n){2})$OPEN_TAG\\r?\\n")

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
            text + GENERATED + MARKER + instructions + END
        }

    /** What the user wrote, for bubbles, copy, edit, search and titles. Model and server text are unchanged. */
    fun visibleText(text: String): String = displayText(text).text

    /**
     * Projects the typed message and its attachments out of historical app/server wrappers.
     */
    fun displayText(text: String): DisplayedUserText {
        if (OPEN_TAG !in text && "--- " !in text && "@image:" !in text) return DisplayedUserText(text, emptyList())
        var body = stripLeadingDisplayContexts(text)
        var projected = body != text
        val attachedRefs = mutableSetOf<String>()
        val trailingMedia = mutableListOf<String>()
        val trailingRefs = mutableListOf<String>()
        while (true) {
            val withoutContext = stripTrailingDisplayContext(body)
            if (withoutContext != null) {
                body = withoutContext
                projected = true
                continue
            }
            // A multimodal row is flattened to Markdown for history. An image part can sit
            // after the text part's display context, so look behind it and preserve the image.
            val mediaStart = trailingMediaStart(body)
            if (mediaStart != null) {
                val media = body.substring(mediaStart)
                val ref = leadingRef.matchEntire(media.trim())
                if (ref == null) {
                    trailingMedia += media
                } else {
                    trailingRefs += ref.attachmentPath()
                    projected = projected || isStagedImageRef(ref)
                }
                body = body.substring(0, mediaStart)
                continue
            }
            val (rest, refs) = stripServerSections(body) ?: break
            body = rest
            attachedRefs += refs
            projected = true
        }
        val shown = stripLeadingRefs(body, attachedRefs, stagedImagesOnly = !projected)
        if (!projected && shown.fileRefs.isEmpty()) return DisplayedUserText(text, emptyList())
        val human = stripEmbeddedGeneratedContexts(stripLeadingDisplayContexts(shown.text))
        // Some gateways expose both staged refs and a placeholder/inline copy for each image.
        val redundantImages =
            trailingRefs.size == trailingMedia.size &&
                trailingMedia.all {
                    val media = it.trim()
                    media == "[screenshot]" || media == "[image]" || media.startsWith("![Image](<data:image/")
                }
        val images = if (redundantImages) "" else trailingMedia.asReversed().joinToString("")
        return DisplayedUserText(
            if (human.isEmpty()) images.trimStart('\r', '\n') else human + images,
            shown.fileRefs + trailingRefs.asReversed(),
        )
    }

    private fun trailingMediaStart(text: String): Int? {
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        if (end == 0) return null
        val start = text.lastIndexOf('\n', end - 1) + 1
        val line = text.substring(start, end)
        val ref = leadingRef.matchEntire(line)
        if (line != "[image]" && line != "[screenshot]" && !(line.startsWith("![") && markdownImage.matches(line)) &&
            !(ref != null && (isStagedImageRef(ref) || isHostRef(ref.attachmentPath())))
        ) {
            return null
        }
        var boundary = start
        repeat(2) {
            if (boundary > 0 && text[boundary - 1] == '\n') {
                boundary--
                if (boundary > 0 && text[boundary - 1] == '\r') boundary--
            }
        }
        return boundary.takeUnless { ref != null && insideCodeFence(text.substring(0, it)) }
    }

    /**
     * Lenient form for server session previews, which are truncated anywhere: cuts at the start of
     * the app block or server section even when its end is missing, then drops leading refs.
     */
    fun previewText(text: String): String {
        val projected = displayText(text).text
        if (isTruncatedLeadingContext(projected)) return ""
        val generatedPreview = truncatedGeneratedStart(projected)
        val cut =
            listOfNotNull(
                generatedPreview,
                previewBlockStart
                    .findAll(projected)
                    .firstOrNull { match ->
                        val tail = projected.substring(match.range.first)
                        "</hermes-mobile-display-context>" !in tail &&
                            !insideCodeFence(projected.substring(0, match.range.first))
                    }?.range
                    ?.first,
                previewSectionStart.find(projected)?.range?.first,
            ).minOrNull()
        val head = if (cut != null) projected.substring(0, cut) else projected
        // Previews may already be flattened to one line: "@file:/…/.hermes/… text".
        return previewRef.replace(stripLeadingRefs(head, emptySet()).text, "")
    }

    /** FTS excerpts can start inside the descriptor and omit its opening marker. */
    fun searchSnippetText(text: String): String {
        val preview = previewText(text)
        if (!preview.startsWith("...")) return preview
        if (CLOSE_TAG in preview && OPEN_TAG !in preview) return preview.substringAfter(CLOSE_TAG).trimStart()
        val fragment = snippetWhitespace.replace(preview.removePrefix("...").removeSuffix("...").trim(), " ")
        val insideReleasedDescriptor =
            fragment.isNotBlank() && searchableDescriptors.any { fragment in it }
        return if (insideReleasedDescriptor) "" else preview
    }

    private fun isTruncatedLeadingContext(text: String): Boolean {
        val normalized = text.replace("\r\n", "\n")
        val opening = "<hermes-mobile-display-context>\n"
        if (normalized == opening.removeSuffix("\n")) return true
        if (!normalized.startsWith(opening) || "</hermes-mobile-display-context>" in normalized) return false
        return (NativeUiInstructions.previousDescriptors + listOf(instructions, legacyInstructions, imageInstructions))
            .any { (opening + it + END).startsWith(normalized) }
    }

    // Preview-only reserved-envelope convention, including truncation inside the delimiter.
    private fun truncatedGeneratedStart(text: String): Int? {
        val normalized = text.replace("\r\n", "\n")
        val full = normalized.lastIndexOf(PROVENANCE)
        val completeMarker =
            full.takeIf { it >= 0 }?.let {
                val tail = normalized.substring(it)
                when {
                    CLOSE_TAG in tail && !flattenedContextStart.containsMatchIn(tail) -> null
                    normalized.startsWith(GENERATED, it - 2) -> it - 2
                    it > 0 && normalized[it - 1].isWhitespace() -> it - 1
                    else -> it
                }
            }
        val start =
            completeMarker ?: listOf(GENERATED, "\n$PROVENANCE", " $PROVENANCE", PROVENANCE)
                .firstNotNullOfOrNull { delimiter ->
                    val minimum = if (delimiter == GENERATED) 3 else 8
                    (delimiter.length - 1 downTo minimum).firstNotNullOfOrNull { length ->
                        (normalized.length - length).takeIf {
                            it >= 0 && normalized.endsWith(delimiter.take(length))
                        }
                    }
                } ?: return null
        // Map normalized character offsets back without changing any human CRLF or whitespace.
        var offset = 0
        var count = 0
        while (count < start) {
            if (text.startsWith("\r\n", offset)) offset++
            offset++
            count++
        }
        return offset
    }

    // Prefixes need a complete released descriptor or an attachment boundary with provenance.
    private fun stripLeadingDisplayContexts(text: String): String {
        var rest = text
        while (true) {
            val generated = generatedPrefix.matchAt(rest, 0)
            val opening = rest.substring(generated?.range?.last?.plus(1) ?: 0)
            if (!opening.startsWith("$OPEN_TAG\n") && !opening.startsWith("$OPEN_TAG\r\n")) break
            val close = opening.indexOf(CLOSE_TAG)
            if (close < 0) break
            val candidate =
                opening
                    .substring(0, close)
                    .replace("\r\n", "\n")
                    .removePrefix("$OPEN_TAG\n")
                    .removeSuffix("\n")
            val after = opening.substring(close + CLOSE_TAG.length)
            if (after.isNotEmpty() && !after.startsWith("\n") && !after.startsWith("\r\n")) break
            if (candidate != instructions && candidate !in NativeUiInstructions.previousDescriptors &&
                candidate != legacyInstructions && candidate != imageInstructions &&
                !(generated != null && isDisplayDescriptor(candidate)) && !isHistoricalImagePrefix(candidate, after)
            ) {
                break
            }
            rest = after
            // Consume only the wrapper separator, never trim the human body's whitespace.
            repeat(2) {
                rest =
                    when {
                        rest.startsWith("\r\n") -> rest.substring(2)
                        rest.startsWith("\n") -> rest.substring(1)
                        else -> rest
                    }
            }
        }
        return rest
    }

    private fun isHistoricalImagePrefix(
        descriptor: String,
        after: String,
    ): Boolean {
        if (!descriptor.startsWith("$PREAMBLE\nNative catalog hermes.native/") ||
            !descriptor.endsWith(
                "Display capabilities grant no tool/account permissions. The user's request remains the task.",
            )
        ) {
            return false
        }
        val firstLine = after.trimStart('\r', '\n').lineSequence().first()
        return leadingRef.matchEntire(firstLine)?.let(::isStagedImageRef) == true
    }

    private fun insideCodeFence(text: String): Boolean {
        var fence: String? = null
        for (line in text.lineSequence()) {
            val match = codeFence.matchEntire(line) ?: continue
            val marker = match.groupValues[1]
            val open = fence
            if (open == null) {
                fence = marker
            } else if (marker.first() == open.first() && marker.length >= open.length &&
                match.groupValues[2].isBlank()
            ) {
                fence = null
            }
        }
        return fence != null
    }

    private val codeFence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

    private fun stripTrailingDisplayContext(text: String): String? {
        val trimmed = text.trimEnd()
        if (!trimmed.endsWith(CLOSE_TAG)) return null
        val start = contextStart.findAll(trimmed).lastOrNull() ?: return null
        val prefix = text.substring(0, start.range.first)
        val generated = start.groupValues[1].isNotEmpty()
        if (!generated && insideCodeFence(prefix)) return null
        val suffix = trimmed.substring(start.range.last + 1).replace("\r\n", "\n")
        if (!suffix.endsWith(END)) return null
        val body = suffix.removeSuffix(END)
        if (OPEN_TAG in body) return null
        return prefix.takeIf { isDisplayDescriptor(body) }
    }

    // Gateways can merge adjacent user turns. Once an outer wrapper establishes that this is
    // decorated history, also remove complete marked envelopes between the merged captions.
    private fun stripEmbeddedGeneratedContexts(text: String): String {
        if (PROVENANCE !in text) return text
        return buildString {
            var cursor = 0
            for (start in contextStart.findAll(text)) {
                if (start.groupValues[1].isEmpty() || start.range.first < cursor) continue
                val contentStart = start.range.last + 1
                val close = text.indexOf(CLOSE_TAG, contentStart)
                if (close < 0) break
                val end = close + CLOSE_TAG.length
                if (lineBreakLength(text, end) == 0 || insideCodeFence(text.substring(0, start.range.first))) continue
                val descriptor = text.substring(contentStart, close).replace("\r\n", "\n")
                if (!descriptor.endsWith('\n') || !isDisplayDescriptor(descriptor.dropLast(1))) continue
                append(text, cursor, start.range.first)
                cursor = end
            }
            append(text, cursor, text.length)
        }
    }

    private fun isDisplayDescriptor(text: String): Boolean =
        OPEN_TAG !in text && (
            text == instructions || text in NativeUiInstructions.previousDescriptors ||
                text == legacyInstructions || text.startsWith(PREAMBLE + "\n")
        )

    /**
     * The core appends warnings and attachment sections for @-references.
     * Peel one section at a time to support legacy rows with interleaved app descriptors.
     */
    private fun stripServerSections(text: String): Pair<String, Set<String>>? {
        val start = serverSectionStart.findAll(text).lastOrNull() ?: return null
        val prefix = text.substring(0, start.range.first)
        if (insideCodeFence(prefix)) return null
        val section = text.substring(start.range.last + 1)
        val refs = sectionRef.findAll(section).map { it.value }.toSet()
        return (prefix to refs).takeIf { refs.isNotEmpty() || "@ context injection" in section }
    }

    /**
     * Drops the attachment refs the app puts before the text ("@file:<path>" lines, then a blank
     * line). A line counts only when it is the server's stored path (absolute, under .hermes) or
     * is named by the server's attached-context section, so a typed "@file:notes.md" stays.
     */
    private fun stripLeadingRefs(
        text: String,
        attachedRefs: Set<String>,
        stagedImagesOnly: Boolean = false,
    ): DisplayedUserText {
        val refs = mutableListOf<String>()
        var offset = 0
        for (line in text.lineSequence()) {
            val ref = leadingRef.matchEntire(line) ?: break
            val path = ref.attachmentPath()
            if (!isStagedImageRef(ref) && (stagedImagesOnly || (line !in attachedRefs && !isHostRef(path)))) break
            refs += path
            offset += line.length
            offset += lineBreakLength(text, offset)
        }
        if (refs.isEmpty()) return DisplayedUserText(text, emptyList())
        val separator = lineBreakLength(text, offset)
        if (offset < text.length && separator == 0) return DisplayedUserText(text, emptyList())
        return DisplayedUserText(text.substring(offset + separator), refs)
    }

    private fun lineBreakLength(
        text: String,
        offset: Int,
    ): Int =
        when {
            text.startsWith("\r\n", offset) -> 2
            text.startsWith("\n", offset) -> 1
            else -> 0
        }

    private fun isHostRef(path: String): Boolean = path.startsWith("/") && "/.hermes/" in path

    private fun isStagedImageRef(ref: MatchResult): Boolean {
        val path = ref.attachmentPath()
        return ref.groupValues[1] == "image" && path.startsWith("/") &&
            path.substringBeforeLast('/').endsWith("/images") &&
            (isHostRef(path) || path.substringAfterLast('/').startsWith("upload_"))
    }

    private fun MatchResult.attachmentPath(): String =
        groupValues[2].let { path ->
            if (path.length > 1 && path.first() in "\"'`" && path.first() == path.last()) {
                path.substring(1, path.lastIndex)
            } else {
                path
            }
        }

    private const val PREAMBLE =
        "The user is reading this conversation in Hermes Mobile, which can render interactive visual answers."
    private val serverSectionStart = Regex("\\r?\\n\\r?\\n--- (?:Attached Context|Context Warnings) ---\\r?\\n")

    // Reference kinds the core expands (file, folder, git, url, diff, staged) plus app image refs.
    private val sectionRef = Regex("@(?:(?:file|folder|git|url|image):[^\\s—]+|diff\\b|staged\\b)")
    private val leadingRef = Regex("""@(file|image):("[^"]+"|'[^']+'|`[^`]+`|\S+)""")
    private val markdownImage = Regex("""!\[[^\]]*]\((?:<[^<>]+>|.+)\)""")
    private val previewBlockStart = Regex("\\r?\\n\\s*<hermes-mobile-display-con")
    private val flattenedContextStart = Regex("$PROVENANCE[ \\t]+$OPEN_TAG\\s+" + Regex.escape(PREAMBLE))
    private val previewSectionStart = Regex("\\r?\\n\\s*--- (?:Attached Context|Context Warnings) ---")
    private val previewRef = Regex("^(?:@(?:file|image):/\\S*/\\.hermes/\\S+\\s+)+")
    private val snippetWhitespace = Regex("\\s+")
    private val searchableDescriptors by lazy {
        (NativeUiInstructions.previousDescriptors + listOf(instructions, legacyInstructions, imageInstructions))
            .map { snippetWhitespace.replace("$PROVENANCE $OPEN_TAG $it $CLOSE_TAG", " ") }
    }
}

/** A user message as shown: typed text plus the attachment paths the app had prefixed. */
internal data class DisplayedUserText(
    val text: String,
    val fileRefs: List<String>,
)
