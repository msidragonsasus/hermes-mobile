package com.m57.hermescontrol.career

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Browsing is independent of the exact registered URL that alone may receive credentials. */
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
