package com.m57.hermescontrol.ui.chat.brainstorm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.BrainstormSnapshot
import com.m57.hermescontrol.data.model.BrainstormSpeakerSnapshot
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NoticeTone

/**
 * M3 Expressive inline brainstorm panel rendered in Hermes Main conversation (Step 4).
 * Uses a single quiet surfaceContainerLow container with hairline dividers per speaker (no nested cards),
 * authentic speaker names & avatars, explicit partial/unknown/presentation_error handling, and non-interactive Markdown.
 */
@Composable
fun BrainstormCard(
    snapshot: BrainstormSnapshot,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalHermesStatusColors.current
    val settledCount = snapshot.speakers.count { it.status == "settled" }
    val totalCount = snapshot.speakers.size

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("brainstorm_card_${snapshot.id}"),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.brainstorm_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                when (snapshot.status) {
                    "running" -> {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(R.string.brainstorm_status_running),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    "unknown" -> {
                        Text(
                            text = stringResource(R.string.brainstorm_status_unknown),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColors.warning,
                        )
                    }

                    else -> {
                        if (totalCount > 0) {
                            Text(
                                text = "$settledCount/$totalCount",
                                style = MaterialTheme.typography.labelMedium,
                                color =
                                    if (snapshot.status == "completed") {
                                        statusColors.success
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }
                }
            }

            if (snapshot.status == "unknown") {
                Spacer(modifier = Modifier.height(8.dp))
                InlineNotice(
                    text = stringResource(R.string.brainstorm_status_unknown),
                    tone = NoticeTone.Warning,
                    modifier = Modifier.testTag("brainstorm_unknown_notice"),
                )
            }

            if (!snapshot.presentation_error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                InlineNotice(
                    text = stringResource(R.string.brainstorm_snapshot_error),
                    tone = NoticeTone.Warning,
                    modifier = Modifier.testTag("brainstorm_snapshot_error_notice"),
                )
            }

            if (snapshot.speakers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                snapshot.speakers.forEachIndexed { index, speaker ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    BrainstormSpeakerRow(speaker = speaker)
                }
            } else if (snapshot.summary_input.isNotBlank()) {
                // Source text fallback only — never presented as Hermes model synthesis.
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = snapshot.summary_input,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BrainstormSpeakerRow(speaker: BrainstormSpeakerSnapshot) {
    val statusColors = LocalHermesStatusColors.current
    val (statusLabel, statusColor) =
        when (speaker.status) {
            "queued" -> stringResource(R.string.brainstorm_speaker_queued) to MaterialTheme.colorScheme.onSurfaceVariant
            "pending" -> stringResource(R.string.brainstorm_speaker_pending) to statusColors.warning
            "settled" -> stringResource(R.string.brainstorm_speaker_settled) to statusColors.success
            "failed" -> stringResource(R.string.brainstorm_speaker_failed) to statusColors.error
            "cancelled" -> stringResource(R.string.brainstorm_speaker_cancelled) to statusColors.warning
            else -> speaker.status to MaterialTheme.colorScheme.onSurfaceVariant
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("brainstorm_speaker_${speaker.profile}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BotAvatar(
                name = speaker.displayName,
                avatar = speaker.resolvedAvatar(),
                size = 28.dp,
                showPresence = false,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = speaker.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        if (speaker.error.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            InlineNotice(
                text = speaker.error,
                tone = NoticeTone.Error,
            )
        }

        if (speaker.reply.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            MarkdownText(
                text = speaker.reply,
                textColor = MaterialTheme.colorScheme.onSurface,
                allowInteractiveContent = false,
            )
        }
    }
}
