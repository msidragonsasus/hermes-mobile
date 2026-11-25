package com.m57.hermescontrol.ui.chat.voice

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTextTest {
    @Test
    fun replacesSelectionAndAdvancesThroughJapaneseSegments() {
        val text = DictationText()
        val draft = TextFieldValue("前削除後", TextRange(3, 1))
        val first = text.update(draft, "今日", final = true)
        assertEquals(TextFieldValue("前今日後", TextRange(3)), first)
        assertEquals("前今日は後", text.update(first, "は", final = true).text)
    }

    @Test
    fun replacesPartialAndSpacesAsciiBoundaries() {
        val text = DictationText()
        val draft = TextFieldValue("A2", TextRange(1))
        val preview = text.update(draft, "b", final = false)
        assertEquals("A b 2", preview.text)
        val first = text.update(preview, "beta", final = true)
        assertEquals("A beta 2", first.text)
        assertEquals("A beta gamma 2", text.update(first, "gamma", final = true).text)
    }

    @Test
    fun movingCursorRelocatesCurrentSegment() {
        val text = DictationText()
        val draft = TextFieldValue("前後", TextRange(1))
        val preview = text.update(draft, "仮", final = false)
        assertEquals("前仮後", preview.text)
        val moved = preview.copy(selection = TextRange(preview.text.length))
        assertEquals("前後確定", text.update(moved, "確定", final = true).text)
    }

    @Test
    fun editingPreviewKeepsManualTextAndReanchorsNextSegment() {
        val text = DictationText()
        val preview = text.update(TextFieldValue("前後", TextRange(1)), "仮", final = false)
        val edited = preview.copy(text = "前手動後", selection = TextRange(0))
        val settled = text.update(edited, "確定", final = true)
        assertEquals(edited, settled)
        assertEquals("次前手動後", text.update(settled, "次", final = true).text)
    }

    @Test
    fun emptyFinalRestoresTheOriginalSelection() {
        val text = DictationText()
        val draft = TextFieldValue("既存", TextRange(0, 2))
        val preview = text.update(draft, "仮", final = false)
        assertEquals(draft, text.update(preview, "", final = true))
    }
}
