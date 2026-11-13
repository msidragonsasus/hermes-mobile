package com.m57.hermescontrol.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A frozen plain-text copy of the terminal buffer, where the user pressed ([offset]) and where
 * the visible top row started ([top]). [word] selects the pressed word; otherwise only a caret.
 */
internal data class TerminalSelection(
    val text: String,
    val offset: Int,
    val top: Int = offset,
    val word: Boolean = true,
)

/** Parses `hermesTerminal.snapshot()`; offsets outside the text are clamped. */
internal fun parseTerminalSnapshot(
    json: String,
    word: Boolean,
): TerminalSelection? =
    runCatching {
        val value = Json.parseToJsonElement(json).jsonObject
        val text = value.getValue("text").jsonPrimitive.content
        TerminalSelection(
            text = text,
            offset =
                value
                    .getValue("offset")
                    .jsonPrimitive.int
                    .coerceIn(0, text.length),
            top =
                value
                    .getValue("top")
                    .jsonPrimitive.int
                    .coerceIn(0, text.length),
            word = word,
        )
    }.getOrNull()

/** The non-space run around [offset], or an empty range there when it falls on space. */
internal fun terminalWordRange(
    text: String,
    offset: Int,
): TextRange {
    val at = offset.coerceIn(0, text.length)

    fun word(i: Int) = i in text.indices && !text[i].isWhitespace()
    val anchor =
        when {
            word(at) -> at
            word(at - 1) -> at - 1
            else -> return TextRange(at)
        }
    var start = anchor
    while (word(start - 1)) start--
    var end = anchor
    while (word(end)) end++
    return TextRange(start, end)
}

/**
 * Selection mode: the buffer as read-only native text so Android's own handles and
 * Copy / Select all toolbar work like in a document. The text stays as it was when
 * opened, so new output cannot move the selection.
 */
@Composable
internal fun TerminalSelectionPane(
    selection: TerminalSelection,
    fontSize: Int,
    onCopyAll: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val colors = MaterialTheme.colorScheme
    val textSize = with(LocalDensity.current) { fontSize.dp.toSp() }
    var value by remember(selection) {
        val range =
            if (selection.word) {
                terminalWordRange(
                    selection.text,
                    selection.offset,
                )
            } else {
                TextRange(selection.offset)
            }
        mutableStateOf(TextFieldValue(selection.text, range))
    }
    var layout by remember(selection) { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    // Room below the last line for Android's drag handles.
    val handleRoom = with(LocalDensity.current) { 32.dp.roundToPx() }
    BackHandler(onBack = onDone)
    LaunchedEffect(selection, layout != null, viewport > 0) {
        val result = layout ?: return@LaunchedEffect
        if (viewport == 0) return@LaunchedEffect
        // Open where the live terminal was: its visible top row stays on top, unless the
        // pressed line would fall outside, never jumping to the tail.
        val topLine = result.getLineForOffset(selection.top.coerceIn(0, selection.text.length))
        val pressedLine = result.getLineForOffset(value.selection.start.coerceIn(0, selection.text.length))
        val pressedTop = result.getLineTop(pressedLine).toInt()
        val target =
            result.getLineTop(topLine).toInt().takeIf { pressedTop - it in 0 until viewport - handleRoom }
                ?: (pressedTop - viewport / 2)
        scroll.scrollTo(target.coerceAtLeast(0))
        runCatching { focus.requestFocus() }
    }
    Column(modifier.background(colors.surface).testTag("terminal_selection")) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(start = spacing.md, end = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "選択モード",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopyAll) { Text("すべてコピー") }
            TextButton(onClick = onDone) { Text("完了") }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewport = it.height }
                .verticalScroll(scroll),
        ) {
            BasicTextField(
                value = value,
                // Read-only: only the selection can change.
                onValueChange = { value = it.copy(text = selection.text) },
                readOnly = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = textSize, color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                onTextLayout = { layout = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // Same inset as the live terminal; extra bottom room keeps end handles draggable.
                        .padding(start = spacing.md, end = spacing.md, top = spacing.sm, bottom = spacing.sm + 32.dp)
                        .focusRequester(focus)
                        .testTag("terminal_selection_text"),
            )
        }
    }
}
