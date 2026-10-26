package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalHermesStatusColors

/**
 * Typed table. Rows are always all visible; tapping one emphasizes it (selection is kept by
 * first-column value across revisions). Numeric columns sort from their header. Narrow widths
 * or 4+ columns switch to one block per row.
 */
@Composable
internal fun NativeTable(spec: TableSpec) {
    val host = LocalNativeUiHost.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var sortColumn by rememberSaveable { mutableIntStateOf(-1) }
    var descending by rememberSaveable { mutableStateOf(false) }
    val columns = spec.columns
    val numeric = columns.indices.map { c -> spec.rows.all { it[c].num != null } }
    val sorting = sortColumn in columns.indices && numeric[sortColumn]
    val rows =
        if (!sorting) {
            spec.rows
        } else if (descending) {
            spec.rows.sortedByDescending { it[sortColumn].num }
        } else {
            spec.rows.sortedBy { it[sortColumn].num }
        }
    val current = selected?.takeIf { label -> spec.rows.any { it.first().display == label } }

    fun toggle(row: List<TableCell>) {
        val label = row.first().display
        selected = if (current == label) null else label
    }

    NativeHeader(spec.title)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        if (columns.size >= 4 || maxWidth < 72.dp * columns.size * fontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row -> TableRowBlock(columns, row, row.first().display == current) { toggle(row) } }
            }
        } else {
            Column {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    columns.forEachIndexed { c, column ->
                        val arrow = if (sorting && sortColumn == c) (if (descending) " ▼" else " ▲") else ""
                        Text(
                            column.name + (if (column.highlight) " ★" else "") + arrow,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = if (numeric[c]) TextAlign.End else TextAlign.Start,
                            modifier =
                                Modifier
                                    .weight(if (c == 0) 1.2f else 1f)
                                    .fillMaxHeight()
                                    .highlightColumn(column.highlight)
                                    .then(
                                        if (numeric[c]) {
                                            Modifier.clickable(role = Role.Button, onClickLabel = "並べ替え") {
                                                if (sortColumn != c) {
                                                    sortColumn = c
                                                    descending = false
                                                } else if (!descending) {
                                                    descending = true
                                                } else {
                                                    sortColumn = -1
                                                    descending = false
                                                }
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ).padding(horizontal = 6.dp, vertical = 10.dp),
                        )
                    }
                }
                rows.forEach { row ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TableLine(columns, numeric, row, row.first().display == current) { toggle(row) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (host != null && current != null) {
        val row = spec.rows.first { it.first().display == current }
        NativeDiscussButton(spec.action ?: NativeActionLabels.TABLE, "${spec.id}-discuss") {
            tableActionMessage(spec, row)
        }
    } else if (host != null) {
        NativeCaption(
            "行を選ぶと、その内容で相談できます。" +
                if (numeric.drop(1).any { it } && columns.size < 4) "数値の列は見出しで並べ替えできます。" else "",
        )
    }
}

@Composable
private fun Modifier.highlightColumn(on: Boolean): Modifier =
    if (on) background(MaterialTheme.colorScheme.primary.copy(alpha = .06f)) else this

@Composable
private fun TableLine(
    columns: List<TableColumn>,
    numeric: List<Boolean>,
    row: List<TableCell>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 48.dp)
            .background(if (selected) primary.copy(alpha = .10f) else Color.Transparent)
            .drawBehind { if (selected) drawRect(primary, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .selectable(selected, onClick = onClick),
    ) {
        row.forEachIndexed { c, cell ->
            Box(
                Modifier
                    .weight(if (c == 0) 1.2f else 1f)
                    .fillMaxHeight()
                    .highlightColumn(columns[c].highlight)
                    .padding(horizontal = 6.dp, vertical = 12.dp),
                contentAlignment = if (numeric[c]) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                CellText(cell, bold = c == 0 && selected, end = numeric[c])
            }
        }
    }
}

@Composable
private fun TableRowBlock(
    columns: List<TableColumn>,
    row: List<TableCell>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().selectable(selected, onClick = onClick),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.first().display,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text(
                        "✓ 選択中",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            columns.drop(1).forEachIndexed { i, column ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        column.name + if (column.highlight) " ★" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    CellText(row[i + 1], bold = column.highlight, end = true)
                }
            }
        }
    }
}

@Composable
private fun CellText(
    cell: TableCell,
    bold: Boolean,
    end: Boolean,
) {
    val status = LocalHermesStatusColors.current
    val (prefix, color) =
        when (cell.mark) {
            "good" -> "◎ " to status.success
            "bad" -> "× " to status.error
            else -> "" to MaterialTheme.colorScheme.onSurface
        }
    Text(
        prefix + cell.display,
        style = MaterialTheme.typography.bodyMedium.merge(Tnum),
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
    )
}
