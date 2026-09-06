package com.m57.hermescontrol

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.navigation.NavigationRole
import com.m57.hermescontrol.navigation.ShellNavigation
import com.m57.hermescontrol.navigation.ShellTab
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PendingChatNavigation(
    val sessionId: String,
    val scrollToBottom: Boolean,
    val requestId: Long,
)

/**
 * Central navigation controller with deduplication guard.
 *
 * Top-level primary screens (drawer items) clear the back stack and become the new
 * root — this matches navigation drawer patterns where switching top-level sections
 * resets the stack.
 *
 * B7 (Jun 18 2026): Never call `backStack.add()` directly from UI callbacks.
 * Always route through [navigateTo] to prevent stacking duplicate screen
 * entries that compete for touch events.
 */
object NavigationController {
    var backStack: NavBackStack<NavKey>? = null
    private var shellNavigation: ShellNavigation? = null

    internal fun attachAuth(stack: NavBackStack<NavKey>) {
        shellNavigation = null
        backStack = stack
    }

    internal fun attachShell(shell: ShellNavigation) {
        shellNavigation = shell
        backStack = shell.backStack
    }

    internal fun detachShell(shell: ShellNavigation) {
        if (shellNavigation !== shell) return
        shellNavigation = null
        backStack = null
    }

    fun selectTab(tab: ShellTab) {
        val shell = shellNavigation ?: return
        shell.select(tab)
        // Update synchronously: a second callback can arrive before recomposition.
        backStack = shell.backStack
    }

    var pendingChatNavigation: PendingChatNavigation? by mutableStateOf(null)
        private set
    val pendingSessionId: String? get() = pendingChatNavigation?.sessionId

    private var nextChatNavigationRequestId = 0L
    private var lastConsumedChatNavigationId = 0L

    fun roleOf(key: NavKey): NavigationRole? = ScreenRegistry.contributions.forKey(key::class)?.role

    /**
     * Top-level primary screens (all drawer-accessible screens).
     * Navigating to any of these clears the stack to `[ChatScreen, key]` (or `[ChatScreen]` for Chat),
     * ensuring swiping back returns to ChatScreen.
     */
    fun isPrimaryScreen(key: NavKey): Boolean = roleOf(key) in setOf(NavigationRole.ROOT, NavigationRole.ADVANCED)

    fun navigateTo(key: NavKey) {
        val shell = shellNavigation
        if (shell != null && roleOf(key) != NavigationRole.AUTH) {
            shell.navigate(key)
            backStack = shell.backStack
            return
        }
        val stack = backStack ?: return
        if (stack.lastOrNull() == key) return

        if (key == ChatScreen) {
            stack.clear()
            stack.add(ChatScreen)
            return
        }

        if (key == LandingScreen) {
            stack.clear()
            stack.add(LandingScreen)
            return
        }

        if (isPrimaryScreen(key)) {
            stack.clear()
            stack.add(ChatScreen)
            stack.add(key)
            return
        }

        // Subscreen / drill-down navigation: append to current stack
        stack.add(key)
    }

    fun openChatSession(sessionId: String) {
        queueChatNavigation(sessionId, scrollToBottom = false)
    }

    fun openChatSessionFromNotification(sessionId: String) {
        queueChatNavigation(sessionId, scrollToBottom = true)
    }

    private fun queueChatNavigation(
        sessionId: String,
        scrollToBottom: Boolean,
    ) {
        if (sessionId.isBlank()) return
        pendingChatNavigation =
            PendingChatNavigation(
                sessionId = sessionId,
                scrollToBottom = scrollToBottom,
                requestId = ++nextChatNavigationRequestId,
            )
        // A notification received before login stays pending until an authenticated stack attaches.
        if (backStack?.any { roleOf(it) == NavigationRole.AUTH } != true) navigateTo(ChatScreen)
    }

    fun savePendingChatNavigation(): String? = pendingChatNavigation?.let { Json.encodeToString(it) }

    fun restorePendingChatNavigation(encoded: String?) {
        val restored =
            encoded?.let { runCatching { Json.decodeFromString<PendingChatNavigation>(it) }.getOrNull() }
                ?: return
        if (restored.sessionId.isBlank() || restored.requestId <= 0 || restored.requestId == Long.MAX_VALUE) return
        if (restored.requestId <= lastConsumedChatNavigationId ||
            restored.requestId <= (pendingChatNavigation?.requestId ?: 0)
        ) {
            return
        }
        nextChatNavigationRequestId = maxOf(nextChatNavigationRequestId, restored.requestId)
        pendingChatNavigation = restored
    }

    fun consumePendingChatNavigation(): PendingChatNavigation? =
        pendingChatNavigation.also {
            if (it != null) lastConsumedChatNavigationId = maxOf(lastConsumedChatNavigationId, it.requestId)
            pendingChatNavigation = null
        }

    fun consumePendingSessionId(): String? = consumePendingChatNavigation()?.sessionId

    /** Clear the stack and navigate to the given screen atomically. */
    fun resetTo(screen: NavKey) {
        if (shellNavigation != null && ShellTab.entries.any { it.root == screen }) {
            navigateTo(screen)
            return
        }
        val stack = backStack ?: return
        stack.clear()
        stack.add(screen)
    }

    /**
     * Navigate back one step, or fall back to [fallback] when the stack has only one item.
     * Never leaves the stack empty.
     */
    fun goBack(fallback: NavKey = ChatScreen) {
        val stack = backStack ?: return
        if (shellNavigation != null && stack.size == 1) {
            selectTab(ShellTab.TODAY)
            return
        }
        if (stack.size > 1) {
            stack.removeLastOrNull()
        } else if (stack.size == 1) {
            if (stack.lastOrNull() != fallback && stack.lastOrNull() != LandingScreen) {
                stack.clear()
                stack.add(fallback)
            }
        }
    }
}
