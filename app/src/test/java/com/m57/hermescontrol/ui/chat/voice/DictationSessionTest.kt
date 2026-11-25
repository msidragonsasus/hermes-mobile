package com.m57.hermescontrol.ui.chat.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test

class DictationSessionTest {
    private class FakeRecognizer : DictationSession.Recognizer {
        var starts = 0
        var closes = 0

        override fun listen(session: DictationSession) {
            starts++
        }

        override fun close() {
            closes++
        }
    }

    private val recognizer = FakeRecognizer()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val texts = mutableListOf<Pair<String, Boolean>>()
    private val failures = mutableListOf<Int>()
    private val session =
        DictationSession(
            recognizer,
            { delay, action -> scheduled.add(delay to action) },
            { text, final -> texts.add(text to final) },
            { failures.add(it) },
        )

    @Test
    fun pausesBackOffAndSuccessfulResultResetsDelay() {
        session.listen()
        session.error(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(500L, restart())
        session.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals(1000L, restart())
        session.partial("途中")
        session.result("確定")
        assertEquals(200L, restart())
        session.error(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(500L, scheduled.single().first)
        assertEquals(4, recognizer.starts)
        assertEquals(listOf("" to true, "" to true, "途中" to false, "確定" to true, "" to true), texts)
    }

    @Test
    fun networkAndBusyErrorsHaveBoundedRetries() {
        session.listen()
        session.error(SpeechRecognizer.ERROR_NETWORK)
        assertEquals(500L, restart())
        session.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        assertEquals(1000L, restart())
        session.error(SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
        assertEquals(listOf(SpeechRecognizer.ERROR_NETWORK_TIMEOUT), failures)
        assertEquals(1, recognizer.closes)
        assertEquals(0, scheduled.size)
    }

    @Test
    fun permissionAndClientErrorsAreFatal() {
        for (code in listOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_CLIENT)) {
            val recognizer = FakeRecognizer()
            val session =
                DictationSession(recognizer, { _, _ -> error("Unexpected retry") }, { _, _ -> }, { failures.add(it) })
            session.listen()
            session.error(code)
            assertEquals(code, failures.last())
            assertEquals(1, recognizer.closes)
        }
    }

    @Test
    fun closingPreventsPendingRestartAndLateCallbacks() {
        session.listen()
        session.result("確定")
        session.close()
        restart()
        session.result("遅延")
        assertEquals(1, recognizer.starts)
        assertEquals(1, recognizer.closes)
        assertEquals(listOf("確定" to true), texts)
    }

    private fun restart(): Long {
        val (delay, action) = scheduled.removeAt(0)
        action()
        return delay
    }
}
