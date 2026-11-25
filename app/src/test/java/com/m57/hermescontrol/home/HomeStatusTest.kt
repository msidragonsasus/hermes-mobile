package com.m57.hermescontrol.home

import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.worker.WorkerSourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStatusTest {
    private fun status(
        link: LinkState = LinkState.CONNECTED,
        storage: String? = null,
        today: HomeFailure? = null,
        career: HomeFailure? = null,
        careerEnabled: Boolean = true,
        careerVisible: Boolean = true,
        approvals: WorkerSourceStatus = WorkerSourceStatus.Ok,
        routines: WorkerSourceStatus = WorkerSourceStatus.Ok,
    ) = homeStatusOf(link, storage, today, career, careerEnabled, careerVisible, approvals, routines)

    @Test
    fun storageAndAuthTakePrecedenceOverOtherActiveFailures() {
        val auth = WorkerSourceStatus.Failed(HomeFailure.AuthRequired)
        assertEquals(
            HomeStatusNotice(NoticeTone.Error, "ストレージのエラー"),
            status(storage = "ストレージのエラー", link = LinkState.OFFLINE, approvals = auth),
        )
        assertEquals(
            HomeStatusNotice(NoticeTone.Warning, "ログインが必要です。保存済みの情報を表示しています。", HomeNoticeAction.ConnectionSettings),
            status(link = LinkState.OFFLINE, today = HomeFailure.NotGated, approvals = auth),
        )
        assertTrue(status(link = LinkState.AUTH_REQUIRED, today = HomeFailure.AuthRequired)!!.coversToday)
    }

    @Test
    fun unsupportedConnectionPrecedesOfflineThenReconnectingThenServerUpdate() {
        val outdated = HomeFailure.ServerOutdated(null)
        val unavailable = HomeFailure.ServerUnavailable("storage_unavailable")
        assertEquals(
            "この接続方式では今日やること・就活を読み込めません。",
            status(link = LinkState.OFFLINE, today = HomeFailure.NotGated, career = outdated)!!.text,
        )
        assertEquals(
            "オフラインです。保存済みの情報を表示しています。",
            status(link = LinkState.RECONNECTING, today = HomeFailure.Offline, career = outdated)!!.text,
        )
        assertEquals("再接続しています…", status(link = LinkState.RECONNECTING, today = outdated, career = unavailable)!!.text)
        assertEquals(
            "今日やることの連携にはサーバーの更新が必要です。",
            status(today = outdated, career = unavailable)!!.text,
        )
    }

    @Test
    fun outdatedSourcesCoalesceAndCoverTodayOnlyWhenApplicable() {
        val outdated = HomeFailure.ServerOutdated(null)
        assertEquals(
            HomeStatusNotice(NoticeTone.Warning, "就活のホーム連携にはサーバーの更新が必要です。"),
            status(career = outdated),
        )
        assertEquals(
            HomeStatusNotice(NoticeTone.Warning, "一部の連携にはサーバーの更新が必要です。", coversToday = true),
            status(today = outdated, career = outdated),
        )
        assertFalse(status(career = outdated, today = HomeFailure.Generic)!!.coversToday)
    }

    @Test
    fun unavailableProfileShowsRetryableWarning() {
        val failure = HomeFailure.ProfileUnavailable
        val expected =
            HomeStatusNotice(
                NoticeTone.Warning,
                "接続プロファイルを確認できません。再試行するか接続設定を確認してください。",
                HomeNoticeAction.Retry,
            )

        assertEquals(expected, status(career = failure))
        assertEquals(expected.copy(coversToday = true), status(today = failure))
        assertEquals(expected, status(routines = WorkerSourceStatus.Failed(failure)))
        assertEquals(expected, status(today = HomeFailure.ServerOutdated(null), career = failure))
    }

    @Test
    fun healthyAndConnectingStatesAndMissingWorkerPluginAreQuiet() {
        assertNull(status())
        assertNull(
            status(
                link = LinkState.CONNECTING,
                approvals = WorkerSourceStatus.Unknown,
                routines = WorkerSourceStatus.Unknown,
            ),
        )
        assertNull(status(approvals = WorkerSourceStatus.Unavailable))
        assertNull(status(career = HomeFailure.AuthRequired, careerVisible = false))
        assertNull(status(career = HomeFailure.Offline, careerEnabled = false))
    }

    @Test
    fun careerOnlyErrorsUseTheExistingCopyAndRetryAction() {
        val expected =
            HomeStatusNotice(
                NoticeTone.Warning,
                "就活情報を読み直せません。前回の情報を表示しています。",
                HomeNoticeAction.Retry,
            )
        assertEquals(expected, status(career = HomeFailure.Generic))
        assertEquals(expected, status(career = HomeFailure.ServerUnavailable("gateway_unavailable")))
    }
}
