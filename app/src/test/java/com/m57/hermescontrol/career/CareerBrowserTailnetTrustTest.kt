package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.remote.SESSION_COOKIE_NAME
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerBrowserTailnetTrustTest {
    private val endpoint = "http://100.64.10.20:9119/"
    private val base = endpoint.toHttpUrl()
    private val credentialsUrl = "${endpoint}api/plugins/career/mypage/browser-credentials"
    private val request = "$credentialsUrl?profile=default".toHttpUrl()
    private val scope = "$endpoint\nlocal-connection-a\nprofile-a"

    private fun trust(
        endpoint: String = this.endpoint,
        declaration: String = this.endpoint,
        session: String? = "synthetic-session",
    ) = CareerBrowserTailnetTrust(endpoint.toHttpUrl(), declaration, scope, session)

    @Test
    fun anotherConfiguredEndpointWorksOnlyWithItsOwnExplicitDeclaration() {
        val other = "http://100.127.20.30:9220/"
        assertTrue(trust(endpoint = other, declaration = other).enabled)
        assertFalse(trust(endpoint = other).enabled)
        assertFalse(trust(declaration = other).enabled)
        assertTrue(
            trust(endpoint = other, declaration = other)
                .allowsRequest("${other}api/plugins/career/mypage/browser-credentials?profile=default".toHttpUrl()),
        )
    }

    @Test
    fun emptyDeclarationNeverEnablesHttpAndExactNormalizedDeclarationDoes() {
        assertFalse(trust(declaration = "").enabled)
        assertTrue(trust().enabled)
        assertTrue(trust(endpoint.removeSuffix("/"), endpoint.removeSuffix("/")).enabled)
        assertFalse(trust(endpoint.replace("http:", "https:")).enabled)
    }

    @Test
    fun unrelatedHostsPortsPrefixesQueriesUserinfoAndFragmentsCannotInheritTrust() {
        val rejected =
            listOf(
                "http://100.64.10.21:9119/",
                "http://100.64.0.1:9119/",
                "http://100.64.10.20.example.test:9119/",
                "http://100.64.10.20:9120/",
                "http://100.64.10.20/",
                "http://100.64.10.20:9119/proxy/",
                "http://user@100.64.10.20:9119/",
                "http://100.64.10.20:9119/?x=1",
                "http://100.64.10.20:9119/#fragment",
            )
        for (value in rejected) {
            assertFalse(value, trust(endpoint = value).enabled)
            assertFalse(value, trust(declaration = value).enabled)
        }
    }

    @Test
    fun declarationMustBeExactLiteralEvenWhenParserWouldNormalizeItToTheApprovedUrl() {
        for (value in listOf(
            "http://@100.64.10.20:9119/",
            "http://100.64.10.20:9119/a/../",
            "http://100.64.10.20:9119/./",
            " http://100.64.10.20:9119/",
            "http://100.64.10.20:9119/\n",
            "HTTP://100.64.10.20:9119/",
        )) {
            assertFalse(value, trust(declaration = value).enabled)
        }
    }

    @Test
    fun missingSessionAndChangedConnectionProfileEndpointOrSessionAreRejectedPermanently() {
        assertFalse(trust(session = null).hasSession)
        assertFalse(trust(session = "").isCurrent(base, scope, true, "synthetic-session"))
        val changes =
            listOf<(CareerBrowserTailnetTrust) -> Boolean>(
                { it.isCurrent(base, "$endpoint\nlocal-connection-b\nprofile-a", true, "synthetic-session") },
                { it.isCurrent(base, "$endpoint\nlocal-connection-a\nprofile-b", true, "synthetic-session") },
                { it.isCurrent("http://100.64.10.21:9119/".toHttpUrl(), scope, true, "synthetic-session") },
                { it.isCurrent(base, scope, false, "synthetic-session") },
                { it.isCurrent(base, scope, true, "changed-session") },
                { it.isCurrent(base, scope, true, null) },
            )
        for (change in changes) {
            val trust = trust()
            assertTrue(trust.isCurrent(base, scope, true, "synthetic-session"))
            assertFalse(change(trust))
            assertFalse(trust.isCurrent(base, scope, true, "synthetic-session"))
            assertFalse(trust.allowsRequest(request))
        }
    }

    @Test
    fun onlyFiveExactMainProfileCredentialUrlsAreAllowed() {
        val trust = trust()
        for (path in listOf(
            "browser-access/prepare",
            "browser-credentials",
            "browser-access/revoke",
            "login-id",
            "browser-login-locate",
        )) {
            assertTrue(trust.allowsRequest("${endpoint}api/plugins/career/mypage/$path?profile=default".toHttpUrl()))
        }
        for (value in listOf(
            credentialsUrl,
            "$credentialsUrl?profile=specialist",
            "$request&profile=specialist",
            "$request&x=1",
            "$credentialsUrl/?profile=default",
            "$request#fragment",
            request.toString().replace("100.64.10.20", "100.64.10.21"),
            request.toString().replace(":9119", ":9120"),
            request.toString().replace("http:", "https:"),
            "${endpoint}api/login",
        )) {
            assertFalse(value, trust.allowsRequest(value.toHttpUrl()))
        }
    }

    @Test
    fun cookieJarNeverForwardsOtherSessionSecureCookieOrCrossOriginAndNeverSaves() {
        val cookie =
            Cookie
                .Builder()
                .name(SESSION_COOKIE_NAME)
                .value("synthetic-session")
                .hostOnlyDomain(base.host)
                .path("/")
                .build()
        val supplied =
            listOf(
                cookie,
                Cookie
                    .Builder()
                    .name(SESSION_COOKIE_NAME)
                    .value("foreign-session")
                    .hostOnlyDomain(base.host)
                    .path("/")
                    .build(),
                Cookie
                    .Builder()
                    .name("other")
                    .value("unrelated-cookie")
                    .hostOnlyDomain(base.host)
                    .path("/")
                    .build(),
                Cookie
                    .Builder()
                    .name(SESSION_COOKIE_NAME)
                    .value("synthetic-session")
                    .hostOnlyDomain(base.host)
                    .path("/")
                    .secure()
                    .build(),
                Cookie
                    .Builder()
                    .name(SESSION_COOKIE_NAME)
                    .value("synthetic-session")
                    .hostOnlyDomain("other.example.test")
                    .path("/")
                    .build(),
            )
        var reads = 0
        val delegate =
            object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    reads++
                    return supplied
                }

                override fun saveFromResponse(
                    url: HttpUrl,
                    cookies: List<Cookie>,
                ) = error("must not persist")
            }
        var current = true
        val jar = CareerBrowserTailnetCookieJar(delegate, trust()) { current }
        assertEquals(listOf(cookie), jar.loadForRequest(request))
        assertTrue(jar.loadForRequest("http://foreign.example.test/".toHttpUrl()).isEmpty())
        assertEquals(1, reads)
        current = false
        assertTrue(jar.loadForRequest(request).isEmpty())
        assertEquals(1, reads)
        jar.saveFromResponse(request, supplied)
    }

    @Test
    fun scopeChangeWhileCookieJarLoadsCannotSendTheNewSessionsCookies() {
        var current = true
        val delegate =
            object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    current = false
                    return listOf(
                        Cookie
                            .Builder()
                            .name(SESSION_COOKIE_NAME)
                            .value("synthetic-session")
                            .hostOnlyDomain(base.host)
                            .path("/")
                            .build(),
                    )
                }

                override fun saveFromResponse(
                    url: HttpUrl,
                    cookies: List<Cookie>,
                ) = Unit
            }
        val jar = CareerBrowserTailnetCookieJar(delegate, trust()) { current }
        assertTrue(jar.loadForRequest(request).isEmpty())
    }
}
