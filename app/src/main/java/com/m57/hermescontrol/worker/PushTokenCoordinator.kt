package com.m57.hermescontrol.worker

import android.app.Application
import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

internal interface PushClient {
    fun isConfigured(context: Context): Boolean

    suspend fun fetchToken(context: Context): String?
}

internal object FirebasePushClient : PushClient {
    override fun isConfigured(context: Context): Boolean =
        runCatching {
            FirebaseApp.getApps(context.applicationContext).isNotEmpty()
        }.getOrDefault(false)

    override suspend fun fetchToken(context: Context): String? {
        if (!isConfigured(context)) return null
        return suspendCancellableCoroutine { cont ->
            val task =
                runCatching {
                    FirebaseMessaging.getInstance().token
                }.getOrNull()
            if (task == null) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            task.addOnCompleteListener { completed ->
                if (!cont.isActive) return@addOnCompleteListener
                val token =
                    if (completed.isSuccessful) {
                        completed.result?.trim()?.takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                cont.resume(token)
            }
        }
    }
}

/**
 * Pure push-token lifecycle coordinator.
 *
 * Security: never logs FCM tokens, auth tokens, cookies, or device payloads.
 */
internal class PushTokenCoordinator(
    private val isPushConfigured: () -> Boolean,
    private val fetchToken: suspend () -> String?,
    private val readCachedToken: () -> String?,
    private val saveCachedToken: (String?) -> Unit,
    private val isAuthenticated: () -> Boolean,
    private val deviceLabel: () -> String,
    private val registerApi: suspend (PushRegisterRequest) -> Unit,
    private val unregisterApi: suspend (PushUnregisterRequest) -> Unit,
) {
    suspend fun registerCurrentToken(): Boolean {
        if (!isPushConfigured()) return false
        if (!isAuthenticated()) return false
        val token =
            fetchToken()?.trim()?.takeIf { it.isNotEmpty() }
                ?: readCachedToken()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return false
        saveCachedToken(token)
        return runCatching {
            registerApi(
                PushRegisterRequest(
                    token = token,
                    device_label = deviceLabel(),
                ),
            )
            true
        }.getOrDefault(false)
    }

    suspend fun onNewToken(token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return false
        saveCachedToken(trimmed)
        if (!isPushConfigured() || !isAuthenticated()) return false
        return runCatching {
            registerApi(
                PushRegisterRequest(
                    token = trimmed,
                    device_label = deviceLabel(),
                ),
            )
            true
        }.getOrDefault(false)
    }

    suspend fun unregisterBeforeLogout(): Boolean {
        if (!isAuthenticated()) return false
        val token =
            readCachedToken()?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (isPushConfigured()) {
                    fetchToken()?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
        if (token.isNullOrBlank()) return false
        return runCatching {
            unregisterApi(PushUnregisterRequest(token = token))
            saveCachedToken(null)
            true
        }.getOrDefault(false)
    }
}

interface WorkerPushLifecycle {
    fun isConfigured(): Boolean

    fun registerAsync()

    suspend fun onNewToken(token: String)

    suspend fun unregisterBeforeLogout()
}

object PushTokenLifecycle : WorkerPushLifecycle {
    private val appRef = AtomicReference<Application?>(null)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun attachApplication(application: Application) {
        appRef.set(application)
    }

    private fun buildCoordinator(context: Context): PushTokenCoordinator {
        val appContext = context.applicationContext
        return PushTokenCoordinator(
            isPushConfigured = { FirebasePushClient.isConfigured(appContext) },
            fetchToken = { FirebasePushClient.fetchToken(appContext) },
            readCachedToken = { WorkerPreferences.readCachedPushToken() },
            saveCachedToken = { WorkerPreferences.saveCachedPushToken(it) },
            isAuthenticated = { WorkerAuthBootstrap.isAuthenticated() },
            deviceLabel = { Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android" },
            registerApi = { req ->
                val resp = ApiClient.hermesApi.registerPushToken(req)
                check(resp.isSuccessful) { "Push register HTTP ${resp.code()}" }
            },
            unregisterApi = { req ->
                val resp = ApiClient.hermesApi.unregisterPushToken(req)
                check(resp.isSuccessful) { "Push unregister HTTP ${resp.code()}" }
            },
        )
    }

    override fun isConfigured(): Boolean {
        val app = appRef.get() ?: return false
        return FirebasePushClient.isConfigured(app)
    }

    override fun registerAsync() {
        val app = appRef.get() ?: return
        ioScope.launch {
            if (!WorkerAuthBootstrap.ensureReady(app)) return@launch
            buildCoordinator(app).registerCurrentToken()
        }
    }

    override suspend fun onNewToken(token: String) {
        val app = appRef.get() ?: return
        if (!WorkerAuthBootstrap.ensureReady(app)) return
        buildCoordinator(app).onNewToken(token)
    }

    internal suspend fun onNewTokenWithContext(
        context: Context,
        token: String,
    ) {
        if (!WorkerAuthBootstrap.ensureReady(context.applicationContext)) return
        buildCoordinator(context.applicationContext).onNewToken(token)
    }

    override suspend fun unregisterBeforeLogout() {
        val app = appRef.get() ?: return
        if (!WorkerAuthBootstrap.ensureReady(app)) return
        buildCoordinator(app).unregisterBeforeLogout()
    }
}
