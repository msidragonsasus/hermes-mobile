package com.m57.hermescontrol.terminal

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Role of the one saved password offered at keiya's SSH login prompt. */
internal const val KEIYA_PASSWORD_ROLE = "keiya-password"

/** Encrypted on-device file; excluded from backup and device transfer (res/xml). */
internal const val TERMINAL_SNIPPET_FILE = "hermes_terminal_snippets"

/**
 * A string the user saved for quick insertion into the terminal editor. Inserting never sends.
 * [secret] entries (passwords) stay masked; only their label is listed.
 */
@Serializable
internal data class TerminalSnippet(
    val id: String,
    val label: String,
    val value: String,
    val secret: Boolean = false,
    val role: String? = null,
) {
    override fun toString() = "TerminalSnippet(id=$id, secret=$secret, role=$role)"
}

internal fun List<TerminalSnippet>.keiyaPassword(): TerminalSnippet? = firstOrNull { it.role == KEIYA_PASSWORD_ROLE }

/**
 * Every store shares one file ([TERMINAL_SNIPPET_FILE]), and a screen can hold an old and a new
 * store for the same scope at once. One process-wide lock covers each whole load-modify-write so
 * a concurrent save cannot drop an entry or bring back a deleted password.
 */
private val snippetTransactions = Any()

/** Fixed messages only: no value, key or storage exception text ever reaches the UI or logs. */
internal class TerminalSnippetException(
    message: String,
    /** The saved list itself cannot be used (unreadable, or the connection changed). */
    val unavailable: Boolean = false,
) : IllegalStateException(message)

/** Key/value storage that must hold values encrypted at rest. Failures throw. */
internal interface TerminalSecretStorage {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String?,
    )
}

/** AndroidKeyStore-backed storage, the same scheme as the app's other encrypted stores. */
internal class EncryptedTerminalStorage(
    context: Context,
) : TerminalSecretStorage {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            TERMINAL_SNIPPET_FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(
        key: String,
        value: String?,
    ) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        if (!editor.commit()) throw java.io.IOException("commit failed")
    }
}

/**
 * Saved entries for exactly one server + profile [scope]. The binding is fixed at construction,
 * and [current] is checked before every read and write so a stale dialog cannot touch another
 * connection. Unreadable data (corrupt, key unavailable) fails closed and is never overwritten.
 */
internal class TerminalSnippetStore(
    private val storage: TerminalSecretStorage,
    scope: String,
    private val current: () -> Boolean = { true },
) {
    private val key = "snippets:" + sha256(scope)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TerminalSnippet.serializer())

    fun load(): List<TerminalSnippet> = synchronized(snippetTransactions) { read() }

    fun upsert(snippet: TerminalSnippet): List<TerminalSnippet> =
        synchronized(snippetTransactions) {
            validate(snippet)
            val saved = read()
            val others =
                saved.filter {
                    it.id != snippet.id && !(snippet.role != null && it.role == snippet.role)
                }
            val index = saved.indexOfFirst { it.id == snippet.id }
            val next =
                if (index < 0) {
                    if (others.size >= MAX_ENTRIES) throw TerminalSnippetException("保存できるのは${MAX_ENTRIES}件までです。")
                    others + snippet
                } else {
                    others.toMutableList().apply { add(index.coerceAtMost(size), snippet) }
                }
            persist(next)
        }

    fun delete(id: String): List<TerminalSnippet> =
        synchronized(snippetTransactions) {
            persist(
                read().filter {
                    it.id !=
                        id
                },
            )
        }

    private fun read(): List<TerminalSnippet> {
        checkScope()
        val raw =
            try {
                storage.read(key)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw TerminalSnippetException(UNREADABLE, unavailable = true)
            } ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw).also { list -> list.forEach(::validate) }
        } catch (error: Exception) {
            throw TerminalSnippetException(UNREADABLE, unavailable = true)
        }
    }

    private fun persist(list: List<TerminalSnippet>): List<TerminalSnippet> {
        checkScope()
        try {
            storage.write(key, if (list.isEmpty()) null else json.encodeToString(serializer, list))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw TerminalSnippetException("端末内に保存できません。入力内容は送信していません。")
        }
        return list
    }

    private fun checkScope() {
        if (!current()) throw TerminalSnippetException("接続先が変わりました。ターミナルを開き直してください。", unavailable = true)
    }

    private fun validate(snippet: TerminalSnippet) {
        if (snippet.label.isBlank() || snippet.label.length > MAX_LABEL) {
            throw TerminalSnippetException("名前は1〜${MAX_LABEL}文字で入力してください。")
        }
        if (snippet.value.isEmpty() || snippet.value.length > MAX_VALUE) {
            throw TerminalSnippetException("内容は1〜${MAX_VALUE}文字で入力してください。")
        }
        if (snippet.id.isBlank() || snippet.id.length > 64) throw TerminalSnippetException(UNREADABLE)
        if (snippet.role != null && (snippet.role != KEIYA_PASSWORD_ROLE || !snippet.secret)) {
            throw TerminalSnippetException(UNREADABLE)
        }
    }

    private companion object {
        const val MAX_ENTRIES = 50
        const val MAX_LABEL = 40
        const val MAX_VALUE = 4096
        const val UNREADABLE = "保存した入力を読み込めません。端末の保護キーまたは保存データを確認してください。"

        fun sha256(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

internal data class TerminalSnippetState(
    val items: List<TerminalSnippet> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
    /** A save or delete is in flight; further mutations are refused until it ends. */
    val busy: Boolean = false,
)

/** UI state for saved entries. Load failures keep the list unavailable instead of empty-and-writable. */
internal class TerminalSnippetsModel(
    private val store: TerminalSnippetStore,
) {
    private val mutableState = MutableStateFlow(TerminalSnippetState())
    val state = mutableState.asStateFlow()
    private val pending =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    suspend fun load() {
        run { store.load() }?.let { mutableState.value = TerminalSnippetState(it, loaded = true) }
    }

    suspend fun save(snippet: TerminalSnippet): Boolean = update { store.upsert(snippet) }

    suspend fun delete(id: String): Boolean = update { store.delete(id) }

    /** One mutation at a time: a second Save/Delete while one is pending is refused, not queued. */
    private suspend fun update(block: () -> List<TerminalSnippet>): Boolean {
        if (!mutableState.value.loaded || !pending.compareAndSet(false, true)) return false
        mutableState.update { it.copy(busy = true) }
        try {
            val result = run(block) ?: return false
            mutableState.value = TerminalSnippetState(result, loaded = true)
            return true
        } finally {
            pending.set(false)
            mutableState.update { it.copy(busy = false) }
        }
    }

    private suspend fun run(block: () -> List<TerminalSnippet>): List<TerminalSnippet>? =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (error: TerminalSnippetException) {
            mutableState.value =
                if (error.unavailable) {
                    TerminalSnippetState(loaded = false, error = error.message)
                } else {
                    mutableState.value.copy(error = error.message)
                }
            null
        }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }
}

/**
 * The editor text and whether it holds an inserted password. Masking follows the content,
 * not a mode: it ends when the field is emptied or sent. Never persisted or saved across recreation.
 */
internal data class TerminalDraft(
    val text: String = "",
    val secret: Boolean = false,
) {
    fun edit(value: String) = TerminalDraft(value, secret && value.isNotEmpty())

    /** Appends at the end, keeping what was already typed. */
    fun insert(snippet: TerminalSnippet) = TerminalDraft(text + snippet.value, secret || snippet.secret)

    override fun toString() = "TerminalDraft(length=${text.length}, secret=$secret)"
}
