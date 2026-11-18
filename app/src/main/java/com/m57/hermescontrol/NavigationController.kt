package com.m57.hermescontrol

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.RequestReferenceCodec
import com.m57.hermescontrol.navigation.AttentionDetailKey
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
    val profileName: String? = null,
)

@Serializable
data class PendingAttentionNavigation(
    val ref: RequestRef,
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
    var pendingCareerNotice: String? by mutableStateOf(null)
    var pendingOpenClawNotice: String? by mutableStateOf(null)
    var pendingWorkerDestination: String? by mutableStateOf(null)
    var pendingChatPrefill: String? by mutableStateOf(null)
        private set
    var pendingSpecialistSetupBot: String? by mutableStateOf(null)
        private set
    var pendingMainConversationRequestId: Long by mutableStateOf(0L)
        private set
    private var lastConsumedMainConversationRequestId = 0L
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

    /** Apps reselect reveals its root; returning from another tab restores the saved Apps stack. */
    fun selectTabFromBar(tab: ShellTab) {
        val shell = shellNavigation ?: return
        when (tab) {
            ShellTab.APPS -> {
                if (shell.selectedTab == ShellTab.APPS) navigateTo(tab.root) else selectTab(tab)
            }

            ShellTab.CHAT, ShellTab.AGENT -> {
                navigateTo(tab.root)
            }

            else -> {
                selectTab(tab)
            }
        }
    }

    var pendingChatNavigation: PendingChatNavigation? by mutableStateOf(null)
        private set
    val pendingSessionId: String? get() = pendingChatNavigation?.sessionId?.takeIf { it.isNotBlank() }

    fun pendingSessionIdForProfile(activeProfile: String?): String? {
        val current = pendingChatNavigation ?: return null
        val target = current.profileName?.takeIf { it.isNotBlank() } ?: return current.sessionId
        val active = activeProfile?.takeIf { it.isNotBlank() } ?: "default"
        return if (target == active) current.sessionId else null
    }

    private var nextChatNavigationRequestId = 0L
    private var lastConsumedChatNavigationId = 0L
    var pendingAttentionNavigation: PendingAttentionNavigation? by mutableStateOf(null)
        private set
    private var nextAttentionNavigationId = 0L
    private var lastConsumedAttentionNavigationId = 0L

    fun openAttentionFromNotification(ref: RequestRef) {
        if (!RequestReferenceCodec.isValid(ref)) return
        pendingChatNavigation = null
        pendingAttentionNavigation = PendingAttentionNavigation(ref, ++nextAttentionNavigationId)
    }

    fun openWorkerDestinationFromNotification(destination: String) {
        if (destination !in
            setOf(
                com.m57.hermescontrol.worker.WorkerNotificationRoute.DEST_TODAY,
                com.m57.hermescontrol.worker.WorkerNotificationRoute.DEST_WORK,
            )
        ) {
            return
        }
        pendingChatNavigation = null
        pendingAttentionNavigation = null
        pendingWorkerDestination = destination
    }

    fun revealPendingAttention() {
        val pending = pendingAttentionNavigation ?: return
        val stack = backStack ?: return
        if (stack.any { roleOf(it) == NavigationRole.AUTH }) return
        navigateTo(AttentionDetailKey(pending.ref))
        lastConsumedAttentionNavigationId = pending.requestId
        pendingAttentionNavigation = null
    }

    fun savePendingAttentionNavigation(): String? = pendingAttentionNavigation?.let { Json.encodeToString(it) }

    fun restorePendingAttentionNavigation(encoded: String?) {
        if (encoded == null || encoded.length > RequestReferenceCodec.MAX_ENCODED_LENGTH + 128) return
        val restored = runCatching { Json.decodeFromString<PendingAttentionNavigation>(encoded) }.getOrNull() ?: return
        if (!RequestReferenceCodec.isValid(restored.ref) || restored.requestId <= 0 ||
            restored.requestId == Long.MAX_VALUE ||
            restored.requestId <= lastConsumedAttentionNavigationId ||
            restored.requestId <= (pendingAttentionNavigation?.requestId ?: 0)
        ) {
            return
        }
        nextAttentionNavigationId = maxOf(nextAttentionNavigationId, restored.requestId)
        pendingAttentionNavigation = restored
    }

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

    fun openChatSession(
        sessionId: String,
        profileName: String? = null,
    ) {
        queueChatNavigation(sessionId, scrollToBottom = false, profileName = profileName)
    }

    fun openChatResult(
        sessionId: String,
        profileName: String? = null,
    ) {
        queueChatNavigation(sessionId, scrollToBottom = true, profileName = profileName)
    }

    fun requestSpecialistSetup(profileName: String) {
        if (profileName.isNotBlank() && profileName != "default") {
            pendingSpecialistSetupBot = profileName
        }
    }

    fun consumePendingSpecialistSetup(profileName: String?): Boolean {
        val normalized = profileName?.takeIf { it.isNotBlank() } ?: return false
        return if (pendingSpecialistSetupBot == normalized) {
            pendingSpecialistSetupBot = null
            true
        } else {
            false
        }
    }

    fun requestMainConversation() {
        pendingSpecialistSetupBot = null
        pendingMainConversationRequestId++
    }

    fun consumePendingMainConversationRequest(): Boolean {
        val current = pendingMainConversationRequestId
        return if (current > lastConsumedMainConversationRequestId) {
            lastConsumedMainConversationRequestId = current
            true
        } else {
            false
        }
    }

    fun clearPendingChatNavigationForMismatch(targetProfile: String) {
        if (pendingSpecialistSetupBot != null && pendingSpecialistSetupBot != targetProfile) {
            pendingSpecialistSetupBot = null
        }
        val current = pendingChatNavigation ?: return
        val expected = current.profileName?.takeIf { it.isNotBlank() }
        if (expected == null || expected != targetProfile) {
            pendingChatNavigation = null
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun openChatSessionFromNotification(sessionId: String) {
        // M5 Step 2: Land in the main conversation at the latest position instead of switching
        // to a potentially stale session ID with no history UI to return from.
        pendingAttentionNavigation = null
        pendingChatNavigation =
            PendingChatNavigation(
                sessionId = "",
                scrollToBottom = true,
                requestId = ++nextChatNavigationRequestId,
            )
        if (backStack?.any { roleOf(it) == NavigationRole.AUTH } != true) navigateTo(ChatScreen)
    }

    fun openChatWithPrefill(prompt: String) {
        val value = prompt.trim().take(4_000)
        if (value.isBlank()) return
        pendingChatPrefill = value
        navigateTo(ChatScreen)
    }

    fun consumePendingChatPrefill(): String? = pendingChatPrefill.also { pendingChatPrefill = null }

    private fun queueChatNavigation(
        sessionId: String,
        scrollToBottom: Boolean,
        profileName: String? = null,
    ) {
        if (sessionId.isBlank()) return
        pendingAttentionNavigation = null
        pendingChatNavigation =
            PendingChatNavigation(
                sessionId = sessionId,
                scrollToBottom = scrollToBottom,
                requestId = ++nextChatNavigationRequestId,
                profileName = profileName?.takeIf { it.isNotBlank() },
            )
        // A notification received before login stays pending until an authenticated stack attaches.
        if (backStack?.any { roleOf(it) == NavigationRole.AUTH } != true) navigateTo(ChatScreen)
    }

    fun savePendingChatNavigation(): String? = pendingChatNavigation?.let { Json.encodeToString(it) }

    fun restorePendingChatNavigation(encoded: String?) {
        val restored =
            encoded?.let { runCatching { Json.decodeFromString<PendingChatNavigation>(it) }.getOrNull() }
                ?: return
        if ((restored.sessionId.isBlank() && !restored.scrollToBottom) ||
            restored.requestId <= 0 ||
            restored.requestId == Long.MAX_VALUE
        ) {
            return
        }
        if (restored.requestId <= lastConsumedChatNavigationId ||
            restored.requestId <= (pendingChatNavigation?.requestId ?: 0)
        ) {
            return
        }
        nextChatNavigationRequestId = maxOf(nextChatNavigationRequestId, restored.requestId)
        pendingChatNavigation = restored
    }

    fun consumePendingChatNavigation(activeProfile: String? = null): PendingChatNavigation? {
        val current = pendingChatNavigation ?: return null
        lastConsumedChatNavigationId = maxOf(lastConsumedChatNavigationId, current.requestId)
        pendingChatNavigation = null
        val target = current.profileName?.takeIf { it.isNotBlank() }
        val active = activeProfile?.takeIf { it.isNotBlank() } ?: "default"
        return if (target == null || target == active) current else null
    }

    fun consumePendingSessionId(): String? = consumePendingChatNavigation()?.sessionId?.takeIf { it.isNotBlank() }

    /** Clear the stack and navigate to the given screen atomically. */
    fun resetTo(screen: NavKey) {
        if (shellNavigation != null &&
            (screen == com.m57.hermescontrol.navigation.AgentScreen || ShellTab.primary.any { it.root == screen })
        ) {
            navigateTo(screen)
            return
        }
        val stack = backStack ?: return
        stack.clear()
        stack.add(screen)
    }

    /** Ignore late screen callbacks after navigation has already left their destination. */
    fun goBackFrom(expected: NavKey) {
        val stack = backStack ?: return
        if (stack.size > 1 && stack.lastOrNull() == expected) {
            stack.removeLastOrNull()
        }
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
