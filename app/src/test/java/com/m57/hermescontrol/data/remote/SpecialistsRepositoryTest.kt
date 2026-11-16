package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.CreateSpecialistRequest
import com.m57.hermescontrol.data.model.SpecialistSetupConfirmRequest
import com.m57.hermescontrol.data.model.SpecialistSetupDraftRequest
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class SpecialistsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApiService
    private lateinit var repo: SpecialistsRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(OkHttpProvider.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(HermesApiService::class.java)
        repo = SpecialistsRepository { api }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchCapabilitiesDistinguishesSupportedLegacyAndUnavailable() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"version":1,"setup":true,"brainstorm_results":true,"group_retirement_ready":false}"""),
            )
            val supported = repo.fetchCapabilities() as SpecialistCapabilitiesState.Supported
            assertTrue(supported.capabilities.setup)
            assertTrue(supported.capabilities.brainstorm_results)
            assertFalse(supported.capabilities.group_retirement_ready)

            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setBody("""{"detail":"Not Found"}"""),
            )
            val legacy = repo.fetchCapabilities()
            assertTrue(legacy is SpecialistCapabilitiesState.LegacyUnsupported)

            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setBody("""{"detail":{"code":"gateway_busy"}}"""),
            )
            val unavailable = repo.fetchCapabilities()
            assertTrue(unavailable is SpecialistCapabilitiesState.Unavailable)
        }

    @Test
    fun fetchTemplatesAndCreateSpecialistMatchContract() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "templates": [
                            {"id":"blank","title":"Blank","description":"Empty specialist"},
                            {"id":"research","title":"Research","description":"Research assistant"},
                            {"id":"career","title":"Career","description":"Career advisor"},
                            {"id":"development","title":"Development","description":"Engineering specialist"},
                            {"id":"life","title":"Life","description":"Personal advisor"}
                          ]
                        }
                        """.trimIndent(),
                    ),
            )
            val templates = repo.fetchTemplates() as SpecialistApiOutcome.Success
            assertEquals(listOf("blank", "research", "career", "development", "life"), templates.data.map { it.id })

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "name": "researcher",
                          "setup_completed": false,
                          "setup": {
                            "name": "researcher",
                            "setup_completed": false,
                            "revision": 0,
                            "draft": null,
                            "questions": ["Role?", "Sources & skills?", "Autonomy?"],
                            "available_skills": ["web-search", "notes"],
                            "activation": "awaiting_confirmation"
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            val created =
                repo.createSpecialist(
                    CreateSpecialistRequest(
                        name = "researcher",
                        title = "Researcher",
                        avatar = BotAvatarMeta(shape = "circle", color = "#2563EB", icon = "science"),
                        template_id = "research",
                        request_id = "req-uuid-1",
                    ),
                ) as SpecialistApiOutcome.Success
            assertEquals("researcher", created.data.name)
            assertFalse(created.data.setup_completed)
            assertEquals(0, created.data.setup?.revision)

            val recorded = server.takeRequest()
            assertEquals("/api/specialists/templates", recorded.path)
            val createReq = server.takeRequest()
            assertEquals("POST", createReq.method)
            assertEquals("/api/specialists", createReq.path)
            val rawBody = createReq.body.readUtf8()
            assertTrue(rawBody.contains(""""request_id":"req-uuid-1""""))
            assertTrue(rawBody.contains(""""template_id":"research""""))
        }

    @Test
    fun setupDraftConfirmAndErrorsPreserveStructuredOutcomes() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setBody("""{"detail":{"code":"profile_already_exists"}}"""),
            )
            val conflict =
                repo.createSpecialist(
                    CreateSpecialistRequest(name = "dup", title = "Dup", request_id = "r1"),
                ) as SpecialistApiOutcome.Conflict
            assertEquals("profile_already_exists", conflict.code)

            server.enqueue(
                MockResponse()
                    .setResponseCode(422)
                    .setBody("""{"detail":{"code":"invalid_specialist_request"}}"""),
            )
            val invalid =
                repo.saveDraft(
                    "researcher",
                    SpecialistSetupDraftRequest(expected_revision = 0, role = "role", autonomy = "auto"),
                ) as SpecialistApiOutcome.Validation
            assertEquals("invalid_specialist_request", invalid.code)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "name": "researcher",
                          "setup_completed": true,
                          "revision": 1,
                          "activation": "next_session_boundary",
                          "available_skills": ["web-search"],
                          "questions": [],
                          "draft": {
                            "id": "draft-abc",
                            "description": "Paper scout",
                            "soul": "# Researcher",
                            "skills": ["web-search"],
                            "autonomy": "Read-only search",
                            "routines": ["Daily digest"],
                            "sources": ["arxiv.org"]
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            val confirmed =
                repo.confirmDraft(
                    "researcher",
                    SpecialistSetupConfirmRequest(
                        expected_revision = 1,
                        draft_id = "draft-abc",
                        request_id = "confirm-uuid-1",
                    ),
                ) as SpecialistApiOutcome.Success
            assertTrue(confirmed.data.setup_completed)
            assertTrue(confirmed.data.isNextSessionBoundaryActivation)
            assertEquals(listOf("arxiv.org"), confirmed.data.draft?.sources)
        }

    @Test
    fun brainstormEndpointsScopeProfileAndSessionAndDecodeSpeakers() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "items": [
                            {
                              "id": "bs-1",
                              "session_id": "sess-main",
                              "status": "partial",
                              "summary_input": "Raw collected opinions",
                              "speakers": [
                                {
                                  "profile": "scout",
                                  "name": "Scout",
                                  "avatar": {"shape": "hexagon", "color": "#2563EB", "icon": "science"},
                                  "status": "settled",
                                  "reply": "Evidence A"
                                },
                                {
                                  "profile": "critic",
                                  "name": "Critic",
                                  "status": "failed",
                                  "error": "Timeout"
                                }
                              ]
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )
            val list =
                repo.listBrainstorms(
                    sessionId = "sess-main",
                    profile = "default",
                ) as SpecialistApiOutcome.Success
            assertEquals(1, list.data.size)
            val item = list.data.single()
            assertEquals("bs-1", item.id)
            assertEquals("partial", item.status)
            assertEquals("hexagon", item.speakers[0].resolvedAvatar()?.shape)
            assertEquals("failed", item.speakers[1].status)
            assertEquals("Timeout", item.speakers[1].error)
            assertNotNull(item.summary_input)

            val recorded = server.takeRequest()
            assertEquals("/api/specialists/brainstorms?profile=default&session_id=sess-main", recorded.path)
        }
}
