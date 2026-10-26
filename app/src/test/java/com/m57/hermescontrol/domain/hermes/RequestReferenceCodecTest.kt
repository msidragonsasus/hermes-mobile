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
