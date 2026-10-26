package com.m57.hermescontrol.miniapp.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class MiniAppContractError {
    SIZE_LIMIT,
    MALFORMED_JSON,
    DUPLICATE,
    INVALID_FIELD,
    UNSUPPORTED_SCHEMA,
    UNKNOWN_PERMISSION,
    INVALID_ORIGIN,
    INVALID_PATH,
    UNKNOWN_NATIVE_FEATURE,
    HOST_VERSION,
}

class MiniAppContractException(
    val reason: MiniAppContractError,
) : IllegalArgumentException(reason.name)

/** Dedicated strict decoder; never use the network client's snake_case/ignoreUnknownKeys settings. */
object MiniAppManifestParser {
    const val MAX_BYTES = 65536
    private val json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
        }

    fun parse(bytes: ByteArray): MiniAppManifest {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw MiniAppContractException(MiniAppContractError.SIZE_LIMIT)
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw MiniAppContractException(MiniAppContractError.MALFORMED_JSON)
            }
        try {
            JsonMemberGuard(text, json).check()
            val element = json.parseToJsonElement(text)
            checkPrimitiveTypes(element)
            return json.decodeFromJsonElement(MiniAppManifest.serializer(), element)
        } catch (e: MiniAppContractException) {
            throw e
        } catch (_: SerializationException) {
            throw MiniAppContractException(MiniAppContractError.MALFORMED_JSON)
        } catch (_: IllegalArgumentException) {
            throw MiniAppContractException(MiniAppContractError.MALFORMED_JSON)
        }
    }

    // Optional schema fields may be omitted, but none permit explicit null or primitive coercion.
    private fun checkPrimitiveTypes(
        element: JsonElement,
        key: String = "",
    ) {
        when (element) {
            JsonNull -> {
                throw MiniAppContractException(MiniAppContractError.INVALID_FIELD)
            }

            is JsonObject -> {
                element.forEach { (name, value) -> checkPrimitiveTypes(value, name) }
            }

            is JsonArray -> {
                element.forEach { checkPrimitiveTypes(it, key) }
            }

            is JsonPrimitive -> {
                val valid =
                    when (key) {
                        "priority", "maxAgeSeconds" -> !element.isString && element.intOrNull != null
                        "allowDrafts" -> !element.isString && element.booleanOrNull != null
                        else -> element.isString
                    }
                if (!valid) throw MiniAppContractException(MiniAppContractError.INVALID_FIELD)
            }
        }
    }
}

/** Bounded lexical pass preserves duplicate member names that a JsonObject would otherwise overwrite. */
private class JsonMemberGuard(
    private val text: String,
    private val json: Json,
) {
    private var position = 0

    fun check() {
        value(0)
        whitespace()
        require(position == text.length)
    }

    private fun value(depth: Int) {
        require(depth <= 32)
        whitespace()
        when (text.getOrNull(position)) {
            '{' -> {
                position++
                whitespace()
                val names = mutableSetOf<String>()
                if (consume('}')) return
                do {
                    whitespace()
                    val name = string()
                    if (!names.add(name)) throw MiniAppContractException(MiniAppContractError.DUPLICATE)
                    whitespace()
                    require(consume(':'))
                    value(depth + 1)
                    whitespace()
                    if (consume('}')) return
                    require(consume(','))
                } while (true)
            }

            '[' -> {
                position++
                whitespace()
                if (consume(']')) return
                do {
                    value(depth + 1)
                    whitespace()
                    if (consume(']')) return
                    require(consume(','))
                } while (true)
            }

            '"' -> {
                string()
            }

            else -> {
                val start = position
                while (position < text.length && text[position] !in ",]} \t\r\n") position++
                require(position > start)
            }
        }
    }

    private fun string(): String {
        val start = position
        require(consume('"'))
        while (position < text.length) {
            when (text[position++]) {
                '\\' -> {
                    require(position < text.length)
                    position++
                }

                '"' -> {
                    return json.decodeFromString<String>(text.substring(start, position))
                }
            }
        }
        throw MiniAppContractException(MiniAppContractError.MALFORMED_JSON)
    }

    private fun whitespace() {
        while (position < text.length && text[position] in " \t\r\n") position++
    }

    private fun consume(character: Char): Boolean {
        if (text.getOrNull(position) != character) return false
        position++
        return true
    }
}
