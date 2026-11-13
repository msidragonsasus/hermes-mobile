package com.m57.hermescontrol.career

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Keep the site's own policies; a synthetic same-origin CSP breaks modern portals and SSO. */
internal fun careerBrowserResponseHeaders(
    origin: Map<String, List<String>>,
    token: String,
): Map<String, String> {
    val replaced = setOf("set-cookie", "content-encoding", "content-length", "cache-control", "server-timing")
    return origin
        .filterKeys { it.lowercase() !in replaced }
        .mapValues { it.value.joinToString(", ") }
        .toMutableMap()
        .apply {
            this["Cache-Control"] = "no-store"
            this["Server-Timing"] = "hermes;desc=\"$token\""
        }
}

/** One root session owns the WebView jar; children share it without acquiring or releasing this lease. */
internal object CareerBrowserLease {
    private var owner: WebView? = null
    private val waiting = ArrayDeque<Pair<WebView, () -> Unit>>()

    @Synchronized
    fun acquire(
        view: WebView,
        ready: () -> Unit,
    ) {
        if (owner != null) {
            waiting.addLast(view to ready)
        } else {
            owner = view
            ready()
        }
    }

    @Synchronized
    fun cancel(view: WebView) {
        waiting.removeAll { it.first === view }
    }

    @Synchronized
    fun release(view: WebView) {
        if (owner !== view) return
        owner = null
        if (waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            owner = next.first
            next.second()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal class CareerBrowserWebView(
    context: Context,
    private val policy: CareerBrowserPolicy,
    private val onStarted: () -> Unit,
    private val onFinished: (CareerBrowserWebView, String?) -> Unit,
    private val onBlocked: () -> Unit,
    private val onManualPage: () -> Unit,
    private val onGone: (CareerBrowserWebView) -> Unit,
    networkClient: OkHttpClient? = null,
    private val onResubmissionRequired: () -> Unit = onBlocked,
    internal val allowsCredentialFill: Boolean = true,
    private val onStateChanged: (CareerBrowserWebView) -> Unit = {},
    private val onCreateTab: (CareerBrowserWebView, Message) -> Boolean = { _, _ -> false },
    private val onCloseTab: (CareerBrowserWebView) -> Unit = {},
    private val onChooseFile: (
        CareerBrowserWebView,
        ValueCallback<Array<Uri>>,
        WebChromeClient.FileChooserParams,
    ) -> Boolean = { _, _, _ -> false },
    private val onDocumentChanged: (CareerBrowserWebView) -> Unit = {},
    private val onDownload: (CareerBrowserDownloadRequest) -> Unit = { onBlocked() },
) : WebView(context) {
    @Volatile
    private var disposed = false
    internal val isDisposed: Boolean
        get() = disposed
    internal var documentSequence: Long = 0
        private set
    private var sessionChild = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var leased = false
    private var started = false
    private val redirects = AtomicInteger(0)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val openResponses = ConcurrentHashMap.newKeySet<Response>()
    private val cookieOperations = Any()

    private class NativeDocument(
        val url: String,
        val token: String,
        val request: Long,
    )

    private val nativeDocument = AtomicReference<NativeDocument?>(null)
    private val landingChain = CareerBrowserLandingChain()

    @Volatile
    var documentToken: String? = null
        private set

    /** This root session's first landing document, if it is still the current one. */
    internal val landing: CareerBrowserLanding?
        get() = landingChain.landing

    /** Main thread only: whether the current document may receive the registered credentials. */
    internal fun allowsDocumentFill(): Boolean =
        allowsCredentialFill && !disposed && policy.allowsDocumentFill(url, documentToken, landing)

    private var discardRedirectHistory = false
    private val network =
        networkClient ?: OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    init {
        WebView.setWebContentsDebuggingEnabled(false)
        CareerBrowserServiceWorkerGuard.install()
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        if (Build.VERSION.SDK_INT >=
            30
        ) {
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        isLongClickable = true
        filterTouchesWhenObscured = true
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            @Suppress("DEPRECATION")
            databaseEnabled = false
            @Suppress("DEPRECATION")
            saveFormData = false
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        // Files are selected explicitly through Android. Camera/microphone/location remain denied.
        webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true

                override fun onProgressChanged(
                    view: WebView,
                    newProgress: Int,
                ) {
                    if (!disposed) onStateChanged(this@CareerBrowserWebView)
                }

                override fun onReceivedTitle(
                    view: WebView,
                    title: String?,
                ) {
                    if (!disposed) onStateChanged(this@CareerBrowserWebView)
                }

                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message,
                ): Boolean {
                    if (disposed || !isUserGesture) return false
                    return onCreateTab(this@CareerBrowserWebView, resultMsg)
                }

                override fun onCloseWindow(window: WebView) {
                    if (!disposed) onCloseTab(this@CareerBrowserWebView)
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: WebChromeClient.FileChooserParams,
                ): Boolean = !disposed && onChooseFile(this@CareerBrowserWebView, filePathCallback, fileChooserParams)

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.deny()
                }
            }
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!disposed) {
                onDownload(CareerBrowserDownloadRequest(url, userAgent, contentDisposition, mimeType, this.url))
            }
        }
        webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (disposed) return true
                    if (request.url.toString() == "about:blank") return false
                    val blocked = !policy.allowsNavigation(request.url.toString())
                    if (blocked) onBlocked()
                    return blocked
                }

                override fun onPageStarted(
                    view: WebView,
                    url: String?,
                    favicon: Bitmap?,
                ) {
                    if (disposed) return
                    documentToken = null
                    landingChain.documentStarted()
                    documentSequence++
                    onDocumentChanged(this@CareerBrowserWebView)
                    onStarted()
                    onStateChanged(this@CareerBrowserWebView)
                    if (url != "about:blank" && !policy.allowsNavigation(url)) {
                        stopLoading()
                        onBlocked()
                    }
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    if (disposed) return
                    val candidate = nativeDocument.get()
                    val served = candidate?.takeIf { url == it.url && nativeDocument.compareAndSet(it, null) }
                    if (served != null && url == served.url && policy.allowsNavigation(url)) {
                        documentToken = served.token
                        // Only the initial load's own document can become the landing; it is never re-granted.
                        landingChain.grant(served.request, url, served.token)
                        if (discardRedirectHistory) {
                            clearHistory()
                            discardRedirectHistory = false
                        }
                        onStateChanged(this@CareerBrowserWebView)
                        onFinished(this@CareerBrowserWebView, url)
                    } else {
                        onStateChanged(this@CareerBrowserWebView)
                        if (!disposed && redirects.get() == 0 && policy.allowsNavigation(url)) onManualPage()
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    val mainFrameRequest =
                        if (request.isForMainFrame) {
                            nativeDocument.set(null)
                            documentToken = null
                            landingChain.request(url, request.method)
                        } else {
                            0L
                        }
                    if (disposed) return blockedResponse()
                    if (url == "about:blank") return null
                    if (!request.isForMainFrame && (url.startsWith("data:") || url.startsWith("blob:"))) return null
                    if (!policy.allowsResource(url)) return blockedResponse()
                    if (request.isForMainFrame && !policy.allowsNavigation(url)) return blockedResponse()
                    // Only registered-scope GETs and the initial load's redirect chain need the native document
                    // identity for autofill. Other documents, frames and resources use the normal Chromium stack.
                    if (!allowsCredentialFill || !request.isForMainFrame || request.method != "GET" ||
                        !(policy.allowsFill(url) || landingChain.continues(mainFrameRequest))
                    ) {
                        return null
                    }
                    return document(url, view, request.requestHeaders, mainFrameRequest)
                }

                override fun doUpdateVisitedHistory(
                    view: WebView,
                    url: String?,
                    isReload: Boolean,
                ) {
                    if (!disposed) onStateChanged(this@CareerBrowserWebView)
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                    if (!disposed) onBlocked()
                }

                override fun onFormResubmission(
                    view: WebView,
                    dontResend: Message,
                    resend: Message,
                ) {
                    // Reload must never silently repeat a login, reservation or other submitted form.
                    dontResend.sendToTarget()
                    if (!disposed) onResubmissionRequired()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (!disposed && request.isForMainFrame) onBlocked()
                }

                override fun onReceivedHttpAuthRequest(
                    view: WebView,
                    handler: HttpAuthHandler,
                    host: String?,
                    realm: String?,
                ) {
                    handler.cancel()
                    if (!disposed) onBlocked()
                }

                override fun onReceivedClientCertRequest(
                    view: WebView,
                    request: ClientCertRequest,
                ) {
                    request.cancel()
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    if (!disposed) {
                        dispose()
                        onGone(this@CareerBrowserWebView)
                    }
                    return true
                }
            }
    }

    fun start() {
        if (disposed || started) return
        started = true
        CareerBrowserLease.acquire(this) {
            if (disposed) {
                CareerBrowserLease.release(this)
                return@acquire
            }
            leased = true
            runCatching {
                clearCache(true)
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(this@CareerBrowserWebView, false)
                    removeAllCookies {
                        runCatching {
                            flush()
                            if (!disposed) {
                                // Popups never start(); only this root's initial load may reach a landing.
                                if (allowsCredentialFill) landingChain.begin(policy.loginUrl.toString())
                                loadUrl(policy.loginUrl.toString())
                            }
                        }.onFailure { if (!disposed) onBlocked() }
                    }
                }
            }.onFailure { if (!disposed) onBlocked() }
        }
    }

    /** A popup joins its root's lease without clearing cookies, storage or cache. */
    internal fun joinSession() {
        check(!started && !disposed)
        started = true
        sessionChild = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    }

    fun reloadCurrentPage() {
        if (disposed || (!leased && !sessionChild)) return
        documentToken = null
        nativeDocument.set(null)
        landingChain.end()
        redirects.set(0)
        reload()
    }

    private fun document(
        url: String,
        view: WebView,
        requestHeaders: Map<String, String>,
        mainFrameRequest: Long,
    ): WebResourceResponse {
        var awaiting: Response? = null
        var activeCall: Call? = null
        try {
            val request = Request.Builder().url(url)
            for (name in listOf("User-Agent", "Accept", "Accept-Language")) {
                requestHeaders.entries.firstOrNull { it.key.equals(name, true) }?.let { request.header(name, it.value) }
            }
            val call =
                synchronized(cookieOperations) {
                    if (disposed) return blockedResponse()
                    CookieManager.getInstance().getCookie(url)?.let { request.header("Cookie", it) }
                    network.newCall(request.build()).also {
                        activeCalls.add(it)
                        activeCall = it
                    }
                }
            // Registered before leaving the disposal lock, even if execute() has not started yet.
            val response = call.execute()
            awaiting = response
            openResponses.add(response)
            if (disposed) {
                return blockedResponse()
            }
            synchronized(cookieOperations) {
                if (!disposed) {
                    response.headers.values("Set-Cookie").forEach { CookieManager.getInstance().setCookie(url, it) }
                }
            }
            if (response.isRedirect) {
                val next =
                    response.header("Location")?.let {
                        response.request.url
                            .resolve(it)
                            ?.toString()
                    }
                closeResponse(response)
                mainHandler.post {
                    if (!disposed && redirects.incrementAndGet() <= 5 && policy.allowsNavigation(next)) {
                        // Server redirects of the initial load stay in its landing chain; nothing else joins.
                        landingChain.redirect(mainFrameRequest, next)
                        discardRedirectHistory = true
                        view.loadUrl(checkNotNull(next))
                    } else if (!disposed) {
                        onBlocked()
                    }
                }
                return blockedResponse()
            }
            redirects.set(0)
            val body =
                response.body ?: run {
                    closeResponse(response)
                    return blockedResponse()
                }
            val type = body.contentType()
            if (type?.subtype != "html" && type?.subtype != "xhtml+xml") {
                closeResponse(response)
                mainHandler.post { if (!disposed) onBlocked() }
                return blockedResponse()
            }
            val stream =
                object : FilterInputStream(body.byteStream()) {
                    override fun close() {
                        super.close()
                        closeResponse(response)
                    }
                }
            val token = UUID.randomUUID().toString()
            val headers = careerBrowserResponseHeaders(response.headers.toMultimap(), token)
            if (disposed) return blockedResponse()
            nativeDocument.set(NativeDocument(url, token, mainFrameRequest))
            val result =
                WebResourceResponse(
                    type.toString().substringBefore(';'),
                    type.charset()?.name(),
                    response.code,
                    response.message.ifBlank { "Response" },
                    headers,
                    stream,
                )
            awaiting = null
            return result
        } catch (_: Exception) {
            mainHandler.post { if (!disposed) onBlocked() }
            return blockedResponse()
        } finally {
            awaiting?.let(::closeResponse)
            activeCall?.let(activeCalls::remove)
        }
    }

    private fun closeResponse(response: Response) {
        openResponses.remove(response)
        response.close()
    }

    fun dispose() {
        synchronized(cookieOperations) {
            if (disposed) return
            disposed = true
        }
        documentToken = null
        landingChain.end()
        documentSequence++
        onDocumentChanged(this)
        nativeDocument.set(null)
        val completion = if (leased) CareerBrowserLeaseCompletion { CareerBrowserLease.release(this) } else null
        runCatching { stopLoading() }
        // Cancellation and response/TLS socket closure may perform network I/O. Never run
        // them on the UI thread, nor in a coroutine cancelled with this outgoing screen.
        // Invalidating disposed above prevents new call registration and document cookie access.
        runCatching {
            scheduleCareerBrowserNetworkCleanup(
                listOf(
                    { activeCalls.forEach { runCatching { it.cancel() } } },
                    { network.dispatcher.cancelAll() },
                    {
                        openResponses.forEach { runCatching { it.close() } }
                        openResponses.clear()
                    },
                    { network.connectionPool.evictAll() },
                    { network.dispatcher.executorService.shutdown() },
                ),
                onComplete = {
                    if (completion != null) mainHandler.post { completion.transportFinished() }
                },
            )
        }
        runCatching { clearHistory() }
        if (leased) {
            runCatching { clearCache(true) }
            runCatching { WebStorage.getInstance().deleteAllData() }
        }
        runCatching { (parent as? ViewGroup)?.removeView(this) }
        runCatching { destroy() }
        CareerBrowserLease.cancel(this)
        if (leased) {
            runCatching {
                CookieManager.getInstance().removeAllCookies {
                    runCatching { CookieManager.getInstance().flush() }
                        .onSuccess { completion?.cookiesFinished() }
                }
            }
        }
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(byteArrayOf()),
        )
}
