package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon

/** One screen owns loading, failure and readable content. The action dock never waits for the body. */
@Composable
internal fun CareerSourceReaderScreen(
    title: String,
    body: String?,
    error: String?,
    onBack: () -> Unit,
    onOpenOrigin: () -> Unit,
    onConsult: () -> Unit,
    onRetry: () -> Unit,
    url: String = "",
    sourceFooter: @Composable () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val uri = LocalUriHandler.current
    val list = rememberLazyListState()
    var expandedTitle by rememberSaveable(title) { mutableStateOf(false) }
    HermesScaffold(
        title = { Text(stringResource(R.string.career_reader_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        Column(Modifier.fillMaxSize().testTag("career_source_reader")) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("career_reader_scroll"),
                state = list,
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                item(key = "subject") {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = if (expandedTitle) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("career_reader_subject"),
                        )
                        if (title.isNotBlank()) {
                            TextButton(
                                onClick = { expandedTitle = !expandedTitle },
                                modifier = Modifier.testTag("career_reader_expand_subject"),
                            ) {
                                Text(
                                    stringResource(
                                        if (expandedTitle) {
                                            R.string.career_reader_collapse_subject
                                        } else {
                                            R.string.career_reader_expand_subject
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
                item(key = "body") {
                    when {
                        body == null && error == null -> {
                            LoadingState(
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                subtitle = stringResource(R.string.career_reader_loading),
                            )
                        }

                        body == null -> {
                            ErrorState(requireNotNull(error), onRetry)
                        }

                        body.isBlank() -> {
                            EmptyState(stringResource(R.string.career_reader_empty))
                        }

                        else -> {
                            SelectionContainer(Modifier.fillMaxWidth().testTag("career_reader_body")) {
                                Text(linkedMailBody(body), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                if (body != null) {
                    if (error !=
                        null
                    ) {
                        item(key = "read-warning") { Text(error, style = MaterialTheme.typography.bodySmall) }
                    }
                    item(key = "source-actions") {
                        if (url.startsWith("https://")) {
                            TextButton(
                                onClick = { uri.openUri(url) },
                                modifier = Modifier.testTag("career_reader_source_link"),
                            ) {
                                Text(stringResource(R.string.career_reader_open_link))
                            }
                        }
                        sourceFooter()
                    }
                }
                item(key = "end") { Spacer(Modifier.height(1.dp).testTag("career_reader_end")) }
            }
            HorizontalDivider()
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(onClick = onOpenOrigin, modifier = Modifier.testTag("career_reader_origin")) {
                    Text(stringResource(R.string.career_reader_origin))
                }
                Button(onClick = onConsult, modifier = Modifier.testTag("career_reader_consult")) {
                    Text(stringResource(R.string.career_reader_consult))
                }
            }
        }
    }
}
