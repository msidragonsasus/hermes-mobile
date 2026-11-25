package com.m57.hermescontrol.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerticalScrollThumbTest {
    @Test
    fun noThumbWhenContentFitsOrMeasurementsAreUnknown() {
        assertNull(verticalScrollThumbGeometry(240f, 0, 0, 16f))
        assertNull(verticalScrollThumbGeometry(0f, 120, 0, 16f))
        assertNull(verticalScrollThumbGeometry(240f, Int.MAX_VALUE, 0, 16f))
    }

    @Test
    fun measuredViewportAndOverflowDetermineGeometryBeforeAndAfterScroll() {
        assertEquals(VerticalScrollThumbGeometry(0f, 120f), verticalScrollThumbGeometry(240f, 240, 0, 16f))
        assertEquals(VerticalScrollThumbGeometry(60f, 120f), verticalScrollThumbGeometry(240f, 240, 120, 16f))
        assertEquals(VerticalScrollThumbGeometry(120f, 120f), verticalScrollThumbGeometry(240f, 240, 240, 16f))
        assertEquals(VerticalScrollThumbGeometry(0f, 180f), verticalScrollThumbGeometry(360f, 360, 0, 16f))
    }

    @Test
    fun minimumThumbStillReachesTheEndOfTheViewport() {
        assertEquals(VerticalScrollThumbGeometry(0f, 16f), verticalScrollThumbGeometry(100f, 1000, -1, 16f))
        assertEquals(VerticalScrollThumbGeometry(84f, 16f), verticalScrollThumbGeometry(100f, 1000, 1001, 16f))
    }
}
