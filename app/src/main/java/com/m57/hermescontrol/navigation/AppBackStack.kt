package com.m57.hermescontrol.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.LandingScreen
import com.m57.hermescontrol.NavigationController

/** Authentication scopes saved navigation so logged-out recreation cannot reveal a saved app destination. */
@Composable
internal fun rememberAppBackStack(authenticated: Boolean): NavBackStack<NavKey> {
    val start: NavKey = if (authenticated) ChatScreen else LandingScreen
    val stack = key(authenticated) { rememberNavBackStack(start) }
    if (stack.isEmpty() || (!authenticated && stack.any { NavigationController.roleOf(it) != NavigationRole.AUTH })) {
        stack.clear()
        stack.add(start)
    }
    return stack
}
