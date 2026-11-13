package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Launch update check (issue #890): the once-per-version guard, the cache
 * write and the persisted latest tag — now through the shared source policy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateNoticeManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val currentVersion = "1.21.0"
    private val pkg = "com.m57.hermescontrol"
    private val signer = "a".repeat(64)
    private val sha = "0123456789abcdef" + "1".repeat(48)
    private val inspector = FakeApkInspector(ApkIdentity(pkg, 1, "1.0-dev", setOf(signer)), null)
    private lateinit var scopes: FakeUpdateScopes

    private fun upstreamInfo(tag: String = "v1.22.0"): UpdateInfo =
        UpdateInfo(
            tagName = tag,
            assets =
                listOf(
                    UpdateInfo.Asset(
                        "hermes-mobile-$tag.apk",
                        12345678L,
                        "https://example.com/hermes-mobile.apk",
                        "sha256:$sha",
                    ),
                ),
        )

    private fun serverRelease() =
        ServerRelease("v2-${sha.take(16)}", pkg, 2, "1.0.2", sha, 10, listOf(signer), "releases/v2-${sha.take(16)}/apk")

    private fun launch(
        checker: AppUpdateChecker,
        now: Long = System.currentTimeMillis(),
    ) = UpdateNoticeManager.checkOnLaunch(checker, currentVersion, testDispatcher, now, inspector, scopes, pkg)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppUpdateCache.reset()
        UpdateNoticeManager.enabled = true
        scopes = FakeUpdateScopes()
        mockkObject(AuthManager)
        every { AuthManager.getUpdateCheckDoneForVersion() } returns null
        every { AuthManager.setUpdateCheckDoneForVersion(any()) } returns Unit
        every { AuthManager.getLastKnownLatestTag() } returns null
        every { AuthManager.setLastKnownLatestTag(any()) } returns Unit
        every { AuthManager.getLastUpdateCheckTimestamp() } returns 0L
        every { AuthManager.setLastUpdateCheckTimestamp(any()) } returns Unit
        every { AuthManager.getDismissedUpdateTag() } returns null
    }

    @After
    fun tearDown() {
        UpdateNoticeManager.enabled = false
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun checkOnLaunch_connectedServerResultIsCachedAndBound() =
        runTest {
            val checker = mockk<AppUpdateChecker>()
            coEvery { checker.fetchServerLatest(any()) } returns ServerLatest.Release(serverRelease())

            launch(checker)
            advanceUntilIdle()

            val state = AppUpdateCache.state.value as AppUpdateState.UpdateAvailable
            assertEquals(TEST_SERVER_SCOPE, state.candidate.scope)
            coVerify(exactly = 0) { checker.fetchLatestRelease() }
            verify(exactly = 1) { AuthManager.setLastKnownLatestTag("1.0.2") }
            assertEquals("1.0.2", UpdateNoticeManager.noticeTag(currentVersion, scopes))
        }

    @Test
    fun checkOnLaunch_signedOutServerDoesNothingAndNeverFallsBack() =
        runTest {
            scopes.serverScope = null
            val checker = mockk<AppUpdateChecker>()

            launch(checker)
            advanceUntilIdle()

            coVerify(exactly = 0) { checker.fetchServerLatest(any()) }
            coVerify(exactly = 0) { checker.fetchLatestRelease() }
            assertEquals(AppUpdateState.Idle, AppUpdateCache.state.value)
        }

    @Test
    fun checkOnLaunch_skipsWhenAlreadyCheckedWithin24Hours() =
        runTest {
            val now = 1000000000000L
            every { AuthManager.getUpdateCheckDoneForVersion() } returns currentVersion
            every { AuthManager.getLastUpdateCheckTimestamp() } returns now - 1000L
            val checker = mockk<AppUpdateChecker>()

            launch(checker, now)
            advanceUntilIdle()

            coVerify(exactly = 0) { checker.fetchServerLatest(any()) }
            verify(exactly = 0) { AuthManager.setUpdateCheckDoneForVersion(currentVersion) }
        }

    @Test
    fun checkOnLaunch_upstreamExplicitSourceUsesDigestedRelease() =
        runTest {
            scopes.selected = UpdateSource.UPSTREAM_GITHUB
            val checker = mockk<AppUpdateChecker>()
            coEvery { checker.fetchLatestRelease() } returns upstreamInfo()

            launch(checker)
            advanceUntilIdle()

            assertTrue(AppUpdateCache.state.value is AppUpdateState.UpdateAvailable)
            coVerify(exactly = 0) { checker.fetchServerLatest(any()) }
        }

    @Test
    fun checkOnLaunch_failureLeavesCacheIdleButMarksDone() =
        runTest {
            val checker = mockk<AppUpdateChecker>()
            coEvery { checker.fetchServerLatest(any()) } throws IOException("boom")

            launch(checker)
            advanceUntilIdle()

            assertEquals(AppUpdateState.Idle, AppUpdateCache.state.value)
            verify(exactly = 1) { AuthManager.setUpdateCheckDoneForVersion(currentVersion) }
        }

    @Test
    fun noticeTag_ignoresServerResultFromAnotherConnection() =
        runTest {
            val checker = mockk<AppUpdateChecker>()
            coEvery { checker.fetchServerLatest(any()) } returns ServerLatest.Release(serverRelease())
            launch(checker)
            advanceUntilIdle()

            scopes.serverScope = TEST_SERVER_SCOPE.copy(baseUrl = "http://100.64.0.9:9119/")
            every { AuthManager.getLastKnownLatestTag() } returns "1.0.2"
            assertNull(UpdateNoticeManager.noticeTag(currentVersion, scopes))
        }

    @Test
    fun noticeTag_persistedTagOnlyForUpstreamAndRespectsDismissal() {
        every { AuthManager.getLastKnownLatestTag() } returns "v1.22.0"
        assertNull(UpdateNoticeManager.noticeTag(currentVersion, scopes))
        scopes.selected = UpdateSource.UPSTREAM_GITHUB
        assertEquals("v1.22.0", UpdateNoticeManager.noticeTag(currentVersion, scopes))
        every { AuthManager.getDismissedUpdateTag() } returns "v1.22.0"
        assertNull(UpdateNoticeManager.noticeTag(currentVersion, scopes))
    }

    @Test
    fun disabled_makesNoNetworkCallAndNoNotice() =
        runTest {
            UpdateNoticeManager.enabled = false
            val checker = mockk<AppUpdateChecker>()
            launch(checker)
            advanceUntilIdle()
            coVerify(exactly = 0) { checker.fetchServerLatest(any()) }
            every { AuthManager.getLastKnownLatestTag() } returns "v1.22.0"
            assertNull(UpdateNoticeManager.noticeTag(currentVersion, scopes))
        }
}
