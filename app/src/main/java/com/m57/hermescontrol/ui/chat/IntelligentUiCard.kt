package com.m57.hermescontrol.ui.chat

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalChatFontScale
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun IntelligentUiCard(
    source: String,
    language: String?,
) {
    var preview by remember(source) { mutableStateOf(IntelligentUiDocument.isInteractiveLanguage(language)) }
    var expanded by remember(source) { mutableStateOf(false) }
    var generation by remember(source) { mutableStateOf(0) }
    var menu by remember(source) { mutableStateOf(false) }
    var measuredHeight by remember(source, generation) { mutableStateOf(IntelligentUiSizing.INITIAL_HEIGHT) }
    val spacing = LocalSpacing.current
    val heightLimit = if (expanded) IntelligentUiSizing.EXPANDED_LIMIT else IntelligentUiSizing.INLINE_LIMIT
    val expansionLabel = if (expanded) R.string.rich_answer_collapse else R.string.rich_answer_expand
    Column(Modifier.fillMaxWidth().testTag("intelligent_ui_card")) {
        if (preview && measuredHeight > IntelligentUiSizing.INLINE_LIMIT &&
            source.length <= IntelligentUiDocument.MAX_SOURCE_LENGTH
        ) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End).testTag("intelligent_ui_expand"),
            ) { Text(stringResource(expansionLabel)) }
        }
        if (!preview) {
            TextButton(onClick = { preview = true }) { Text(stringResource(R.string.rich_answer_preview)) }
        }
        when {
            !preview -> {
                com.m57.hermescontrol.ui.chat.components
                    .CodeBlockCard(source, language, onCopy = {})
            }

            source.length > IntelligentUiDocument.MAX_SOURCE_LENGTH -> {
                Text(stringResource(R.string.rich_answer_too_large), style = MaterialTheme.typography.bodyMedium)
            }

            else -> {
                key(generation) {
                    IntelligentUiWebView(
                        source,
                        Modifier.fillMaxWidth().height(
                            measuredHeight.coerceIn(IntelligentUiSizing.MIN_HEIGHT, heightLimit).dp,
                        ),
                        onHeight = { measuredHeight = it },
                    )
                }
            }
        }
        if (preview) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (source.length <= IntelligentUiDocument.MAX_SOURCE_LENGTH) {
                    Text(
                        stringResource(R.string.rich_answer_local),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = spacing.xs).weight(1f),
                    )
                }
                if (source.length > IntelligentUiDocument.MAX_SOURCE_LENGTH) Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Default.MoreHoriz,
                            contentDescription = stringResource(R.string.rich_answer_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (preview) R.string.rich_answer_source else R.string.rich_answer_preview,
                                    ),
                                )
                            },
                            onClick = {
                                preview = !preview
                                menu = false
                            },
                        )
                        if (source.length <= IntelligentUiDocument.MAX_SOURCE_LENGTH) {
                            if (measuredHeight > IntelligentUiSizing.INLINE_LIMIT) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                expansionLabel,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        expanded = !expanded
                                        menu = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rich_answer_reset)) },
                                onClick = {
                                    generation++
                                    menu = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Color.cssHex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)

@SuppressLint("SetJavaScriptEnabled") // Generated content runs only in an opaque sandbox without a native bridge.
@Composable
internal fun IntelligentUiWebView(
    source: String,
    modifier: Modifier = Modifier,
    onHeight: (Int) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val status = LocalHermesStatusColors.current
    val spacing = LocalSpacing.current
    val density = LocalDensity.current
    val shape = MaterialTheme.shapes.large
    val corner = shape.topStart.toPx(Size(100f * density.density, 100f * density.density), density) / density.density
    val background = colors.surfaceContainerLow
    val textZoom = (LocalDensity.current.fontScale * LocalChatFontScale.current * 100).roundToInt()
    val theme =
        IntelligentUiTheme(
            background.cssHex(),
            colors.onSurface.cssHex(),
            colors.primary.cssHex(),
            colors.onPrimary.cssHex(),
            colors.onSurfaceVariant.cssHex(),
            colors.surfaceContainerHigh.cssHex(),
            colors.secondary.cssHex(),
            colors.tertiary.cssHex(),
            status.success.cssHex(),
            status.info.cssHex(),
            status.warning.cssHex(),
            status.error.cssHex(),
            corner = corner.coerceIn(4f, 64f),
            spacing = spacing.lg.value,
            bodySize = MaterialTheme.typography.bodyMedium.fontSize.value,
            titleSize = MaterialTheme.typography.titleMedium.fontSize.value,
        )
    val document =
        remember(source, theme) {
            IntelligentUiDocument.build(
                source,
                theme,
                background.luminance() < 0.5f,
            )
        }
    var failed by remember(document) { mutableStateOf(false) }
    if (failed) {
        Text(stringResource(R.string.rich_answer_unavailable), modifier = modifier)
        return
    }
    AndroidView(
        modifier = modifier.clip(MaterialTheme.shapes.large).testTag("intelligent_ui_preview"),
        factory = { context ->
            IntelligentPreviewWebView(context).apply {
                // WRAP_CONTENT makes WebView collapse iframe viewport units inside AndroidView.
                layoutParams =
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(background.toArgb())
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    blockNetworkLoads = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(true)
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mediaPlaybackRequiresUserGesture = true
                    setGeolocationEnabled(false)
                    saveFormData = false
                    setSupportZoom(false)
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
                        ): Boolean = request.url.toString() !in setOf("about:blank", "about:srcdoc")

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? =
                            if (request.url.toString() == "about:blank" || request.url.toString() == "about:srcdoc") {
                                null
                            } else {
                                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                            }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: android.webkit.RenderProcessGoneDetail,
                        ): Boolean {
                            failed = true
                            return true
                        }
                    }
                setDownloadListener { _, _, _, _, _ -> }
            }
        },
        update = { view ->
            view.onHeight = onHeight
            view.settings.textZoom = textZoom
            if (view.tag != document) {
                view.tag = document
                view.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
            }
        },
        onRelease = { view ->
            view.stopMeasuring()
            view.stopLoading()
            view.destroy()
        },
    )
}
