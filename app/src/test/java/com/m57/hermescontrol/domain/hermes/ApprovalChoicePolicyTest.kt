package com.m57.hermescontrol.domain.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalChoicePolicyTest {
    @Test
    fun absentAdvertisementStaysLegacyEvenWhenFlagsPermitMore() {
        for (allowPermanent in listOf(null, false, true)) {
            for (smartDenied in listOf(null, false, true)) {
                assertEquals(listOf("once", "deny"), allowedApprovalChoices(null, allowPermanent, smartDenied))
            }
        }
    }

    @Test
    fun explicitEmptyOrUnknownAdvertisementNeverFallsBack() {
        for (advertised in listOf(emptyList(), listOf("approve", "ONCE", " once", "deny ", "all"))) {
            for (smartDenied in listOf(null, true)) {
                assertEquals(emptyList<String>(), allowedApprovalChoices(advertised, smartDenied = smartDenied))
            }
        }
    }

    @Test
    fun explicitChoicesAreKnownUniqueAndCanonicallyOrdered() {
        assertEquals(
            listOf("once", "session", "always", "deny"),
            allowedApprovalChoices(listOf("deny", "always", "session", "once", "always", "unknown")),
        )
        assertEquals(listOf("session"), allowedApprovalChoices(listOf("session")))
        assertEquals(listOf("always"), allowedApprovalChoices(listOf("always")))
    }

    @Test
    fun permanentAndSmartDeniedFlagsOnlyRemoveOfferedChoices() {
        val offered = listOf("once", "session", "always", "deny")
        assertEquals(listOf("once", "session", "deny"), allowedApprovalChoices(offered, allowPermanent = false))
        assertEquals(listOf("once", "deny"), allowedApprovalChoices(offered, smartDenied = true))
        assertEquals(
            listOf("once", "deny"),
            allowedApprovalChoices(offered, allowPermanent = false, smartDenied = true),
        )
        assertEquals(emptyList<String>(), allowedApprovalChoices(listOf("always"), allowPermanent = false))
        assertEquals(emptyList<String>(), allowedApprovalChoices(listOf("session", "always"), smartDenied = true))
        assertEquals(listOf("deny"), allowedApprovalChoices(listOf("deny"), smartDenied = true))
    }
}
