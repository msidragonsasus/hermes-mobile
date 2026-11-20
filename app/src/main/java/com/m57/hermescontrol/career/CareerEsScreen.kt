package com.m57.hermescontrol.career

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.QuietFilterChip
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun CareerEsScreen(
    companyId: String,
    offerId: String,
    initialQuestionId: String? = null,
) {
    val context by AuthManager.contextFlow.collectAsState()
    val scope = remember(context) { careerScope() }
    val app = LocalContext.current.applicationContext
    key(scope) {
        val model: CareerEsViewModel =
            viewModel(
                key = "career-es:$scope:$offerId",
                factory =
                    viewModelFactory {
                        initializer {
                            CareerEsViewModel(
                                DashboardCareerEsRepository(scope),
                                CareerLocalStore(app, scope),
                                companyId,
                                offerId,
                            )
                        }
                    },
            )
        val state by model.state.collectAsState()
        var initialQuestionOpened by rememberSaveable(scope, offerId, initialQuestionId) { mutableStateOf(false) }
        LaunchedEffect(state.detail, initialQuestionId) {
            if (state.detail != null && initialQuestionId != null && !initialQuestionOpened) {
                model.open(initialQuestionId)
                initialQuestionOpened = true
            }
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, model) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) model.refresh() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val back = { model.leave { if (state.selected == null) NavigationController.goBack() } }
        BackHandler { back() }
        HermesScaffold(
            title = { Text(if (state.selected == null) "ES作業" else "回答の編集") },
            navigationIcon = NavIcon.Back(back),
            drawerGesturesEnabled = false,
            actions = { TextButton(onClick = model::refresh, shapes = ButtonDefaults.shapes()) { Text("保存情報を再読込") } },
        ) {
            when {
                !state.initialized -> {
                    LoadingState()
                }

                state.detail == null && state.loading -> {
                    LoadingState()
                }

                state.detail == null -> {
                    ErrorState(state.error ?: "募集を読み込めません。", model::refresh)
                }

                state.selected != null -> {
                    val question = model.question(state.selected!!)
                    if (question == null) {
                        Column(Modifier.padding(LocalSpacing.current.md)) {
                            Text("設問が変更または削除されています。入力は保持しています。")
                            SelectionContainer {
                                Text(
                                    state.cache.drafts[state.selected]
                                        ?.body
                                        .orEmpty(),
                                )
                            }
                            TextButton(onClick = back, shapes = ButtonDefaults.shapes()) { Text("ES作業へ戻る") }
                        }
                    } else {
                        key(state.selected) { CareerAnswerScreen(state, question, model, scope) }
                    }
                }

                else -> {
                    CareerEsList(state, offerId, model::open, model::refresh)
                }
            }
        }
    }
}

@Composable
internal fun CareerEsList(
    state: CareerEsState,
    offerId: String,
    onOpen: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val detail = state.detail ?: return
    val offer = detail.offers.firstOrNull { it.id == offerId }
    var originalOrder by rememberSaveable { mutableStateOf(false) }
    if (offer == null) {
        EmptyState("対象の募集が見つかりません", subtitle = "企業に戻って募集を確認してください。")
        return
    }
    val questions = offer.questions()
    LazyColumn(
        Modifier.fillMaxSize().testTag("career_es_list"),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item {
            Text(detail.company.title, style = MaterialTheme.typography.titleMedium)
            Text(offer.title, style = MaterialTheme.typography.bodyLarge)
            Text("回答案と、確認が必要な項目", style = MaterialTheme.typography.headlineSmall)
            if (state.stale) Text("前回の保存情報を表示しています。接続後に再読込してください。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (offer.data.text("questions_stale") == "true" || offer.data.text("portal_state") != "available") {
                Text("設問の取得状況は要確認です。保存情報から準備を続けられます。")
            }
            if (!detail.es_workspace) Text("回答の保存には就活サーバーの更新が必要です。既存の閲覧・相談は利用できます。")
            state.cache.lastQuestion?.takeIf { id -> questions.any { it.text("id") == id } }?.let { id ->
                Button(onClick = { onOpen(id) }, shapes = ButtonDefaults.shapes()) { Text("前回の続き") }
            }
            QuietFilterChip(
                selected = originalOrder,
                onClick = { originalOrder = !originalOrder },
                label = { Text("原順で確認") },
            )
        }
        if (questions.isEmpty()) item { EmptyState("設問をまだ確認できません", subtitle = "取得済みの本文があれば下の保存済みESから開けます。") }
        val groups =
            if (originalOrder) {
                mapOf("設問の原順" to questions)
            } else {
                questions.groupBy(::questionGroup).toSortedMap(
                    compareBy {
                        listOf("記述回答", "基本情報", "興味・選社軸", "マイページでの対応").indexOf(it)
                    },
                )
            }
        groups.forEach { (group, rows) ->
            item {
                Text(
                    group,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = spacing.md),
                )
            }
            items(rows, key = { it.text("id").orEmpty() }) { question ->
                val id = question.text("id").orEmpty()
                val draft = state.cache.drafts[id]
                val documents = detail.documents.filter { it.answersQuestion(offerId, id) }
                val work = detail.answerWork(offerId, id)
                Card(onClick = { onOpen(id) }, modifier = Modifier.fillMaxWidth().testTag("es_question_$id")) {
                    Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Text(questionTitle(question), style = MaterialTheme.typography.titleSmall)
                        val sourceNumbers =
                            Regex("設問\\s*\\d+")
                                .findAll(question.text("text").orEmpty())
                                .map { it.value }
                                .distinct()
                                .joinToString("・")
                        if (sourceNumbers.isNotEmpty()) Text(sourceNumbers, style = MaterialTheme.typography.bodySmall)
                        if (questions.count { questionTitle(it) == questionTitle(question) } > 1) {
                            Text("同じ見出しの設問があります。原文を確認してください。", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(questionConstraints(question), style = MaterialTheme.typography.bodySmall)
                        Text(
                            when {
                                question.text("input_kind") in setOf("choice", "file") -> "マイページでの対応・状況未確認"
                                draft?.changed == true -> "端末に編集中の回答あり"
                                work["reviewed"] is JsonObject -> "本人確認済みの回答あり"
                                work["adopted"] is JsonObject -> "採用中の回答あり・本人確認は未完了"
                                documents.isNotEmpty() -> "回答案あり・採用する版を確認"
                                else -> "回答案なし・保存情報から準備できます"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val adopted = work["adopted"] as? JsonObject
                        if (adopted != null &&
                            documents.any {
                                it.id != adopted.text("id") ||
                                    it.data.text("version") != adopted.text("version")
                            }
                        ) {
                            Text("別の保存案があります", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        val unlinked =
            detail.documents.filter { doc ->
                questions.none { doc.answersQuestion(offerId, it.text("id").orEmpty()) }
            }
        if (unlinked.isNotEmpty()) {
            item {
                Text("関連する保存済みES", style = MaterialTheme.typography.titleMedium)
                Text("設問との対応が未確認の文書は企業詳細から開けます。")
            }
        }
        item { TextButton(onClick = onRefresh, shapes = ButtonDefaults.shapes()) { Text("保存情報を再読込") } }
    }
}

internal fun questionConstraints(question: JsonObject): String =
    listOfNotNull(
        when {
            question.text("required") == "true" -> "必須"
            question.text("required_known") == "true" -> "任意"
            else -> "必須条件は未確認"
        },
        when (question.text("input_kind")) {
            "file" -> "写真の添付"
            "choice" -> "選択式"
            else -> question.text("max_characters")?.let { "${it}字以内" } ?: "字数上限は未確認"
        },
        if (question.text("no_line_breaks") == "true" || "改行を使用せず" in question.text("text").orEmpty()) "改行禁止" else null,
    ).joinToString(" · ")

@Composable
internal fun CareerAnswerScreen(
    state: CareerEsState,
    question: JsonObject,
    model: CareerEsViewModel,
    scope: String,
    coachOverride: CareerCoachViewModel? = null,
) {
    val app = LocalContext.current.applicationContext
    val id = question.text("id").orEmpty()
    val draft = state.cache.drafts[id]
    val spacing = LocalSpacing.current
    var handoffError by remember { mutableStateOf<String?>(null) }
    var showDiff by remember { mutableStateOf(false) }
    var versionDocument by remember { mutableStateOf<CareerEntity?>(null) }
    var adoptRequest by remember { mutableStateOf<Pair<CareerEntity, Int>?>(null) }
    val clipboard = LocalClipboard.current
    val coroutine = rememberCoroutineScope()
    val answerScroll = rememberScrollState()
    var answerField by rememberSaveable(id, stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        mutableStateOf(
            androidx.compose.ui.text.input
                .TextFieldValue(draft?.body.orEmpty()),
        )
    }
    LaunchedEffect(draft?.body) {
        val text = draft?.body.orEmpty()
        if (answerField.text !=
            text
        ) {
            answerField =
                answerField.copy(
                    text = text,
                    selection =
                        androidx.compose.ui.text
                            .TextRange(answerField.selection.end.coerceAtMost(text.length)),
                )
        }
    }
    var legacy by remember { mutableStateOf<CareerCoachCache?>(null) }
    LaunchedEffect(id) { legacy = CareerLocalStore(app, "$scope/es-conversation:${model.offerId}:$id").coach() }
    val conversationScroll = rememberLazyListState()
    val context =
        draft?.let { value ->
            buildJsonObject {
                put("offer_id", model.offerId)
                put("question_id", id)
                put("question_version", value.questionVersion)
                put("body", value.body)
                value.documentId?.let {
                    put("document_id", it)
                    put("document_version", value.documentVersion)
                }
            }
        }

    fun consult(prompt: String = "") {
        if (context == null) return
        model.prepareConsultation {
            coroutine.launch {
                try {
                    val token =
                        java.util.UUID
                            .randomUUID()
                            .toString()
                    CareerLocalStore(app, scope).saveUiValue("handoff/$token", context.toString())
                    NavigationController.navigateTo(
                        CareerThreadsKey(
                            state.detail?.company?.id,
                            model.offerId,
                            prompt,
                            CareerTarget("offers", model.offerId),
                            token,
                        ),
                    )
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    handoffError = careerError(failure)
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                state.detail
                    ?.company
                    ?.title
                    .orEmpty(),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(questionTitle(question), style = MaterialTheme.typography.titleMedium)
            Text(
                state.detail
                    ?.offers
                    ?.firstOrNull {
                        it.id == model.offerId
                    }?.title
                    .orEmpty(),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(questionConstraints(question), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { consult() }, shapes = ButtonDefaults.shapes()) { Text("この回答について相談") }
            handoffError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (legacy?.draft?.isNotBlank() == true || legacy?.pending != null) {
                TextButton(onClick = {
                    NavigationController.navigateTo(
                        CareerConversationKey("offer:${model.offerId}", title = "以前の設問相談", legacyEsQuestionId = id),
                    )
                }) { Text("以前の相談下書き・送信結果を確認") }
            }
        }
        run {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(answerScroll)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                SelectionContainer {
                    Text(
                        question.text("text").orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.editingLoading) LoadingState()
                state.error?.let { ErrorState(it, onRetry = model::refresh) }
                state.message?.let { Text(it) }
                if (draft == null &&
                    !state.editingLoading
                ) {
                    TextButton(onClick = { model.open(id) }, shapes = ButtonDefaults.shapes()) { Text("回答本文を再読込") }
                }
                if (draft != null) {
                    val changedQuestion = draft.questionVersion != question.text("version")
                    val newer =
                        model
                            .documents(
                                id,
                            ).any { it.id == draft.documentId && it.version != draft.entityVersion }
                    if (changedQuestion || newer) {
                        Text("原文または保存版が更新されています。編集中の本文は保持しています。")
                        if (changedQuestion) SelectionContainer { Text("編集開始時の原文\n${draft.questionText}") }
                    }
                    if (question.text("input_kind") in setOf("file", "choice")) {
                        Text(if (question.text("input_kind") == "file") "マイページで写真を添付してください。" else "マイページで選択してください。")
                        val uri = LocalUriHandler.current
                        val locator =
                            state.detail
                                ?.evidence
                                ?.firstOrNull {
                                    it.text("id") ==
                                        question.text("evidence_id")
                                }?.text("locator")
                        if (locator?.startsWith("https://") ==
                            true
                        ) {
                            TextButton(
                                onClick = { uri.openUri(locator) },
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("マイページを開く") }
                        }
                    } else {
                        if (!state.busy && state.cache.pending == null) {
                            Column(Modifier.testTag("career_answer_input")) {
                                com.m57.hermescontrol.ui.common.HermesTextField(answerField, {
                                    answerField = it
                                    model.changeBody(it.text)
                                }, label = { Text("回答本文") }, modifier = Modifier.fillMaxWidth(), minLines = 6)
                            }
                        } else {
                            SelectionContainer { Text(draft.body) }
                        }
                        Text(
                            "${draft.body.codePointCount(
                                0,
                                draft.body.length,
                            )}字 · ${if (state.localSaved) "端末に保持済み" else "端末に保存中"}",
                        )
                        answerConstraint(
                            question,
                            draft.body,
                        )?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text("空白・改行を含めた文字数です。元フォームの算定との一致は未確認です。", style = MaterialTheme.typography.bodySmall)
                        if (draft.documentVersion >
                            0
                        ) {
                            Text("編集中の基準：第${draft.documentVersion}版", style = MaterialTheme.typography.bodySmall)
                            Text("サーバー保存版あり · GitHubへの同期状態は版履歴で確認", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.stale) Text("保存情報の再読込が必要です。入力は端末に保持します。")
                        if (state.cache.pending != null) {
                            Text("保存結果を確認できていません。入力は保持しています。")
                            TextButton(
                                onClick = model::retry,
                                enabled = !state.busy,
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("同じ保存の結果を確認") }
                            TextButton(
                                onClick = model::discardPending,
                                enabled = !state.busy,
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("保留を解除して編集を続ける") }
                        }
                        Button(
                            onClick = { showDiff = true },
                            enabled =
                                !state.busy && !state.stale && state.detail?.es_workspace == true &&
                                    state.cache.pending == null && (draft.changed || draft.documentId == null) &&
                                    draft.body.isNotBlank() &&
                                    answerConstraint(question, draft.body) == null &&
                                    !changedQuestion &&
                                    !newer,
                            modifier = Modifier.fillMaxWidth().testTag("career_answer_save"),
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(if (state.busy) "保存中…" else "回答を保存") }
                        TextButton(onClick = {
                            consult("この設問の回答案を、保存済みの本人情報と編集中の本文を使って用意してください。不足だけ1〜2問ずつ聞いてください。")
                        }) { Text("保存情報から回答案を用意・相談") }
                        TextButton(onClick = {
                            coroutine.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText(questionTitle(question), draft.body)),
                                )
                            }
                        }) { Text("本文をコピー") }
                    }
                    val documents = model.documents(id)
                    documents.forEach { entity ->
                        TextButton(
                            onClick = { versionDocument = entity },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("保存案・版履歴：${entity.title}") }
                    }
                    if (changedQuestion ||
                        newer
                    ) {
                        TextButton(
                            onClick = model::rebase,
                            enabled = !state.stale && state.cache.pending == null,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text("原文・保存案を確認したので、この本文で続ける")
                        }
                    }
                    val work = state.detail?.answerWork(model.offerId, id)
                    val adopted = work?.get("adopted") as? JsonObject
                    if (adopted != null) {
                        Text(
                            "採用中：第${adopted.text(
                                "version",
                            )}版 · ${if (work["reviewed"] == adopted) "本人確認済み" else "本人確認は未完了"}",
                        )
                        TextButton(
                            onClick = { model.save("review") },
                            enabled =
                                !draft.changed && adopted.text("id") == draft.documentId &&
                                    adopted.text(
                                        "version",
                                    ) == draft.documentVersion.toString() && !state.stale && !state.busy &&
                                    state.cache.pending == null &&
                                    !changedQuestion &&
                                    !newer,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("この回答を本人確認済みにする") }
                    }
                    Text("保存・コピー・本人確認では、企業への提出完了にはなりません。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (showDiff && draft != null) {
        AlertDialog(
            onDismissRequest = { showDiff = false },
            title = { Text("回答の変更を確認") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CareerDiff(
                        buildJsonObject { put("body", draft.baseBody) },
                        buildJsonObject { put("body", draft.body) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiff = false
                    model.save()
                }) { Text("この本文を保存") }
            },
            dismissButton = { TextButton(onClick = { showDiff = false }) { Text("編集へ戻る") } },
        )
    }
    versionDocument?.let { entity ->
        CareerDocumentReadDialog(entity, "offer:${model.offerId}", onDismiss = {
            versionDocument =
                null
        }, onAdopt = { version ->
            adoptRequest = entity to version
        })
    }
    adoptRequest?.let { (entity, version) ->
        AlertDialog(
            onDismissRequest = { adoptRequest = null },
            title = { Text("第${version}版を採用しますか") },
            text = { Text("編集中の本文をこの保存版に置き換えます。保存済みの以前の版は残ります。") },
            confirmButton = {
                TextButton(
                    enabled =
                        !state.busy && !state.stale && state.cache.pending == null &&
                            state.detail?.es_workspace == true,
                    onClick = {
                        model.save("adopt", entity, version)
                        adoptRequest = null
                        versionDocument =
                            null
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("この版を採用") }
            },
            dismissButton = { TextButton(onClick = { adoptRequest = null }) { Text("戻る") } },
        )
    }
}
