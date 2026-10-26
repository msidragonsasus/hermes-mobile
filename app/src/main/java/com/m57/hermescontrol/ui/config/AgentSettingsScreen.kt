package com.m57.hermescontrol.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.ConfigAdvancedScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
import kotlinx.coroutines.launch

/**
 * "エージェント設定": the settings a person actually changes, in plain words, saved on tap.
 * The full schema-driven editor stays one row away under 詳細設定.
 */
@Composable
fun AgentSettingsScreen(
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: AgentSettingsViewModel = viewModel { AgentSettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<AgentSetting, AgentSettingOption>?>(null) }

    AgentSettingsNoticeEffect(state.notice, snackbar, viewModel::consumeNotice, viewModel::undo)

    fun choose(
        setting: AgentSetting,
        option: AgentSettingOption,
    ) {
        expandedId = null
        if (option.confirmMessage != null) {
            confirm = setting to option
        } else {
            viewModel.select(setting, option)
        }
    }

    HermesScaffold(
        title = { Text("エージェント設定") },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = viewModel::load,
        snackbarHost = { SnackbarHost(snackbar) },
    ) {
        val values = state.values
        when {
            values == null && state.isLoading -> {
                SkeletonListState()
            }

            values == null -> {
                ErrorState(message = state.errorMessage ?: "", onRetry = viewModel::load)
            }

            else -> {
                AgentSettingsList(
                    state = state,
                    values = values,
                    query = query,
                    onQueryChange = { query = it },
                    expandedId = expandedId,
                    onExpandedChange = { setting, open -> expandedId = if (open) setting.id else null },
                    onChoose = ::choose,
                    onRestore = { setting ->
                        expandedId = null
                        viewModel.restoreDefault(setting)
                    },
                )
            }
        }
    }

    confirm?.let { (setting, option) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("「${option.label}」にしますか？") },
            text = { Text(option.confirmMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    viewModel.select(setting, option)
                }) { Text("変更する") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("やめる") } },
        )
    }
}

@Composable
internal fun AgentSettingsNoticeEffect(
    notice: AgentSettingsNotice?,
    snackbar: SnackbarHostState,
    onConsume: (Long) -> Unit,
    onUndo: (AgentSettingsNotice) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentUndo by rememberUpdatedState(onUndo)
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        onConsume(current.id)
        scope.launch {
            val result =
                snackbar.showSnackbar(
                    message = current.message,
                    actionLabel = if (current.undo != null) "元に戻す" else null,
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) currentUndo(current)
        }
    }
}

@Composable
private fun AgentSettingsList(
    state: AgentSettingsUiState,
    values: Map<String, kotlinx.serialization.json.JsonElement>,
    query: String,
    onQueryChange: (String) -> Unit,
    expandedId: String?,
    onExpandedChange: (AgentSetting, Boolean) -> Unit,
    onChoose: (AgentSetting, AgentSettingOption) -> Unit,
    onRestore: (AgentSetting) -> Unit,
) {
    val spacing = LocalSpacing.current
    val groups =
        state.visibleGroups
            .map { group -> group.copy(settings = group.settings.filter { it.matches(query) }) }
            .filter { it.settings.isNotEmpty() }
    LazyColumn(Modifier.fillMaxSize().testTag("agent_settings_list")) {
        item("google_connection") {
            ListItem(
                headlineContent = { Text("Google接続") },
                supportingContent = { Text("アカウントを選び、HermesにGmailの読み取りを許可します。") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        NavigationController.navigateTo(
                            com.m57.hermescontrol.SettingsGoogleConnection,
                        )
                    },
            )
        }
        item("search") {
            SearchBar(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = "設定を探す（例: 確認、記憶、時間）",
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
            )
        }
        groups.forEach { group ->
            item("group_${group.title}") { SectionHeader(group.title, Modifier.padding(top = spacing.sm)) }
            items(group.settings, key = { it.id }) { setting ->
                AgentSettingItem(
                    setting = setting,
                    state = state,
                    values = values,
                    expanded = expandedId == setting.id,
                    showDescription = true,
                    onExpandedChange = { onExpandedChange(setting, it) },
                    onChoose = { onChoose(setting, it) },
                    onRestore = { onRestore(setting) },
                )
            }
        }
        if (groups.isEmpty()) {
            item("empty") {
                Text(
                    "「$query」に合う設定はありません。下の詳細設定ではすべての項目を検索できます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.lg),
                )
            }
        }
        item("advanced") {
            HorizontalDivider(Modifier.padding(top = spacing.md))
            ListItem(
                headlineContent = { Text("詳細設定（上級者向け）") },
                supportingContent = {
                    Text(
                        if (state.advancedFieldCount > 0) {
                            "設定ファイルの全${state.advancedFieldCount}項目をそのまま編集します。"
                        } else {
                            "設定ファイルをそのまま編集します。"
                        },
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier.testTag("agent_settings_advanced").clickable {
                        NavigationController.navigateTo(ConfigAdvancedScreen)
                    },
            )
        }
    }
}
