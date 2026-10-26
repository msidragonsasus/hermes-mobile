package com.m57.hermescontrol.ui.connections

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opted-in metadata read; never reports URLs, identities, secrets, or response bodies. */
class ExternalServicesInstalledReadTest {
    @Test fun authenticatedMcpInventoryIsAvailable() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("readInstalledConnections") == "true")
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            AuthManager.init(instrumentation.targetContext)
            AuthManager.getToken()
            withTimeout(40_000) {
                val response = ApiClient.hermesApi.getMcpServers()
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putInt("mcp_http_status", response.code())
                        putInt("mcp_server_count", response.body()?.servers?.size ?: -1)
                        putString("provider_authorization_started", "false")
                    },
                )
                assertTrue(response.isSuccessful && response.body() != null)
            }
        }
}
