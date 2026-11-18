package com.m57.hermescontrol.ui.connections

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.ExternalServiceDetailKey
import com.m57.hermescontrol.McpConnectionSettingsKey
import com.m57.hermescontrol.McpServersScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsGoogleConnection
import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.mcp.McpServersViewModel

@Composable
private fun servicesModel(): ExternalServicesViewModel {
    val scope = careerScope()
    return viewModel(key = "external-services:$scope") {
        ExternalServicesViewModel(DashboardExternalServicesRepository(scope))
    }
}

@Composable
fun ExternalServicesScreen() {
    val model = servicesModel()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refresh() }
    HermesScaffold(
        title = { Text(stringResource(R.string.management_external_services)) },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        isRefreshing = state.loading,
        onRefresh = model::refresh,
    ) {
        ExternalServicesContent(
            state = state,
            onOpenService = { NavigationController.navigateTo(ExternalServiceDetailKey(it)) },
            onOpenServer = { NavigationController.navigateTo(McpConnectionSettingsKey(serverName = it)) },
            onAdvanced = { NavigationController.navigateTo(McpServersScreen) },
            onRetry = model::refresh,
        )
    }
}

@Composable
fun ExternalServiceDetailScreen(serviceId: String) {
    if (serviceId == "gmail") {
        // One Gmail management screen: accounts, permission and features live there.
        GoogleConnectionScreen()
        return
    }
    ExternalServiceManagementScreen(serviceId)
}

/** Only https provider pages are opened from the connect sheet. */
private fun openHttps(
    context: android.content.Context,
    url: String,
) {
    if (runCatching { java.net.URI(url).scheme }.getOrNull() != "https") return
    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
}

@Composable
private fun ExternalServiceManagementScreen(serviceId: String) {
    val model = servicesModel()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model, serviceId) { model.refresh() }
    val service = externalService(serviceId)
    val context = LocalContext.current
    val scope = careerScope()
    val slack =
        if (serviceId == "slack") {
            viewModel<SlackConnectionViewModel>(key = "slack-login:$scope") {
                SlackConnectionViewModel(DashboardSlackConnectionRepository(scope))
            }
        } else {
            null
        }
    val slackState = slack?.state?.collectAsStateWithLifecycle()?.value
    val workspace =
        if (serviceId in GOOGLE_WORKSPACE_SERVICES) {
            viewModel<GoogleWorkspaceViewModel>(key = "google-workspace:$scope") {
                GoogleWorkspaceViewModel(DashboardGoogleWorkspaceRepository(scope))
            }
        } else {
            null
        }
    val workspaceState = workspace?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(workspace) { workspace?.refresh() }
    val workspaceConnect = workspace?.let { rememberGoogleWorkspaceConnect(it) }
    LaunchedEffect(slack) { slack?.refresh() }
    DisposableEffect(slack) { onDispose { slack?.cancel() } }

    // Connect sheet: catalog install + provider sign-in run here, on this page.
    val mcp: McpServersViewModel = viewModel(key = "service-connect:$scope:$serviceId") { McpServersViewModel() }
    val mcpState by mcp.uiState.collectAsStateWithLifecycle()
    var sheetOpen by rememberSaveable(serviceId) { mutableStateOf(false) }
    var pendingSignIn by remember(serviceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(sheetOpen) {
        if (sheetOpen && serviceId != "slack") {
            mcp.loadServers()
            mcp.loadCatalog()
        }
    }
    val servers = mcpState.servers.ifEmpty { state.servers }
    val plan =
        when {
            service == null -> null
            serviceId == "slack" -> ServiceConnectPlan.SlackLogin
            workspaceState?.canConnect() == true -> ServiceConnectPlan.GoogleWorkspace
            workspace != null && workspaceState?.loaded != true -> null
            mcpState.catalogLoading || (mcpState.isLoading && mcpState.servers.isEmpty()) -> null
            else -> serviceConnectPlan(service, servers, mcpState.catalogEntries, mcpState.catalogError != null)
        }
    LaunchedEffect(plan) {
        val install = plan as? ServiceConnectPlan.Install ?: return@LaunchedEffect
        if (install.entry.env
                .orEmpty()
                .isNotEmpty()
        ) {
            mcp.beginCatalogSetup(install.entry)
        }
    }
    // After installing an OAuth entry, go straight on to its sign-in.
    LaunchedEffect(mcpState.lastAddedServerName, mcpState.servers) {
        if (pendingSignIn == null) return@LaunchedEffect
        val name = mcpState.lastAddedServerName ?: return@LaunchedEffect
        val added = mcpState.servers.find { it.name == name } ?: return@LaunchedEffect
        pendingSignIn = null
        if (added.usesOAuth()) mcp.startMcpOAuthFlow(added) { openHttps(context, it) }
    }
    LaunchedEffect(mcpState.toastMessage) {
        if (mcpState.toastMessage == McpServersViewModel.OAUTH_CONNECTED_MESSAGE) model.refresh()
    }
    val sheetStatus =
        when {
            plan == ServiceConnectPlan.GoogleWorkspace && workspaceState != null -> {
                when {
                    workspaceState.busy -> {
                        ConnectSheetStatus("Googleでアカウントを選んで許可してください。", busy = true)
                    }

                    workspaceState.error != null -> {
                        ConnectSheetStatus(workspaceState.error, error = true)
                    }

                    workspaceState.connectedEmail != null -> {
                        ConnectSheetStatus(
                            listOf(workspaceState.connectedEmail, "接続しました。Hermesから使えます。")
                                .filter { it.isNotBlank() }
                                .joinToString(" を"),
                            done = true,
                        )
                    }

                    else -> {
                        ConnectSheetStatus()
                    }
                }
            }

            mcpState.installingCatalogEntry != null -> {
                ConnectSheetStatus("接続先を準備中…", busy = true)
            }

            mcpState.activeOAuthFlow != null -> {
                ConnectSheetStatus("ブラウザでログインして許可してください。", busy = true, cancellable = true)
            }

            mcpState.toastMessage == McpServersViewModel.OAUTH_CONNECTED_MESSAGE -> {
                ConnectSheetStatus(mcpState.toastMessage, done = true)
            }

            mcpState.toastMessage != null -> {
                val message = mcpState.toastMessage.orEmpty()
                ConnectSheetStatus(message, error = !message.endsWith("…") && !message.startsWith("接続先を準備しました"))
            }

            else -> {
                ConnectSheetStatus()
            }
        }

    HermesScaffold(
        title = { if (service == null) Text("外部サービス") },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        isRefreshing = state.loading,
        onRefresh = {
            model.refresh()
            slack?.refresh()
            workspace?.refresh()
        },
    ) {
        if (service == null) {
            EmptyState(title = "サービスが見つかりません")
        } else {
            ExternalServiceDetailContent(
                service = service,
                state = state,
                onGmail = { NavigationController.navigateTo(SettingsGoogleConnection) },
                onOpenServer = { NavigationController.navigateTo(McpConnectionSettingsKey(serverName = it)) },
                onSetup = { NavigationController.navigateTo(McpConnectionSettingsKey(serviceId = service.id)) },
                slackState = slackState,
                onSlackConnect = { slack?.connect { url -> openHttps(context, url) } },
                onSlackCancel = { slack?.cancel() },
                onSlackRefresh = { slack?.refresh() },
                onRefresh = {
                    model.refresh()
                    workspace?.refresh()
                },
                googleWorkspace = workspaceState,
                onWorkspaceReconnect = { workspaceConnect?.invoke() },
                onWorkspaceDisconnect = { workspace?.disconnect(it) },
                onConnect = {
                    mcp.clearToast()
                    workspace?.clearResult()
                    sheetOpen = true
                },
            )
        }
    }
    if (service != null && sheetOpen) {
        val install = plan as? ServiceConnectPlan.Install
        ServiceConnectSheet(
            service = service,
            plan = plan,
            status = sheetStatus,
            envFields = install?.entry?.env.orEmpty(),
            envValues = mcpState.catalogInstallEnv,
            onEnv = mcp::updateCatalogEnvVar,
            onCancel = mcp::dismissOAuthFlow,
            onDismiss = {
                sheetOpen = false
                if (mcpState.activeOAuthFlow == null) mcp.clearToast()
                workspace?.clearResult()
            },
            onProceed = {
                mcp.clearToast()
                when (plan) {
                    ServiceConnectPlan.SlackLogin -> {
                        sheetOpen = false
                        slack?.connect { url -> openHttps(context, url) }
                    }

                    ServiceConnectPlan.GoogleWorkspace -> {
                        workspaceConnect?.invoke()
                    }

                    is ServiceConnectPlan.SignIn -> {
                        mcp.startMcpOAuthFlow(plan.server) { openHttps(context, it) }
                    }

                    is ServiceConnectPlan.Install -> {
                        if (plan.signInAfter) pendingSignIn = plan.entry.name
                        mcp.installCatalogEntry(plan.entry)
                    }

                    else -> {
                        sheetOpen = false
                    }
                }
            },
        )
    }
}
