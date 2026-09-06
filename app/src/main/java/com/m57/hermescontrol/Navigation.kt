package com.m57.hermescontrol

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.navigation.ActivityScreen
import com.m57.hermescontrol.navigation.AdvancedScreen
import com.m57.hermescontrol.navigation.AppsScreen
import com.m57.hermescontrol.navigation.AttentionDetailKey
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.navigation.TodayScreen
import com.m57.hermescontrol.navigation.rememberAppBackStack
import com.m57.hermescontrol.navigation.rememberShellNavigation
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.attention.AttentionDetailScreen
import com.m57.hermescontrol.ui.common.AdvancedScreenContent
import com.m57.hermescontrol.ui.common.DisableDrawerGestures
import com.m57.hermescontrol.ui.common.DrawerGestureController
import com.m57.hermescontrol.ui.common.LocalDrawerGestureController
import com.m57.hermescontrol.ui.common.LocalShellMenu
import com.m57.hermescontrol.ui.common.ObservedActivityScreen
import com.m57.hermescontrol.ui.common.ShellBottomBar
import com.m57.hermescontrol.ui.common.ShellMenu
import com.m57.hermescontrol.ui.common.ShellRootScreen
import com.m57.hermescontrol.ui.plugins.MemoryProviderDetailScreen
import com.m57.hermescontrol.ui.settings.SettingsAboutPage
import com.m57.hermescontrol.ui.settings.SettingsAppearancePage
import com.m57.hermescontrol.ui.settings.SettingsBehaviorPage
import com.m57.hermescontrol.ui.settings.SettingsChatPage
import com.m57.hermescontrol.ui.settings.SettingsConnectionPage
import com.m57.hermescontrol.ui.settings.SettingsLanguagePage
import com.m57.hermescontrol.ui.settings.SettingsViewModel
import com.m57.hermescontrol.ui.toolsets.ToolsetDetailScreen
import kotlinx.coroutines.launch
import com.m57.hermescontrol.ui.authlogin.AuthLoginScreen as AuthLoginScreenContent
import com.m57.hermescontrol.ui.landing.LandingScreen as LandingScreenContent

private fun appEntryProvider(
    sessionId: String?,
    openDrawer: () -> Unit,
) = entryProvider {
    entry<TodayScreen> { ObservedActivityScreen(today = true) }
    entry<ActivityScreen> { ObservedActivityScreen(today = false) }
    entry<AppsScreen> { ShellRootScreen(ShellTab.APPS) }
    entry<AdvancedScreen> { AdvancedScreenContent() }
    entry<AttentionDetailKey> { AttentionDetailScreen(it.ref) }
    entry<LandingScreen> {
        // B7 (Jun 30 2026, kanban t_424): route landing screen buttons through navigateTo to prevent duplicate screens
        LandingScreenContent(
            onAuthLogin = {
                NavigationController.navigateTo(AuthLoginScreen)
            },
        )
        // Landing doesn't use HermesScaffold — opt out of drawer gestures explicitly (issue #619).
        DisableDrawerGestures()
    }

    entry<AuthLoginScreen> {
        AuthLoginScreenContent(
            onConnected = {
                NavigationController.resetTo(ChatScreen)
            },
            onBack = {
                NavigationController.goBack()
            },
        )
        DisableDrawerGestures()
    }

    ScreenRegistry.ALL_SCREENS.forEach { screen ->
        addEntryProvider(clazz = screen.key::class) {
            screen.content(sessionId, openDrawer)
        }
    }

    // ── Settings drill-down sub-pages ───────────────────────────────────
    // Each passes drawerGesturesEnabled = false to HermesScaffold — this is the
    // single source of truth that prevents the drawer-scrim stuck-open bug
    // (issue #619). No global gesture set, no closeDrawer callback, no
    // LaunchedEffect(snapTo(Closed)) — the scaffold reconciles automatically.
    entry<SettingsConnection> {
        SettingsConnectionPage(
            onBack = { NavigationController.goBack() },
            onLogout = { /* handled by caller via goBack fallback */ },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsAppearance> {
        SettingsAppearancePage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsLanguage> {
        SettingsLanguagePage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsChat> {
        SettingsChatPage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsBehavior> {
        SettingsBehaviorPage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsAbout> {
        SettingsAboutPage(
            onBack = { NavigationController.goBack() },
        )
    }

    // ── Toolset detail drill-down (issue #782) ────────────────────────
    entry<ToolsetDetailKey> { key ->
        ToolsetDetailScreen(
            name = key.name,
            label = key.label,
            onBack = { NavigationController.goBack() },
        )
    }

    // ── Memory provider detail drill-down (issue #783) ─────────────────
    entry<MemoryProviderDetailKey> { key ->
        MemoryProviderDetailScreen(
            name = key.name,
            label = key.label,
            onBack = { NavigationController.goBack() },
        )
    }

    // ── Multi-Agent Group Chat Room ─────────────────────────────────────
    entry<GroupChatKey> { key ->
        com.m57.hermescontrol.ui.bots.group.GroupChatScreen(
            groupName = key.groupName,
            onBack = { NavigationController.goBack() },
        )
    }
}

@Composable
fun MainNavigation(sessionId: String? = null) {
    val token by AuthManager.tokenFlow.collectAsState()
    val hasToken = !token.isNullOrBlank()
    val startScreen: NavKey = if (hasToken) TodayScreen else LandingScreen

    val authStack = rememberAppBackStack(hasToken)
    val shell = rememberShellNavigation(hasToken)
    val backStack = shell?.backStack ?: authStack
    if (shell != null) NavigationController.attachShell(shell) else NavigationController.attachAuth(authStack)
    DisposableEffect(shell, authStack) {
        onDispose {
            if (shell != null) {
                NavigationController.detachShell(shell)
            } else if (NavigationController.backStack === authStack) {
                NavigationController.backStack = null
            }
        }
    }
    val pendingNavigation = NavigationController.pendingChatNavigation
    LaunchedEffect(hasToken, pendingNavigation?.requestId) {
        if (hasToken && pendingNavigation != null) NavigationController.navigateTo(ChatScreen)
    }
    val pendingAttention = NavigationController.pendingAttentionNavigation
    LaunchedEffect(hasToken, pendingAttention?.requestId) {
        if (hasToken && pendingAttention != null) NavigationController.revealPendingAttention()
    }

    val currentScreen = backStack.lastOrNull() ?: startScreen
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Single source of truth for drawer gestures (issue #619).
    // HermesScaffold / DisableDrawerGestures reconcile each screen's preference
    // into this controller via SideEffect; ModalNavigationDrawer reads .enabled
    // below. No DRAWER_GESTURE_SCREENS set, no LaunchedEffect(snapTo(Closed)),
    // no closeDrawer callback — the controller closes the drawer itself when a
    // screen opts out.
    val gestureController =
        remember(drawerState, scope) {
            DrawerGestureController(drawerState, scope)
        }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    val context = LocalContext.current
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    val backToastText = stringResource(R.string.press_back_again_to_exit)

    // Double-back to exit on Today. Other tab roots return to Today.
    BackHandler(enabled = currentScreen == TodayScreen && backStack.size == 1) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < 2000L) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressTime = now
            Toast.makeText(context, backToastText, Toast.LENGTH_SHORT).show()
        }
    }

    // Tab roots return to Today; unauthenticated navigation retains the controller fallback.
    BackHandler(
        enabled = currentScreen != TodayScreen && currentScreen != LandingScreen && backStack.size == 1,
    ) {
        NavigationController.goBack()
    }

    val shellMenu: (@Composable () -> Unit)? = if (hasToken) ({ ShellMenu() }) else null
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    CompositionLocalProvider(
        LocalDrawerGestureController provides gestureController,
        LocalShellMenu provides shellMenu,
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gestureController.enabled,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    val connectionStatus by HermesWsClient.connectionStatus.collectAsState()
                    val statusColor =
                        when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> LocalHermesStatusColors.current.success

                            ConnectionStatus.CONNECTING,
                            ConnectionStatus.RECONNECTING,
                            -> LocalHermesStatusColors.current.warning

                            ConnectionStatus.DISCONNECTED,
                            ConnectionStatus.NO_NETWORK,
                            ConnectionStatus.AUTH_EXPIRED,
                            -> LocalHermesStatusColors.current.error
                        }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.nav_drawer_title),
                            style =
                                MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier =
                                Modifier
                                    .size(10.dp)
                                    .background(color = statusColor, shape = CircleShape),
                        )
                    }
                    Text(
                        text = stringResource(R.string.nav_drawer_subtitle),
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, end = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    for (section in DrawerSection.entries) {
                        Text(
                            text = stringResource(section.titleRes).uppercase(),
                            modifier =
                                Modifier.padding(
                                    start = 16.dp,
                                    top = 8.dp,
                                    bottom = 4.dp,
                                    end = 16.dp,
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ScreenRegistry.ALL_SCREENS
                            .filter { it.drawerSection == section }
                            .forEach { entry ->
                                NavigationDrawerItem(
                                    icon = { Icon(entry.icon, contentDescription = null) },
                                    label = { Text(stringResource(entry.labelRes)) },
                                    selected = currentScreen == entry.key,
                                    onClick = {
                                        scope.launch { drawerState.close() }
                                        NavigationController.navigateTo(entry.key)
                                    },
                                    colors =
                                        NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                )
                            }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            },
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets.navigationBars,
                // Chat owns IME padding. Do not reserve another tab-bar row above the keyboard.
                bottomBar = { if (!imeVisible) shell?.let { ShellBottomBar(it.selectedTab) } },
            ) { paddingValues ->
                key(hasToken) {
                    val stateHolder = rememberSaveableStateHolder()
                    stateHolder.SaveableStateProvider(shell?.selectedTab?.name ?: "auth") {
                        NavDisplay(
                            backStack = backStack,
                            onBack = { NavigationController.goBack() },
                            entryProvider =
                                appEntryProvider(sessionId, openDrawer),
                            modifier =
                                Modifier
                                    .padding(paddingValues)
                                    .consumeWindowInsets(paddingValues)
                                    .fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
