package com.m57.hermescontrol.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import java.time.Duration
import java.time.Instant

private val japaneseKana = Regex("[\\p{sc=Hiragana}\\p{sc=Katakana}]")

@Composable
private fun HomeFocusTitle(title: String) {
    val typography = MaterialTheme.typography.headlineMedium
    Text(
        title,
        style =
            typography.copy(
                fontFamily = FontFamily.Default,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "palt",
                // Compose forwards phrase breaking to Android from API 33.
                lineBreak =
                    LineBreak.Heading.copy(
                        strictness = LineBreak.Strictness.Strict,
                        wordBreak = LineBreak.WordBreak.Phrase,
                    ),
                // Japanese titles may arrive even when the app is using a different locale.
                localeList = if (japaneseKana.containsMatchIn(title)) LocaleList("ja") else typography.localeList,
            ),
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
internal fun HomeFocusAction(
    title: String,
    details: String?,
    actionLabel: String,
    actionModifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.padding(top = spacing.md, bottom = spacing.sm).testTag("home_focus"),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        HomeFocusTitle(title)
        if (details != null) {
            Text(details, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        Button(
            onClick = onOpen,
            modifier = actionModifier.heightIn(min = 48.dp).testTag("home_focus_action"),
            shapes = ButtonDefaults.shapes(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = colors.inverseSurface,
                    contentColor = colors.inverseOnSurface,
                ),
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
internal fun HomeStatusPill(
    pendingCount: Int,
    hasDecisions: Boolean,
    confirmed: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalHermesStatusColors.current
    val status =
        when {
            pendingCount > 0 -> "判断待ち ${pendingCount}件"
            hasDecisions -> "応答状況を確認"
            confirmed -> "判断待ちなし"
            else -> "判断待ちの状況は未確認"
        }
    val toggle =
        if (hasDecisions) {
            Modifier
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = foldStateDescription(expanded) }
        } else {
            Modifier
        }
    Row(
        Modifier
            .testTag("home_status_pill")
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(toggle)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (pendingCount > 0) colors.warning else colors.neutral, CircleShape))
        Text(
            "Hermes · $status",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (hasDecisions) HomeFoldIcon(expanded)
    }
}

internal fun Instant.isHomeDecisionSoon(now: Instant): Boolean =
    isAfter(now) && !isAfter(now.plus(Duration.ofHours(24)))

internal fun homeFocusDeadline(
    value: String?,
    now: Instant,
): String? {
    val deadline = todayDeadline(value) ?: return null
    if (deadline == TodayDeadline.Unknown) return "期限不明"
    val time = deadline.label(now)
    if (deadline.isPast(now)) return "期限切れ（$time）"
    val end = homeDeadlineInstant(value) ?: return "期限 $time"
    val remaining = Duration.between(now, end).toMinutes()
    val hours = remaining / 60
    val countdown =
        when {
            remaining <= 0 -> "残り1分未満"
            remaining < 60 -> "残り${remaining}分"
            hours < 24 && remaining % 60 == 0L -> "残り${hours}時間"
            hours < 24 -> "残り${hours}時間${remaining % 60}分"
            hours % 24 == 0L -> "残り${hours / 24}日"
            else -> "残り${hours / 24}日${hours % 24}時間"
        }
    return listOfNotNull("期限 $time", countdown).joinToString(" · ")
}
