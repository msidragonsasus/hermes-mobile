package com.m57.hermescontrol.ui.system

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.MediaImageStore
import com.m57.hermescontrol.ui.common.ActionProgressDialog
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.system.components.CredentialEntryRow
import com.m57.hermescontrol.ui.system.components.HookCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Helpers ─────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
    }

private fun formatDuration(totalSeconds: Double): String {
    val days = (totalSeconds / 86400).toInt()
    val hours = ((totalSeconds % 86400) / 3600).toInt()
    val minutes = ((totalSeconds % 3600) / 60).toInt()
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        append("${minutes}m")
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SystemScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: SystemViewModel? = null,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel = viewModel ?: viewModel { SystemViewModel(app) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        viewModel.loadAll()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    // ── Backup import (issue #786) ────────────────────────────────────────
    // SAF file picker → stage uri/name/mime → confirm dialog → read bytes on
    // IO → multipart upload via SystemViewModel.importArchive.
    val context = LocalContext.current
    val importScope = rememberCoroutineScope()
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    val importPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            importScope.launch {
                val meta =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val name =
                                uri.lastPathSegment?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
                                    ?: "backup.zip"
                            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            PendingImport(uri, name, mime)
                        }
                    }
                meta.fold(
                    onSuccess = {
                        pendingImport = it
                        viewModel.setImportFile(it.name)
                    },
                    onFailure = { viewModel.showToast("Could not read selected file: ${it.message}") },
                )
            }
        }

    // ── Confirmation dialogs ──────────────────────────────────────────────

    if (state.updateConfirmOpen) {
        AlertDialog(
            onDismissRequest = viewModel::closeUpdateConfirm,
            title = { Text(stringResource(R.string.system_update_confirm_title)) },
            text = { Text(stringResource(R.string.system_update_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.closeUpdateConfirm()
                    viewModel.applyUpdate()
                }) {
                    Text(stringResource(R.string.system_confirm_update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeUpdateConfirm, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }

    // Update progress popup (issue #863): replaces the old bottom-of-screen
    // action log for the update flow — spinner + log tail while running,
    // success/failure state once it exits.
    ActionProgressDialog(
        controller = viewModel.actionProgress,
        title = stringResource(R.string.system_update_progress_title),
    )

    // Credential remove confirmation
    var credToRemove by remember { mutableStateOf<Pair<String, Int>?>(null) }
    if (credToRemove != null) {
        AlertDialog(
            onDismissRequest = { credToRemove = null },
            title = { Text(stringResource(R.string.system_credentials_remove_confirm_title)) },
            text = { Text(stringResource(R.string.system_credentials_remove_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    credToRemove?.let { (p, i) -> viewModel.removeCredential(p, i) }
                    credToRemove = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { credToRemove = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }

    // Prune checkpoints confirmation
    var pruneConfirm by remember { mutableStateOf(false) }
    if (pruneConfirm) {
        AlertDialog(
            onDismissRequest = { pruneConfirm = false },
            title = { Text(stringResource(R.string.system_checkpoints_prune_confirm_title)) },
            text = { Text(stringResource(R.string.system_checkpoints_prune_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    pruneConfirm = false
                    viewModel.pruneCheckpoints()
                }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pruneConfirm = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }

    // Import / Restore confirmation
    var importConfirm by remember { mutableStateOf(false) }
    if (importConfirm) {
        AlertDialog(
            onDismissRequest = { importConfirm = false },
            title = { Text(stringResource(R.string.system_op_import_confirm_title)) },
            text = { Text(stringResource(R.string.system_op_import_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    importConfirm = false
                    val pending = pendingImport
                    pendingImport = null
                    if (pending != null) {
                        importScope.launch {
                            val read =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        context.contentResolver.openInputStream(pending.uri)?.use { it.readBytes() }
                                    }
                                }
                            read.fold(
                                onSuccess = { bytes ->
                                    if (bytes != null) {
                                        viewModel.importArchive(pending.name, bytes, pending.mime)
                                    } else {
                                        viewModel.showToast("Could not read selected file")
                                    }
                                },
                                onFailure = {
                                    viewModel.showToast("Could not read selected file: ${it.message}")
                                },
                            )
                        }
                    }
                }) {
                    Text(stringResource(R.string.system_confirm_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { importConfirm = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }

    HermesScaffold(
        modifier = modifier,
        title = { Text(stringResource(R.string.screen_system)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadAll() },
    ) {
        when {
            state.isLoading && state.stats == null && state.doctorReport == null &&
                state.status == null -> {
                SkeletonListState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { viewModel.loadAll() },
                )
            }

            state.stats == null && state.doctorReport == null && state.status == null -> {
                EmptyState(
                    title = stringResource(R.string.system_empty_title),
                    subtitle = stringResource(R.string.system_empty_desc),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            horizontal = spacing.md,
                            vertical = spacing.sm,
                        ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    // ── 1. Host ────────────────────────────────────────────
                    hostSection(state, spacing, statusColors, viewModel)

                    // ── 2. Portal ──────────────────────────────────────────
                    portalSection(state, spacing, uriHandler)

                    // ── 3. Curator ─────────────────────────────────────────
                    curatorSection(state, spacing, statusColors, viewModel)

                    // ── 4. Gateway ─────────────────────────────────────────
                    gatewaySection(state, spacing, statusColors, viewModel)

                    // ── 6. Credentials ─────────────────────────────────────
                    credentialsSection(state, spacing, viewModel, credToRemove, { credToRemove = it })

                    // ── 7. Operations ──────────────────────────────────────
                    operationsSection(
                        state,
                        spacing,
                        viewModel,
                        importConfirm,
                        { importConfirm = it },
                        {
                            importPickerLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "*/*",
                                ),
                            )
                        },
                        {
                            viewModel.downloadBackup { body, fileName ->
                                importScope.launch {
                                    val uri =
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                MediaImageStore.saveToDownloads(
                                                    context,
                                                    body.byteStream(),
                                                    body.contentLength().takeIf { it >= 0 },
                                                    fileName,
                                                    "application/zip",
                                                )
                                            }.getOrNull()
                                        }
                                    if (uri != null) {
                                        viewModel.showToast("Backup saved to Downloads")
                                    } else {
                                        viewModel.showToast("Could not save backup to Downloads")
                                    }
                                }
                            }
                        },
                    )

                    // ── 8. Checkpoints ─────────────────────────────────────
                    checkpointsSection(state, spacing, pruneConfirm, { pruneConfirm = it })

                    // ── 9. Shell Hooks ─────────────────────────────────────
                    shellHooksSection(state, spacing, viewModel)

                    // ── 10. Action Log ─────────────────────────────────────
                    if (state.activeAction != null) {
                        actionLogSection(state, spacing, viewModel)
                    }
                }
            }
        }
    }
}

// ── Section composables ─────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun LazyListScope.hostSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    statusColors: com.m57.hermescontrol.theme.HermesStatusColors,
    viewModel: SystemViewModel,
) {
    state.stats?.let { stats ->
        item {
            SectionHeader(contentPadding = SystemHeaderPadding, title = stringResource(R.string.system_sec_host))
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // OS, Arch, Hostname
                    stats.os?.let { SystemInfoRow(stringResource(R.string.system_label_os), it) }
                    stats.arch?.let { SystemInfoRow(stringResource(R.string.system_label_arch), it) }
                    stats.hostname?.let { SystemInfoRow(stringResource(R.string.system_label_hostname), it) }
                    stats.python_version?.let { python ->
                        val impl = stats.python_impl?.let { " ($it)" } ?: ""
                        SystemInfoRow(stringResource(R.string.system_label_python), "$python$impl")
                    }

                    // Hermes version + update badge
                    stats.hermes_version?.let { ver ->
                        val updateBadge =
                            state.updateInfo?.let { info ->
                                when {
                                    info.update_available == true && info.behind != null && info.behind > 0 -> {
                                        " (${stringResource(
                                            R.string.system_version_update_available,
                                        )}: ${stringResource(R.string.system_version_behind, info.behind)})"
                                    }

                                    info.update_available == true -> {
                                        " (${stringResource(
                                            R.string.system_version_update_available,
                                        )})"
                                    }

                                    else -> {
                                        " (${stringResource(R.string.system_version_latest)})"
                                    }
                                }
                            } ?: ""
                        SystemInfoRow(stringResource(R.string.system_label_hermes_version), "$ver$updateBadge")
                    }

                    // Update buttons
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        TextButton(
                            onClick = { viewModel.checkForUpdate(false) },
                            enabled = !state.checkingUpdate,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            if (state.checkingUpdate) {
                                ActionButtonContent(
                                    icon = null,
                                    text = stringResource(R.string.system_action_check_updates),
                                    loading = true,
                                )
                            } else {
                                ActionButtonContent(
                                    icon = Icons.Filled.Update,
                                    text = stringResource(R.string.system_action_check_updates),
                                )
                            }
                        }
                        state.updateInfo?.let { info ->
                            if (info.update_available == true && info.can_apply == true) {
                                PrimaryPillButton(
                                    onClick = { viewModel.openUpdateConfirm() },
                                ) {
                                    ActionButtonContent(
                                        icon = Icons.Filled.PlayArrow,
                                        text = stringResource(R.string.system_action_update_now),
                                    )
                                }
                            }
                        }
                    }

                    // CPU load as a label/value row (no stat tiles)
                    stats.cpu_percent?.let { cpu ->
                        SystemInfoRow(stringResource(R.string.system_label_cpu), "${cpu.toInt()}%")
                    }


                    // Memory details
                    stats.memory?.let { mem ->
                        SystemInfoRow(
                            label = stringResource(R.string.system_label_memory),
                            value =
                                stringResource(
                                    R.string.system_label_used_total_percent,
                                    formatBytes(mem.used ?: 0L),
                                    formatBytes(mem.total ?: 0L),
                                    "${(mem.percent ?: 0.0).toInt()}",
                                ),
                        )
                    }

                    // Disk details
                    stats.disk?.let { disk ->
                        SystemInfoRow(
                            label = stringResource(R.string.system_label_disk),
                            value =
                                stringResource(
                                    R.string.system_label_used_total_percent,
                                    formatBytes(disk.used ?: 0L),
                                    formatBytes(disk.total ?: 0L),
                                    "${(disk.percent ?: 0.0).toInt()}",
                                ),
                        )
                    }

                    // Uptime
                    stats.uptime_seconds?.let { secs ->
                        SystemInfoRow(
                            label = stringResource(R.string.system_label_uptime),
                            value = formatDuration(secs),
                        )
                    }

                    // Load avg
                    stats.load_avg?.let { loads ->
                        if (loads.isNotEmpty()) {
                            val coresText =
                                stats.cpu_count?.let {
                                    " ($it ${stringResource(
                                        R.string.system_label_cores,
                                    )})"
                                } ?: ""
                            SystemInfoRow(
                                label = stringResource(R.string.system_label_load_avg),
                                value = loads.joinToString(", ") { "%.2f".format(it) } + coresText,
                            )
                        }
                    } ?: stats.cpu_count?.let { cores ->
                        SystemInfoRow(
                            label = stringResource(R.string.system_label_cpu),
                            value = "$cores ${stringResource(R.string.system_label_cores)}",
                        )
                    }

                    // psutil warning
                    if (stats.psutil == false) {
                        Spacer(modifier = Modifier.height(spacing.sm))
                        StatusBadge(
                            text = stringResource(R.string.system_psutil_warning),
                            status = StatusBadgeType.WARNING,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.portalSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    state.portal?.let { portal ->
        item {
            SectionHeader(contentPadding = SystemHeaderPadding, title = stringResource(R.string.system_sec_portal))
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Login status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        val loggedIn = portal.logged_in == true
                        StatusBadge(
                            text =
                                if (loggedIn) {
                                    stringResource(R.string.system_status_logged_in)
                                } else {
                                    stringResource(R.string.system_status_not_logged_in)
                                },
                            status = if (loggedIn) StatusBadgeType.SUCCESS else StatusBadgeType.WARNING,
                        )
                        if (!loggedIn) {
                            Text(
                                text = stringResource(R.string.system_portal_login_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Provider
                    portal.provider?.let { provider ->
                        SystemInfoRow(
                            label = stringResource(R.string.system_portal_provider, provider),
                            value = "",
                        )
                    }

                    // Manage subscription link
                    portal.subscription_url?.let { url ->
                        TextButton(
                            onClick = { uriHandler.openUri(url) },
                            modifier = Modifier.padding(top = spacing.xs),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(spacing.xs))
                            Text(
                                stringResource(R.string.system_portal_manage_subscription),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }

                    // Feature routing
                    portal.features?.let { features ->
                        if (features.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                            features.forEach { feature ->
                                SystemInfoRow(
                                    label = feature.label ?: stringResource(R.string.system_portal_feature_routing),
                                    value = feature.state ?: "",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.curatorSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    statusColors: com.m57.hermescontrol.theme.HermesStatusColors,
    viewModel: SystemViewModel,
) {
    state.curator?.let { curator ->
        item {
            SectionHeader(contentPadding = SystemHeaderPadding, title = stringResource(R.string.system_sec_curator))
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        val statusText: String
                        val statusType: StatusBadgeType
                        when {
                            curator.enabled != true -> {
                                statusText = stringResource(R.string.system_status_disabled)
                                statusType = StatusBadgeType.ERROR
                            }

                            curator.paused == true -> {
                                statusText = stringResource(R.string.system_status_paused)
                                statusType = StatusBadgeType.WARNING
                            }

                            else -> {
                                statusText = stringResource(R.string.system_status_active)
                                statusType = StatusBadgeType.SUCCESS
                            }
                        }
                        StatusBadge(text = statusText, status = statusType)
                    }

                    // Interval info
                    curator.interval_hours?.let { hrs ->
                        SystemInfoRow(
                            label = stringResource(R.string.system_curator_interval, hrs),
                            value = "",
                        )
                        Spacer(modifier = Modifier.height(spacing.xs))
                    }

                    // Last run
                    curator.last_run_at?.let { lastRun ->
                        SystemInfoRow(
                            label = stringResource(R.string.system_curator_last_run, lastRun),
                            value = "",
                        )
                    } ?: SystemInfoRow(
                        label = stringResource(R.string.system_curator_never_run),
                        value = "",
                    )

                    // Actions
                    if (curator.enabled == true) {
                        Spacer(modifier = Modifier.height(spacing.sm))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            TextButton(
                                onClick = { viewModel.toggleCuratorPaused() },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                if (curator.paused == true) {
                                    ActionButtonContent(
                                        icon = Icons.Filled.PlayArrow,
                                        text = stringResource(R.string.system_curator_resume),
                                    )
                                } else {
                                    ActionButtonContent(
                                        icon = Icons.Filled.Pause,
                                        text = stringResource(R.string.system_curator_pause),
                                    )
                                }
                            }
                            TextButton(
                                onClick = { viewModel.runCuratorNow() },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                ActionButtonContent(
                                    icon = Icons.Filled.Refresh,
                                    text = stringResource(R.string.system_curator_run_now),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.gatewaySection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    statusColors: com.m57.hermescontrol.theme.HermesStatusColors,
    viewModel: SystemViewModel,
) {
    state.status?.let { status ->
        item {
            SectionHeader(contentPadding = SystemHeaderPadding, title = stringResource(R.string.system_sec_gateway))
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Status badge + version
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            val isRunning = status.gateway_running == true
                            StatusBadge(
                                text =
                                    if (isRunning) {
                                        stringResource(R.string.system_status_running)
                                    } else {
                                        stringResource(R.string.system_status_stopped)
                                    },
                                status = if (isRunning) StatusBadgeType.SUCCESS else StatusBadgeType.ERROR,
                            )
                        }
                    }

                    // Version · Sessions · PID
                    status.version?.let { ver ->
                        val parts = mutableListOf(ver)
                        status.active_sessions?.let { parts.add("$it session(s)") }
                        state.doctorReport?.let { report ->
                            if (report.ok && report.pid != null) {
                                parts.add("pid ${report.pid}")
                            }
                        }
                        SystemInfoRow(
                            label = stringResource(R.string.system_label_hermes_version),
                            value = parts.joinToString(" · "),
                        )
                    }

                    // Auth required
                    status.auth_required?.let { auth ->
                        SystemInfoRow(
                            label = stringResource(R.string.system_auth_required),
                            value = if (auth) "yes" else "no",
                        )
                    }

                    // Active platforms
                    status.gateway_platforms?.let { platforms ->
                        if (platforms.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                            platforms.forEach { (name, platform) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                                ) {
                                    Icon(
                                        Icons.Filled.Devices,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    platform?.state?.let { platformState ->
                                        StatusBadge(
                                            text = platformState,
                                            status =
                                                when (platformState.lowercase()) {
                                                    "connected", "running", "ready" -> StatusBadgeType.SUCCESS
                                                    "error", "disconnected", "failed" -> StatusBadgeType.ERROR
                                                    else -> StatusBadgeType.NEUTRAL
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Action buttons
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        val isRunning = status.gateway_running == true
                        if (!isRunning) {
                            TextButton(
                                onClick = { viewModel.startGateway() },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                ActionButtonContent(
                                    icon = Icons.Filled.PlayArrow,
                                    text = stringResource(R.string.system_gateway_start),
                                )
                            }
                        }
                        if (isRunning) {
                            TextButton(
                                onClick = { viewModel.restartGateway() },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                ActionButtonContent(
                                    icon = Icons.Filled.RestartAlt,
                                    text = stringResource(R.string.system_gateway_restart),
                                )
                            }
                            TextButton(
                                onClick = { viewModel.stopGateway() },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                ActionButtonContent(
                                    icon = Icons.Filled.Stop,
                                    text = stringResource(R.string.system_gateway_stop),
                                    iconTint = MaterialTheme.colorScheme.error,
                                    textColor = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.credentialsSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    viewModel: SystemViewModel,
    credToRemove: Pair<String, Int>?,
    onRemoveRequest: (Pair<String, Int>) -> Unit,
) {
    item {
        SectionHeader(
            contentPadding = SystemHeaderPadding,
            title = stringResource(R.string.system_sec_credentials),
            trailing = {
                val hasCreds = state.credentials.any { it.entries?.isNotEmpty() == true }
                if (hasCreds) {
                    Text(
                        text = "${state.credentials.sumOf { it.entries?.size ?: 0 }} key(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
    item {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Add credential form
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    HermesTextField(
                        value = state.credProvider,
                        onValueChange = viewModel::updateCredProvider,
                        label = { Text(stringResource(R.string.system_credentials_provider)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    HermesTextField(
                        value = state.credKey,
                        onValueChange = viewModel::updateCredKey,
                        label = { Text(stringResource(R.string.system_credentials_api_key)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    HermesTextField(
                        value = state.credLabel,
                        onValueChange = viewModel::updateCredLabel,
                        label = { Text(stringResource(R.string.system_credentials_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.sm))
                TextButton(
                    onClick = { viewModel.addCredential() },
                    enabled = !state.addingCred && state.credKey.isNotBlank(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    if (state.addingCred) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(spacing.sm))
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                    }
                    Text(stringResource(R.string.system_credentials_add_key), maxLines = 1, softWrap = false)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))

                // Provider list with entries
                if (state.credentials.isEmpty()) {
                    Text(
                        text = stringResource(R.string.system_credentials_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = spacing.sm),
                    )
                }

                state.credentials.forEach { provider ->
                    Text(
                        text = provider.provider ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = spacing.xs),
                    )
                    provider.entries?.forEach { entry ->
                        CredentialEntryRow(
                            entry = entry,
                            providerName = provider.provider ?: "",
                            onRemove = { onRemoveRequest(Pair(provider.provider ?: "", entry.index ?: 0)) },
                            spacing = spacing,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.operationsSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    viewModel: SystemViewModel,
    importConfirm: Boolean,
    onImportConfirm: (Boolean) -> Unit,
    onImportPick: () -> Unit,
    downloadBackup: () -> Unit,
) {
    item {
        SectionHeader(contentPadding = SystemHeaderPadding, title = stringResource(R.string.system_sec_operations))
    }
    item {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Maintenance operations as plain rows
                SystemOperationRow(Icons.Filled.HealthAndSafety, stringResource(R.string.system_op_doctor)) {
                    // loadAll already fetches doctor report, just call it
                    viewModel.loadAll()
                }
                SystemOperationRow(Icons.Filled.VerifiedUser, stringResource(R.string.system_op_security_audit)) {
                    viewModel.runSecurityAudit()
                }
                SystemOperationRow(Icons.Filled.Refresh, stringResource(R.string.system_op_update_skills)) {
                    viewModel.runUpdateSkills()
                }
                SystemOperationRow(Icons.Filled.HealthAndSafety, stringResource(R.string.system_op_prompt_size)) {
                    viewModel.runPromptSize()
                }
                SystemOperationRow(Icons.Filled.Build, stringResource(R.string.system_op_dump)) {
                    viewModel.runDump()
                }
                SystemOperationRow(Icons.Filled.Build, stringResource(R.string.system_op_config_migrate)) {
                    viewModel.runConfigMigrate()
                }

                Spacer(modifier = Modifier.height(spacing.lg))

                // Backup section
                Text(
                    text = stringResource(R.string.system_sec_backup),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    TextButton(
                        onClick = { viewModel.triggerBackup() },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        ActionButtonContent(
                            icon = Icons.Filled.Backup,
                            text = stringResource(R.string.system_op_backup_create),
                        )
                    }
                    TextButton(
                        onClick = downloadBackup,
                        enabled = state.backupArchive != null && !state.isDownloading,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        if (state.isDownloading) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.size(spacing.xs))
                        } else {
                            Icon(
                                Icons.Filled.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(spacing.xs))
                        }
                        Text(
                            text = stringResource(R.string.system_op_backup_download),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                // Restore from picked archive (issue #786: SAF picker → upload)
                Spacer(modifier = Modifier.height(spacing.sm))
                Text(
                    text = stringResource(R.string.system_sec_restore),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                TextButton(
                    onClick = onImportPick,
                    enabled = !state.isImporting,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(spacing.xs))
                    Text(
                        text = stringResource(R.string.system_op_restore_pick),
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                state.importFileName?.let { name ->
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(spacing.sm))

                TextButton(
                    onClick = { onImportConfirm(true) },
                    enabled = state.importFileName != null && !state.isImporting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    if (state.isImporting) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.size(spacing.xs))
                    } else {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                    }
                    Text(stringResource(R.string.system_op_restore_upload), maxLines = 1, softWrap = false)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))

                // Debug share section
                SectionHeader(contentPadding = SystemHeaderPadding, title = stringResource(R.string.system_sec_debug_share))
                Spacer(modifier = Modifier.height(spacing.sm))

                Text(
                    text = stringResource(R.string.system_debug_share_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                androidx.compose.material3.Switch(
                    checked = state.shareRedact,
                    onCheckedChange = { viewModel.toggleShareRedact() },
                )
                Spacer(modifier = Modifier.size(spacing.sm))
                Text(
                    text = stringResource(R.string.system_debug_share_redact),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = spacing.xs),
                )

                Spacer(modifier = Modifier.height(spacing.sm))

                TextButton(
                    onClick = { viewModel.runDebugShare() },
                    enabled = !state.sharing,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    if (state.sharing) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(spacing.sm))
                    } else {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                    }
                    Text(
                        if (state.sharing) {
                            stringResource(R.string.system_debug_share_uploading)
                        } else {
                            stringResource(R.string.system_debug_share_generate)
                        },
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                // Debug share results
                state.debugShare?.let { share ->
                    Spacer(modifier = Modifier.height(spacing.sm))
                    @Suppress("DEPRECATION")
                    val clipboardManager = LocalClipboardManager.current

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        StatusBadge(
                            text =
                                if (share.ok == true) {
                                    stringResource(R.string.system_debug_share_uploaded)
                                } else {
                                    stringResource(R.string.system_debug_share_failed)
                                },
                            status = if (share.ok == true) StatusBadgeType.SUCCESS else StatusBadgeType.ERROR,
                        )
                        share.redacted?.let { redacted ->
                            StatusBadge(
                                text =
                                    if (redacted) {
                                        stringResource(R.string.system_debug_share_redacted)
                                    } else {
                                        stringResource(R.string.system_debug_share_not_redacted)
                                    },
                                status = StatusBadgeType.INFO,
                            )
                        }
                        share.auto_delete_seconds?.let { secs ->
                            StatusBadge(
                                text = stringResource(R.string.system_debug_share_auto_delete, secs / 3600),
                                status = StatusBadgeType.NEUTRAL,
                            )
                        }
                    }

                    share.urls?.forEach { (name, url) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "$name: $url",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                @Suppress("DEPRECATION")
                                clipboardManager.setText(AnnotatedString(url))
                            }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.system_debug_share_copy_all),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.checkpointsSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    pruneConfirm: Boolean,
    onPruneConfirm: (Boolean) -> Unit,
) {
    state.checkpoints?.let { checkpoints ->
        item {
            SectionHeader(contentPadding = SystemHeaderPadding, title = stringResource(R.string.system_sec_checkpoints))
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val sessionsCount = checkpoints.sessions?.size ?: 0
                    val totalBytes = checkpoints.total_bytes ?: 0L
                    SystemInfoRow(
                        label = "",
                        value =
                            stringResource(
                                R.string.system_checkpoints_summary,
                                sessionsCount,
                                formatBytes(totalBytes),
                            ),
                    )

                    Spacer(modifier = Modifier.height(spacing.sm))
                    TextButton(
                        onClick = { onPruneConfirm(true) },
                        enabled = sessionsCount > 0,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_checkpoints_prune), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.shellHooksSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    viewModel: SystemViewModel,
) {
    val hooks = state.hooks?.hooks ?: emptyList()

    item {
        SectionHeader(
            contentPadding = SystemHeaderPadding,
            title = stringResource(R.string.system_sec_hooks),
            trailing = {
                TextButton(onClick = { viewModel.toggleHookModal() }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(spacing.xs))
                    Text(stringResource(R.string.system_hooks_new), maxLines = 1, softWrap = false)
                }
            },
        )
    }

    if (hooks.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.system_hooks_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            )
        }
    }

    items(hooks, key = { "${it.event}:${it.command}" }) { hook ->
        HookCard(hook = hook, spacing = spacing, onDelete = {
            viewModel.deleteHook(hook.event ?: "", hook.command ?: "")
        })
    }

    // Hook creation modal
    if (state.hookModalOpen) {
        item {
            AlertDialog(
                onDismissRequest = { viewModel.toggleHookModal() },
                title = { Text(stringResource(R.string.system_hooks_modal_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        HermesTextField(
                            value = state.hookEvent,
                            onValueChange = viewModel::updateHookEvent,
                            label = { Text(stringResource(R.string.system_hooks_field_event)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HermesTextField(
                            value = state.hookCommand,
                            onValueChange = viewModel::updateHookCommand,
                            label = { Text(stringResource(R.string.system_hooks_field_command)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HermesTextField(
                            value = state.hookMatcher,
                            onValueChange = viewModel::updateHookMatcher,
                            label = { Text(stringResource(R.string.system_hooks_field_matcher)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HermesTextField(
                            value = state.hookTimeout,
                            onValueChange = viewModel::updateHookTimeout,
                            label = { Text(stringResource(R.string.system_hooks_field_timeout)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Switch(
                                checked = state.hookApprove,
                                onCheckedChange = viewModel::updateHookApprove,
                            )
                            Spacer(modifier = Modifier.width(spacing.sm))
                            Text(
                                text = stringResource(R.string.system_hooks_field_approve),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(R.string.system_hooks_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.createHook() },
                        enabled = !state.creatingHook && state.hookCommand.isNotBlank(),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        if (state.creatingHook) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.size(spacing.sm))
                        }
                        Text(stringResource(R.string.system_hooks_create))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.toggleHookModal() }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.system_confirm_cancel))
                    }
                },
            )
        }
    }
}

private fun LazyListScope.actionLogSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    viewModel: SystemViewModel,
) {
    item {
        SectionHeader(
            contentPadding = SystemHeaderPadding,
            title = stringResource(R.string.system_action_log_title),
            trailing = {
                TextButton(onClick = { viewModel.closeActionLog() }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(spacing.xs))
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    item {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Action title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = state.activeAction ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    val actionLog = state.actionLog
                    if (actionLog != null) {
                        if (actionLog.running == true) {
                            StatusBadge(
                                text = stringResource(R.string.system_action_log_running),
                                status = StatusBadgeType.INFO,
                            )
                        } else {
                            StatusBadge(
                                text = stringResource(R.string.system_action_log_done),
                                status = StatusBadgeType.SUCCESS,
                            )
                            actionLog.exit_code?.let { code ->
                                Spacer(modifier = Modifier.size(spacing.xs))
                                StatusBadge(
                                    text = stringResource(R.string.system_action_log_exit, code),
                                    status =
                                        if (code == 0) StatusBadgeType.SUCCESS else StatusBadgeType.ERROR,
                                )
                            }
                        }
                    } else {
                        StatusBadge(
                            text = stringResource(R.string.system_action_log_starting),
                            status = StatusBadgeType.NEUTRAL,
                        )
                    }
                }

                // Output lines
                state.actionLog?.lines?.let { lines ->
                    if (lines.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                        ) {
                            Column(modifier = Modifier.padding(spacing.sm)) {
                                lines.take(20).forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (lines.size > 20) {
                                    Text(
                                        text = stringResource(R.string.system_more_lines, lines.size - 20),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val SystemHeaderPadding =
    androidx.compose.foundation.layout
        .PaddingValues(top = 16.dp, bottom = 4.dp)

/** Label/value row aligned to the list edge (the list already pads the sides). */
@Composable
private fun SystemInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
        )
    }
}

/** One maintenance operation as a tappable row with a hairline below. */
@Composable
private fun SystemOperationRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onClick)
                    .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ActionButtonContent(
    icon: ImageVector?,
    text: String,
    iconTint: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current,
    textColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    loading: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = iconTint)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Staged SAF-picked backup archive for the restore flow (issue #786). */
private data class PendingImport(
    val uri: Uri,
    val name: String,
    val mime: String,
)
