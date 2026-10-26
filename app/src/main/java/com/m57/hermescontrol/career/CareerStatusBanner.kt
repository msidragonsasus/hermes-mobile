package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing

/** One connection/sync status for a career area, so tabs never stack several warnings that say the same thing. */
internal enum class CareerStatusKind { LOGIN, ERROR, STALE }

internal data class CareerStatus(
    val kind: CareerStatusKind,
    val title: String,
    val detail: String? = null,
)

private const val LOGIN_MARKER = "再ログインが必要です"

/** Collapses the first error and the stale flag into a single status; null when everything is current. */
internal fun careerStatusOf(
    errors: List<String?>,
    stale: Boolean,
    staleDetail: String = "保存済みの情報を表示しています。",
): CareerStatus? {
    val error = errors.firstOrNull { !it.isNullOrBlank() }
    val savedNote = staleDetail.takeIf { stale }
    return when {
        errors.any { it?.contains(LOGIN_MARKER) == true } -> {
            CareerStatus(CareerStatusKind.LOGIN, "$LOGIN_MARKER。", savedNote)
        }

        error != null -> {
            CareerStatus(CareerStatusKind.ERROR, error, savedNote.takeUnless { error.contains("前回") })
        }

        stale -> {
            CareerStatus(CareerStatusKind.STALE, "オフラインです。", staleDetail)
        }

        else -> {
            null
        }
    }
}

@Composable
internal fun CareerStatusBanner(
    status: CareerStatus?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    status ?: return
    val spacing = LocalSpacing.current
    val colors = LocalHermesStatusColors.current
    val (icon, tint) =
        when (status.kind) {
            CareerStatusKind.LOGIN -> Icons.AutoMirrored.Outlined.Login to colors.error
            CareerStatusKind.ERROR -> Icons.Outlined.ErrorOutline to colors.error
            CareerStatusKind.STALE -> Icons.Outlined.CloudOff to colors.warning
        }
    // Compact inline notice (DESIGN.md "Notices and status"): one row, never a tall banner.
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().testTag("career_status_banner"),
    ) {
        Row(
            Modifier.padding(start = spacing.md, top = 2.dp, bottom = 2.dp, end = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(vertical = spacing.xs)) {
                Text(status.title, style = MaterialTheme.typography.bodySmall)
                status.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (status.kind == CareerStatusKind.LOGIN) {
                TextButton(
                    onClick = { NavigationController.navigateTo(SettingsConnection) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.testTag("career_status_login"),
                ) { Text("ログイン") }
            } else if (onRetry != null) {
                TextButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text("再試行") }
            }
        }
    }
}
