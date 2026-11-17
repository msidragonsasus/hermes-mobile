package com.m57.hermescontrol.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.remote.GatewayFileClient

internal fun normalizeMarkdownLink(raw: String): String = raw.trim().removeSurrounding("<", ">")

internal fun markdownLinkEnd(
    source: String,
    start: Int,
): Int {
    if (source.getOrNull(start) != '<') return source.indexOf(')', start)
    val end = source.indexOf('>', start + 1)
    if (end < 0) return -1
    var close = end + 1
    while (source.getOrNull(close)?.isWhitespace() == true) close++
    return if (source.getOrNull(close) == ')') close else -1
}

/** Host file paths belong to the authenticated gateway, not the phone's browser or file system. */
internal fun markdownFileAttachment(raw: String): Attachment? {
    val path = GatewayFileClient.normalizePath(normalizeMarkdownLink(raw)) ?: return null
    return Attachment(
        uri = "gateway:$path",
        name = mediaNameFromPath(path),
        mimeType = mediaMimeForPath(path),
        source = AttachmentSource.GATEWAY,
    )
}

@Composable
internal fun MarkdownLinkScope(
    onOpenAttachment: ((Attachment) -> Unit)?,
    content: @Composable () -> Unit,
) {
    val external = LocalUriHandler.current
    val handler =
        remember(external, onOpenAttachment) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    val attachment = markdownFileAttachment(uri)
                    if (attachment != null && onOpenAttachment != null) {
                        onOpenAttachment(attachment)
                    } else {
                        external.openUri(normalizeMarkdownLink(uri))
                    }
                }
            }
        }
    CompositionLocalProvider(LocalUriHandler provides handler, content = content)
}
