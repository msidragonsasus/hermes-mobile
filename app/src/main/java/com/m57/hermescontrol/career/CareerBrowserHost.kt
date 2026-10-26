package com.m57.hermescontrol.career

import android.content.Context
import android.os.Message
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout

/** One ephemeral browsing session. Only the root owns the cookie/storage cleanup lease. */
internal class CareerBrowserHost(
    context: Context,
    private val onSelectionChanged: (CareerBrowserWebView?) -> Unit = {},
) : FrameLayout(context) {
    private val pages = mutableListOf<CareerBrowserWebView>()
    val tabs: List<CareerBrowserWebView>
        get() = pages.toList()
    var browser: CareerBrowserWebView? = null
        private set
    var activeBrowser: CareerBrowserWebView? = null
        private set
    private var foreground = true

    fun attachBrowser(value: CareerBrowserWebView) {
        check(browser == null)
        browser = value
        pages.add(value)
        addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        select(value)
    }

    fun openPopup(
        opener: CareerBrowserWebView,
        result: Message,
        create: () -> CareerBrowserWebView,
    ): Boolean {
        val transport = result.obj as? WebView.WebViewTransport ?: return false
        if (!foreground || opener !== activeBrowser || opener.isDisposed || pages.size >= MAX_TABS) return false
        val child =
            try {
                create()
            } catch (_: Exception) {
                return false
            }
        return try {
            child.joinSession()
            pages.add(child)
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            select(child)
            transport.webView = child
            result.sendToTarget()
            true
        } catch (_: Exception) {
            pages.remove(child)
            child.dispose()
            select(opener)
            false
        }
    }

    fun select(value: CareerBrowserWebView) {
        if (value !in pages || value.isDisposed) return
        activeBrowser?.takeUnless { it === value || it.isDisposed }?.onPause()
        pages.forEach { it.visibility = if (it === value) View.VISIBLE else View.GONE }
        activeBrowser = value
        if (foreground) value.onResume() else value.onPause()
        onSelectionChanged(value)
    }

    /** Root is the session anchor and cannot be closed by a site's window.close(). */
    fun closeTab(value: CareerBrowserWebView): Boolean {
        if (value === browser || value !in pages) return false
        val index = pages.indexOf(value)
        val selected = activeBrowser === value
        pages.remove(value)
        value.dispose()
        if (selected) {
            pages.getOrNull((index - 1).coerceAtLeast(0))?.let(::select)
        } else {
            onSelectionChanged(activeBrowser)
        }
        return true
    }

    fun setForeground(value: Boolean) {
        foreground = value
        pages.forEach { page ->
            if (!page.isDisposed) {
                if (value && page === activeBrowser) page.onResume() else page.onPause()
            }
        }
    }

    fun disposeBrowser() {
        val root = browser
        browser = null
        activeBrowser = null
        val old = pages.toList()
        pages.clear()
        // Child teardown never clears the jar. Root cleanup runs after all children are invalidated.
        old.filter { it !== root }.forEach { it.dispose() }
        root?.dispose()
    }

    companion object {
        const val MAX_TABS = 6
    }
}
