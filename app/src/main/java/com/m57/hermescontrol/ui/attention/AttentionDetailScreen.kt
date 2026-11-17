package com.m57.hermescontrol.ui.attention

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.AttentionKind
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.components.ClarifyBubble
import com.m57.hermescontrol.ui.common.DetailRow
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import java.text.DateFormat
import java.util.Date

@Composable
fun AttentionDetailScreen(
    ref: RequestRef,
    viewModel: AttentionViewModel = viewModel(),
) {
    val app = LocalContext.current.applicationContext as HermesControlApp
    val items by app.attentionRepository.items.collectAsState()
    val observed by app.runRepository.snapshot.collectAsState()
    val ready =
        observed.connection.scope == ref.scope && observed.connection.state == LinkState.CONNECTED &&
            app.attentionCoordinator.isReady(ref)
    AttentionDetailContent(
        ref,
        items.firstOrNull { it.ref == ref },
        ready,
        onApprove = { viewModel.approve(ref, it) },
        onClarify = { viewModel.clarify(ref, it) },
        onBack = { NavigationController.goBack() },
    )
}

/**
 * One request, read top to bottom: state (only when it blocks a response), what is
 * being asked, the exact operation, what the server told us, then technical identity.
 * Fields the server did not provide are named once in a single line instead of a
 * stack of identical "unknown" rows.
 */
@Composable
internal fun AttentionDetailContent(
    ref: RequestRef,
    item: AttentionItem?,
    ready: Boolean,
    onApprove: (String) -> Unit,
    onClarify: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val canRespond = ready && item?.status == AttentionStatus.PENDING
    HermesScaffold(
        title = { Text(stringResource(R.string.attention_detail_title)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        Column(
            Modifier.fillMaxSize().imePadding().padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                if (item == null) {
                    InlineNotice(stringResource(R.string.attention_missing), tone = NoticeTone.Warning)
                } else {
                    AttentionStateNotice(item.status, ready)
                    Text(item.title, style = MaterialTheme.typography.titleLarge)
                }
                if (item?.kind == AttentionKind.CLARIFICATION) {
                    ClarificationBlock(ref, item, canRespond, onClarify)
                }
                item?.let { RequestFacts(it) }
                TechnicalIdentity(ref)
            }
            if (item?.kind == AttentionKind.APPROVAL && item.status.isOpenDecision()) {
                ApprovalDecisionBar(item, canRespond, onApprove)
            }
        }
    }
}

/** Shown only when something stops a normal response; a pending, ready request needs no banner. */
@Composable
private fun AttentionStateNotice(
    status: AttentionStatus,
    ready: Boolean,
) {
    val (text, tone) =
        when (status) {
            AttentionStatus.PENDING -> if (ready) return else R.string.attention_on_hold to NoticeTone.Info
            AttentionStatus.SUBMITTING -> R.string.attention_submitting to NoticeTone.Info
            AttentionStatus.CONFIRMED -> R.string.attention_confirmed to NoticeTone.Info
            AttentionStatus.EXPIRED -> R.string.attention_expired_detail to NoticeTone.Warning
            AttentionStatus.UNKNOWN -> R.string.attention_unknown_result to NoticeTone.Warning
        }
    InlineNotice(stringResource(text), tone = tone)
}

@Composable
private fun ClarificationBlock(
    ref: RequestRef,
    item: AttentionItem,
    canRespond: Boolean,
    onClarify: (String?) -> Unit,
) {
    val details = item.details
    val questionKey = ref.questionId ?: "q0"
    ClarifyBubble(
        ClarifyUi(
            text = item.title,
            options = details?.options.orEmpty(),
            clarifyId = ref.requestId,
            questionId = ref.questionId,
            multiSelect = details?.multiSelect == true,
            ref = ref,
            questionStates = mapOf(questionKey to item.status),
            responseEnabled = canRespond,
            questionLocks = item.clarificationLock?.let { mapOf(questionKey to it) }.orEmpty(),
        ),
        onRespondSingle = { onClarify(it) },
        onRespondBatch = { answers -> answers[questionKey]?.let(onClarify) },
        onDismiss = {
            onClarify(
                if (ref.serverRequestId != null) {
                    null
                } else {
                    com.m57.hermescontrol.domain.hermes.CLARIFICATION_DECLINED_ANSWER
                },
            )
        },
    )
}

@Composable
private fun RequestFacts(item: AttentionItem) {
    val spacing = LocalSpacing.current
    val details = item.details
    val operation = details?.action?.takeIf { it.isNotBlank() && it != item.title }
    val facts =
        listOf(
            stringResource(R.string.attention_target) to details?.target,
            stringResource(R.string.attention_impact) to details?.impact,
            stringResource(R.string.attention_reversibility) to details?.reversibility,
            stringResource(R.string.attention_permission) to
                details?.permissions?.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            stringResource(R.string.attention_expiry) to
                item.expiresAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) },
        )
    val provided = facts.filter { !it.second.isNullOrBlank() }
    val missing = facts.filter { it.second.isNullOrBlank() }.map { it.first }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        if (operation != null) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                FactLabel(stringResource(R.string.attention_operation))
                ApprovalOperationBlock(operation)
            }
        }
        details?.patterns?.takeIf { it.isNotEmpty() }?.let { patterns ->
            Fact(stringResource(R.string.attention_patterns), patterns.joinToString("\n"))
        }
        provided.forEach { (label, value) -> Fact(label, value.orEmpty()) }
        if (missing.isNotEmpty()) {
            Text(
                text =
                    stringResource(
                        R.string.attention_missing_fields,
                        missing.joinToString(stringResource(R.string.attention_missing_separator)),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TechnicalIdentity(ref: RequestRef) {
    val spacing = LocalSpacing.current
    val unknown = stringResource(R.string.attention_not_provided)
    val rows =
        listOfNotNull(
            DetailRow(stringResource(R.string.attention_server), ref.scope.serverIdentity),
            DetailRow(stringResource(R.string.attention_connection_profile), ref.scope.connectionProfileId ?: unknown),
            DetailRow(stringResource(R.string.attention_server_profile), ref.scope.serverProfileId ?: unknown),
            DetailRow(stringResource(R.string.attention_session), ref.session.runtimeId),
            DetailRow(stringResource(R.string.attention_request), ref.requestId),
            ref.questionId?.let { DetailRow(stringResource(R.string.attention_question), it) },
        )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            stringResource(R.string.attention_technical),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row ->
            Column {
                FactLabel(row.label)
                Text(
                    row.value.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Fact(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FactLabel(label)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun FactLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Thumb-reach decisions: once/deny only (UX_SPEC §6). Editing is not offered by this transport. */
@Composable
private fun ApprovalDecisionBar(
    item: AttentionItem,
    canRespond: Boolean,
    onApprove: (String) -> Unit,
) {
    val spacing = LocalSpacing.current

    fun offered(choice: String) = item.details?.approvalChoices?.let { choice in it } ?: true
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            TextButton(
                onClick = { onApprove("deny") },
                enabled = canRespond && offered("deny"),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("attention_deny"),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.attention_deny))
            }
            PrimaryPillButton(
                onClick = { onApprove("once") },
                enabled = canRespond && offered("once"),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("attention_once"),
            ) {
                Text(stringResource(R.string.attention_once))
            }
        }
        Text(
            stringResource(R.string.attention_modify_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
