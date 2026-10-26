package com.m57.hermescontrol.ui.connections

import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.ui.mcp.McpServersViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalServicesTest {
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun gmailAuthorizationNeverConnectsCalendarOrDrive() {
        val state =
            ExternalServicesState(loading = false, gmail = GoogleConnectionStatus("connected", "mail@example.test"))
        assertEquals(ServiceConnectionState.CONNECTED, serviceConnectionState(externalService("gmail")!!, state))
        listOf("calendar", "drive").forEach {
            assertEquals(ServiceConnectionState.NOT_CONFIGURED, serviceConnectionState(externalService(it)!!, state))
        }
    }

    @Test fun enabledMcpIsConfigurationAndNeverProviderAuthorization() {
        val state =
            ExternalServicesState(
                loading = false,
                servers = listOf(McpServer("slack", enabled = true, status = "connected")),
            )
        assertEquals(ServiceConnectionState.CONFIGURED, serviceConnectionState(externalService("slack")!!, state))
    }

    @Test fun sharedWorkspaceConnectionRemainsExplicitlyShared() {
        val server = McpServer("google_workspace", enabled = true)
        assertTrue(externalService("calendar")!!.matches(server))
        assertTrue(externalService("drive")!!.matches(server))
        assertFalse(externalService("gmail")!!.matches(server))
    }

    @Test fun namesCannotMatchFromSecretsOrArbitrarySubstrings() {
        val service = externalService("slack")!!
        assertFalse(
            service.matches(
                McpServer(
                    "not-slack",
                    enabled = true,
                    url = "https://slack.com",
                    env =
                        mapOf("KEY" to "slack"),
                ),
            ),
        )
        assertTrue(service.matches(McpServer("SLACK_MCP", enabled = true)))
    }

    @Test fun mixedGmailAccountsRequireAttention() {
        val state =
            ExternalServicesState(
                loading = false,
                gmail =
                    GoogleConnectionStatus(
                        "connected",
                        accounts =
                            listOf(
                                GoogleConnectedAccount("a", "a@example.test"),
                                GoogleConnectedAccount("b", "b@example.test", state = "reauth_required"),
                            ),
                    ),
            )
        assertEquals(ServiceConnectionState.NEEDS_ATTENTION, serviceConnectionState(externalService("gmail")!!, state))
    }

    @Test fun disabledOrFailedConnectionIsNotReady() {
        val service = externalService("figma")!!
        assertEquals(
            ServiceConnectionState.DISABLED,
            serviceConnectionState(
                service,
                ExternalServicesState(
                    loading = false,
                    servers = listOf(McpServer("figma", enabled = false, status = "connected")),
                ),
            ),
        )
        assertEquals(
            ServiceConnectionState.NEEDS_ATTENTION,
            serviceConnectionState(
                service,
                ExternalServicesState(
                    loading = false,
                    servers = listOf(McpServer("figma", enabled = true, error = "authorization required")),
                ),
            ),
        )
    }

    @Test fun unavailableInventoryDoesNotMeanUnconfigured() {
        assertEquals(
            ServiceConnectionState.UNKNOWN,
            serviceConnectionState(
                externalService("dropbox")!!,
                ExternalServicesState(loading = false, mcpError = true),
            ),
        )
    }

    @Test fun endpointSummaryExcludesAllCredentialBearingParts() {
        assertEquals("example.test", connectionHost("https://user:password@example.test/private?token=secret#code"))
        assertNull(connectionHost("not a url"))
        assertNull(connectionHost("file:///private"))
    }

    @Test fun oneUnavailableSourceDoesNotHideTheOther() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository =
                object : ExternalServicesRepository {
                    override suspend fun servers(): List<McpServer> = error("private provider detail")

                    override suspend fun gmail() = GoogleConnectionStatus("connected", "mail@example.test")
                }
            val model = ExternalServicesViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            assertFalse(model.state.value.loading)
            assertTrue(model.state.value.mcpError)
            assertFalse(model.state.value.gmailError)
            assertEquals(
                ServiceConnectionState.CONNECTED,
                serviceConnectionState(externalService("gmail")!!, model.state.value),
            )
            assertFalse(
                model.state.value
                    .toString()
                    .contains("private provider detail"),
            )
        }

    @Test fun failedRefreshDoesNotLeaveStaleAccountsConnected() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var fail = false
            val repository =
                object : ExternalServicesRepository {
                    override suspend fun servers() = emptyList<McpServer>()

                    override suspend fun gmail(): GoogleConnectionStatus {
                        if (fail) error("unavailable")
                        return GoogleConnectionStatus("connected", "mail@example.test")
                    }
                }
            val model = ExternalServicesViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            fail = true
            model.refresh()
            advanceUntilIdle()
            assertNull(model.state.value.gmail)
            assertEquals(
                ServiceConnectionState.UNKNOWN,
                serviceConnectionState(externalService("gmail")!!, model.state.value),
            )
        }

    @Test fun refreshDoesNotStartDuplicateReads() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val gate = CompletableDeferred<Unit>()
            var reads = 0
            val repository =
                object : ExternalServicesRepository {
                    override suspend fun servers(): List<McpServer> {
                        reads++
                        gate.await()
                        return emptyList()
                    }

                    override suspend fun gmail() = GoogleConnectionStatus("disconnected")
                }
            val model = ExternalServicesViewModel(repository)
            model.refresh()
            model.refresh()
            runCurrent()
            assertEquals(1, reads)
            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test fun returningToConnectionSettingsPreservesDraftButClearsSecrets() {
        val model = McpServersViewModel()
        model.prepareConnection("slack")
        model.updateAddServerName("slack-personal")
        model.updateAddServerUrl("https://example.test/mcp")
        model.updateAddServerBearerToken("secret")
        model.clearConnectionForm()
        model.prepareConnection("slack")
        assertEquals("slack-personal", model.uiState.value.addServerName)
        assertEquals("https://example.test/mcp", model.uiState.value.addServerUrl)
        assertEquals("", model.uiState.value.addServerBearerToken)
    }

    @Test fun actionsComeOnlyFromEnabledMatchedConnectionsWithoutDuplicates() {
        val servers =
            listOf(
                McpServer("slack", enabled = true, tools = listOf("slack_get_reactions", "slack_list_channel_members")),
                McpServer("slack-mcp", enabled = true, tools = listOf("slack_get_reactions", " ")),
                McpServer("slack_old", enabled = false, tools = listOf("slack_post_message")),
            )
        assertEquals(listOf("slack_get_reactions", "slack_list_channel_members"), serviceActions(servers))
        assertEquals("Slack get reactions", actionLabel("slack_get_reactions"))
        assertEquals("Search files", actionLabel("search-files"))
    }

    @Test fun connectPlanStartsOnlyWhatTheServerReports() {
        val calendar = externalService("calendar")!!
        val stdio = McpServer("google-workspace", enabled = true, command = "uvx")
        assertEquals(ServiceConnectPlan.ServerManaged(stdio), serviceConnectPlan(calendar, listOf(stdio), emptyList()))
        assertTrue(serviceConnectPlan(calendar, emptyList(), emptyList()) is ServiceConnectPlan.Unavailable)
        val figma = externalService("figma")!!
        val oauth = McpServer("figma", enabled = true, auth = "oauth", url = "https://mcp.figma.com/mcp")
        assertEquals(ServiceConnectPlan.SignIn(oauth), serviceConnectPlan(figma, listOf(oauth), emptyList()))
        val entry = com.m57.hermescontrol.data.model.McpCatalogEntry("figma", authType = "oauth")
        assertEquals(ServiceConnectPlan.Install(entry, true), serviceConnectPlan(figma, emptyList(), listOf(entry)))
        assertEquals(ServiceConnectPlan.GoogleAccount, serviceConnectPlan(externalService("gmail")!!, emptyList(), emptyList()))
        assertEquals("Figmaに進む", proceedLabel(figma, ServiceConnectPlan.SignIn(oauth)))
        assertNull(proceedLabel(calendar, ServiceConnectPlan.ServerManaged(stdio)))
    }
}
