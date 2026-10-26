package com.m57.hermescontrol.career

import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * ServiceWorkerController is process-wide, outliving every browser and Activity.
 * Its deny-all client must never hold a View, Context, session, or screen callback.
 * Keep the existing guard installed when individual tabs are disposed.
 */
internal object CareerBrowserServiceWorkerGuard {
    internal val client: ServiceWorkerClient = BlockingClient()
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.apply {
                allowContentAccess = false
                allowFileAccess = false
                blockNetworkLoads = true
            }
            setServiceWorkerClient(client)
        }
        installed = true
    }

    // A nested class has no outer WebView/Activity reference, unlike the former anonymous client.
    private class BlockingClient : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse =
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Blocked",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(byteArrayOf()),
            )
    }
}
