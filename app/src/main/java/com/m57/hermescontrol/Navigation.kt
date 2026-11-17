package com.m57.hermescontrol

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.m57.hermescontrol.agents.AgentDetailKey
import com.m57.hermescontrol.agents.AgentDetailScreen
import com.m57.hermescontrol.agents.AgentHubScreen
import com.m57.hermescontrol.agents.BotSettingsKey
import com.m57.hermescontrol.career.text
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.navigation.ActivityScreen
import com.m57.hermescontrol.navigation.AdvancedScreen
import com.m57.hermescontrol.navigation.AgentScreen
import com.m57.hermescontrol.navigation.AppsScreen
import com.m57.hermescontrol.navigation.AttentionDetailKey
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.navigation.TodayScreen
import com.m57.hermescontrol.navigation.rememberAppBackStack
import com.m57.hermescontrol.navigation.rememberShellNavigation
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.apps.MiniAppsScreen
import com.m57.hermescontrol.ui.attention.AttentionDetailScreen
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.common.AdvancedScreenContent
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.ui.common.DisableDrawerGestures
import com.m57.hermescontrol.ui.common.DrawerGestureController
import com.m57.hermescontrol.ui.common.LocalDrawerGestureController
import com.m57.hermescontrol.ui.common.LocalManagementScope
import com.m57.hermescontrol.ui.common.LocalShellMenu
import com.m57.hermescontrol.ui.common.ObservedActivityScreen
import com.m57.hermescontrol.ui.common.ShellBottomBar
import com.m57.hermescontrol.ui.common.ShellMenu
import com.m57.hermescontrol.ui.plugins.MemoryProviderDetailScreen
import com.m57.hermescontrol.ui.settings.SettingsAboutPage
import com.m57.hermescontrol.ui.settings.SettingsConnectionPage
import com.m57.hermescontrol.ui.settings.SettingsViewModel
import com.m57.hermescontrol.ui.toolsets.ToolsetDetailScreen
import kotlinx.coroutines.launch
import com.m57.hermescontrol.ui.authlogin.AuthLoginScreen as AuthLoginScreenContent
import com.m57.hermescontrol.ui.config.ConfigScreen as RawConfigScreen
import com.m57.hermescontrol.ui.landing.LandingScreen as LandingScreenContent
import com.m57.hermescontrol.ui.settings.SettingsScreen as UnifiedSettingsScreen

private fun appEntryProvider(
    sessionId: String?,
    openDrawer: () -> Unit,
) = entryProvider {
    entry<TodayScreen> {
        com.m57.hermescontrol.home
            .HomeScreen()
    }
    entry<com.m57.hermescontrol.home.HomeDetailKey> {
        com.m57.hermescontrol.career
            .CareerHomeDetailScreen(it)
    }
    entry<ActivityScreen> { ObservedActivityScreen(today = false) }
    entry<AppsScreen> { MiniAppsScreen() }
    entry<AgentScreen> { AgentHubScreen(sessionId = sessionId, onOpenDrawer = openDrawer) }
    entry<AgentDetailKey> { AgentDetailScreen(it) }
    entry<com.m57.hermescontrol.agents.BotDefaultsKey> {
        com.m57.hermescontrol.agents
            .BotDefaultsScreen(it.profileName)
    }
    entry<com.m57.hermescontrol.openclaw.OpenClawChatKey> {
        com.m57.hermescontrol.openclaw
            .OpenClawChatScreen()
    }
    entry<com.m57.hermescontrol.openclaw.OpenClawSettingsKey> {
        com.m57.hermescontrol.openclaw
            .OpenClawSettingsScreen()
    }
    entry<com.m57.hermescontrol.agents.BotSettingsKey> {
        com.m57.hermescontrol.agents
            .BotDetailScreen(profileName = it.profileName)
    }
    entry<com.m57.hermescontrol.agents.ChatSettingsKey> {
        com.m57.hermescontrol.agents
            .ChatSettingsScreen()
    }
    entry<com.m57.hermescontrol.navigation.MiniAppExamplesScreen> { MiniAppsScreen(showExamples = true) }
    entry<com.m57.hermescontrol.pcpower.PcPowerScreenKey> {
        com.m57.hermescontrol.pcpower
            .PcPowerScreen()
    }
    entry<com.m57.hermescontrol.terminal.TerminalScreenKey> {
        com.m57.hermescontrol.terminal
            .TerminalScreen()
    }
    entry<WorkScreen> {
        com.m57.hermescontrol.worker
            .WorkScreen(onOpenDrawer = openDrawer)
    }
    entry<com.m57.hermescontrol.career.CareerScreenKey> {
        com.m57.hermescontrol.career
            .CareerAppScreen()
    }
    entry<AdvancedScreen> { AdvancedScreenContent() }
    entry<com.m57.hermescontrol.career.CareerCompanyKey> {
        com.m57.hermescontrol.career
            .CareerScreen(it.companyId, initialTab = it.tab.ordinal, initialEntityId = it.entityId)
    }
    entry<com.m57.hermescontrol.career.CareerMyPageBrowserKey> {
        com.m57.hermescontrol.career
            .CareerMyPageBrowserScreen(it)
    }
    entry<com.m57.hermescontrol.miniapp.workbench.CareerMyPageWorkbenchKey> {
        com.m57.hermescontrol.miniapp.workbench
            .CareerMyPageWorkbenchScreen(it)
    }
    entry<com.m57.hermescontrol.miniapp.sdui.CareerMyPageSduiKey> {
        com.m57.hermescontrol.miniapp.sdui
            .CareerMyPageSduiScreen(it)
    }
    entry<com.m57.hermescontrol.career.CareerConversationKey> {
        com.m57.hermescontrol.career.CareerConversationScreen(
            it.threadId,
            initialPrompt = it.prompt,
            initialTarget = it.target,
            handoffId = it.handoffId,
            title = it.title,
            readOnly = it.readOnly,
            legacyEsQuestionId = it.legacyEsQuestionId,
        )
    }
    entry<com.m57.hermescontrol.career.CareerThreadsKey> {
        com.m57.hermescontrol.career
            .CareerThreadsScreen(it)
    }
    entry<com.m57.hermescontrol.career.CareerEntityKey> {
        com.m57.hermescontrol.career
            .CareerEntityScreen(it.entityId)
    }
    entry<com.m57.hermescontrol.career.CareerCompanySettingsKey> {
        com.m57.hermescontrol.career
            .CareerScreen(it.companyId, settingsOnly = true)
    }
    entry<com.m57.hermescontrol.career.CareerSettingsKey> {
        com.m57.hermescontrol.career
            .CareerSettingsScreen()
    }
    entry<com.m57.hermescontrol.career.CareerEsKey> {
        com.m57.hermescontrol.career
            .CareerEsScreen(it.companyId, it.offerId, it.questionId)
    }
    entry<com.m57.hermescontrol.career.CareerMailKey> {
        com.m57.hermescontrol.career
            .CareerScreen(it.companyId, initialTab = com.m57.hermescontrol.career.CareerCompanyTab.MAIL.ordinal)
    }
    entry<com.m57.hermescontrol.career.CareerMailSettingsKey> {
        com.m57.hermescontrol.career
            .CareerMailSettingsScreen()
    }
    entry<com.m57.hermescontrol.career.CareerNoticeDetailKey> {
        com.m57.hermescontrol.career
            .CareerNoticeDetailScreen(it)
    }
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
    // The former appearance/language/chat/behaviour sub-pages now live inline on the settings
    // page; their keys stay routable so saved back stacks and deep links still resolve.
    entry<SettingsAppearance> { UnifiedSettingsScreen(onBack = { NavigationController.goBack() }) }
    entry<SettingsLanguage> { UnifiedSettingsScreen(onBack = { NavigationController.goBack() }) }
    entry<SettingsChat> { UnifiedSettingsScreen(onBack = { NavigationController.goBack() }) }
    entry<SettingsBehavior> { UnifiedSettingsScreen(onBack = { NavigationController.goBack() }) }
    entry<ConfigAdvancedScreen> { RawConfigScreen(onBack = { NavigationController.goBack() }) }
    entry<SettingsGoogleConnection> {
        com.m57.hermescontrol.ui.connections
            .GoogleConnectionScreen()
    }
    entry<SettingsExternalServices> {
        com.m57.hermescontrol.ui.connections
            .ExternalServicesScreen()
    }
    entry<ExternalServiceDetailKey> {
        com.m57.hermescontrol.ui.connections
            .ExternalServiceDetailScreen(it.serviceId)
    }
    entry<McpConnectionSettingsKey> { key ->
        val scope =
            com.m57.hermescontrol.career
                .careerScope()
        com.m57.hermescontrol.ui.mcp.McpServersScreen(
            focusedServerName = key.serverName,
            focusedServiceId = key.serviceId,
            viewModel =
                viewModel(key = "mcp-settings:$scope:${key.serverName}:${key.serviceId}") {
                    com.m57.hermescontrol.ui.mcp
                        .McpServersViewModel()
                },
        )
    }
    entry<SettingsPasswords> {
        com.m57.hermescontrol.vault
            .PasswordsScreen()
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
}

@Composable
fun MainNavigation(sessionId: String? = null) {
    val token by AuthManager.tokenFlow.collectAsState()
    val hasToken = !token.isNullOrBlank()
    val startScreen: NavKey = if (hasToken) ChatScreen else LandingScreen

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
    val clawNotice = NavigationController.pendingOpenClawNotice
    val clawContext = LocalContext.current
    LaunchedEffect(hasToken, clawNotice) {
        if (hasToken && clawNotice != null) {
            val session =
                com.m57.hermescontrol.openclaw.OpenClawNotifications
                    .resolve(clawContext, clawNotice)
            if (session != null) {
                com.m57.hermescontrol.openclaw.OpenClawRuntime
                    .model(
                        clawContext.applicationContext as android.app.Application,
                    ).openNotifiedSession(session)
                NavigationController.navigateTo(com.m57.hermescontrol.openclaw.OpenClawChatKey)
            } else {
                Toast.makeText(clawContext, "通知の接続先を確認してください。", Toast.LENGTH_LONG).show()
            }
            NavigationController.pendingOpenClawNotice = null
        }
    }
    val pendingAttention = NavigationController.pendingAttentionNavigation
    val careerNotice = NavigationController.pendingCareerNotice
    val careerProfile by AuthManager.activeProfileId.collectAsState()
    val careerContext by AuthManager.contextFlow.collectAsState()
    val careerAppContext = LocalContext.current
    LaunchedEffect(hasToken, careerNotice, careerProfile, careerContext) {
        if (hasToken && careerNotice != null) {
            val expectedScope =
                com.m57.hermescontrol.career
                    .careerScope()
            val route =
                com.m57.hermescontrol.career
                    .CareerLocalStore(
                        careerAppContext,
                        expectedScope,
                    ).resolveNotificationDestination(careerNotice)
            val company = route?.text("company_id")
            if (expectedScope ==
                com.m57.hermescontrol.career
                    .careerScope()
            ) {
                if (company != null) {
                    NavigationController.selectTab(ShellTab.APPS)
                    NavigationController.navigateTo(com.m57.hermescontrol.career.CareerScreenKey)
                    val destination =
                        com.m57.hermescontrol.career
                            .careerNoticeDestination(route, expectedScope)
                    NavigationController.navigateTo(destination)
                } else {
                    Toast.makeText(careerAppContext, "通知の接続先・プロフィールを確認してください。", Toast.LENGTH_LONG).show()
                }
                NavigationController.pendingCareerNotice = null
            }
        }
    }
    LaunchedEffect(hasToken, pendingAttention?.requestId) {
        if (hasToken && pendingAttention != null) NavigationController.revealPendingAttention()
    }
    val workerDestination = NavigationController.pendingWorkerDestination
    LaunchedEffect(hasToken, workerDestination) {
        if (hasToken && workerDestination != null) {
            val target =
                when (workerDestination) {
                    com.m57.hermescontrol.worker.WorkerNotificationRoute.DEST_WORK -> WorkScreen
                    else -> TodayScreen
                }
            NavigationController.navigateTo(target)
            NavigationController.pendingWorkerDestination = null
        }
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
    var showCreateSpecialistSheet by remember { mutableStateOf(false) }

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

    val botsViewModel: BotsViewModel? = if (hasToken) viewModel { BotsViewModel() } else null
    val botsStateState = botsViewModel?.uiState?.collectAsStateWithLifecycle()
    val botsState = botsStateState?.value
    LaunchedEffect(hasToken, drawerState.targetValue) {
        if (hasToken && drawerState.targetValue == DrawerValue.Open) {
            botsViewModel?.loadBots(isRefresh = true)
        }
    }

    val shellMenu: (@Composable () -> Unit)? = if (hasToken && shell?.isAtRoot == true) ({ ShellMenu() }) else null
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val managementScope = hasToken && shell?.isInManagement == true
    val appUpdateViewModel =
        com.m57.hermescontrol.ui.common
            .sharedAppUpdateViewModel()
    CompositionLocalProvider(
        com.m57.hermescontrol.ui.common.LocalAppUpdateViewModel provides appUpdateViewModel,
        LocalDrawerGestureController provides gestureController,
        LocalShellMenu provides shellMenu,
        LocalManagementScope provides managementScope,
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gestureController.enabled,
            drawerContent = {
                ModalDrawerSheet {
                    Column(
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
                        val statusText =
                            when (connectionStatus) {
                                ConnectionStatus.CONNECTED -> stringResource(R.string.agent_selector_status_ready)

                                ConnectionStatus.CONNECTING,
                                ConnectionStatus.RECONNECTING,
                                -> stringResource(R.string.drawer_status_connecting)

                                ConnectionStatus.DISCONNECTED,
                                ConnectionStatus.NO_NETWORK,
                                ConnectionStatus.AUTH_EXPIRED,
                                -> stringResource(R.string.agent_selector_status_unavailable)
                            }
                        val drawerItemColors =
                            NavigationDrawerItemDefaults.colors(
                                // Mono Expressive: selection is grey, not blue.
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        val activeProfile = careerProfile ?: botsState?.activeProfileName
                        val switchFailedFallback = stringResource(R.string.drawer_switch_profile_failed)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 28.dp, top = 16.dp, end = 28.dp, bottom = 12.dp),
                        ) {
                            Text(
                                text = "Hermes",
                                style =
                                    MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier =
                                    Modifier
                                        .size(8.dp)
                                        .background(color = statusColor, shape = CircleShape),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 28.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        NavigationDrawerItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                            label = {
                                Text(
                                    text = stringResource(R.string.drawer_hermes_main),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            selected =
                                currentScreen == ChatScreen &&
                                    (activeProfile.isNullOrBlank() || activeProfile == "default"),
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    val opened =
                                        com.m57.hermescontrol.agents.BotConversations
                                            .openMain()
                                    if (opened) {
                                        botsViewModel?.loadBots(isRefresh = true)
                                    } else {
                                        Toast
                                            .makeText(
                                                context,
                                                switchFailedFallback,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                }
                            },
                            colors = drawerItemColors,
                            modifier =
                                Modifier
                                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                                    .testTag("drawer_hermes_main"),
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 28.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Text(
                            text = stringResource(R.string.drawer_specialist_bots),
                            modifier =
                                Modifier.padding(
                                    start = 28.dp,
                                    top = 16.dp,
                                    bottom = 4.dp,
                                    end = 28.dp,
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        val specialistBots =
                            specialistDrawerBots(
                                profiles = botsState?.profiles.orEmpty(),
                                hiddenProfiles = botsState?.hiddenProfiles.orEmpty(),
                                activeProfileName = activeProfile,
                            )
                        specialistBots.forEach { bot ->
                            NavigationDrawerItem(
                                icon = {
                                    BotAvatar(
                                        name = bot.name,
                                        avatar = bot.botMeta()?.avatar,
                                        size = 24.dp,
                                        showPresence = false,
                                    )
                                },
                                label = {
                                    Text(
                                        text = bot.effectiveTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                badge =
                                    if (bot.worker_session != null) {
                                        {
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.drawer_bot_working),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                selected = currentScreen == BotSettingsKey(bot.name),
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    NavigationController.navigateTo(BotSettingsKey(bot.name))
                                },
                                colors = drawerItemColors,
                                modifier =
                                    Modifier
                                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                                        .testTag("drawer_bot_${bot.name}"),
                            )
                        }

                        NavigationDrawerItem(
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            label = {
                                Text(
                                    text = stringResource(R.string.drawer_create_bot),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            selected = showCreateSpecialistSheet,
                            onClick = {
                                scope.launch { drawerState.close() }
                                showCreateSpecialistSheet = true
                            },
                            colors = drawerItemColors,
                            modifier =
                                Modifier
                                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                                    .testTag("drawer_create_bot"),
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 28.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        NavigationDrawerItem(
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = {
                                Text(
                                    text = stringResource(R.string.drawer_settings_management),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            selected = currentScreen == AdvancedScreen,
                            onClick = {
                                scope.launch { drawerState.close() }
                                NavigationController.navigateTo(AdvancedScreen)
                            },
                            colors = drawerItemColors,
                            modifier =
                                Modifier
                                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                                    .testTag("drawer_settings_management"),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            },
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets.navigationBars,
                // Chat owns IME padding. Do not reserve another tab-bar row above the keyboard.
                bottomBar = {
                    if (!imeVisible && !managementScope &&
                        currentScreen !is com.m57.hermescontrol.career.CareerMyPageBrowserKey
                    ) {
                        shell?.let { ShellBottomBar(it.selectedTab) }
                    }
                },
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
        if (hasToken && showCreateSpecialistSheet) {
            com.m57.hermescontrol.ui.bots.CreateSpecialistBotSheet(
                onDismiss = { showCreateSpecialistSheet = false },
                onCreated = { created ->
                    showCreateSpecialistSheet = false
                    scope.launch {
                        botsViewModel?.loadBots(isRefresh = true)
                        val opened =
                            com.m57.hermescontrol.agents.BotConversations.openSpecialist(
                                profileName = created.name,
                                openSetupAfterLaunch = !created.setup_completed,
                            )
                        if (!opened) {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.drawer_switch_profile_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                },
            )
        }
        if (hasToken) {
            com.m57.hermescontrol.ui.common
                .AppUpdateHost(appUpdateViewModel)
        }
    }
}

internal fun specialistDrawerBots(
    profiles: List<ProfileInfo>,
    hiddenProfiles: Set<String>,
    activeProfileName: String?,
): List<ProfileInfo> =
    profiles
        .filter { profile ->
            profile.is_default != true &&
                profile.name != "default" &&
                !profile.isHidden &&
                profile.name !in hiddenProfiles
        }.sortedWith(
            compareByDescending<ProfileInfo> { it.worker_session != null }
                .thenByDescending { it.name == activeProfileName }
                .thenByDescending {
                    it.canonical_session?.last_active
                        ?: it.last_session?.last_active
                        ?: 0.0
                }.thenBy { it.name },
        )
