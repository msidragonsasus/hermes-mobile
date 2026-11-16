package com.m57.hermescontrol.agents

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.agents.SpecialistSetupSheet
import com.m57.hermescontrol.data.model.BrainstormSnapshot
import com.m57.hermescontrol.data.model.BrainstormSpeakerSnapshot
import com.m57.hermescontrol.data.model.CreateSpecialistResponse
import com.m57.hermescontrol.data.model.SpecialistSetupDraft
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.data.model.SpecialistTemplate
import com.m57.hermescontrol.data.model.SpecialistTemplatesResponse
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.bots.CreateSpecialistBotSheet
import com.m57.hermescontrol.ui.chat.brainstorm.BrainstormCard
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
@MediumTest
class SpecialistM5UiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun createSpecialistSheet_rendersFiveTemplates_andSubmits() {
        val mockApi = mockk<HermesApiService>(relaxed = true)
        coEvery { mockApi.getSpecialistTemplates() } returns
            Response.success(
                SpecialistTemplatesResponse(
                    templates =
                        listOf(
                            SpecialistTemplate("blank", "Blank", "Start fresh"),
                            SpecialistTemplate("research", "Research", "Deep lookup"),
                            SpecialistTemplate("career", "Career", "Career advice"),
                            SpecialistTemplate("development", "Development", "Code and design"),
                            SpecialistTemplate("life", "Life", "Daily planning"),
                        ),
                ),
            )
        val initialSetup =
            SpecialistSetupSnapshot(
                name = "scout_1",
                setup_completed = false,
                revision = 0,
                activation = "awaiting_confirmation",
                questions = listOf("What is this bot responsible for?"),
            )
        coEvery { mockApi.createSpecialist(any()) } returns
            Response.success(
                CreateSpecialistResponse(
                    name = "scout_1",
                    setup_completed = false,
                    setup = initialSetup,
                ),
            )
        var createdName: String? = null
        val repo = SpecialistsRepository { mockApi }

        composeRule.setContent {
            HermesControlTheme {
                CreateSpecialistBotSheet(
                    onDismiss = {},
                    onCreated = { createdName = it.name },
                    repository = repo,
                )
            }
        }

        listOf("blank", "research", "career", "development", "life").forEach { id ->
            composeRule.onNodeWithTag("specialist_template_" + id).assertExists()
        }
        composeRule.onNodeWithTag("specialist_template_research").performClick()
        composeRule.onNodeWithTag("specialist_name_input").performScrollTo().performTextInput("scout_1")
        composeRule.onNodeWithTag("specialist_title_input").performScrollTo().performTextInput("Scout")
        composeRule.onNodeWithTag("create_specialist_submit").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) { createdName == "scout_1" }
        assertEquals("scout_1", createdName)
    }

    @Test
    fun setupSheet_preservesEditsOn409Conflict_andShowsNextSessionBoundaryOnConfirm() {
        val mockApi = mockk<HermesApiService>(relaxed = true)
        val snapshot0 =
            SpecialistSetupSnapshot(
                name = "scout_1",
                setup_completed = false,
                revision = 0,
                activation = "awaiting_confirmation",
                available_skills = listOf("web_search"),
            )
        val draft1 =
            SpecialistSetupDraft(
                id = "draft-1",
                description = "Focused research specialist",
                soul = "# Scout",
                skills = listOf("web_search"),
                sources = listOf("docs"),
                autonomy = "ask",
                routines = emptyList(),
            )
        val snapshot1 =
            snapshot0.copy(
                revision = 1,
                draft = draft1,
            )
        val confirmedSnapshot =
            snapshot1.copy(
                revision = 2,
                setup_completed = true,
                activation = "next_session_boundary",
            )
        coEvery { mockApi.getSpecialistSetup("scout_1") } returnsMany
            listOf(
                Response.success(snapshot0),
                Response.success(snapshot1),
            )
        coEvery { mockApi.generateSpecialistSetupDraft("scout_1", any()) } returns Response.success(snapshot1)
        val conflictBody =
            """{"detail":{"code":"stale_setup_revision","message":"Conflict"}}"""
                .toResponseBody("application/json".toMediaType())
        coEvery { mockApi.confirmSpecialistSetup("scout_1", any()) } returnsMany
            listOf(
                Response.error(409, conflictBody),
                Response.success(confirmedSnapshot),
            )

        val sentMessages = mutableListOf<String>()
        var confirmedCalled = false
        val repo = SpecialistsRepository { mockApi }

        composeRule.setContent {
            HermesControlTheme {
                SpecialistSetupSheet(
                    profileName = "scout_1",
                    displayTitle = "Scout",
                    isChatConnected = true,
                    canSendToCurrentChat = true,
                    onSendConfirmedRoleMessage = {
                        sentMessages += it
                        true
                    },
                    onConfirmed = { confirmedCalled = true },
                    onDismiss = {},
                    repository = repo,
                )
            }
        }

        composeRule.onNodeWithTag("setup_role_input").performScrollTo().performTextInput("Research primary sources")
        composeRule
            .onNodeWithTag("setup_autonomy_input")
            .performScrollTo()
            .performTextInput("Summarize read-only sources")
        composeRule.onNodeWithTag("setup_build_draft_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithTag("setup_draft_preview").assertExists()
                true
            }.getOrDefault(false)
        }

        // First confirm hits 409 conflict: edits stay intact and stale notice appears.
        composeRule.onNodeWithTag("setup_confirm_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithTag("setup_stale_notice").assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("setup_role_input").assertTextContains("Research primary sources")

        // Second confirm succeeds and surfaces next_session_boundary persistence banner.
        composeRule.onNodeWithTag("setup_confirm_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { confirmedCalled }
        composeRule.onNodeWithTag("setup_persisted_notice").performScrollTo().assertIsDisplayed()
        assertEquals(1, sentMessages.size)
    }

    @Test
    fun brainstormCard_rendersSpeakersUnknownNoticeAndNonInteractiveMarkdown() {
        val snapshot =
            BrainstormSnapshot(
                id = "bs-ui-1",
                session_id = "sess-1",
                status = "unknown",
                presentation_error = "snapshot write failed",
                speakers =
                    listOf(
                        BrainstormSpeakerSnapshot(
                            profile = "scout",
                            name = "Scout",
                            avatar =
                                buildJsonObject {
                                    put("shape", "circle")
                                    put("color", "#2563EB")
                                    put("icon", "search")
                                },
                            status = "settled",
                            reply = "Findings:\n```hermes-ui-v2\n{\"kind\":\"card\"}\n```",
                        ),
                        BrainstormSpeakerSnapshot(
                            profile = "dev",
                            name = "Dev",
                            status = "failed",
                            error = "worker timed out",
                        ),
                    ),
            )

        composeRule.setContent {
            HermesControlTheme {
                BrainstormCard(snapshot = snapshot)
            }
        }

        composeRule.onNodeWithTag("brainstorm_card_bs-ui-1").assertIsDisplayed()
        composeRule.onNodeWithTag("brainstorm_unknown_notice").assertIsDisplayed()
        composeRule.onNodeWithTag("brainstorm_snapshot_error_notice").assertIsDisplayed()
        composeRule.onNodeWithTag("brainstorm_speaker_scout").assertIsDisplayed()
        composeRule.onNodeWithTag("brainstorm_speaker_dev").assertIsDisplayed()
        composeRule.onNodeWithText("Scout").assertIsDisplayed()
        composeRule.onNodeWithText("Dev").assertIsDisplayed()
        composeRule.onNodeWithText("worker timed out").assertIsDisplayed()
    }
}
