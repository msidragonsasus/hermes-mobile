package com.m57.hermescontrol.vault

import com.m57.hermescontrol.data.local.StorageStartup
import com.m57.hermescontrol.data.local.StorageStartupState
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only handshake against the configured server; no prompt/model/vault write. */
class VaultLiveCompatibilityTest {
    @Test
    fun installedServerAcceptsMaskedPromptCapability() =
        runBlocking {
            val storage = withTimeout(20_000) { StorageStartup.state.first { it != StorageStartupState.Loading } }
            assertTrue("Existing encrypted app storage must remain readable", storage == StorageStartupState.Ready)
            HermesWsClient.disconnect()
            val response =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(20_000) {
                        HermesWsClient.events.first {
                            (it is WsEvent.RpcResult && it.id.startsWith("mobile-capabilities-")) ||
                                (it is WsEvent.RpcError && it.id.startsWith("mobile-capabilities-"))
                        }
                    }
                }
            try {
                HermesWsClient.connect()
                val event = response.await()
                assertTrue("Server must accept the masked prompt handshake", event is WsEvent.RpcResult)
                val methods = ((event as WsEvent.RpcResult).result as? Map<*, *>)?.get("server_requests") as? List<*>
                assertTrue("Server must support site-login requests", methods?.contains("vault.save_login") == true)
            } finally {
                HermesWsClient.disconnect()
                response.cancel()
            }
        }
}
