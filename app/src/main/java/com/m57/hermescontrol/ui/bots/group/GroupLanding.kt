package com.m57.hermescontrol.ui.bots.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.bots.GroupInfo

@Composable
internal fun GroupLanding(
    groups: List<GroupInfo>,
    loading: Boolean,
    error: String?,
    onSelect: (GroupInfo) -> Unit,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(modifier.fillMaxSize().testTag("group_landing"), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text("グループを選択", Modifier.padding(spacing.lg), style = MaterialTheme.typography.titleLarge)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            Text(it, Modifier.padding(horizontal = spacing.lg), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefresh) { Text("再読み込み") }
        }
        LazyColumn(Modifier.weight(1f).testTag("group_landing_list")) {
            items(groups, key = { it.name }) { group ->
                ListItem(
                    headlineContent = { Text(group.displayName) },
                    supportingContent = { Text(group.members.joinToString("・") { it.effectiveTitle }) },
                    leadingContent = { Icon(Icons.Filled.Groups, null) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(
                                "group_select_${group.name}",
                            ).clickable { onSelect(group) },
                )
            }
            if (!loading && error == null && groups.isEmpty()) {
                item { Text("グループを作成して、参加するエージェントを選べます。", Modifier.padding(spacing.lg)) }
            }
        }
        TextButton(
            onClick = onCreate,
            modifier = Modifier.padding(horizontal = spacing.md).testTag("group_landing_create"),
        ) {
            Text("グループを作成")
        }
    }
}
