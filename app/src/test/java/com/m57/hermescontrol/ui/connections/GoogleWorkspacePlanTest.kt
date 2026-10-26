package com.m57.hermescontrol.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleWorkspacePlanTest {
    private val calendar = requireNotNull(externalService("calendar"))
    private val drive = requireNotNull(externalService("drive"))

    @Test
    fun calendarAndDriveUseGoogleAccountPickerWhenServerSupportsIt() {
        assertEquals(ServiceConnectPlan.GoogleWorkspace, serviceConnectPlan(calendar, emptyList(), emptyList(), googleWorkspaceReady = true))
        assertEquals(ServiceConnectPlan.GoogleWorkspace, serviceConnectPlan(drive, emptyList(), emptyList(), googleWorkspaceReady = true))
        assertEquals("Googleに進む", proceedLabel(calendar, ServiceConnectPlan.GoogleWorkspace))
        assertTrue(connectNotes(calendar, ServiceConnectPlan.GoogleWorkspace).any { "カレンダー・ドライブ" in it })
    }

    @Test
    fun otherServicesAndOlderServersKeepTheirPlans() {
        val figma = requireNotNull(externalService("figma"))
        assertFalse(serviceConnectPlan(figma, emptyList(), emptyList(), googleWorkspaceReady = true) is ServiceConnectPlan.GoogleWorkspace)
        assertTrue(serviceConnectPlan(calendar, emptyList(), emptyList()) is ServiceConnectPlan.Unavailable)
    }

    @Test
    fun canConnectNeedsAServerThatCanAddAccounts() {
        val ready = GoogleConnectionStatus(state = "disconnected", can_add = true)
        assertTrue(GoogleWorkspaceUiState(status = ready, loaded = true).canConnect())
        assertFalse(GoogleWorkspaceUiState(status = ready, loaded = true, unsupported = true).canConnect())
        assertFalse(GoogleWorkspaceUiState(status = ready.copy(state = "setup_required"), loaded = true).canConnect())
        assertFalse(GoogleWorkspaceUiState(status = ready.copy(can_add = false), loaded = true).canConnect())
        assertFalse(GoogleWorkspaceUiState(loaded = true).canConnect())
    }
}
