package com.m57.hermescontrol.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputTest {
    @Test
    fun normalInputCommitsUnicodeAndConvertsLineBreaksToTerminalEnter() {
        assertArrayEquals("日本語\r次の行\r".toByteArray(), terminalInput("日本語\r\n次の行", true))
        assertArrayEquals(" 認証コード ".toByteArray(), terminalInput(" 認証コード ", false))
    }

    @Test
    fun specialKeysRemainRawAndInputLimitUsesUtf8Bytes() {
        assertArrayEquals(byteArrayOf(3), terminalInput("\u0003", false))
        assertThrows(IllegalArgumentException::class.java) { terminalInput("あ".repeat(6000), false) }
        assertThrows(IllegalArgumentException::class.java) { terminalInput("", false) }
        assertFalse(validId("../other-session"))
    }

    @Test
    fun externalLinksAcceptOnlyExplicitHttpsWithoutEmbeddedCredentials() {
        assertEquals(
            listOf("https://claude.ai/oauth/authorize?state=example"),
            terminalLinks(
                "https://claude.ai/oauth/authorize?state=example javascript:alert(1) https://user:pass@evil.test ftp://a.test",
            ),
        )
    }

    @Test
    fun terminalReportsCannotCarryCommandsOrLineBreaks() {
        assertTrue(validTerminalReport("\u001b[24;80R"))
        assertTrue(validTerminalReport("\u001b[?1;2c"))
        assertFalse(validTerminalReport("\u001b[24;80R\recho injected\r"))
        assertFalse(validTerminalReport("\u001b]52;c;secret\u0007"))
        assertEquals("\u001bOA", terminalKeyBytes("\u001b[A", true))
        assertEquals("\u001b[A", terminalKeyBytes("\u001b[A", false))
        assertEquals("\u0003", terminalKeyBytes("\u0003", true))
    }
}
