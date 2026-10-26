package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import kotlin.math.min

private val MAX_SVG_HEIGHT = 480.dp

/** Validated diagram: full width, viewBox aspect kept, at most ~480dp tall; tap opens a zoomable view. */
@Composable
internal fun NativeSvg(spec: SvgSpec) {
    val drawing = spec.drawing
    val colors = svgThemeColors()
    val measurer = rememberTextMeasurer()
    val paths = remember(drawing) { SvgPathCache() }
    var zoomed by rememberSaveable { mutableStateOf(false) }
    NativeHeader(spec.title)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val ratio = drawing.width / drawing.height
        val width = minOf(maxWidth, MAX_SVG_HEIGHT * ratio)
        Canvas(
            Modifier
                .size(width, width / ratio)
                .semantics {
                    contentDescription = spec.alt
                    role = Role.Image
                }.clickable(onClickLabel = "拡大表示") { zoomed = true }
                .testTag("${spec.id}-svg"),
        ) {
            drawSvg(drawing, colors, measurer, paths)
        }
    }
    if (zoomed) {
        NativeSvgViewer(spec, colors, measurer, paths) { zoomed = false }
    }
}

/** Full-screen pinch-zoom and pan, like the chat image viewer; redrawn as vectors at every scale. */
@Composable
private fun NativeSvgViewer(
    spec: SvgSpec,
    colors: Map<String, Color>,
    measurer: TextMeasurer,
    paths: SvgPathCache,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize()) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }.semantics { contentDescription = spec.alt }
                        .testTag("${spec.id}-svg-viewer"),
                ) {
                    withTransform({
                        translate(offset.x, offset.y)
                        scale(scale, scale, pivot = center)
                    }) { drawSvg(spec.drawing, colors, measurer, paths) }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "閉じる")
                }
            }
        }
    }
}

/** Theme words resolve here, so one diagram fits dark and light without new text. */
@Composable
private fun svgThemeColors(): Map<String, Color> {
    val scheme = MaterialTheme.colorScheme
    val status = LocalHermesStatusColors.current
    return remember(scheme, status) {
        mapOf(
            "fg" to scheme.onSurface,
            "muted" to scheme.onSurfaceVariant,
            "surface" to scheme.surface,
            "accent" to scheme.primary,
            "good" to status.success,
            "bad" to status.error,
            "warn" to status.warning,
            "line" to scheme.outline,
        )
    }
}

internal fun resolveSvgPaint(
    paint: SvgPaint,
    colors: Map<String, Color>,
): Color? =
    when (paint) {
        SvgPaint.None -> {
            null
        }

        is SvgPaint.Theme -> {
            colors.getValue(paint.word)
        }

        is SvgPaint.Hex -> {
            val hex = paint.value.drop(1)
            val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
            val v = full.toLong(16)
            if (full.length == 8) {
                Color(
                    red = (v shr 24 and 0xFF).toInt(),
                    green = (v shr 16 and 0xFF).toInt(),
                    blue = (v shr 8 and 0xFF).toInt(),
                    alpha =
                        (
                            v and
                                0xFF
                        ).toInt(),
                )
            } else {
                Color(
                    red = (v shr 16 and 0xFF).toInt(),
                    green = (v shr 8 and 0xFF).toInt(),
                    blue = (v and 0xFF).toInt(),
                )
            }
        }
    }

/** Paths are built once per shape and reused across frames and the zoom view. */
internal class SvgPathCache {
    private val cache = HashMap<SvgShape, Path?>()

    fun get(shape: SvgShape): Path? =
        cache.getOrPut(shape) {
            when (shape) {
                is SvgShape.Rect -> {
                    if (shape.width <= 0f || shape.height <= 0f) return@getOrPut null
                    val rect = Rect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
                    val rx = (if (shape.rx > 0f) shape.rx else shape.ry).coerceAtMost(shape.width / 2)
                    val ry = (if (shape.ry > 0f) shape.ry else shape.rx).coerceAtMost(shape.height / 2)
                    Path().apply {
                        if (rx > 0f || ry > 0f) addRoundRect(RoundRect(rect, CornerRadius(rx, ry))) else addRect(rect)
                    }
                }

                is SvgShape.Ellipse -> {
                    if (shape.rx <= 0f || shape.ry <= 0f) return@getOrPut null
                    Path().apply {
                        addOval(
                            Rect(
                                shape.cx - shape.rx,
                                shape.cy - shape.ry,
                                shape.cx + shape.rx,
                                shape.cy + shape.ry,
                            ),
                        )
                    }
                }

                is SvgShape.Line -> {
                    Path().apply {
                        moveTo(shape.x1, shape.y1)
                        lineTo(shape.x2, shape.y2)
                    }
                }

                is SvgShape.Poly -> {
                    Path().apply {
                        moveTo(shape.points[0], shape.points[1])
                        for (i in 2 until shape.points.size step 2) lineTo(shape.points[i], shape.points[i + 1])
                        if (shape.closed) close()
                    }
                }

                is SvgShape.Path -> {
                    runCatching { PathParser().parsePathString(shape.d).toPath() }.getOrNull()
                }
            }
        }
}

internal fun DrawScope.drawSvg(
    drawing: SvgDrawing,
    colors: Map<String, Color>,
    measurer: TextMeasurer,
    paths: SvgPathCache,
) {
    val s = min(size.width / drawing.width, size.height / drawing.height)
    val dx = (size.width - drawing.width * s) / 2
    val dy = (size.height - drawing.height * s) / 2
    withTransform({
        translate(dx, dy)
        scale(s, s, pivot = Offset.Zero)
        translate(-drawing.minX, -drawing.minY)
    }) {
        clipRect(drawing.minX, drawing.minY, drawing.minX + drawing.width, drawing.minY + drawing.height) {
            drawSvgNode(drawing.root, SvgStyle(), 1f, colors, measurer, paths)
        }
    }
}

private fun DrawScope.drawSvgNode(
    node: SvgNode,
    parent: SvgStyle,
    parentAlpha: Float,
    colors: Map<String, Color>,
    measurer: TextMeasurer,
    paths: SvgPathCache,
) {
    val style = node.style.inherit(parent)
    val alpha = parentAlpha * (style.opacity ?: 1f)
    if (alpha <= 0f) return
    withTransform({
        node.transform.forEach {
            when (it) {
                is SvgTransform.Translate -> translate(it.x, it.y)
                is SvgTransform.Scale -> scale(it.x, it.y, pivot = Offset.Zero)
                is SvgTransform.Rotate -> rotate(it.degrees, pivot = Offset(it.cx, it.cy))
            }
        }
    }) {
        when (node) {
            is SvgNode.Group -> {
                node.children.forEach { drawSvgNode(it, style, alpha, colors, measurer, paths) }
            }

            is SvgNode.Shape -> {
                val path = paths.get(node.shape) ?: return@withTransform
                // SVG's default fill is black; here it is the theme foreground so dark mode stays legible.
                if (node.shape !is SvgShape.Line) {
                    resolveSvgPaint(style.fill ?: SvgPaint.Theme("fg"), colors)?.let { color ->
                        drawPath(path, color, alpha = (alpha * (style.fillOpacity ?: 1f)).coerceIn(0f, 1f))
                    }
                }
                val stroke = style.stroke?.let { resolveSvgPaint(it, colors) }
                val width = style.strokeWidth ?: 1f
                if (stroke != null && width > 0f) {
                    drawPath(
                        path,
                        stroke,
                        alpha = (alpha * (style.strokeOpacity ?: 1f)).coerceIn(0f, 1f),
                        style = svgStroke(style, width),
                    )
                }
            }

            is SvgNode.Text -> {
                drawSvgText(node, style, alpha, colors, measurer)
            }
        }
    }
}

private fun svgStroke(
    style: SvgStyle,
    width: Float,
): Stroke {
    val dash = style.dash?.takeIf { it.isNotEmpty() }?.let { if (it.size % 2 == 1) it + it else it }
    return Stroke(
        width = width,
        cap =
            when (style.cap) {
                "round" -> StrokeCap.Round
                "square" -> StrokeCap.Square
                else -> StrokeCap.Butt
            },
        join =
            when (style.join) {
                "round" -> StrokeJoin.Round
                "bevel" -> StrokeJoin.Bevel
                else -> StrokeJoin.Miter
            },
        pathEffect = dash?.let { PathEffect.dashPathEffect(it.toFloatArray(), 0f) },
    )
}

/**
 * Text is laid out in viewBox units (font-size is a viewBox length, independent of the system
 * font scale, so labels stay inside their boxes). A run with its own x starts a new anchored chunk.
 */
private fun DrawScope.drawSvgText(
    node: SvgNode.Text,
    style: SvgStyle,
    alpha: Float,
    colors: Map<String, Color>,
    measurer: TextMeasurer,
) {
    var x = node.x
    var y = node.y
    node.runs.forEachIndexed { index, run ->
        val runStyle = run.style.inherit(style)
        val newChunk = index == 0 || run.x != null
        run.x?.let { x = it }
        run.y?.let { y = it }
        val color = resolveSvgPaint(runStyle.fill ?: SvgPaint.Theme("fg"), colors) ?: return@forEachIndexed
        val fontPx = runStyle.fontSize ?: 16f
        val layout =
            measurer.measure(
                run.text,
                TextStyle(
                    color = color,
                    fontSize = (fontPx / (density * fontScale)).sp,
                    fontWeight = if (runStyle.bold == true) FontWeight.Bold else FontWeight.Normal,
                ),
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Visible,
            )
        val width = layout.size.width.toFloat()
        if (newChunk) {
            x -=
                when (runStyle.anchor) {
                    "middle" -> width / 2
                    "end" -> width
                    else -> 0f
                }
        }
        val top =
            when (runStyle.baseline) {
                "middle", "central" -> y - layout.size.height / 2f
                "hanging", "text-before-edge" -> y
                else -> y - layout.firstBaseline
            }
        drawText(
            layout,
            topLeft = Offset(x, top),
            alpha = (alpha * (runStyle.fillOpacity ?: 1f)).coerceIn(0f, 1f),
        )
        x += width
    }
}
