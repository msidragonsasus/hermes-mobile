package com.m57.hermescontrol.ui.attention

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import com.m57.hermescontrol.ui.common.NavIcon
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

@Composable
internal fun AttentionDetailContent(
    ref: RequestRef,
    item: AttentionItem?,
    ready: Boolean,
    onApprove: (String) -> Unit,
    onClarify: (String) -> Unit,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val unknown = stringResource(R.string.attention_not_provided)
    val canRespond = ready && item?.status == AttentionStatus.PENDING
    HermesScaffold(
        title = { Text(stringResource(R.string.attention_detail_title)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        Column(
            Modifier.fillMaxSize().imePadding().padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                if (item == null) {
                    Text(stringResource(R.string.attention_missing))
                } else {
                    Text(item.title, style = MaterialTheme.typography.titleLarge)
                    AttentionStatusLabel(item.status, ready)
                }
                val details = item?.details
                if (item?.kind == AttentionKind.CLARIFICATION) {
                    ClarifyBubble(
                        ClarifyUi(
                            text = item.title,
                            options = details?.options.orEmpty(),
                            clarifyId = ref.requestId,
                            questionId = ref.questionId,
                            multiSelect = details?.multiSelect == true,
                            ref = ref,
                            questionStates = mapOf((ref.questionId ?: "q0") to item.status),
                            responseEnabled = canRespond,
                        ),
                        onRespondSingle = onClarify,
                        onRespondBatch = { answers -> answers[ref.questionId ?: "q0"]?.let(onClarify) },
                        onDismiss = { onClarify(com.m57.hermescontrol.domain.hermes.CLARIFICATION_DECLINED_ANSWER) },
                    )
                }
                val rows =
                    listOf(
                        DetailRow(stringResource(R.string.attention_action), details?.action ?: item?.title ?: unknown),
                        DetailRow(stringResource(R.string.attention_target), details?.target ?: unknown),
                        DetailRow(stringResource(R.string.attention_impact), details?.impact ?: unknown),
                        DetailRow(stringResource(R.string.attention_reversibility), details?.reversibility ?: unknown),
                        DetailRow(
                            stringResource(R.string.attention_permission),
                            details?.permissions?.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: unknown,
                        ),
                        DetailRow(
                            stringResource(R.string.attention_expiry),
                            item?.expiresAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: unknown,
                        ),
                        DetailRow(
                            stringResource(R.string.attention_source),
                            "${ref.scope.serverIdentity}\n${ref.scope.connectionProfileId ?: unknown}\n${ref.scope.serverProfileId ?: unknown}\n${ref.session.runtimeId}",
                        ),
                        DetailRow(stringResource(R.string.attention_request), ref.requestId),
                        DetailRow(stringResource(R.string.attention_question), ref.questionId ?: unknown),
                    )
                rows.forEach { row ->
                    Column {
                        Text(row.label, style = MaterialTheme.typography.labelMedium)
                        Text(row.value.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.attention_modify_unsupported), style = MaterialTheme.typography.bodySmall)
            }
            if (item?.kind == AttentionKind.APPROVAL) {
                for (choice in listOf("deny", "once")) {
                    val offered = item.details?.approvalChoices?.let { choice in it } ?: true
                    Button(
                        onClick = { onApprove(choice) },
                        enabled = canRespond && offered,
                        modifier = Modifier.fillMaxWidth().testTag("attention_$choice"),
                    ) {
                        Text(stringResource(if (choice == "deny") R.string.attention_deny else R.string.attention_once))
                    }
                }
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.attention_modify))
                }
            }
        }
    }
}
