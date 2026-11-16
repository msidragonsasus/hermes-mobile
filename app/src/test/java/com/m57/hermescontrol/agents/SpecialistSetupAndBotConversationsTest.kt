package com.m57.hermescontrol.agents

import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SpecialistSetupDraft
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.SpecialistApiOutcome
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.ui.bots.isValidSpecialistId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpecialistSetupAndBotConversationsTest {
    private val activeProfileFlow = MutableStateFlow<String?>("default")

    @Before
    fun setUp() {
        mockkObject(AuthManager)
        every { AuthManager.activeProfileId } returns activeProfileFlow
        mockkObject(ProfileSwitchCoordinator)
        mockkObject(NavigationController)
        every { NavigationController.navigateTo(any()) } returns Unit
        activeProfileFlow.value = "default"
        NavigationController.consumePendingSpecialistSetup("scout")
        NavigationController.consumePendingChatNavigation()
    }

    @After
    fun tearDown() {
        NavigationController.consumePendingChatNavigation()
        unmockkObject(NavigationController)
        unmockkObject(ProfileSwitchCoordinator)
        unmockkObject(AuthManager)
    }

    @Test
    fun specialistIdValidationRejectsDefaultBlankAndNonAsciiRules() {
        assertFalse(isValidSpecialistId(""))
        assertFalse(isValidSpecialistId("default"))
        assertFalse(isValidSpecialistId("Default"))
        assertFalse(isValidSpecialistId("bad space"))
        assertFalse(isValidSpecialistId("日本語bot"))
        assertTrue(isValidSpecialistId("research_bot-1"))
    }

    @Test
    fun setupReducerPreservesUserEditsOn409ConflictAndHonorsNextSessionBoundary() {
        val initialSnapshot =
            SpecialistSetupSnapshot(
                name = "scout",
                setup_completed = false,
                revision = 0,
                questions = listOf("Role?"),
                available_skills = listOf("web-search", "git"),
                activation = "awaiting_confirmation",
            )
        var state = SpecialistSetupReducer.onSnapshotLoaded(SpecialistSetupState(), initialSnapshot)
        state =
            state.copy(
                roleInput = "Track AI papers",
                sourcesInput = "arxiv.org\nhuggingface.co",
                selectedSkills = setOf("web-search"),
                autonomyInput = "Read-only indexing",
                routinesInput = "Weekday morning digest",
            )

        val conflictState =
            SpecialistSetupReducer.onOutcome(
                state,
                SpecialistApiOutcome.Conflict("stale_setup_draft", "Draft out of date"),
                isConfirmAction = true,
            )
        assertTrue(conflictState.staleConflict)
        assertNull(conflictState.errorMessage)
        assertEquals("Track AI papers", conflictState.roleInput)

        val reloadedWithServerDraft =
            SpecialistSetupSnapshot(
                name = "scout",
                setup_completed = false,
                revision = 1,
                available_skills = listOf("web-search"),
                draft =
                    SpecialistSetupDraft(
                        id = "d-server",
                        description = "Old server description",
                        sources = listOf("old.example"),
                        skills = listOf("web-search"),
                        autonomy = "Old autonomy",
                    ),
            )
        val afterReload =
            SpecialistSetupReducer.onSnapshotLoaded(
                conflictState,
                reloadedWithServerDraft,
                preserveUserEdits = true,
            )
        assertFalse(afterReload.staleConflict)
        assertEquals("Track AI papers", afterReload.roleInput)
        assertEquals("arxiv.org\nhuggingface.co", afterReload.sourcesInput)

        val confirmedSnap =
            reloadedWithServerDraft.copy(
                setup_completed = true,
                revision = 1,
                activation = "next_session_boundary",
            )
        val confirmedState =
            SpecialistSetupReducer.onOutcome(
                afterReload,
                SpecialistApiOutcome.Success(confirmedSnap),
                isConfirmAction = true,
                preserveEditsOnReload = true,
            )
        assertTrue(confirmedState.snapshot?.setup_completed == true)
        assertTrue(confirmedState.snapshot?.isNextSessionBoundaryActivation == true)
    }

    @Test
    fun setupReducer404AndUncertainTransportDoNotFakeSuccess() {
        val base = SpecialistSetupState(confirmRequestId = "stable-req-1")
        val unsupported =
            SpecialistSetupReducer.onOutcome(
                base,
                SpecialistApiOutcome.Unsupported("specialist_state_missing"),
                isConfirmAction = false,
            )
        assertTrue(unsupported.missingOnServer)
        assertNull(unsupported.snapshot)

        val uncertain =
            SpecialistSetupReducer.onOutcome(
                base,
                SpecialistApiOutcome.UncertainTransport("timeout"),
                isConfirmAction = true,
            )
        assertTrue(uncertain.uncertainConfirm)
        assertEquals("stable-req-1", uncertain.confirmRequestId)
    }

    @Test
    fun dispatchConfirmedRoleSendsAtMostOnceAndNeverReplaysWhenUnknown() {
        val draft =
            SpecialistSetupDraft(
                id = "d-1",
                description = "Monitor CVE feeds",
                sources = listOf("nvd.nist.gov"),
                skills = listOf("web-search"),
                autonomy = "Summarize without editing files",
            )
        val snap =
            SpecialistSetupSnapshot(
                name = "secbot",
                setup_completed = true,
                revision = 2,
                draft = draft,
                activation = "next_session_boundary",
            )

        // 1. Offline skips without invoking send
        var sendCalls = 0
        val offlineResult =
            SpecialistSetupReducer.dispatchConfirmedRoleIfEligible(
                state = SpecialistSetupState(),
                profileName = "secbot",
                confirmedSnapshot = snap,
                draft = draft,
                isChatConnected = false,
                canSendToCurrentChat = true,
                onSendConfirmedRoleMessage = {
                    sendCalls++
                    true
                },
            )
        assertEquals(0, sendCalls)
        assertTrue(offlineResult.ownerMessageDispatchNote?.contains("オフライン") == true)

        // 2. Unknown send result (returns false) records attempt key and NEVER replays
        var unknownCalls = 0
        val afterUnknown =
            SpecialistSetupReducer.dispatchConfirmedRoleIfEligible(
                state = SpecialistSetupState(),
                profileName = "secbot",
                confirmedSnapshot = snap,
                draft = draft,
                isChatConnected = true,
                canSendToCurrentChat = true,
                onSendConfirmedRoleMessage = {
                    unknownCalls++
                    false
                },
            )
        assertEquals(1, unknownCalls)
        val afterReplayAttempt =
            SpecialistSetupReducer.dispatchConfirmedRoleIfEligible(
                state = afterUnknown,
                profileName = "secbot",
                confirmedSnapshot = snap,
                draft = draft,
                isChatConnected = true,
                canSendToCurrentChat = true,
                onSendConfirmedRoleMessage = {
                    unknownCalls++
                    true
                },
            )
        assertEquals(1, unknownCalls)
        assertEquals(afterUnknown.confirmedRoleSentKeys, afterReplayAttempt.confirmedRoleSentKeys)

        // 3. Connected success dispatches once
        val sentMessages = mutableListOf<String>()
        val afterSent =
            SpecialistSetupReducer.dispatchConfirmedRoleIfEligible(
                state = SpecialistSetupState(),
                profileName = "secbot",
                confirmedSnapshot = snap,
                draft = draft,
                isChatConnected = true,
                canSendToCurrentChat = true,
                onSendConfirmedRoleMessage = { msg ->
                    sentMessages += msg
                    true
                },
            )
        assertEquals(1, sentMessages.size)
        assertTrue(sentMessages.single().contains("Monitor CVE feeds"))
        assertTrue("secbot:2:d-1" in afterSent.confirmedRoleSentKeys)
    }

    @Test
    fun botConversationsIsolatesSpecialistAndMainCanonicalNavigation() =
        runTest {
            coEvery { ProfileSwitchCoordinator.switchProfile("scout") } answers {
                activeProfileFlow.value = "scout"
                NetworkResult.Success(Unit)
            }
            coEvery { ProfileSwitchCoordinator.switchProfile("default") } answers {
                activeProfileFlow.value = "default"
                NetworkResult.Success(Unit)
            }

            val openedSpecialist =
                BotConversations.openSpecialist(
                    profileName = "scout",
                    canonicalSessionId = "canon-scout-1",
                    openSetupAfterLaunch = true,
                )
            assertTrue(openedSpecialist)
            coVerify(exactly = 1) { ProfileSwitchCoordinator.switchProfile("scout") }
            assertEquals("scout", NavigationController.pendingSpecialistSetupBot)
            assertNull(NavigationController.pendingSessionIdForProfile("default"))
            assertEquals("canon-scout-1", NavigationController.pendingSessionIdForProfile("scout"))

            val beforeMainReq = NavigationController.pendingMainConversationRequestId
            val returnedMain = BotConversations.openMain()
            assertTrue(returnedMain)
            coVerify(exactly = 1) { ProfileSwitchCoordinator.switchProfile("default") }
            assertNull(NavigationController.pendingSpecialistSetupBot)
            assertNull(NavigationController.pendingChatNavigation)
            assertEquals(beforeMainReq + 1, NavigationController.pendingMainConversationRequestId)
            coVerify { NavigationController.navigateTo(ChatScreen) }
        }

    @Test
    fun botConversationsAbortsWithoutLeakingSessionWhenProfileSwitchFails() =
        runTest {
            coEvery { ProfileSwitchCoordinator.switchProfile("broken") } returns
                NetworkResult.Failure(NetworkError.Connection("offline", java.io.IOException("offline")))

            val opened =
                BotConversations.openSpecialist(
                    profileName = "broken",
                    canonicalSessionId = "canon-broken",
                    openSetupAfterLaunch = true,
                )
            assertFalse(opened)
            assertNull(NavigationController.pendingChatNavigation)
            assertNull(NavigationController.pendingSpecialistSetupBot)
        }
}
