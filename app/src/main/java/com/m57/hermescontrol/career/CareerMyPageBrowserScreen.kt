package com.m57.hermescontrol.career

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Navigation holds only entity IDs; neither URLs nor secrets are serialized. */
@Composable
fun CareerMyPageBrowserScreen(key: CareerMyPageBrowserKey) {
    val connectionScope = remember(key) { careerScope() }
    val repository = remember(key) { CareerCompanyRepository(connectionScope) }
    val source = remember(key) { CareerBrowserCredentialSource(connectionScope) }
    val coroutineScope = rememberCoroutineScope()
    var detail by remember(key) { mutableStateOf<CareerCompanyDetail?>(null) }
    var status by remember(key) { mutableStateOf("マイページを読み込んでいます…") }
    var suspended by rememberSaveable(key) { mutableStateOf(false) }
    var refresh by rememberSaveable(key) { mutableIntStateOf(0) }
    var scopeInvalid by rememberSaveable(key) { mutableStateOf(false) }
    var view by remember(key) { mutableStateOf<CareerBrowserWebView?>(null) }
    var browserHost by remember(key) { mutableStateOf<CareerBrowserHost?>(null) }
    var tabLabels by remember(key) { mutableStateOf<List<Pair<CareerBrowserWebView, String>>>(emptyList()) }
    var activeOrigin by remember(key) { mutableStateOf<String?>(null) }
    var pageProgress by remember(key) { mutableIntStateOf(100) }
    var pickerPending by rememberSaveable(key) { mutableStateOf(false) }
    var chooserReference by remember(key) { mutableStateOf<CareerBrowserFileChooser?>(null) }
    var deferredPickerResult by remember(key) { mutableStateOf<ActivityResult?>(null) }
    val pickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val chooser = chooserReference
            if (chooser != null) chooser.onResult(it.resultCode, it.data) else deferredPickerResult = it
        }
    val context = LocalContext.current
    val fileChooser =
        remember(key, context) {
            CareerBrowserFileChooser(
                context = context,
                launch = { pickerLauncher.launch(it) },
                isOwnerCurrent = { owner, sequence ->
                    owner === view && owner is CareerBrowserWebView && !owner.isDisposed &&
                        owner.documentSequence == sequence && !scopeInvalid && connectionScope == careerScope()
                },
                initiallyAwaitingResult = pickerPending,
                onAwaitingResultChanged = { pickerPending = it },
            )
        }
    DisposableEffect(fileChooser) {
        chooserReference = fileChooser
        deferredPickerResult?.let {
            deferredPickerResult = null
            fileChooser.onResult(it.resultCode, it.data)
        }
        onDispose {
            fileChooser.close()
            if (chooserReference === fileChooser) chooserReference = null
        }
    }
    var closing by remember(key) { mutableStateOf(false) }
    var fillJob by remember(key) { mutableStateOf<Job?>(null) }
    var generation by remember(key, refresh) { mutableIntStateOf(0) }
    var attempted by remember(key, refresh) { mutableIntStateOf(-1) }
    var requested by rememberSaveable(key, refresh) { mutableStateOf(false) }
    var passwordStepPending by remember(key, refresh) { mutableStateOf(false) }
    var statusDetails by remember(key) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    val sessionGate =
        remember(key) { CareerBrowserSessionGate(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var foreground by remember(key) { mutableStateOf(sessionGate.isForeground) }
    var pausedDocument by remember(key) { mutableStateOf<Pair<String?, String?>?>(null) }

    fun cancelPendingFill() {
        sessionGate.invalidate()
        fillJob?.cancel()
        fillJob = null
    }

    fun disposeBrowser() {
        cancelPendingFill()
        suspended = true
        fileChooser.cancel()
        val host = browserHost
        browserHost = null
        view = null
        tabLabels = emptyList()
        activeOrigin = null
        pageProgress = 100
        host?.disposeBrowser()
    }

    fun validateScope(): Boolean {
        if (connectionScope != careerScope()) {
            scopeInvalid = true
            disposeBrowser()
            detail = null
            status = "接続先が変わりました。企業画面から開き直してください。"
        }
        return !scopeInvalid
    }
    LaunchedEffect(key, refresh) {
        detail = null
        try {
            detail = repository.company(key.companyId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = "接続情報を取得できません。企業画面に戻って再確認してください。"
        }
    }
    LaunchedEffect(connectionScope) {
        while (true) {
            delay(250)
            if (!validateScope()) break
        }
    }
    val current = detail
    val url =
        if (key.linkId == null) {
            current?.mypage?.portal_url
        } else {
            current
                ?.links
                ?.firstOrNull { it.id == key.linkId && it.data.text("type") == "MyPage" }
                ?.data
                ?.text("url")
        }
    val policy = remember(url) { CareerBrowserPolicy.create(url) }

    fun close() {
        if (closing) return
        closing = true
        disposeBrowser()
        NavigationController.goBackFrom(key)
    }

    fun back() {
        if (closing) return
        val browser = view
        if (browser != null && !browser.isDisposed && browser.canGoBack()) {
            browser.goBack()
        } else if (browser != null && browserHost?.closeTab(browser) == true) {
            status = "元のタブに戻りました。"
        } else {
            close()
        }
    }
    BackHandler { back() }

    fun reopen() {
        if (scopeInvalid) {
            close()
            return
        }
        disposeBrowser()
        detail = null
        status = "マイページを読み込んでいます…"
        suspended = false
        foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (foreground) sessionGate.resume()
        sessionGate.allowAutomaticFill()
        refresh++
    }

    fun requestFill(
        browser: CareerBrowserWebView,
        manual: Boolean = false,
    ) {
        val fillPolicy = policy ?: return
        val company = current ?: return
        if (!sessionGate.isForeground || closing || suspended || scopeInvalid || browser.isDisposed ||
            view !== browser || !browser.allowsCredentialFill
        ) {
            return
        }
        if (fillJob?.isActive == true || (!manual && !sessionGate.automaticFillAllowed)) return
        if (requested && !passwordStepPending && !manual) return
        // Credentials stay bound to the saved registered URL; the server never learns the landing page.
        if (!fillPolicy.allowsFill(company.mypage?.portal_url)) {
            status = "登録したログインURLと異なるため入力しません。企業の接続設定を確認してください。"
            return
        }
        val documentToken = browser.documentToken?.takeIf { browser.allowsDocumentFill() }
        if (documentToken == null) {
            status =
                if (browser.documentToken == null && fillPolicy.allowsFill(browser.url)) {
                    "このページでは接続を安全に確認できません。開き直すか手動で入力してください。"
                } else {
                    "最初に開いたページとは異なるため入力しません。開き直すと最初のページで入力します。"
                }
            return
        }
        // The first landing (after the registered URL's redirects) is authorized for this document only.
        val landing = browser.landing?.takeIf { it.token == documentToken }
        if (manual) sessionGate.allowAutomaticFill()
        val documentGeneration = generation
        val fillEpoch = sessionGate.epoch
        val passwordContinuation = passwordStepPending
        fillJob =
            coroutineScope.launch {
                fun documentCurrent(): Boolean =
                    sessionGate.isCurrent(fillEpoch) && !closing && !suspended && !scopeInvalid &&
                        !browser.isDisposed && view === browser &&
                        generation == documentGeneration && connectionScope == careerScope() &&
                        browser.documentToken == documentToken && browser.allowsDocumentFill()
                try {
                    status = if (passwordContinuation) "パスワード欄を待っています。サイトの次へ進む操作をしてください。" else "ログイン入力欄を確認中…"
                    var readiness =
                        awaitCareerBrowserForm(
                            browser,
                            fillPolicy,
                            documentToken,
                            passwordContinuation,
                            landing,
                            ::documentCurrent,
                        )
                    if (!documentCurrent()) return@launch
                    var selection: CareerBrowserLoginSelection? = null
                    if (readiness == "\"ambiguous\"" || readiness == "\"unsupported\"") {
                        suspend fun evaluate(script: String): String? {
                            val value = CompletableDeferred<String>()
                            if (!documentCurrent()) return null
                            browser.evaluateJavascript(script) { value.complete(it) }
                            return withTimeoutOrNull(3_000) { value.await() }?.takeIf { documentCurrent() }
                        }
                        status = "軽量モデルでログイン欄を特定中…"
                        val snapshot =
                            evaluate(CareerBrowserFill.candidateScript(fillPolicy, documentToken, landing))
                                ?.let(CareerBrowserLoginSnapshot::parse)
                        if (snapshot != null) {
                            selection =
                                source.locateLogin(
                                    key.companyId,
                                    company.mypage?.connection_version ?: 0,
                                    fillPolicy,
                                    snapshot,
                                    ::documentCurrent,
                                )
                        }
                        if (!documentCurrent()) return@launch
                        if (selection == null) {
                            status = source.lastFailure?.userMessage ?: "軽量モデルでログイン欄を特定できません。接続とモデル設定を確認してください。"
                            return@launch
                        }
                        readiness = evaluate(
                            CareerBrowserFill.readinessScript(
                                fillPolicy,
                                documentToken,
                                passwordContinuation,
                                selection,
                                landing,
                            ),
                        ) ?: return@launch
                    }
                    val step =
                        when (readiness) {
                            "\"ready\"" -> {
                                CareerBrowserFillStep.COMBINED
                            }

                            "\"ready_id\"" -> {
                                CareerBrowserFillStep.IDENTIFIER
                            }

                            "\"ready_password\"" -> {
                                if (passwordContinuation) CareerBrowserFillStep.PASSWORD else null
                            }

                            "\"ready_password_with_id\"" -> {
                                if (passwordContinuation) CareerBrowserFillStep.PASSWORD_WITH_IDENTIFIER else null
                            }

                            else -> {
                                null
                            }
                        }
                    if (step == null) {
                        status =
                            if (passwordContinuation && readiness == "\"unsupported\"") {
                                "パスワード欄へ進んだら上部の鍵ボタンで入力できます。別URLでは手動入力してください。"
                            } else {
                                browserFillStatus(readiness)
                            }
                        return@launch
                    }
                    requested = true
                    status = "Hermesの保存済み接続を確認中…"
                    val evaluation = CompletableDeferred<String>()
                    val queued =
                        source.fillCurrentDocument(
                            key.companyId,
                            company.mypage?.connection_version ?: 0,
                            fillPolicy,
                            ::documentCurrent,
                        ) { credentials ->
                            if (documentCurrent()) {
                                browser.evaluateJavascript(
                                    CareerBrowserFill.script(
                                        fillPolicy,
                                        credentials,
                                        documentToken,
                                        step,
                                        selection,
                                        landing,
                                    ),
                                ) {
                                    evaluation.complete(it)
                                }
                                true
                            } else {
                                false
                            }
                        }
                    if (!documentCurrent()) return@launch
                    if (!queued) {
                        status = source.lastFailure?.userMessage ?: "ページが変わったため入力を止めました。鍵ボタンで再確認できます。"
                        return@launch
                    }
                    val result = withTimeoutOrNull(3_000) { evaluation.await() }
                    if (!documentCurrent()) return@launch
                    when (result) {
                        "\"filled_id\"" -> {
                            passwordStepPending = true
                            requested = false
                        }

                        "\"filled\"", "\"filled_password\"" -> {
                            passwordStepPending = false
                        }
                    }
                    status = browserFillStatus(result ?: "\"unsupported\"")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (documentCurrent()) status = "自動入力を完了できません。鍵ボタンで再確認するか、手動入力してください。"
                }
            }
    }

    fun requestAutomaticFill(browser: CareerBrowserWebView) {
        if (sessionGate.shouldAutomaticallyFill(browser.documentToken != null, attempted, generation)) {
            attempted = generation
            requestFill(browser)
        }
    }
    LaunchedEffect(passwordStepPending, generation, view) {
        val epoch = sessionGate.epoch
        if (passwordStepPending && sessionGate.isCurrent(epoch) && !suspended) {
            delay(250)
            if (sessionGate.isCurrent(epoch)) view?.let { requestFill(it) }
        }
    }
    // The lifecycle owner can outlive a refresh; always dispatch to this composition's callbacks.
    val currentLifecycleObserver by rememberUpdatedState(
        careerBrowserLifecycleObserver(
            pauseSession = {
                if (sessionGate.isForeground) pausedDocument = view?.let { it.url to it.documentToken }
                sessionGate.pause()
                browserHost?.setForeground(false)
                foreground = false
                attempted = generation
                val interrupted = fillJob?.isActive == true
                cancelPendingFill()
                if (interrupted) status = "自動入力を中断しました。続ける場合は鍵ボタンで入力できます。"
            },
            resumeSession = {
                // Recheck the connection synchronously, before any retained WebView resumes.
                val valid = validateScope() && !closing && !suspended
                if (valid) {
                    sessionGate.resume()
                    browserHost?.setForeground(true)
                    // A background navigation must not inherit the previous password-step authority.
                    val document = view?.let { it.url to it.documentToken }
                    if (document != pausedDocument || view?.documentToken == null ||
                        view?.allowsDocumentFill() != true
                    ) {
                        passwordStepPending = false
                    }
                    pausedDocument = null
                }
                foreground = valid
                valid
            },
            browser = { view },
            onResumed = {
                // Initial composition may finish loading before its Nav3 owner first reaches RESUMED.
                // A real pause disarms automaticFillAllowed, so returning never revives cancelled work.
                view?.let { requestAutomaticFill(it) }
            },
            disposeSession = {
                sessionGate.pause()
                foreground = false
                disposeBrowser()
            },
        ),
    )
    DisposableEffect(lifecycle, key) {
        val observer = LifecycleEventObserver { owner, event -> currentLifecycleObserver.onStateChanged(owner, event) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // AndroidView.onRelease owns actual removal. No secret-bearing work survives disposal.
            currentLifecycleObserver.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_PAUSE)
        }
    }
    val toolbarHeight = maxOf(56f, 36f * LocalDensity.current.fontScale + 8f).dp
    HermesScaffold(
        title = { CareerBrowserTitle(current?.company?.title, activeOrigin) },
        topBarHeight = toolbarHeight,
        showShellMenu = false,
        pinTopBar = true,
        navigationIcon = NavIcon.Back { back() },
        drawerGesturesEnabled = false,
        // Three long text actions do not fit beside the title at 360dp or large font sizes;
        // keep the direct exit visible and move the other two actions, unchanged, into the menu.
        actions = {
            var menu by remember { mutableStateOf(false) }
            IconButton(
                onClick = { view?.let { requestFill(it, manual = true) } },
                enabled = view?.allowsDocumentFill() == true && foreground && !suspended,
            ) {
                Icon(Icons.Outlined.Key, contentDescription = "Hermesの保存済みID・パスワードを入力")
            }
            IconButton(onClick = { close() }) {
                Icon(Icons.Outlined.Close, contentDescription = "ブラウザを閉じる")
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "マイページのその他の操作")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("再読込") },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                        enabled = view != null && foreground && !suspended && !scopeInvalid,
                        onClick = {
                            menu = false
                            if (validateScope()) {
                                cancelPendingFill()
                                generation++
                                passwordStepPending = false
                                status = "読み込み中…"
                                view?.reloadCurrentPage()
                            }
                        },
                    )
                    DropdownMenuItem(text = { Text("進む") }, enabled = view?.canGoForward() == true, onClick = {
                        menu = false
                        if (validateScope()) view?.goForward()
                    })
                    DropdownMenuItem(
                        text = { Text("このタブを閉じる") },
                        enabled = view != null && view !== browserHost?.browser,
                        onClick = {
                            menu = false
                            view?.let { browserHost?.closeTab(it) }
                        },
                    )
                    DropdownMenuItem(text = { Text("状態の詳細") }, onClick = {
                        menu = false
                        statusDetails = true
                    })
                    DropdownMenuItem(text = { Text("自動入力を停止") }, onClick = {
                        menu = false
                        disposeBrowser()
                        coroutineScope.launch {
                            val revoked =
                                try {
                                    source.revoke(key.companyId)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                            status =
                                if (revoked) {
                                    "自動入力を停止し、この企業の一時許可を取り消しました。開き直すと自動入力を再開します。"
                                } else {
                                    "この画面の自動入力は停止しました。一時許可の取消を確認できません。通信を確認し、もう一度停止してください。"
                                }
                        }
                    })
                    DropdownMenuItem(
                        text = { Text(if (scopeInvalid) "企業画面に戻る" else "開き直す") },
                        onClick = {
                            menu = false
                            reopen()
                        },
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().testTag("mypage_browser")) {
            CareerBrowserStatusBar(
                status = status,
                progress = pageProgress.takeIf { view != null && !suspended && it < 100 }?.let { it / 100f },
                onShowDetails = { statusDetails = true },
            )
            if (tabLabels.size > 1) {
                CareerBrowserTabStrip(
                    tabs = tabLabels,
                    active = view,
                    primary = browserHost?.browser,
                    onSelect = { browserHost?.select(it) },
                    onClose = { browserHost?.closeTab(it) },
                )
            }
            if (suspended) {
                CareerBrowserNotice(
                    icon = if (scopeInvalid) Icons.Outlined.LinkOff else Icons.Outlined.Public,
                    title = if (scopeInvalid) "接続先が変わりました" else "ブラウザを閉じました",
                    body =
                        if (scopeInvalid) {
                            "安全のためこの画面を閉じました。企業画面から開き直してください。"
                        } else {
                            "開き直すと保存済みの接続で自動入力を再開します。"
                        },
                    actionLabel = if (scopeInvalid) "企業画面に戻る" else "開き直す",
                    onAction = { reopen() },
                )
            } else if (current != null && policy == null) {
                CareerBrowserNotice(
                    icon = Icons.Outlined.LinkOff,
                    title = "このURLは開けません",
                    body = "内部ブラウザはHTTPSのマイページだけを開きます。企業の接続設定でURLを確認してください。",
                    actionLabel = "企業画面に戻る",
                    onAction = { close() },
                )
            } else if (current == null) {
                if (careerBrowserStatusTone(status) == CareerBrowserStatusTone.BUSY) {
                    LoadingState(subtitle = "マイページを準備しています")
                } else {
                    CareerBrowserNotice(
                        icon = Icons.Outlined.ErrorOutline,
                        title = "マイページを開けません",
                        body = status,
                        actionLabel = "開き直す",
                        onAction = { reopen() },
                    )
                }
            } else if (policy != null) {
                androidx.compose.runtime.key(key, refresh, url) {
                    val sessionRefresh = refresh
                    AndroidView(
                        modifier = Modifier.weight(1f),
                        factory = { context ->
                            lateinit var host: CareerBrowserHost

                            fun updateAddress(browser: CareerBrowserWebView?) {
                                activeOrigin = browser?.url?.let(::careerBrowserDisplayOrigin)
                                pageProgress = browser?.takeUnless { it.isDisposed }?.progress ?: 100
                                tabLabels =
                                    host.tabs.map { tab ->
                                        tab to (
                                            tab.title?.takeIf { it.isNotBlank() }?.take(24)
                                                ?: careerBrowserDisplayOrigin(tab.url).orEmpty().ifBlank { "新しいタブ" }
                                        )
                                    }
                            }
                            host =
                                CareerBrowserHost(context) { selected ->
                                    if (view !== selected) {
                                        fileChooser.cancel()
                                        cancelPendingFill()
                                        generation++
                                        passwordStepPending = false
                                    }
                                    view = selected
                                    updateAddress(selected)
                                }
                            browserHost = host
                            try {
                                fun makeBrowser(popup: Boolean): CareerBrowserWebView =
                                    CareerBrowserWebView(
                                        context,
                                        policy,
                                        allowsCredentialFill = !popup,
                                        onStarted = {},
                                        onBlocked = {
                                            status = "この移動または読込みは利用できません。HTTPS接続とサイトの対応を確認してください。"
                                        },
                                        onManualPage = {
                                            status = "ページを読み込みました。"
                                        },
                                        onDownload = { request ->
                                            status = careerBrowserStartDownload(context, policy, request)
                                        },
                                        onResubmissionRequired = {
                                            status = "送信済みのフォームは再送しません。ページ内のリンクから移動してください。"
                                        },
                                        onStateChanged = { browser ->
                                            if (host.activeBrowser === browser) updateAddress(browser)
                                        },
                                        onDocumentChanged = { browser ->
                                            fileChooser.cancel(browser)
                                            if (view === browser) {
                                                generation++
                                                cancelPendingFill()
                                                status = "読み込み中…"
                                            }
                                        },
                                        onChooseFile = { browser, callback, params ->
                                            if (view === browser && foreground && validateScope()) {
                                                fileChooser.show(browser, browser.documentSequence, callback, params)
                                            } else {
                                                false
                                            }
                                        },
                                        onCreateTab = { opener, result ->
                                            val opened = host.openPopup(opener, result) { makeBrowser(true) }
                                            if (!opened) status = "新しいタブを開けません。一時タブは最大${CareerBrowserHost.MAX_TABS}枚です。"
                                            opened
                                        },
                                        onCloseTab = { browser -> host.closeTab(browser) },
                                        onGone = { browser ->
                                            if (!host.closeTab(browser) && host.browser === browser) {
                                                disposeBrowser()
                                                status = "ブラウザを終了しました。開き直してください。"
                                            }
                                        },
                                        onFinished = { browser, _ ->
                                            if (view === browser && browser.allowsCredentialFill) {
                                                if (!sessionGate.automaticFillAllowed) {
                                                    status = "ページを読み込みました。自動入力は鍵ボタンで再開できます。"
                                                } else {
                                                    requestAutomaticFill(browser)
                                                }
                                            }
                                        },
                                    )
                                val browser = makeBrowser(false)
                                host.attachBrowser(browser)
                                host.setForeground(foreground)
                                browser.start()
                            } catch (_: Exception) {
                                host.disposeBrowser()
                                browserHost = null
                                view = null
                                suspended = true
                                status = "内部ブラウザを起動できません。Android System WebViewを確認して開き直してください。"
                            }
                            host
                        },
                        onRelease = { host ->
                            // An outgoing composition must not cancel a newer browser's work.
                            if (browserHost === host) {
                                if (refresh == sessionRefresh) suspended = true
                                cancelPendingFill()
                                fileChooser.cancel()
                                view = null
                                browserHost = null
                                tabLabels = emptyList()
                                activeOrigin = null
                                pageProgress = 100
                            }
                            host.disposeBrowser()
                        },
                        update = {},
                    )
                }
            }
        }
    }
    if (statusDetails) {
        AlertDialog(
            onDismissRequest = { statusDetails = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text("内部ブラウザの状態") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md)) {
                    CareerBrowserDetailRow("接続先", activeOrigin ?: "確認中")
                    CareerBrowserDetailRow("状態", status)
                    source.transportWarning?.let { CareerBrowserDetailRow("Hermesサーバーとの通信", it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { statusDetails = false }, shapes = ButtonDefaults.shapes()) { Text("閉じる") }
            },
        )
    }
}

/** One compact company/address block instead of two full-width header rows. */
@Composable
internal fun CareerBrowserTitle(
    company: String?,
    origin: String?,
) {
    Column {
        Text(
            company ?: "マイページ",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (origin != null) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = "HTTPS",
                    modifier = Modifier.size(12.dp),
                    tint = LocalHermesStatusColors.current.success,
                )
            }
            Text(
                origin?.removePrefix("https://") ?: "接続先を確認中",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal enum class CareerBrowserStatusTone { BUSY, DONE, ATTENTION, INFO }

/** Visual tone only; the status text itself stays the source of truth for the user. */
internal fun careerBrowserStatusTone(status: String): CareerBrowserStatusTone =
    when {
        status.endsWith("…") -> CareerBrowserStatusTone.BUSY
        ATTENTION_MARKERS.any { it in status } -> CareerBrowserStatusTone.ATTENTION
        DONE_MARKERS.any { it in status } -> CareerBrowserStatusTone.DONE
        else -> CareerBrowserStatusTone.INFO
    }

private val ATTENTION_MARKERS =
    listOf("できません", "中断", "異なる", "変わりました", "終了しました", "閉じました", "停止", "再送しません", "最大")
private val DONE_MARKERS = listOf("入力しました", "設定しました", "読み込みました", "戻りました", "保持しています")

/** One status line under the toolbar; a hairline becomes the page-load bar while a document loads. */
@Composable
private fun CareerBrowserStatusBar(
    status: String,
    progress: Float?,
    onShowDetails: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current
    val tone = careerBrowserStatusTone(status)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clickable(onClickLabel = "状態の詳細を表示", role = Role.Button, onClick = onShowDetails)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                when (tone) {
                    CareerBrowserStatusTone.BUSY -> {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    }

                    CareerBrowserStatusTone.DONE -> {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = statusColors.success)
                    }

                    CareerBrowserStatusTone.ATTENTION -> {
                        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = statusColors.warning)
                    }

                    CareerBrowserStatusTone.INFO -> {
                        Icon(
                            Icons.Outlined.Info,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                status,
                Modifier.weight(1f).testTag("mypage_browser_status"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp), contentAlignment = Alignment.BottomCenter) {
            if (progress != null) {
                val animated by animateFloatAsState(progress, MaterialTheme.motionScheme.defaultEffectsSpec())
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxSize().testTag("mypage_browser_progress"),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Temporary popup tabs as pills: the active one is a full pill, the original tab cannot be closed. */
@Composable
private fun CareerBrowserTabStrip(
    tabs: List<Pair<CareerBrowserWebView, String>>,
    active: CareerBrowserWebView?,
    primary: CareerBrowserWebView?,
    onSelect: (CareerBrowserWebView) -> Unit,
    onClose: (CareerBrowserWebView) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.md, vertical = spacing.xs)
                .testTag("browser_tabs"),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, (tab, label) ->
                val selected = tab === active
                InputChip(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    label = {
                        Text(
                            "${index + 1}  $label",
                            Modifier.widthIn(max = 180.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    shape = if (selected) CircleShape else MaterialTheme.shapes.small,
                    trailingIcon =
                        if (tab !== primary) {
                            {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "タブ${index + 1}を閉じる",
                                    modifier =
                                        Modifier
                                            .size(InputChipDefaults.IconSize)
                                            .clip(CircleShape)
                                            .clickable(role = Role.Button) { onClose(tab) },
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Centered, flat state for a closed or unusable browser: shaped icon, short title, one action. */
@Composable
private fun CareerBrowserNotice(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 420.dp)
                .padding(horizontal = spacing.xl, vertical = spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(spacing.lg))
            Text(
                title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.xl))
            Button(onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(actionLabel) }
        }
    }
}

@Composable
private fun CareerBrowserDetailRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Poll only metadata, never retain credentials while waiting for a portal's dynamic form. */
internal suspend fun awaitCareerBrowserForm(
    browser: CareerBrowserWebView,
    policy: CareerBrowserPolicy,
    documentToken: String,
    allowPasswordOnly: Boolean = false,
    landing: CareerBrowserLanding? = null,
    isCurrent: () -> Boolean,
): String =
    withTimeoutOrNull(10_000) wait@{
        repeat(40) {
            if (!isCurrent()) return@wait "\"blocked\""
            val result =
                withTimeoutOrNull(1_000) {
                    suspendCancellableCoroutine<String> { continuation ->
                        if (!isCurrent()) {
                            continuation.resume("\"blocked\"") { _, _, _ -> }
                        } else {
                            browser.evaluateJavascript(
                                CareerBrowserFill.readinessScript(
                                    policy,
                                    documentToken,
                                    allowPasswordOnly,
                                    landing = landing,
                                ),
                            ) { value ->
                                if (continuation.isActive) continuation.resume(value) { _, _, _ -> }
                            }
                        }
                    }
                } ?: return@wait "\"unsupported\""
            if (!isCurrent()) return@wait "\"blocked\""
            if (result != "\"pending\"") return@wait result
            delay(250)
        }
        "\"pending\""
    } ?: "\"pending\""

/** App switching suspends work, not the document. Destruction remains an explicit ownership event. */
internal fun careerBrowserLifecycleObserver(
    pauseSession: () -> Unit,
    resumeSession: () -> Boolean,
    browser: () -> CareerBrowserWebView?,
    disposeSession: () -> Unit,
    onResumed: () -> Unit = {},
): LifecycleEventObserver =
    LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                pauseSession()
                browser()?.takeUnless { it.isDisposed }?.onPause()
            }

            Lifecycle.Event.ON_RESUME -> {
                if (resumeSession()) {
                    browser()?.takeUnless { it.isDisposed }?.onResume()
                    onResumed()
                }
            }

            Lifecycle.Event.ON_DESTROY -> {
                disposeSession()
            }

            else -> {
                Unit
            }
        }
    }

internal fun browserFillStatus(result: String): String =
    when (result) {
        "\"filled_id\"" -> "IDを入力しました。サイトの次へ進む操作後、パスワード欄を確認します。"
        "\"filled_password\"" -> "パスワードを入力しました。内容を確認し、ログインボタンを押してください。"
        "\"filled\"" -> "入力欄に値を設定しました。IDとパスワードの欄を確認してログインボタンを押してください。"
        "\"occupied\"" -> "入力済みの値を保持しています。ログインボタンはご自身で押してください。"
        "\"pending\"" -> "ログイン入力欄がまだ見つかりません。表示後に鍵ボタンで再確認できます。"
        "\"ambiguous\"" -> "ログイン欄を一意に特定できないため入力していません。手動で入力してください。"
        "\"unsafe_action\"" -> "開いたログインページと同じ送信先か確認できないため入力していません。手動で入力してください。"
        "\"blocked\"", "\"changed\"" -> "ページまたは入力欄が変わったため入力を止めました。鍵ボタンで再確認できます。"
        "\"not_retained\"" -> "サイトが入力値を保持できませんでした。手動で入力してください。"
        else -> "このログインフォームには自動入力できません。手動で入力してください。"
    }

/** Show the actual tab's HTTPS origin, never a login-token-bearing path/query or stale initial host. */
internal fun careerBrowserDisplayOrigin(value: String?): String? {
    val uri = value?.let(Uri::parse) ?: return null
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
    return "https://${uri.host}${if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""}"
}
