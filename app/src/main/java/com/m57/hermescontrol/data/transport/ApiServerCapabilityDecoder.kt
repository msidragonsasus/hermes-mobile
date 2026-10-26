package com.m57.hermescontrol.data.transport

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** API Server wire types are deliberately separate from Dashboard DTOs and authentication. */
enum class ApiFeature(
    val wireName: String,
) {
    RUN_SUBMISSION("run_submission"),
    APPROVAL_EVENTS("approval_events"),
    RUN_APPROVAL_RESPONSE("run_approval_response"),
    SESSION_CONTINUITY_HEADER("session_continuity_header"),
}

data class AdvertisedIdempotency(
    val supported: Boolean,
    val durable: Boolean,
    val retentionSeconds: Long,
)

data class ApiServerAdvertisement(
    val features: Map<ApiFeature, Boolean>,
    val idempotency: AdvertisedIdempotency?,
) {
    /** Advertisements alone do not prove continuity, exact approval, or safe retry. */
    val liveWritesEnabled: Boolean get() = false
}

enum class ApiDiscoveryFailure { AUTH_REQUIRED, NOT_FOUND, MALFORMED, UNAVAILABLE }

sealed interface ApiDiscoveryResult {
    data class Advertisement(
        val value: ApiServerAdvertisement,
    ) : ApiDiscoveryResult

    data class Unavailable(
        val reason: ApiDiscoveryFailure,
    ) : ApiDiscoveryResult
}

/** Offline decoder only in Phase 1. It neither fetches advertised URLs nor reuses Dashboard credentials. */
object ApiServerCapabilityDecoder {
    private const val MAX_BYTES = 64 * 1024
    private val decoder = Json { isLenient = false }

    fun decode(
        status: Int?,
        body: String?,
    ): ApiDiscoveryResult {
        if (status == 401 || status == 403) return unavailable(ApiDiscoveryFailure.AUTH_REQUIRED)
        if (status == 404) return unavailable(ApiDiscoveryFailure.NOT_FOUND)
        if (status != 200) return unavailable(ApiDiscoveryFailure.UNAVAILABLE)
        if (body == null || body.length > MAX_BYTES || body.toByteArray(Charsets.UTF_8).size > MAX_BYTES ||
            !safeDepth(body)
        ) {
            return unavailable(ApiDiscoveryFailure.MALFORMED)
        }
        val root =
            try {
                decoder.parseToJsonElement(body) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return unavailable(ApiDiscoveryFailure.MALFORMED)
        if ((root["object"] as? JsonPrimitive)?.content != "hermes.api_server.capabilities") {
            return unavailable(ApiDiscoveryFailure.MALFORMED)
        }
        val features = root["features"] as? JsonObject ?: return unavailable(ApiDiscoveryFailure.MALFORMED)
        val flags = mutableMapOf<ApiFeature, Boolean>()
        for (feature in ApiFeature.entries) {
            if (feature.wireName !in features) continue
            val raw = features[feature.wireName] as? JsonPrimitive
            val flag =
                raw?.takeUnless { it.isString }?.booleanOrNull ?: return unavailable(ApiDiscoveryFailure.MALFORMED)
            flags[feature] = flag
        }
        val idempotency =
            if ("runs_idempotency" in features) {
                val raw =
                    features["runs_idempotency"] as? JsonObject ?: return unavailable(ApiDiscoveryFailure.MALFORMED)
                val supported = (raw["supported"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                val durable = (raw["durable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                val retention = (raw["retention_seconds"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                if (supported == null || durable == null || retention == null || retention < 0) {
                    return unavailable(ApiDiscoveryFailure.MALFORMED)
                }
                AdvertisedIdempotency(supported, durable, retention)
            } else {
                null
            }
        return ApiDiscoveryResult.Advertisement(ApiServerAdvertisement(flags, idempotency))
    }

    private fun unavailable(reason: ApiDiscoveryFailure) = ApiDiscoveryResult.Unavailable(reason)

    private fun safeDepth(body: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in body) {
            if (quoted) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> if (++depth > 32) return false
                    '}', ']' -> depth--
                }
            }
        }
        return true
    }
}
