package com.m57.hermescontrol.agents

import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator

/**
 * Shared canonical navigation coordinator between Hermes Main ("default") and specialist bots.
 *
 * All specialist conversations reuse the single canonical chat engine (`ChatScreen` / `ChatViewModel`).
 * Switching profiles clears any cross-profile pending session navigation so another bot's session
 * can never be rendered under Hermes Main (or vice versa).
 */
object BotConversations {
    private var createdSetup: Pair<String, SpecialistSetupSnapshot>? = null

    internal fun serverScope(): String =
        listOf(AuthManager.baseUrlFlow.value, AuthManager.selectedProfileFlow.value.orEmpty()).joinToString("\n")

    internal fun rememberCreatedSetup(
        server: String,
        snapshot: SpecialistSetupSnapshot?,
    ) {
        createdSetup = snapshot?.takeIf { it.greeting != null }?.let { server to it }
    }

    internal fun takeCreatedSetup(profile: String): SpecialistSetupSnapshot? {
        val seed = createdSetup ?: return null
        if (seed.first != serverScope() || seed.second.name != profile) return null
        createdSetup = null
        return seed.second
    }

    /** Use roster and profile metadata for display while keeping the profile ID for routing. */
    fun label(
        profileName: String?,
        vararg knownProfiles: ProfileInfo?,
    ): String? =
        when {
            profileName.isNullOrBlank() -> {
                null
            }

            profileName == "default" -> {
                "Hermes"
            }

            else -> {
                knownProfiles.firstNotNullOfOrNull { profile ->
                    profile?.takeIf { it.name == profileName }?.let {
                        if (it.is_default ==
                            true
                        ) {
                            "Hermes"
                        } else {
                            it.effectiveTitle.takeUnless { title -> title == profileName }
                        }
                    }
                } ?: profileName
            }
        }

    /** Normalized active profile id, defaulting to `"default"`. */
    fun activeProfile(): String = AuthManager.activeProfileId.value?.takeIf { it.isNotBlank() } ?: "default"

    /** True when [profileName] names a bot other than the one currently active. */
    fun isOtherBot(profileName: String?): Boolean {
        if (profileName.isNullOrBlank()) return false
        return profileName != activeProfile()
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
        val target = profileName?.takeIf { it.isNotBlank() }
        if (isOtherBot(target)) {
            val result = ProfileSwitchCoordinator.switchProfile(requireNotNull(target))
            if (result !is NetworkResult.Success) return false
            NavigationController.clearPendingChatNavigationForMismatch(target)
        }
        NavigationController.openChatSession(sessionId, profileName = target)
        return true
    }

    /**
     * Open a specialist's dedicated canonical consultation (switching profile when needed).
     * When [canonicalSessionId] is supplied it targets that exact canonical session; otherwise
     * [ChatViewModel.handleGatewayReady] resolves the specialist's canonical `"Bot Chat"`.
     */
    suspend fun openSpecialist(
        profileName: String,
        canonicalSessionId: String? = null,
        openSetupAfterLaunch: Boolean = false,
    ): Boolean {
        if (profileName.isBlank()) return false
        val switchedNeeded = isOtherBot(profileName)
        if (switchedNeeded) {
            val result = ProfileSwitchCoordinator.switchProfile(profileName)
            if (result !is NetworkResult.Success) return false
        }
        NavigationController.clearPendingChatNavigationForMismatch(profileName)
        if (canonicalSessionId.isNullOrBlank()) NavigationController.requestMainConversation()
        if (openSetupAfterLaunch && profileName != "default") {
            NavigationController.requestSpecialistSetup(profileName)
        }
        if (!canonicalSessionId.isNullOrBlank()) {
            NavigationController.openChatSession(canonicalSessionId, profileName = profileName)
        } else {
            NavigationController.navigateTo(ChatScreen)
        }
        return true
    }

    /**
     * Return from a specialist conversation (or past session) to Hermes Main (`"default"`),
     * restoring `"default"`'s canonical `"Bot Chat"` without leaking the specialist's session.
     */
    suspend fun openMain(canonicalSessionId: String? = null): Boolean {
        if (isOtherBot("default")) {
            val result = ProfileSwitchCoordinator.switchProfile("default")
            if (result !is NetworkResult.Success) return false
        }
        NavigationController.clearPendingChatNavigationForMismatch("default")
        if (!canonicalSessionId.isNullOrBlank()) {
            NavigationController.openChatSession(canonicalSessionId, profileName = "default")
        } else {
            NavigationController.requestMainConversation()
            NavigationController.navigateTo(ChatScreen)
        }
        return true
    }
}
