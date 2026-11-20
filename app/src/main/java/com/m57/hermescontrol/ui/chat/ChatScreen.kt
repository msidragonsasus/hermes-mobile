package com.m57.hermescontrol.ui.chat

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.agents.BotConversations
import com.m57.hermescontrol.agents.BotSettingsKey
import com.m57.hermescontrol.agents.SpecialistSetupSheet
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.MainModelAssignment
import com.m57.hermescontrol.data.update.AppUpdateCache
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.UpdateNoticeManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.components.ChatConnectionBanner
import com.m57.hermescontrol.ui.chat.components.ChatHeaderStatusSheet
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import com.m57.hermescontrol.ui.chat.components.ChatLifecycleEffects
import com.m57.hermescontrol.ui.chat.components.ChatLoadingOverlay
import com.m57.hermescontrol.ui.chat.components.ChatResumeErrorOverlay
import com.m57.hermescontrol.ui.chat.components.ChatScrollToBottomFab
import com.m57.hermescontrol.ui.chat.components.ContextDetailSheet
import com.m57.hermescontrol.ui.chat.components.ConversationLayout
import com.m57.hermescontrol.ui.chat.components.MainChatHeader
import com.m57.hermescontrol.ui.chat.components.ReactionHeartsOverlay
import com.m57.hermescontrol.ui.chat.components.ReloginDialog
import com.m57.hermescontrol.ui.chat.components.SearchBarRow
import com.m57.hermescontrol.ui.chat.components.SideQuestionSheet
import com.m57.hermescontrol.ui.chat.components.SubagentInspectionSheet
import com.m57.hermescontrol.ui.chat.components.TaskProgressChip
import com.m57.hermescontrol.ui.chat.components.quotedReplyField
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import com.m57.hermescontrol.ui.chat.components.shouldShowProgressChip
import com.m57.hermescontrol.ui.chat.fullbleed.MainChatTimeline
import com.m57.hermescontrol.ui.chat.fullbleed.mainChatScrollKey
import com.m57.hermescontrol.ui.chat.fullbleed.rememberMainChatScrollPosition
import com.m57.hermescontrol.ui.common.ActionProgressDialog
import com.m57.hermescontrol.ui.common.AppUpdateDialog
import com.m57.hermescontrol.ui.common.CredentialWarningBanner
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.SettingsMenuItem
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog
import com.m57.hermescontrol.ui.settings.AppUpdateViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SESSION_SYNC_INTERVAL_MS = 30_000L

internal fun acceptedSaveDestination(
    resultCode: Int,
    destination: Uri?,
): Uri? = destination.takeIf { resultCode == Activity.RESULT_OK }

internal fun canStartAttachmentSave(
    pendingSavePath: String?,
    savingAttachmentPath: String?,
): Boolean = pendingSavePath == null && savingAttachmentPath == null

/**
 * Chat screen — the primary conversation surface of Hermes Control.
 *
 * This file is a thin compositor that delegates all UI rendering to focused
 * composable components under `ui/chat/components/`. See issue #621 for the
 * rationale behind the split and the full file→content mapping.
 *
 * The original 2,267-line god file was split into 11 single-purpose files;
 * this entry point handles only state hoisting, scaffold wiring, and the
 * remembered launchers that need to be activity-scoped.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    sessionId: String? = null,
    viewModel: ChatViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val streamingState = viewModel.streamingState.collectAsStateWithLifecycle()
    val credentialWarning by HermesWsClient.credentialWarning.collectAsStateWithLifecycle()
    // Snapshot-backed search state — read directly so only the scopes that
    // read its fields recompose on search changes (bar, matched bubbles).
    val searchState = viewModel.searchState
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeProfileId by AuthManager.activeProfileId.collectAsStateWithLifecycle()
    val chatScope = AuthManager.uiPreferenceScope()
    val scrollKey = remember(chatScope, state.currentSessionId) { mainChatScrollKey(chatScope, state.currentSessionId) }
    val scrollPosition = rememberMainChatScrollPosition(scrollKey)
    val listState = scrollPosition.listState
    com.m57.hermescontrol.home
        .HomeChatReadEffect(state, listState)
    val scrollScope = rememberCoroutineScope()
    val scrollController =
        key(scrollKey) {
            rememberChatScrollController(listState, rememberCoroutineScope(), reverseLayout = true)
        }
    var showContextSheet by remember { mutableStateOf(false) }
    var pendingSavePath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSaveName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSaveMimeType by rememberSaveable { mutableStateOf<String?>(null) }
    val saveAttachmentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                ExternalActivityLifecycleGuard.externalActivityReturned()
                val path = pendingSavePath
                val destination = acceptedSaveDestination(result.resultCode, result.data?.data)
                if (destination != null && path != null) {
                    viewModel.saveAttachment(
                        Attachment(
                            uri = "gateway:$path",
                            name = pendingSaveName ?: "download",
                            mimeType = pendingSaveMimeType ?: "application/octet-stream",
                            size = 0,
                            source = AttachmentSource.GATEWAY,
                        ),
                        destination,
                    )
                }
            } finally {
                pendingSavePath = null
                pendingSaveName = null
                pendingSaveMimeType = null
            }
        }

    LaunchedEffect(scrollController) { scrollController.observeUserScrollPosition() }

    var inputFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    LaunchedEffect(state.pendingPrefillText) {
        val prefill = state.pendingPrefillText
        if (prefill != null) {
            inputFieldValue = ChatInputPolicy.commandFieldValue(prefill)
            viewModel.consumePendingPrefill()
        }
    }
    val navigationPrefill = NavigationController.pendingChatPrefill
    LaunchedEffect(navigationPrefill) {
        NavigationController.consumePendingChatPrefill()?.let { prefill ->
            inputFieldValue = ChatInputPolicy.commandFieldValue(prefill)
        }
    }
    LaunchedEffect(state.composerTextToRestore) {
        state.composerTextToRestore?.let { text ->
            inputFieldValue = ChatInputPolicy.restoreRejectedText(text, inputFieldValue)
            viewModel.consumeComposerTextRestore()
        }
    }
    var isListening by rememberSaveable { mutableStateOf(false) }
    var lastAnimatedMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var showReloginDialog by rememberSaveable { mutableStateOf(false) }
    var showSubagentInspectionSheet by rememberSaveable { mutableStateOf(false) }
    var viewingImage by rememberSaveable { mutableStateOf<ImageViewerModel?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val launchExternalActivity: (() -> Unit) -> Unit = { launch ->
        ExternalActivityLifecycleGuard.launchExternalActivity(
            acquireConnectionLease = HermesWsClient::acquireExternalActivityConnectionLease,
            releaseConnectionLease = HermesWsClient::releaseExternalActivityConnectionLease,
            prepareForBackground = { NotificationHelper.start(context) },
            cleanupAfterLaunchFailure = { NotificationHelper.stop(context) },
            launch = launch,
        )
    }

    val micListeningPrompt = stringResource(R.string.chat_mic_listening)
    val sttNotAvailableMsg = stringResource(R.string.stt_not_available)
    val sttPermissionDeniedMsg = stringResource(R.string.stt_permission_denied)

    // Speech-to-text recognition launcher (issue #194)
    val speechLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            isListening = false
            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText =
                    result.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        .orEmpty()
                if (spokenText.isNotBlank()) {
                    val merged =
                        if (inputFieldValue.text.isBlank()) {
                            spokenText
                        } else {
                            "${inputFieldValue.text} $spokenText"
                        }
                    inputFieldValue = ChatInputPolicy.commandFieldValue(merged)
                }
            }
        }

    // Mic permission launcher
    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            if (granted) {
                if (SpeechInputHelper.isSpeechInputAvailable(context)) {
                    val intent = SpeechInputHelper.createSpeechIntent(micListeningPrompt)
                    isListening = true
                    launchExternalActivity {
                        try {
                            speechLauncher.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            isListening = false
                            scrollScope.launch {
                                snackbarHostState.showSnackbar(sttNotAvailableMsg)
                            }
                        }
                    }
                } else {
                    scrollScope.launch {
                        snackbarHostState.showSnackbar(sttNotAvailableMsg)
                    }
                }
            } else {
                scrollScope.launch {
                    snackbarHostState.showSnackbar(sttPermissionDeniedMsg)
                }
            }
        }

    // Multi-file picker for attachments (issue #195).
    val filePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetMultipleContents(),
        ) { uris: List<Uri> ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            val attachments =
                uris.mapNotNull { uri ->
                    runCatching {
                        var name = uri.lastPathSegment ?: "file"
                        var size = 0L
                        context.contentResolver
                            .query(
                                uri,
                                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                                null,
                                null,
                                null,
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                                }
                            }
                        Attachment(
                            uri = uri.toString(),
                            name = name,
                            mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                            size = size,
                        )
                    }.onFailure { error ->
                        Log.w("ChatScreen", "Skipping unreadable picked attachment", error)
                    }.getOrNull()
                }
            viewModel.addAttachments(attachments)
        }

    // Camera photo launcher (issue #195)
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraErrorMsg = stringResource(R.string.chat_camera_error)
    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (success && uri != null) {
                try {
                    val fileName =
                        "photo_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(
                                Date(),
                            )
                        }.jpg"
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val size = inputStream?.use { it.available().toLong() } ?: 0L
                    viewModel.addAttachment(uri.toString(), fileName, "image/jpeg", size)
                } catch (e: Exception) {
                    Log.e("ChatScreen", "Camera capture failed", e)
                    scrollScope.launch {
                        snackbarHostState.showSnackbar(
                            cameraErrorMsg,
                        )
                    }
                }
            }
        }

    // Lifecycle effects, permissions, session switching, auto-scroll, errors
    ChatLifecycleEffects(
        sessionId = sessionId,
        connectionStatus = state.connectionStatus,
        errorMessage = state.errorMessage,
        backgroundCompleteMessage = state.backgroundCompleteMessage,
        openError = state.openError,
        sudoPrompt = state.sudoPrompt,
        secretPrompt = state.secretPrompt,
        vaultPrompt = state.vaultPrompt,
        onOpenLatest = { target ->
            val targetKey = target?.let { mainChatScrollKey(chatScope, it) } ?: scrollKey
            scrollPosition.forgetAnchor(targetKey)
            if (targetKey == scrollKey) scrollController.jumpToBottom()
        },
        snackbarHostState = snackbarHostState,
        viewModel = viewModel,
    )

    val botsViewModel: com.m57.hermescontrol.ui.bots.BotsViewModel =
        viewModel {
            com.m57.hermescontrol.ui.bots
                .BotsViewModel()
        }
    val botsState by botsViewModel.uiState.collectAsStateWithLifecycle()
    val activeProfileName =
        activeProfileId?.takeIf { it.isNotBlank() }
            ?: botsState.activeProfileName?.takeIf { it.isNotBlank() }
            ?: "default"
    val rosterScope = BotConversations.serverScope()
    LaunchedEffect(activeProfileName, rosterScope) {
        botsViewModel.loadBots(isRefresh = true)
    }
    val isMainProfile = activeProfileName == "default"
    val shouldSync = !isMainProfile || !state.isAgentTyping || state.responseStatusUnknown
    LaunchedEffect(lifecycleOwner, state.currentSessionId, state.connectionStatus, isMainProfile, shouldSync) {
        if (state.currentSessionId == null || state.connectionStatus != ConnectionStatus.CONNECTED || !shouldSync) {
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!isMainProfile) {
                viewModel.syncCurrentSession()
                viewModel.fetchContextUsage()
            }
            while (true) {
                delay(if (isMainProfile) 2 * SESSION_SYNC_INTERVAL_MS else SESSION_SYNC_INTERVAL_MS)
                viewModel.syncCurrentSession(includeSubagents = !isMainProfile)
                if (!isMainProfile) viewModel.fetchContextUsage()
            }
        }
    }

    val activeAgent =
        botsState.profiles.firstOrNull {
            botsState.rosterScope == rosterScope &&
                it.name == activeProfileName
        }
    val activeTitle =
        BotConversations
            .label(activeProfileName, activeAgent)
            ?.takeUnless { it == activeProfileName }
            ?: stringResource(R.string.chat_header_specialist)
    var setupSheetProfile by rememberSaveable { mutableStateOf<String?>(null) }
    val epoch by AuthManager.connectionEpoch.collectAsStateWithLifecycle()
    val verifiedBotChat = state.canonicalBotChatId.takeIf { state.canonicalBotChatEpoch == epoch }
    val setupController = viewModel.specialistChat
    val setupState by setupController.state.collectAsStateWithLifecycle()
    val setupTarget =
        if (epoch % 2 == 0L && activeProfileId == activeProfileName && activeProfileName != "default") {
            SpecialistChatTarget(AuthManager.uiPreferenceScope(), epoch, activeProfileName)
        } else {
            null
        }
    val inSpecialistBotChat =
        setupTarget != null && (state.currentSessionId == null || state.currentSessionId == verifiedBotChat)
    val profileSetup = setupState.takeIf { setupTarget != null && it.target == setupTarget }
    val specialist = profileSetup.takeIf { inSpecialistBotChat }
    val specialistKnown = activeAgent?.setup_completed != null || activeAgent?.botMeta()?.setup_completed != null
    val setupUnsupported = profileSetup?.legacy == true
    val specialDrafts = remember { mutableStateMapOf<String, TextFieldValue>() }
    val setupKey = setupTarget?.scope.orEmpty()
    val setupComposer =
        inSpecialistBotChat && !setupUnsupported &&
            (specialist?.blocksNormalInput != false || (specialistKnown && specialist.missing))
    val readyForSetup = state.isConnected && !state.isLoading && specialist?.sessionId != null
    val canChangeSetup = readyForSetup && !state.isAgentTyping && !state.responseStatusUnknown
    val visibleMessages =
        remember(state.messages, specialist, specialistKnown) {
            specialist?.takeIf { !it.legacy && (!it.missing || specialistKnown) }?.projectMessages(state.messages)
                ?: state.messages
        }
    LaunchedEffect(
        setupTarget,
        state.currentSessionId,
        verifiedBotChat,
        state.canonicalBotChatPending,
        state.isConnected,
    ) {
        viewModel.bindSpecialistChat(
            setupTarget,
            verifiedBotChat.takeIf { it == state.currentSessionId && !state.canonicalBotChatPending },
        )
    }
    LaunchedEffect(
        activeProfileName,
        epoch,
        state.currentSessionId,
        state.isConnected,
        profileSetup?.capabilities,
        setupUnsupported,
    ) {
        viewModel.verifySpecialistCanonical()
    }
    val hasSetupFooter =
        specialist != null && !specialist.legacy &&
            (!specialist.missing || specialistKnown) && specialist.showsFooter
    val showScrollToBottom by remember(scrollController, listState) {
        derivedStateOf { scrollController.showFab(listState.layoutInfo.visibleItemsInfo.isNotEmpty()) }
    }
    DisposableEffect(viewModel, botsViewModel) {
        viewModel.refreshSpecialistRoster = { botsViewModel.loadBots(isRefresh = true) }
        onDispose { viewModel.refreshSpecialistRoster = {} }
    }
    val pendingSetupBot = NavigationController.pendingSpecialistSetupBot
    LaunchedEffect(activeProfileName, pendingSetupBot, profileSetup?.capabilities, setupUnsupported) {
        if (activeProfileName != "default" && (setupUnsupported || profileSetup?.capabilities != null) &&
            NavigationController.consumePendingSpecialistSetup(activeProfileName) && setupUnsupported
        ) {
            setupSheetProfile = activeProfileName
        }
    }

    val chatAvatar = activeAgent?.botMeta()?.avatar ?: BotAvatarMeta(icon = "robot")
    var showHeaderStatusSheet by remember(activeProfileName, state.currentSessionId) { mutableStateOf(false) }

    HermesScaffold(
        showTopBar = false,
        modifier = modifier,
        title = {},
        drawerGesturesEnabled = true,
        showShellMenu = false,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val statusColors = LocalHermesStatusColors.current
                val isDownloadComplete = data.visuals.message.startsWith("Saved ")
                Snackbar(
                    snackbarData = data,
                    containerColor =
                        if (isDownloadComplete) statusColors.success else MaterialTheme.colorScheme.errorContainer,
                    contentColor =
                        if (isDownloadComplete) statusColors.onSuccess else MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) { _ ->
        ConversationLayout(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding(),
            onReply =
                if (state.isConnected) {
                    { message -> inputFieldValue = quotedReplyField(message, inputFieldValue) }
                } else {
                    null
                },
            composer = {
                ChatInputBar(
                    inputFieldValue =
                        if (setupComposer) {
                            specialDrafts[setupKey] ?: TextFieldValue()
                        } else {
                            inputFieldValue
                        },
                    onInputChange = {
                        if (setupComposer) specialDrafts[setupKey] = it else inputFieldValue = it
                    },
                    onSend = {
                        if (setupComposer) {
                            if (readyForSetup && setupController.submit(specialDrafts[setupKey]?.text.orEmpty())) {
                                specialDrafts.remove(setupKey)
                                scrollController.jumpToBottom(animated = true)
                            }
                        } else if (!viewModel.blocksSpecialistRegularPrompt(showFeedback = true)) {
                            viewModel.sendMessage(inputFieldValue.text)
                            inputFieldValue = TextFieldValue("")
                            scrollController.jumpToBottom(animated = true)
                        }
                    },
                    onMicTap = {
                        if (isListening) {
                            isListening = false
                        } else if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            if (SpeechInputHelper.isSpeechInputAvailable(context)) {
                                val intent = SpeechInputHelper.createSpeechIntent(micListeningPrompt)
                                isListening = true
                                launchExternalActivity {
                                    try {
                                        speechLauncher.launch(intent)
                                    } catch (_: ActivityNotFoundException) {
                                        isListening = false
                                        scrollScope.launch {
                                            snackbarHostState.showSnackbar(sttNotAvailableMsg)
                                        }
                                    }
                                }
                            } else {
                                scrollScope.launch {
                                    snackbarHostState.showSnackbar(sttNotAvailableMsg)
                                }
                            }
                        } else {
                            launchExternalActivity {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    queueOnSend =
                        !setupComposer &&
                            (
                                (!isMainProfile && state.isAgentTyping) || state.responseStatusUnknown ||
                                    !state.isConnected
                            ),
                    allowOfflineQueue = !setupComposer,
                    attachmentsEnabled = !setupComposer,
                    microphoneEnabled = !setupComposer,
                    showAttachmentButton = !setupComposer,
                    showModelControls = !setupComposer,
                    showCommandSuggestions = !setupComposer,
                    showSendWhenEmpty = setupComposer,
                    inputEnabled = if (setupComposer) specialist?.turnMode != null else state.isConnected,
                    sendEnabled =
                        !setupComposer || (readyForSetup && specialist?.canAct == true && specialist.turnMode != null),
                    inputPlaceholder =
                        if (setupComposer && specialist?.turnMode == "edit") {
                            stringResource(R.string.specialist_chat_edit_prompt)
                        } else {
                            null
                        },
                    onSteer =
                        if (!setupComposer && state.isConnected && state.isAgentTyping &&
                            !state.responseStatusUnknown && state.pendingAttachments.isEmpty()
                        ) {
                            {
                                if (!viewModel.blocksSpecialistRegularPrompt(showFeedback = true)) {
                                    viewModel.sendMessage(inputFieldValue.text, steer = true)
                                    inputFieldValue = TextFieldValue("")
                                    scrollController.jumpToBottom(animated = true)
                                }
                            }
                        } else {
                            null
                        },
                    isListening = isListening,
                    isAgentTyping = state.isAgentTyping,
                    responseStatusUnknown = state.responseStatusUnknown,
                    isConnected = state.isConnected,
                    commandCatalog = state.commandCatalog,
                    slashUsageCounts = state.slashUsageCounts,
                    pendingAttachments = if (setupComposer) emptyList() else state.pendingAttachments,
                    onCameraTap = {
                        try {
                            val timeStamp =
                                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val photoFile =
                                File.createTempFile("camera_${timeStamp}_", ".jpg", context.cacheDir)
                            val uri =
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    photoFile,
                                )
                            pendingCameraUri = uri
                            launchExternalActivity {
                                cameraLauncher.launch(uri)
                            }
                        } catch (e: Exception) {
                            Log.e("ChatScreen", "Camera launch failed", e)
                        }
                    },
                    onImageTap = {
                        launchExternalActivity {
                            filePickerLauncher.launch("image/*")
                        }
                    },
                    onFileTap = {
                        launchExternalActivity {
                            filePickerLauncher.launch("*/*")
                        }
                    },
                    onRemoveAttachment = viewModel::removeAttachment,
                    onPreviewAttachment = { attachment ->
                        if (attachment.isImage) {
                            viewingImage =
                                ImageViewerModel(
                                    model = attachment.uri,
                                    name = attachment.name,
                                    mimeType = attachment.mimeType,
                                )
                        }
                    },
                    // Composer toolbar wiring (PR 1)
                    currentSessionModel = state.currentSessionModel,
                    reasoningLevel = state.reasoningLevel,
                    onModelTap = { viewModel.openModelPicker() },
                    onReasoningTap = { level -> viewModel.setReasoningLevel(level) },
                    canDisableReasoning = state.currentModelCapabilities?.can_disable_reasoning,
                    supportsReasoning = state.currentModelCapabilities?.reasoning,
                )
            },
        ) {
            ChatConnectionBanner(
                connectionStatus = state.connectionStatus,
                onReconnect = viewModel::reconnect,
                onReloginClick = { showReloginDialog = true },
            )

            credentialWarning?.let { warning ->
                CredentialWarningBanner(
                    warning = warning,
                    onFix = { NavigationController.navigateTo(com.m57.hermescontrol.ProvidersScreen) },
                    onDismiss = { HermesWsClient.clearCredentialWarning() },
                )
            }

            // Issue #890: launch update check — non-blocking banner when a
            // newer release exists. Tapping "Update" opens the in-place dialog.
            val updateNotice by AppUpdateCache.state.collectAsStateWithLifecycle()
            if (UpdateNoticeManager.enabled && !AppUpdateCache.dismissed) {
                val noticeTag =
                    (updateNotice as? AppUpdateState.UpdateAvailable)?.latestTag
                        ?: UpdateNoticeManager.noticeTag()
                if (noticeTag != null) {
                    com.m57.hermescontrol.ui.common.UpdateNoticeBanner(
                        latestTag = noticeTag,
                        onUpdate = { AppUpdateCache.showDialog() },
                        onDismiss = { AppUpdateCache.dismiss() },
                    )
                }
            }

            val recoverableDrafts =
                remember(isMainProfile, state.outgoingDrafts, state.activeDraftIds) {
                    if (isMainProfile) {
                        state.outgoingDrafts.filterNot {
                            it.id in state.activeDraftIds
                        }
                    } else {
                        state.outgoingDrafts
                    }
                }
            com.m57.hermescontrol.ui.chat.components.OutgoingDraftPanel(
                drafts = recoverableDrafts,
                activeIds = state.activeDraftIds,
                storageError = state.draftStorageError,
                onRestore = viewModel::restoreDraft,
                onOpenConversation = viewModel::openDraftConversation,
                onDiscard = viewModel::discardDraft,
                onQueue = viewModel::queueDraft,
            )

            if (setupUnsupported && activeProfileName != "default" && activeAgent?.isSetupIncomplete == true) {
                InlineNotice(
                    text = stringResource(R.string.specialist_setup_incomplete_banner),
                    tone = NoticeTone.Warning,
                    actionLabel = stringResource(R.string.specialist_setup_open_action),
                    onAction = { setupSheetProfile = activeProfileName },
                    modifier =
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .testTag("chat_setup_incomplete_notice"),
                )
            }

            AnimatedVisibility(
                visible = searchState.isActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                    border =
                        BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                        ),
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        SearchBarRow(
                            searchQuery = searchState.query,
                            onQueryChange = { viewModel.setSearchQuery(it, mainChatOnly = true) },
                            searchMatchCount = searchState.matchTotal,
                            searchMatchCapped = searchState.matchCapped,
                            currentMatchIndex = searchState.currentIndex,
                            onNavigateUp = { viewModel.navigateSearchMatch(-1) },
                            onNavigateDown = { viewModel.navigateSearchMatch(1) },
                            onClose = { viewModel.clearSearch() },
                        )
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                val onSaveAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit = { attachment ->
                    if (canStartAttachmentSave(pendingSavePath, state.savingAttachmentPath)) {
                        pendingSavePath = viewModel.gatewayPathFor(attachment)
                        pendingSaveName = attachment.name
                        pendingSaveMimeType = attachment.mimeType
                        launchExternalActivity {
                            saveAttachmentLauncher.launch(
                                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type =
                                        attachment.mimeType
                                            .substringBefore(';')
                                            .trim()
                                            .takeIf { it.isNotBlank() } ?: "application/octet-stream"
                                    putExtra(
                                        Intent.EXTRA_TITLE,
                                        attachment.name
                                            .substringAfterLast('/')
                                            .substringAfterLast('\\')
                                            .ifBlank { "download" },
                                    )
                                },
                            )
                        }
                    }
                }

                // Full-bleed chat renderer (issue #866) — the single chat
                // surface since the bubble renderer was removed.
                val nativeUiScope = nativeUiKey(AuthManager.uiPreferenceScope(), state.currentSessionId.orEmpty())
                val nativeCanSubmit = !setupComposer && canSendNativeAction(state)
                // Rebuilt only when the scope or send availability changes, not on every chat update.
                val nativeUiHost =
                    remember(nativeUiScope, nativeCanSubmit, scrollController) {
                        NativeUiHost(nativeUiScope, nativeCanSubmit) { text ->
                            // The composer draft and attachments stay as they are.
                            if (viewModel.sendNativeAction(text)) scrollController.jumpToBottom(animated = true)
                        }
                    }
                var headerHeightPx by remember { mutableStateOf(0) }
                val headerHeight = with(LocalDensity.current) { headerHeightPx.toDp() }
                // Turn headers name the agent you are talking to, with its bot avatar.
                androidx.compose.runtime.CompositionLocalProvider(LocalNativeUiHost provides nativeUiHost) {
                    MainChatTimeline(
                        state = state,
                        messages = visibleMessages,
                        streaming = streamingState,
                        headerHeight = headerHeight,
                        scrollController = scrollController,
                        scrollPosition = scrollPosition,
                        search = searchState,
                        viewModel = viewModel,
                        onSaveAttachment = onSaveAttachment,
                        savingAttachmentPath = pendingSavePath ?: state.savingAttachmentPath,
                        onImageClick = { viewingImage = it },
                        specialistReview =
                            if (specialist != null) {
                                { SpecialistReviewCard(specialist, canChangeSetup, setupController::confirm) }
                            } else {
                                null
                            },
                        specialistFooter =
                            if (hasSetupFooter) {
                                {
                                    if (specialist.activeDraft?.kind == "edit") {
                                        SpecialistReviewCard(
                                            specialist,
                                            canChangeSetup,
                                            setupController::confirm,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        )
                                    }
                                    SpecialistChatActions(
                                        state = specialist,
                                        connected = state.isConnected,
                                        canUseSession = canChangeSetup,
                                        onRetry = setupController::retry,
                                        onResolveSession = viewModel::showMainConversation,
                                        onApply = setupController::apply,
                                        onReturnToChat = { setupController.setEditing(false) },
                                        onRecoverText = { text ->
                                            val field = specialDrafts[setupKey] ?: TextFieldValue()
                                            specialDrafts[setupKey] = ChatInputPolicy.restoreRejectedText(text, field)
                                            setupController.recoverText()
                                        },
                                    )
                                }
                            } else {
                                null
                            },
                        specialistCardsState = specialist?.takeIf { hasSetupFooter || it.activeDraft?.kind == "setup" },
                    )
                }

                // Loading overlay: only over an empty transcript. Cached history stays readable
                // while a resume is confirmed (desktop parity: warm cache paints immediately).
                ChatLoadingOverlay(
                    isLoading =
                        state.isLoading && state.resumeError == null && visibleMessages.isEmpty() && !hasSetupFooter,
                )

                // Resume-exhausted overlay — explicit error + Retry instead
                // of an infinite spinner (desktop parity).
                ChatResumeErrorOverlay(
                    errorMessage = state.resumeError,
                    onRetry = viewModel::retryResumeSession,
                )

                // Scroll-to-bottom FAB (issue #682): shows while follow is
                // paused and renders the unseen-message badge.
                ChatScrollToBottomFab(
                    show = showScrollToBottom,
                    pendingCount = scrollController.pendingCount,
                    onScrollToBottom = scrollController::resumeFollowing,
                )

                MainChatHeader(
                    name = activeTitle,
                    avatar = chatAvatar,
                    connection = state.connectionStatus,
                    working = state.isAgentTyping,
                    responseStatusUnknown = state.responseStatusUnknown,
                    onOpenDrawer = onOpenDrawer,
                    onOpenStatus = {
                        showHeaderStatusSheet = true
                        if (state.isConnected) viewModel.fetchContextUsage()
                    },
                    modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeightPx = it.height },
                )

                // Reaction heartsanimation (purely cosmetic — fades out
                // automatically after the ViewModel clears the state)
                key(state.reactionTriggerId) {
                    if (!isMainProfile) {
                        ReactionHeartsOverlay(
                            reactionKind = state.reactionKind,
                        )
                    }
                }
            }
        }

        if (showReloginDialog) {
            ReloginDialog(
                onDismiss = { showReloginDialog = false },
                onRelogin = { username, password, onResult ->
                    viewModel.relogin(username, password, onResult)
                },
            )
        }

        // /update from chat (issue #862): confirm, then the shared progress
        // popup tracks the background update (live log tail + final state).
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
        ActionProgressDialog(
            controller = viewModel.actionProgress,
            title = stringResource(R.string.system_update_progress_title),
        )

        // In-session model picker (issue #589) — opens on "/model".
        if (state.showModelPicker) {
            ModelPickerDialog(
                providers = state.modelPickerProviders,
                title = stringResource(R.string.chat_switch_model_title),
                subtitle = stringResource(R.string.chat_switch_model_scope),
                isLoading = state.modelPickerLoading,
                errorMessage = state.modelPickerError,
                onRefresh = viewModel::refreshModelOptions,
                pinnedModels = state.modelPickerPinned,
                currentModel =
                    state.currentSessionModel?.let { label ->
                        val parts = label.split('/', limit = 2)
                        if (parts.size == 2) MainModelAssignment(parts[0], parts[1]) else null
                    },
                onPinToggle = { provider, model -> viewModel.togglePinModel(provider, model) },
                onSelect = { provider, model ->
                    viewModel.sendSlashModel(provider, model)
                },
                onDismiss = { viewModel.closeModelPicker() },
            )
        }

        // Expensive / Data-policy model confirmation dialog (issue #589 follow-up)
        if (state.modelSwitchConfirmMessage != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissModelSwitchConfirm() },
                title = { Text(stringResource(R.string.model_expensive_title)) },
                text = { Text(state.modelSwitchConfirmMessage!!) },
                confirmButton = {
                    Button(onClick = { viewModel.confirmModelSwitchExpensive() }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.model_confirm_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissModelSwitchConfirm() }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }

        if (showContextSheet && state.contextBreakdown != null) {
            ContextDetailSheet(
                breakdown = state.contextBreakdown!!,
                usedTokens = state.usedContextTokens,
                fullTokens = state.fullContextTokens,
                onDismiss = { showContextSheet = false },
            )
        }

        if (showSubagentInspectionSheet) {
            SubagentInspectionSheet(
                indicators = state.subagentIndicators,
                todos = state.todos,
                onSteerSubagent = { indicator, msg -> viewModel.steerSubagent(indicator, msg) },
                onStopSubagent = { indicator -> viewModel.stopSubagent(indicator) },
                onDismiss = { showSubagentInspectionSheet = false },
            )
        }

        state.btwState?.let { btw ->
            SideQuestionSheet(
                state = btw,
                onDismiss = { viewModel.dismissBtw() },
            )
        }

        viewingImage?.let { image ->
            ImageViewerDialog(
                image = image,
                onDismiss = { viewingImage = null },
            )
        }

        setupSheetProfile?.takeIf { setupUnsupported && it == activeProfileName }?.let { targetProfile ->
            val targetTitle =
                BotConversations.label(
                    targetProfile,
                    botsState.profiles.firstOrNull { botsState.rosterScope == rosterScope && it.name == targetProfile },
                )
                    ?: targetProfile
            SpecialistSetupSheet(
                profileName = targetProfile,
                displayTitle = targetTitle,
                isChatConnected = state.isConnected,
                canSendToCurrentChat = canSendNativeAction(state) && activeProfileName == targetProfile,
                onSendConfirmedRoleMessage = { messageText ->
                    viewModel.sendNativeAction(messageText).also { sent ->
                        if (sent) {
                            scrollController.jumpToBottom(animated = true)
                        }
                    }
                },
                onConfirmed = { botsViewModel.loadBots(isRefresh = true) },
                onDismiss = { setupSheetProfile = null },
            )
        }

        if (showHeaderStatusSheet) {
            val botSettingsLabel = stringResource(R.string.chat_settings_this_bot, activeTitle)
            val editSetupLabel = stringResource(R.string.specialist_chat_edit)
            val returnMainLabel = stringResource(R.string.specialist_detail_return_main)
            val specialistActions =
                if (isMainProfile) {
                    emptyList()
                } else {
                    buildList {
                        add(
                            SettingsMenuItem(
                                botSettingsLabel,
                            ) { NavigationController.navigateTo(BotSettingsKey(activeProfileName)) },
                        )
                        if (specialist != null && !specialist.legacy && specialist.canEnterEdit &&
                            !specialist.editing
                        ) {
                            add(
                                SettingsMenuItem(
                                    editSetupLabel,
                                    canChangeSetup && specialist.canAct,
                                ) { setupController.setEditing(true) },
                            )
                        }
                        add(
                            SettingsMenuItem(returnMainLabel) {
                                scrollScope.launch {
                                    if (BotConversations.openMain()) botsViewModel.loadBots(isRefresh = true)
                                }
                            },
                        )
                    }
                }
            ChatHeaderStatusSheet(
                title = activeTitle,
                avatar = chatAvatar,
                currentSessionModel = state.currentSessionModel,
                reasoningLevel = state.reasoningLevel,
                canDisableReasoning = state.currentModelCapabilities?.can_disable_reasoning,
                supportsReasoning = state.currentModelCapabilities?.reasoning,
                usedContextTokens = state.usedContextTokens,
                fullContextTokens = state.fullContextTokens,
                compressionCount = state.compressionCount,
                hasContextBreakdown = state.contextBreakdown != null,
                controlsEnabled = !setupComposer && state.isConnected,
                onSelectModel = { viewModel.openModelPicker() },
                onSelectReasoning = { level -> viewModel.setReasoningLevel(level) },
                onOpenContextDetails = { showContextSheet = true },
                onDismiss = { showHeaderStatusSheet = false },
                specialistActions = specialistActions,
                workProgress =
                    if (!isMainProfile && shouldShowProgressChip(state.todos, state.subagentIndicators)) {
                        {
                            TaskProgressChip(
                                visible = true,
                                todos = state.todos,
                                indicators = state.subagentIndicators,
                                onClick = {
                                    showHeaderStatusSheet = false
                                    showSubagentInspectionSheet = true
                                },
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}
