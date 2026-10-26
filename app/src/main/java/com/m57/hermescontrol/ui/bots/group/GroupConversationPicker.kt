package com.m57.hermescontrol.ui.bots.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.openclaw.OpenClawTitles
import com.m57.hermescontrol.openclaw.resolveClawTitleKey

@Composable
internal fun groupConversationViewModel(): GroupChatViewModel {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { EncryptedGroupConversationStore(context) }
    val titles = remember { OpenClawTitles() }
    return viewModel {
        GroupChatViewModel(
            conversationStore = store,
            conversationScope = ::groupConversationScope,
            serverTransport = ApiGroupServerTransport(),
            titleGenerator = { firstMessage ->
                val owner = groupConversationScope()
                resolveClawTitleKey(null, { owner != null && owner == groupConversationScope() })
                    ?.let { key -> titles.generate(key, firstMessage) }
            },
        )
    }
}

@Composable
internal fun GroupConversationPicker(
    state: GroupChatUiState,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_conversations)) },
        text = {
            Column {
                Text(stringResource(R.string.group_conversations_local))
                LazyColumn(
                    Modifier.weight(1f, fill = false).heightIn(max = 380.dp).testTag("group_conversations_list"),
                ) {
                    items(state.conversations.reversed(), key = { it.id }) { conversation ->
                        TextButton(
                            onClick = { onSelect(conversation.id) },
                            modifier = Modifier.fillMaxWidth().testTag("group_conversation_${conversation.id}"),
                        ) {
                            val title = conversation.title.ifBlank { stringResource(R.string.group_new_conversation) }
                            val unresolved =
                                conversation.turns.any {
                                    it.delivery == GroupDelivery.UNKNOWN || it.delivery == GroupDelivery.ACCEPTED
                                }
                            Text(
                                (if (conversation.id == state.conversationId) "✓ " else "") + title +
                                    if (unresolved) " · " + stringResource(R.string.group_delivery_unknown) else "",
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew, modifier = Modifier.testTag("group_new_conversation")) {
                Text(stringResource(R.string.group_new_conversation))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
