package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class HostMediaParityTest {
    @Test
    fun remoteHomePathIsPreservedForTheGatewayToResolve() {
        assertEquals("~/reports/図表.png", HostMediaExtractor.extract("MEDIA:~/reports/図表.png").single().path)
    }
}
