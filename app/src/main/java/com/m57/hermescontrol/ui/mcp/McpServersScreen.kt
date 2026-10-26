package com.m57.hermescontrol.ui.mcp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.HermesSecondaryIndicator
import com.m57.hermescontrol.ui.common.InListSectionHeaderPadding
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.common.rememberHasLoaded
import com.m57.hermescontrol.ui.common.toDetailRows
import com.m57.hermescontrol.ui.connections.connectionHost
import com.m57.hermescontrol.ui.connections.externalService
import com.m57.hermescontrol.ui.connections.matches

@Composable
fun McpServersScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: McpServersViewModel = viewModel { McpServersViewModel() },
    focusedServerName: String? = null,
    focusedServiceId: String? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    var showDetail by remember { mutableStateOf<McpServer?>(null) }
    val service = externalService(focusedServiceId.orEmpty())
    val focused = focusedServerName != null || focusedServiceId != null
    val visibleServers =
        state.servers.filter {
            when {
                focusedServerName != null -> it.name == focusedServerName
                focusedServiceId != null -> service?.matches(it) == true || it.name == state.lastAddedServerName
                else -> true
            }
        }

    val filteredServers =
        remember(query, visibleServers) {
            visibleServers.filter { server ->
                server.name.contains(query, ignoreCase = true) ||
                    server.command?.contains(query, ignoreCase = true) == true ||
                    server.transport?.contains(query, ignoreCase = true) == true
            }
        }

    val filteredCatalog =
        remember(state.catalogQuery, state.catalogEntries, service) {
            val q = state.catalogQuery.trim().lowercase()
            val entries = state.catalogEntries.filter { service == null || service.matches(it) }
            if (q.isEmpty()) {
                entries
            } else {
                entries.filter {
                    it.name.lowercase().contains(q) ||
                        it.description?.lowercase()?.contains(q) == true
                }
            }
        }

    LaunchedEffect(viewModel) {
        viewModel.loadServers()
        if (service != null) {
            viewModel.prepareConnection(service.id)
            viewModel.loadCatalog()
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.clearConnectionForm() }
    }
    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)
    val hasLoaded = rememberHasLoaded(state.isLoading)

    HermesScaffold(
        title = {
            Text(
                if (focused) {
                    "${service?.name ?: focusedServerName ?: "外部サービス"}の接続設定"
                } else {
                    stringResource(
                        R.string.screen_mcp_servers,
                    )
                },
            )
        },
        navigationIcon =
            if (focused) {
                NavIcon.Back {
                    NavigationController.goBack()
                }
            } else {
                onOpenDrawer?.let { NavIcon.Menu(it) }
            },
        drawerGesturesEnabled = !focused,
        isRefreshing = state.isLoading,
        onRefresh = {
            viewModel.loadServers()
            if (service != null) viewModel.loadCatalog()
        },
        actions = {
            // Import JSON lives in the add-server row below; only "test all" stays up here,
            // and only when there is something to test.
            if (!focused && state.servers.any { it.enabled }) {
                IconButton(
                    onClick = { viewModel.testAllServers() },
                    enabled = !state.isTestingAll,
                ) {
                    if (state.isTestingAll) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Science,
                            contentDescription = stringResource(R.string.mcp_servers_action_test_all),
                        )
                    }
                }
            }
        },
    ) {
        when {
            state.isLoading && state.servers.isEmpty() && !hasLoaded -> {
                SkeletonListState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = if (focused) "接続先を取得できません。接続を確認して再読込してください。" else state.errorMessage ?: "",
                    onRetry = { viewModel.loadServers() },
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    if (service != null) {
                        item(key = "service-catalog") {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                Text("接続方法を選ぶ", style = MaterialTheme.typography.titleMedium)
                                Text("接続先を準備してから、使うアカウントの認証へ進みます。")
                                McpCatalogContent(
                                    state = state,
                                    entries = filteredCatalog,
                                    onRetry = viewModel::loadCatalog,
                                    onBeginSetup = viewModel::beginCatalogSetup,
                                    onInstall = viewModel::installCatalogEntry,
                                    onUpdateEnv = viewModel::updateCatalogEnvVar,
                                )
                            }
                        }
                    }

                    // ── 1. Add server ────────────────────────────────────
                    if (focusedServerName == null) {
                        item(key = "add-header") {
                            AddServerSection(state, viewModel, spacing, connectionMode = service != null)
                        }
                    }

                    // ── 2. Search (only useful once there are servers) ──
                    if (!focused && state.servers.isNotEmpty()) {
                        item(key = "search") {
                            SearchBar(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = stringResource(R.string.mcp_search_placeholder),
                            )
                        }
                    }

                    // ── 3. Server list ───────────────────────────────────
                    if (filteredServers.isEmpty() && query.isEmpty() && !focused) {
                        // The add-server row above is the action; no second "Add" button here.
                        item(key = "empty") {
                            EmptyState(
                                title = stringResource(R.string.mcp_servers_empty_title),
                                subtitle = stringResource(R.string.mcp_servers_empty_desc),
                            )
                        }
                    } else if (filteredServers.isEmpty() && query.isNotEmpty()) {
                        item(key = "no-match") {
                            Text(
                                text = stringResource(R.string.mcp_no_match, query),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(spacing.md),
                            )
                        }
                    }

                    items(filteredServers, key = { "server:${it.name}" }) { server ->
                        ServerCard(
                            server = server,
                            state = state,
                            viewModel = viewModel,
                            spacing = spacing,
                            onClick = { showDetail = server },
                            connectionMode = focused,
                            onOpenBrowser = { url ->
                                try {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(url),
                                        ),
                                    )
                                } catch (_: Exception) {
                                }
                            },
                        )
                    }

                    // ── 4. Catalog ──────────────────────────────────────
                    if (!focused) {
                        item(key = "catalog-header") {
                            CatalogSection(
                                state = state,
                                viewModel = viewModel,
                                spacing = spacing,
                                filteredCatalog = filteredCatalog,
                            )
                        }
                    }
                }
            }
        }
    }

    showDetail?.let { server ->
        DetailDialog(
            title = server.name,
            rows = server.toDetailRows(),
            onDismiss = { showDetail = null },
        )
    }

    state.activeOAuthFlow?.let { flow ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissOAuthFlow() },
            confirmButton = {
                Button(
                    onClick = {
                        flow.authorizationUrl?.let { url ->
                            try {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url),
                                    ),
                                )
                            } catch (_: Exception) {
                            }
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.mcp_servers_oauth_dialog_open_browser))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissOAuthFlow() }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = {
                Text(stringResource(R.string.mcp_servers_oauth_dialog_title))
            },
            text = {
                Column {
                    Text(stringResource(R.string.mcp_servers_oauth_dialog_desc))
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Text(
                        text = stringResource(R.string.mcp_servers_oauth_dialog_status, flow.status),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    flow.error?.let { err ->
                        Spacer(modifier = Modifier.height(spacing.xs))
                        Text(
                            text = stringResource(R.string.mcp_servers_oauth_dialog_error, err),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
        )
    }

    if (state.showImportDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.toggleImportDialog() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(stringResource(R.string.mcp_servers_import_dialog_title))
                }
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.mcp_servers_import_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    viewModel.updateImportJsonInput(clip)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(spacing.xs))
                            Text(
                                text = stringResource(R.string.mcp_servers_import_dialog_paste),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(spacing.xs))
                    OutlinedTextField(
                        value = state.importJsonInput,
                        onValueChange = viewModel::updateImportJsonInput,
                        placeholder = {
                            Text(
                                "{\n  \"mcpServers\": {\n    \"name\": {\n      \"command\": \"npx\",\n      \"args\": [\"-y\", \"@mcp/srv\"]\n    }\n  }\n}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                        maxLines = 10,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.submitImportJson() },
                    enabled = !state.isImportingJson && state.importJsonInput.isNotBlank(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    if (state.isImportingJson) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(stringResource(R.string.mcp_servers_import_dialog_importing))
                    } else {
                        Text(stringResource(R.string.mcp_servers_action_submit))
                    }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.toggleImportDialog() }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// ── Sections ──────────────────────────────────────────────────────

@Composable
internal fun AddServerSection(
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: com.m57.hermescontrol.theme.Spacing,
    connectionMode: Boolean = false,
) {
    SectionHeader(
        title = if (connectionMode) "手動で設定する" else stringResource(R.string.mcp_servers_add_server),
        contentPadding = InListSectionHeaderPadding,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                if (!connectionMode) {
                    TextButton(
                        onClick = { viewModel.toggleImportDialog() },
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(stringResource(R.string.mcp_servers_action_import_json))
                    }
                }
                TextButton(
                    onClick = { viewModel.toggleAddForm() },
                    colors =
                        ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        if (state.showAddForm) {
                            stringResource(R.string.action_close)
                        } else {
                            if (connectionMode) "設定を開く" else stringResource(R.string.mcp_servers_add_manual)
                        },
                    )
                }
            }
        },
    )

    AnimatedVisibility(visible = state.showAddForm) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                val addModeIndex = if (state.addMode == AddServerMode.HTTP) 0 else 1
                SecondaryTabRow(
                    selectedTabIndex = addModeIndex,
                    indicator = { HermesSecondaryIndicator(addModeIndex) },
                ) {
                    Tab(
                        selected = state.addMode == AddServerMode.HTTP,
                        onClick = { viewModel.setAddMode(AddServerMode.HTTP) },
                        text = { Text(stringResource(R.string.mcp_servers_add_http)) },
                    )
                    Tab(
                        selected = state.addMode == AddServerMode.Stdio,
                        onClick = { viewModel.setAddMode(AddServerMode.Stdio) },
                        text = { Text(stringResource(R.string.mcp_servers_add_stdio)) },
                    )
                }

                Spacer(modifier = Modifier.height(spacing.sm))

                OutlinedTextField(
                    value = state.addServerName,
                    onValueChange = viewModel::updateAddServerName,
                    label = { Text(stringResource(R.string.mcp_servers_field_name)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                if (state.addMode == AddServerMode.HTTP) {
                    OutlinedTextField(
                        value = state.addServerUrl,
                        onValueChange = viewModel::updateAddServerUrl,
                        label = { Text(stringResource(R.string.mcp_servers_field_url)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Text(
                        text = stringResource(R.string.mcp_servers_auth_mode),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(spacing.xs))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        FilterChip(
                            selected = state.addServerAuth == "none",
                            onClick = { viewModel.updateAddServerAuth("none") },
                            label = { Text(stringResource(R.string.mcp_servers_auth_none)) },
                        )
                        FilterChip(
                            selected = state.addServerAuth == "header",
                            onClick = { viewModel.updateAddServerAuth("header") },
                            label = { Text(stringResource(R.string.mcp_servers_auth_header)) },
                        )
                        FilterChip(
                            selected = state.addServerAuth == "oauth",
                            onClick = { viewModel.updateAddServerAuth("oauth") },
                            label = { Text(stringResource(R.string.mcp_servers_auth_oauth)) },
                        )
                    }
                    if (state.addServerAuth == "header") {
                        Spacer(modifier = Modifier.height(spacing.sm))
                        OutlinedTextField(
                            value = state.addServerBearerToken,
                            onValueChange = viewModel::updateAddServerBearerToken,
                            label = { Text(stringResource(R.string.mcp_servers_field_bearer_token)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            visualTransformation = remember { PasswordVisualTransformation() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = state.addServerCommand,
                        onValueChange = viewModel::updateAddServerCommand,
                        label = { Text(stringResource(R.string.mcp_servers_field_command)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    OutlinedTextField(
                        value = state.addServerArgs,
                        onValueChange = viewModel::updateAddServerArgs,
                        label = { Text(stringResource(R.string.mcp_servers_field_args)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(spacing.md))
                Button(
                    onClick = { viewModel.submitAddServer() },
                    enabled = !state.addingServer && !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        if (state.addingServer) {
                            stringResource(R.string.mcp_servers_action_adding)
                        } else {
                            stringResource(R.string.mcp_servers_action_submit)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ServerCard(
    server: McpServer,
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: com.m57.hermescontrol.theme.Spacing,
    onClick: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    connectionMode: Boolean = false,
) {
    var showEnv by remember { mutableStateOf(false) }
    val isTesting = server.name in state.testingServers
    val testResult = state.serverTestResults[server.name]
    val healthStatus = McpHealthStatus.resolve(isTesting, testResult, server.status, server.error)

    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("接続先を削除しますか？") },
            text = { Text("${server.name}を使うすべてのサービスが利用できなくなります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteServer(server.name)
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
    Column(
        modifier =
            if (connectionMode) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.fillMaxWidth().clickable(onClick = onClick)
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.sm)) {
            // Header row: name + status + toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server.name.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        when (healthStatus) {
                            McpHealthStatus.HEALTHY -> {
                                StatusBadge(
                                    text = stringResource(R.string.mcp_servers_status_healthy),
                                    status = StatusBadgeType.SUCCESS,
                                )
                            }

                            McpHealthStatus.AUTH_REQUIRED -> {
                                StatusBadge(
                                    text = stringResource(R.string.mcp_servers_status_needs_auth),
                                    status = StatusBadgeType.WARNING,
                                )
                            }

                            McpHealthStatus.ERROR -> {
                                StatusBadge(
                                    text = stringResource(R.string.mcp_servers_status_error),
                                    status = StatusBadgeType.ERROR,
                                )
                            }

                            McpHealthStatus.TESTING -> {
                                StatusBadge(
                                    text = stringResource(R.string.mcp_servers_status_testing),
                                    status = StatusBadgeType.INFO,
                                )
                            }

                            McpHealthStatus.UNKNOWN -> {
                                ServerStatusBadge(server.status)
                            }
                        }
                    }
                    if (!connectionMode) {
                        Text(
                            text = stringResource(R.string.mcp_servers_label_transport, server.transport ?: "stdio"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Tool count & schema token overhead badge (issue #1029)
                    val toolInfos = testResult?.tools
                    val toolCount = toolInfos?.size ?: server.tools?.size
                    if (toolCount != null && toolCount > 0 && !connectionMode) {
                        val tokenEst = toolInfos?.let { McpTokenEstimator.estimateTokens(it) }
                        val label = McpTokenEstimator.formatTokenOverhead(toolCount, tokenEst)
                        Spacer(modifier = Modifier.height(spacing.xs))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = spacing.xs + 2.dp, vertical = 2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Build,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(modifier = Modifier.width(spacing.xs))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = { viewModel.toggleServer(server) },
                )
            }

            // URL or Command
            if (connectionMode) {
                Text(connectionHost(server.url) ?: "Hermesサーバー上の接続")
                val authLabel =
                    when (server.auth) {
                        "oauth" -> "サービスのログイン画面で認証"
                        "header" -> "アクセストークンで認証"
                        "none" -> "認証なし"
                        else -> "接続先の設定に従う"
                    }
                Text(authLabel, style = MaterialTheme.typography.bodyMedium)
                Text("この接続先を使う全サービスに設定が適用されます。", style = MaterialTheme.typography.bodySmall)
            } else if (server.url != null) {
                Spacer(modifier = Modifier.height(spacing.sm))
                Text(
                    text = server.url,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            } else if (server.command != null) {
                Spacer(modifier = Modifier.height(spacing.sm))
                Text(
                    text = stringResource(R.string.mcp_servers_label_command),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${server.command} ${server.args.orEmpty().joinToString(" ")}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }

            // Error diagnostics
            server.error?.let { error ->
                if (error.isNotBlank()) {
                    Spacer(modifier = Modifier.height(spacing.sm))
                    StatusBadge(
                        text = if (connectionMode) "接続を確認できません。認証や接続先の設定を確認してください。" else error,
                        status = StatusBadgeType.ERROR,
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.sm))

            // Action buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (server.auth == "oauth") {
                    TextButton(
                        onClick = { viewModel.startMcpOAuthFlow(server, onOpenBrowser) },
                        modifier = if (connectionMode) Modifier.fillMaxWidth() else Modifier.weight(1.2f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(if (connectionMode) "アカウントを認証" else stringResource(R.string.mcp_servers_action_authorize))
                    }
                }
                TextButton(
                    onClick = { viewModel.testServer(server.name) },
                    enabled = !isTesting,
                    modifier = if (connectionMode) Modifier.fillMaxWidth() else Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(if (connectionMode) "接続を確認中…" else stringResource(R.string.mcp_servers_status_testing))
                    } else {
                        Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(if (connectionMode) "接続を確認" else stringResource(R.string.mcp_servers_action_test))
                    }
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }

            // Env vars toggle
            TextButton(
                onClick = { showEnv = !showEnv },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text(if (connectionMode) "接続先の詳細設定" else stringResource(R.string.mcp_servers_env_vars))
            }

            AnimatedVisibility(visible = showEnv) {
                EnvVarSection(server = server, state = state, viewModel = viewModel, spacing = spacing)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EnvVarSection(
    server: McpServer,
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: com.m57.hermescontrol.theme.Spacing,
) {
    val isEditing = state.editingEnvFor == server.name
    val env = server.env ?: emptyMap()

    if (env.isEmpty() && !isEditing) {
        Text(
            text = stringResource(R.string.mcp_servers_env_no_vars),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = spacing.xs),
        )
    } else {
        env.forEach { (key, _) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "••••••••",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.removeEnvVar(server.name, key) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_remove),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    if (isEditing) {
        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.envKeyInput,
                onValueChange = viewModel::updateEnvKey,
                label = { Text(stringResource(R.string.mcp_servers_env_key)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.envValueInput,
                onValueChange = viewModel::updateEnvValue,
                label = { Text(stringResource(R.string.mcp_servers_env_value)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Button(onClick = { viewModel.addEnvVar(server.name) }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.mcp_servers_env_add))
            }
            OutlinedButton(onClick = { viewModel.stopEditingEnv() }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    } else {
        TextButton(onClick = { viewModel.startEditingEnv(server) }, shapes = ButtonDefaults.shapes()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(stringResource(R.string.mcp_servers_env_add))
        }
    }
}

@Composable
private fun ServerStatusBadge(status: String?) {
    if (status == null) return
    val badgeType =
        when (status.lowercase()) {
            "running", "ok", "connected" -> StatusBadgeType.SUCCESS
            "error", "failed" -> StatusBadgeType.ERROR
            "stopped" -> StatusBadgeType.NEUTRAL
            else -> StatusBadgeType.NEUTRAL
        }
    StatusBadge(text = status, status = badgeType)
}

// ── Catalog ─────────────────────────────────────────────────────

@Composable
private fun CatalogSection(
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: com.m57.hermescontrol.theme.Spacing,
    filteredCatalog: List<McpCatalogEntry>,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionHeader(
        title = stringResource(R.string.mcp_servers_section_catalog),
        contentPadding = InListSectionHeaderPadding,
        trailing = {
            TextButton(
                onClick = {
                    expanded = !expanded
                    if (expanded && state.catalogEntries.isEmpty()) viewModel.loadCatalog()
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    if (expanded) {
                        stringResource(
                            R.string.action_close,
                        )
                    } else {
                        stringResource(R.string.mcp_servers_catalog_browse)
                    },
                )
            }
        },
    )
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            SearchBar(
                query = state.catalogQuery,
                onQueryChange = viewModel::updateCatalogQuery,
                placeholder = stringResource(R.string.mcp_servers_catalog_search),
            )
            McpCatalogContent(
                state = state,
                entries = filteredCatalog,
                onRetry = viewModel::loadCatalog,
                onBeginSetup = viewModel::beginCatalogSetup,
                onInstall = viewModel::installCatalogEntry,
                onUpdateEnv = viewModel::updateCatalogEnvVar,
            )
        }
    }
}
