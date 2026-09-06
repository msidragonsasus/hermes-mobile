package com.m57.hermescontrol.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.LandingScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SessionRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttentionNavigationTest {
    private val ref =
        RequestRef(
            ConnectionScope("server", "connection", "profile", "process", 1),
            SessionRef("runtime"),
            "request",
            "q2",
        )
    private val shell =
        ShellNavigation(
            mutableStateOf(ShellTab.TODAY),
            ShellTab.entries.associateWith { NavBackStack<NavKey>(it.root) },
        )

    @After
    fun clean() {
        NavigationController.openChatSession("cleanup")
        NavigationController.consumePendingChatNavigation()
        NavigationController.detachShell(shell)
        NavigationController.backStack = null
    }

    @Test
    fun notificationWaitsForAuthenticationThenOpensExactQuestionAndDeduplicates() {
        val auth = NavBackStack<NavKey>(LandingScreen)
        NavigationController.attachAuth(auth)
        NavigationController.openAttentionFromNotification(ref)
        NavigationController.revealPendingAttention()
        assertEquals(listOf(LandingScreen), auth.toList())
        assertEquals(ref, NavigationController.pendingAttentionNavigation?.ref)
        NavigationController.attachShell(shell)
        NavigationController.revealPendingAttention()
        NavigationController.openAttentionFromNotification(ref)
        NavigationController.revealPendingAttention()
        assertEquals(listOf(TodayScreen, AttentionDetailKey(ref)), shell.backStack.toList())
        assertNull(NavigationController.pendingAttentionNavigation)
    }

    @Test
    fun pendingStateRestoresBeforeRevealAndConsumedStateCannotReopen() {
        NavigationController.backStack = null
        NavigationController.openAttentionFromNotification(ref)
        val saved = NavigationController.savePendingAttentionNavigation()
        NavigationController.openChatSession("latest")
        NavigationController.consumePendingChatNavigation()
        NavigationController.restorePendingAttentionNavigation(saved)
        assertEquals(ref, NavigationController.pendingAttentionNavigation?.ref)
        NavigationController.attachShell(shell)
        NavigationController.revealPendingAttention()
        NavigationController.restorePendingAttentionNavigation(saved)
        assertNull(NavigationController.pendingAttentionNavigation)
        assertEquals(AttentionDetailKey(ref), shell.backStack.last())
    }

    @Test
    fun latestExplicitChatOrAttentionDestinationWins() {
        NavigationController.attachShell(shell)
        NavigationController.openChatSessionFromNotification("saved")
        NavigationController.openAttentionFromNotification(ref)
        assertNull(NavigationController.pendingChatNavigation)
        NavigationController.openChatSession("new saved")
        assertNull(NavigationController.pendingAttentionNavigation)
        assertEquals("new saved", NavigationController.pendingSessionId)
    }
}
