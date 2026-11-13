package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.PromptDisclosurePolicy
import com.m57.hermescontrol.ui.chat.SavedPromptExpansion
import com.m57.hermescontrol.ui.chat.promptContentFingerprint

private const val FADE_LINE_COUNT = 2

/** Collapsed-layout facts measured independently of the displayed state. */
private data class CollapsedPromptMeasurement(
    val overflows: Boolean,
    val fadeHeightPx: Float,
)

/**
 * Message body text for the user bubble. Long USER prompts fold to
 * [PromptDisclosurePolicy.COLLAPSED_LINE_LIMIT] laid-out lines with a fade and a
 * separate show more / show less control; every other role renders unchanged.
 * Only presentation is clipped — [text] itself always stays complete.
 */
@Composable
internal fun CollapsiblePromptBody(
    text: AnnotatedString,
    messageId: String,
    content: String,
    role: MessageRole,
    style: TextStyle,
    color: Color,
    surfaceColor: Color,
    currentSearchMatch: Boolean,
    modifier: Modifier = Modifier,
) {
    if (role != MessageRole.USER) {
        SelectionContainer(modifier = modifier) {
            Text(text = text, color = color, style = style)
        }
        return
    }

    val contentFingerprint = remember(content) { promptContentFingerprint(content) }
    var savedExpansion by rememberSaveable(messageId, contentFingerprint, stateSaver = SavedPromptExpansion.Saver) {
        mutableStateOf(SavedPromptExpansion(messageId, contentFingerprint, false))
    }
    val manuallyExpanded = savedExpansion.expandedFor(messageId, contentFingerprint)
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    BoxWithConstraints(modifier = modifier) {
        val maxWidth = constraints.maxWidth
        // Measured against the collapsed layout at the displayed width so the
        // control never depends on the state it toggles. Highlights only change
        // colors, so the plain content is measured.
        val measurement =
            remember(content, style, maxWidth, density, layoutDirection, textMeasurer) {
                measureCollapsedPrompt(textMeasurer, content, style, maxWidth, density, layoutDirection)
            }
        val collapsed =
            PromptDisclosurePolicy.shouldCollapse(
                role = role,
                overflows = measurement.overflows,
                manuallyExpanded = manuallyExpanded,
                currentSearchMatch = currentSearchMatch,
            )
        val showControl =
            PromptDisclosurePolicy.showControl(
                role = role,
                overflows = measurement.overflows,
                currentSearchMatch = currentSearchMatch,
            )
        val stateText =
            stringResource(
                if (collapsed) {
                    R.string.prompt_disclosure_state_collapsed
                } else {
                    R.string.prompt_disclosure_state_expanded
                },
            )

        Column {
            Box {
                SelectionContainer {
                    Text(
                        text = text,
                        color = color,
                        style = style,
                        maxLines = if (collapsed) PromptDisclosurePolicy.COLLAPSED_LINE_LIMIT else Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                        modifier =
                            Modifier
                                .testTag("prompt_body")
                                .then(
                                    if (measurement.overflows) {
                                        Modifier.semantics { stateDescription = stateText }
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
                if (collapsed) {
                    val fadeHeightPx = measurement.fadeHeightPx
                    Spacer(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .testTag("prompt_disclosure_fade")
                                .drawBehind {
                                    val top = (size.height - fadeHeightPx).coerceAtLeast(0f)
                                    drawRect(
                                        brush =
                                            Brush.verticalGradient(
                                                colors = listOf(surfaceColor.copy(alpha = 0f), surfaceColor),
                                                startY = top,
                                                endY = size.height,
                                            ),
                                        topLeft = Offset(0f, top),
                                        size = Size(size.width, size.height - top),
                                    )
                                },
                    )
                }
            }
            if (showControl) {
                TextButton(
                    onClick = {
                        savedExpansion = SavedPromptExpansion(messageId, contentFingerprint, !manuallyExpanded)
                    },
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .testTag("prompt_disclosure_toggle")
                            .semantics { stateDescription = stateText },
                    colors = ButtonDefaults.textButtonColors(contentColor = color),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        text =
                            stringResource(
                                if (collapsed) {
                                    R.string.prompt_disclosure_show_more
                                } else {
                                    R.string.prompt_disclosure_show_less
                                },
                            ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Lays out at most the collapsed line limit, so cost stays bounded for huge prompts. */
private fun measureCollapsedPrompt(
    textMeasurer: TextMeasurer,
    content: String,
    style: TextStyle,
    maxWidth: Int,
    density: Density,
    layoutDirection: LayoutDirection,
): CollapsedPromptMeasurement {
    val limit = PromptDisclosurePolicy.COLLAPSED_LINE_LIMIT
    val result =
        textMeasurer.measure(
            text = content,
            style = style,
            overflow = TextOverflow.Clip,
            softWrap = true,
            maxLines = limit,
            constraints = if (maxWidth == Constraints.Infinity) Constraints() else Constraints(maxWidth = maxWidth),
            layoutDirection = layoutDirection,
            density = density,
            skipCache = true,
        )
    val overflows = result.multiParagraph.didExceedMaxLines
    val fadeHeightPx =
        if (overflows && result.lineCount >= FADE_LINE_COUNT) {
            result.getLineBottom(result.lineCount - 1) - result.getLineTop(result.lineCount - FADE_LINE_COUNT)
        } else {
            0f
        }
    return CollapsedPromptMeasurement(overflows = overflows, fadeHeightPx = fadeHeightPx)
}
