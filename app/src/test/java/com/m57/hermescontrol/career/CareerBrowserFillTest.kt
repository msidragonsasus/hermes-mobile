package com.m57.hermescontrol.career

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerBrowserFillTest {
    private val policy = checkNotNull(CareerBrowserPolicy.create("https://portal.example.test/company/login?year=2028"))

    @Test
    fun genericReadinessUsesLabelsAndTenantBoundariesWithoutAddingProviderExceptions() {
        listOf(
            "https://mypage.3030.i-webs.jp/pwc_newgrads28/",
            "https://mypage.3070.i-webs.jp/jr-central2028/",
            "https://portal.example.test/nested/company/login",
        ).forEach { address ->
            val probe = CareerBrowserFill.readinessScript(checkNotNull(CareerBrowserPolicy.create(address)), "dummy")
            assertTrue(probe.contains("e.labels"))
            assertTrue(probe.contains("aria-labelledby"))
            assertTrue(probe.contains("u.origin !== registered.origin"))
            assertTrue(probe.contains("u.search !== registered.search"))
            assertTrue(probe.contains("!u.pathname.startsWith(prefix)"))
            assertTrue(probe.contains("lastIndexOf(true)"))
            assertTrue(probe.contains("legacyForm"))
            assertFalse(probe.contains("loginValue"))
            assertFalse(probe.contains("passwordValue"))
        }
    }

    @Test
    fun legacyEntryExceptionRemainsNarrowAndOtherCompaniesNeedNoPin() {
        val entry = "https://mypage.3030.i-webs.jp/pwc_newgrads28/applicant/login/baitai-entry/entrycd/C00"
        val legacy = checkNotNull(CareerBrowserPolicy.create(entry))
        assertTrue(CareerBrowserFill.readinessScript(legacy, "dummy").contains("const legacyAction = \"https://"))
        assertTrue(CareerBrowserFill.readinessScript(policy, "dummy").contains("const legacyAction = null;"))
        // Reviewed pins keep the exact page; the JS scope then accepts nothing wider.
        assertTrue(CareerBrowserFill.readinessScript(legacy, "dummy").contains("const fillPrefix = null;"))
    }

    @Test
    fun readinessContainsTheSameDocumentAndFormGuardsWithoutCredentials() {
        val probe = CareerBrowserFill.readinessScript(policy, "dummy-document-token")
        assertTrue(probe.contains("'ready_id'"))
        assertTrue(probe.contains("'ready_password'"))
        assertTrue(probe.contains("!false) return 'pending'"))
        assertTrue(probe.contains("return 'pending'"))
        assertTrue(probe.contains("document.visibilityState === 'hidden'"))
        assertTrue(probe.contains("window !== window.top"))
        assertTrue(probe.contains("!inScope(new URL(location.href))"))
        assertTrue(probe.contains("const fillPrefix = \"/company/\";"))
        assertTrue(probe.contains("t.description === \"dummy-document-token\""))
        assertTrue(probe.contains("if (!safeForm()) return 'unsafe_action'"))
        assertFalse(probe.contains("loginValue"))
        assertFalse(probe.contains("passwordValue"))
        assertFalse(probe.contains("setValue.call"))
    }

    @Test
    fun generatedFillDoesNotDispatchEventsSubmitOrInstallAnObserver() {
        val credentials = CareerBrowserCredentials("dummy-user".toCharArray(), "dummy-password".toCharArray())
        try {
            val script = CareerBrowserFill.script(policy, credentials, "dummy-document-token")
            assertTrue(script.contains("const loginValue = \"dummy-user\", passwordValue = \"dummy-password\";"))
            assertTrue(script.contains("'filled'"))
            assertTrue(script.contains("document.visibilityState === 'hidden'"))
            assertTrue(script.contains("(pass && pass.value)) return 'occupied'"))
            assertFalse(script.contains("dispatchEvent("))
            assertFalse(script.contains(".submit("))
            assertFalse(script.contains(".requestSubmit("))
            assertFalse(script.contains(".click("))
            assertFalse(script.contains("MutationObserver("))
            assertFalse(script.contains("setTimeout("))
        } finally {
            credentials.close()
        }
    }

    @Test
    fun identifierStepNeverSerializesPassword() {
        CareerBrowserCredentials("dummy-user".toCharArray(), "dummy-password".toCharArray()).use { credentials ->
            val script = CareerBrowserFill.script(policy, credentials, "dummy-token", CareerBrowserFillStep.IDENTIFIER)
            assertTrue(script.contains("stage !== 'identifier'"))
            assertTrue(script.contains("dummy-user"))
            assertFalse(script.contains("dummy-password"))
            assertTrue(script.contains("!false) return 'pending'"))
        }
    }

    @Test
    fun passwordContinuationNeverSerializesIdentifier() {
        CareerBrowserCredentials("dummy-user".toCharArray(), "dummy-password".toCharArray()).use { credentials ->
            val script = CareerBrowserFill.script(policy, credentials, "dummy-token", CareerBrowserFillStep.PASSWORD)
            assertTrue(script.contains("stage !== 'password'"))
            assertTrue(script.contains("dummy-password"))
            assertFalse(script.contains("dummy-user"))
            assertTrue(script.contains("!true) return 'pending'"))
        }
        val probe = CareerBrowserFill.readinessScript(policy, "dummy-token", allowPasswordOnly = true)
        assertTrue(probe.contains("!true) return 'pending'"))
        assertFalse(probe.contains("loginValue"))
        assertFalse(probe.contains("passwordValue"))
    }

    @Test
    fun visibleIdentifierContinuationComparesButDoesNotOverwriteIdentifier() {
        CareerBrowserCredentials("dummy-user".toCharArray(), "dummy-password".toCharArray()).use { credentials ->
            val script =
                CareerBrowserFill.script(
                    policy,
                    credentials,
                    "dummy-token",
                    CareerBrowserFillStep.PASSWORD_WITH_IDENTIFIER,
                )
            assertTrue(script.contains("stage !== 'password_with_identifier'"))
            assertTrue(script.contains("dummy-user"))
            assertTrue(script.contains("dummy-password"))
            assertTrue(script.contains("ids[0].value !== loginValue) return 'occupied'"))
            assertTrue(
                script.contains(
                    "if (stage === 'combined' || stage === 'identifier') setValue.call(ids[0], loginValue)",
                ),
            )
        }
    }

    @Test
    fun continuationConsidersRenderedLockedIdentifiersForComparisonOnly() {
        val probe = CareerBrowserFill.readinessScript(policy, "dummy-token", allowPasswordOnly = true)
        assertTrue(probe.contains("true ? rendered(e) : editable(e)"))
        assertTrue(probe.contains("!e.matches(':disabled') && !e.readOnly"))
        assertTrue(probe.contains("ids.some(e => !editable(e))"))
        val freshProbe = CareerBrowserFill.readinessScript(policy, "dummy-token")
        assertTrue(freshProbe.contains("false ? rendered(e) : editable(e)"))
    }
}
