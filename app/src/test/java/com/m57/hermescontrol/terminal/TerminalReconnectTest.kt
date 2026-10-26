package com.m57.hermescontrol.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalReconnectTest {
    @Test
    fun connectionDropsAndGatewayErrorsAreRetried() {
        assertTrue(transientTerminalError(java.io.IOException("unexpected end of stream")))
        assertTrue(transientTerminalError(java.net.ConnectException("refused")))
        assertTrue(transientTerminalError(TerminalHttpException(502, "bad gateway")))
        assertTrue(transientTerminalError(TerminalHttpException(504, "timeout")))
    }

    @Test
    fun authMissingPluginAndStateErrorsStop() {
        assertFalse(transientTerminalError(TerminalHttpException(401, "login")))
        assertFalse(transientTerminalError(TerminalHttpException(404, "missing")))
        assertFalse(transientTerminalError(TerminalHttpException(503, "disabled")))
        assertFalse(transientTerminalError(IllegalStateException("scope")))
    }
}
