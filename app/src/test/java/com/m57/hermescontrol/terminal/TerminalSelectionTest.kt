package com.m57.hermescontrol.terminal

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSelectionTest {
    @Test
    fun pressedWordIsSelectedAcrossPunctuationButNotSpaces() {
        val text = "$ claude auth login\nhttps://example.test/a?b=c done"
        assertEquals(TextRange(2, 8), terminalWordRange(text, 4))
        val url = text.indexOf("https")
        assertEquals(TextRange(url, url + "https://example.test/a?b=c".length), terminalWordRange(text, url + 10))
    }

    @Test
    fun onlyPlainHttpsLinksWithoutCredentialsMayOpen() {
        assertTrue(terminalLinkAllowed("https://claude.ai/oauth/authorize?code=true&state=abc"))
        assertFalse(terminalLinkAllowed("http://example.test/"))
        assertFalse(terminalLinkAllowed("javascript:alert(1)"))
        assertFalse(terminalLinkAllowed("intent://scan#Intent;scheme=zxing;end"))
        assertFalse(terminalLinkAllowed("https://user:pass@example.test/"))
        assertFalse(terminalLinkAllowed("https:///nohost"))
        assertFalse(terminalLinkAllowed("https://example.test/" + "a".repeat(9000)))
        assertEquals(listOf("https://a.test/x"), terminalLinks("go https://a.test/x or https://u@b.test/ http://c.test"))
    }

    @Test
    fun pressAtWordEndSelectsThatWordAndSpaceGivesCaret() {
        assertEquals(TextRange(0, 3), terminalWordRange("abc  def", 3))
        assertEquals(TextRange(4), terminalWordRange("abc  def", 4))
        assertEquals(TextRange(0), terminalWordRange("", 5))
    }
}
