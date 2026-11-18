package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import retrofit2.HttpException

/** Passwords never enter saved state, Room, draft queues or company caches. */
@Composable
internal fun CompanyMyPageSettings(
    detail: CareerCompanyDetail,
    onRefresh: () -> Unit,
    onDirty: (String, Boolean) -> Unit,
    repository: CareerCompanyRepository? = null,
    sharedLogin: String? = null,
    sharedAccountKey: String? = null,
    onSharedLoginChange: (String) -> Unit = {},
    inputEnabled: Boolean = true,
    loginChoices: @Composable (Boolean) -> Unit = {},
    onBusyChanged: (Boolean) -> Unit = {},
    onCredentialsSaved: suspend () -> Unit = {},
    onPortalUrlSaved: (String) -> Unit = {},
    onShowResults: () -> Unit = {},
    sharedLoginReadKey: String? = null,
    onFormEdit: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true } }
    val client = repository ?: remember(detail.company.id) { CareerCompanyRepository() }
    var savedConnection by remember(detail.company.id) { mutableStateOf<CareerMyPage?>(null) }
    val connection =
        savedConnection?.takeIf { it.connection_version > (detail.mypage?.connection_version ?: 0) }
            ?: detail.mypage
    val pendingUrl = connection?.pending_request?.text("portal_url")
    var url by rememberSaveable(detail.company.id, connection?.connection_version, pendingUrl) {
        mutableStateOf(
            pendingUrl
                ?: connection?.portal_url
                ?: detail.links
                    .firstOrNull { it.data.text("type") == "MyPage" }
                    ?.data
                    ?.text("url")
                    .orEmpty(),
        )
    }
    var loginDraft by rememberSaveable(detail.company.id) { mutableStateOf("") }
    val login = sharedLogin ?: loginDraft
    // A saved-account read may arrive after this form. Draft edits and manual account selection keep this key.
    // The parent retains its login draft when a lazy item leaves composition. Retain the
    // comparison baseline too, so returning to the form cannot hide Save for that draft.
    var originalSharedLogin by rememberSaveable(detail.company.id, sharedLoginReadKey) { mutableStateOf(sharedLogin) }
    val loginEdited = sharedLogin != originalSharedLogin
    var password by remember(detail.company.id, sharedAccountKey) { mutableStateOf("") }
    val pendingEnabled =
        connection
            ?.pending_request
            ?.get("enabled")
            ?.jsonPrimitive
            ?.booleanOrNull
    var enabled by rememberSaveable(detail.company.id, connection?.connection_version, pendingUrl) {
        mutableStateOf(pendingEnabled ?: (connection?.automation == "enabled" || connection?.connection_version == 0))
    }
    // A pending coach request is not saved yet: compare against the server's saved values.
    var savedUrl by rememberSaveable(detail.company.id, connection?.connection_version) {
        mutableStateOf(connection?.portal_url?.trim() ?: url.trim())
    }
    var savedEnabled by rememberSaveable(detail.company.id, connection?.connection_version) {
        mutableStateOf(connection?.automation == "enabled" || connection?.connection_version == 0)
    }
    var busy by remember(detail.company.id) { mutableStateOf(false) }
    var saving by remember(detail.company.id) { mutableStateOf(false) }
    var localLoginSavePending by rememberSaveable(detail.company.id) { mutableStateOf(false) }
    var feedback by remember(detail.company.id) { mutableStateOf<String?>(null) }
    // Server execution does not make a form unsaved. Keep the credential write guard separate.
    val edited = url.trim() != savedUrl || enabled != savedEnabled || password.isNotEmpty() || loginDraft.isNotEmpty()
    LaunchedEffect(detail.company.id, edited, loginEdited, saving) {
        onDirty("mypage-bot", edited || loginEdited)
        onDirty("mypage-saving", saving)
    }
    val spacing = LocalSpacing.current
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant
    // Only offered after a failure that asks the user to reload; not a permanent control.
    var reloadOffered by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("company_mypage_bot_settings")) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text("マイページ", style = MaterialTheme.typography.titleMedium)
            if (connection?.bot_available != true) {
                Text("この設定を使うには就活サーバーの更新が必要です。", color = secondaryText)
            } else {
                Text(
                    myPageConnectionSummary(connection),
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryText,
                    modifier = Modifier.testTag("mypage_connection_summary"),
                )
                CompanyMyPageListingApproval(
                    connection = connection,
                    companyName = detail.company.data.text("name") ?: "この企業",
                    busy = busy || edited || loginEdited,
                    onDecision = { decision, proposalId ->
                        busy = true
                        feedback = null
                        scope.launch {
                            try {
                                val result =
                                    client.mutate(
                                        "mypage-listings",
                                        buildJsonObject {
                                            put("company_id", detail.company.id)
                                            put("decision", decision)
                                            put("expected_version", connection.connection_version)
                                            proposalId?.let { put("proposal_id", it) }
                                        },
                                    )
                                savedConnection = json.decodeFromJsonElement(result.getValue("mypage"))
                                feedback = myPageListingDecisionMessage(decision)
                                onRefresh()
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                reloadOffered = true
                                feedback = myPageListingDecisionError(error)
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
                if (myPageListingSectionVisible(connection)) HorizontalDivider()
                if (pendingUrl != null) {
                    Text(
                        "コーチに依頼された接続先です。URLを確認し、ログインIDとパスワードを入力して保存すると設定されます。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("mypage_pending_request"),
                    )
                }
                val urlChanged = connection.connection_version > 0 && url.trim() != connection.portal_url
                val needsCredentials = connection.connection_version == 0 || urlChanged
                HermesTextField(
                    value = url,
                    onValueChange = {
                        onFormEdit()
                        url = it
                    },
                    label = { Text("マイページURL（ログイン画面）") },
                    modifier = Modifier.fillMaxWidth().testTag("mypage_login_url"),
                    enabled = !busy && inputEnabled,
                    singleLine = true,
                )
                loginChoices(!busy)
                HermesTextField(
                    value = login,
                    onValueChange = {
                        onFormEdit()
                        if (sharedLogin == null) loginDraft = it else onSharedLoginChange(it)
                    },
                    label = { Text("ログインID") },
                    modifier =
                        Modifier.fillMaxWidth().testTag("mypage_login_id").semantics {
                            contentType = ContentType.Username
                        },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    enabled = !busy && inputEnabled,
                    singleLine = true,
                )
                HermesTextField(
                    value = password,
                    onValueChange = {
                        onFormEdit()
                        password = it
                    },
                    label = { Text("パスワード") },
                    supportingText = {
                        if (needsCredentials && (login.isBlank() || password.isBlank())) {
                            Text(
                                if (urlChanged) "URLを変えたため、ログインIDとパスワードが必要です" else "サーバーで暗号化して保存します",
                                modifier = Modifier.testTag("mypage_credentials_hint"),
                            )
                        } else if (!needsCredentials) {
                            Text("保存済みです。変更するときだけ入力します")
                        }
                    },
                    modifier =
                        Modifier.fillMaxWidth().testTag("mypage_password").semantics {
                            contentType = ContentType.Password
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy && inputEnabled,
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("毎日9時・18時に自動確認")
                        if (!connection.verified) {
                            Text("初回の確認に成功すると始まります", style = MaterialTheme.typography.bodySmall, color = secondaryText)
                        }
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        onFormEdit()
                        enabled = it
                    }, enabled = !busy && inputEnabled)
                }
                connection.schedule_error?.let {
                    Text(companyCollectionReason(it).orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                // One action at a time: save while there are changes, otherwise check now.
                val changed = edited || loginEdited || connection.connection_version == 0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End)) {
                    if (reloadOffered) {
                        TextButton(enabled = !busy && inputEnabled, onClick = {
                            reloadOffered = false
                            onRefresh()
                        }) { Text("保存した設定を読み直す") }
                    }
                    if (changed) {
                        Button(
                            enabled =
                                !busy && inputEnabled && validCompanyUrl(url) &&
                                    (sharedLogin == null || login.isNotBlank()) &&
                                    (password.isBlank() || login.isNotBlank()) &&
                                    (!needsCredentials || (login.isNotBlank() && password.isNotBlank())),
                            modifier = Modifier.testTag("mypage_save"),
                            onClick = {
                                busy = true
                                saving = true
                                onDirty("mypage-saving", true)
                                onBusyChanged(true)
                                feedback = null
                                reloadOffered = false
                                scope.launch {
                                    var acknowledged = false
                                    try {
                                        val result =
                                            client.mutate(
                                                "mypage-settings",
                                                buildJsonObject {
                                                    put("company_id", detail.company.id)
                                                    put("expected_version", connection.connection_version)
                                                    put("url", url.trim())
                                                    put("login_id", login)
                                                    put("password", password)
                                                    put("enabled", enabled)
                                                },
                                            )
                                        acknowledged = true
                                        loginDraft = ""
                                        password = ""
                                        savedUrl = url.trim()
                                        savedEnabled = enabled
                                        originalSharedLogin = sharedLogin
                                        onDirty("mypage-bot", false)
                                        savedConnection =
                                            json.decodeFromJsonElement(result.getValue("mypage"))
                                        onPortalUrlSaved(url.trim())
                                        feedback =
                                            try {
                                                onCredentialsSaved()
                                                localLoginSavePending = false
                                                "保存しました。「今すぐ確認」で確認を始められます。"
                                            } catch (error: Exception) {
                                                if (error is CancellationException) throw error
                                                localLoginSavePending = true
                                                "接続設定は保存しました。端末へのID保存に失敗しました。「端末にIDを保存」で再試行できます。"
                                            }
                                        onRefresh()
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        reloadOffered = true
                                        feedback =
                                            if (acknowledged) {
                                                "保存は受け付けましたが、表示を更新できません。「保存した設定を読み直す」を押してください。"
                                            } else {
                                                myPageSettingsSaveError(error)
                                            }
                                    } finally {
                                        busy = false
                                        saving = false
                                        onDirty("mypage-saving", false)
                                        onBusyChanged(false)
                                    }
                                }
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(if (busy) "保存中…" else "保存") }
                    } else if (connection.listing_proposal == null) {
                        Button(
                            enabled = !busy && inputEnabled,
                            modifier = Modifier.testTag("mypage_check"),
                            onClick = {
                                busy = true
                                feedback = "確認開始をサーバーに依頼しています。"
                                scope.launch {
                                    try {
                                        val accepted =
                                            client.mutate(
                                                "mypage-check",
                                                buildJsonObject { put("company_id", detail.company.id) },
                                            )
                                        feedback =
                                            if (accepted.text("run_id").isNullOrBlank()) {
                                                "確認依頼の受付状態を確認できません。募集・締切から最新の状態を確認してください。"
                                            } else {
                                                "サーバーで確認を受け付けました。この画面を離れても続行します。取得結果から進捗と案内を確認できます。"
                                            }
                                        onRefresh()
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        feedback = "確認結果を取得できません。募集・締切から最新の状態と理由を確認してください。"
                                        onRefresh()
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("今すぐ確認") }
                    }
                }
                if (localLoginSavePending) {
                    TextButton(
                        enabled = !busy && inputEnabled,
                        modifier = Modifier.testTag("mypage_retry_local_login"),
                        onClick = {
                            busy = true
                            saving = true
                            onDirty("mypage-saving", true)
                            onBusyChanged(true)
                            scope.launch {
                                try {
                                    onCredentialsSaved()
                                    localLoginSavePending = false
                                    feedback = "ログインIDを端末に保存しました。"
                                    onRefresh()
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    feedback = "端末へのID保存に失敗しました。「端末にIDを保存」で再試行できます。"
                                } finally {
                                    busy = false
                                    saving = false
                                    onDirty("mypage-saving", false)
                                    onBusyChanged(false)
                                }
                            }
                        },
                    ) { Text("端末にIDを保存") }
                }
                TextButton(
                    enabled = !saving,
                    onClick = onShowResults,
                    modifier = Modifier.testTag("mypage_show_results"),
                ) { Text("取得結果を見る") }
                feedback?.let { Text(it) }
            }
        }
    }
}

/** One line under the card title: where the connection stands, in the user's terms. */
internal fun myPageConnectionSummary(connection: CareerMyPage): String {
    val reason = companyCollectionReason(connection.status.text("reason"))
    return when {
        connection.connection_version == 0 -> "未設定です"
        connection.status.text("state") in setOf("queued", "running") -> "確認しています"
        connection.listing_proposal != null -> "確認する一覧の承認待ちです"
        reason != null -> reason
        connection.verified && connection.automation == "enabled" -> "確認済み・毎日9時と18時に自動確認します"
        connection.verified -> "確認済み・自動確認は止めています"
        else -> "まだ確認していません"
    }
}

internal fun myPageListingDecisionMessage(decision: String): String =
    when (decision) {
        "approve" -> "一覧を承認しました。「今すぐ確認」で案内の確認を始められます。"
        "reject" -> "提案を却下しました。次回の確認でBotが一覧を提案し直します。"
        else -> "次回の確認で一覧を学習し直します。"
    }

internal fun myPageListingDecisionError(error: Exception): String =
    when ((error as? HttpException)?.let(::careerErrorCode)) {
        "listing_proposal_changed", "version_conflict" -> "提案または接続設定が更新されています。「保存した設定を読み直す」で最新の提案を確認してください。"
        "listing_approval_required" -> "先に一覧の提案を承認または却下してください。"
        else -> "操作を受け付けられませんでした。「保存した設定を読み直す」で最新状態を確認してください。"
    }

internal fun myPageSettingsSaveError(error: Exception): String =
    if (error is HttpException) {
        when (careerErrorCode(error)) {
            "credentials_required_for_new_url", "authentication_required" -> {
                "ログインID・パスワードを両方入力してください。URL変更時は保存済みの認証情報を引き継げません。"
            }

            "version_conflict", "connection_changed" -> {
                "接続設定が更新されています。「保存した設定を読み直す」で最新版を確認してから保存してください。"
            }

            "invalid_portal_url", "unsafe_url" -> {
                "取得用ログインURLを確認してください。HTTPSのログイン画面を指定してください。"
            }

            "company_missing", "company_closed" -> {
                "この企業は取得対象ではありません。企業の登録・選考状況を確認してください。"
            }

            else -> {
                "サーバーが保存を受け付けませんでした。入力はこの画面内に保持しています。接続状態を確認してください。"
            }
        }
    } else {
        "保存結果を確認できません。「保存した設定を読み直す」で最新状態を確認してください。入力はこの画面内に保持しています。"
    }
