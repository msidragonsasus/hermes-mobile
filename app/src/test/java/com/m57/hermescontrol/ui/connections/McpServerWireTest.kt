package com.m57.hermescontrol.ui.connections

import com.m57.hermescontrol.data.model.McpServersResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class McpServerWireTest {
    @Test fun structuredToolMetadataDoesNotBreakTheInventoryOrInventToolCounts() {
        val result =
            OkHttpProvider.json.decodeFromString<McpServersResponse>(
                """{"servers":[{"name":"google_workspace","enabled":true,"tools":{"include":["list_calendars"],"exclude":[]}}]}""",
            )
        assertEquals(1, result.servers.size)
        assertNull(result.servers.single().tools)
        assertEquals(
            ServiceConnectionState.CONFIGURED,
            serviceConnectionState(
                externalService("calendar")!!,
                ExternalServicesState(loading = false, servers = result.servers),
            ),
        )
    }

    @Test fun discoveredNameListsRemainAvailable() {
        val result =
            OkHttpProvider.json.decodeFromString<McpServersResponse>(
                """{"servers":[{"name":"test","enabled":true,"tools":["search","read"]}]}""",
            )
        assertEquals(listOf("search", "read"), result.servers.single().tools)
    }

    @Test fun nullableAndOmittedToolMetadataRemainUnknown() {
        listOf("", ",\"tools\":null").forEach { suffix ->
            val result =
                OkHttpProvider.json.decodeFromString<McpServersResponse>(
                    """{"servers":[{"name":"test","enabled":true$suffix}]}""",
                )
            assertNull(result.servers.single().tools)
        }
    }

    @Test fun invalidScalarToolMetadataStillFailsExplicitly() {
        assertThrows(SerializationException::class.java) {
            OkHttpProvider.json.decodeFromString<McpServersResponse>(
                """{"servers":[{"name":"test","enabled":true,"tools":123}]}""",
            )
        }
    }
}
