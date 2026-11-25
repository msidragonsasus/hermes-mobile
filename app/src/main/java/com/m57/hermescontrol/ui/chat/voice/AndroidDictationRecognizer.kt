package com.m57.hermescontrol.ui.chat.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

internal class AndroidDictationRecognizer private constructor(
    private val recognizer: SpeechRecognizer,
) : DictationSession.Recognizer {
    private val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

    override fun listen(session: DictationSession) {
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit

                override fun onPartialResults(partialResults: Bundle?) = session.partial(partialResults.transcript())

                override fun onResults(results: Bundle?) = session.result(results.transcript())

                override fun onError(error: Int) = session.error(error)
            },
        )
        recognizer.startListening(intent)
    }

    override fun close() {
        recognizer.destroy()
    }

    private fun Bundle?.transcript(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    companion object {
        @Suppress("DEPRECATION")
        private fun hasDefaultService(
            context: Context,
            component: ComponentName,
        ): Boolean =
            context.packageManager
                .queryIntentServices(
                    Intent(RecognitionService.SERVICE_INTERFACE).setComponent(component),
                    0,
                ).isNotEmpty()

        fun create(context: Context): AndroidDictationRecognizer? =
            runCatching {
                val setting = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
                val component = setting?.let(ComponentName::unflattenFromString)
                if (component == null || component.packageName == context.packageName ||
                    !SpeechRecognizer.isRecognitionAvailable(context) ||
                    !hasDefaultService(context, component)
                ) {
                    null
                } else {
                    AndroidDictationRecognizer(SpeechRecognizer.createSpeechRecognizer(context))
                }
            }.getOrNull()
    }
}
