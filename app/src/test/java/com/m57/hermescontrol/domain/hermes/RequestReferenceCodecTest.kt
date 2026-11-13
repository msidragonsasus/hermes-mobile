package com.m57.hermescontrol.domain.hermes

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestReferenceCodecTest {
    private val ref =
        RequestRef(
            ConnectionScope("server", "connection", "profile", "process", 2),
            SessionRef("runtime"),
            "request",
            "q2",
        )

    @Test
    fun preservesAllOriginalIdentifiersWithoutARequestBody() {
        assertEquals(ref, RequestReferenceCodec.decode(RequestReferenceCodec.encode(ref)))
    }

    @Test
    fun optionalOuterIdentityRoundTripsWithoutBreakingLegacyOrInferringPrefixes() {
        val modern = ref.copy(serverRequestId = "outer")
        assertEquals(modern, RequestReferenceCodec.decode(RequestReferenceCodec.encode(modern)))
        val legacy = ref.copy(requestId = "srq-legacy")
        assertEquals(null, RequestReferenceCodec.decode(RequestReferenceCodec.encode(legacy))?.serverRequestId)
        for (invalid in listOf("", " ", "outer\n", "x".repeat(513))) {
            assertNull(RequestReferenceCodec.decode(Json.encodeToString(ref.copy(serverRequestId = invalid))))
        }
    }

    @Test
    fun rejectsMalformedOversizeAndInvalidIdentifiers() {
        listOf(null, "", "{}", "[", "x".repeat(8193), RequestReferenceCodec.encode(ref).dropLast(1)).forEach {
            assertNull(RequestReferenceCodec.decode(it))
        }
        listOf(
            ref.copy(requestId = ""),
            ref.copy(questionId = " "),
            ref.copy(scope = ref.scope.copy(generation = -1)),
            ref.copy(session = SessionRef("runtime\nother")),
            ref.copy(requestId = "x".repeat(513)),
        ).forEach { assertNull(RequestReferenceCodec.decode(Json.encodeToString(it))) }
    }
}
