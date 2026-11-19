package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.credentialTransportEndpoint
import com.m57.hermescontrol.data.remote.SESSION_COOKIE_NAME
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.security.MessageDigest

internal fun HttpUrl.credentialRequestUrl(path: String): HttpUrl =
    newBuilder()
        .addPathSegments("api/plugins/career/mypage/$path")
        .addQueryParameter("profile", AppScope.PROFILE)
        .build()

/** Explicit operator declaration, never an IP-range or VPN-presence inference. In-memory only. */
internal class CareerBrowserTailnetTrust(
    base: HttpUrl?,
    declaration: String,
    private val scope: String,
    sessionCookie: String?,
) {
    private val endpoint = credentialTransportEndpoint(declaration)
    private val requestUrls =
        listOf(
            "browser-access/prepare",
            "browser-credentials",
            "browser-access/revoke",
            "login-id",
            "browser-login-locate",
        ).mapNotNull { endpoint?.credentialRequestUrl(it) }.toSet()
    val enabled: Boolean = endpoint != null && base == endpoint
    private val fingerprint = if (enabled) fingerprint(sessionCookie) else null
    val hasSession: Boolean get() = fingerprint != null

    @Volatile
    private var invalidated = false

    fun isCurrent(
        base: HttpUrl?,
        scope: String,
        gated: Boolean,
        sessionCookie: String?,
    ): Boolean {
        if (!enabled || invalidated) return false
        val current = base == endpoint && this.scope == scope && gated && matchesSession(sessionCookie)
        if (!current) invalidated = true
        return current
    }

    fun matchesSession(sessionCookie: String?): Boolean {
        val expected = fingerprint ?: return false
        val actual = fingerprint(sessionCookie) ?: return false
        return try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            actual.fill(0)
        }
    }

    fun allowsRequest(url: HttpUrl): Boolean = enabled && !invalidated && url in requestUrls

    companion object {
        private fun fingerprint(value: String?): ByteArray? {
            if (value.isNullOrBlank()) return null
            val bytes = value.toByteArray(Charsets.UTF_8)
            return try {
                MessageDigest.getInstance("SHA-256").digest(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }
}

/** No cookie migration, refresh, persistence or cross-origin forwarding on credential endpoints. */
internal class CareerBrowserTailnetCookieJar(
    private val delegate: CookieJar,
    private val trust: CareerBrowserTailnetTrust,
    private val isCurrent: () -> Boolean,
) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!trust.allowsRequest(url) || !isCurrent()) return emptyList()
        val cookies =
            delegate.loadForRequest(url).filter {
                it.name == SESSION_COOKIE_NAME && !it.secure && it.matches(url) && trust.matchesSession(it.value)
            }
        return if (isCurrent()) cookies else emptyList()
    }

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) = Unit
}
