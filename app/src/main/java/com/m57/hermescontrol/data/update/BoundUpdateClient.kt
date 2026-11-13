package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * Immutable credential snapshot for exactly one [UpdateScope], taken while the
 * connection was stable ([UpdateScopeProvider.bind]). Requests made with it
 * never read the live token, profile or cookie jar, so a connection switch
 * between selecting a release and sending the request cannot pair one
 * server's URL with another server's credential. Never persisted or logged.
 */
class UpdateBinding(
    val scope: UpdateScope,
    internal val bearer: String?,
    internal val cookieHeader: String?,
) {
    private val base: HttpUrl? =
        if (scope.source == UpdateSource.CONNECTED_SERVER) {
            runCatching { ServerEndpoint.parseForBuild(scope.baseUrl).baseUrl }.getOrNull()
        } else {
            null
        }

    /** Same scheme/host/port as the bound endpoint and under its updates plugin path. */
    fun allows(url: HttpUrl): Boolean {
        val bound = base ?: return false
        return url.scheme == bound.scheme &&
            url.host == bound.host &&
            url.port == bound.port &&
            url.encodedPath.startsWith(bound.encodedPath + SERVER_UPDATES_PATH + "/")
    }

    override fun toString(): String = "UpdateBinding(scope=$scope, credential=<redacted>)"
}

/** A request outside the bound origin/path; no credential was attached and nothing was sent. */
class BoundRequestRefusedException : IOException("update request outside the bound connection")

private class BoundCredentialInterceptor(
    private val binding: UpdateBinding,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!binding.allows(request.url)) throw BoundRequestRefusedException()
        val builder = request.newBuilder().removeHeader("Authorization").removeHeader("Cookie")
        binding.bearer?.let { builder.header("Authorization", "Bearer $it") }
        binding.cookieHeader?.let { builder.header("Cookie", it) }
        return chain.proceed(builder.build())
    }
}

/**
 * OkHttp may follow a 503 + Retry-After: 0 even with connection retries disabled.
 * Remove only that follow-up trigger before its protocol retry interceptor sees it;
 * keep the original status/body so callers still expose the typed 503 failure.
 */
private class NoUnavailableFollowUpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        return if (response.code == 503 && response.header("Retry-After") == "0") {
            response.newBuilder().removeHeader("Retry-After").build()
        } else {
            response
        }
    }
}

/**
 * Client for connected-server update requests. Unlike the REST client it has
 * no live auth interceptor, no profile rewriting, no shared cookie jar, no
 * token-refresh authenticator, no redirects and no transparent retries; the
 * only credential is the [binding] snapshot, attached only to its own origin.
 */
fun boundUpdateClient(
    binding: UpdateBinding,
    base: OkHttpClient = OkHttpProvider.base,
): OkHttpClient =
    base
        .newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }.cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor(BoundCredentialInterceptor(binding))
        .addNetworkInterceptor(NoUnavailableFollowUpInterceptor())
        .build()
