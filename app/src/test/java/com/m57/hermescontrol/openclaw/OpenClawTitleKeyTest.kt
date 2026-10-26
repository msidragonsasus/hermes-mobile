package com.m57.hermescontrol.openclaw

import com.m57.hermescontrol.data.model.EnvVarConfig
import com.m57.hermescontrol.data.model.EnvVarRevealResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class OpenClawTitleKeyTest {
    @Test
    fun disabledTitlesNeverReadServerSecretsOrUseALocalKey() =
        runBlocking {
            for (key in listOf(null, "local")) {
                assertNull(resolveClawTitleKey(key, { true }, enabled = false) { error("must not read server") })
            }
        }

    @Test
    fun explicitKeyWinsWithoutReadingServerSecrets() =
        runBlocking {
            assertEquals("local", resolveClawTitleKey("local", { true }) { error("must not read server") })
        }

    @Test
    fun savedServerKeyIsUsedOnlyForTheOriginalOwner() =
        runBlocking {
            assertEquals("existing", resolveClawTitleKey(null, { true }) { "existing" })
            var current = true
            assertNull(
                resolveClawTitleKey(null, { current }) {
                    current = false
                    "old-owner-key"
                },
            )
            assertNull(resolveClawTitleKey(null, { false }) { error("must not fetch for an old owner") })
        }

    @Test
    fun missingServerKeyDoesNotRevealAnythingAndMismatchedResponsesAreRejected() =
        runBlocking {
            mockkObject(ApiClient)
            val api = mockk<HermesApiService>()
            every { ApiClient.hermesApi } returns api
            try {
                coEvery { api.getEnvVars() } returns Response.success(emptyMap())
                assertNull(existingOpenRouterTitleKey())
                coVerify(exactly = 0) { api.revealEnvVar(any()) }
                coEvery { api.getEnvVars() } returns
                    Response.success(mapOf("OPENROUTER_API_KEY" to EnvVarConfig(isSet = true, isPassword = true)))
                coEvery { api.revealEnvVar(any()) } returns
                    Response.success(EnvVarRevealResponse("OTHER_KEY", "wrong-key"))
                assertNull(existingOpenRouterTitleKey())
                coEvery { api.revealEnvVar(any()) } returns
                    Response.success(EnvVarRevealResponse("OPENROUTER_API_KEY", "existing"))
                assertEquals("existing", existingOpenRouterTitleKey())
            } finally {
                unmockkObject(ApiClient)
            }
        }
}
