package com.m57.hermescontrol.career

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerCompanyReadinessTest {
    @Test
    fun missingServerFieldIsUnknownNotMissingCredentials() {
        val row = Json.decodeFromString<CareerCompanyRow>("""{"id":"old","name":"旧サーバー"}""")
        assertNull(row.mypage_readiness)
        assertFalse(row.matchesCompanyFilter("", false, "login_missing"))
        assertFalse(row.matchesCompanyFilter("", false, "login_attention"))
    }

    @Test
    fun setupAuthenticationAndUnverifiedFiltersRemainDistinct() {
        val missing = row("not_configured", "unknown")
        val saved = row("configured", "unknown")
        val blocked = row("configured", "needs_attention")
        val ready = row("configured", "previously_confirmed")
        assertTrue(missing.matchesCompanyFilter("", false, "login_missing"))
        assertFalse(saved.matchesCompanyFilter("", false, "login_missing"))
        assertTrue(saved.matchesCompanyFilter("", false, "login_unverified"))
        assertTrue(blocked.matchesCompanyFilter("", false, "login_attention"))
        assertFalse(blocked.matchesCompanyFilter("", false, "login_unverified"))
        assertFalse(ready.matchesCompanyFilter("", false, "login_attention"))
        assertEquals("unknown", missing.mypage_readiness?.website_registration)
        assertTrue(missing.matchesCompanyFilter("対象", false, "all"))
        assertFalse(missing.matchesCompanyFilter("別会社", false, "login_missing"))
        assertFalse(missing.matchesCompanyFilter("", true, "login_missing"))
    }

    private fun row(
        credentials: String,
        login: String,
    ) = CareerCompanyRow(
        id = "company",
        name = "対象企業",
        mypage_readiness = CareerMyPageReadiness(credential_state = credentials, login_state = login),
    )
}
