package com.m57.hermescontrol.worker

import com.m57.hermescontrol.WorkScreen
import com.m57.hermescontrol.navigation.AppContribution
import com.m57.hermescontrol.navigation.DestinationMetadata
import com.m57.hermescontrol.navigation.NavigationRole

class WorkerContribution : AppContribution {
    override val id = "worker.native"
    override val destinations =
        listOf(
            DestinationMetadata(
                id = "worker.work",
                route = "worker/work",
                keyType = WorkScreen::class,
                order = 1400,
                role = NavigationRole.ADVANCED,
            ),
        )
}
