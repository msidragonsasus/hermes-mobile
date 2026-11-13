package com.m57.hermescontrol.terminal

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.components.ChatComposer
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import com.m57.hermescontrol.ui.common.SectionHeader
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen() {
    val context by AuthManager.contextFlow.collectAsState()
    val profile by AuthManager.activeProfileId.collectAsState()
    val scope = remember(context, profile) { AuthManager.uiPreferenceScope() }
    key(scope) {
        val model: TerminalViewModel =
            viewModel(
                key = "terminal:$scope",
                factory = viewModelFactory { initializer { TerminalViewModel(scope) } },
            )
        val state by model.state.collectAsState()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(model, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.resume()
                try {
                    awaitCancellation()
                } finally {
                    model.pause()
                }
            }
        }
        DisposableEffect(model) { onDispose { model.forget() } }
        val appContext = LocalContext.current.applicationContext
        // Bound to this server + profile for its whole life; re-checked before every read/write.
        val snippets =
            remember(scope) {
                TerminalSnippetsModel(
                    TerminalSnippetStore(EncryptedTerminalStorage(appContext), scope) {
                        AuthManager.uiPreferenceScope() == scope
                    },
                )
            }
        LaunchedEffect(snippets) { snippets.load() }
        TerminalContent(state, model, snippets)
    }
}

@Composable
internal fun TerminalContent(
    state: TerminalUiState,
    model: TerminalActions,
    snippets: TerminalSnippetsModel,
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    var draft by remember { mutableStateOf(TerminalDraft()) }
    var enter by remember { mutableStateOf(true) }
    var fontSize by remember { mutableIntStateOf(14) }
    var display by remember { mutableStateOf<TerminalDisplay?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<TerminalSelection?>(null) }
    var links by remember { mutableStateOf<List<String>?>(null) }
    var displayFailed by remember { mutableStateOf(false) }
    var scroll by remember { mutableStateOf(TerminalScroll()) }
    var picker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TerminalEditorRequest?>(null) }
    val saved by snippets.state.collectAsState()
    val keiyaPassword = saved.items.keiyaPassword()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Commands, codes and inserted passwords are ephemeral; never rememberSaveable or a history DB here.
    LaunchedEffect(state.selected) {
        draft = TerminalDraft()
        selection = null
        scroll = TerminalScroll()
    }
    // Leaving the app drops an inserted password and any open password editor.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    if (draft.secret) draft = TerminalDraft()
                    editing = null
                    picker = false
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val writable = state.connected && !state.closed && !state.busy && !displayFailed
    val home = state.selected == null
    HermesScaffold(
        title = { Text("サーバーターミナル") },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        showShellMenu = false,
        pinTopBar = true,
        actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "ターミナルの操作") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("再接続") }, onClick = {
                    menu = false
                    model.pause()
                    model.resume()
                })
                DropdownMenuItem(text = { Text("選択してコピー(画面の長押しでも)") }, enabled = !home, onClick = {
                    menu = false
                    display?.snapshot(-1, -1, word = false) { selection = it }
                })
                DropdownMenuItem(text = { Text("貼り付け") }, enabled = writable, onClick = {
                    menu = false
                    scope.launch {
                        val pasted =
                            clipboard
                                .getClipEntry()
                                ?.clipData
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                        if (!pasted.isNullOrEmpty()) draft = draft.edit(draft.text + pasted)
                    }
                })
                DropdownMenuItem(text = { Text("出力内のリンクを開く") }, onClick = {
                    menu = false
                    display?.text { value -> links = terminalLinks(value) }
                })
                DropdownMenuItem(text = { Text("保存した入力") }, onClick = {
                    menu = false
                    picker = true
                })
                DropdownMenuItem(text = { Text("前のページへ") }, enabled = !home, onClick = {
                    menu = false
                    display?.page(-1)
                })
                DropdownMenuItem(text = { Text("最新の出力へ") }, enabled = !home, onClick = {
                    menu = false
                    display?.bottom()
                })
                DropdownMenuItem(
                    text = { Text(if (enter) "送信時にEnter: オン" else "送信時にEnter: オフ") },
                    onClick = { enter = !enter },
                )
                DropdownMenuItem(text = { Text("「claude auth login」を入力") }, enabled = writable, onClick = {
                    menu = false
                    draft = TerminalDraft("claude auth login")
                })
                DropdownMenuItem(text = { Text("「tmux new -As mobile」を入力") }, enabled = writable, onClick = {
                    menu = false
                    draft = TerminalDraft("tmux new -As mobile")
                })
                DropdownMenuItem(text = { Text("文字を大きく") }, onClick = { fontSize = (fontSize + 2).coerceAtMost(28) })
                DropdownMenuItem(text = { Text("文字を小さく") }, onClick = { fontSize = (fontSize - 2).coerceAtLeast(10) })
                DropdownMenuItem(text = { Text("セッションを終了") }, enabled = state.selected != null, onClick = {
                    menu = false
                    confirmClose = true
                })
            }
        },
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // Compact status line: dot + server · session. Inside a session, tap to go back to the list.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !home, onClickLabel = "セッション一覧へ") { model.showHome() }
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusColors = LocalHermesStatusColors.current
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            when {
                                home -> if (state.enabled) statusColors.success else MaterialTheme.colorScheme.outline
                                state.closed -> MaterialTheme.colorScheme.outline
                                state.connected -> statusColors.success
                                state.busy -> statusColors.warning
                                else -> MaterialTheme.colorScheme.outline
                            },
                            CircleShape,
                        ),
                )
                Text(
                    listOf(state.serverLabel, state.selected?.let { "端末 ${it.take(8)}" } ?: "")
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        state.busy -> "確認中"
                        home -> if (state.enabled) "接続済み" else "未接続"
                        state.closed -> "終了"
                        state.connected -> "接続済み"
                        else -> "未接続"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let {
                InlineNotice(
                    text = it,
                    tone = NoticeTone.Error,
                    actionLabel = "再接続",
                    onAction = {
                        model.pause()
                        model.resume()
                    },
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
                )
            }
            if (home) {
                TerminalHome(
                    state,
                    model,
                    onCreate = model::create,
                    keiyaPasswordSaved = keiyaPassword != null,
                    savedReady = saved.loaded,
                    onKeiyaPassword = { editing = TerminalEditorRequest(keiyaPassword ?: newKeiyaPassword()) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (displayFailed) {
                Text("表示を再開するにはターミナルを開き直してください。", Modifier.weight(1f).padding(spacing.md))
            } else {
                // The terminal keeps receiving underneath; selection mode covers it with frozen native text.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TerminalDisplay(
                        state,
                        fontSize,
                        Modifier.fillMaxSize().testTag("terminal_display"),
                        model::resize,
                        { view -> display = view },
                        { displayFailed = true },
                        model::report,
                        { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                        // The page detects long-press itself and opens selection at the pressed word.
                        onLongPress = { selection = it },
                        onScroll = { scroll = it },
                    )
                    if (!scroll.atBottom && selection == null) {
                        TerminalScrollControls(
                            unseen = scroll.unseen,
                            onPage = { display?.page(it) },
                            onLatest = { display?.bottom() },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(spacing.sm),
                        )
                    }
                    selection?.let { current ->
                        TerminalSelectionPane(
                            selection = current,
                            fontSize = fontSize,
                            onCopyAll = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("terminal", current.text)))
                                }
                                selection = null
                            },
                            onDone = { selection = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            if (!home) {
                // One accessory row: saved input, keiya password, then special keys as small key caps.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = spacing.md, vertical = spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TerminalKeyCap(
                        label = "保存した入力",
                        icon = Icons.Default.Bookmarks,
                        onClick = { picker = true },
                        modifier = Modifier.testTag("terminal_saved_inputs"),
                    )
                    // Inserts only; the user sends at the password prompt. Never sent automatically.
                    TerminalKeyCap(
                        label = if (keiyaPassword != null) "keiyaのパスワード" else "keiyaのパスワードを保存",
                        icon = Icons.Default.Key,
                        enabled = saved.loaded,
                        onClick = {
                            if (keiyaPassword !=
                                null
                            ) {
                                draft = draft.insert(keiyaPassword)
                            } else {
                                editing = TerminalEditorRequest(newKeiyaPassword())
                            }
                        },
                        modifier = Modifier.testTag("terminal_keiya_password"),
                    )
                    if (draft.secret) {
                        TerminalKeyCap(
                            label = "パスワードを消去",
                            icon = Icons.Default.Clear,
                            selected = true,
                            onClick = { draft = TerminalDraft() },
                        )
                    }
                    terminalKeys.forEach { (label, bytes) ->
                        TerminalKeyCap(
                            label = label,
                            enabled = writable,
                            onClick = {
                                model.send(
                                    terminalKeyBytes(bytes, display?.applicationCursorKeys == true),
                                    false,
                                )
                            },
                        )
                    }
                }
                ChatComposer(
                    value = draft.text,
                    onValueChange = { draft = draft.edit(it) },
                    onSend = {
                        val text = draft.text
                        draft = TerminalDraft()
                        model.send(text, enter)
                    },
                    sendEnabled = writable && draft.text.isNotEmpty(),
                    placeholder = "コマンドや返答を入力",
                    inputTestTag = "terminal_input",
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = if (draft.secret) KeyboardType.Password else KeyboardType.Text,
                        ),
                    // Masked because a saved password was inserted, not because of a mode.
                    visualTransformation =
                        if (draft.secret) PasswordVisualTransformation() else VisualTransformation.None,
                )
            }
        }
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("セッションを終了") },
            text = { Text("このシェルと、その配下で動いている処理を終了します。tmux内の処理は残る場合があります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    model.closeSession()
                }) { Text("終了") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("戻る") } },
        )
    }
    links?.let { values ->
        AlertDialog(
            onDismissRequest = { links = null },
            title = { Text("ブラウザで開く") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (values.isEmpty()) Text("リンクが見つかりません。出力からコピーできます。")
                    values.forEach { url ->
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            links = null
                        }) { Text(url) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { links = null }) { Text("閉じる") } },
        )
    }
    if (picker) {
        TerminalSnippetSheet(
            state = saved,
            onInsert = {
                draft = draft.insert(it)
                picker = false
            },
            onEdit = {
                snippets.clearError()
                editing = TerminalEditorRequest(it)
            },
            onDismiss = { picker = false },
        )
    }
    editing?.let { request ->
        val current = request.snippet
        TerminalSnippetEditor(
            initial = current,
            isNew = saved.items.none { it.id == current.id },
            error = saved.error,
            busy = saved.busy,
            // Close only the editor that issued the request; a newer one stays open.
            onSave = { value ->
                scope.launch { if (snippets.save(value) && editing === request) editing = null }
            },
            onDelete = { scope.launch { if (snippets.delete(current.id) && editing === request) editing = null } },
            onDismiss = {
                snippets.clearError()
                editing = null
            },
        )
    }
}

/** Start screen: running shells (one tap opens) and the two ways to open a new one. */
@Composable
private fun TerminalHome(
    state: TerminalUiState,
    model: TerminalActions,
    onCreate: (TerminalKind) -> Unit,
    keiyaPasswordSaved: Boolean,
    savedReady: Boolean,
    onKeiyaPassword: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current
    val canCreate = state.enabled && !state.busy
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (state.sessions.isNotEmpty()) {
            SectionHeader("稼働中")
            Column(Modifier.padding(horizontal = spacing.md)) {
                state.sessions.sortedBy { it.closed }.forEach { item ->
                    CompactListRow(
                        modifier = Modifier.testTag("terminal_session"),
                        onClick = if (state.busy) null else ({ model.select(item.id) }),
                        trailing =
                            if (item.closed) {
                                {
                                    Text(
                                        "終了",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            } else {
                                null
                            },
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(
                                        if (item.closed) MaterialTheme.colorScheme.outline else statusColors.success,
                                        CircleShape,
                                    ),
                            )
                            Text(
                                "端末 " + item.id.take(8),
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                                color =
                                    if (item.closed) {
                                        MaterialTheme.colorScheme.outline
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                    }
                }
            }
        }
        SectionHeader("新しいターミナル")
        Row(
            Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            PrimaryPillButton(
                onClick = { onCreate(TerminalKind.HERMES) },
                enabled = canCreate,
                modifier = Modifier.heightIn(min = 48.dp).testTag("terminal_create"),
            ) { Text("Hermes") }
            OutlinedButton(
                onClick = { onCreate(TerminalKind.KEIYA) },
                enabled = canCreate,
                modifier = Modifier.heightIn(min = 48.dp).testTag("terminal_create_keiya"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("keiya") }
        }
        // keiya logs in through SSH and asks for its password at the prompt.
        Text(
            if (keiyaPasswordSaved) {
                "keiyaのパスワードは保存済みです。パスワードを聞かれたら「keiyaのパスワード」で入力欄に入れて送信します。"
            } else {
                "keiyaのパスワードをこの端末に暗号化して保存すると、ログイン時にワンタップで入力欄に入れられます。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
        )
        TextButton(
            onClick = onKeiyaPassword,
            enabled = savedReady,
            modifier =
                Modifier
                    .padding(horizontal = spacing.xs)
                    .heightIn(min = 48.dp)
                    .testTag("terminal_home_keiya_password"),
        ) { Text(if (keiyaPasswordSaved) "keiyaのパスワードを編集" else "keiyaのパスワードを保存") }
    }
}

@Composable
private fun TerminalKeyCap(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        contentColor =
            if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = if (icon == null) FontFamily.Monospace else FontFamily.Default,
                color = if (enabled) LocalContentColor.current else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

internal val terminalKeys =
    listOf(
        "Enter" to "\r",
        "Tab" to "\t",
        "Esc" to "\u001b",
        "Ctrl+C" to "\u0003",
        "↑" to "\u001b[A",
        "↓" to "\u001b[B",
        "←" to "\u001b[D",
        "→" to "\u001b[C",
        "Ctrl+D" to "\u0004",
        "Backspace" to "\u007f",
        "Ctrl+Z" to "\u001a",
        "Ctrl+L" to "\u000c",
    )

internal fun terminalKeyBytes(
    bytes: String,
    applicationCursor: Boolean,
): String =
    if (applicationCursor && bytes in setOf("\u001b[A", "\u001b[B", "\u001b[C", "\u001b[D")) {
        bytes.replace('[', 'O')
    } else {
        bytes
    }

internal fun terminalLinks(text: String): List<String> =
    Regex("https://[^\\s<>\\\"']+")
        .findAll(text)
        .map { it.value }
        .filter(::terminalLinkAllowed)
        .distinct()
        .take(12)
        .toList()

/** Only plain https links with a host and no embedded credentials leave the terminal. */
internal fun terminalLinkAllowed(url: String): Boolean =
    url.length <= 8192 &&
        runCatching {
            val uri = java.net.URI(url)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
