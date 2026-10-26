package com.m57.hermescontrol.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabIndicatorScope
import androidx.compose.material3.TabRowDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

// Mono Expressive: selection reads in ink, not blue — blue is reserved for actions and
// progress. Pass these as the `indicator` of Primary/Secondary tab rows, and give Primary
// rows `contentColor = MaterialTheme.colorScheme.onSurface`.

@Composable
fun TabIndicatorScope.HermesPrimaryIndicator(selectedTabIndex: Int) {
    TabRowDefaults.PrimaryIndicator(
        modifier = Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = true),
        width = Dp.Unspecified,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun TabIndicatorScope.HermesSecondaryIndicator(selectedTabIndex: Int) {
    TabRowDefaults.SecondaryIndicator(
        modifier = Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = false),
        color = MaterialTheme.colorScheme.onSurface,
    )
}
