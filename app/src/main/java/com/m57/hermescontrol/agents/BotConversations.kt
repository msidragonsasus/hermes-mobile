package com.m57.hermescontrol.agents

import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator

/**
 * A session id only means something inside the profile (bot) that owns it: REST and the socket
 * are scoped to the active profile. Opening another bot's conversation or run result therefore
 * switches to that bot first, then opens the session.
 */
object BotConversations {
    /** Display name of a bot: the default profile is "Hermes". */
    fun label(profileName: String?): String? =
        when {
            profileName.isNullOrBlank() -> null
            profileName == "default" -> "Hermes"
            else -> profileName
        }

    /** True when [profileName] names a bot other than the one currently active. */
    fun isOtherBot(profileName: String?): Boolean {
        if (profileName.isNullOrBlank()) return false
        val active = AuthManager.activeProfileId.value ?: "default"
        return profileName != active
    }

    /**
     * Switch to [profileName] when needed, then open [sessionId] in the chat.
     * Returns false (and opens nothing) when the switch fails, so a session is never shown
     * under the wrong bot.
     */
    suspend fun open(
        profileName: String?,
        sessionId: String,
    ): Boolean {
        if (isOtherBot(profileName)) {
            val result = ProfileSwitchCoordinator.switchProfile(requireNotNull(profileName))
            if (result !is NetworkResult.Success) return false
        }
        NavigationController.openChatSession(sessionId)
        return true
    }
}
