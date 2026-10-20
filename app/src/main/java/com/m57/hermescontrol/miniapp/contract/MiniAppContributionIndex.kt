package com.m57.hermescontrol.miniapp.contract

enum class MiniAppSlot { HOME_CARD, COMMAND, COMPOSER_ACTION, ACTIVITY_RENDERER, SETTINGS_SECTION, NOTIFICATION_ROUTE }

data class MiniAppContribution(
    val appId: String,
    val slot: MiniAppSlot,
    val id: String,
    val priority: Int,
)

/** Declaration index only: no renderer, network request, or action execution is available in Phase 1. */
class MiniAppContributionIndex(
    apps: List<ValidatedMiniApp>,
) {
    private val appsById = apps.associateBy { it.manifest.id }
    private val enabled = mutableSetOf<String>()

    init {
        if (appsById.size != apps.size) throw MiniAppContractException(MiniAppContractError.DUPLICATE)
    }

    fun setEnabled(
        appId: String,
        value: Boolean,
    ) {
        require(appId in appsById)
        if (value) enabled.add(appId) else enabled.remove(appId)
    }

    fun contributions(): List<MiniAppContribution> =
        enabled
            .flatMap { appId ->
                val declared = appsById.getValue(appId).manifest.contributions
                val routes =
                    listOf(
                        MiniAppSlot.HOME_CARD to declared.homeCards,
                        MiniAppSlot.COMPOSER_ACTION to declared.composerActions,
                        MiniAppSlot.ACTIVITY_RENDERER to declared.activityRenderers,
                        MiniAppSlot.SETTINGS_SECTION to declared.settingsSections,
                        MiniAppSlot.NOTIFICATION_ROUTE to declared.notificationRoutes,
                    ).flatMap { (slot, entries) ->
                        entries.map { MiniAppContribution(appId, slot, it.id, it.priority) }
                    }
                routes + declared.commands.map { MiniAppContribution(appId, MiniAppSlot.COMMAND, it.id, 0) }
            }.sortedWith(
                compareByDescending<MiniAppContribution> {
                    it.priority
                }.thenBy { it.appId }.thenBy { it.slot }.thenBy { it.id },
            )
}
