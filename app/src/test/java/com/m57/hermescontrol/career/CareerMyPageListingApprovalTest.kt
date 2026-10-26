package com.m57.hermescontrol.career

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerMyPageListingApprovalTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Shape returned by the server view (MyPageBotStore.view); selectors are not included.
    private val view =
        """
        {"connection_version": 3, "bot_available": true, "listing_approved": false, "listing_relearn": false,
         "new_entrance_count": 0,
         "listing_proposal": {"id": "run-1", "proposed_at": "2026-09-29T00:00:00+00:00", "listings": [
           {"id": "news", "label": "お知らせ", "count": 2, "samples": ["【重要】本選考エントリー受付開始", "1dayセミナーのご案内"],
            "items_with_link": 2, "empty_text": null, "paginated": true},
           {"id": "messages", "label": "メッセージ", "count": 0, "samples": [], "items_with_link": 0,
            "empty_text": "現在メッセージはありません", "paginated": false}]}}
        """.trimIndent()

    @Test
    fun proposalFromServerViewIsReadableWithoutSelectors() {
        val mypage = json.decodeFromString<CareerMyPage>(view)
        val listings = myPageProposedListings(mypage.listing_proposal)
        assertEquals(listOf("お知らせ", "メッセージ"), listings.map { it.label })
        assertEquals("2件（試験の完了は未確認）", myPageListingSummary(listings[0]))
        assertEquals("現在は空（「現在メッセージはありません」と表示）", myPageListingSummary(listings[1]))
        assertEquals(listOf("【重要】本選考エントリー受付開始", "1dayセミナーのご案内"), listings[0].samples)
        assertFalse(mypage.listing_approved)
    }

    @Test
    fun olderServerWithoutListingFieldsShowsNoApprovalCard() {
        val mypage = json.decodeFromString<CareerMyPage>("""{"connection_version": 2, "bot_available": true}""")
        assertNull(mypage.listing_proposal)
        assertTrue(myPageProposedListings(mypage.listing_proposal).isEmpty())
        assertEquals(0, mypage.new_entrance_count)
    }

    @Test
    fun unresolvedCandidatesStayVisibleAfterApproval() {
        val approved =
            json.decodeFromString<CareerMyPage>(
                """{"connection_version": 4, "bot_available": true, "listing_approved": true,
                   "listing_unresolved": {"candidates": 1, "unreviewed_pages": 3}}""",
            )
        assertEquals(
            "確認できなかった候補 1件・未確認のページ 3件（承認しても、これらは確認しません）",
            myPageUnresolvedNotice(approved.listing_unresolved, true),
        )
        assertEquals(
            "確認できなかった候補 1件・未確認のページ 3件（これらは確認していません）",
            myPageUnresolvedNotice(approved.listing_unresolved, false),
        )
        assertTrue(myPageListingSectionVisible(approved))
        val resolved =
            json.decodeFromString<CareerMyPage>(
                """{"connection_version": 5, "listing_approved": true, "listing_unresolved": null}""",
            )
        assertNull(myPageUnresolvedNotice(resolved.listing_unresolved, false))
        assertFalse(myPageListingSectionVisible(resolved))
        assertTrue(companyCollectionReason("listing_unresolved")!!.contains("確認対象に含まれていません"))
    }

    @Test
    fun newEntranceIsANoticeNotAFailure() {
        assertNull(myPageEntranceNotice(0))
        assertTrue(myPageEntranceNotice(2)!!.contains("新しい入口が2件"))
        assertTrue(myPageEntranceNotice(1)!!.contains("完了扱い"))
        assertEquals("確認する一覧の承認待ちです。Botの提案を確認してください。", companyCollectionReason("listing_approval_required"))
    }

    @Test
    fun partialTrialCannotLookLikeACompleteCount() {
        val mypage =
            json.decodeFromString<CareerMyPage>(
                view.replace(
                    "\"count\": 2",
                    "\"count\": 2, \"trial_state\": \"partial\", \"trial_reason\": \"pagination_limit\"",
                ),
            )
        assertEquals(
            "2件取得・試験は途中まで（確認できるページ数の上限）",
            myPageListingSummary(myPageProposedListings(mypage.listing_proposal)[0]),
        )
        val completed =
            myPageProposedListings(
                mypage.listing_proposal,
            )[0].copy(trialState = "complete", trialReason = null)
        assertEquals("2件・次のページ以降も確認", myPageListingSummary(completed))
    }

    @Test
    fun approvedScopesHideUnsafeRelearnAlsoWithOlderServer() {
        val old = json.decodeFromString<CareerMyPage>("""{"listing_approved": true, "new_entrance_count": 1}""")
        assertFalse(myPageCanRelearn(old))
        assertFalse(myPageCanApproveProposal(old))
        assertTrue(myPageRelearnBlockedNotice(old)!!.contains("現在利用できません"))
        assertFalse(myPageEntranceNotice(1, myPageCanRelearn(old))!!.contains("学習し直してください"))
        assertTrue(myPageListingSectionVisible(old))
        val initial = json.decodeFromString<CareerMyPage>(view)
        assertTrue(myPageCanRelearn(initial))
        assertTrue(myPageCanApproveProposal(initial))
    }

    @Test
    fun serverCapabilitiesAndIndividualBrokenScopesAreRespected() {
        val protected =
            json.decodeFromString<CareerMyPage>(
                """{"listing_approved": false, "listing_relearn_allowed": false,
                   "listing_proposal_approval_allowed": false}""",
            )
        assertFalse(myPageCanRelearn(protected))
        assertFalse(myPageCanApproveProposal(protected))
        val broken =
            json.decodeFromString<CareerMyPage>(
                """{"listing_approved": true, "scopes": [{"reason": "listing_controls_changed"}]}""",
            )
        assertTrue(myPageListingSectionVisible(broken))
        assertTrue(companyCollectionReason("list_unsettled")!!.contains("保存済みの案内は保持"))
        assertTrue(companyCollectionReason("listing_retry_stopped")!!.contains("自動再試行を停止"))
    }

    @Test
    fun stoppedRetryDoesNotPromiseAScheduledRelearn() {
        val stopped =
            json.decodeFromString<CareerMyPage>(
                """{"listing_relearn": true, "listing_retry_state": "stopped"}""",
            )
        assertNull(myPageRelearnPendingNotice(stopped))
        assertTrue(myPageCanRelearn(stopped))
        assertTrue(myPageListingSectionVisible(stopped))
        val backoff = stopped.copy(listing_retry_state = "backoff", listing_retry_after = "2026-10-02T00:00:00+00:00")
        assertTrue(myPageRelearnPendingNotice(backoff)!!.contains("再試行を待っています"))
        assertNull(myPageRelearnPendingNotice(stopped.copy(listing_retry_state = "blocked")))
    }
}
