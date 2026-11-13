package com.m57.hermescontrol.domain.hermes

internal val KNOWN_APPROVAL_CHOICES = listOf("once", "session", "always", "deny")

/** Request-local choices only: absent advertisement stays legacy, and flags can only restrict. */
fun allowedApprovalChoices(
    advertised: List<String>?,
    allowPermanent: Boolean? = null,
    smartDenied: Boolean? = null,
): List<String> {
    val offered = advertised ?: listOf("once", "deny")
    return KNOWN_APPROVAL_CHOICES.filter { choice ->
        choice in offered &&
            (allowPermanent != false || choice != "always") &&
            (smartDenied != true || choice == "once" || choice == "deny")
    }
}
