package com.m57.hermescontrol.career

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerBrowserPolicyTest {
    private val policy = checkNotNull(CareerBrowserPolicy.create("https://portal.example.test/company/login?year=2028"))

    @Test
    fun publicHttpsPagesAndAssetsDoNotNeedSiteSpecificAllowLists() {
        val axolLogin = "https://axol.jp/zw/s/sumitomocorp_28/mypage/login"
        val axol = checkNotNull(CareerBrowserPolicy.create(axolLogin))
        val pagesAndAssets =
            listOf(
                "https://static.axol.jp/zw/st/public/css/common.css",
                "https://static.axol.jp/other/st/js/jquery.js",
                "https://upload.axol.jp/zw/s/sumitomocorp_28/mypage/1/logo.jpg",
                "https://upload.axol.jp/zw/s/other/mypage/logo.jpg",
                "https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined",
                "https://fonts.gstatic.com/s/materialsymbolsoutlined/v1/font.woff2",
                "https://cdn.example.test/bundle.js",
                "https://identity.example.test/sso/start?return=https%3A%2F%2Fportal.example.test",
                "https://another-portal.example.test/apply",
                "https://portal.example.test/other/login?year=2028",
                "https://axol.jp/zw/s/other/mypage/login",
            )
        listOf(policy, axol).forEach { registered ->
            pagesAndAssets.forEach { address ->
                assertTrue(address, registered.allowsResource(address))
                assertTrue(address, registered.allowsNavigation(address))
                assertFalse(address, registered.allowsFill(address))
            }
        }
        assertTrue(axol.allowsFill(axolLogin))
        assertTrue(axol.allowsFill(axolLogin.replace("/login", "/home")))
    }

    @Test
    fun axolLoginAcceptsTheSameCompanyPagesButNeverAnotherTenantOrHost() {
        val axolLogin = "https://axol.jp/zw/s/sumitomocorp_28/mypage/login"
        val axol = checkNotNull(CareerBrowserPolicy.create(axolLogin))
        assertEquals("/zw/s/sumitomocorp_28/mypage/", axol.fillPathPrefix)
        listOf(
            axolLogin,
            "$axolLogin?ref=top",
            "$axolLogin/",
            "$axolLogin#agree",
            "https://axol.jp/zw/s/sumitomocorp_28/mypage",
            "https://axol.jp/zw/s/sumitomocorp_28/mypage/",
            "https://axol.jp/zw/s/sumitomocorp_28/mypage/top?x=1",
        ).forEach { assertTrue(it, axol.allowsFill(it)) }
        listOf(
            "https://axol.jp/zw/s/other_28/mypage/login",
            "https://axol.jp/zw/s/sumitomocorp_28/mypageevil/login",
            "https://axol.jp/zw/s/sumitomocorp_28/login",
            "https://axol.jp/zw/s/sumitomocorp_28/mypage/..%2F..%2Fother_28/mypage/login",
            "https://axol.jp/zw/s/sumitomocorp_28/mypage/;other/login",
            "https://static.axol.jp/zw/s/sumitomocorp_28/mypage/login",
            "https://axol.jp.evil.test/zw/s/sumitomocorp_28/mypage/login",
            "https://evil-axol.jp/zw/s/sumitomocorp_28/mypage/login",
            "http://axol.jp/zw/s/sumitomocorp_28/mypage/login",
            "https://axol.jp:8443/zw/s/sumitomocorp_28/mypage/login",
        ).forEach { assertFalse(it, axol.allowsFill(it)) }
    }

    @Test
    fun tenantPrefixKeepsEverythingBeforeTheLoginSegment() {
        assertEquals("/company/", CareerBrowserPolicy.tenantPrefix("/company/login"))
        assertEquals("/nested/company/", CareerBrowserPolicy.tenantPrefix("/nested/company/login.php"))
        assertEquals("/jr-central2028/", CareerBrowserPolicy.tenantPrefix("/jr-central2028/"))
        assertEquals("/tenant/", CareerBrowserPolicy.tenantPrefix("/tenant"))
        assertEquals("/tenant/", CareerBrowserPolicy.tenantPrefix("/tenant/index.html"))
        assertEquals("/", CareerBrowserPolicy.tenantPrefix("/login"))
    }

    @Test
    fun responseHeadersPreserveServerContentSecurityPoliciesWithoutAddingRestrictions() {
        val policies = listOf("default-src 'self' https://cdn.example.test", "object-src 'none'")
        val reportOnly = "script-src 'self'; report-uri /csp-report"
        val headers =
            careerBrowserResponseHeaders(
                mapOf(
                    "content-security-policy" to policies,
                    "Content-Security-Policy-Report-Only" to listOf(reportOnly),
                    "X-Content-Type-Options" to listOf("nosniff"),
                    "server-timing" to listOf("hermes;desc=\"untrusted-server-marker\""),
                ),
                "fixture-token",
            )
        assertEquals(policies.joinToString(", "), headers["content-security-policy"])
        assertEquals(1, headers.keys.count { it.equals("Content-Security-Policy", true) })
        assertEquals(reportOnly, headers["Content-Security-Policy-Report-Only"])
        assertEquals("nosniff", headers["X-Content-Type-Options"])
        assertEquals("hermes;desc=\"fixture-token\"", headers["Server-Timing"])
        assertEquals(1, headers.keys.count { it.equals("Server-Timing", true) })
    }

    @Test
    fun responseHeadersDoNotInjectCspWhenServerHasNone() {
        val headers = careerBrowserResponseHeaders(emptyMap(), "fixture-token")
        assertFalse(headers.keys.any { it.equals("Content-Security-Policy", true) })
        assertFalse(headers.keys.any { it.equals("Content-Security-Policy-Report-Only", true) })
        assertEquals("hermes;desc=\"fixture-token\"", headers["Server-Timing"])
    }

    @Test
    fun e2rSharedLoginRetainsTheExactRegisteredTenantIdentity() {
        val root = "https://www.e2r.jp/ja/baycurrent2028/"
        val registered = checkNotNull(CareerBrowserPolicy.create(root))
        assertEquals("baycurrent2028", registered.reviewedE2rSiteId)
        assertTrue(registered.allowsFill(root))
        assertFalse(registered.allowsFill("https://www.e2r.jp/eARTH/common/Logon"))
        assertFalse(registered.allowsFill(root.replace("baycurrent2028", "another2028")))
        assertEquals(
            "synthetic2028",
            checkNotNull(CareerBrowserPolicy.create(root.replace("baycurrent2028", "synthetic2028"))).reviewedE2rSiteId,
        )
        listOf(
            root + "?tenant=another2028",
            root + "login",
            root.replace("www.e2r.jp", "www.e2r.jp.evil.test"),
            root.replace("www.e2r.jp", "other.example.test"),
            root.replace("baycurrent2028", "baycurrent2028%2fother"),
            root.replace("/ja/", "/other/"),
            "https://www.e2r.jp/eARTH/common/Logon",
        ).forEach { address ->
            assertNull(address, CareerBrowserPolicy.create(address)?.reviewedE2rSiteId)
        }
        val probe = CareerBrowserFill.readinessScript(registered, "synthetic-token")
        assertTrue(probe.contains("const e2rSiteId = \"baycurrent2028\";"))
        assertTrue(CareerBrowserFill.readinessScript(policy, "synthetic-token").contains("const e2rSiteId = null;"))
    }

    @Test
    fun reviewedIwebAdapterPinsObservedPortalAndActionWithoutBroadeningFill() {
        val root = "https://mypage.3030.i-webs.jp/pwc_newgrads28/"
        val entry = root + "applicant/login/baitai-entry/entrycd/C00"
        listOf(root, entry).forEach { address ->
            val reviewed = checkNotNull(CareerBrowserPolicy.create(address))
            assertEquals(root + "applicant/login/check", reviewed.reviewedFormAction)
            assertTrue(reviewed.allowsNavigation(root + "applicant/home"))
            assertTrue(reviewed.allowsNavigation(root.replace("pwc_newgrads28", "other") + "applicant/home"))
            assertTrue(reviewed.allowsNavigation(root.replace("pwc_newgrads28", "pwc_newgrads28evil")))
            assertTrue(reviewed.allowsFill(address))
            assertFalse(reviewed.allowsFill(root + "applicant/home"))
            assertFalse(reviewed.allowsFill(root + "applicant/login/check"))
            assertFalse(reviewed.allowsFill(address + "?other=1"))
            assertFalse(reviewed.allowsFill(address.replace("pwc_newgrads28", "other")))
            assertFalse(reviewed.allowsFill(address.replace("pwc_newgrads28", "pwc_newgrads28evil")))
        }
        listOf(
            root.replace("pwc_newgrads28", "other"),
            root.replace("mypage.3030.i-webs.jp", "mypage.9999.i-webs.jp"),
            root.replace("mypage.3030.i-webs.jp", "mypage.3030.i-webs.jp.evil.test"),
            root + "?tenant=other",
            root + "applicant/unknown",
            entry.replace("C00", "C01"),
            root + "applicant/login/check",
        ).forEach { address ->
            assertNull(address, checkNotNull(CareerBrowserPolicy.create(address)).reviewedFormAction)
        }
        assertNull(policy.reviewedFormAction)
    }

    @Test
    fun registeredPagesAcrossProvidersDoNotBroadenDocumentFillAuthority() {
        listOf(
            "https://mypage.3030.i-webs.jp/pwc_newgrads28/",
            "https://mypage.3070.i-webs.jp/jr-central2028/",
            "https://portal.example.test/nested/company/login",
        ).forEach { address ->
            val registered = checkNotNull(CareerBrowserPolicy.create(address))
            assertTrue(registered.allowsFill(address))
            // Reviewed i-web pins stay exact; generic portals accept their own tenant directory.
            val exact = registered.fillPathPrefix == null
            assertEquals(!exact, registered.allowsFill(address + "?other=1"))
            assertEquals(!exact, registered.allowsFill(address + "/applicant/login/check".removePrefix(if (address.endsWith("/")) "/" else "")))
            assertFalse(registered.allowsFill(address.replace(Regex("(i-webs\\.jp|example\\.test)/[^/]+/"), "$1/other/")))
            assertTrue(registered.allowsNavigation("https://other.example.test/login"))
            assertFalse(registered.allowsFill("https://other.example.test/login"))
        }
    }

    @Test
    fun browsingOtherOriginsTenantsAndQueriesDoesNotGrantFillAuthority() {
        listOf(
            "https://portal.example.test/company/home",
            "https://portal.example.test/other/login?year=2028",
            "https://portal.example.test/company/login?year=2027",
            "https://portal.example.test/css/styles.css",
            "https://portal.example.test.evil.test/company/login?year=2028",
            "https://other.example.test/company/login?year=2028",
        ).forEach { address ->
            assertTrue(address, policy.allowsNavigation(address))
            assertTrue(address, policy.allowsResource(address))
            assertFalse(address, policy.allowsFill(address))
        }
        assertTrue(policy.allowsFill("https://portal.example.test/company/login?year=2028"))
        assertTrue(policy.allowsFill("https://portal.example.test/company/login?year=2028#fragment"))
    }

    @Test
    fun refusesUnsafeUrlsAndSchemesForRegistrationNavigationResourcesAndFill() {
        listOf(
            "http://portal.example.test",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://vault/item",
            "data:text/html,fixture",
            "blob:https://portal.example.test/fixture",
            "intent://portal.example.test/#Intent;scheme=https;end",
            "https://user:password@portal.example.test",
            "https://portal.example.test:444/",
            "https://127.0.0.1/",
            "https://10.0.0.1/",
            "https://192.168.0.1/",
            "https://169.254.169.254/",
            "https://2130706433/",
            "https://[::1]/",
            "https://[::ffff:127.0.0.1]/",
            "https://localhost/",
            "https://box.local/",
            "https://box.localhost/",
            "https://intranet/",
            "https://portal.example.test\\@evil.test/",
            "https://portal.example.test/\n",
            "https://portal.example.test/" + "x".repeat(65_536),
            "",
            " ",
            null,
        ).forEach {
            assertNull(it, CareerBrowserPolicy.create(it))
            assertFalse(it, policy.allowsNavigation(it))
            assertFalse(it, policy.allowsResource(it))
            assertFalse(it, policy.allowsFill(it))
        }
    }

    @Test
    fun longSsoUrlsCanBeBrowsedWithoutExpandingRegisteredCredentialTargets() {
        val prefix = "https://identity.example.test/sso?request="
        val registeredLimit = prefix + "x".repeat(2048 - prefix.length)
        val registered = checkNotNull(CareerBrowserPolicy.create(registeredLimit))
        assertTrue(registered.allowsFill(registeredLimit))
        assertNull(CareerBrowserPolicy.create(registeredLimit + "x"))
        assertFalse(registered.allowsFill(registeredLimit + "x"))

        val browserLimit = prefix + "x".repeat(65_536 - prefix.length)
        listOf(registeredLimit + "x", browserLimit).forEach {
            assertTrue(it, policy.allowsNavigation(it))
            assertTrue(it, policy.allowsResource(it))
            assertNull(it, CareerBrowserPolicy.create(it))
            assertFalse(it, policy.allowsFill(it))
        }
        assertFalse(policy.allowsNavigation(browserLimit + "x"))
        assertFalse(policy.allowsResource(browserLimit + "x"))
    }

    @Test
    fun browsingRejectsRootedLocalNamesAndAlternativeIpLiterals() {
        listOf(
            "https://localhost./",
            "https://intranet./",
            "https://box.local./",
            "https://box.localhost./",
            "https://127.1/",
            "https://0177.0.0.1/",
            "https://0x7f.0.0.1/",
            "https://0x7f.0x0.0x0.0x1/",
        ).forEach {
            assertFalse(it, policy.allowsNavigation(it))
            assertFalse(it, policy.allowsResource(it))
        }
        assertTrue(policy.allowsNavigation("https://portal.example.test./company/home"))
        assertFalse(policy.allowsFill("https://portal.example.test./company/login?year=2028"))
    }

    @Test
    fun credentialObjectRedactsAndWipesOwnedBuffers() {
        val value = CareerBrowserCredentials("dummy-id".toCharArray(), "dummy-password".toCharArray())
        assertFalse(value.toString().contains("dummy-password"))
        value.close()
        assertTrue(value.password.all { it == '\u0000' })
        assertTrue(value.loginId.all { it == '\u0000' })
    }

    @Test
    fun rootLoginAllowsBrowsingOtherTenantsButOnlyFillsRegisteredTenantQuery() {
        val login = "https://portal.example.test/login?tenant=one"
        val root = checkNotNull(CareerBrowserPolicy.create(login))
        listOf(
            "https://portal.example.test/other/login",
            "https://portal.example.test/login?tenant=two",
        ).forEach {
            assertTrue(it, root.allowsNavigation(it))
            assertFalse(it, root.allowsFill(it))
        }
        assertTrue(root.allowsFill(login))
    }

    @Test
    fun encodedPathsMayBeBrowsedButCannotBecomeCredentialTargets() {
        listOf(
            "https://portal.example.test/company/..%2Fother/login",
            "https://portal.example.test/company/%5cother/login",
            "https://portal.example.test/company/..;/other/login",
            "https://portal.example.test/company/%3bother/login",
            "https://portal.example.test/company/%252e%252e%252fother/login",
            "https://cdn.example.test/images/100%25.jpg",
        ).forEach {
            assertTrue(it, policy.allowsNavigation(it))
            assertTrue(it, policy.allowsResource(it))
            assertNull(it, CareerBrowserPolicy.create(it))
            assertFalse(it, policy.allowsFill(it))
        }
    }
}
