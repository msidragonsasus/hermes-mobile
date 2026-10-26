package com.m57.hermescontrol.ui.connections

import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpServer

/** What "〇〇に進む" does for one service. Decided only from what the server reports. */
internal sealed interface ServiceConnectPlan {
    /** Gmail: native Google account picker, then the server stores the grant. */
    data object GoogleAccount : ServiceConnectPlan

    /** Calendar/Drive: native Google account picker; the server stores it for Workspace MCP. */
    data object GoogleWorkspace : ServiceConnectPlan

    /** Slack: Hermes-managed Slack login in the browser. */
    data object SlackLogin : ServiceConnectPlan

    /** A registered HTTP connection with OAuth: open the provider's login. */
    data class SignIn(
        val server: McpServer,
    ) : ServiceConnectPlan

    /** A catalog entry can be installed first; [signInAfter] when it uses OAuth. */
    data class Install(
        val entry: McpCatalogEntry,
        val signInAfter: Boolean,
    ) : ServiceConnectPlan

    /** Already connected through a server-side connection that has no app login. */
    data class ServerManaged(
        val server: McpServer,
    ) : ServiceConnectPlan

    /** Nothing the app can start; [reason] says what is missing. */
    data class Unavailable(
        val reason: String,
    ) : ServiceConnectPlan
}

internal fun McpServer.usesOAuth(): Boolean = auth.equals("oauth", ignoreCase = true)

internal fun McpCatalogEntry.usesOAuth(): Boolean = authType.equals("oauth", ignoreCase = true)

internal fun serviceConnectPlan(
    service: ExternalService,
    servers: List<McpServer>,
    catalog: List<McpCatalogEntry>,
    catalogFailed: Boolean = false,
    googleWorkspaceReady: Boolean = false,
): ServiceConnectPlan {
    if (service.id == "gmail") return ServiceConnectPlan.GoogleAccount
    if (service.id in GOOGLE_WORKSPACE_SERVICES && googleWorkspaceReady) return ServiceConnectPlan.GoogleWorkspace
    if (service.id == "slack") return ServiceConnectPlan.SlackLogin
    val matched = servers.filter(service::matches)
    matched.firstOrNull { it.usesOAuth() }?.let { return ServiceConnectPlan.SignIn(it) }
    val entry =
        catalog.firstOrNull { service.matches(it) && !it.installed && servers.none { s -> s.name == it.name } }
    if (entry != null) return ServiceConnectPlan.Install(entry, entry.usesOAuth())
    matched.firstOrNull()?.let { return ServiceConnectPlan.ServerManaged(it) }
    return ServiceConnectPlan.Unavailable(
        if (catalogFailed) {
            "接続方法を取得できませんでした。再読込してください。"
        } else {
            "Hermesサーバーにこのサービスの接続方法が登録されていません。"
        },
    )
}
