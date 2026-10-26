package com.m57.hermescontrol.career

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import java.util.Locale

internal data class CareerBrowserFileTypes(
    val intentType: String,
    val mimeTypes: List<String>,
)

/** Accept values are picker filters, never permission to read a URI returned by another app. */
internal fun careerBrowserFileTypes(
    acceptTypes: List<String>,
    extensionType: (String) -> String?,
): CareerBrowserFileTypes {
    val mimePattern = Regex("[a-z0-9][a-z0-9!#$&^_.+\\-]*/(?:\\*|[a-z0-9][a-z0-9!#$&^_.+\\-]*)")
    val types = linkedSetOf<String>()
    for (entry in acceptTypes) {
        for (part in entry.split(',')) {
            val token = part.trim().lowercase(Locale.ROOT)
            if (token.isEmpty()) continue
            val type =
                if (token.startsWith('.')) {
                    // Unknown extensions still need to be selectable through the system file browser.
                    extensionType(token.removePrefix("."))?.lowercase(Locale.ROOT) ?: "*/*"
                } else {
                    token
                }
            if (type == "*/*") return CareerBrowserFileTypes("*/*", emptyList())
            if (mimePattern.matches(type)) types += type
        }
    }
    if (types.isEmpty()) return CareerBrowserFileTypes("*/*", emptyList())
    val groups = types.map { it.substringBefore('/') }.distinct()
    val intentType =
        when {
            types.size == 1 -> types.first()
            groups.size == 1 -> "${groups.single()}/*"
            else -> "*/*"
        }
    return CareerBrowserFileTypes(intentType, types.toList())
}

/** A cancelled launch keeps its slot until the OS result is drained. No result can change owners. */
internal class CareerBrowserPickerSlot<T : Any>(
    initiallyAwaitingResult: Boolean = false,
) {
    var isAwaitingResult: Boolean = initiallyAwaitingResult
        private set
    var pending: T? = null
        private set
    private var closed = false

    fun begin(request: T): Boolean {
        if (closed || isAwaitingResult) return false
        pending = request
        isAwaitingResult = true
        return true
    }

    fun cancel(): T? = pending.also { pending = null }

    fun finish(): T? {
        isAwaitingResult = false
        return cancel()
    }

    fun close(): T? {
        closed = true
        return cancel()
    }
}

/**
 * Upload-only bridge to Android's document picker. It neither opens bytes nor persists URI grants.
 *
 * Keep one instance per ActivityResult launcher and save its pending-result flag across recreation.
 * Cancel on document/tab/session replacement; close before destroying the WebView. Do not cancel
 * merely because the host pauses or stops: the system picker itself causes those lifecycle events.
 */
@MainThread
internal class CareerBrowserFileChooser(
    context: Context,
    private val launch: (Intent) -> Unit,
    private val isOwnerCurrent: (WebView, Long) -> Boolean,
    initiallyAwaitingResult: Boolean = false,
    private val onAwaitingResultChanged: (Boolean) -> Unit = {},
) {
    private val appContext = context.applicationContext ?: context
    private val slot = CareerBrowserPickerSlot<Request>(initiallyAwaitingResult)

    private class Request(
        val owner: WebView,
        val documentSequence: Long,
        val callback: ValueCallback<Array<Uri>>,
        val multiple: Boolean,
    )

    val isAwaitingResult: Boolean
        get() = slot.isAwaitingResult

    /** Always handles the callback, including unsupported modes and failures to open a picker. */
    fun show(
        owner: WebView,
        documentSequence: Long,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        if (slot.isAwaitingResult) {
            // Never replace a callback beneath a still-running ActivityResult launch.
            cancel()
            deliver(callback, null)
            return true
        }
        val request: Request
        val intent: Intent
        try {
            val mode = params.mode
            if (
                (
                    mode != WebChromeClient.FileChooserParams.MODE_OPEN &&
                        mode != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                ) ||
                !isOwnerCurrent(owner, documentSequence)
            ) {
                deliver(callback, null)
                return true
            }
            request =
                Request(owner, documentSequence, callback, mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            val types =
                careerBrowserFileTypes(params.acceptTypes?.toList().orEmpty()) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
                }
            intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = types.intentType
                    if (types.mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.mimeTypes.toTypedArray())
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.multiple)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
        } catch (_: RuntimeException) {
            deliver(callback, null)
            return true
        }
        if (!slot.begin(request)) {
            deliver(callback, null)
            return true
        }
        onAwaitingResultChanged(true)
        try {
            // Ignore capture hints: uploads always require an explicit system document selection.
            launch(intent)
        } catch (_: RuntimeException) {
            val failed = slot.finish()
            onAwaitingResultChanged(false)
            failed?.let { deliver(it.callback, null) }
        }
        return true
    }

    fun onResult(
        resultCode: Int,
        data: Intent?,
    ) {
        val request = slot.finish()
        onAwaitingResultChanged(false)
        if (request == null) return
        val selected =
            try {
                if (resultCode == Activity.RESULT_OK && isOwnerCurrent(request.owner, request.documentSequence)) {
                    readSelection(data, request.multiple)
                } else {
                    null
                }
            } catch (_: RuntimeException) {
                null
            }
        deliver(request.callback, selected)
    }

    fun cancel() {
        slot.cancel()?.let { deliver(it.callback, null) }
    }

    fun cancel(owner: WebView) {
        if (slot.pending?.owner === owner) cancel()
    }

    fun close() {
        slot.close()?.let { deliver(it.callback, null) }
    }

    private fun readSelection(
        data: Intent?,
        multiple: Boolean,
    ): Array<Uri>? {
        if (data == null || data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return null
        val selected = linkedSetOf<Uri>()
        data.data?.let { selected += it }
        val clip = data.clipData
        if (clip != null) {
            // Bound work on untrusted activity results, without opening or examining any file.
            if (clip.itemCount > MAX_SELECTION_COUNT) return null
            for (index in 0 until clip.itemCount) {
                selected += clip.getItemAt(index).uri ?: return null
            }
        }
        if (selected.isEmpty() || selected.size > MAX_SELECTION_COUNT || (!multiple && selected.size != 1)) return null
        if (selected.any { !hasExternalReadGrant(it) }) return null
        return selected.toTypedArray()
    }

    private fun hasExternalReadGrant(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority?.takeIf { it.isNotBlank() } ?: return false
        // User-qualified or malformed authorities must not hide an app-owned provider.
        if ('@' in authority) return false
        @Suppress("DEPRECATION")
        val provider = appContext.packageManager.resolveContentProvider(authority, 0) ?: return false
        val providerApp = provider.applicationInfo ?: return false
        if (provider.packageName == appContext.packageName || providerApp.uid == Process.myUid()) return false
        return appContext.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun deliver(
        callback: ValueCallback<Array<Uri>>,
        value: Array<Uri>?,
    ) {
        try {
            callback.onReceiveValue(value)
        } catch (_: RuntimeException) {
            // The framework callback can already belong to a destroyed renderer. Never retry it.
        }
    }

    private companion object {
        const val MAX_SELECTION_COUNT = 100
    }
}
