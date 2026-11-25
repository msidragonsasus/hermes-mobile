package com.m57.hermescontrol.ui.chat.voice

import android.speech.SpeechRecognizer

internal class DictationSession(
    private val recognizer: Recognizer,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val onText: (String, Boolean) -> Unit,
    private val onFailure: (Int) -> Unit,
) {
    interface Recognizer {
        fun listen(session: DictationSession)

        fun close()
    }

    private var active = true
    private var awaitingResult = false
    private var consecutiveErrors = 0
    private var retryableFailures = 0

    fun listen() {
        if (!active || awaitingResult) return
        awaitingResult = true
        try {
            recognizer.listen(this)
        } catch (_: SecurityException) {
            error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        } catch (_: RuntimeException) {
            error(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun partial(text: String) {
        if (awaitingResult) onText(text, false)
    }

    fun result(text: String) {
        if (!awaitingResult) return
        awaitingResult = false
        consecutiveErrors = 0
        retryableFailures = 0
        onText(text, true)
        schedule(200, ::listen)
    }

    fun error(code: Int) {
        if (!awaitingResult) return
        awaitingResult = false
        onText("", true)
        val retry =
            when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> true

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> ++retryableFailures <= 2

                else -> false
            }
        if (retry) {
            consecutiveErrors = (consecutiveErrors + 1).coerceAtMost(4)
            schedule(250L shl consecutiveErrors, ::listen)
        } else {
            close()
            onFailure(code)
        }
    }

    fun close() {
        if (!active) return
        active = false
        awaitingResult = false
        recognizer.close()
    }
}
