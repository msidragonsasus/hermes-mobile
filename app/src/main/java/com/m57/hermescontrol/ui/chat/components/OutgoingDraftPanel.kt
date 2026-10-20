package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.DraftState
import com.m57.hermescontrol.domain.hermes.OutgoingDraft
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.DetailRow

@Composable
fun OutgoingDraftPanel(
    drafts: List<OutgoingDraft>,
    activeIds: Set<String>,
    storageError: Boolean,
    onRestore: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(drafts.isEmpty()) { if (drafts.isEmpty()) expanded = false }
    val spacing = LocalSpacing.current
    val status = LocalHermesStatusColors.current
    val title = pluralStringResource(R.plurals.outgoing_drafts_count, drafts.size, drafts.size)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        if (storageError) {
            Text(
                stringResource(R.string.draft_storage_unavailable),
                color = status.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (drafts.isNotEmpty()) {
            TextButton(onClick = {
                expanded = true
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("outgoing-drafts")) {
                Icon(
                    if (drafts.any {
                            it.state == DraftState.UNKNOWN
                        }
                    ) {
                        Icons.Filled.Warning
                    } else {
                        Icons.Filled.Description
                    },
                    contentDescription = null,
                )
                Spacer(Modifier.width(spacing.sm))
                Text(title)
            }
        }
    }
    if (expanded && drafts.isNotEmpty()) {
        DetailDialog(title, listOf(DetailRow("", stringResource(R.string.draft_saved_hint))), onDismiss = {
            expanded =
                false
        }) {
            drafts.forEach { draft ->
                val active = draft.id in activeIds
                val unknown = draft.state == DraftState.UNKNOWN
                val stateLabel =
                    stringResource(
                        if (active) {
                            R.string.draft_sending
                        } else if (unknown) {
                            R.string.draft_result_unknown
                        } else {
                            R.string.draft_not_sent
                        },
                    )
                Column(
                    Modifier.fillMaxWidth().padding(vertical = spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Icon(
                        if (active) {
                            Icons.Filled.Schedule
                        } else if (unknown) {
                            Icons.Filled.Warning
                        } else {
                            Icons.Filled.Description
                        },
                        contentDescription = null,
                        tint = if (unknown) status.warning else MaterialTheme.colorScheme.primary,
                    )
                    Text(stateLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.draft_conversation,
                            draft.savedSessionId ?: stringResource(R.string.draft_new_conversation),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (unknown &&
                        !active
                    ) {
                        Text(
                            stringResource(R.string.draft_unknown_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(stringResource(R.string.draft_preview), style = MaterialTheme.typography.labelMedium)
                    Text(
                        draft.text.take(240),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (draft.attachments.isNotEmpty()) {
                        Text(
                            draft.attachments.joinToString("\n") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(stringResource(R.string.draft_attachment_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!unknown) {
                        TextButton(
                            onClick = {
                                onRestore(draft.id)
                                expanded = false
                            },
                            enabled = !active,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(
                                        min = 48.dp,
                                    ).testTag("draft-restore-${draft.id}"),
                        ) {
                            Text(stringResource(R.string.draft_open))
                        }
                    }
                    if (unknown && draft.savedSessionId != null) {
                        TextButton(onClick = {
                            onOpenConversation(draft.id)
                            expanded = false
                        }, enabled = !active, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.draft_open_conversation))
                        }
                    }
                    TextButton(
                        onClick = {
                            onDiscard(draft.id)
                        },
                        enabled = !active,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    min = 48.dp,
                                ).testTag("draft-discard-${draft.id}"),
                    ) {
                        Text(stringResource(if (unknown) R.string.draft_remove_checked else R.string.draft_discard))
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
