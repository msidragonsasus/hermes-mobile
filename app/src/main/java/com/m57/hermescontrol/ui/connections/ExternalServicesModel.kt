package com.m57.hermescontrol.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Display metadata describes use cases, never grants or infers provider permissions. */
internal data class ExternalService(
    val id: String,
    val name: String,
    val summary: String,
    val examples: List<String>,
    val aliases: Set<String>,
)

internal val externalServices =
    listOf(
        ExternalService(
            "gmail",
            "Gmail",
            "メールを探す・内容を確認する",
            listOf("企業から届いたメールを確認", "必要なメールを探して要約"),
            setOf("gmail", "google-gmail"),
        ),
        ExternalService(
            "calendar",
            "Google カレンダー",
            "予定を調べる・日程を整理する",
            listOf("今週の予定を確認", "日程調整に必要な予定を調べる"),
            setOf("calendar", "google-calendar", "gcal", "google-workspace", "google-workspace-mcp"),
        ),
        ExternalService(
            "drive",
            "Google Drive",
            "ファイルを探す・資料を読み解く",
            listOf("関連する資料を検索", "文書を読んで要点を整理"),
            setOf("drive", "google-drive", "gdrive", "google-workspace", "google-workspace-mcp"),
        ),
        ExternalService(
            "figma",
            "Figma",
            "デザインを確認・実装の参考にする",
            listOf("指定したデザインの内容を確認", "画面の構成を実装の参考にする"),
            setOf("figma", "figma-mcp"),
        ),
        ExternalService(
            "slack",
            "Slack",
            "会話を検索・チームの情報を確認する",
            listOf("関連する会話を探す", "チャンネルの話題を整理"),
            setOf("slack", "slack-mcp"),
        ),
        ExternalService(
            "dropbox",
            "Dropbox",
            "ファイルを探す・内容を確認する",
            listOf("必要なファイルを検索", "保存した資料の内容を確認"),
            setOf("dropbox", "dropbox-mcp"),
        ),
        ExternalService(
            "github",
            "GitHub",
            "コード・Issue・変更内容を確認する",
            listOf("リポジトリのコードを調べる", "Issueやプルリクエストを確認"),
            setOf("github", "github-mcp"),
        ),
        ExternalService(
            "notion",
            "Notion",
            "ページを探す・ナレッジを整理する",
            listOf("関連するページを探す", "メモや議事録の要点を整理"),
            setOf("notion", "notion-mcp"),
        ),
    )

/** Match explicit names only. A URL, an env value, or an English description is not identity. */
internal fun ExternalService.matches(server: McpServer): Boolean = server.name.lowercase().replace('_', '-') in aliases

/** Catalog entries use the same explicit identity rules as configured connections. */
internal fun ExternalService.matches(entry: McpCatalogEntry): Boolean =
    entry.name.lowercase().replace('_', '-') in aliases

internal fun externalService(id: String): ExternalService? = externalServices.find { it.id == id }

internal data class ExternalServicesState(
    val loading: Boolean = true,
    val servers: List<McpServer> = emptyList(),
    val gmail: GoogleConnectionStatus? = null,
    val mcpError: Boolean = false,
    val gmailError: Boolean = false,
)

internal interface ExternalServicesRepository {
    suspend fun servers(): List<McpServer>

    suspend fun gmail(): GoogleConnectionStatus
}

internal class DashboardExternalServicesRepository(
    private val scope: String,
) : ExternalServicesRepository {
    private val api = ApiClient.hermesApi
    private val google = DashboardGoogleConnectionRepository(scope)

    override suspend fun servers(): List<McpServer> {
        check(scope == careerScope())
        val response = api.getMcpServers()
        check(scope == careerScope())
        check(response.isSuccessful)
        return requireNotNull(response.body()).servers
    }

    override suspend fun gmail(): GoogleConnectionStatus = google.status()
}

internal class ExternalServicesViewModel(
    private val repository: ExternalServicesRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExternalServicesState())
    val state = mutableState.asStateFlow()
    private var refreshing = false

    fun refresh() {
        if (refreshing) return
        refreshing = true
        // Old accounts must never be presented as currently connected after a failed refresh.
        mutableState.value = ExternalServicesState()
        viewModelScope.launch {
            try {
                val servers = async { read { repository.servers() } }
                val gmail = async { read { repository.gmail() } }
                val serverResult = servers.await()
                val gmailResult = gmail.await()
                mutableState.value =
                    ExternalServicesState(
                        loading = false,
                        servers = serverResult.getOrDefault(emptyList()),
                        gmail = gmailResult.getOrNull(),
                        mcpError = serverResult.isFailure,
                        gmailError = gmailResult.isFailure,
                    )
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun <T> read(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.failure(IllegalStateException("unavailable"))
        }
}

internal enum class ServiceConnectionState(
    val label: String,
) {
    CHECKING("確認中"),
    UNKNOWN("確認できません"),
    NEEDS_ATTENTION("確認が必要"),
    CONFIGURED("接続設定あり"),
    CONNECTED("接続済み"),
    DISABLED("無効"),
    NOT_CONFIGURED("未設定"),
}

internal fun serviceConnectionState(
    service: ExternalService,
    state: ExternalServicesState,
): ServiceConnectionState {
    if (state.loading) return ServiceConnectionState.CHECKING
    val servers = state.servers.filter(service::matches)
    if (service.id == "gmail" && !state.gmailError) {
        val google = state.gmail
        if (google?.accounts?.any { it.state != "connected" } == true || google?.state == "reauth_required") {
            return ServiceConnectionState.NEEDS_ATTENTION
        }
        if (google?.accounts?.any { it.state == "connected" } == true || google?.state == "connected") {
            return ServiceConnectionState.CONNECTED
        }
    }
    if (servers.any { it.enabled && (!it.error.isNullOrBlank() || it.status in setOf("error", "failed")) }) {
        return ServiceConnectionState.NEEDS_ATTENTION
    }
    if (servers.any { it.enabled }) return ServiceConnectionState.CONFIGURED
    if (servers.isNotEmpty()) return ServiceConnectionState.DISABLED
    if (state.mcpError || (service.id == "gmail" && state.gmailError)) return ServiceConnectionState.UNKNOWN
    return ServiceConnectionState.NOT_CONFIGURED
}

/** Never show credentials, URL parameters, paths, or fragments in a catalog card. */
internal fun connectionHost(url: String?): String? =
    runCatching {
        java.net
            .URI(url.orEmpty())
            .takeIf { it.scheme in setOf("http", "https") }
            ?.host
    }.getOrNull()

/** Tool names the matched connections reported, in server order, without duplicates. */
internal fun serviceActions(servers: List<McpServer>): List<String> =
    servers
        .filter { it.enabled }
        .flatMap { it.tools.orEmpty() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/** "slack_get_reactions" -> "Slack get reactions". Display only; the name is not interpreted. */
internal fun actionLabel(name: String): String =
    name
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .replaceFirstChar { it.uppercase() }
