package com.m57.hermescontrol.career

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Browsing is independent of credential authorization for registered scope or the session's first landing. */
internal class CareerBrowserPolicy private constructor(
    val loginUrl: HttpUrl,
) {
    val origin: String = "https://${loginUrl.host}"

    // E2R posts every tenant to one handler; the hidden siteID must match the registered page.
    val reviewedE2rSiteId: String? =
        if (loginUrl.host == "www.e2r.jp" && loginUrl.encodedQuery == null) {
            Regex("/ja/([A-Za-z0-9_-]+)/").matchEntire(loginUrl.encodedPath)?.groupValues?.get(1)
        } else {
            null
        }

    // Legacy compatibility pin for already-reviewed entry routes; generic portals do not require entries here.
    // Never infer trust from an i-web suffix.
    val reviewedFormAction: String? =
        if (loginUrl.host == "mypage.3030.i-webs.jp" && loginUrl.encodedQuery == null &&
            loginUrl.encodedPath in
            setOf(
                "/pwc_newgrads28/",
                "/pwc_newgrads28/applicant/login/baitai-entry/entrycd/C00",
            )
        ) {
            "https://mypage.3030.i-webs.jp/pwc_newgrads28/applicant/login/check"
        } else {
            null
        }

    // Login pages often add a session query or move between sibling paths (for example
    // /mypage/login -> /mypage/login?ref=top). Generic portals therefore accept the same host under the
    // registered tenant directory, with every registered query parameter unchanged. Never widen to the
    // registrable domain: axol.jp, i-webs.jp and e2r.jp serve many companies from one host.
    // Reviewed E2R and i-web pins stay exact.
    val fillPathPrefix: String? =
        if (reviewedE2rSiteId != null || reviewedFormAction != null) null else tenantPrefix(loginUrl.encodedPath)

    // Viewing a public page or resource does not grant it credential or form-action authority.
    fun allowsNavigation(value: String?): Boolean = parseBrowsing(value) != null

    fun allowsResource(value: String?): Boolean = parseBrowsing(value) != null

    fun allowsFill(value: String?): Boolean {
        val url = parse(value)?.newBuilder()?.fragment(null)?.build() ?: return false
        if (url == loginUrl) return true
        val prefix = fillPathPrefix ?: return false
        val path = url.encodedPath
        if (url.host != loginUrl.host || "//" in path || !"$path/".startsWith(prefix)) return false
        return loginUrl.queryParameterNames.all { url.queryParameterValues(it) == loginUrl.queryParameterValues(it) }
    }

    /** The registered scope, or this session's first landing document; both need a native document token. */
    fun allowsDocumentFill(
        value: String?,
        documentToken: String?,
        landing: CareerBrowserLanding?,
    ): Boolean = documentToken != null && (allowsFill(value) || landing?.isDocument(value, documentToken) == true)

    companion object {
        private val authSegment =
            Regex(
                "(?:login|logon|sign-?in|auth|authenticate|authentication|sessions?)(?:\\.(?:php|aspx?|jsp|html?))?",
                RegexOption.IGNORE_CASE,
            )
        private val indexFile = Regex("index\\.(?:php|aspx?|jsp|html?)", RegexOption.IGNORE_CASE)

        /** Directory that identifies the company: everything before the login segment, else the whole path. */
        internal fun tenantPrefix(encodedPath: String): String {
            val segments = encodedPath.split('/').filter { it.isNotEmpty() }
            val auth = segments.indexOfLast { authSegment.matches(it) }
            val parts =
                when {
                    auth >= 0 -> segments.take(auth)
                    segments.lastOrNull()?.let(indexFile::matches) == true -> segments.dropLast(1)
                    else -> segments
                }
            return "/" + parts.joinToString("") { "$it/" }
        }

        fun create(value: String?): CareerBrowserPolicy? =
            parse(value)?.let {
                CareerBrowserPolicy(it.newBuilder().fragment(null).build())
            }

        /** A browsable public HTTPS document without fragment or encoded path tricks. */
        internal fun documentUrl(value: String?): HttpUrl? {
            val url = parseBrowsing(value) ?: return null
            if (';' in url.encodedPath || "//" in url.encodedPath ||
                Regex("%2f|%5c|%25|%3b", RegexOption.IGNORE_CASE).containsMatchIn(url.encodedPath)
            ) {
                return null
            }
            return url.newBuilder().fragment(null).build()
        }

        private fun parseBrowsing(value: String?): HttpUrl? {
            val url = parse(value, strictLoginTarget = false) ?: return null
            val host = url.host.trimEnd('.')
            // Also reject rooted local names and legacy dotted hexadecimal IP literals.
            if ('.' !in host || host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost") ||
                host.split('.').all { it.matches(Regex("(?:[0-9]+|0[xX][0-9a-fA-F]+)")) }
            ) {
                return null
            }
            return url
        }

        // Only browsing accepts encoded paths and longer URLs used by SSO redirect flows.
        private fun parse(
            value: String?,
            strictLoginTarget: Boolean = true,
        ): HttpUrl? {
            val maxLength = if (strictLoginTarget) 2048 else 65_536
            if (value.isNullOrBlank() || value.length > maxLength ||
                value.any { it.isWhitespace() || it <= '\u001f' } || '\\' in value
            ) {
                return null
            }
            val url = value.toHttpUrlOrNull() ?: return null
            if (strictLoginTarget &&
                (
                    ';' in url.encodedPath ||
                        Regex("%2f|%5c|%25|%3b", RegexOption.IGNORE_CASE).containsMatchIn(url.encodedPath)
                )
            ) {
                return null
            }
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            // Recruitment portals are public HTTPS endpoints, never local Hermes/LAN addresses.
            val host = url.host
            if (host == "localhost" || ':' in host || host.matches(Regex("[0-9.]+")) ||
                '.' !in host || host.endsWith(".local") || host.endsWith(".localhost")
            ) {
                return null
            }
            return url
        }
    }
}

/**
 * The document the registered URL actually opened, after its server redirects. It receives the registered
 * company's credentials although its URL differs; the server request stays bound to the registered URL.
 */
internal class CareerBrowserLanding private constructor(
    val url: HttpUrl,
    val token: String,
) {
    fun isDocument(
        value: String?,
        documentToken: String?,
    ): Boolean = documentToken == token && CareerBrowserPolicy.documentUrl(value) == url

    companion object {
        fun create(
            value: String?,
            token: String,
        ): CareerBrowserLanding? {
            if (token.isBlank()) return null
            return CareerBrowserPolicy.documentUrl(value)?.let { CareerBrowserLanding(it, token) }
        }
    }
}

/**
 * Follows only the root session's initial load and the server redirects it is answered with. Any other
 * main-frame request (link, form, script redirect, back/forward, reload) ends the chain for good.
 */
internal class CareerBrowserLandingChain {
    private var begun = false
    private var awaiting: HttpUrl? = null
    private var latest = 0L
    private var chained = 0L
    private var chainedUrl: HttpUrl? = null

    @get:Synchronized
    var landing: CareerBrowserLanding? = null
        private set

    @Synchronized
    fun begin(url: String) {
        if (begun) return
        begun = true
        awaiting = CareerBrowserPolicy.documentUrl(url)
    }

    /** Called for every main-frame request; returns its sequence number. */
    @Synchronized
    fun request(
        url: String,
        method: String,
    ): Long {
        val expected = awaiting
        awaiting = null
        latest++
        val continued = expected != null && method == "GET" && CareerBrowserPolicy.documentUrl(url) == expected
        chained = if (continued) latest else 0
        chainedUrl = if (continued) expected else null
        return latest
    }

    @Synchronized
    fun continues(request: Long): Boolean = request != 0L && request == chained && request == latest

    /** A redirect continues the chain only if no other main-frame request superseded it. */
    @Synchronized
    fun redirect(
        request: Long,
        next: String?,
    ): Boolean {
        if (!continues(request)) return false
        chained = 0
        chainedUrl = null
        awaiting = CareerBrowserPolicy.documentUrl(next)
        return awaiting != null
    }

    /** The chain's served document becomes the one landing; nothing later can be granted. */
    @Synchronized
    fun grant(
        request: Long,
        url: String?,
        token: String,
    ): CareerBrowserLanding? {
        if (!continues(request)) return null
        val expected = chainedUrl
        chained = 0
        chainedUrl = null
        if (CareerBrowserPolicy.documentUrl(url) != expected) return null
        return CareerBrowserLanding.create(url, token)?.also { landing = it }
    }

    @Synchronized
    fun documentStarted() {
        landing = null
    }

    @Synchronized
    fun end() {
        awaiting = null
        chained = 0
        chainedUrl = null
        landing = null
    }
}

/** Credentials are never data classes, saved state, navigation arguments, or log output. */
internal class CareerBrowserCredentials(
    val loginId: CharArray,
    val password: CharArray,
) : AutoCloseable {
    override fun close() {
        loginId.fill('\u0000')
        password.fill('\u0000')
    }

    override fun toString(): String = "CareerBrowserCredentials([REDACTED])"
}
