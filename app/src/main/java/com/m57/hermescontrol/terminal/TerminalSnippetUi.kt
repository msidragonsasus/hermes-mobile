package com.m57.hermescontrol.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import com.m57.hermescontrol.ui.common.SectionHeader
import java.util.UUID

internal fun newTerminalSnippet(secret: Boolean) =
    TerminalSnippet(UUID.randomUUID().toString(), "", "", secret = secret)

internal fun newKeiyaPassword() =
    TerminalSnippet(UUID.randomUUID().toString(), "keiyaのパスワード", "", secret = true, role = KEIYA_PASSWORD_ROLE)

/**
 * Saved strings for this server and profile. Tapping one only places it in the editor;
 * sending stays an explicit action. Passwords are listed by label only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalSnippetSheet(
    state: TerminalSnippetState,
    onInsert: (TerminalSnippet) -> Unit,
    onEdit: (TerminalSnippet) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = spacing.md)
                .testTag("terminal_snippets"),
        ) {
            SectionHeader("保存した入力")
            Text(
                "タップすると入力欄に入ります。送信は自分で行います。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.md),
            )
            state.error?.let {
                InlineNotice(
                    text = it,
                    tone = NoticeTone.Error,
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
                )
            }
            Column(Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)) {
                state.items.forEach { item ->
                    CompactListRow(
                        modifier = Modifier.testTag("terminal_snippet"),
                        onClick = { onInsert(item) },
                        trailing = {
                            IconButton(onClick = { onEdit(item) }) {
                                Icon(Icons.Default.Edit, contentDescription = "${item.label}を編集")
                            }
                        },
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (item.secret) {
                                Icon(
                                    Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column {
                                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (item.secret) "パスワード(非表示)" else item.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = if (item.secret) FontFamily.Default else FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (state.loaded && state.items.isEmpty()) {
                    Text(
                        "まだありません。よく使うコマンドや文字列、パスワードを保存できます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = spacing.sm),
                    )
                }
            }
            Row(
                Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryPillButton(
                    onClick = { onEdit(newTerminalSnippet(secret = false)) },
                    enabled = state.loaded,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("terminal_snippet_add"),
                ) { Text("文字列を追加") }
                TextButton(
                    onClick = { onEdit(newTerminalSnippet(secret = true)) },
                    enabled = state.loaded,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("terminal_snippet_add_password"),
                ) { Text("パスワードを追加") }
            }
            if (state.loaded && state.items.keiyaPassword() == null) {
                TextButton(
                    onClick = { onEdit(newKeiyaPassword()) },
                    modifier = Modifier.padding(horizontal = spacing.xs).heightIn(min = 48.dp),
                ) { Text("keiyaのパスワードを保存") }
            }
        }
    }
}

/** Rounded filled field without an outline or underline (DESIGN.md: Inputs). */
@Composable
private fun terminalFieldColors() =
    TextFieldDefaults.colors(
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
    )

/** One opening of the editor. Identity, not content, so a finished save never closes a newer editor. */
internal class TerminalEditorRequest(
    val snippet: TerminalSnippet,
)

/**
 * The editor can reveal a password, so its window is kept out of screenshots and recents like the
 * activity (vault dialogs use the same policy). Applied to every entry kind for one rule.
 */
internal fun terminalSnippetEditorProperties() = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

/**
 * Add or edit one entry. Its kind (string or password) is fixed when it is created, so there is
 * no mode to switch. Password values are masked unless the user reveals them here.
 */
@Composable
internal fun TerminalSnippetEditor(
    initial: TerminalSnippet,
    isNew: Boolean,
    error: String?,
    busy: Boolean,
    onSave: (TerminalSnippet) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Plain remember: dropped with the dialog, never written to saved instance state.
    var label by remember(initial) { mutableStateOf(initial.label) }
    var value by remember(initial) { mutableStateOf(initial.value) }
    var reveal by remember(initial) { mutableStateOf(false) }
    var confirmDelete by remember(initial) { mutableStateOf(false) }
    val title =
        when {
            confirmDelete -> "削除しますか"
            initial.role == KEIYA_PASSWORD_ROLE -> "keiyaのパスワード"
            initial.secret -> if (isNew) "パスワードを追加" else "パスワードを編集"
            else -> if (isNew) "文字列を追加" else "文字列を編集"
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = terminalSnippetEditorProperties(),
        title = { Text(title) },
        text = {
            if (confirmDelete) {
                Text("「${initial.label}」をこの端末から削除します。")
            } else {
                TerminalSnippetFields(initial, isNew, error, label, value, reveal, { label = it }, { value = it }, {
                    reveal =
                        !reveal
                }) {
                    confirmDelete = true
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                TextButton(
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.testTag("terminal_snippet_delete"),
                ) {
                    Text("削除", color = if (busy) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(
                    onClick = { onSave(initial.copy(label = label.trim(), value = value)) },
                    enabled = !busy && label.isNotBlank() && value.isNotEmpty(),
                    modifier = Modifier.testTag("terminal_snippet_save"),
                ) { Text("保存") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) confirmDelete = false else onDismiss() }) {
                Text(if (confirmDelete) "戻る" else "キャンセル")
            }
        },
    )
}

@Composable
private fun TerminalSnippetFields(
    initial: TerminalSnippet,
    isNew: Boolean,
    error: String?,
    label: String,
    value: String,
    reveal: Boolean,
    onLabel: (String) -> Unit,
    onValue: (String) -> Unit,
    onReveal: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (initial.secret) {
            Text(
                if (initial.role == KEIYA_PASSWORD_ROLE) {
                    "この接続先とプロファイル専用に、端末内で暗号化して保存します。サーバーやバックアップには送られません。" +
                        "keiyaのログインで「パスワード:」と表示されたら、ボタンで入力欄に入れて送信してください。"
                } else {
                    "この接続先とプロファイル専用に、端末内で暗号化して保存します。入力欄では伏せ字で表示されます。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextField(
            value = label,
            onValueChange = { onLabel(it.take(40)) },
            label = { Text("名前") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = terminalFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("terminal_snippet_label"),
        )
        TextField(
            value = value,
            onValueChange = { onValue(it.take(4096)) },
            label = { Text(if (initial.secret) "パスワード" else "内容") },
            singleLine = initial.secret,
            maxLines = if (initial.secret) 1 else 4,
            shape = MaterialTheme.shapes.medium,
            colors = terminalFieldColors(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            visualTransformation =
                if (initial.secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = if (initial.secret) KeyboardType.Password else KeyboardType.Text,
                ),
            trailingIcon =
                if (initial.secret) {
                    {
                        IconButton(onClick = onReveal) {
                            Icon(
                                if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (reveal) "パスワードを隠す" else "パスワードを表示",
                            )
                        }
                    }
                } else {
                    null
                },
            modifier = Modifier.fillMaxWidth().testTag("terminal_snippet_value"),
        )
        error?.let { InlineNotice(text = it, tone = NoticeTone.Error) }
        if (!isNew) {
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("削除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Shown only while older output is visible: page through it or jump to the latest line. */
@Composable
internal fun TerminalScrollControls(
    unseen: Boolean,
    onPage: (Int) -> Unit,
    onLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        modifier = modifier.testTag("terminal_scroll_controls"),
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPage(-1) }) { Icon(Icons.Default.KeyboardDoubleArrowUp, "前のページ") }
            IconButton(onClick = { onPage(1) }) { Icon(Icons.Default.KeyboardDoubleArrowDown, "次のページ") }
            TextButton(onClick = onLatest, modifier = Modifier.heightIn(min = 48.dp).testTag("terminal_latest")) {
                Icon(Icons.Default.VerticalAlignBottom, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (unseen) "新しい出力へ" else "最新へ")
            }
        }
    }
}
