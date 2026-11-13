package com.m57.hermescontrol.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.Locale

/** Whether older output is shown, and whether new output arrived meanwhile. */
internal data class TerminalScroll(
    val atBottom: Boolean = true,
    val unseen: Boolean = false,
    val alternate: Boolean = false,
)

internal class TerminalDisplay(
    context: Context,
) : WebView(context) {
    var ready = false
    var renderedSession: String? = null
    var renderedOffset = 0L
    var snapshot = TerminalUiState()
    var appearance = ""
    private var renderedAppearance = ""
    var onSize: (Int, Int) -> Unit = { _, _ -> }
    var onFailure: () -> Unit = {}
    var onReport: (String) -> Unit = {}
    var onLink: (String) -> Unit = {}
    var onLongPress: (TerminalSelection) -> Unit = {}
    var onScroll: (TerminalScroll) -> Unit = {}
    private var scroll = TerminalScroll()
    var applicationCursorKeys = false
    private val statusPoll =
        object : Runnable {
            override fun run() {
                if (!ready) return
                evaluateJavascript("window.hermesTerminal.status()") { value ->
                    runCatching {
                        val status = Json.parseToJsonElement(value).jsonObject
                        applicationCursorKeys = status.getValue("applicationCursor").jsonPrimitive.content == "true"
                        onSize(
                            status
                                .getValue("cols")
                                .jsonPrimitive.content
                                .toInt(),
                            status
                                .getValue("rows")
                                .jsonPrimitive.content
                                .toInt(),
                        )
                        status.getValue("reports").jsonArray.take(16).forEach {
                            val report = it.jsonPrimitive.content
                            if (validTerminalReport(report)) onReport(report)
                        }
                        val next =
                            TerminalScroll(
                                atBottom = status.getValue("atBottom").jsonPrimitive.content == "true",
                                unseen = status.getValue("unseen").jsonPrimitive.content == "true",
                                alternate = status.getValue("alternate").jsonPrimitive.content == "true",
                            )
                        if (next != scroll) {
                            scroll = next
                            onScroll(next)
                        }
                    }
                }
                postDelayed(this, 250)
            }
        }

    fun startStatusPoll() {
        removeCallbacks(statusPoll)
        post(statusPoll)
    }

    fun stopStatusPoll() {
        removeCallbacks(statusPoll)
    }

    fun render() {
        if (!ready) return
        val first = snapshot.chunks.firstOrNull()?.start ?: snapshot.offset
        if (renderedSession != snapshot.selected || renderedOffset < first || renderedOffset > snapshot.offset) {
            evaluateJavascript("window.hermesTerminal.reset()", null)
            renderedSession = snapshot.selected
            renderedOffset = first
        }
        snapshot.chunks.filter { it.next > renderedOffset }.forEach {
            evaluateJavascript("window.hermesTerminal.write(${JsonPrimitive(it.data)})", null)
            renderedOffset = it.next
        }
        if (appearance.isNotEmpty() && appearance != renderedAppearance) {
            evaluateJavascript("window.hermesTerminal.configure($appearance)", null)
            renderedAppearance = appearance
        }
    }

    private var touching = false
    private var touchDown = 0L
    private var lastTapUp = 0L

    /** Page-to-host calls, each honoured only while a real touch backs it. */
    private inner class Host {
        @JavascriptInterface
        fun openLink(url: String) {
            post {
                if (SystemClock.uptimeMillis() - lastTapUp < 1500 && terminalLinkAllowed(url)) onLink(url)
            }
        }

        /** The page's own long-press detector, at a buffer cell (the inset is not text). */
        @JavascriptInterface
        fun longPress(
            row: Int,
            col: Int,
        ) {
            post {
                if (!touching || SystemClock.uptimeMillis() - touchDown < 300) return@post
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                snapshot(row, col, word = true) { onLongPress(it) }
            }
        }
    }

    init {
        addJavascriptInterface(Host(), "hermesHost")
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touching = true
                touchDown = SystemClock.uptimeMillis()
            }

            android.view.MotionEvent.ACTION_UP -> {
                touching = false
                lastTapUp = SystemClock.uptimeMillis()
            }

            android.view.MotionEvent.ACTION_CANCEL -> {
                touching = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Whole buffer as plain text for the native selection mode, with the offset of buffer
     * cell ([row], [col]) or of the visible top when [row] is negative, plus that visible top.
     */
    fun snapshot(
        row: Int,
        col: Int,
        word: Boolean,
        callback: (TerminalSelection) -> Unit,
    ) {
        if (!ready) return
        evaluateJavascript("JSON.stringify(window.hermesTerminal.snapshot($row,$col))") { result ->
            runCatching { Json.parseToJsonElement(result).jsonPrimitive.content }
                .getOrNull()
                ?.let { parseTerminalSnapshot(it, word) }
                ?.let(callback)
        }
    }

    /** Scrolls the view by one page (negative = older); never sends keys to the shell. */
    fun page(amount: Int) {
        if (ready) evaluateJavascript("window.hermesTerminal.page(${amount.coerceIn(-1, 1)})", null)
    }

    fun bottom() {
        if (ready) evaluateJavascript("window.hermesTerminal.bottom()", null)
    }

    fun text(callback: (String) -> Unit) {
        if (ready) {
            evaluateJavascript("window.hermesTerminal.text()") { result ->
                runCatching { Json.parseToJsonElement(result).jsonPrimitive.content }.getOrNull()?.let(callback)
            }
        }
    }
}

private fun Color.css(): String = String.format(Locale.ROOT, "#%06x", toArgb() and 0xffffff)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TerminalDisplay(
    state: TerminalUiState,
    fontSize: Int,
    modifier: Modifier,
    onSize: (Int, Int) -> Unit,
    onView: (TerminalDisplay?) -> Unit,
    onFailure: () -> Unit,
    onReport: (String) -> Unit = {},
    onLink: (String) -> Unit = {},
    onLongPress: (TerminalSelection) -> Unit = {},
    onScroll: (TerminalScroll) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val appearance = "$fontSize,${JsonPrimitive(
        colors.surface.css(),
    )},${JsonPrimitive(colors.onSurface.css())},${JsonPrimitive(colors.primary.css())}"
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { context ->
            TerminalDisplay(context).apply {
                // Match the shared rich-preview host: WRAP_CONTENT collapses WebView viewport units.
                layoutParams =
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setBackgroundColor(colors.surface.toArgb())
                isFocusable = false
                isFocusableInTouchMode = false
                // terminal.js owns scroll and long-press; consuming Chromium's long-press keeps
                // its text selection from racing a slow drag.
                setOnLongClickListener { true }
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    domStorageEnabled = false
                    databaseEnabled = false
                    saveFormData = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(true)
                    setGeolocationEnabled(false)
                }
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                    }
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = true

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse {
                            val uri = request.url
                            val name = uri.path?.removePrefix("/")
                            val allowed =
                                setOf(
                                    "index.html",
                                    "xterm.js",
                                    "xterm.css",
                                    "addon-fit.js",
                                    "terminal.js",
                                    "terminal.css",
                                )
                            if (uri.scheme == "https" && uri.host == "terminal.hermes.invalid" && uri.port == -1 &&
                                uri.query == null && name in allowed
                            ) {
                                val type =
                                    when {
                                        name!!.endsWith(".html") -> "text/html"
                                        name.endsWith(".css") -> "text/css"
                                        else -> "application/javascript"
                                    }
                                return WebResourceResponse(type, "UTF-8", context.assets.open("terminal/$name"))
                            }
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            if (url == "https://terminal.hermes.invalid/index.html") {
                                ready = true
                                render()
                                startStatusPoll()
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: android.webkit.RenderProcessGoneDetail,
                        ): Boolean {
                            ready = false
                            this@apply.onFailure()
                            return true
                        }
                    }
                setDownloadListener { _, _, _, _, _ -> }
                loadUrl("https://terminal.hermes.invalid/index.html")
                onView(this)
            }
        },
        update = { view ->
            view.snapshot = state
            view.appearance = appearance
            view.onSize = onSize
            view.onFailure = onFailure
            view.onReport = onReport
            view.onLink = onLink
            view.onLongPress = onLongPress
            view.onScroll = onScroll
            view.render()
        },
        onRelease = { view ->
            onView(null)
            view.ready = false
            view.stopStatusPoll()
            view.stopLoading()
            view.destroy()
        },
    )
}
