package com.m57.hermescontrol.ui.attention

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.navigation.AttentionDetailKey
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing

@Composable
fun AttentionOpenButton(
    ref: RequestRef,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = { NavigationController.navigateTo(AttentionDetailKey(ref)) }, modifier = modifier) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        Icon(Icons.Default.Info, contentDescription = null, tint = color)
        Text(stringResource(label), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
