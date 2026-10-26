package com.m57.hermescontrol.ui.mcp

import com.m57.hermescontrol.data.model.ActionStatusResponse
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpCatalogEnvVar
import com.m57.hermescontrol.data.model.McpCatalogInstallRequest
import com.m57.hermescontrol.data.model.McpCatalogInstallResponse
import com.m57.hermescontrol.data.model.McpCatalogResponse
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.McpServersResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.ui.connections.externalService
import com.m57.hermescontrol.ui.connections.matches
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class McpCatalogConnectionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: HermesApiService
    private lateinit var model: McpServersViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient)
        api = mockk()
        every { ApiClient.hermesApi } returns api
        coEvery { api.getMcpServers() } returns Response.success(McpServersResponse(emptyList()))
        coEvery { api.getMcpCatalog() } returns Response.success(McpCatalogResponse(emptyList()))
        model = McpServersViewModel()
    }

    @After fun teardown() {
        model.clearConnectionForm()
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test fun backendRequiredFieldsAndInstallationStateAreNotLost() {
        val catalog =
            OkHttpProvider.json.decodeFromString<McpCatalogResponse>(
                """{"entries":[{"name":"notion","auth_type":"oauth","installed":true,"enabled":false,
                "required_env":[{"name":"CLIENT_SECRET","prompt":"Client secret","required":true}],
                "post_install":"Choose an account"}]}""",
            )
        val entry = catalog.entries.single()
        assertEquals("CLIENT_SECRET", entry.env!!.single().key)
        assertEquals("Client secret", entry.env.single().label)
        assertTrue(entry.env.single().required)
        assertTrue(entry.env.single().secret)
        assertTrue(entry.installed)
        assertFalse(entry.enabled)
        assertEquals("oauth", entry.authType)
        assertEquals("Choose an account", entry.postInstall)
    }

    @Test fun legacyCatalogFieldsStillDecode() {
        val entry =
            OkHttpProvider.json.decodeFromString<McpCatalogEntry>(
                """{"name":"notion","env":[{"key":"URL","label":"URL","secret":false}]}""",
            )
        assertEquals("URL", entry.env!!.single().key)
        assertFalse(entry.env.single().secret)
        assertFalse(entry.installed)
    }

    @Test fun catalogIdentityDoesNotMergeGmailOrGuessServicesFromDescriptions() {
        val shared = McpCatalogEntry("google_workspace")
        assertTrue(externalService("calendar")!!.matches(shared))
        assertTrue(externalService("drive")!!.matches(shared))
        assertFalse(externalService("gmail")!!.matches(shared))
        assertFalse(externalService("slack")!!.matches(McpCatalogEntry("other", description = "slack")))
    }

    @Test fun browsingAndPreparingAServiceNeverInstallOrAuthorize() =
        runTest(dispatcher) {
            model.prepareConnection("notion")
            model.loadCatalog()
            advanceUntilIdle()
            assertFalse(model.uiState.value.showAddForm)
            coVerify(exactly = 0) { api.installMcpCatalogEntry(any()) }
            coVerify(exactly = 0) { api.authMcpServer(any()) }
        }

    @Test fun missingRequiredCredentialDoesNotSendAnInstallationRequest() =
        runTest(dispatcher) {
            val entry = McpCatalogEntry("slack", env = listOf(McpCatalogEnvVar("KEY", required = true)))
            model.installCatalogEntry(entry)
            advanceUntilIdle()
            coVerify(exactly = 0) { api.installMcpCatalogEntry(any()) }
            coVerify(exactly = 0) { api.getMcpServers() }
            assertNull(model.uiState.value.installingCatalogEntry)
        }

    @Test fun credentialsBelongToOneEntryAndAreClearedOnExit() =
        runTest(dispatcher) {
            val first = McpCatalogEntry("slack", env = listOf(McpCatalogEnvVar("SLACK_KEY")))
            val second = McpCatalogEntry("notion", env = listOf(McpCatalogEnvVar("NOTION_KEY")))
            coEvery { api.getMcpCatalog() } returns Response.success(McpCatalogResponse(listOf(first, second)))
            model.loadCatalog()
            advanceUntilIdle()
            model.beginCatalogSetup(first)
            model.updateCatalogEnvVar("SLACK_KEY", "first-secret")
            model.beginCatalogSetup(second)
            assertTrue(
                model.uiState.value.catalogInstallEnv
                    .isEmpty(),
            )
            model.updateCatalogEnvVar("SLACK_KEY", "wrong-provider")
            assertTrue(
                model.uiState.value.catalogInstallEnv
                    .isEmpty(),
            )
            model.updateCatalogEnvVar("NOTION_KEY", "second-secret")
            model.clearConnectionForm()
            assertTrue(
                model.uiState.value.catalogInstallEnv
                    .isEmpty(),
            )
            assertNull(model.uiState.value.catalogSetupEntry)
        }

    @Test fun concurrentInstallIsNotOverwrittenWhenTheCatalogIsStale() =
        runTest(dispatcher) {
            coEvery { api.getMcpServers() } returns
                Response.success(
                    McpServersResponse(listOf(McpServer("notion", enabled = false))),
                )
            model.installCatalogEntry(McpCatalogEntry("notion"))
            advanceUntilIdle()
            coVerify(exactly = 0) { api.installMcpCatalogEntry(any()) }
            coVerify(exactly = 0) { api.authMcpServer(any()) }
            assertFalse(
                model.uiState.value.servers
                    .single()
                    .enabled,
            )
        }

    @Test fun doubleTapSendsOneRequestAndOnlyTheSelectedCredentialFields() =
        runTest(dispatcher) {
            val entry = McpCatalogEntry("notion", env = listOf(McpCatalogEnvVar("NOTION_KEY", required = true)))
            coEvery { api.getMcpCatalog() } returns Response.success(McpCatalogResponse(listOf(entry)))
            model.loadCatalog()
            advanceUntilIdle()
            model.beginCatalogSetup(entry)
            model.updateCatalogEnvVar("NOTION_KEY", "secret")
            model.updateCatalogEnvVar("OTHER_KEY", "unrelated")
            val request = slot<McpCatalogInstallRequest>()
            val gate = CompletableDeferred<Unit>()
            coEvery { api.installMcpCatalogEntry(capture(request)) } coAnswers {
                gate.await()
                Response.success(McpCatalogInstallResponse(ok = true, name = "notion"))
            }
            model.installCatalogEntry(entry)
            model.installCatalogEntry(entry)
            runCurrent()
            coVerify(exactly = 1) { api.installMcpCatalogEntry(any()) }
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(mapOf("NOTION_KEY" to "secret"), request.captured.env)
            assertTrue(
                model.uiState.value.catalogInstallEnv
                    .isEmpty(),
            )
            coVerify(exactly = 0) { api.authMcpServer(any()) }
        }

    @Test fun uncertainPostIsNotRetriedOrEchoed() =
        runTest(dispatcher) {
            coEvery { api.installMcpCatalogEntry(any()) } returns
                Response.error(
                    500,
                    "{\"detail\":\"private-secret\"}".toResponseBody(),
                )
            val entry = McpCatalogEntry("notion")
            model.installCatalogEntry(entry)
            advanceUntilIdle()
            model.installCatalogEntry(entry)
            advanceUntilIdle()
            coVerify(exactly = 1) { api.installMcpCatalogEntry(any()) }
            assertTrue("notion" in model.uiState.value.unconfirmedCatalogEntries)
            assertFalse(
                model.uiState.value.toastMessage
                    .orEmpty()
                    .contains("private-secret"),
            )
        }

    @Test fun backgroundPreparationWaitsForTheActualExitAndDoesNotAuthorizeAutomatically() =
        runTest(dispatcher) {
            coEvery { api.installMcpCatalogEntry(any()) } returns
                Response.success(
                    McpCatalogInstallResponse(
                        ok = true,
                        name = "notion",
                        background = true,
                        action = "mcp-install-notion",
                    ),
                )
            coEvery { api.getActionStatus("mcp-install-notion") } returnsMany
                listOf(
                    Response.success(ActionStatusResponse(name = "mcp-install-notion", running = true)),
                    Response.success(ActionStatusResponse(name = "mcp-install-notion", running = false, exit_code = 0)),
                )
            model.installCatalogEntry(McpCatalogEntry("notion"))
            runCurrent()
            assertEquals("notion", model.uiState.value.installingCatalogEntry)
            assertNull(model.uiState.value.lastAddedServerName)
            advanceTimeBy(1_500)
            runCurrent()
            assertNull(model.uiState.value.lastAddedServerName)
            advanceUntilIdle()
            assertEquals("notion", model.uiState.value.lastAddedServerName)
            assertNull(model.uiState.value.installingCatalogEntry)
            coVerify(exactly = 1) { api.installMcpCatalogEntry(any()) }
            coVerify(exactly = 0) { api.authMcpServer(any()) }
        }
}
