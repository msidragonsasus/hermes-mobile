package com.m57.hermescontrol.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.MessageRole

@Composable
fun HomeChatReadEffect(
    state: ChatUiState,
    listState: LazyListState,
) {
    val app = LocalContext.current.applicationContext as HermesControlApp
    val lifecycle = LocalLifecycleOwner.current
    val scope = AuthManager.uiPreferenceScope()
    val session = state.currentSessionId
    val visible by remember(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .map { it.key.toString() }
                .toSet()
        }
    }.collectAsState(emptySet())
    val versions =
        remember(state.messages, visible) {
            state.messages
                .filter {
                    it.role == MessageRole.ASSISTANT && !it.isStreaming &&
                        (it.id in visible || "prose-${it.id}" in visible)
                }.map { homeVersion(it.content) }
                .toSet()
        }
    LaunchedEffect(scope, session, versions, state.isLoading, state.resumeError, lifecycle) {
        if (session == null || state.isLoading || state.resumeError != null) return@LaunchedEffect
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            app.homeRepository.dao.observe(scope).collect { rows ->
                rows.firstOrNull { it.provider == "chat" && it.id == session }?.let { row ->
                    val item = app.homeRepository.json.decodeFromString<HomeItem>(row.payload)
                    if (item.version in versions) app.homeRepository.markRead(scope, item)
                }
            }
        }
    }
}
