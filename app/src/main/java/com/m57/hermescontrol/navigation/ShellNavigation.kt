package com.m57.hermescontrol.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.LandingScreen
import com.m57.hermescontrol.ScreenRegistry
import com.m57.hermescontrol.agents.AgentDetailKey
import com.m57.hermescontrol.career.CareerScreenKey
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.pcpower.PcPowerScreenKey
import kotlinx.serialization.Serializable

@Serializable data object TodayScreen : NavKey

@Serializable data object ActivityScreen : NavKey

@Serializable data object AppsScreen : NavKey

@Serializable data object AgentScreen : NavKey

@Serializable data object AdvancedScreen : NavKey

@Serializable data object MiniAppExamplesScreen : NavKey

enum class ShellTab(
    val root: NavKey,
) {
    TODAY(TodayScreen),
    CHAT(ChatScreen),
    APPS(AppsScreen),
    AGENT(ChatScreen),

    // Legacy enum values remain deserializable; CHAT is an alias of AGENT.
    ACTIVITY(ActivityScreen),
    ;

    companion object {
        val primary = listOf(TODAY, AGENT, APPS)
    }
}

/** Each tab owns a saved Navigation 3 stack. Switching tabs never discards a drill-down. */
internal class ShellNavigation(
    selected: MutableState<ShellTab>,
    private val stacks: Map<ShellTab, NavBackStack<NavKey>>,
) {
    init {
        selected.value = canonicalTab(selected.value)
    }

    var selectedTab by selected
        private set
    val backStack: NavBackStack<NavKey> get() = stacks.getValue(selectedTab)
    val isAtRoot: Boolean get() = backStack.lastOrNull() == selectedTab.root
    val isInManagement: Boolean get() = AppManagement.contains(backStack.toList())

    fun select(tab: ShellTab) {
        selectedTab = canonicalTab(tab)
    }

    fun navigate(destination: NavKey) {
        val key = canonicalAgentDestination(destination)
        val tab = ShellTab.primary.firstOrNull { it.root == key }
        if (tab != null) {
            select(tab)
            // Explicit root navigation (including notification routing) reveals the tab root.
            backStack.clear()
            backStack.add(tab.root)
            return
        }
        val stack = backStack
        if (stack.lastOrNull() == key) return
        val existing = stack.indexOf(key)
        if (existing >= 0) {
            while (stack.size > existing + 1) stack.removeLastOrNull()
        } else {
            stack.add(key)
        }
    }
}

internal fun canonicalTab(tab: ShellTab): ShellTab =
    when (tab) {
        ShellTab.CHAT -> ShellTab.AGENT
        ShellTab.ACTIVITY -> ShellTab.TODAY
        else -> tab
    }

/** Existing chat URLs, notification targets and candidate hub routes resolve to one conversation. */
internal fun canonicalAgentDestination(key: NavKey): NavKey =
    if (key == AgentScreen || key == AgentDetailKey("HERMES")) ChatScreen else key

internal fun migrateAgentStack(stack: List<NavKey>): List<NavKey> =
    buildList {
        add(ChatScreen)
        stack.drop(1).forEach { key ->
            val migrated = canonicalAgentDestination(key)
            if (migrated != ChatScreen && migrated !in this) add(migrated)
        }
    }

/** Only stable registered screens are persisted; detail arguments and auth screens never enter preferences. */
internal object ShellResume {
    private val destinations: Map<String, NavKey> get() =
        (
            ShellTab.primary.map { it.root } + listOf(CareerScreenKey, PcPowerScreenKey, AdvancedScreen) +
                ScreenRegistry.ALL_SCREENS.map { it.key }
        ).associateBy { it.toString() }

    fun decode(value: String?): Pair<ShellTab, NavKey> {
        val parts = value?.split('|', limit = 2)
        if (parts?.firstOrNull() in setOf("TODAY", "ACTIVITY")) return ShellTab.TODAY to TodayScreen
        val tab =
            ShellTab.entries.firstOrNull { it.name == parts?.firstOrNull() } ?: return ShellTab.TODAY to TodayScreen
        val screen = parts?.getOrNull(1)?.let { destinations[it] } ?: tab.root
        return canonicalTab(tab) to canonicalAgentDestination(screen)
    }

    fun encode(
        tab: ShellTab,
        stack: List<NavKey>,
    ): String {
        val stable = stack.lastOrNull { it in destinations.values } ?: tab.root
        return "${canonicalTab(tab).name}|${canonicalAgentDestination(stable)}"
    }
}

/** Removing the authenticated composition on logout drops all private tab state. */
@Composable
internal fun rememberShellNavigation(authenticated: Boolean): ShellNavigation? {
    val context by AuthManager.contextFlow.collectAsState()
    val profile by AuthManager.activeProfileId.collectAsState()
    val preferenceScope = remember(context, profile) { AuthManager.uiPreferenceScope() }
    return key(authenticated, preferenceScope) {
        if (!authenticated) {
            null
        } else {
            val resume =
                remember(
                    preferenceScope,
                ) { ShellResume.decode(AuthManager.readUiPreference(preferenceScope, "last_screen")) }
            val selected = rememberSaveable { mutableStateOf(resume.first) }
            val legacySelected = selected.value
            selected.value = canonicalTab(selected.value)
            val today = rememberNavBackStack(TodayScreen)
            val chat = rememberNavBackStack(ChatScreen)
            val apps = rememberNavBackStack(AppsScreen)
            // Keep these saved-state call positions compatible with the four-tab version.
            val legacyAgent = rememberNavBackStack(AgentScreen)
            var migrated by rememberSaveable { mutableStateOf(false) }
            if (!migrated) {
                val source = if (legacySelected == ShellTab.AGENT && legacyAgent.size > 1) legacyAgent else chat
                val merged = migrateAgentStack(source.toList())
                chat.clear()
                chat.addAll(merged)
                migrated = true
            }
            val stacks =
                mapOf(
                    ShellTab.TODAY to today,
                    ShellTab.APPS to apps,
                    ShellTab.AGENT to chat,
                )
            stacks.forEach { (tab, stack) ->
                if (stack.firstOrNull() != tab.root || stack.any { it == LandingScreen }) {
                    stack.clear()
                    stack.add(tab.root)
                }
            }
            var restored by rememberSaveable { mutableStateOf(false) }
            if (!restored) {
                val stack = stacks.getValue(resume.first)
                if (stack.size == 1 && resume.second != resume.first.root) stack.add(resume.second)
                restored = true
            }
            val navigation = remember(selected, today, chat, apps) { ShellNavigation(selected, stacks) }
            val encoded = ShellResume.encode(navigation.selectedTab, navigation.backStack.toList())
            LaunchedEffect(encoded, preferenceScope) {
                AuthManager.writeUiPreference(preferenceScope, "last_screen", encoded)
            }
            navigation
        }
    }
}
