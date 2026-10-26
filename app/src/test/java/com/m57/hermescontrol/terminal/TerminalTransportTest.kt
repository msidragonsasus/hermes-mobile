package com.m57.hermescontrol.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTransportTest {
    @Test
    fun httpsIsAlwaysAllowed() {
        assertTrue(terminalTransportAllowed(true, "192.168.1.10"))
        assertTrue(terminalTransportAllowed(true, "example.com"))
    }

    @Test
    fun plainHttpOnlyOnLoopbackAndTailscale() {
        assertTrue(terminalTransportAllowed(false, "100.101.102.103"))
        assertTrue(terminalTransportAllowed(false, "100.64.0.1"))
        assertTrue(terminalTransportAllowed(false, "server.tail1234.ts.net"))
        assertTrue(terminalTransportAllowed(false, "127.0.0.1"))
        assertTrue(terminalTransportAllowed(false, "localhost"))
        assertFalse(terminalTransportAllowed(false, "192.168.1.10"))
        assertFalse(terminalTransportAllowed(false, "100.128.0.1"))
        assertFalse(terminalTransportAllowed(false, "100.63.255.255"))
        assertFalse(terminalTransportAllowed(false, "example.com"))
        assertFalse(terminalTransportAllowed(false, "evil.ts.net.example.com"))
    }
}
