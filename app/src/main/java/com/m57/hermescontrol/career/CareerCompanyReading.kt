package com.m57.hermescontrol.career

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.coroutines.launch
import java.net.URI

internal val CareerMailItem.readIdentity: String get() = "source:mail:$account_id:$message_id"

internal fun CareerMailItem.isUnread(readKeys: Set<String>): Boolean =
    gmail_unread && "career:$readIdentity" !in readKeys

@Composable
internal fun rememberCareerMailReadKeys(): Set<String> {
    val app = LocalContext.current.applicationContext as HermesControlApp
    val scope = AuthManager.uiPreferenceScope()
    val reads by remember(scope) { app.homeRepository.dao.reads(scope) }.collectAsState(emptyList())
    return reads.map { it.key }.toSet()
}

@Composable
internal fun CompanyCopyButton(
    value: String,
    label: String = "IDをコピー",
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value))) }
    }, enabled = value.isNotBlank()) { Text(label) }
}

@Composable
internal fun CompanyPortalActions(
    detail: CareerCompanyDetail,
    accounts: List<ShukatsuLoginAccount>,
    onSettings: () -> Unit,
) {
    val links =
        detail.links
            .filter { it.data.text("type") == "MyPage" }
            .map { it.data.text("label").orEmpty() to it.data.text("url").orEmpty() }
            .filter { validCompanyUrl(it.second) }
            .ifEmpty {
                detail.mypage
                    ?.portal_url
                    ?.takeIf(::validCompanyUrl)
                    ?.let { listOf("マイページ" to it) }
                    .orEmpty()
            }
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        if (links.isEmpty()) {
            Button(
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                shapes = ButtonDefaults.shapes(),
            ) { Text("マイページURLを設定") }
        }
        links.forEach { (label, url) ->
            Button(onClick = {
                com.m57.hermescontrol.NavigationController.navigateTo(
                    CareerMyPageBrowserKey(
                        detail.company.id,
                        detail.links.firstOrNull { it.data.text("type") == "MyPage" && it.data.text("url") == url }?.id,
                    ),
                )
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("company_open_mypage")) {
                Column(Modifier.padding(vertical = LocalSpacing.current.sm)) {
                    Text("マイページを開く", style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOf(label, URI(url).host)
                            .filter {
                                it.isNotBlank()
                            }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        accounts.forEach { account ->
            Text(account.accountLabel.ifBlank { "ログインID" }, style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(account.username, style = MaterialTheme.typography.bodyLarge) }
            CompanyCopyButton(account.username)
        }
    }
}

@Composable
internal fun CareerMailRow(
    mail: CareerMailItem,
    unread: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("mail_${mail.account_id}_${mail.message_id}")) {
        Column(
            Modifier.padding(LocalSpacing.current.md),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
        ) {
            Text(
                mail.subject.ifBlank { "（件名なし）" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
            )
            if (unread) {
                Text(
                    "未読",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(mail.from_text, style = MaterialTheme.typography.bodySmall)
            Text(careerDisplayTime(mail.received_at), style = MaterialTheme.typography.bodySmall)
            Text(
                mail.snippet.ifBlank { mail.body.take(180) },
                maxLines = 2,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareerMailBodySheet(
    mail: CareerMailItem,
    onDismiss: () -> Unit,
    onConsult: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val body = mail.body.ifBlank { mail.snippet }
    if (mail.body.isNotBlank()) CareerHomeReadEffect(mail.readIdentity, mail.subject + "\n" + mail.body)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalSpacing.current.lg),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
        ) {
            Text(mail.subject.ifBlank { "（件名なし）" }, style = MaterialTheme.typography.titleLarge)
            Text(mail.from_text, style = MaterialTheme.typography.bodyMedium)
            Text(careerDisplayTime(mail.received_at), style = MaterialTheme.typography.bodySmall)
            FlowRow {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("閉じる") }
                CompanyCopyButton(body, "本文をコピー")
                if (validCompanyUrl(
                        mail.locator,
                    )
                ) {
                    TextButton(
                        onClick = { uri.openUri(mail.locator) },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("Gmailで開く") }
                }
            }
            HorizontalDivider()
            if (mail.body.isBlank()) Text("本文は取得できていません。Gmailで確認できます。")
            SelectionContainer(Modifier.testTag("career_mail_body")) {
                Text(linkedMailBody(body), style = MaterialTheme.typography.bodyLarge)
            }
            CareerSourceResolution(mail.readIdentity)
            TextButton(onClick = onConsult, shapes = ButtonDefaults.shapes()) { Text("この内容について相談") }
        }
    }
}

@Composable
internal fun linkedMailBody(body: String): AnnotatedString {
    val uri = LocalUriHandler.current
    val style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    return AnnotatedString
        .Builder()
        .apply {
            var cursor = 0
            Regex("https?://[^\\s<>\"']+").findAll(body).forEach { match ->
                val url = match.value.trimEnd('.', ',', '。', '、', ')', '）', ']', '】')
                append(body.substring(cursor, match.range.first))
                pushLink(LinkAnnotation.Url(url, TextLinkStyles(style = style)) { uri.openUri(url) })
                append(url)
                pop()
                cursor = match.range.first + url.length
            }
            append(body.substring(cursor))
        }.toAnnotatedString()
}
