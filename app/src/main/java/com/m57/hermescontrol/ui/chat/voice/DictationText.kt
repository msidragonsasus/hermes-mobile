package com.m57.hermescontrol.ui.chat.voice

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal class DictationText {
    private var base: TextFieldValue? = null
    private var preview: TextFieldValue? = null
    private var insertedEnd = 0
    private var editedPreview = false

    fun update(
        current: TextFieldValue,
        spoken: String,
        final: Boolean,
    ): TextFieldValue {
        val original = base
        val visible = preview
        if (visible != null && current.text != visible.text) editedPreview = true
        if (editedPreview || spoken.isBlank()) {
            return if (final) finish(current, keepPreview = editedPreview) else current
        }

        val target =
            when {
                original == null -> current
                current.selection == visible?.selection -> original
                else -> reanchor(current, original)
            }
        val start = target.selection.min
        val end = target.selection.max
        val words = spoken.trim()
        val leading =
            if (target.text.getOrNull(start - 1).isAsciiAlphanumeric() &&
                words.first().isAsciiAlphanumeric()
            ) {
                " "
            } else {
                ""
            }
        val trailing =
            if (words.last().isAsciiAlphanumeric() &&
                target.text.getOrNull(end).isAsciiAlphanumeric()
            ) {
                " "
            } else {
                ""
            }
        val insertion = leading + words + trailing
        insertedEnd = start + insertion.length
        val result =
            TextFieldValue(
                target.text.replaceRange(start, end, insertion),
                TextRange(insertedEnd - trailing.length),
            )
        base = if (final) null else target
        preview = if (final) null else result
        return result
    }

    fun finish(
        current: TextFieldValue,
        keepPreview: Boolean,
    ): TextFieldValue {
        val original = base
        val visible = preview
        val result =
            if (!keepPreview && original != null && current.text == visible?.text) {
                if (current.selection == visible.selection) original else reanchor(current, original)
            } else {
                current
            }
        base = null
        preview = null
        editedPreview = false
        return result
    }

    private fun reanchor(
        current: TextFieldValue,
        original: TextFieldValue,
    ): TextFieldValue {
        fun map(offset: Int): Int =
            when {
                offset <= original.selection.min -> offset
                offset < insertedEnd -> original.selection.min
                else -> offset - insertedEnd + original.selection.max
            }

        return original.copy(
            selection = TextRange(map(current.selection.start), map(current.selection.end)),
            composition = null,
        )
    }

    private fun Char?.isAsciiAlphanumeric(): Boolean =
        this != null && (this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9')
}
