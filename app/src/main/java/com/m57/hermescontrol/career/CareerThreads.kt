package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import com.m57.hermescontrol.ui.common.SearchBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class CareerThread(
    val id: String,
    val title: String,
    val company_id: String? = null,
    val offer_id: String? = null,
    val archived: Boolean = false,
    val version: Int = 1,
    val updated_at: String = "",
    val last_message: String = "",
    val run_state: String? = null,
    val context_missing: Boolean = false,
    val company_name: String? = null,
    val offer_title: String? = null,
    val local_only: Boolean = false,
)

@Serializable
data class CareerThreads(
    val api_version: Int = 1,
    val profile: String = "default",
    val threads: List<CareerThread> = emptyList(),
)

private const val THREADS_CACHE_KEY = "threads"
private val json = Json { ignoreUnknownKeys = true }

internal suspend fun CareerLocalStore.cachedThreads(): List<CareerThread> =
    uiValue(THREADS_CACHE_KEY)?.let { json.decodeFromString<CareerThreads>(it).threads }.orEmpty()

data class CareerThreadsState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val supported: Boolean = false,
    val capabilitiesChecked: Boolean = false,
    val stale: Boolean = true,
    val threads: List<CareerThread> = emptyList(),
    val pending: CareerPendingOperation? = null,
    val error: String? = null,
    val created: CareerThread? = null,
)

class CareerThreadsViewModel(
    private val scope: String,
    private val local: CareerLocalStore,
    private val threadLocal: ((String) -> CareerLocalStore)? = null,
) : ViewModel() {
    private val api = ApiClient.hermesApi
    private val profile = AppScope.PROFILE
    private val mutableState = MutableStateFlow(CareerThreadsState())
    val state = mutableState.asStateFlow()
    private var storageReadable = false

    init {
        viewModelScope.launch {
            try {
                mutableState.update {
                    it.copy(
                        threads =
                            local
                                .cachedThreads()
                                .plus(recoverableDrafts())
                                .distinctBy { thread -> thread.id },
                        pending =
                            local.uiValue("thread-operation")?.let { value ->
                                json.decodeFromString<CareerPendingOperation>(value)
                            },
                        supported = local.uiValue("threads-supported") == "true",
                    )
                }
                storageReadable = true
                refresh()
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    private fun checkScope() {
        check(scope == careerScope() && AuthManager.isGatedMode()) { "接続先が変わりました。開き直してください。" }
    }

    private suspend fun recoverableDrafts(): List<CareerThread> {
        val entities = local.cached()?.entities.orEmpty()
        return CareerNewConversationRecovery.entries(local).mapNotNull { entry ->
            val store = threadLocal?.invoke(entry.threadId) ?: return@mapNotNull null
            val cache = store.coach() ?: return@mapNotNull null
            if (cache.draft.isBlank() && cache.pending == null) return@mapNotNull null
            val receipt =
                store.uiValue("new-conversation-receipt")?.let {
                    json.decodeFromString<CareerNewConversationReceipt>(it)
                }
            if (receipt?.thread?.let { !local.hasPendingThread(it.id) } == true) return@mapNotNull null
            CareerThread(
                entry.threadId,
                "端末の下書き · ${careerConversationTitle(cache.draft)}",
                entry.companyId,
                entry.offerId,
                last_message = cache.draft,
                company_name = entities.firstOrNull { it.kind == "companies" && it.id == entry.companyId }?.title,
                offer_title = entities.firstOrNull { it.kind == "offers" && it.id == entry.offerId }?.title,
                local_only = true,
            )
        }
    }

    fun refresh() {
        if (!storageReadable) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                checkScope()
                val capabilities = api.careerCoachCapabilities(profile)
                checkScope()
                val supported = "conversation_threads" in capabilities.features
                val result =
                    if (supported) {
                        api.careerThreads(
                            profile,
                        )
                    } else {
                        CareerThreads(profile = profile, threads = listOf(CareerThread("global", "これまでの相談")))
                    }
                checkScope()
                check(result.profile == profile && result.api_version == 1)
                val cachedOffers =
                    local
                        .cached()
                        ?.entities
                        .orEmpty()
                        .filter { it.kind == "offers" }
                val localDrafts =
                    local.legacyDraftThreads().filter { id -> result.threads.none { it.id == id } }.map { id ->
                        val offer = id.removePrefix("offer:").takeIf { id.startsWith("offer:") }
                        val company =
                            if (id.startsWith("company:")) {
                                id.removePrefix("company:")
                            } else {
                                cachedOffers.firstOrNull { it.id == offer }?.data?.text("company_id")
                            }
                        CareerThread(id, "これまでの相談（端末の下書き）", company, offer, local_only = true)
                    }
                val merged =
                    result.copy(
                        threads = (result.threads + localDrafts + recoverableDrafts()).distinctBy { it.id },
                    )
                local.saveUiValue(THREADS_CACHE_KEY, json.encodeToString(merged))
                local.saveUiValue("threads-supported", supported.toString())
                mutableState.update {
                    it.copy(
                        threads = merged.threads,
                        supported = supported,
                        capabilitiesChecked = true,
                        stale = false,
                        loading = false,
                    )
                }
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun create(
        title: String,
        companyId: String?,
        offerId: String?,
    ) = submit(
        "create",
        buildJsonObject {
            put("title", title.ifBlank { "新しい相談" })
            companyId?.let { put("company_id", it) }
            offerId?.let { put("offer_id", it) }
        },
    )

    fun startConversation(key: CareerThreadsKey) {
        if (state.value.busy || state.value.pending != null || !state.value.supported || !storageReadable) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                checkScope()
                val context =
                    CareerNewConversation(
                        companyId = key.companyId,
                        offerId = key.offerId,
                        handoffId = key.handoffId,
                        target = key.target,
                    )
                val previous =
                    local.uiValue(context.registryKey)?.let { json.decodeFromString<CareerNewConversation>(it) }
                val receipt =
                    previous?.let { candidate ->
                        threadLocal?.invoke(candidate.threadId)?.uiValue("new-conversation-receipt")?.let {
                            json.decodeFromString<CareerNewConversationReceipt>(it)
                        }
                    }
                // Acknowledgment may finish from another screen, or Back may cancel its final hook.
                // Resume an unresolved send; after its local pending is cleared, New starts afresh.
                val finished = receipt?.thread?.let { !local.hasPendingThread(it.id) } == true
                val entry = previous?.takeUnless { finished } ?: context
                check(entry.copy(id = "") == context.copy(id = ""))
                local.saveUiValue(context.registryKey, json.encodeToString(entry))
                checkScope()
                NavigationController.navigateTo(
                    CareerConversationKey(
                        entry.threadId,
                        key.prompt,
                        key.target,
                        key.handoffId,
                        if (key.companyId == null && key.offerId == null) "全体相談" else "企業の相談",
                    ),
                )
                mutableState.update { it.copy(busy = false) }
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun update(
        thread: CareerThread,
        title: String? = null,
        archived: Boolean? = null,
    ) {
        viewModelScope.launch {
            try {
                if (archived == true) {
                    check(!local.hasPendingThread(thread.id)) { "送信結果を確認してからアーカイブしてください。" }
                }
                submit(
                    thread.id,
                    buildJsonObject {
                        put("expected_version", thread.version)
                        title?.let { put("title", it) }
                        archived?.let { put("archived", it) }
                    },
                )
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    private fun submit(
        action: String,
        payload: JsonObject,
    ) {
        if (state.value.busy || state.value.pending != null || state.value.stale || !state.value.supported) return
        execute(
            CareerPendingOperation(
                action,
                buildJsonObject {
                    payload.forEach { (key, value) -> put(key, value) }
                    put("operation_id", UUID.randomUUID().toString())
                },
            ),
        )
    }

    fun retry() {
        state.value.pending?.let(::execute)
    }

    fun discardPending() {
        if (state.value.busy || !storageReadable) return
        viewModelScope.launch {
            try {
                local.saveUiValue("thread-operation", null)
                mutableState.update { it.copy(pending = null, error = null) }
                refresh()
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun consumed() {
        mutableState.update { it.copy(created = null) }
    }

    private fun execute(operation: CareerPendingOperation) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, pending = operation, error = null) }
        viewModelScope.launch {
            try {
                local.saveUiValue("thread-operation", json.encodeToString(operation))
                checkScope()
                val result =
                    if (operation.action ==
                        "create"
                    ) {
                        api.careerCreateThread(profile, operation.payload)
                    } else {
                        api.careerUpdateThread(operation.action, profile, operation.payload)
                    }
                checkScope()
                local.saveUiValue("thread-operation", null)
                mutableState.update {
                    it.copy(
                        busy = false,
                        pending = null,
                        created =
                            result.takeIf {
                                operation.action ==
                                    "create"
                            },
                    )
                }
                refresh()
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    private fun fail(error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(loading = false, busy = false, stale = true, error = careerError(error)) }
    }
}

@Composable
internal fun careerThreadsModel(scope: String): CareerThreadsViewModel {
    val app = LocalContext.current.applicationContext
    return viewModel(
        key = "career-threads:$scope",
        factory =
            viewModelFactory {
                initializer {
                    CareerThreadsViewModel(scope, CareerLocalStore(app, scope)) { identity ->
                        CareerLocalStore(app, "$scope/thread/$identity")
                    }
                }
            },
    )
}

@Composable
fun CareerThreadsScreen(key: CareerThreadsKey) {
    val context by AuthManager.contextFlow.collectAsState()
    val scope = remember(context) { careerScope() }
    val model = careerThreadsModel(scope)
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    HermesScaffold(title = {
        Text("相談")
    }, navigationIcon = NavIcon.Back { NavigationController.goBack() }, drawerGesturesEnabled = false) {
        CareerThreadsContent(state, model, key)
    }
}

@Composable
internal fun CareerThreadsContent(
    state: CareerThreadsState,
    model: CareerThreadsViewModel,
    key: CareerThreadsKey = CareerThreadsKey(),
) {
    val spacing = LocalSpacing.current
    var search by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<CareerThread?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var discard by rememberSaveable { mutableStateOf(false) }

    fun open(thread: CareerThread) {
        val draft = CareerNewConversation.fromThreadId(thread.id)
        NavigationController.navigateTo(
            CareerConversationKey(
                thread.id,
                if (draft == null) key.prompt else "",
                draft?.target ?: key.target.takeIf { draft == null },
                draft?.handoffId ?: key.handoffId.takeIf { draft == null },
                thread.title,
                thread.archived || thread.context_missing,
            ),
        )
    }
    LaunchedEffect(state.created) {
        state.created?.let {
            model.consumed()
            open(it)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = spacing.md),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = spacing.sm, bottom = spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                // One control row: search, the screen's single primary action, overflow menu.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    SearchBar(search, { search = it }, Modifier.weight(1f), placeholder = "会話を探す")
                    PrimaryPillButton(
                        onClick = { model.startConversation(key) },
                        enabled =
                            state.supported && !state.busy && state.pending == null,
                        modifier = Modifier.testTag("career_new_conversation"),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("新しい相談", Modifier.padding(start = spacing.xs))
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("career_threads_menu")) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "相談一覧のメニュー")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("更新") }, enabled = !state.loading, onClick = {
                                menu = false
                                model.refresh()
                            })
                            DropdownMenuItem(
                                text = { Text(if (archived) "相談一覧に戻る" else "アーカイブを表示") },
                                onClick = {
                                    menu = false
                                    archived = !archived
                                },
                            )
                        }
                    }
                }
                if (archived) {
                    TextButton(
                        onClick = { archived = false },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("アーカイブ · 相談一覧に戻る") }
                }
                if (key.companyId != null) Text("この企業の相談", style = MaterialTheme.typography.labelMedium)
                if (key.target != null || key.handoffId != null) Text("参照内容を引き継ぐ会話を選ぶか、新しい相談を作成してください。")
                if (state.loading) LoadingState()
                CareerStatusBanner(
                    careerStatusOf(
                        listOf(state.error),
                        state.stale && state.threads.isNotEmpty(),
                        "保存済みの会話一覧を表示しています。",
                    ),
                    model::refresh,
                )
                if (!state.loading && state.capabilitiesChecked &&
                    !state.supported
                ) {
                    Text("新しい相談の作成には就活サーバーの更新が必要です。以前の相談は開けます。")
                }
                if (state.pending != null) {
                    TextButton(
                        onClick = model::retry,
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("会話の変更結果を再確認") }
                    TextButton(
                        onClick = { discard = true },
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("再試行を取りやめる") }
                }
            }
        }
        val candidates =
            if (!state.supported && key.companyId != null) {
                state.threads +
                    CareerThread(
                        key.offerId?.let { "offer:$it" } ?: "company:${key.companyId}",
                        "これまでの相談",
                        key.companyId,
                        key.offerId,
                    )
            } else {
                state.threads
            }
        val rows =
            candidates.filter {
                it.archived == archived && (key.companyId == null || it.company_id == key.companyId) &&
                    (key.offerId == null || it.offer_id == null || it.offer_id == key.offerId) &&
                    (
                        it.title.contains(search, true) || it.last_message.contains(search, true) ||
                            it.company_name.orEmpty().contains(search, true) ||
                            it.offer_title.orEmpty().contains(search, true)
                    )
            }
        if (rows.isEmpty() && !state.loading) {
            item {
                EmptyState(
                    if (search.isNotBlank()) {
                        "一致する相談はありません"
                    } else if (archived) {
                        "アーカイブはありません"
                    } else {
                        "相談したいことを話す"
                    },
                    subtitle =
                        when {
                            search.isNotBlank() -> "別の言葉で検索してください。"
                            !state.supported && state.error != null -> "接続が戻ると「新しい相談」を始められます。"
                            else -> "「新しい相談」から、そのまま入力できます。"
                        },
                )
            }
        }
        items(rows, key = { it.id }) { thread ->
            var threadMenu by remember(thread.id) { mutableStateOf(false) }
            val canManage =
                state.supported && !state.stale && !state.busy && !thread.local_only && state.pending == null
            // Flat row + divider, not a card per conversation (DESIGN.md, 2026-10-24).
            CompactListRow(
                modifier = Modifier.testTag("career_thread_${thread.id}"),
                onClick = { open(thread) },
                trailing = {
                    Box {
                        IconButton(
                            onClick = { threadMenu = true },
                            modifier = Modifier.size(40.dp).testTag("career_thread_menu_${thread.id}"),
                        ) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = "${thread.title}のメニュー",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(expanded = threadMenu, onDismissRequest = { threadMenu = false }) {
                            DropdownMenuItem(text = { Text("名前変更") }, enabled = canManage, onClick = {
                                threadMenu = false
                                rename = thread
                                title = thread.title
                            })
                            DropdownMenuItem(
                                text = { Text(if (thread.archived) "復元" else "アーカイブ") },
                                enabled = canManage && (thread.archived || thread.run_state == null),
                                onClick = {
                                    threadMenu = false
                                    model.update(thread, archived = !thread.archived)
                                },
                            )
                        }
                    }
                },
            ) {
                Text(
                    thread.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                careerThreadPreview(thread.last_message).takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                listOfNotNull(
                    careerThreadScopeLabel(thread),
                    careerThreadUpdatedAt(thread.updated_at).takeIf { it.isNotBlank() },
                    careerThreadRunBadge(thread.run_state),
                ).joinToString(" · ").let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (thread.context_missing) {
                    Text(
                        "関連先不明・履歴は保持しています",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (rename != null) {
        AlertDialog(
            onDismissRequest = {
                rename = null
            },
            title = { Text("会話の名前") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    HermesTextField(
                        title,
                        { title = it.take(120) },
                        label = { Text("テーマ・会話名") },
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.pending !=
                        null
                    ) {
                        TextButton(
                            onClick = model::retry,
                            enabled = !state.busy,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("会話の変更結果を再確認") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        rename?.let {
                            model.update(it, title = title)
                            rename = null
                        }
                    },
                    enabled = !state.busy && state.pending == null && title.isNotBlank(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    rename = null
                }, shapes = ButtonDefaults.shapes()) { Text("閉じる") }
            },
        )
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("再試行を取りやめますか") },
            text = { Text("サーバーで作成・変更済みの会話は取り消しません。一覧を更新して状態を確認してください。") },
            confirmButton = {
                TextButton(onClick = {
                    model.discardPending()
                    discard = false
                }, shapes = ButtonDefaults.shapes()) { Text("取りやめる") }
            },
            dismissButton = {
                TextButton(onClick = { discard = false }, shapes = ButtonDefaults.shapes()) { Text("戻る") }
            },
        )
    }
}
