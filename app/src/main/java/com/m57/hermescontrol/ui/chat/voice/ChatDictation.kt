package com.m57.hermescontrol.ui.chat.voice

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.SpeechInputHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ChatDictation {
    var session by mutableStateOf<DictationSession?>(null)
    var toggle: () -> Unit = {}

    fun stop() {
        session?.close()
        session = null
    }
}

@Composable
internal fun rememberChatDictation(
    conversationKey: String,
    enabled: Boolean,
    readDraft: () -> TextFieldValue,
    writeDraft: (TextFieldValue) -> Unit,
    launchExternalActivity: (() -> Unit) -> Unit,
    showError: (String) -> Unit,
): ChatDictation {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val controller = remember(conversationKey, enabled) { ChatDictation() }
    val prompt = stringResource(R.string.chat_mic_listening)
    val unavailable = stringResource(R.string.stt_not_available)
    val denied = stringResource(R.string.stt_permission_denied)
    val failed = stringResource(R.string.stt_session_failed)
    val read by rememberUpdatedState(readDraft)
    val write by rememberUpdatedState(writeDraft)

    var fallbackOwner by remember { mutableStateOf<ChatDictation?>(null) }
    val popup =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            val owner = fallbackOwner
            fallbackOwner = null
            if (owner === controller && result.resultCode == Activity.RESULT_OK) {
                val text =
                    result.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        .orEmpty()
                if (text.isNotBlank()) write(DictationText().update(read(), text, final = true))
            }
        }

    fun start() {
        if (!enabled || fallbackOwner != null || controller.session != null) return
        val recognizer = AndroidDictationRecognizer.create(context)
        if (recognizer == null) {
            fallbackOwner = controller
            try {
                launchExternalActivity { popup.launch(SpeechInputHelper.createSpeechIntent(prompt)) }
            } catch (_: ActivityNotFoundException) {
                fallbackOwner = null
                showError(unavailable)
            }
            return
        }

        val insertion = DictationText()
        controller.session =
            DictationSession(
                recognizer = recognizer,
                schedule = { millis, action ->
                    scope.launch {
                        delay(millis)
                        action()
                    }
                },
                onText = { text, final -> write(insertion.update(read(), text, final)) },
                onFailure = { error ->
                    controller.stop()
                    showError(if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) denied else failed)
                },
            )
        controller.session?.listen()
    }

    var permissionOwner by remember { mutableStateOf<ChatDictation?>(null) }
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val owner = permissionOwner
            permissionOwner = null
            if (owner === controller) {
                if (granted) start() else showError(denied)
            }
        }
    controller.toggle = {
        when {
            controller.session != null -> {
                controller.stop()
            }

            permissionOwner != null -> {
                Unit
            }

            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> {
                start()
            }

            else -> {
                permissionOwner = controller
                requestPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    DisposableEffect(lifecycle, controller) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) controller.stop()
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.stop()
        }
    }
    return controller
}
