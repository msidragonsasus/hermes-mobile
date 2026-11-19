package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.home.HomeDetailKey
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Resolve exact saved content, reusing the App's source reader. List limits never imply missing content. */
@Composable
fun CareerHomeDetailScreen(key: HomeDetailKey) {
    val app = LocalContext.current.applicationContext as HermesControlApp
    val lifecycle = LocalLifecycleOwner.current
    var content by remember(key) { mutableStateOf<JsonObject?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var retry by rememberSaveable(key) { mutableIntStateOf(0) }
    val target = key.item.target
    val context by AuthManager.contextFlow.collectAsState()
    val currentScope = remember(context) { AppScope.connectionKey() }
    if (currentScope != key.scope) {
        HermesScaffold(
            title = { Text("接続先が変わりました") },
            navigationIcon = NavIcon.Back { NavigationController.goBack() },
        ) {
            Text("ホームを開き直してください。")
        }
        return
    }
    if (target.kind == "settings") {
        CareerSettingsScreen()
        return
    }
    if (target.kind == "company" && target.entity_id != null) {
        if (target.company_id !=
            null
        ) {
            CareerScreen(target.company_id, CareerCompanyTab.SELECTION.ordinal, initialEntityId = target.entity_id)
        } else {
            CareerEntityScreen(target.entity_id)
        }
        return
    }
    LaunchedEffect(key, retry) {
        content = null
        error = null
        try {
            val value =
                ApiClient.hermesApi.careerHomeContent(
                    AppScope.PROFILE,
                    key.item.id,
                    key.item.version,
                )
            check(key.scope == AppScope.connectionKey())
            check(!value.text("body").isNullOrBlank())
            content = value
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = "この内容を確認できません。ホームを更新するか、元のAppで確認してください。"
        }
    }
    // Only mark the exact version after the reader has entered composition with its body.
    LaunchedEffect(content) {
        if (content != null) {
            try {
                lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    app.homeRepository.markRead(key.scope, key.item)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "既読を保存できませんでした。内容は表示できます。"
            }
        }
    }

    fun openOrigin() {
        when {
            target.kind == "document" -> {
                NavigationController.navigateTo(
                    CareerConversationKey(
                        target.thread_id ?: "global",
                        target = CareerTarget("documents", target.entity_id!!, target.document_version),
                    ),
                )
            }

            target.thread_id != null -> {
                NavigationController.navigateTo(CareerConversationKey(target.thread_id))
            }

            target.company_id != null -> {
                NavigationController.navigateTo(CareerCompanyKey(target.company_id))
            }

            else -> {
                NavigationController.navigateTo(CareerScreenKey)
            }
        }
    }
    content?.let { source ->
        key.item.id.takeIf { it.startsWith("source:") }?.let { identity ->
            CareerHomeReadEffect(
                identity,
                (source.text("title") ?: key.item.title) + "\n" + source.text("body").orEmpty(),
            )
        }
    }
    CareerSourceReaderScreen(
        title = content?.text("title") ?: key.item.title,
        body = content?.text("body"),
        error = error,
        onBack = { NavigationController.goBack() },
        onOpenOrigin = ::openOrigin,
        onConsult = {
            val thread = target.thread_id ?: target.company_id?.let { "company:$it" } ?: "global"
            NavigationController.navigateTo(CareerConversationKey(thread))
        },
        onRetry = { retry++ },
        url = content?.text("locator").orEmpty(),
        sourceFooter = {
            content?.let {
                key.item.id.takeIf { it.startsWith("source:") }?.let { identity ->
                    CareerSourceResolution(identity)
                }
            }
        },
    )
}
