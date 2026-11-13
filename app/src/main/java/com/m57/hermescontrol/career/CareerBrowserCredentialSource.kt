package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Safe, fixed copy only: never include server bodies, exception messages or credential values. */
internal enum class CareerBrowserCredentialFailure(
    val userMessage: String,
) {
    HTTPS_REQUIRED("保存済みID・パスワードを取得できません。HTTPS接続を使うか、接続設定でこのTailscale接続へのHTTP取得を許可してください。"),
    TRUSTED_TRANSPORT_REJECTED("Hermesサーバーが許可済みのTailscale接続を確認できません。接続先とサーバーのTailscale設定を確認してください。"),
    SERVER_HTTPS_REQUIRED("HermesサーバーがHTTPS接続として確認できないため、自動入力を拒否しました。サーバーのHTTPS・プロキシ設定を確認してください。"),
    TLS_FAILED("Hermesの安全な接続を確認できません。証明書と接続設定を確認してください。"),
    SESSION_REQUIRED("Hermesへのログイン状態を確認し、マイページを開き直してください。"),
    SCOPE_CHANGED("接続先またはプロフィールが変わりました。マイページを開き直してください。"),
    DISABLED("サーバー側の自動入力が無効です。管理者に有効化の状況を確認してください。"),
    UNSUPPORTED_ENDPOINT("接続先が自動入力APIに対応していません。サーバーの対応版と接続先を確認してください。"),
    TARGET_CHANGED("登録済みの接続情報が変わりました。マイページを開き直してください。"),
    SAVED_CONNECTION_REQUIRED("保存済みのログイン情報を確認してから、マイページを開き直してください。"),
    APPROVAL_REQUIRED("自動入力の許可を確認できません。マイページを開き直して確認してください。"),
    SERVER_UNAVAILABLE("自動入力のサーバー処理を利用できません。少し待ってから開き直してください。"),
    LOCATOR_UNAVAILABLE("ログイン欄の自動特定に使う軽量モデルを利用できません。サーバーのモデル接続を確認してください。"),
    LOCATOR_UNCERTAIN("軽量モデルで入力先を確定できませんでした。ページを確認して鍵ボタンで再試行できます。"),
    REQUEST_REJECTED("自動入力の要求が拒否されました。サーバーの対応版と認証設定を確認してください。"),
    INVALID_RESPONSE("自動入力の応答を確認できません。サーバーの対応版を確認してください。"),
    NETWORK_FAILED("Hermesに接続できません。通信状態を確認してから開き直してください。"),
}

/** Opt-in server endpoint. No redirects, logging, disk cache, retries or portal auth headers. */
internal class CareerBrowserCredentialSource(
    private val scope: String = careerScope(),
    trustedTailnetEndpoint: String? = null,
    private val clientFactory: (() -> OkHttpClient)? = null,
) {
    @Volatile
    var lastFailure: CareerBrowserCredentialFailure? = null
        private set

    private fun <T> fail(reason: CareerBrowserCredentialFailure): T? {
        lastFailure = reason
        return null
    }

    private fun ready(version: Int = 1): Boolean {
        lastFailure = null
        val failure =
            when {
                base?.isHttps != true && !tailnetTrust.enabled -> CareerBrowserCredentialFailure.HTTPS_REQUIRED
                tailnetTrust.enabled && !tailnetTrust.hasSession -> CareerBrowserCredentialFailure.SESSION_REQUIRED
                !validScope() -> CareerBrowserCredentialFailure.SCOPE_CHANGED
                version <= 0 -> CareerBrowserCredentialFailure.TARGET_CHANGED
                else -> null
            }
        lastFailure = failure
        return failure == null
    }

    private val base = AuthManager.getBaseUrl().toHttpUrlOrNull()
    private val profile = AuthManager.activeProfileId.value ?: "default"
    private val transportConsent =
        if (base?.isHttps == false && trustedTailnetEndpoint == null) {
            runCatching { AuthManager.credentialTransportState() }.getOrNull()
        } else {
            null
        }
    private val trustedEndpoint = trustedTailnetEndpoint ?: transportConsent?.allowedEndpoint.orEmpty()
    private val tailnetTrust =
        CareerBrowserTailnetTrust(
            base,
            trustedEndpoint,
            scope,
            if (base?.isHttps == false && trustedEndpoint.isNotEmpty()) AuthManager.getSessionCookie() else null,
        )

    val transportWarning: String?
        get() =
            if (tailnetTrust.enabled) {
                "保存済みID・パスワードは、Tailscale内のHermesサーバーからHTTPで受け取ります(通信の保護はTailscaleに依存)。表示中のページとの通信とは別です。"
            } else {
                null
            }

    private fun newClient(locator: Boolean = false): OkHttpClient {
        val builder =
            OkHttpClient
                .Builder()
                .cookieJar(
                    if (tailnetTrust.enabled) {
                        CareerBrowserTailnetCookieJar(CookieManager.cookieJar, tailnetTrust, ::validScope)
                    } else {
                        CookieManager.cookieJar
                    },
                ).followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(if (locator) 35 else 10, TimeUnit.SECONDS)
                .readTimeout(if (locator) 35 else 10, TimeUnit.SECONDS)
        if (tailnetTrust.enabled) {
            // An OS/system HTTP proxy is not part of the approved direct Tailnet route.
            builder.proxy(Proxy.NO_PROXY)
            builder.addNetworkInterceptor { chain ->
                if (!validScope() || !tailnetTrust.allowsRequest(chain.request().url)) {
                    throw IOException("Credential connection scope changed")
                }
                chain.proceed(chain.request())
            }
        }
        return builder.build()
    }

    /** The registered-page autofill action is pre-authorized; server grants remain exact and single-use. */
    suspend fun fillCurrentDocument(
        companyId: String,
        version: Int,
        policy: CareerBrowserPolicy,
        documentCurrent: () -> Boolean,
        action: (CareerBrowserCredentials) -> Boolean,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (!documentCurrent()) return false
        val approval = prepare(companyId, version, policy)
        if (!documentCurrent()) return false
        if (approval == null) return false
        if (!approval.isCurrent()) {
            lastFailure = CareerBrowserCredentialFailure.APPROVAL_REQUIRED
            return false
        }
        currentCoroutineContext().ensureActive()
        if (!documentCurrent()) return false
        // requiresConfirmation selects the existing wire grant mode, not another UI decision.
        val queued =
            use(companyId, version, policy, approval) { credentials ->
                documentCurrent() && action(credentials)
            }
        if (!documentCurrent()) return false
        return queued
    }

    /** Resolve structural input indexes before retrieving any credential. */
    suspend fun locateLogin(
        companyId: String,
        version: Int,
        policy: CareerBrowserPolicy,
        snapshot: CareerBrowserLoginSnapshot,
        documentCurrent: () -> Boolean,
    ): CareerBrowserLoginSelection? {
        currentCoroutineContext().ensureActive()
        if (!ready(version) || !documentCurrent()) return null
        val session = AuthManager.getSessionCookie()?.takeIf { it.isNotBlank() } ?: return null
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val body =
            JsonObject(
                payload(companyId, version, policy) +
                    mapOf("request_id" to JsonPrimitive(requestId), "fields" to snapshot.fields),
            )
        var locatorEndpointMissing = false
        val selected =
            request("browser-login-locate", body) { response ->
                locatorEndpointMissing = response.code in setOf(404, 405, 501)
                val value = readJson(response) ?: return@request null
                if (!matches(value, companyId, version, policy) || value.string("request_id") != requestId ||
                    value.keys !=
                    setOf(
                        "company_id",
                        "profile",
                        "connection_version",
                        "url",
                        "request_id",
                        "login_index",
                        "password_index",
                    )
                ) {
                    return@request null
                }
                snapshot.selection(value)
            }
        currentCoroutineContext().ensureActive()
        if (!documentCurrent() || !validScope() || session != AuthManager.getSessionCookie()) return null
        if (selected != null) return selected
        // Older servers expose credential grants but not the optional model locator. A unique,
        // empty local pair can still pass the unchanged document/form guards before any fill.
        if (locatorEndpointMissing && lastFailure == CareerBrowserCredentialFailure.UNSUPPORTED_ENDPOINT) {
            snapshot.legacySelection()?.let {
                lastFailure = null
                return it
            }
        }
        return null
    }

    /** Read the registered username only; never request the password-bearing autofill endpoint. */
    suspend fun savedLoginId(
        companyId: String,
        version: Int,
        policy: CareerBrowserPolicy,
    ): String? {
        currentCoroutineContext().ensureActive()
        if (!ready(version)) return null
        val session =
            AuthManager.getSessionCookie()?.takeIf { it.isNotBlank() }
                ?: return fail(CareerBrowserCredentialFailure.SESSION_REQUIRED)
        val login =
            request("login-id", payload(companyId, version, policy)) { response ->
                if (session != AuthManager.getSessionCookie()) {
                    return@request fail<String>(CareerBrowserCredentialFailure.SCOPE_CHANGED)
                }
                val value = readJson(response) ?: return@request null
                if (value.keys != setOf("company_id", "profile", "connection_version", "url", "login_id")) {
                    return@request fail<String>(CareerBrowserCredentialFailure.INVALID_RESPONSE)
                }
                if (!matches(value, companyId, version, policy)) return@request null
                value.string("login_id")?.takeIf { it.isNotBlank() && it.length <= 500 }
            }
        currentCoroutineContext().ensureActive()
        if (!validScope() || session != AuthManager.getSessionCookie()) {
            return fail(CareerBrowserCredentialFailure.SCOPE_CHANGED)
        }
        return login
    }

    suspend fun prepare(
        companyId: String,
        version: Int,
        policy: CareerBrowserPolicy,
    ): CareerBrowserApproval? {
        if (!ready(version)) return null
        val started = System.nanoTime()
        val result =
            request("browser-access/prepare", payload(companyId, version, policy)) { response ->
                val value = readJson(response) ?: return@request null
                if (!matches(value, companyId, version, policy)) return@request null
                val id = value.string("approval_id") ?: return@request null
                val seconds =
                    (value["expires_in"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return@request null
                if (!id.matches(Regex("[a-f0-9]{64}")) || seconds !in 1..60) return@request null
                val requiresConfirmation =
                    (value["requires_confirmation"] as? JsonPrimitive)
                        ?.takeIf { !it.isString }
                        ?.booleanOrNull ?: return@request null
                CareerBrowserApproval(
                    id,
                    companyId,
                    profile,
                    version,
                    policy.loginUrl.toString(),
                    scope,
                    started + seconds * 1_000_000_000L,
                    requiresConfirmation,
                )
            }
        if (!validScope()) return fail(CareerBrowserCredentialFailure.SCOPE_CHANGED)
        if (result != null && !result.isCurrent()) return fail(CareerBrowserCredentialFailure.APPROVAL_REQUIRED)
        return result
    }

    private fun payload(
        companyId: String,
        version: Int,
        policy: CareerBrowserPolicy,
        approvalId: String? = null,
        requiresConfirmation: Boolean = true,
    ): JsonObject =
        buildJsonObject {
            put("company_id", companyId)
            put("profile", profile)
            put("expected_version", version)
            put("url", policy.loginUrl.toString())
            if (approvalId != null) {
                put("approval_id", approvalId)
                put("consent_mode", if (requiresConfirmation) "allow_target" else "use_target")
            }
        }

    suspend fun use(
        companyId: String,
        version: Int,
        policy: CareerBrowserPolicy,
        approval: CareerBrowserApproval,
        action: (CareerBrowserCredentials) -> Boolean,
    ): Boolean {
        if (!ready(version)) return false
        val approvalId =
            approval.consume(companyId, profile, version, policy.loginUrl.toString(), scope) ?: run {
                lastFailure = CareerBrowserCredentialFailure.APPROVAL_REQUIRED
                return false
            }
        val credentials =
            request(
                "browser-credentials",
                payload(companyId, version, policy, approvalId, approval.requiresConfirmation),
            ) { response ->
                decode(response, companyId, version, policy)
            } ?: return false
        credentials.use {
            return withContext(Dispatchers.Main) {
                if (validScope()) {
                    action(it)
                } else {
                    lastFailure = CareerBrowserCredentialFailure.SCOPE_CHANGED
                    false
                }
            }
        }
    }

    suspend fun revoke(companyId: String): Boolean {
        if (!ready()) return false
        val body =
            buildJsonObject {
                put("company_id", companyId)
                put("profile", profile)
            }
        return request("browser-access/revoke", body) { response ->
            val value = readJson(response) ?: return@request null
            val revoked = (value["revoked"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            val disabled = (value["disabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            if (revoked != null && revoked >= 0 && disabled != null) true else null
        } == true
    }

    private suspend fun <T> request(
        path: String,
        body: JsonObject,
        decode: (Response) -> T?,
    ): T? {
        val url = base?.newBuilder()?.addPathSegments("api/plugins/career/mypage/$path")?.build() ?: return null
        if (!validScope()) return fail(CareerBrowserCredentialFailure.SCOPE_CHANGED)
        if (tailnetTrust.enabled && !tailnetTrust.allowsRequest(url)) {
            return fail(CareerBrowserCredentialFailure.TRUSTED_TRANSPORT_REJECTED)
        }
        val client = clientFactory?.invoke() ?: newClient(path == "browser-login-locate")
        if (client.followRedirects || client.followSslRedirects) {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            return fail(CareerBrowserCredentialFailure.REQUEST_REJECTED)
        }
        val call =
            client.newCall(
                Request
                    .Builder()
                    .url(url)
                    .header("Cache-Control", "no-store")
                    .header("X-Hermes-Browser-Consent", "once")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build(),
            )
        try {
            return suspendCancellableCoroutine<T?> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            if (!continuation.isActive) return
                            lastFailure =
                                if (e is SSLException) {
                                    CareerBrowserCredentialFailure.TLS_FAILED
                                } else {
                                    CareerBrowserCredentialFailure.NETWORK_FAILED
                                }
                            continuation.resume(null) { _, _, _ -> }
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            if (!continuation.isActive) {
                                response.close()
                                return
                            }
                            val result =
                                try {
                                    response.use {
                                        if (it.request.url != url || it.priorResponse != null) {
                                            fail<T>(CareerBrowserCredentialFailure.REQUEST_REJECTED)
                                        } else {
                                            decode(it)
                                        }
                                    }
                                } catch (_: IOException) {
                                    fail<T>(CareerBrowserCredentialFailure.NETWORK_FAILED)
                                } catch (_: Exception) {
                                    fail<T>(CareerBrowserCredentialFailure.INVALID_RESPONSE)
                                }
                            if (result == null && lastFailure == null) {
                                lastFailure = CareerBrowserCredentialFailure.INVALID_RESPONSE
                            }
                            continuation.resume(result) { _, abandoned, _ ->
                                (abandoned as? CareerBrowserCredentials)?.close()
                            }
                        }
                    },
                )
            }
        } finally {
            call.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun decode(
        response: Response,
        companyId: String,
        version: Int,
        policy: CareerBrowserPolicy,
    ): CareerBrowserCredentials? {
        val value = readJson(response) ?: return null
        if (!matches(value, companyId, version, policy)) return null
        val id = value.string("login_id") ?: return null
        val pass = value.string("password") ?: return null
        if (id.length !in 1..500 || pass.length !in 1..2000) return null
        return CareerBrowserCredentials(id.toCharArray(), pass.toCharArray())
    }

    private fun matches(
        value: JsonObject,
        companyId: String,
        version: Int,
        policy: CareerBrowserPolicy,
    ): Boolean {
        if (!validScope()) {
            lastFailure = CareerBrowserCredentialFailure.SCOPE_CHANGED
            return false
        }
        val returnedCompany = value.string("company_id")
        val returnedProfile = value.string("profile")
        val returnedVersion =
            (value["connection_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        val returnedUrl = value.string("url")
        if (returnedCompany == null || returnedProfile == null || returnedVersion == null || returnedUrl == null) {
            lastFailure = CareerBrowserCredentialFailure.INVALID_RESPONSE
            return false
        }
        val matches =
            returnedCompany == companyId && returnedProfile == profile &&
                returnedVersion == version && returnedUrl == policy.loginUrl.toString()
        if (!matches) lastFailure = CareerBrowserCredentialFailure.TARGET_CHANGED
        return matches
    }

    private fun readJson(response: Response): JsonObject? {
        if (!validScope()) return fail(CareerBrowserCredentialFailure.SCOPE_CHANGED)
        if (!response.isSuccessful) {
            val value =
                try {
                    readBody(response)
                } catch (_: Exception) {
                    null
                }
            val code = (value?.get("detail") as? JsonObject)?.string("code")
            return fail(httpFailure(response.code, code))
        }
        return readBody(response)
    }

    private fun readBody(response: Response): JsonObject? {
        val input = response.body ?: return null
        val buffered = input.source()
        buffered.request(16_385)
        val bytes = buffered.readByteArray(minOf(buffered.buffer.size, 16_385))
        try {
            if (bytes.size > 16_384) return null
            return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } finally {
            bytes.fill(0)
        }
    }

    private fun httpFailure(
        status: Int,
        code: String?,
    ): CareerBrowserCredentialFailure =
        when (code) {
            "login_locator_model_unavailable", "login_locator_unavailable", "oauth_unavailable" -> {
                CareerBrowserCredentialFailure.LOCATOR_UNAVAILABLE
            }

            "login_locator_abstained", "login_locator_invalid" -> {
                CareerBrowserCredentialFailure.LOCATOR_UNCERTAIN
            }

            "browser_credentials_disabled" -> {
                CareerBrowserCredentialFailure.DISABLED
            }

            "browser_tailnet_unverified" -> {
                CareerBrowserCredentialFailure.TRUSTED_TRANSPORT_REJECTED
            }

            "browser_tls_required" -> {
                if (tailnetTrust.enabled) {
                    CareerBrowserCredentialFailure.TRUSTED_TRANSPORT_REJECTED
                } else {
                    CareerBrowserCredentialFailure.SERVER_HTTPS_REQUIRED
                }
            }

            "dashboard_session_required" -> {
                CareerBrowserCredentialFailure.SESSION_REQUIRED
            }

            "version_conflict" -> {
                CareerBrowserCredentialFailure.TARGET_CHANGED
            }

            "mypage_not_connected", "authentication_required", "company_missing", "company_closed" -> {
                CareerBrowserCredentialFailure.SAVED_CONNECTION_REQUIRED
            }

            "browser_authorization_required" -> {
                CareerBrowserCredentialFailure.APPROVAL_REQUIRED
            }

            "browser_worker_unavailable", "browser_consent_unavailable" -> {
                CareerBrowserCredentialFailure.SERVER_UNAVAILABLE
            }

            else -> {
                when (status) {
                    401 -> CareerBrowserCredentialFailure.SESSION_REQUIRED
                    404, 405, 501 -> CareerBrowserCredentialFailure.UNSUPPORTED_ENDPOINT
                    in 500..599 -> CareerBrowserCredentialFailure.SERVER_UNAVAILABLE
                    else -> CareerBrowserCredentialFailure.REQUEST_REJECTED
                }
            }
        }

    private fun validScope(): Boolean {
        val currentBase = AuthManager.getBaseUrl().toHttpUrlOrNull()
        val currentScope = careerScope()
        val gated = AuthManager.isGatedMode()
        // Evaluate the trust latch even when the ordinary scope guard also rejects the request.
        val trustedSessionCurrent =
            !tailnetTrust.enabled ||
                (
                    tailnetTrust.isCurrent(currentBase, currentScope, gated, AuthManager.getSessionCookie()) &&
                        (transportConsent == null || AuthManager.isCredentialTransportCurrent(transportConsent))
                )
        return scope == currentScope && gated &&
            profile == (AuthManager.activeProfileId.value ?: "default") && base == currentBase && trustedSessionCurrent
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
