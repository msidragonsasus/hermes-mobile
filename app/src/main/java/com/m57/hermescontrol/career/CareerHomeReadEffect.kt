package com.m57.hermescontrol.career

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

/** Record only rendered content, also when the user opened it directly inside the App. */
@Composable
internal fun CareerHomeReadEffect(
    id: String,
    content: String,
) {
    val app = LocalContext.current.applicationContext as HermesControlApp
    val lifecycle = LocalLifecycleOwner.current
    val scope = AuthManager.uiPreferenceScope()
    LaunchedEffect(scope, id, content, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            app.homeRepository.markContentRead(scope, id, content)
        }
    }
}

@Composable
internal fun CareerConversationHomeReadEffect(
    snapshot: CareerCoachSnapshot?,
    listState: LazyListState,
) {
    val visible by remember(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .map { it.key.toString() }
                .toSet()
        }
    }.collectAsState(emptySet())
    snapshot?.messages?.filter { it.role == "assistant" && it.id in visible }?.forEach {
        CareerHomeReadEffect("reply:${it.run_id}", it.text)
    }
}
