package com.m57.hermescontrol.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Clock

/**
 * Chat-side capabilities for native components. [submit] sends a component's shown conditions as
 * the user's next message; the composer draft and attachments stay untouched. [canSubmit] is false
 * while a reply is generating, offline, or another message is waiting.
 */
internal data class NativeUiHost(
    val scope: String,
    val canSubmit: Boolean = true,
    val submit: (String) -> Unit,
)

internal val LocalNativeUiHost = staticCompositionLocalOf<NativeUiHost?> { null }
internal val LocalNativeUiMessage = staticCompositionLocalOf<String?> { null }

/** Hash namespaces: no host URLs, prompt text, or financial values in preference keys. */
internal fun nativeUiKey(vararg parts: String): String {
    val input = parts.joinToString("") { "${it.length}:$it" }
    return MessageDigest
        .getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Bounded non-secret values inside the app's existing encrypted preferences, never logs. */
internal object NativeUiStateStore {
    private val mutex = Mutex()
    private val memory = linkedMapOf<String, String>()
    private const val SCOPE = "native-ui-v1"

    @Synchronized
    fun peek(key: String): String? = memory[key]

    @Synchronized
    fun remember(
        key: String,
        value: String,
    ) {
        require(value.length <= 256)
        memory.remove(key)
        memory[key] = value
        while (memory.size > 128) memory.remove(memory.keys.first())
    }

    suspend fun read(key: String): String? =
        withContext(Dispatchers.IO) {
            peek(key) ?: mutex.withLock { entries()[key]?.let { (it as? JsonPrimitive)?.content } }
        }

    suspend fun write(
        key: String,
        value: String,
    ) = withContext(Dispatchers.IO) {
        require(value.length <= 256)
        mutex.withLock {
            val map = entries().toMutableMap()
            map.remove(key)
            map[key] = JsonPrimitive(value)
            while (map.size > 128) map.remove(map.keys.first())
            AuthManager.writeUiPreference(SCOPE, "inputs", JsonObject(map).toString())
        }
    }

    private fun entries(): JsonObject {
        val raw = AuthManager.readUiPreference(SCOPE, "inputs") ?: return JsonObject(emptyMap())
        if (raw.length > 64_000) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }
}

internal data class NativeSavingsInputs(
    val monthly: Double,
    val years: Int,
    val rate: Double,
)

internal fun restoreSavingsInputs(
    value: String?,
    maximum: Double,
): NativeSavingsInputs? {
    val parts = value?.split(',')?.takeIf { it.size == 3 } ?: return null
    val values = parts.map { it.toDoubleOrNull() ?: return null }
    if (values.size != 3 || values.any { !it.isFinite() } || values[0] !in 0.0..maximum ||
        values[1] !in 1.0..60.0 || values[1] != values[1].toInt().toDouble() || values[2] !in -20.0..30.0
    ) {
        return null
    }
    return NativeSavingsInputs(values[0], values[1].toInt(), values[2])
}

/** Device clock for date-relative labels; tests provide a fixed clock. */
internal val LocalNativeUiClock = staticCompositionLocalOf<Clock> { Clock.systemDefaultZone() }

/** Default button labels when a component has no model-provided action. */
internal object NativeActionLabels {
    const val SAVINGS = "この条件で相談"
    const val TABLE = "この行で相談"
    const val CHOICES = "これで進める"
}

/** Sent text: the action label first, then the component title and the conditions shown. */
internal fun nativeActionMessage(
    action: String,
    title: String,
    lines: List<String>,
): String =
    buildString {
        append(action)
        append("\n「").append(title).append("」")
        lines.filter { it.isNotBlank() }.forEach { append('\n').append(it.trim()) }
    }

internal fun savingsActionMessage(
    spec: SavingsSpec,
    monthly: Double,
    years: Int,
    rate: Double,
    result: SavingsResult,
): String =
    nativeActionMessage(
        spec.action ?: NativeActionLabels.SAVINGS,
        spec.title,
        listOf(
            "毎月 ${NativeFormat.number(monthly, 0)}円、期間 ${years}年、想定年率 ${NativeFormat.number(rate)}%。",
            "開始資産 ${NativeFormat.yen(spec.initial)}、新規積立元本上限 ${NativeFormat.yen(spec.cap)}。",
            "毎月末積立の端末内試算: 最終資産 ${NativeFormat.yen(result.last.value)}、" +
                "元本 ${NativeFormat.yen(result.last.principal)}。",
            spec.note,
        ),
    )

internal fun tableActionMessage(
    spec: TableSpec,
    row: List<TableCell>,
): String =
    nativeActionMessage(
        spec.action ?: NativeActionLabels.TABLE,
        spec.title,
        spec.columns.zip(row).map { (column, cell) -> "${column.name}: ${cell.display}" },
    )

internal fun choicesActionMessage(
    spec: ChoicesSpec,
    option: ChoiceOption,
): String =
    nativeActionMessage(
        spec.action ?: NativeActionLabels.CHOICES,
        spec.title,
        listOfNotNull(
            "選択: ${option.label}",
            option.pros.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "利点: "),
            option.cons.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "懸念: "),
            option.scores.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "評価: ") {
                "${it.first} ${it.second}/5"
            },
        ),
    )

/**
 * Shared input persistence for interactive components: memory on every change,
 * encrypted write only when an operation ends, restore once per state key.
 */
internal class NativeUiPersistence(
    private val key: String,
    private val enabled: Boolean,
    private val scope: CoroutineScope,
) {
    var failed by mutableStateOf(false)
        private set

    fun peek(): String? = if (enabled) NativeUiStateStore.peek(key) else null

    fun retain(value: String) {
        if (enabled) NativeUiStateStore.remember(key, value)
    }

    fun save(value: String) {
        if (!enabled) return
        retain(value)
        scope.launch { failed = runCatching { NativeUiStateStore.write(key, value) }.isFailure }
    }

    suspend fun read(): String? {
        if (!enabled) return null
        val saved = runCatching { NativeUiStateStore.read(key) }
        failed = saved.isFailure
        return saved.getOrNull()
    }
}

@Composable
internal fun rememberNativeUiPersistence(
    key: String,
    enabled: Boolean,
    onRestored: (String) -> Unit,
): NativeUiPersistence {
    val scope = rememberCoroutineScope()
    val persistence = remember(key, enabled) { NativeUiPersistence(key, enabled, scope) }
    LaunchedEffect(key, enabled) { persistence.read()?.let(onRestored) }
    return persistence
}

/** Checked keys as short hashes so 20 arbitrary keys fit the 256-char store limit. */
internal fun checklistToken(key: String): String = nativeUiKey("check", key).take(8)

internal fun encodeChecklist(tokens: Set<String>): String = tokens.sorted().takeLast(MAX_CHECK_TOKENS).joinToString(",")

internal fun decodeChecklist(value: String?): Set<String>? {
    if (value == null) return null
    if (value.isEmpty()) return emptySet()
    val tokens = value.split(',')
    if (tokens.size > MAX_CHECK_TOKENS || tokens.any { !checkTokenPattern.matches(it) }) return null
    return tokens.toSet()
}

private const val MAX_CHECK_TOKENS = 25
private val checkTokenPattern = Regex("[0-9a-f]{8}")
