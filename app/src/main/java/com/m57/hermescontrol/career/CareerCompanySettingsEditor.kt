package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal fun validCompanyUrl(value: String): Boolean =
    runCatching {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

internal fun LazyListScope.companySettingsEditors(
    detail: CareerCompanyDetail,
    accounts: List<ShukatsuLoginAccount>,
    state: CareerUiState,
    model: CareerViewModel,
    onRefresh: () -> Unit,
    onShowMyPageResults: () -> Unit = {},
    onDirty: (String, Boolean) -> Unit,
) {
    item(key = "company_basic_editor") {
        CompanyEntityEditor(detail.company, state, model, "基本情報", onRefresh, onDirty)
    }
    item(key = "company_login_editor") {
        Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md)) {
            CompanyLoginEditor(
                detail,
                accounts,
                onRefresh,
                onDirty,
                onPortalUrlSaved = { url -> syncMyPageLink(detail, model, url) },
                onShowResults = onShowMyPageResults,
            )
        }
    }
}

/**
 * The MyPage URL is the login URL: one field in the MyPage card. Its company link record
 * (used by the overview) follows the saved login URL instead of being edited separately.
 */
internal fun syncMyPageLink(
    detail: CareerCompanyDetail,
    model: CareerViewModel,
    url: String,
) {
    val link = detail.links.firstOrNull { it.data.text("type") == "MyPage" }
    if (link?.data?.text("url") == url) return
    val data =
        link?.data ?: buildJsonObject {
            put("company_id", detail.company.id)
            put("type", "MyPage")
            put("label", "マイページ")
        }
    model.send(
        "save",
        buildJsonObject {
            put("kind", "company_links")
            put("id", link?.id ?: ("link:" + UUID.randomUUID()))
            put("expected_version", link?.version ?: 0)
            put("data", JsonObject(data + ("url" to JsonPrimitive(url))))
        },
    )
}

/** Each section keeps its original version and draft until an acknowledged save is read back. */
@Composable
internal fun CompanyEntityEditor(
    entity: CareerEntity,
    state: CareerUiState,
    model: CareerViewModel,
    title: String,
    onSaved: () -> Unit,
    onDirty: (String, Boolean) -> Unit,
) {
    val fields =
        if (entity.kind == "companies") listOf("name", "official_urls") else listOf("url")

    fun values(data: JsonObject): JsonObject =
        buildJsonObject {
            fields.forEach { field ->
                put(
                    field,
                    if (field == "official_urls") {
                        (data[field] as? JsonArray).orEmpty().joinToString(
                            "\n",
                        ) { (it as? JsonPrimitive)?.content.orEmpty() }
                    } else if (field == "name") {
                        data.text("name", "title").orEmpty()
                    } else {
                        data.text(field).orEmpty()
                    },
                )
            }
        }
    var base by rememberSaveable(entity.id) { mutableStateOf(Json.encodeToString(entity)) }
    var original by rememberSaveable(entity.id) { mutableStateOf(values(entity.data).toString()) }
    var draft by rememberSaveable(entity.id) { mutableStateOf(original) }
    var submitted by rememberSaveable(entity.id) { mutableStateOf<String?>(null) }
    var feedback by rememberSaveable(entity.id) { mutableStateOf<String?>(null) }
    val baseline = Json.decodeFromString<CareerEntity>(base)
    val input = Json.decodeFromString<JsonObject>(draft)
    val snapshotEntity = state.snapshot?.entities?.firstOrNull { it.kind == entity.kind && it.id == entity.id }
    val latest = listOfNotNull(snapshotEntity, entity).maxBy { it.version }
    val dirty = draft != original || submitted != null
    LaunchedEffect(entity.id, dirty) { onDirty(entity.id, dirty) }
    LaunchedEffect(latest, state.pending, state.busy) {
        if (submitted != null && state.pending == null && !state.busy && latest.version > baseline.version &&
            values(latest.data).toString() == submitted
        ) {
            base = Json.encodeToString(latest)
            original = values(latest.data).toString()
            draft = original
            submitted = null
            onDirty(entity.id, false)
            feedback = "保存しました。"
            onSaved()
        } else if (!dirty && latest.version != baseline.version) {
            base = Json.encodeToString(latest)
            original = values(latest.data).toString()
            draft = original
        }
    }
    val valid =
        if (entity.kind == "companies") {
            input
                .text("name")
                .orEmpty()
                .trim()
                .length in 1..200 &&
                input
                    .text("official_urls")
                    .orEmpty()
                    .lines()
                    .filter { it.isNotBlank() }
                    .all { validCompanyUrl(it.trim()) }
        } else {
            validCompanyUrl(input.text("url").orEmpty().trim())
        }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(LocalSpacing.current.md),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            fields.forEach { field ->
                OutlinedTextField(
                    value = (input[field] as? JsonPrimitive)?.content.orEmpty(),
                    onValueChange = {
                        draft = input.with(field to JsonPrimitive(it)).toString()
                        feedback = null
                    },
                    enabled = submitted == null && !state.busy,
                    label = {
                        Text(
                            when (field) {
                                "name" -> "企業名"
                                "official_urls" -> "公式採用URL（1行に1件）"
                                else -> "マイページURL"
                            },
                        )
                    },
                    minLines = if (field == "notes") 3 else 1,
                    singleLine = field in listOf("name", "url"),
                    modifier = Modifier.fillMaxWidth().testTag("company_edit_$field"),
                )
            }
            if (!valid && dirty) {
                Text(
                    if (entity.kind ==
                        "companies"
                    ) {
                        "企業名と公式採用URLを確認してください。"
                    } else {
                        "HTTPSのマイページURLを入力してください。"
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (latest.version != baseline.version && dirty) {
                Text("保存先の内容が更新されています。入力を保持して最新版と比較できます。")
                Text("最新版：" + fields.joinToString("\n") { values(latest.data).text(it).orEmpty() })
                TextButton(
                    onClick = {
                        model.discardPendingAndThen {
                            val previous = Json.decodeFromString<JsonObject>(original)
                            val refreshed = values(latest.data)
                            draft =
                                JsonObject(
                                    refreshed +
                                        input.filter { (field, value) ->
                                            value != previous[field]
                                        },
                                ).toString()
                            original = refreshed.toString()
                            base = Json.encodeToString(latest)
                            submitted = null
                        }
                    },
                    enabled =
                        !state.busy && !state.stale &&
                            (state.pending == null || state.pending.payload.text("id") == entity.id),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("入力を保持して最新版を基準にする") }
            }
            feedback?.let { Text(it) }
            val ownPending = state.pending?.payload?.text("id") == entity.id
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (ownPending) {
                TextButton(
                    onClick = model::refresh,
                    enabled = !state.loading && !state.busy,
                    shapes = ButtonDefaults.shapes(),
                ) { Text("最新版を確認") }
                FlowRow {
                    TextButton(
                        onClick = model::retry,
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("保存結果を再確認") }
                    TextButton(onClick = {
                        model.discardPendingAndThen { submitted = null }
                    }, enabled = !state.busy) {
                        Text("入力に戻る")
                    }
                }
            }
            if (submitted != null && !state.busy && state.pending == null) {
                Text("保存結果を確認しています。入力は保持しています。")
                TextButton(onClick = model::refresh, shapes = ButtonDefaults.shapes()) { Text("保存情報を再読込") }
                if (state.error != null) TextButton(onClick = { submitted = null }) { Text("入力に戻る") }
            }
            if (dirty || ownPending) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = {
                            val updated = baseline.data.toMutableMap()
                            fields.forEach { field ->
                                val value = (input[field] as? JsonPrimitive)?.content.orEmpty()
                                updated[field] =
                                    if (field == "official_urls") {
                                        JsonArray(
                                            value
                                                .lines()
                                                .map(String::trim)
                                                .filter(String::isNotEmpty)
                                                .distinct()
                                                .map(::JsonPrimitive),
                                        )
                                    } else {
                                        JsonPrimitive(if (field == "notes") value else value.trim())
                                    }
                            }
                            submitted = values(JsonObject(updated)).toString()
                            model.send(
                                "save",
                                buildJsonObject {
                                    put("kind", entity.kind)
                                    put("id", entity.id)
                                    put("expected_version", baseline.version)
                                    put("data", JsonObject(updated))
                                },
                            )
                        },
                        enabled =
                            valid && submitted == null && !state.busy && !state.stale && state.enabled &&
                                state.pending == null,
                        modifier = Modifier.testTag("company_edit_save"),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(
                            if (state.busy &&
                                ownPending
                            ) {
                                "保存中…"
                            } else {
                                "保存"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompanyLoginEditor(
    detail: CareerCompanyDetail,
    accounts: List<ShukatsuLoginAccount>,
    onSaved: () -> Unit,
    onDirty: (String, Boolean) -> Unit,
    localStore: CareerLocalStore? = null,
    repository: CareerCompanyRepository? = null,
    onPortalUrlSaved: (String) -> Unit = {},
    onShowResults: () -> Unit = {},
    savedLoginSource: CareerBrowserCredentialSource? = null,
) {
    val token by AuthManager.tokenFlow.collectAsState()
    // Gated WS tickets rotate when returning from a password picker; the Dashboard session does not.
    // Keep the original autofill targets for ticket refreshes, while still dropping another session's drafts.
    // Only a one-way identity participates in Compose keys, never a raw token or cookie.
    val sessionIdentity =
        token?.takeIf(String::isNotBlank)?.let { value ->
            val dashboardSession =
                if (AuthManager.isGatedMode()) AuthManager.getSessionCookie()?.takeIf(String::isNotBlank) else null
            val identity = dashboardSession?.let { "dashboard:$it" } ?: "token:$value"
            MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    // Never restore one account's editor state under another gateway/profile/company/session.
    key(careerScope(), detail.company.id, sessionIdentity) {
        CompanyLoginEditorContent(
            detail,
            accounts,
            onSaved,
            onDirty,
            localStore,
            repository,
            onPortalUrlSaved,
            onShowResults,
            savedLoginSource,
        )
    }
}

@Composable
private fun CompanyLoginEditorContent(
    detail: CareerCompanyDetail,
    accounts: List<ShukatsuLoginAccount>,
    onSaved: () -> Unit,
    onDirty: (String, Boolean) -> Unit,
    localStore: CareerLocalStore?,
    repository: CareerCompanyRepository?,
    onPortalUrlSaved: (String) -> Unit,
    onShowResults: () -> Unit,
    savedLoginSource: CareerBrowserCredentialSource?,
) {
    val company = detail.company
    val context = LocalContext.current.applicationContext
    val connection = careerScope()
    val local = localStore ?: remember(connection) { CareerLocalStore(context, connection) }
    val credentialSource = savedLoginSource ?: remember(connection) { CareerBrowserCredentialSource(connection) }
    val savedConnection = detail.mypage
    val version = savedConnection?.connection_version ?: 0
    val portal = savedConnection?.portal_url
    var savedLogin by remember(company.id, version, portal) { mutableStateOf<String?>(null) }
    var readingLogin by remember(company.id, version, portal) { mutableStateOf(version > 0) }
    var loginReadFailed by remember(company.id, version, portal) { mutableStateOf(false) }
    var readAttempt by remember(company.id) { mutableStateOf(0) }
    LaunchedEffect(connection, company.id, version, portal, readAttempt) {
        if (version <= 0) return@LaunchedEffect
        readingLogin = true
        loginReadFailed = false
        try {
            val policy = CareerBrowserPolicy.create(portal)
            val result = policy?.let { credentialSource.savedLoginId(company.id, version, it) }
            if (connection != careerScope()) return@LaunchedEffect
            savedLogin = result
            loginReadFailed = result == null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            loginReadFailed = true
        } finally {
            readingLogin = false
        }
    }
    val availableAccounts = savedLoginAccounts(accounts, company.id, company.title, savedLogin)
    val availableAccountsJson = Json.encodeToString(availableAccounts)
    var observedAccounts by rememberSaveable(company.id) { mutableStateOf(availableAccountsJson) }
    var original by rememberSaveable(company.id) { mutableStateOf(availableAccountsJson) }
    var draft by rememberSaveable(company.id) { mutableStateOf(original) }
    var botBusy by remember { mutableStateOf(false) }
    var formEdited by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable(company.id) { mutableStateOf<String?>(null) }
    val newAccountId = rememberSaveable(company.id) { UUID.randomUUID().toString() }
    val rows = Json.decodeFromString<List<ShukatsuLoginAccount>>(draft)
    val selected =
        selectedLoginAccount(rows, selectedId, savedLogin)
            ?: if (rows.isEmpty()) {
                ShukatsuLoginAccount(newAccountId, company.id, company.title, "MyPage", "", "")
            } else {
                null
            }
    LaunchedEffect(draft, original) { onDirty("logins", draft != original) }
    LaunchedEffect(availableAccountsJson, botBusy, formEdited) {
        // Finishing a save must not reapply the unchanged, pre-save account list.
        // Still apply a delayed read once the user has no pending edits.
        if (availableAccountsJson != observedAccounts && draft == original && !botBusy && !formEdited) {
            if (selectedId == newAccountId && rows.isEmpty()) selectedId = null
            observedAccounts = availableAccountsJson
            original = availableAccountsJson
            draft = original
        }
    }
    CompanyMyPageSettings(
        detail,
        onSaved,
        onDirty = { section, dirty ->
            if (section == "mypage-bot") {
                formEdited = dirty
                if (dirty && selectedId == null) selectedId = selected?.sourceId
            }
            onDirty(section, dirty)
        },
        repository = repository,
        sharedLogin = selected?.username.orEmpty(),
        sharedLoginReadKey = original,
        sharedAccountKey = selected?.sourceId,
        onSharedLoginChange = { value ->
            selected?.let { account ->
                selectedId = account.sourceId
                val updated = account.copy(username = value)
                draft =
                    Json.encodeToString(
                        if (rows.isEmpty()) {
                            listOf(updated)
                        } else {
                            rows.map { if (it.sourceId == account.sourceId) updated else it }
                        },
                    )
            }
        },
        inputEnabled = selected != null,
        loginChoices = { enabled ->
            if (readingLogin) {
                Text("保存済みのログインIDを読み込んでいます。", Modifier.testTag("mypage_id_loading"))
            } else if (loginReadFailed && rows.isEmpty()) {
                // The server keeps the ID and password either way; only showing the ID here failed.
                // When this phone already shows an ID, the read failure changes nothing for the user.
                Text(
                    "IDとパスワードはサーバーに保存済みです。この画面にはIDを表示できませんでした。",
                    Modifier.testTag("mypage_id_read_failed"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(enabled = enabled, onClick = { readAttempt += 1 }) { Text("IDを再読込") }
            }
            if (rows.size > 1) {
                Text("取得に使うログインIDを選択してください。")
                rows.forEach { account ->
                    TextButton(
                        onClick = { selectedId = account.sourceId },
                        enabled = enabled,
                        modifier = Modifier.testTag("mypage_account_${account.sourceId}"),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(account.username + if (selected?.sourceId == account.sourceId) "（選択中）" else "") }
                }
            }
        },
        onFormEdit = {
            formEdited = true
            if (selectedId == null) selectedId = selected?.sourceId
        },
        onBusyChanged = { botBusy = it },
        onPortalUrlSaved = onPortalUrlSaved,
        onShowResults = onShowResults,
        onCredentialsSaved = {
            // Preserve all other accounts and metadata while saving the shared login.
            check(connection == careerScope())
            local.saveCompanyLoginAccounts(company.id, rows)
            original = Json.encodeToString(rows)
        },
    )
}
