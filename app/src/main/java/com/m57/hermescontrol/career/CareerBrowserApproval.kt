package com.m57.hermescontrol.career

/** Memory only: neither the confirmation identifier nor URL is saved in navigation/state. */
internal class CareerBrowserApproval(
    private val id: String,
    private val company: String,
    private val profile: String,
    private val version: Int,
    private val url: String,
    private val scope: String,
    private val expires: Long,
    val requiresConfirmation: Boolean = true,
) {
    private var used = false

    @Synchronized
    fun isCurrent(): Boolean = !used && System.nanoTime() < expires

    @Synchronized
    fun consume(
        company: String,
        profile: String,
        version: Int,
        url: String,
        scope: String,
    ): String? {
        if (!isCurrent() || this.company != company || this.profile != profile ||
            this.version != version || this.url != url || this.scope != scope
        ) {
            return null
        }
        used = true
        return id
    }
}
