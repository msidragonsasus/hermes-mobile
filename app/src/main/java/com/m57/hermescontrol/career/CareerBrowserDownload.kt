package com.m57.hermescontrol.career

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil

/** What the in-app browser hands over when a page asks to save a file. */
internal data class CareerBrowserDownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val referer: String?,
)

internal enum class CareerBrowserDownloadDecision {
    /** A plain HTTPS file that DownloadManager can fetch again with the page's cookies. */
    SAVE,

    /** blob:/data: files exist only inside the page, so they cannot be re-fetched. */
    PAGE_GENERATED,

    /** http, IP literals, local hosts and other URLs the browser policy never loads. */
    NOT_ALLOWED,
}

internal fun careerBrowserDownloadDecision(
    policy: CareerBrowserPolicy,
    url: String?,
): CareerBrowserDownloadDecision =
    when {
        url == null -> CareerBrowserDownloadDecision.NOT_ALLOWED
        url.startsWith("blob:") || url.startsWith("data:") -> CareerBrowserDownloadDecision.PAGE_GENERATED
        policy.allowsNavigation(url) -> CareerBrowserDownloadDecision.SAVE
        else -> CareerBrowserDownloadDecision.NOT_ALLOWED
    }

/** Saves into the public Downloads folder and returns the status line for the browser bar. */
internal fun careerBrowserStartDownload(
    context: Context,
    policy: CareerBrowserPolicy,
    request: CareerBrowserDownloadRequest,
): String {
    when (careerBrowserDownloadDecision(policy, request.url)) {
        CareerBrowserDownloadDecision.PAGE_GENERATED ->
            return "このファイルはページ内で作られているため保存できません。サイトの別の保存ボタンを試してください。"

        CareerBrowserDownloadDecision.NOT_ALLOWED ->
            return "このファイルはHTTPSではないため保存できません。"

        CareerBrowserDownloadDecision.SAVE -> Unit
    }
    val name = URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
    return runCatching {
        val manager = checkNotNull(context.getSystemService(DownloadManager::class.java))
        val download =
            DownloadManager.Request(Uri.parse(request.url)).apply {
                CookieManager.getInstance().getCookie(request.url)?.let { addRequestHeader("Cookie", it) }
                request.userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
                request.referer
                    ?.takeIf { policy.allowsNavigation(it) }
                    ?.let { addRequestHeader("Referer", it) }
                request.mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
        manager.enqueue(download)
        "「$name」をダウンロードしています。完了は通知と「ダウンロード」フォルダで確認できます。"
    }.getOrElse {
        "「$name」を保存できませんでした。もう一度試してください。"
    }
}
