package com.m57.hermescontrol.ui.settings.limits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.parseLimitInstant
import com.m57.hermescontrol.data.remote.AccountLimitsRepository
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsListItem
import com.m57.hermescontrol.ui.common.SkeletonListState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AccountLimitsScreen() {
    val profile by AuthManager.activeProfileId.collectAsStateWithLifecycle()
    val connection by AuthManager.contextFlow.collectAsStateWithLifecycle()
    val scope = remember(connection, profile) { AuthManager.uiPreferenceScope() }
    val model: AccountLimitsViewModel = viewModel(key = "account-limits:$scope") { AccountLimitsViewModel() }
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val coroutineScope = rememberCoroutineScope()
    var now by remember { mutableStateOf(Instant.now()) }

    suspend fun refresh() {
        model.refresh { AccountLimitsRepository.read(profile) }
    }

    LaunchedEffect(scope, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                while (true) {
                    refresh()
                    delay(60_000)
                }
            }
            while (true) {
                now = Instant.now()
                delay(1_000)
            }
        }
    }
    HermesScaffold(
        title = { Text(stringResource(R.string.settings_limits)) },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        isRefreshing = state.status == LimitsStatus.LOADING,
        onRefresh = { coroutineScope.launch { refresh() } },
    ) {
        AccountLimitsContent(state, now, onRetry = { coroutineScope.launch { refresh() } })
    }
}

@Composable
internal fun AccountLimitsContent(
    state: AccountLimitsState,
    now: Instant,
    onRetry: () -> Unit,
) {
    val spacing = LocalSpacing.current
    when (state.status) {
        LimitsStatus.LOADING -> {
            SkeletonListState()
        }

        LimitsStatus.ERROR -> {
            ErrorState(stringResource(R.string.settings_limits_error), onRetry = onRetry)
        }

        LimitsStatus.UNAVAILABLE -> {
            ErrorState(stringResource(R.string.settings_limits_unavailable), onRetry = onRetry)
        }

        LimitsStatus.READY -> {
            val snapshot = state.snapshot ?: return
            val fresh = snapshot.isFreshAt(now)
            LazyColumn(
                Modifier.fillMaxSize().testTag("account_limits"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item("provider") {
                    SettingsGroupLabel(listOfNotNull(snapshot.provider, snapshot.plan).joinToString(" · "))
                }
                itemsIndexed(snapshot.windows) { index, window ->
                    val minutes = window.resetMinutesAt(now)
                    val expired = !fresh || minutes == 0L
                    val remaining = window.remainingPercent?.takeUnless { expired }
                    SettingsListItem(first = index == 0, last = index == snapshot.windows.lastIndex) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when (window.label.lowercase()) {
                                        "session" -> stringResource(R.string.settings_limits_session)
                                        "weekly" -> stringResource(R.string.settings_limits_weekly)
                                        else -> window.label
                                    },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                remaining?.let {
                                    val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
                                    Text(
                                        stringResource(R.string.settings_limits_remaining, number.format(it)),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            remaining?.let {
                                LinearProgressIndicator(
                                    progress = { (it / 100).toFloat() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (remaining == null && !expired) {
                                Text(
                                    stringResource(R.string.settings_limits_percent_unknown),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                when {
                                    expired -> {
                                        stringResource(R.string.settings_limits_waiting)
                                    }

                                    minutes == null -> {
                                        stringResource(R.string.settings_limits_reset_unknown)
                                    }

                                    minutes < 60 -> {
                                        stringResource(R.string.settings_limits_reset_minutes, minutes)
                                    }

                                    else -> {
                                        stringResource(
                                            R.string.settings_limits_reset_hours,
                                            minutes / 60,
                                            minutes % 60,
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                parseLimitInstant(snapshot.fetched_at)?.let { checked ->
                    item("checked") {
                        SettingsFootnote(
                            stringResource(
                                R.string.settings_limits_checked,
                                DateTimeFormatter
                                    .ofPattern(
                                        "M/d HH:mm",
                                    ).withZone(ZoneId.systemDefault())
                                    .format(checked),
                            ),
                        )
                    }
                }
            }
        }
    }
}
