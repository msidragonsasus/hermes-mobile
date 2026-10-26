package com.m57.hermescontrol.miniapp.sdui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Why a payload was refused. A category only: payload text is never carried, shown or logged. */
internal enum class SduiRejection {
    MALFORMED,
    TOO_LARGE,
    UNSUPPORTED_VERSION,
    UNKNOWN_FIELD,
    UNKNOWN_COMPONENT,
    UNKNOWN_ACTION,
    LIMIT_EXCEEDED,
    IDENTITY_MISMATCH,
    RELEASE_MISMATCH,
    PERMISSION_MISSING,
}

internal sealed interface SduiParsed<out T> {
    data class Valid<T>(
        val value: T,
    ) : SduiParsed<T>

    data class Rejected(
        val reason: SduiRejection,
    ) : SduiParsed<Nothing>
}

/** What a receipt must echo back for the request that was actually sent. */
internal data class SduiReceiptExpectation(
    val releaseId: String,
    val companyId: String,
    val profile: String,
    val operationId: String,
)

/**
 * The fixed machine codes of backend HTTP errors that this host acts on. Only the code is ever read
 * from an error body; its other fields and any message text are neither kept, shown nor logged.
 */
internal enum class SduiErrorCode(
    val wire: String,
) {
    MOBILE_RELEASE_CHANGED("mobile_release_changed"),
    OPERATION_CONFLICT("operation_conflict"),
    MYPAGE_NOT_CONNECTED("mypage_not_connected"),
    COMPANY_CLOSED("company_closed"),
    COMPANY_MISSING("company_missing"),
    ;

    companion object {
        fun fromWire(value: String): SduiErrorCode? = entries.firstOrNull { it.wire == value }
    }
}

private class SduiReject(
    val reason: SduiRejection,
) : RuntimeException(reason.name)

private fun reject(reason: SduiRejection): Nothing = throw SduiReject(reason)

/**
 * All-or-nothing parser for the first SDUI slice. A payload is either fully understood and
 * identity-checked, or rejected as a whole: unknown fields, components, actions, versions and
 * oversize structures are never skipped or partially rendered. Quoted `"true"` / `"1"` are
 * strings, not booleans or numbers.
 */
internal object SduiParser {
    private const val MAX_RAW_NESTING = 32
    private const val MAX_STATE = 64
    private const val MAX_PERMISSION = 64

    private val manifestKeys =
        setOf("app_id", "protocol_version", "component_version", "release_id", "permissions", "route")
    private val screenKeys =
        setOf(
            "app_id",
            "protocol_version",
            "component_version",
            "release_id",
            "company_id",
            "profile",
            "title",
            "state",
            "components",
        )
    private val receiptKeys =
        setOf("protocol_version", "release_id", "company_id", "profile", "operation_id", "state", "run_id")

    fun parseManifest(body: ByteArray): SduiParsed<SduiManifest> =
        guarded {
            val root = rootObject(body)
            versionGate(root, "protocol_version", "component_version")
            val o = Strict(root, manifestKeys)
            val appId = o.id("app_id")
            if (appId != SduiContract.APP_ID) reject(SduiRejection.IDENTITY_MISMATCH)
            val permissions = permissions(o.array("permissions"))
            if (SduiContract.PERMISSION_READ !in permissions) reject(SduiRejection.PERMISSION_MISSING)
            val route = o.id("route")
            if (route != SduiContract.ROUTE) reject(SduiRejection.IDENTITY_MISMATCH)
            SduiManifest(
                appId = appId,
                protocolVersion = o.int("protocol_version"),
                componentVersion = o.int("component_version"),
                releaseId = o.id("release_id"),
                permissions = permissions,
                route = route,
            )
        }

    fun parseScreen(
        body: ByteArray,
        manifest: SduiManifest,
        companyId: String,
        profile: String,
    ): SduiParsed<SduiScreen> =
        guarded {
            val root = rootObject(body)
            versionGate(root, "protocol_version", "component_version")
            val o = Strict(root, screenKeys)
            if (o.id("app_id") != manifest.appId) reject(SduiRejection.IDENTITY_MISMATCH)
            val release = o.id("release_id")
            if (release != manifest.releaseId) reject(SduiRejection.RELEASE_MISMATCH)
            if (o.id("company_id") != companyId) reject(SduiRejection.IDENTITY_MISMATCH)
            if (o.id("profile") != profile) reject(SduiRejection.IDENTITY_MISMATCH)
            val title = o.string("title", SduiContract.MAX_LABEL, blank = false)
            val state = o.string("state", MAX_STATE, blank = false)
            val counter = NodeCounter()
            val components = nodes(o.array("components"), 1, counter)
            SduiScreen(
                appId = manifest.appId,
                protocolVersion = o.int("protocol_version"),
                componentVersion = o.int("component_version"),
                releaseId = release,
                companyId = companyId,
                profile = profile,
                title = title,
                state = state,
                components = components,
            )
        }

    fun parseReceipt(
        body: ByteArray,
        expected: SduiReceiptExpectation,
    ): SduiParsed<SduiReceipt> =
        guarded {
            val root = rootObject(body)
            versionGate(root, "protocol_version")
            val o = Strict(root, receiptKeys)
            o.int("protocol_version")
            if (o.id("release_id") != expected.releaseId) reject(SduiRejection.RELEASE_MISMATCH)
            if (o.id("company_id") != expected.companyId) reject(SduiRejection.IDENTITY_MISMATCH)
            if (o.id("profile") != expected.profile) reject(SduiRejection.IDENTITY_MISMATCH)
            val operation = o.id("operation_id")
            if (operation != expected.operationId) reject(SduiRejection.IDENTITY_MISMATCH)
            val state =
                when (o.string("state", MAX_STATE, blank = false)) {
                    "queued" -> SduiReceiptState.QUEUED
                    "running" -> SduiReceiptState.RUNNING
                    else -> reject(SduiRejection.MALFORMED)
                }
            SduiReceipt(
                releaseId = expected.releaseId,
                companyId = expected.companyId,
                profile = expected.profile,
                operationId = operation,
                state = state,
                runId = o.id("run_id"),
            )
        }

    /**
     * `{"detail":{"code":"…"}}` to one trusted code. Null for everything else: oversize, not strict
     * UTF-8, not that shape, or a code this host does not know. The caller treats null as "unknown".
     */
    fun parseErrorCode(body: ByteArray): SduiErrorCode? {
        if (body.size > SduiContract.MAX_ERROR_BODY_BYTES) return null
        return try {
            val detail = rootObject(body)["detail"] as? JsonObject ?: return null
            val code = detail["code"] as? JsonPrimitive ?: return null
            if (!code.isString) null else SduiErrorCode.fromWire(code.content)
        } catch (_: Exception) {
            // Includes the parser's own rejections: an unreadable body is simply an unknown error.
            null
        }
    }

    // ── components ──────────────────────────────────────────────────────

    private class NodeCounter {
        var count = 0
    }

    private fun nodes(
        array: JsonArray,
        depth: Int,
        counter: NodeCounter,
    ): List<SduiNode> {
        if (depth > SduiContract.MAX_DEPTH) reject(SduiRejection.LIMIT_EXCEEDED)
        return array.map { node(it, depth, counter) }
    }

    private fun node(
        element: JsonElement,
        depth: Int,
        counter: NodeCounter,
    ): SduiNode {
        counter.count++
        if (counter.count > SduiContract.MAX_NODES) reject(SduiRejection.LIMIT_EXCEEDED)
        val obj = element as? JsonObject ?: reject(SduiRejection.MALFORMED)
        val type = obj["type"] as? JsonPrimitive
        if (type == null || !type.isString) reject(SduiRejection.MALFORMED)
        return when (type.content) {
            "Section" -> {
                val o = Strict(obj, setOf("type", "title", "children"))
                SduiSection(
                    title = o.string("title", SduiContract.MAX_LABEL, blank = false),
                    children = nodes(o.array("children"), depth + 1, counter),
                )
            }

            "Column" -> {
                val o = Strict(obj, setOf("type", "children"))
                SduiColumn(nodes(o.array("children"), depth + 1, counter))
            }

            "Text" -> {
                val o = Strict(obj, setOf("type", "text"))
                SduiText(o.string("text", SduiContract.MAX_TEXT))
            }

            "Status" -> {
                val o = Strict(obj, setOf("type", "text", "tone"))
                val tone = SduiTone.fromWire(o.string("tone", MAX_STATE)) ?: reject(SduiRejection.MALFORMED)
                SduiStatus(o.string("text", SduiContract.MAX_TEXT), tone)
            }

            "KeyValue" -> {
                val o = Strict(obj, setOf("type", "label", "value"))
                SduiKeyValue(
                    label = o.string("label", SduiContract.MAX_LABEL, blank = false),
                    value = o.string("value", SduiContract.MAX_TEXT),
                )
            }

            "Button" -> {
                val o = Strict(obj, setOf("type", "label", "action", "enabled"))
                val action =
                    SduiAction.fromWire(o.string("action", MAX_STATE)) ?: reject(SduiRejection.UNKNOWN_ACTION)
                SduiButton(
                    label = o.string("label", SduiContract.MAX_LABEL, blank = false),
                    action = action,
                    enabled = o.bool("enabled"),
                )
            }

            else -> {
                reject(SduiRejection.UNKNOWN_COMPONENT)
            }
        }
    }

    private fun permissions(array: JsonArray): Set<String> {
        if (array.size > SduiContract.MAX_PERMISSIONS) reject(SduiRejection.LIMIT_EXCEEDED)
        val result = LinkedHashSet<String>()
        array.forEach { element ->
            val primitive = element as? JsonPrimitive
            if (primitive == null || !primitive.isString) reject(SduiRejection.MALFORMED)
            val value = primitive.content
            if (value.length > MAX_PERMISSION) reject(SduiRejection.LIMIT_EXCEEDED)
            if (value !in SduiContract.PERMISSIONS) reject(SduiRejection.UNKNOWN_FIELD)
            result.add(value)
        }
        return result
    }

    // ── envelope ────────────────────────────────────────────────────────

    private inline fun <T> guarded(block: () -> T): SduiParsed<T> =
        try {
            SduiParsed.Valid(block())
        } catch (rejected: SduiReject) {
            SduiParsed.Rejected(rejected.reason)
        } catch (_: Exception) {
            SduiParsed.Rejected(SduiRejection.MALFORMED)
        }

    private fun rootObject(body: ByteArray): JsonObject {
        if (body.size > SduiContract.MAX_BODY_BYTES) reject(SduiRejection.TOO_LARGE)
        if (rawNesting(body) > MAX_RAW_NESTING) reject(SduiRejection.LIMIT_EXCEEDED)
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString()
            } catch (_: CharacterCodingException) {
                reject(SduiRejection.MALFORMED)
            }
        return Json.parseToJsonElement(text) as? JsonObject ?: reject(SduiRejection.MALFORMED)
    }

    /** Bracket depth before parsing, so a hostile `[[[[…` body cannot overflow the parser's stack. */
    private fun rawNesting(body: ByteArray): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        for (byte in body) {
            val c = byte.toInt().toChar()
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > max) max = depth
                    '}', ']' -> depth--
                }
            }
        }
        return max
    }

    /**
     * Version is judged before the schema so a future server answers "update needed" rather than
     * "unknown field". A present but non-numeric version is malformed.
     */
    private fun versionGate(
        root: JsonObject,
        vararg keys: String,
    ) {
        keys.forEach { key ->
            val primitive = root[key] as? JsonPrimitive ?: return@forEach
            val number = if (primitive.isString) null else primitive.content.toLongOrNull()
            if (number == null) reject(SduiRejection.MALFORMED)
            val expected =
                if (key == "protocol_version") {
                    SduiContract.PROTOCOL_VERSION
                } else {
                    SduiContract.COMPONENT_VERSION
                }
            if (number != expected.toLong()) reject(SduiRejection.UNSUPPORTED_VERSION)
        }
    }

    /** One JSON object with an exact key set and primitives of exactly the declared JSON kind. */
    private class Strict(
        private val obj: JsonObject,
        keys: Set<String>,
    ) {
        init {
            if (obj.keys.any { it !in keys }) reject(SduiRejection.UNKNOWN_FIELD)
            if (keys.any { it !in obj }) reject(SduiRejection.MALFORMED)
        }

        fun string(
            key: String,
            max: Int,
            blank: Boolean = true,
        ): String {
            val primitive = obj[key] as? JsonPrimitive
            if (primitive == null || !primitive.isString) reject(SduiRejection.MALFORMED)
            val value = primitive.content
            if (value.length > max) reject(SduiRejection.LIMIT_EXCEEDED)
            if (!blank && value.isBlank()) reject(SduiRejection.MALFORMED)
            if (!validText(value)) reject(SduiRejection.MALFORMED)
            return value
        }

        fun id(key: String): String {
            val value = string(key, SduiContract.MAX_ID, blank = false)
            if (value.any { it.isWhitespace() || it.isISOControl() }) reject(SduiRejection.MALFORMED)
            return value
        }

        fun int(key: String): Int {
            val primitive = obj[key] as? JsonPrimitive
            if (primitive == null || primitive.isString) reject(SduiRejection.MALFORMED)
            return primitive.content.toIntOrNull() ?: reject(SduiRejection.MALFORMED)
        }

        fun bool(key: String): Boolean {
            val primitive = obj[key] as? JsonPrimitive
            if (primitive == null || primitive.isString) reject(SduiRejection.MALFORMED)
            return when (primitive.content) {
                "true" -> true
                "false" -> false
                else -> reject(SduiRejection.MALFORMED)
            }
        }

        fun array(key: String): JsonArray = obj[key] as? JsonArray ?: reject(SduiRejection.MALFORMED)
    }
}

/** Newlines and tabs are content; other control characters and unpaired surrogates are not. */
private fun validText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val c = value[index]
        if ((c < ' ' && c != '\n' && c != '\r' && c != '\t') || c == '\u007f') return false
        if (Character.isHighSurrogate(c)) {
            if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
            index++
        } else if (Character.isLowSurrogate(c)) {
            return false
        }
        index++
    }
    return true
}
