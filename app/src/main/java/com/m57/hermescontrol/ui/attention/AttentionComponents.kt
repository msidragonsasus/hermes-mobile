package com.m57.hermescontrol.ui.attention

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.navigation.AttentionDetailKey
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.ApprovalInfo
import com.m57.hermescontrol.ui.common.PrimaryPillButton

@Composable
fun AttentionOpenButton(
    ref: RequestRef,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = { NavigationController.navigateTo(AttentionDetailKey(ref)) },
        modifier = modifier,
        shapes = ButtonDefaults.shapes(),
    ) {
        Text(stringResource(R.string.attention_open_details))
    }
}

@Composable
fun AttentionSummaryCard(
    item: AttentionItem,
    ready: Boolean,
) {
    val spacing = LocalSpacing.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            AttentionStatusLabel(item.status, ready)
            AttentionOpenButton(item.ref)
        }
    }
}

@Composable
fun AttentionStatusLabel(
    status: AttentionStatus,
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHermesStatusColors.current
    val label =
        when (status) {
            AttentionStatus.PENDING -> if (ready) R.string.attention_pending else R.string.attention_on_hold
            AttentionStatus.SUBMITTING -> R.string.attention_submitting
            AttentionStatus.CONFIRMED -> R.string.attention_confirmed
            AttentionStatus.EXPIRED -> R.string.attention_expired
            AttentionStatus.UNKNOWN -> R.string.attention_unknown_result
        }
    val color =
        when (status) {
            AttentionStatus.CONFIRMED -> colors.success
            AttentionStatus.EXPIRED, AttentionStatus.UNKNOWN -> colors.warning
            else -> colors.info
        }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(stringResource(label), color = color, style = MaterialTheme.typography.bodySmall)
    }
}

/** True while a decision can still be made or is awaiting its acknowledgement. */
internal fun AttentionStatus.isOpenDecision(): Boolean =
    this == AttentionStatus.PENDING || this == AttentionStatus.SUBMITTING

/** The exact operation awaiting approval, shown verbatim in monospace. */
@Composable
internal fun ApprovalOperationBlock(
    text: String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Chat-inline approval, decided in place like the worker's `WorkerApprovalObject`.
 * Once the request is closed (expired, answered, unknown) it folds to one quiet line
 * that still opens the details.
 */
@Composable
fun ApprovalRequestCard(
    info: ApprovalInfo,
    onRespond: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val operation = info.command?.takeIf { it.isNotBlank() }
    val description = info.description?.takeIf { it.isNotBlank() && it != operation }
    val openDetails = info.ref?.let { ref -> { NavigationController.navigateTo(AttentionDetailKey(ref)) } }

    if (!info.status.isOpenDecision()) {
        ClosedApprovalLine(info.status, operation ?: description, openDetails, modifier)
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth().testTag("approval_request_card"),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = description ?: stringResource(R.string.attention_approval_heading),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            operation?.let { ApprovalOperationBlock(it) }
            if (info.status == AttentionStatus.SUBMITTING || !info.responseEnabled) {
                AttentionStatusLabel(info.status, info.responseEnabled)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (openDetails != null) {
                    TextButton(onClick = openDetails, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.attention_details_short))
                    }
                }
                Spacer(Modifier.weight(1f))
                val canRespond = info.responseEnabled && info.status == AttentionStatus.PENDING
                TextButton(
                    onClick = { onRespond("deny") },
                    enabled = canRespond && info.offers("deny"),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.testTag("approval_card_deny"),
                ) {
                    Text(stringResource(R.string.attention_deny))
                }
                PrimaryPillButton(
                    onClick = { onRespond("once") },
                    enabled = canRespond && info.offers("once"),
                    modifier = Modifier.testTag("approval_card_once"),
                ) {
                    Text(stringResource(R.string.attention_once))
                }
            }
        }
    }
}

private fun ApprovalInfo.offers(choice: String): Boolean = choices?.let { choice in it } ?: true

@Composable
private fun ClosedApprovalLine(
    status: AttentionStatus,
    summary: String?,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state =
        stringResource(
            when (status) {
                AttentionStatus.CONFIRMED -> R.string.attention_closed_confirmed
                AttentionStatus.EXPIRED -> R.string.attention_closed_expired
                else -> R.string.attention_closed_unknown
            },
        )
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(vertical = LocalSpacing.current.xs)
                .testTag("approval_closed_line"),
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.attention_closed_label, state),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        summary?.let {
            Text(
                text = it,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } ?: Spacer(Modifier.weight(1f))
        if (onOpen != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.attention_open_details),
                tint = color,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
