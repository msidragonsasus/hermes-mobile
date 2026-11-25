package com.m57.hermescontrol.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Constraints
import com.m57.hermescontrol.ui.common.verticalScrollThumb

@Composable
internal fun DismissImeOnDrawerOpen(drawerState: DrawerState) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerState, drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
}

@Composable
internal fun MainDrawerLayout(
    header: @Composable ColumnScope.() -> Unit,
    botRows: @Composable () -> Unit,
    createBot: @Composable () -> Unit,
    settings: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val bodyHeight = constraints.maxHeight
            Layout(
                contents =
                    listOf(
                        { Column(content = header) },
                        {
                            val scroll = rememberScrollState()
                            Layout(
                                botRows,
                                Modifier.verticalScrollThumb(scroll).verticalScroll(scroll),
                                BotRowsMeasurePolicy,
                            )
                        },
                        createBot,
                    ),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) { (headers, botLists, actions), constraints ->
                val width = constraints.maxWidth
                val slotConstraints = Constraints.fixedWidth(width)
                val headerPlaceable = headers.single().measure(slotConstraints)
                val createBotPlaceable = actions.single().measure(slotConstraints)
                val botList = botLists.single()
                val availableForBots = bodyHeight - headerPlaceable.height - createBotPlaceable.height
                // Keep one row usable when the upper section has to scroll on a very short screen.
                val botViewportHeight =
                    availableForBots
                        .coerceIn(botList.minIntrinsicHeight(width), botList.maxIntrinsicHeight(width))
                val botsPlaceable = botList.measure(slotConstraints.copy(maxHeight = botViewportHeight))
                layout(width, headerPlaceable.height + botsPlaceable.height + createBotPlaceable.height) {
                    headerPlaceable.placeRelative(0, 0)
                    botsPlaceable.placeRelative(0, headerPlaceable.height)
                    createBotPlaceable.placeRelative(0, headerPlaceable.height + botsPlaceable.height)
                }
            }
        }
        Column(content = settings)
    }
}

private const val DRAWER_VISIBLE_BOT_ROWS = 4

private object BotRowsMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ) = with(constraints) {
        val rows = measurables.map { it.measure(Constraints.fixedWidth(maxWidth)) }
        layout(maxWidth, rows.sumOf { it.height }) {
            var top = 0
            rows.forEach { row ->
                row.placeRelative(0, top)
                top += row.height
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables.firstOrNull()?.maxIntrinsicHeight(width) ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables.take(DRAWER_VISIBLE_BOT_ROWS).sumOf { it.maxIntrinsicHeight(width) }
}
