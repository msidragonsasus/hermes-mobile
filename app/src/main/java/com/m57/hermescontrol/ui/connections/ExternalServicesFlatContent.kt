package com.m57.hermescontrol.ui.connections

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsListItem
import com.m57.hermescontrol.ui.common.SettingsMenuItem
import com.m57.hermescontrol.ui.common.SettingsOverflowMenu
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.common.SettingsServiceMark
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType

@Composable
internal fun ExternalServicesContent(
    state: ExternalServicesState,
    onOpenService: (String) -> Unit,
    onOpenServer: (String) -> Unit,
    onAdvanced: () -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("すべて") }
    val filtered =
        externalServices.filter { service ->
            val status = serviceConnectionState(service, state)
            val matchesFilter =
                when (filter) {
                    "設定済み" -> {
                        status in
                            setOf(
                                ServiceConnectionState.CONNECTED,
                                ServiceConnectionState.CONFIGURED,
                                ServiceConnectionState.DISABLED,
                            )
                    }

                    "要確認" -> {
                        status in setOf(ServiceConnectionState.NEEDS_ATTENTION, ServiceConnectionState.UNKNOWN)
                    }

                    else -> {
                        true
                    }
                }
            matchesFilter &&
                "${service.name} ${service.summary} ${service.examples.joinToString()}".contains(query.trim(), true)
        }
    val other =
        state.servers
            .filter { server -> externalServices.none { it.matches(server) } }
            .filter { it.name.contains(query.trim(), true) }
            .filter { filter != "要確認" || !it.error.isNullOrBlank() || it.status in setOf("error", "failed") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("external_services"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item {
            SearchBar(query, { query = it }, placeholder = "サービス名・使い方で検索")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                listOf("すべて", "設定済み", "要確認").forEach { label ->
                    FilterChip(
                        selected = filter == label,
                        onClick = { filter = label },
                        label = { Text(label) },
                        border = null,
                    )
                }
            }
        }
        if (state.mcpError || state.gmailError) {
            item {
                CompactListRow(trailing = { TextButton(onClick = onRetry) { Text("再読込") } }) {
                    Text(
                        "一部の接続状態を確認できません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (filtered.isNotEmpty()) item { SettingsGroupLabel(stringResource(R.string.settings_group_connectors)) }
        itemsIndexed(filtered, key = { _, service -> service.id }) { index, service ->
            SettingsListItem(first = index == 0, last = index == filtered.lastIndex) {
                val status = serviceConnectionState(service, state)
                SettingsRow(
                    title = service.name,
                    subtitle = service.summary,
                    modifier = Modifier.testTag("service_${service.id}"),
                    leading = { ServiceIcon(service.id, 24.dp) },
                    onClick = { onOpenService(service.id) },
                    // "Not set" is the quiet default; only states worth noticing get a badge.
                    status =
                        if (status != ServiceConnectionState.NOT_CONFIGURED) {
                            { ConnectionBadge(status) }
                        } else {
                            null
                        },
                    chevron = true,
                )
            }
        }
        if (other.isNotEmpty()) item { SettingsGroupLabel("その他の接続") }
        itemsIndexed(
            other,
            key = { _, server -> "server:${server.name}" },
        ) { index, server ->
            SettingsListItem(first = index == 0, last = index == other.lastIndex) {
                ConnectionTargetRow(server) { onOpenServer(server.name) }
            }
        }
        if (filtered.isEmpty() && other.isEmpty()) {
            item { EmptyState(title = "該当するサービスがありません", subtitle = "検索語や絞り込みを変えてください。") }
        }
        item {
            SettingsGroup(Modifier.padding(top = 24.dp)) {
                row {
                    SettingsRow(
                        title = stringResource(R.string.settings_group_custom_connections),
                        chevron = true,
                        onClick = onAdvanced,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExternalServiceDetailContent(
    service: ExternalService,
    state: ExternalServicesState,
    onGmail: () -> Unit,
    onOpenServer: (String) -> Unit,
    onSetup: () -> Unit,
    slackState: SlackConnectionUiState? = null,
    onSlackConnect: () -> Unit = {},
    onSlackCancel: () -> Unit = {},
    onSlackRefresh: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onConnect: (() -> Unit)? = null,
    googleWorkspace: GoogleWorkspaceUiState? = null,
    onWorkspaceReconnect: () -> Unit = {},
    onWorkspaceDisconnect: (String) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val servers = state.servers.filter(service::matches)
    val actions = serviceActions(servers)
    val status = serviceConnectionState(service, state)
    var info by rememberSaveable(service.id) { mutableStateOf(false) }
    var allActions by rememberSaveable(service.id) { mutableStateOf(false) }
    val warning = MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SettingsGroup {
            row {
                SettingsRow(
                    title = service.name,
                    subtitle = service.summary,
                    leading = { SettingsServiceMark { ServiceIcon(service.id, 28.dp) } },
                    trailing = {
                        SettingsOverflowMenu(
                            listOf(
                                SettingsMenuItem("再読込") {
                                    onRefresh()
                                    onSlackRefresh()
                                },
                                SettingsMenuItem("詳細設定", onClick = onSetup),
                                SettingsMenuItem(if (info) "説明を閉じる" else "使い方と接続の説明") { info = !info },
                            ),
                            contentDescription = "${service.name}の操作",
                        )
                    },
                )
            }
        }
        if (info) {
            Column(Modifier.padding(top = spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                service.examples.forEach { Footnote("・$it") }
                Footnote("利用できる操作は接続先と許可した権限によって変わります。")
                Footnote("同じ接続先を使うサービスでは、設定の変更が各サービスに影響します。")
            }
        }

        if (service.id == "gmail") {
            // Gmail has its own screen; this path stays for older back stacks.
            SettingsGroupLabel("接続済みアカウント")
            SettingsGroup {
                row {
                    SettingsRow(
                        title = "Gmailアカウントを管理・追加",
                        chevron = true,
                        onClick = onGmail,
                    )
                }
            }
        }

        if (service.id == "slack") {
            SettingsGroupLabel("接続済みアカウント")
            SettingsGroup {
                slackAccountRows(
                    slackState ?: SlackConnectionUiState(),
                    onConnect ?: onSlackConnect,
                    onSlackCancel,
                    onSlackRefresh,
                )
            }
        }

        if (googleWorkspace != null && googleWorkspace.canConnect()) {
            GoogleWorkspaceAccountsSection(
                googleWorkspace,
                onAdd = onConnect ?: onSetup,
                onReconnect = onWorkspaceReconnect,
                onDisconnect = onWorkspaceDisconnect,
            )
        } else {
            googleWorkspace?.error?.let { Footnote(it) }
        }

        SettingsGroupLabel("接続先")
        SettingsGroup {
            when {
                state.loading -> {
                    row { SettingsRow(title = "確認中…") }
                }

                state.mcpError -> {
                    row {
                        SettingsRow(
                            title = "接続先を取得できません。下に引いて再読込してください。",
                            titleColor = warning,
                        )
                    }
                }

                servers.isEmpty() -> {
                    row { SettingsRow(title = "接続先はまだ登録されていません。") }
                }
            }
            servers.forEach { server ->
                row {
                    val failing = !server.error.isNullOrBlank() || server.status in setOf("error", "failed")
                    SettingsRow(
                        title = server.name,
                        subtitle =
                            listOfNotNull(
                                connectionHost(server.url) ?: "Hermesサーバー上の接続",
                                if (server.enabled) "有効" else "無効",
                                "確認が必要".takeIf { server.enabled && failing },
                            ).joinToString(" · "),
                        subtitleColor = if (server.enabled && failing) warning else Color.Unspecified,
                        chevron = true,
                        onClick = { onOpenServer(server.name) },
                    )
                }
            }
            if (service.id != "slack") {
                row {
                    // Opens the connect sheet on this page; manual setup lives in the ︙ menu.
                    SettingsRow(
                        title = "${service.name}を接続",
                        leading = { Icon(Icons.Default.Add, null, Modifier.size(24.dp)) },
                        onClick = onConnect ?: onSetup,
                        modifier = Modifier.testTag("service_connect"),
                    )
                }
            }
        }

        if (actions.isNotEmpty()) {
            SettingsGroupLabel("アクション（${actions.size}件）")
            SettingsGroup {
                val shown = if (allActions) actions else actions.take(ACTIONS_PREVIEW)
                shown.forEach { name -> row { SettingsRow(title = actionLabel(name)) } }
                if (actions.size > ACTIONS_PREVIEW) {
                    row {
                        SettingsRow(
                            title = if (allActions) "閉じる" else "すべて表示",
                            titleColor = MaterialTheme.colorScheme.primary,
                            onClick = { allActions = !allActions },
                        )
                    }
                }
            }
            Footnote("接続先が公開している操作の一覧です。読み取り・書き込みの区別はサーバーから届いていません。")
        }
        if (status == ServiceConnectionState.UNKNOWN) {
            Footnote("一部の接続状態を確認できません。")
        }
        Spacer(Modifier.height(spacing.xl))
    }
}

private const val ACTIONS_PREVIEW = 6

@Composable
private fun ConnectionTargetRow(
    server: McpServer,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = server.name,
        subtitle = "${connectionHost(server.url) ?: "Hermesサーバー上の接続"} · ${if (server.enabled) "有効" else "無効"}",
        chevron = true,
        onClick = onClick,
    )
}

@Composable
internal fun ConnectionBadge(status: ServiceConnectionState) {
    StatusBadge(
        status.label,
        when (status) {
            ServiceConnectionState.CONNECTED -> StatusBadgeType.SUCCESS
            ServiceConnectionState.NEEDS_ATTENTION, ServiceConnectionState.UNKNOWN -> StatusBadgeType.WARNING
            ServiceConnectionState.CHECKING -> StatusBadgeType.INFO
            else -> StatusBadgeType.NEUTRAL
        },
    )
}

@Composable
internal fun ServiceIcon(
    id: String,
    size: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val icon =
        when (id) {
            "gmail" -> Icons.Default.Email
            "calendar" -> Icons.Default.CalendarMonth
            "drive", "dropbox" -> Icons.Default.Folder
            "figma" -> Icons.Default.DesignServices
            "slack" -> Icons.AutoMirrored.Filled.Chat
            "github" -> Icons.Default.Code
            "notion" -> Icons.Default.Description
            else -> Icons.Default.Hub
        }
    Icon(icon, contentDescription = null, modifier = Modifier.size(size))
}
