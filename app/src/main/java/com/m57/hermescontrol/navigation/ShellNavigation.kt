package com.m57.hermescontrol.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
import kotlinx.serialization.Serializable

@Serializable data object TodayScreen : NavKey

@Serializable data object ActivityScreen : NavKey

@Serializable data object AppsScreen : NavKey

@Serializable data object AdvancedScreen : NavKey

enum class ShellTab(
    val root: NavKey,
) {
    TODAY(TodayScreen),
    CHAT(ChatScreen),
    ACTIVITY(ActivityScreen),
    APPS(AppsScreen),
}

/** Each tab owns a saved Navigation 3 stack. Switching tabs never discards a drill-down. */
internal class ShellNavigation(
    selected: MutableState<ShellTab>,
    private val stacks: Map<ShellTab, NavBackStack<NavKey>>,
) {
    var selectedTab by selected
        private set
    val backStack: NavBackStack<NavKey> get() = stacks.getValue(selectedTab)

    fun select(tab: ShellTab) {
        selectedTab = tab
    }

    fun navigate(key: NavKey) {
        val tab = ShellTab.entries.firstOrNull { it.root == key }
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

/** Removing the authenticated composition on logout drops all private tab state. */
@Composable
internal fun rememberShellNavigation(authenticated: Boolean): ShellNavigation? =
    key(authenticated) {
        if (!authenticated) {
            null
        } else {
            val selected = rememberSaveable { mutableStateOf(ShellTab.TODAY) }
            val today = rememberNavBackStack(TodayScreen)
            val chat = rememberNavBackStack(ChatScreen)
            val activity = rememberNavBackStack(ActivityScreen)
            val apps = rememberNavBackStack(AppsScreen)
            val stacks =
                mapOf(
                    ShellTab.TODAY to today,
                    ShellTab.CHAT to chat,
                    ShellTab.ACTIVITY to activity,
                    ShellTab.APPS to apps,
                )
            stacks.forEach { (tab, stack) ->
                if (stack.firstOrNull() != tab.root || stack.any { it == LandingScreen }) {
                    stack.clear()
                    stack.add(tab.root)
                }
            }
            remember(selected, today, chat, activity, apps) { ShellNavigation(selected, stacks) }
        }
    }
