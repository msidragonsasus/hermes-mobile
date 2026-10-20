package com.m57.hermescontrol.domain.hermes

import kotlinx.serialization.json.Json

/** Untrusted deep-link metadata can select a detail view, never create a request or grant permission. */
object RequestReferenceCodec {
    const val MAX_ENCODED_LENGTH = 8192

    fun isValid(ref: RequestRef): Boolean =
        validId(ref.scope.serverIdentity, 1024) && validId(ref.scope.processId, 128) &&
            ref.scope.generation >= 0 && optionalId(ref.scope.connectionProfileId) &&
            optionalId(ref.scope.serverProfileId) && validId(ref.session.runtimeId) &&
            optionalId(ref.session.savedId) && validId(ref.requestId) && optionalId(ref.questionId)

    fun encode(ref: RequestRef): String {
        require(isValid(ref))
        return Json.encodeToString(ref).also { require(it.length <= MAX_ENCODED_LENGTH) }
    }

    fun decode(encoded: String?): RequestRef? {
        if (encoded == null || encoded.length > MAX_ENCODED_LENGTH) return null
        return runCatching { Json.decodeFromString<RequestRef>(encoded) }.getOrNull()?.takeIf(::isValid)
    }

    private fun validId(
        value: String,
        limit: Int = 512,
    ): Boolean = value.isNotBlank() && value.length <= limit && value.none(Char::isISOControl)

    private fun optionalId(value: String?): Boolean = value == null || validId(value)
}
