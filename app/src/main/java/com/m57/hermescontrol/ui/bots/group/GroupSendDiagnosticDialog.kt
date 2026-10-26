package com.m57.hermescontrol.ui.bots.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GroupSendDiagnosticDialog(
    snapshot: GroupDiagnosticSnapshot?,
    onCopy: suspend (GroupDiagnosticChoice) -> Boolean,
    onDismiss: () -> Unit,
) {
    var selected by remember(snapshot?.scopeTag) { mutableStateOf<String?>(null) }
    var copied by remember(snapshot?.scopeTag, selected) { mutableStateOf(false) }
    var copyFailed by remember(snapshot?.scopeTag, selected) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val choice = snapshot?.choices?.singleOrNull { it.turnId == selected }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_send_diagnostics)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.group_diagnostics_explanation))
                if (snapshot?.choices.isNullOrEmpty()) {
                    Text(stringResource(R.string.group_diagnostics_missing))
                } else if (choice == null) {
                    Text(stringResource(R.string.group_diagnostics_select_bot))
                    snapshot?.choices.orEmpty().forEachIndexed { index, item ->
                        TextButton(
                            onClick = { selected = item.turnId },
                            modifier = Modifier.fillMaxWidth().testTag("group_diagnostic_choice_${item.turnId}"),
                        ) {
                            Text(stringResource(R.string.group_diagnostics_bot_turn, item.diagnostic.bot, index + 1))
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.group_diagnostics_preview),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(stringResource(R.string.group_diagnostics_missing_fields))
                    SelectionContainer {
                        Text(
                            choice.diagnostic.copyText(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("group_diagnostic_preview"),
                        )
                    }
                    TextButton(onClick = { selected = null }) {
                        Text(stringResource(R.string.group_diagnostics_select_bot))
                    }
                    if (copyFailed) Text(stringResource(R.string.group_diagnostics_copy_failed))
                }
            }
        },
        confirmButton = {
            if (choice != null) {
                TextButton(
                    onClick = {
                        scope.launch {
                            copied =
                                try {
                                    onCopy(choice)
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    false
                                }
                            copyFailed = !copied
                        }
                    },
                    modifier = Modifier.testTag("group_diagnostic_copy"),
                ) {
                    Text(
                        stringResource(
                            if (copied) R.string.group_diagnostics_copied else R.string.group_diagnostics_copy,
                        ),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
