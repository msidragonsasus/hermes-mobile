package com.m57.hermescontrol.terminal

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

data class TerminalChunk(
    val start: Long,
    val next: Long,
    val data: String,
)

data class TerminalUiState(
    val serverLabel: String = "",
    val sessions: List<TerminalSessionInfo> = emptyList(),
    val selected: String? = null,
    val chunks: List<TerminalChunk> = emptyList(),
    val offset: Long = 0,
    val connected: Boolean = false,
    val closed: Boolean = false,
    val busy: Boolean = false,
    val enabled: Boolean = false,
    val error: String? = null,
)

/** What a new terminal starts in: Hermes' directory, or a login shell as keiya. */
enum class TerminalKind(
    val command: String,
) {
    HERMES("cd ~/.hermes"),
    // su cannot gain privileges inside the sandboxed dashboard service, so log in through local sshd.
    KEIYA("exec ssh -o StrictHostKeyChecking=accept-new keiya@127.0.0.1"),
}

internal interface TerminalActions {
    fun resume()

    fun pause()

    fun create(kind: TerminalKind)

    fun select(id: String)

    /** Back to the session list; the shell keeps running. */
    fun showHome()

    fun send(
        text: String,
        appendEnter: Boolean,
    )

    fun resize(
        newCols: Int,
        newRows: Int,
    )

    fun closeSession()

    fun report(value: String)
}

internal class TerminalViewModel(
    scope: String,
) : ViewModel(),
    TerminalActions {
    private val api = TerminalApi(scope)
    private val mutableState = MutableStateFlow(TerminalUiState(serverLabel = api.serverLabel))
    val state = mutableState.asStateFlow()
    private val operations = Mutex()
    private var polling: Job? = null
    private var resizing: Job? = null
    private var sequence = 0L
    private var cols = 80
    private var rows = 24

    override fun resume() {
        if (polling?.isActive == true) return
        polling =
            viewModelScope.launch {
                var backoff = 1000L
                while (true) {
                    try {
                        if (!connect()) return@launch
                        backoff = 1000L
                        while (true) {
                            try {
                                operations.withLock { readSelected() }
                            } catch (error: TerminalHttpException) {
                                if (error.code != 404 || mutableState.value.selected == null) throw error
                                // The shell is gone (for example after a server restart). Show the list again.
                                mutableState.update {
                                    it.copy(selected = null, chunks = emptyList(), offset = 0, connected = false, closed = false)
                                }
                                refreshSessions()
                                mutableState.update { it.copy(error = "セッションが終了していました。新しく開いてください。") }
                            }
                            backoff = 1000L
                            delay(300)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (!transientTerminalError(error)) {
                            failed(error)
                            return@launch
                        }
                        // Reading is idempotent, so reconnecting is safe. Input is never retried.
                        mutableState.update {
                            it.copy(connected = false, error = "接続が途切れました。再接続しています…")
                        }
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(10_000L)
                    }
                }
            }
    }

    /** Returns false when the server reports the terminal as disabled. */
    private suspend fun connect(): Boolean {
        val status = io { api.call<TerminalStatus>("GET", "status") }
        check(status.api_version == 1) { "未対応のターミナルです。" }
        mutableState.update {
            it.copy(
                enabled = status.enabled,
                serverLabel =
                    status.user?.let { user -> "$user@${api.serverLabel}" } ?: api.serverLabel,
            )
        }
        if (!status.enabled) {
            mutableState.update { it.copy(error = "サーバー側のターミナルを有効にしてください。") }
            return false
        }
        refreshSessions()
        return true
    }

    override fun pause() {
        polling?.cancel()
        polling = null
        mutableState.update { it.copy(connected = false) }
    }

    private suspend fun refreshSessions() {
        val sessions = io { api.call<TerminalSessions>("GET", "sessions") }.sessions
        check(sessions.size <= 16 && sessions.all { validId(it.id) }) { "無効なセッション情報です。" }
        mutableState.update { it.copy(sessions = sessions, error = null) }
    }

    override fun select(id: String) =
        act {
            check(validId(id))
            sequence = 0
            mutableState.update { it.copy(selected = id, chunks = emptyList(), offset = 0, connected = false) }
            readSelected()
            sendSize(id)
        }

    override fun showHome() {
        viewModelScope.launch {
            operations.withLock {
                sequence = 0
                mutableState.update { it.copy(selected = null, chunks = emptyList(), offset = 0, connected = false, closed = false) }
            }
        }
    }

    override fun create(kind: TerminalKind) =
        act {
            val id = UUID.randomUUID().toString()
            // Keep the ID if the response is lost so reconnect can reconcile without creating another shell.
            mutableState.update { it.copy(selected = id, chunks = emptyList(), offset = 0, connected = false) }
            val output =
                io { api.call<TerminalOutput>("POST", "sessions", "{\"id\":\"$id\",\"cols\":$cols,\"rows\":$rows}") }
            accept(output)
            refreshSessions()
            // Same safety as typed input: written once, never re-sent automatically.
            write(kind.command, true)
        }

    override fun send(
        text: String,
        appendEnter: Boolean,
    ) = act { write(text, appendEnter) }

    override fun report(value: String) {
        if (!validTerminalReport(value)) return
        val selected = mutableState.value.selected
        viewModelScope.launch {
            operations.withLock {
                if (selected != mutableState.value.selected || !mutableState.value.connected) return@withLock
                try {
                    write(value, false)
                } catch (error: Exception) {
                    failed(error)
                }
            }
        }
    }

    private suspend fun write(
        text: String,
        appendEnter: Boolean,
    ) {
        val current = mutableState.value
        check(current.connected && !current.closed) { "再接続して状態を確認してください。" }
        val id = current.selected ?: return
        val input = terminalInput(text, appendEnter)
        val data = Base64.encodeToString(input, Base64.NO_WRAP)
        val next = sequence + 1
        mutableState.update { it.copy(connected = false) }
        val receipt =
            try {
                io {
                    api.call<TerminalInputReceipt>(
                        "POST",
                        "sessions/$id/input",
                        "{\"sequence\":$next,\"data\":${JsonPrimitive(data)}}",
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw java.io.IOException("入力の到達を確認できません。出力を確認してください。自動再送しません。")
            }
        check(receipt.sequence == next && receipt.state == "accepted") {
            "入力の一部だけ届いた可能性があります。出力を確認してください。自動再送しません。"
        }
        sequence = next
        readSelected()
    }

    override fun resize(
        newCols: Int,
        newRows: Int,
    ) {
        val c = newCols.coerceIn(20, 240)
        val r = newRows.coerceIn(4, 120)
        if (c == cols && r == rows) return
        cols = c
        rows = r
        resizing?.cancel()
        val selected = mutableState.value.selected ?: return
        resizing =
            viewModelScope.launch {
                delay(100)
                operations.withLock {
                    if (selected == mutableState.value.selected && mutableState.value.connected) {
                        try {
                            sendSize(selected)
                        } catch (error: Exception) {
                            failed(error)
                        }
                    }
                }
            }
    }

    private suspend fun sendSize(id: String) {
        io {
            api.call<kotlinx.serialization.json.JsonObject>(
                "POST",
                "sessions/$id/size",
                "{\"cols\":$cols,\"rows\":$rows}",
            )
        }
    }

    override fun closeSession() =
        act {
            val id = mutableState.value.selected ?: return@act
            io { api.call<kotlinx.serialization.json.JsonObject>("DELETE", "sessions/$id") }
            mutableState.update { it.copy(selected = null, chunks = emptyList(), offset = 0, connected = false) }
            refreshSessions()
        }

    private suspend fun readSelected() {
        val current = mutableState.value
        val id = current.selected ?: return
        val output = io { api.call<TerminalOutput>("GET", "sessions/$id", offset = current.offset) }
        accept(output)
    }

    private fun accept(output: TerminalOutput) {
        val current = mutableState.value
        check(output.id == current.selected && output.start >= current.offset && output.next >= output.start)
        val bytes = Base64.decode(output.data, Base64.NO_WRAP)
        check(bytes.size <= 65536 && output.next - output.start == bytes.size.toLong())
        var chunks = if (output.truncated) emptyList() else current.chunks
        if (bytes.isNotEmpty()) chunks = chunks + TerminalChunk(output.start, output.next, output.data)
        while (chunks.isNotEmpty() && output.next - chunks.first().start > 1024 * 1024) chunks = chunks.drop(1)
        sequence = output.last_input
        mutableState.update {
            it.copy(
                chunks = chunks,
                offset = output.next,
                closed = output.closed,
                connected = !output.closed && output.input_state != "uncertain",
                error =
                    when {
                        output.input_state == "uncertain" -> "入力の一部だけ届いた可能性があります。このセッションを終了して開き直してください。"
                        output.truncated -> "古い出力はバッファ上限を超えました。"
                        else -> null
                    },
            )
        }
    }

    private fun act(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                operations.withLock { block() }
            } catch (error: Exception) {
                failed(error)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun failed(error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update {
            it.copy(
                connected = false,
                error =
                    error.message?.takeIf { error is java.io.IOException || error is IllegalStateException }
                        ?: "接続を確認してください。入力は自動再送されません。",
            )
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Explicit disposal on connection/profile changes removes output and credentials from this model. */
    fun forget() {
        pause()
        resizing?.cancel()
        mutableState.value = TerminalUiState()
    }
}

/** Connection drops and gateway errors while the Dashboard restarts are worth retrying. */
internal fun transientTerminalError(error: Exception): Boolean =
    when (error) {
        is TerminalHttpException -> error.code == 502 || error.code == 504
        is java.io.IOException -> true
        else -> false
    }

internal fun validId(value: String): Boolean =
    runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)

internal fun validTerminalReport(value: String): Boolean =
    Regex("\u001b\\[(?:[0-9]{1,3};[0-9]{1,3}R|[?>][0-9;]{1,40}c|0n)").matches(value)

internal fun terminalInput(
    text: String,
    appendEnter: Boolean,
): ByteArray {
    val value = if (appendEnter) text.replace("\r\n", "\n").replace('\n', '\r') + "\r" else text
    return value.toByteArray(Charsets.UTF_8).also { require(it.size in 1..16384) { "入力はUTF-8で16KBまでです。" } }
}
