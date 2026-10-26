package com.m57.hermescontrol.career

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerMyPageResultTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun oldServerViewKeepsMissingCoverageUnknown() {
        val connection = json.decodeFromString<CareerMyPage>("""{"status":{"state":"updated"},"supported":true}""")

        assertTrue(connection.scopes.isEmpty())
        assertTrue(connection.unconfirmed.isEmpty())
        assertNull(connection.collection_state)
        assertNull(connection.analysis_state)
        assertFalse(connection.analysis_resumable)
        assertNull(myPageProgressCounts(connection.status))
    }

    @Test
    fun savedViewRetainsPendingAnalysisAndUnconfirmedScopes() {
        val connection =
            json.decodeFromString<CareerMyPage>(
                """
                {
                  "status":{"state":"partial","confirmed_count":2,"unconfirmed_count":1,"pending_analysis_count":2},
                  "scopes":[{"label":"説明会","state":"partial"}],
                  "unconfirmed":[{"label":"会社説明会","reason":"detail_loading"}],
                  "collection_state":"partial",
                  "analysis_state":"pending",
                  "analysis_resumable":true
                }
                """.trimIndent(),
            )

        assertEquals("本文確認 2 件 · 未確認 1 件 · 解析待ち 2 件", myPageProgressCounts(connection.status))
        assertEquals("説明会：未確認の箇所があります", myPageScopeLines(connection).single())
        assertEquals("詳細本文の表示完了を確認できませんでした", myPageUnconfirmedReason(connection.unconfirmed.single().text("reason")))
        assertEquals("pending", connection.analysis_state)
        assertTrue(connection.analysis_resumable)
    }

    @Test
    fun unknownScopeAndInvalidCountsNeverBecomeConfirmedOrZero() {
        val connection =
            CareerMyPage(
                scopes =
                    listOf(
                        buildJsonObject {
                            put("label", "お知らせ")
                            put("state", "future_state")
                        },
                    ),
                status =
                    buildJsonObject {
                        put("confirmed_count", "unknown")
                        put("unconfirmed_count", -1)
                    },
            )

        assertEquals("お知らせ：確認状況は未確認です", myPageScopeLines(connection).single())
        assertNull(myPageProgressCounts(connection.status))
    }

    @Test
    fun verifiedEmptyScopeAndUnconfirmedPaginationHaveDifferentLabels() {
        val connection =
            CareerMyPage(
                scopes =
                    listOf(
                        buildJsonObject {
                            put("label", "お知らせ")
                            put("state", "empty")
                        },
                    ),
            )

        assertEquals("お知らせ：案内がないことを確認済み", myPageScopeLines(connection).single())
        assertEquals("続きのページは未確認です", myPageUnconfirmedReason("pagination_unresolved"))
        assertEquals("同名の案内を区別できませんでした", myPageUnconfirmedReason("listing_identity_ambiguous"))
    }

    @Test
    fun historyAcceptsSavedJsonStringAndRetainsTheActualFailureReason() {
        val run =
            buildJsonObject {
                put("state", "partial")
                put("result", """{"reason":"model_not_configured","phase":"paused","pending_analysis_count":3}""")
            }
        val result = requireNotNull(myPageHistoryProgress(run))

        assertEquals("model_not_configured", result.text("reason"))
        assertEquals("一部確認", myPageHistoryStateLabel(run.text("state")))
        assertEquals("解析待ち 3 件", myPageProgressCounts(result))
        assertTrue(companyCollectionReason(result.text("reason")).orEmpty().contains("AIが未設定"))
    }

    @Test
    fun unreadableHistoryCannotInventACompletedResult() {
        assertNull(myPageHistoryProgress(buildJsonObject { put("result", "not json") }))
        assertNull(myPageHistoryProgress(buildJsonObject { put("result", JsonPrimitive("x".repeat(128001))) }))
        assertNull(myPageHistoryProgress(buildJsonObject { put("result", "[]") }))
        assertEquals("状態未確認", myPageHistoryStateLabel(null))
        assertEquals("確認失敗", myPageHistoryStateLabel("failed"))
    }

    @Test
    fun currentHistoryObjectAndQueuedStateAreSupported() {
        val result = buildJsonObject { put("phase", "queued") }

        assertEquals(result, myPageHistoryProgress(buildJsonObject { put("result", result) }))
        assertEquals("順番待ち", myPagePhaseLabel("queued"))
        assertEquals("順番待ち", myPageHistoryStateLabel("queued"))
        assertEquals("本文を取得中", myPagePhaseLabel("collecting"))
        assertNull(myPagePhaseLabel("future_phase"))
    }

    @Test
    fun knownModelPriceAndAuthenticationFailuresDoNotUseGenericConnectionAdvice() {
        assertTrue(companyCollectionReason("model_not_configured").orEmpty().contains("AIが未設定"))
        assertTrue(companyCollectionReason("pricing_unverified").orEmpty().contains("API単価"))
        assertTrue(companyCollectionReason("model_auth_required").orEmpty().contains("AIの接続・認証"))
        assertTrue(companyCollectionReason("analysis_unresolved").orEmpty().contains("解析が完了していません"))
        assertTrue(companyCollectionReason("detail_unread").orEmpty().contains("本文を確認できません"))
    }

    @Test
    fun unknownReasonPreservesSavedInformationWithoutBlamingConnectionSettings() {
        val label = companyCollectionReason("future_server_reason").orEmpty()

        assertTrue(label.contains("保存済みの情報は保持"))
        assertTrue(label.contains("確認履歴"))
        assertFalse(label.contains("接続設定"))
        assertNull(companyCollectionReason(null))
        assertNull(companyCollectionReason(""))
        assertEquals(JsonObject(emptyMap()), CareerMyPage().status)
    }
}
