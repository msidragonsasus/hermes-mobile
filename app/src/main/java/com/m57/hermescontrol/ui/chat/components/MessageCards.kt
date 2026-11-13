package com.m57.hermescontrol.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.theme.CodeComment
import com.m57.hermescontrol.theme.CodeKeyword
import com.m57.hermescontrol.theme.CodeNumber
import com.m57.hermescontrol.theme.CodePunctuation
import com.m57.hermescontrol.theme.CodeString
import com.m57.hermescontrol.theme.CodeTerminalBg
import com.m57.hermescontrol.theme.CodeTerminalBorder
import com.m57.hermescontrol.theme.CodeTerminalMuted
import com.m57.hermescontrol.theme.CodeTerminalText
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import kotlinx.coroutines.delay

// ── ReasoningCard ─────────────────────────────────────────────────────────

/**
 * Collapsible card showing the assistant's reasoning trace
 * (reasoning-model thinking steps) before the final answer.
 *
 * Collapsed: vector lightbulb + "Reasoning · {N} steps" with chevron.
 * Expanded: reasoning text in monospace bodySmall, capped at ~40% of the
 *           screen height with internal scroll; "Show full" lifts the cap.
 * Long-press anywhere: copies the reasoning trace to the clipboard.
 * Streaming: pulsing indicator at the bottom while [isStreaming].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReasoningCard(
    reasoningText: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var fullHeight by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val stepCount = remember(reasoningText) { reasoningText.count { it == '\n' } + 1 }
    val capHeight = with(LocalConfiguration.current) { (screenHeightDp * 0.4f).dp }

    // Copy feedback: briefly show "Copied" then revert
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (!isStreaming) Modifier.animateContentSize() else Modifier)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                ?: return@combinedClickable
                        clipboard.setPrimaryClip(ClipData.newPlainText(null, reasoningText))
                        copied = true
                    },
                ).testTag("reasoning_card"),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.message_reasoning_steps, stepCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (copied) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.reasoning_copied),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = reasoningText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .then(
                                    if (!fullHeight) {
                                        // Capped at ~40% of the screen with internal scroll.
                                        Modifier.heightIn(max = capHeight).verticalScroll(scrollState)
                                    } else {
                                        // "Show full": render the whole trace at natural height.
                                        // A scrollable must NEVER be measured with an infinite
                                        // max height — inside a LazyColumn item (unbounded main
                                        // axis) that combination throws IllegalStateException.
                                        Modifier
                                    },
                                ),
                    )
                    if (isStreaming) {
                        ReasoningPulsingDot(modifier = Modifier.padding(top = 6.dp))
                    }
                    if (fullHeight) {
                        TextButton(onClick = { fullHeight = false }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.reasoning_show_less))
                        }
                    } else if (scrollState.maxValue > 0) {
                        TextButton(onClick = { fullHeight = true }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.reasoning_show_full))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Small pulsing dot shown at the bottom of [ReasoningCard] while streaming.
 */
@Composable
private fun ReasoningPulsingDot(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "reasoning_pulse")
    val pulseSpec: InfiniteRepeatableSpec<Float> =
        remember {
            infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            )
        }
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = pulseSpec,
        label = "reasoning_dot_alpha",
    )
    Box(
        modifier =
            modifier
                .size(8.dp)
                .clip(CircleShape)
                .graphicsLayer { this.alpha = alpha }
                .testTag("reasoning_pulsing_dot"),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {}
    }
}

// ── CodeBlockCard ─────────────────────────────────────────────────────────

/**
 * Terminal-style card for a fenced code block with syntax highlighting,
 * a language badge, and a copy button.
 */
@Composable
fun CodeBlockCard(
    code: String,
    language: String?,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("code_block"),
        shape = RoundedCornerShape(8.dp),
        color = CodeTerminalBg,
        border = BorderStroke(1.dp, CodeTerminalBorder),
    ) {
        Column {
            // Header row: language badge (left) + copy button (right)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!language.isNullOrBlank()) {
                    Text(
                        text = language.uppercase(),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = CodeTerminalMuted,
                            ),
                    )
                }
                Spacer(Modifier.weight(1f))
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (copied) {
                        kotlinx.coroutines.delay(2000)
                        copied = false
                    }
                }
                IconButton(
                    onClick = {
                        copyToClipboard(context, code)
                        copied = true
                        onCopy(code)
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy code",
                        tint = CodeTerminalMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            // Code content with syntax highlighting
            val highlighted = remember(code) { highlightSyntax(code) }
            Text(
                text = highlighted,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = CodeTerminalText,
                softWrap = false,
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

// Hoisted token patterns for syntax highlighting — compiled once.
private val HIGHLIGHT_TOKENS =
    listOf(
        TokenPattern(Regex("""//[^\n]*"""), CodeComment),
        TokenPattern(Regex("""/\*[\s\S]*?\*/"""), CodeComment),
        TokenPattern(Regex(""""[^"\\]*(\\.[^"\\]*)*""""), CodeString),
        TokenPattern(Regex("""'[^'\\]*(\\.[^'\\]*)*'"""), CodeString),
        TokenPattern(Regex("""`[^`\\]*(\\.[^`\\]*)*`"""), CodeString),
        TokenPattern(Regex("""\b0[xX][0-9a-fA-F]+\b"""), CodeNumber),
        TokenPattern(Regex("""\b\d+\.?\d*(?:[eE][+-]?\d+)?\b"""), CodeNumber),
        TokenPattern(
            Regex(
                """\b(?:val|var|fun|class|object|interface|enum|data|sealed|open|abstract|""" +
                    """override|private|protected|public|internal|import|package|""" +
                    """if|else|when|for|while|do|return|throw|try|catch|finally|""" +
                    """true|false|null|this|super|is|in|as|typealias|companion|""" +
                    """init|constructor|by|get|set|field|value|suspend|inline|""" +
                    """infix|operator|tailrec|external|annotation)\b""",
            ),
            CodeKeyword,
        ),
        TokenPattern(Regex("""[{}()\[\];,.]"""), CodePunctuation),
    )

/**
 * Builds an [AnnotatedString] from [code] with syntax highlighting colours
 * applied via token regexes. Covers keywords, strings, comments, numbers,
 * and punctuation — everything else remains the default light-grey.
 */
internal fun highlightSyntax(code: String): AnnotatedString =
    buildAnnotatedString {
        val tokens = HIGHLIGHT_TOKENS
        var lastIndex = 0
        val matches = mutableListOf<Pair<IntRange, Color>>()

        for (pattern in tokens) {
            for (match in pattern.regex.findAll(code)) {
                matches.add(match.range to pattern.color)
            }
        }

        matches.sortBy { it.first.first }
        // Resolve overlaps: later-in-text wins for same-pos, else first-match
        val resolved = mutableListOf<Pair<IntRange, Color>>()
        for (m in matches) {
            if (resolved.isEmpty()) {
                resolved.add(m)
            } else {
                val last = resolved.last()
                if (m.first.first >= last.first.last + 1) {
                    // Non-overlapping — safe add
                    resolved.add(m)
                } else if (m.first.first > last.first.first) {
                    // Overlap, this match starts later — it wins the overlapping portion
                    // Keep the last match's pre-overlap, then replace
                    resolved.removeAt(resolved.lastIndex)
                    // Split: keep text before overlap from last match
                    if (last.first.first < m.first.first) {
                        resolved.add(last.first.first..<m.first.first to last.second)
                    }
                    resolved.add(m)
                    // If last match extends beyond this match, add remainder
                    if (last.first.last > m.first.last) {
                        resolved.add(m.first.last + 1..last.first.last to last.second)
                    }
                }
                // else same start position — first match wins, skip this one
            }
        }

        var pos = 0
        for ((range, color) in resolved) {
            if (range.first > pos) {
                append(code.substring(pos, range.first))
            }
            withStyle(SpanStyle(color = color)) {
                append(code.substring(range.first, range.last + 1))
            }
            pos = range.last + 1
        }
        if (pos < code.length) {
            append(code.substring(pos))
        }
    }

private data class TokenPattern(
    val regex: Regex,
    val color: Color,
)

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}

// ── ClarifyBubble ─────────────────────────────────────────────────────────

/**
 * Clarification request from Hermes as one soft surface: question first, then choices and a
 * rounded answer field, one inverse conversation pill to send and a quiet decline. Status is an
 * icon plus text; locked, submitted or unconfirmed content stays readable as plain text instead
 * of a faded editor. Nothing here resends after an unconfirmed submission. Technical IDs sit
 * behind the collapsed 詳細 row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClarifyBubble(
    clarifyRequest: ClarifyUi,
    onRespondSingle: (String) -> Unit,
    onRespondBatch: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val questions = clarifyRequest.resolvedQuestions
    val isBatch = questions.size > 1
    val scheme = MaterialTheme.colorScheme

    var selectedChoicesByQid by remember(clarifyRequest.ref, clarifyRequest.clarifyId) {
        mutableStateOf<Map<String, Set<String>>>(emptyMap())
    }
    var customTextByQid by remember(clarifyRequest.ref, clarifyRequest.clarifyId) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var detailsExpanded by remember(clarifyRequest.ref, clarifyRequest.clarifyId) {
        mutableStateOf(false)
    }
    val anyAnswerable = questions.any { clarifyRequest.canRespond(it.qid) }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("clarify_bubble"),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) { heading() },
            ) {
                Icon(
                    imageVector = Icons.Outlined.QuestionAnswer,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Hermesからの質問",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }

            questions.forEachIndexed { index, q ->
                val answerable = clarifyRequest.canRespond(q.qid)
                val status = clarifyRequest.questionStates[q.qid]
                val hasLock =
                    clarifyRequest.questionLocks.containsKey(q.qid) || clarifyRequest.lockedAnswers.containsKey(q.qid)

                Spacer(Modifier.height(if (index == 0) 12.dp else 24.dp))
                Text(
                    text = if (isBatch) "${index + 1}. ${q.question}" else q.question,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (answerable) {
                    if (q.choices.isNotEmpty()) {
                        val customBlank = customTextByQid[q.qid].isNullOrBlank()
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text =
                                when {
                                    q.multiSelect -> "複数選択できます。選んだら送信してください"
                                    !isBatch && customBlank -> "選ぶとその回答をすぐに送信します"
                                    !isBatch -> "選んだ回答と入力した内容をまとめて送信します"
                                    else -> "1つ選んでください"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val selectedSet = selectedChoicesByQid[q.qid] ?: emptySet()
                            q.choices.forEach { choice ->
                                val isSelected = selectedSet.contains(choice)
                                FilterChip(
                                    selected = isSelected,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = {
                                        if (q.multiSelect) {
                                            val updated = if (isSelected) selectedSet - choice else selectedSet + choice
                                            selectedChoicesByQid = selectedChoicesByQid + (q.qid to updated)
                                        } else {
                                            if (!isBatch && customTextByQid[q.qid].isNullOrBlank()) {
                                                // Fast 1-tap respond for lone single-select question when no custom text is entered
                                                selectedChoicesByQid = selectedChoicesByQid + (q.qid to setOf(choice))
                                                onRespondSingle(choice)
                                            } else {
                                                val updated = if (isSelected) emptySet() else setOf(choice)
                                                selectedChoicesByQid = selectedChoicesByQid + (q.qid to updated)
                                            }
                                        }
                                    },
                                    label = { Text(choice) },
                                    leadingIcon =
                                        if (isSelected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customTextByQid[q.qid].orEmpty(),
                        onValueChange = { newText ->
                            customTextByQid = customTextByQid + (q.qid to newText)
                        },
                        label = {
                            Text(
                                if (q.choices.isEmpty()) {
                                    stringResource(R.string.message_your_response)
                                } else {
                                    "その他の回答（任意）"
                                },
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("clarify_input_${q.qid}"),
                        minLines = 2,
                    )
                } else {
                    clarifyStatusOf(
                        status = status,
                        locked = hasLock,
                        responseEnabled = clarifyRequest.responseEnabled,
                    ).let { ClarifyStatusLine(it) }

                    val lockedAnswer =
                        clarifyRequest.lockedAnswers[q.qid] ?: clarifyRequest.questionLocks[q.qid]?.answer
                    val draft = composeClarifyAnswer(selectedChoicesByQid[q.qid], customTextByQid[q.qid])
                    when {
                        hasLock && lockedAnswer == null -> {
                            ClarifyReadOnlyAnswer(label = null, text = "回答を辞退しました")
                        }

                        hasLock -> {
                            ClarifyReadOnlyAnswer(
                                label =
                                    if (status ==
                                        AttentionStatus.CONFIRMED
                                    ) {
                                        "送信した回答"
                                    } else {
                                        CLARIFY_UNCONFIRMED_LABEL
                                    },
                                text = lockedAnswer.orEmpty(),
                            )
                        }

                        draft.isNotBlank() -> {
                            ClarifyReadOnlyAnswer(
                                label =
                                    if (status == AttentionStatus.SUBMITTING || status == AttentionStatus.UNKNOWN) {
                                        CLARIFY_UNCONFIRMED_LABEL
                                    } else {
                                        "この端末で入力した内容"
                                    },
                                text = draft,
                            )
                        }
                    }
                }
            }

            if (anyAnswerable) {
                val hasAnyInput =
                    questions.any { q ->
                        clarifyRequest.canRespond(q.qid) &&
                            (
                                (selectedChoicesByQid[q.qid]?.isNotEmpty() == true) ||
                                    !customTextByQid[q.qid].isNullOrBlank()
                            )
                    }
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                    ) {
                        Text("回答を辞退")
                    }
                    Button(
                        onClick = {
                            if (isBatch) {
                                val answers =
                                    questions.filter { clarifyRequest.canRespond(it.qid) }.associate { q ->
                                        q.qid to
                                            composeClarifyAnswer(selectedChoicesByQid[q.qid], customTextByQid[q.qid])
                                    }
                                onRespondBatch(answers.filterValues { it.isNotBlank() })
                            } else {
                                val q = questions.first()
                                val finalAns = composeClarifyAnswer(selectedChoicesByQid[q.qid], customTextByQid[q.qid])
                                if (finalAns.isNotBlank()) {
                                    onRespondSingle(finalAns)
                                }
                            }
                        },
                        enabled = hasAnyInput,
                        shape = CircleShape,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = scheme.onSurface,
                                contentColor = scheme.surface,
                                disabledContainerColor = scheme.onSurface.copy(alpha = .12f),
                                disabledContentColor = scheme.onSurface.copy(alpha = .38f),
                            ),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = if (isBatch) "入力した回答を送信" else "回答を送信",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { detailsExpanded = !detailsExpanded },
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clarify_details")
                        .semantics { stateDescription = if (detailsExpanded) "表示中" else "非表示" },
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("詳細", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = detailsExpanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    clarifyDetailRows(clarifyRequest, questions.map { it.qid }).forEach { (label, value) ->
                        Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = scheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val CLARIFY_UNCONFIRMED_LABEL = "送信内容（受付未確認）"

/** Selected choices plus trimmed custom text, comma-joined; blank when nothing was entered. */
private fun composeClarifyAnswer(
    selected: Set<String>?,
    custom: String?,
): String {
    val selectedList = selected?.toList().orEmpty()
    val trimmed = custom?.trim().orEmpty()
    return when {
        selectedList.isNotEmpty() && trimmed.isNotEmpty() -> (selectedList + trimmed).joinToString(", ")
        selectedList.isNotEmpty() -> selectedList.joinToString(", ")
        else -> trimmed
    }
}

private class ClarifyStatus(
    val text: String,
    val tint: Color,
    val icon: ImageVector? = null,
    val note: String? = null,
)

/** Status for a question that cannot be answered now. Never claims delivery without a server ACK. */
@Composable
private fun clarifyStatusOf(
    status: AttentionStatus?,
    locked: Boolean,
    responseEnabled: Boolean,
): ClarifyStatus {
    val statusColors = LocalHermesStatusColors.current
    val scheme = MaterialTheme.colorScheme
    return when {
        status == AttentionStatus.CONFIRMED -> {
            ClarifyStatus("回答を受け付けました", statusColors.success, Icons.Default.Check)
        }

        status == AttentionStatus.SUBMITTING -> {
            ClarifyStatus("送信中・受付確認待ち", statusColors.info)
        }

        status == AttentionStatus.EXPIRED -> {
            ClarifyStatus("期限切れのため回答できません", scheme.onSurfaceVariant, Icons.Outlined.Schedule)
        }

        status == AttentionStatus.UNKNOWN || locked -> {
            ClarifyStatus(
                text = "受付を確認できていません",
                tint = statusColors.warning,
                icon = Icons.Outlined.ErrorOutline,
                note = "サーバーが受け取ったかはまだ分かりません。二重に回答しないよう、このアプリからは再送しません。",
            )
        }

        !responseEnabled -> {
            ClarifyStatus("接続を確認できないため、いまは回答できません", scheme.onSurfaceVariant, Icons.Outlined.CloudOff)
        }

        else -> {
            ClarifyStatus("この質問にはいま回答できません", scheme.onSurfaceVariant, Icons.Outlined.Info)
        }
    }
}

@Composable
private fun ClarifyStatusLine(status: ClarifyStatus) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (status.icon != null) {
                Icon(
                    imageVector = status.icon,
                    contentDescription = null,
                    tint = status.tint,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                CircularProgressIndicator(
                    color = status.tint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = status.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            status.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Submitted, locked or local answer as plain readable text, not a disabled editor. */
@Composable
private fun ClarifyReadOnlyAnswer(
    label: String?,
    text: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics(mergeDescendants = true) {},
    ) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Factual identifiers for diagnostics only; no server identity, process or credential fields. */
private fun clarifyDetailRows(
    clarifyRequest: ClarifyUi,
    qids: List<String>,
): List<Pair<String, String>> =
    buildList {
        clarifyRequest.ref?.let { ref ->
            add("依頼ID" to ref.requestId)
            ref.serverRequestId?.let { add("サーバー依頼ID" to it) }
        }
        clarifyRequest.clarifyId?.let { add("確認ID" to it) }
        add("質問ID" to qids.joinToString(", "))
        clarifyRequest.ref?.let { ref ->
            add("実行ID" to ref.session.runtimeId)
            ref.scope.connectionProfileId?.let { add("接続プロファイルID" to it) }
            ref.scope.serverProfileId?.let { add("サーバープロファイルID" to it) }
        }
    }

/**
 * Backward-compatible overload for legacy callers and unit tests.
 */
@Composable
fun ClarifyBubble(
    text: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClarifyBubble(
        clarifyRequest = ClarifyUi(text = text, options = options),
        onRespondSingle = onOptionSelected,
        onRespondBatch = { answers ->
            answers.values.firstOrNull()?.let(onOptionSelected)
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

// ── SubagentCard ──────────────────────────────────────────────────────────

/**
 * Inline card showing subagent task progress.
 *
 * - While running: [CircularProgressIndicator] + goal text on [tertiaryContainer].
 * - On completion: ✅ checkmark + summary text.
 */
@Composable
fun SubagentCard(
    indicator: SubagentIndicator,
    modifier: Modifier = Modifier,
) {
    val isComplete = indicator.type == "subagent.complete"
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).testTag("subagent_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isComplete) {
                Text(text = "✅", fontSize = 14.sp)
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            val displayText =
                if (isComplete && !indicator.summary.isNullOrBlank()) {
                    indicator.summary
                } else if (!indicator.goal.isNullOrBlank()) {
                    indicator.goal
                } else {
                    "Subagent task"
                }
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── TypingIndicator ───────────────────────────────────────────────────────

/**
 * Three bouncing dots shown while the assistant is typing / thinking.
 * Staggered animation: 0ms, 150ms, 300ms delay per dot.
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("typing_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 0 until 3) {
            TypingDot(delayMs = i * 150)
        }
    }
}

@Composable
private fun TypingDot(delayMs: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing_dot_$delayMs")
    val typingSpec: InfiniteRepeatableSpec<Float> =
        remember(delayMs) {
            infiniteRepeatable(
                animation = tween(400, delayMillis = delayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            )
        }
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = typingSpec,
        label = "typing_dot_offset_$delayMs",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = typingSpec,
        label = "typing_dot_alpha_$delayMs",
    )
    Box(
        modifier =
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .graphicsLayer {
                    this.translationY = offset
                    this.alpha = alpha
                },
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {}
    }
}
