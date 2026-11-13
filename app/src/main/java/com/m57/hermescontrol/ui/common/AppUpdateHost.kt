package com.m57.hermescontrol.ui.common

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.m57.hermescontrol.data.update.AppUpdateCache
import com.m57.hermescontrol.ui.settings.AppUpdateViewModel
import kotlinx.coroutines.awaitCancellation

/** One shell host for the existing update dialog, including Home and permission return. */
@Composable
internal fun AppUpdateHost(viewModel: AppUpdateViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.onForeground()
            awaitCancellation()
        }
    }
    if (AppUpdateCache.isDialogVisible) {
        val context = LocalContext.current
        val state by viewModel.state.collectAsStateWithLifecycle()
        val sourceInfo by viewModel.sourceInfo.collectAsStateWithLifecycle()
        AppUpdateDialog(
            state = state,
            sourceLabel = updateSourceLabel(sourceInfo.source, sourceInfo.serverLabel),
            onDismiss = AppUpdateCache::hideDialog,
            onStartUpdate = viewModel::startUpdate,
            onRetry = viewModel::retryUpdate,
            onCheckUpdate = viewModel::checkForUpdate,
            onCancelDownload = viewModel::cancelDownload,
            onNeverAskAgain = viewModel::dismissCurrentUpdate,
            onOpenSettings = {
                val intent =
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}
