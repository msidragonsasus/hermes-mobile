package com.m57.hermescontrol.ui.chat.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.SecretPromptUi
import com.m57.hermescontrol.ui.chat.SudoPromptUi

@Composable
fun ChatLifecycleEffects(
    sessionId: String?,
    connectionStatus: ConnectionStatus,
    errorMessage: String?,
    backgroundCompleteMessage: String?,
    openError: String?,
    sudoPrompt: SudoPromptUi?,
    secretPrompt: SecretPromptUi?,
    onOpenLatest: (String?) -> Unit,
    snackbarHostState: SnackbarHostState,
    viewModel: ChatViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Switch to session from history/explicit target (profile-scoped), or scroll to latest on notification return
    val pendingNavigation = NavigationController.pendingChatNavigation
    val pendingMainRequest = NavigationController.pendingMainConversationRequestId
    LaunchedEffect(sessionId, pendingNavigation, pendingMainRequest, connectionStatus) {
        if (connectionStatus != ConnectionStatus.CONNECTED) return@LaunchedEffect
        val activeProfile = AuthManager.activeProfileId.value
        val request = NavigationController.consumePendingChatNavigation(activeProfile)
        val target = request?.sessionId?.takeIf { it.isNotBlank() } ?: sessionId
        if (!target.isNullOrBlank()) {
            viewModel.switchSession(target)
        } else if (NavigationController.consumePendingMainConversationRequest()) {
            viewModel.showMainConversation()
        }
        if (request?.scrollToBottom == true) {
            onOpenLatest(target)
        }
    }

    // Refresh chat state when the app returns to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        viewModel.refreshSettings()
                        viewModel.refreshCurrentSession()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Request POST_NOTIFICATIONS permission on Android 13+
    val requestNotificationPermission =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { /* granted */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(permission)
            }
        }
    }

    // Show error as snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Show background-complete as a non-blocking snackbar (issue #527)
    LaunchedEffect(backgroundCompleteMessage) {
        backgroundCompleteMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearBackgroundComplete()
        }
    }

    // Show attachment-open failures as a non-blocking snackbar (issue #724)
    LaunchedEffect(openError) {
        openError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearOpenError()
        }
    }

    // Sudo / secret prompt dialogs (issue #524)
    sudoPrompt?.let { prompt ->
        SudoPromptDialog(
            onConfirm = viewModel::respondToSudo,
            onDismiss = viewModel::dismissSudo,
        )
    }

    secretPrompt?.let { prompt ->
        SecretPromptDialog(
            onConfirm = viewModel::respondToSecret,
            onDismiss = viewModel::dismissSecret,
            envVar = prompt.envVar,
            prompt = prompt.prompt,
        )
    }
}
