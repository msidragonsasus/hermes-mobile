package com.m57.hermescontrol.career

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first page a session opens receives credentials even when its server redirects elsewhere. */
class CareerBrowserLandingTest {
    private val registered = "https://portal.example.test/company/login?year=2028"
    private val policy = checkNotNull(CareerBrowserPolicy.create(registered))
    private val landingUrl = "https://login.idp.example.test/tenant/sign-in?client=company&state=synthetic-state"

    /** Registered GET -> server redirect -> landing GET, as the root WebView reports them. */
    private fun landed(
        chain: CareerBrowserLandingChain = CareerBrowserLandingChain(),
        token: String = "landing-token",
    ): Pair<CareerBrowserLandingChain, CareerBrowserLanding?> {
        chain.begin(registered)
        val first = chain.request(registered, "GET")
        assertTrue(chain.continues(first))
        assertTrue(chain.redirect(first, landingUrl))
        val landing = chain.request(landingUrl, "GET")
        assertTrue(chain.continues(landing))
        return chain to chain.grant(landing, landingUrl, token)
    }

    @Test
    fun redirectedFirstLandingDocumentMayFillDespiteRegisteredUrlMismatch() {
        val (chain, landing) = landed()
        assertFalse("registered scope alone still refuses this URL", policy.allowsFill(landingUrl))
        assertNotNull(landing)
        assertEquals(landing, chain.landing)
        assertTrue(policy.allowsDocumentFill(landingUrl, "landing-token", landing))
        assertTrue(policy.allowsDocumentFill("$landingUrl#step", "landing-token", landing))
    }

    @Test
    fun landingAuthorityIsBoundToItsOwnDocumentTokenAndUrl() {
        val (_, landing) = landed()
        assertFalse(policy.allowsDocumentFill(landingUrl, "replacement-token", landing))
        assertFalse(policy.allowsDocumentFill(landingUrl, null, landing))
        // Same document after an SPA URL change, another path, host or tenant.
        listOf(
            landingUrl.replace("synthetic-state", "other-state"),
            landingUrl.replace("/sign-in", "/home"),
            landingUrl.replace("login.idp", "other.idp"),
            "https://login.idp.example.test/other/sign-in?client=company&state=synthetic-state",
        ).forEach { assertFalse(it, policy.allowsDocumentFill(it, "landing-token", landing)) }
        assertFalse(policy.allowsDocumentFill(landingUrl, "landing-token", null))
    }

    @Test
    fun registeredScopeStillNeedsANativeDocumentTokenButNoLanding() {
        assertTrue(policy.allowsDocumentFill(registered, "registered-token", null))
        assertFalse(policy.allowsDocumentFill(registered, null, null))
        assertFalse(policy.allowsDocumentFill("https://other.example.test/login", "token", null))
    }

    @Test
    fun onlyOneLandingIsEverGrantedAndLaterDocumentsDoNotInheritIt() {
        val (chain, landing) = landed()
        assertNotNull(landing)
        // Link click, back/forward, reload, late JS redirect: every later main-frame request is outside the chain.
        listOf(landingUrl, registered, "https://login.idp.example.test/tenant/next").forEach { next ->
            val request = chain.request(next, "GET")
            assertFalse(next, chain.continues(request))
            assertNull(next, chain.grant(request, next, "later-token"))
        }
        // The original landing remains only for its document until a new document starts.
        assertTrue(policy.allowsDocumentFill(landingUrl, "landing-token", chain.landing))
        chain.documentStarted()
        assertNull(chain.landing)
    }

    @Test
    fun userNavigationOrSupersededRedirectEndsTheInitialChain() {
        val chain = CareerBrowserLandingChain()
        chain.begin(registered)
        val first = chain.request(registered, "GET")
        // The user taps a link before the registered response's redirect is handled.
        val manual = chain.request("https://portal.example.test/company/help", "GET")
        assertFalse(chain.continues(manual))
        assertFalse("stale redirect cannot re-open the chain", chain.redirect(first, landingUrl))
        val late = chain.request(landingUrl, "GET")
        assertFalse(chain.continues(late))
        assertNull(chain.grant(late, landingUrl, "late-token"))
        assertNull(chain.grant(first, registered, "first-token"))
    }

    @Test
    fun chainContinuesOnlyForTheAwaitedGetAndOnlyAfterBegin() {
        // Popups never begin a chain: their first document is never a landing.
        val popup = CareerBrowserLandingChain()
        val opened = popup.request(registered, "GET")
        assertFalse(popup.continues(opened))
        assertNull(popup.grant(opened, registered, "popup-token"))

        val post = CareerBrowserLandingChain().apply { begin(registered) }
        assertFalse(post.continues(post.request(registered, "POST")))

        val other = CareerBrowserLandingChain().apply { begin(registered) }
        assertFalse(other.continues(other.request("https://portal.example.test/company/other", "GET")))

        // begin is single-use, so a later restart of the same view cannot mint a second landing.
        val (chain, _) = landed()
        chain.begin(registered)
        assertFalse(chain.continues(chain.request(registered, "GET")))
    }

    @Test
    fun endRevokesTheLandingAndAnyPendingRedirect() {
        val (chain, landing) = landed()
        assertNotNull(landing)
        chain.end()
        assertNull(chain.landing)

        val pending = CareerBrowserLandingChain()
        pending.begin(registered)
        val first = pending.request(registered, "GET")
        assertTrue(pending.redirect(first, landingUrl))
        pending.end()
        assertFalse(pending.continues(pending.request(landingUrl, "GET")))
    }

    @Test
    fun grantRequiresTheServedUrlAndASafePublicHttpsDocument() {
        val chain = CareerBrowserLandingChain()
        chain.begin(registered)
        val first = chain.request(registered, "GET")
        assertNull("served URL must be the requested one", chain.grant(first, landingUrl, "token"))
        listOf(
            "http://login.idp.example.test/sign-in",
            "https://localhost/sign-in",
            "https://10.0.0.1/sign-in",
            "https://login.idp.example.test/tenant/..%2Fother/sign-in",
            "https://login.idp.example.test/tenant/;other/sign-in",
            "https://user:pass@login.idp.example.test/sign-in",
        ).forEach { unsafe ->
            assertNull(unsafe, CareerBrowserLanding.create(unsafe, "token"))
            val fresh = CareerBrowserLandingChain().apply { begin(registered) }
            assertFalse(unsafe, fresh.redirect(fresh.request(registered, "GET"), unsafe))
        }
    }

    @Test
    fun landingScriptsCarryTheExactLandingPageWithoutActionGatesOrSecrets() {
        val (_, landing) = landed()
        val probe = CareerBrowserFill.readinessScript(policy, "landing-token", landing = landing)
        assertTrue(probe.contains("const landingUrl = \"$landingUrl\";"))
        // Input-only: the granted landing's own form action, method and target are not fill gates.
        assertTrue(probe.contains("if (landingGranted()) return true;"))
        assertFalse(probe.contains("landingPrefix"))
        assertFalse(probe.contains("landingAction"))
        assertFalse(probe.contains("loginValue"))
        val plain = CareerBrowserFill.readinessScript(policy, "landing-token")
        assertTrue(plain.contains("const landingUrl = null;"))
        CareerBrowserCredentials("dummy-user".toCharArray(), "dummy-password".toCharArray()).use { credentials ->
            val script = CareerBrowserFill.script(policy, credentials, "landing-token", landing = landing)
            assertTrue(script.contains("const landingUrl = \"$landingUrl\";"))
            assertFalse(script.contains("dispatchEvent("))
            assertFalse(script.contains(".submit("))
        }
        val candidates = CareerBrowserFill.candidateScript(policy, "landing-token", landing)
        assertTrue(candidates.contains("const landingUrl = \"$landingUrl\";"))
        // A landing is embedded only into scripts for its own document.
        val other = CareerBrowserFill.readinessScript(policy, "replacement-token", landing = landing)
        assertTrue(other.contains("const landingUrl = null;"))
    }
}
