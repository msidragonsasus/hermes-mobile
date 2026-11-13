package com.m57.hermescontrol.ui.settings

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.update.ApkIdentity
import com.m57.hermescontrol.data.update.AppUpdateCache
import com.m57.hermescontrol.data.update.AppUpdateChecker
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.DownloadedApk
import com.m57.hermescontrol.data.update.FakeApkInspector
import com.m57.hermescontrol.data.update.FakeUpdateScopes
import com.m57.hermescontrol.data.update.ServerLatest
import com.m57.hermescontrol.data.update.ServerRelease
import com.m57.hermescontrol.data.update.TEST_SERVER_SCOPE
import com.m57.hermescontrol.data.update.UpdateException
import com.m57.hermescontrol.data.update.UpdateFailure
import com.m57.hermescontrol.data.update.UpdateInfo
import com.m57.hermescontrol.data.update.UpdateSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * AppUpdateViewModel (issue #867 + connected-server updates): source-bound
 * checks without upstream fallback, verified install only, cancellation and
 * stale-connection guards, and permission-resume re-verification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val pkg = "com.m57.hermescontrol"
    private val signer = "a".repeat(64)
    private val apkBytes = ByteArray(4096) { (it % 13).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val releaseId = "v2-${apkSha.take(16)}"

    private lateinit var app: Application
    private lateinit var packageManager: PackageManager
    private lateinit var checker: AppUpdateChecker
    private lateinit var scopes: FakeUpdateScopes
    private lateinit var inspector: FakeApkInspector
    private lateinit var cacheDir: File
    private val launched = mutableListOf<Intent>()
    private val intentMock = mockk<Intent>()

    private fun release(signers: List<String> = listOf(signer)) =
        ServerRelease(
            releaseId,
            pkg,
            2,
            "1.0.2",
            apkSha,
            apkBytes.size.toLong(),
            signers,
            "releases/$releaseId/apk",
            "Fix",
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppUpdateCache.reset()
        mockkObject(AuthManager)
        every { AuthManager.setUpdateCheckDoneForVersion(any()) } returns Unit
        every { AuthManager.setLastKnownLatestTag(any()) } returns Unit
        every { AuthManager.setLastUpdateCheckTimestamp(any()) } returns Unit
        every { AuthManager.setDismissedUpdateTag(any()) } returns Unit

        cacheDir = Files.createTempDirectory("vm-update").toFile()
        app = mockk(relaxed = true)
        every { app.cacheDir } returns cacheDir
        every { app.packageName } returns pkg
        packageManager = mockk(relaxed = true)
        every { app.packageManager } returns packageManager
        every { packageManager.canRequestPackageInstalls() } returns true
        every { app.startActivity(capture(launched)) } returns Unit
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk<Uri>()

        scopes = FakeUpdateScopes()
        inspector =
            FakeApkInspector(
                installedIdentity = ApkIdentity(pkg, 1, "1.0-dev", setOf(signer)),
                archiveIdentity = ApkIdentity(pkg, 2, "1.0.2", setOf(signer)),
            )
        checker = mockk()
        coEvery { checker.fetchServerLatest(any()) } returns ServerLatest.Release(release())
        coEvery { checker.fetchLatestRelease() } returns null
        coEvery { checker.download(any(), any(), any()) } coAnswers {
            val dest = secondArg<File>()
            dest.writeBytes(apkBytes)
            DownloadedApk(dest, apkSha, apkBytes.size.toLong())
        }
    }

    private val viewModels = mutableListOf<AppUpdateViewModel>()

    @After
    fun tearDown() {
        viewModels.forEach { it.clearForTest() }
        unmockkAll()
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    private fun createViewModel() =
        AppUpdateViewModel(app, checker, "1.0-dev", testDispatcher, inspector, scopes, pkg) { intentMock }
            .also { viewModels += it }

    /** APK files (incl. `.part`) currently in the updater's cache directory. */
    private fun apkFiles(): List<File> = File(cacheDir, "updates").listFiles()?.toList().orEmpty()

    /** Download fake that writes [bytes] to the attempt's own file, then waits [millis]. */
    private fun slowDownload(
        millis: Long,
        bytes: ByteArray = apkBytes,
    ) {
        coEvery { checker.download(any(), any(), any()) } coAnswers {
            val dest = secondArg<File>()
            dest.writeBytes(bytes)
            kotlinx.coroutines.delay(millis)
            DownloadedApk(dest, apkSha, bytes.size.toLong())
        }
    }

    // ── Attempt lifecycle ───────────────────────────────────────────────

    @Test
    fun onCleared_cancelsDownloadDeletesOwnedFileAndNeverInstalls() =
        runTest {
            slowDownload(10_000)
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)
            assertEquals(1, apkFiles().size)

            vm.clearForTest()
            advanceUntilIdle()

            assertTrue(apkFiles().isEmpty())
            assertTrue(launched.isEmpty())
        }

    @Test
    fun duplicateStartWhileDownloadingIsIgnored() =
        runTest {
            slowDownload(1_000)
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)
            vm.startUpdate()
            vm.resumeInstallAfterPermission()
            advanceUntilIdle()

            coVerify(exactly = 1) { checker.download(any(), any(), any()) }
            assertEquals(1, launched.size)
        }

    @Test
    fun cancelledAttemptsLateCleanupNeverTouchesTheNewerAttempt() =
        runTest {
            val dests = mutableListOf<File>()
            coEvery { checker.download(any(), any(), any()) } coAnswers {
                val dest = secondArg<File>()
                dests += dest
                dest.writeBytes(apkBytes)
                kotlinx.coroutines.delay(if (dests.size == 1) 10_000 else 500)
                DownloadedApk(dest, apkSha, apkBytes.size.toLong())
            }
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)
            vm.cancelDownload()
            vm.startUpdate()
            advanceUntilIdle()

            assertEquals(2, dests.size)
            assertTrue("attempts own distinct files", dests[0] != dests[1])
            assertFalse(dests[0].exists())
            assertTrue("newer attempt's file survives the old attempt's cleanup", dests[1].exists())
            assertEquals(AppUpdateState.InstallerOpened("1.0.2"), vm.state.value)
            assertEquals(listOf(dests[1]), inspector.inspected)
            assertEquals(1, launched.size)
        }

    @Test
    fun twoViewModelsNeverDeleteEachOthersFiles() =
        runTest {
            slowDownload(10_000)
            val about = createViewModel()
            val chat = createViewModel()
            advanceUntilIdle()
            about.startUpdate()
            chat.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)
            assertEquals(2, apkFiles().size)

            about.cancelDownload()
            assertEquals("chat attempt keeps its file", 1, apkFiles().size)
            advanceUntilIdle()
            coVerify(exactly = 2) { checker.download(any(), any(), any()) }
            assertEquals(AppUpdateState.InstallerOpened("1.0.2"), chat.state.value)
            assertEquals(1, launched.size)
        }

    // ── Check ───────────────────────────────────────────────────────────

    @Test
    fun check_usesConnectedServerAndBindsCandidateToScope() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.state.value as AppUpdateState.UpdateAvailable
            assertEquals(UpdateSource.CONNECTED_SERVER, state.candidate.source)
            assertEquals(TEST_SERVER_SCOPE, state.candidate.scope)
            assertEquals(
                "http://100.64.0.2:9119/api/plugins/mobile_updates/releases/$releaseId/apk",
                state.candidate.downloadUrl,
            )
            coVerify(exactly = 0) { checker.fetchLatestRelease() }
            assertEquals(state, AppUpdateCache.state.value)
        }

    @Test
    fun check_whenNotSignedIn_failsWithoutUpstreamFallback() =
        runTest {
            scopes.serverScope = null
            val vm = createViewModel()
            advanceUntilIdle()

            assertEquals(AppUpdateState.Error(UpdateFailure.NOT_CONNECTED), vm.state.value)
            coVerify(exactly = 0) { checker.fetchLatestRelease() }
            coVerify(exactly = 0) { checker.fetchServerLatest(any()) }
        }

    @Test
    fun check_serverUnavailable_reportsServerFailureWithoutUpstreamFallback() =
        runTest {
            for (failure in listOf(UpdateFailure.SERVER_UPDATES_MISSING, UpdateFailure.SERVER_UPDATES_DISABLED)) {
                coEvery { checker.fetchServerLatest(any()) } throws UpdateException(failure)
                val vm = createViewModel()
                advanceUntilIdle()
                assertEquals(AppUpdateState.Error(failure), vm.state.value)
            }
            coEvery { checker.fetchServerLatest(any()) } throws IOException("offline")
            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Error(UpdateFailure.NETWORK), vm.state.value)
            coVerify(exactly = 0) { checker.fetchLatestRelease() }
        }

    @Test
    fun check_differentSignerIsNeverOffered() =
        runTest {
            coEvery { checker.fetchServerLatest(any()) } returns ServerLatest.Release(release(listOf("b".repeat(64))))
            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Error(UpdateFailure.SIGNER_MISMATCH), vm.state.value)
        }

    @Test
    fun check_noReleaseAndUpToDate() =
        runTest {
            coEvery { checker.fetchServerLatest(any()) } returns ServerLatest.NoRelease
            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Error(UpdateFailure.NO_RELEASE), vm.state.value)

            inspector.installedIdentity = ApkIdentity(pkg, 2, "1.0.2", setOf(signer))
            coEvery { checker.fetchServerLatest(any()) } returns ServerLatest.Release(release())
            vm.checkForUpdate()
            advanceUntilIdle()
            assertEquals(AppUpdateState.UpToDate("1.0.2", TEST_SERVER_SCOPE), vm.state.value)
        }

    @Test
    fun init_adoptsCachedResultOnlyForTheSameConnection() =
        runTest {
            val first = createViewModel()
            advanceUntilIdle()
            val cached = first.state.value
            coVerify(exactly = 1) { checker.fetchServerLatest(any()) }

            val second = createViewModel()
            assertEquals(cached, second.state.value)
            advanceUntilIdle()
            coVerify(exactly = 1) { checker.fetchServerLatest(any()) }
            first.clearForTest()
            second.clearForTest()

            scopes.serverScope = TEST_SERVER_SCOPE.copy(selectedProfileId = "work")
            createViewModel()
            advanceUntilIdle()
            coVerify(exactly = 2) { checker.fetchServerLatest(any()) }
        }

    @Test
    fun upstreamSource_isExplicitAndRequiresDigest() =
        runTest {
            scopes.selected = UpdateSource.UPSTREAM_GITHUB
            val asset = UpdateInfo.Asset("hermes-mobile-v9.0.0.apk", 10, "https://github.com/a.apk")
            coEvery { checker.fetchLatestRelease() } returns UpdateInfo("v9.0.0", "", listOf(asset))
            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Error(UpdateFailure.NO_DIGEST), vm.state.value)
            coVerify(exactly = 0) { checker.fetchServerLatest(any()) }
        }

    // ── Install ─────────────────────────────────────────────────────────

    @Test
    fun startUpdate_launchesInstallerOnlyAfterArchiveVerification() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            advanceUntilIdle()

            assertEquals(AppUpdateState.InstallerOpened("1.0.2"), vm.state.value)
            assertEquals(1, inspector.inspected.size)
            assertTrue(launched.single() === intentMock)
            verify { FileProvider.getUriForFile(any(), "$pkg.fileprovider", any()) }
        }

    @Test
    fun startUpdate_verificationFailures_neverLaunchInstallerAndDeleteFile() =
        runTest {
            val bad =
                listOf(
                    ApkIdentity(pkg, 2, "1.0.2", setOf("b".repeat(64))) to UpdateFailure.SIGNER_MISMATCH,
                    ApkIdentity(pkg, 2, "1.0.2", emptySet()) to UpdateFailure.UNSIGNED,
                    ApkIdentity("com.example.other", 2, "1.0.2", setOf(signer)) to UpdateFailure.WRONG_APPLICATION,
                    ApkIdentity(pkg, 3, "1.0.2", setOf(signer)) to UpdateFailure.VERSION_MISMATCH,
                    null to UpdateFailure.ARCHIVE_UNREADABLE,
                )
            for ((archive, failure) in bad) {
                inspector.archiveIdentity = archive
                val vm = createViewModel()
                advanceUntilIdle()
                vm.startUpdate()
                advanceUntilIdle()
                assertEquals(AppUpdateState.Error(failure), vm.state.value)
                assertTrue(apkFiles().isEmpty())
            }
            assertTrue("installer must never open for a rejected APK", launched.isEmpty())
        }

    @Test
    fun startUpdate_tamperedBytesFailHashCheck() =
        runTest {
            coEvery { checker.download(any(), any(), any()) } coAnswers {
                val dest = secondArg<File>()
                dest.writeBytes(apkBytes.copyOf().also { it[0] = 99 })
                DownloadedApk(dest, apkSha, apkBytes.size.toLong())
            }
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Error(UpdateFailure.HASH_MISMATCH), vm.state.value)
            assertTrue(launched.isEmpty())
        }

    @Test
    fun cancelDownload_neverInstallsAndRemovesFiles() =
        runTest {
            slowDownload(10_000)
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)
            assertTrue(vm.state.value is AppUpdateState.Downloading)
            assertEquals(1, apkFiles().size)

            vm.cancelDownload()
            advanceUntilIdle()

            assertTrue(vm.state.value is AppUpdateState.UpdateAvailable)
            assertTrue(launched.isEmpty())
            assertTrue(apkFiles().isEmpty())
        }

    @Test
    fun connectionChangeDuringDownload_discardsResultAndNeverInstalls() =
        runTest {
            slowDownload(1_000)
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)

            scopes.connect(null) // logout
            advanceUntilIdle()

            assertEquals(AppUpdateState.Idle, vm.state.value)
            assertEquals(AppUpdateState.Idle, AppUpdateCache.state.value)
            assertTrue(launched.isEmpty())
            assertTrue(apkFiles().isEmpty())
        }

    @Test
    fun credentialReplacementOrAbaSwitchDuringDownloadNeverInstalls() =
        runTest {
            slowDownload(1_000)
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)

            // Same server and profile, but re-login replaced the credential and moved the epoch.
            scopes.connect(TEST_SERVER_SCOPE.copy(connectionEpoch = 6, credentialFingerprint = "relogin"))
            advanceUntilIdle()

            assertEquals(scopes.serverScope, (vm.state.value as AppUpdateState.UpdateAvailable).candidate.scope)
            assertTrue(launched.isEmpty())
            assertTrue(apkFiles().isEmpty())
        }

    @Test
    fun startUpdate_staleCandidateAfterProfileSwitchIsRefused() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            scopes.serverScope = TEST_SERVER_SCOPE.copy(activeProfileId = "other") // changed without emission yet
            vm.startUpdate()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Error(UpdateFailure.CONNECTION_CHANGED), vm.state.value)
            coVerify(exactly = 0) { checker.download(any(), any(), any()) }
        }

    @Test
    fun permissionGate_thenResumeReverifiesCachedFileBeforeInstalling() =
        runTest {
            every { packageManager.canRequestPackageInstalls() } returns false
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            advanceUntilIdle()
            assertEquals(AppUpdateState.NeedsUnknownSourcesPermission, vm.state.value)
            coVerify(exactly = 0) { checker.download(any(), any(), any()) }

            // A cached file whose bytes no longer match is re-downloaded, never installed as-is.
            // An orphan left by a dead attempt/process is swept, never installed as-is.
            val orphan = File(cacheDir, "updates").apply { mkdirs() }.resolve("hermes-update-999999.apk")
            orphan.writeBytes(ByteArray(apkBytes.size))
            every { packageManager.canRequestPackageInstalls() } returns true
            vm.resumeInstallAfterPermission()
            advanceUntilIdle()

            coVerify(exactly = 1) { checker.download(any(), any(), any()) }
            assertEquals(AppUpdateState.InstallerOpened("1.0.2"), vm.state.value)
            assertEquals(1, launched.size)
            assertFalse("orphan swept", orphan.exists())
            assertFalse(inspector.inspected.contains(orphan))

            // Re-opening the installer explicitly re-verifies the cached file again.
            vm.resumeInstallAfterPermission()
            advanceUntilIdle()
            assertEquals(2, inspector.inspected.size)
            assertEquals(2, launched.size)
        }

    @Test
    fun selectSource_invalidatesAndChecksOnlyTheChosenSource() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.selectSource(UpdateSource.UPSTREAM_GITHUB)
            advanceUntilIdle()
            assertEquals(UpdateSource.UPSTREAM_GITHUB, scopes.selected)
            assertEquals(UpdateSource.UPSTREAM_GITHUB, vm.sourceInfo.value.source)
            assertEquals(AppUpdateState.Error(UpdateFailure.NO_RELEASE), vm.state.value)
            coVerify(exactly = 1) { checker.fetchLatestRelease() }
        }

    // Home uses confirmed controller evidence, never notice/dismissal preferences.
    @Test
    fun homeAvailability_freshEqualOlderAndNewerInstalled() =
        runTest {
            for (installedCode in listOf(1L, 2L, 3L)) {
                AppUpdateCache.invalidate()
                inspector.installedIdentity = ApkIdentity(pkg, installedCode, "ignored-name", setOf(signer))
                val vm = createViewModel()
                advanceUntilIdle()
                assertEquals(installedCode == 1L, vm.homeUpdateCandidate.value != null)
                vm.clearForTest()
            }
        }

    @Test
    fun homeAvailability_dismissalDoesNotHideAndOpensSameDialogWithoutDownloading() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            val candidate = (vm.state.value as AppUpdateState.UpdateAvailable).candidate
            vm.dismissCurrentUpdate()
            assertEquals(candidate, vm.homeUpdateCandidate.value)
            vm.openUpdateDialog()
            assertTrue(AppUpdateCache.isDialogVisible)
            assertEquals(candidate, vm.homeUpdateCandidate.value)
            coVerify(exactly = 0) { checker.download(any(), any(), any()) }
        }

    @Test
    fun foreground_rechecksCachedUpToDateAndDeduplicatesChecking() =
        runTest {
            AppUpdateCache.update(AppUpdateState.UpToDate("1.0-dev", TEST_SERVER_SCOPE))
            val vm = createViewModel()
            advanceUntilIdle()
            coVerify(exactly = 0) { checker.fetchServerLatest(any()) }
            vm.onForeground()
            vm.onForeground()
            advanceUntilIdle()
            assertTrue(vm.homeUpdateCandidate.value != null)
            coVerify(exactly = 1) { checker.fetchServerLatest(any()) }
        }

    @Test
    fun foreground_installedNowEqualOrNewerInvalidatesImmediately() =
        runTest {
            for (installedCode in listOf(2L, 3L)) {
                AppUpdateCache.invalidate()
                inspector.installedIdentity = ApkIdentity(pkg, 1, "1.0-dev", setOf(signer))
                val vm = createViewModel()
                advanceUntilIdle()
                assertTrue(vm.homeUpdateCandidate.value != null)
                inspector.installedIdentity = ApkIdentity(pkg, installedCode, "1.0.2", setOf(signer))
                vm.onForeground()
                assertEquals(null, vm.homeUpdateCandidate.value)
                advanceUntilIdle()
                assertEquals(null, vm.homeUpdateCandidate.value)
                vm.clearForTest()
            }
        }

    @Test
    fun foregroundAndOpenDuringTransfer_keepCandidateAndOneAttempt() =
        runTest {
            slowDownload(10_000)
            val vm = createViewModel()
            advanceUntilIdle()
            val candidate = vm.homeUpdateCandidate.value
            assertTrue(candidate != null)
            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)
            val progress = vm.state.value
            vm.onForeground()
            vm.openUpdateDialog()
            vm.retryUpdate()
            vm.startUpdate()
            assertEquals(progress, vm.state.value)
            assertEquals(candidate, vm.homeUpdateCandidate.value)
            advanceUntilIdle()
            coVerify(exactly = 1) { checker.download(any(), any(), any()) }
            coVerify(exactly = 1) { checker.fetchServerLatest(any()) }
            assertEquals(1, launched.size)
        }

    @Test
    fun retry_checkFailureNeverUsesOldCacheAndRechecks() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            coEvery { checker.fetchServerLatest(any()) } throws IOException("offline")
            vm.checkForUpdate()
            assertEquals(null, vm.homeUpdateCandidate.value)
            advanceUntilIdle()
            assertEquals(null, vm.homeUpdateCandidate.value)
            vm.startUpdate()
            vm.retryUpdate()
            advanceUntilIdle()
            coVerify(exactly = 3) { checker.fetchServerLatest(any()) }
            coVerify(exactly = 0) { checker.download(any(), any(), any()) }
        }

    @Test
    fun retry_transferFailureRetainsCandidateAndUsesNewOwnedAttempt() =
        runTest {
            val dests = mutableListOf<File>()
            coEvery { checker.download(any(), any(), any()) } coAnswers {
                val dest = secondArg<File>()
                dests += dest
                dest.writeBytes(apkBytes)
                if (dests.size == 1) throw UpdateException(UpdateFailure.DOWNLOAD_FAILED)
                DownloadedApk(dest, apkSha, apkBytes.size.toLong())
            }
            val vm = createViewModel()
            advanceUntilIdle()
            val candidate = vm.homeUpdateCandidate.value
            assertTrue(candidate != null)
            vm.startUpdate()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Error(UpdateFailure.DOWNLOAD_FAILED), vm.state.value)
            assertEquals(candidate, vm.homeUpdateCandidate.value)
            vm.retryUpdate()
            advanceUntilIdle()
            assertEquals(2, dests.size)
            assertTrue(dests[0] != dests[1])
            assertFalse(dests[0].exists())
            coVerify(exactly = 1) { checker.fetchServerLatest(any()) }
            assertEquals(1, launched.size)
        }

    @Test
    fun permissionForeground_repeatedResumeDoesNotDuplicateInstaller() =
        runTest {
            every { packageManager.canRequestPackageInstalls() } returns false
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            val candidate = vm.homeUpdateCandidate.value
            assertTrue(candidate != null)
            vm.onForeground()
            assertEquals(AppUpdateState.NeedsUnknownSourcesPermission, vm.state.value)
            assertEquals(candidate, vm.homeUpdateCandidate.value)
            every { packageManager.canRequestPackageInstalls() } returns true
            vm.onForeground()
            vm.onForeground()
            advanceUntilIdle()
            vm.onForeground()
            vm.onForeground()
            advanceUntilIdle()
            coVerify(exactly = 1) { checker.download(any(), any(), any()) }
            assertEquals(1, launched.size)
        }

    @Test
    fun connectionSignal_cancelsOldAttemptAndRechecksUsableScope() =
        runTest {
            slowDownload(10_000)
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)
            val newScope = TEST_SERVER_SCOPE.copy(connectionEpoch = 8, credentialFingerprint = "replacement")
            scopes.connect(newScope)
            advanceUntilIdle()
            assertEquals(newScope, vm.homeUpdateCandidate.value?.scope)
            assertTrue(launched.isEmpty())
            assertTrue(apkFiles().isEmpty())
            coVerify(exactly = 2) { checker.fetchServerLatest(any()) }
        }

    @Test
    fun noReleaseAndUnknownInstalled_clearHomeAvailability() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            assertTrue(vm.homeUpdateCandidate.value != null)
            coEvery { checker.fetchServerLatest(any()) } returns ServerLatest.NoRelease
            vm.onForeground()
            advanceUntilIdle()
            assertEquals(null, vm.homeUpdateCandidate.value)
            inspector.installedIdentity = null
            coEvery { checker.fetchServerLatest(any()) } returns ServerLatest.Release(release())
            vm.onForeground()
            advanceUntilIdle()
            assertEquals(null, vm.homeUpdateCandidate.value)
            assertEquals(AppUpdateState.Error(UpdateFailure.INSTALLED_IDENTITY_UNAVAILABLE), vm.state.value)
        }

    @Test
    fun foreground_transferFailureKeepsConfirmedCandidateUntilExplicitRetry() =
        runTest {
            coEvery { checker.download(any(), any(), any()) } throws UpdateException(UpdateFailure.DOWNLOAD_FAILED)
            val vm = createViewModel()
            advanceUntilIdle()
            val candidate = vm.homeUpdateCandidate.value
            vm.startUpdate()
            advanceUntilIdle()
            vm.onForeground()
            assertEquals(AppUpdateState.Error(UpdateFailure.DOWNLOAD_FAILED), vm.state.value)
            assertEquals(candidate, vm.homeUpdateCandidate.value)
            advanceUntilIdle()
            coVerify(exactly = 1) { checker.fetchServerLatest(any()) }
        }

    @Test
    fun connectionSignal_lateNonCooperativeMetadataCannotRestoreOldScope() =
        runTest {
            coEvery { checker.fetchServerLatest(any()) } coAnswers {
                if (firstArg<com.m57.hermescontrol.data.update.UpdateScope>() == TEST_SERVER_SCOPE) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        kotlinx.coroutines.delay(1_000)
                    }
                    ServerLatest.Release(release())
                } else {
                    ServerLatest.NoRelease
                }
            }
            val vm = createViewModel()
            testDispatcher.scheduler.advanceTimeBy(100)
            scopes.connect(TEST_SERVER_SCOPE.copy(connectionEpoch = 19, credentialFingerprint = "new-auth"))
            advanceUntilIdle()
            assertEquals(AppUpdateState.Error(UpdateFailure.NO_RELEASE), vm.state.value)
            assertEquals(null, vm.homeUpdateCandidate.value)
            coVerify(exactly = 2) { checker.fetchServerLatest(any()) }
            assertTrue(launched.isEmpty())
        }

    @Test
    fun permissionRevokedDuringTransfer_reverifyFileAtRestAndNeverReplayHandoff() =
        runTest {
            slowDownload(1_000)
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)
            every { packageManager.canRequestPackageInstalls() } returns false
            advanceUntilIdle()
            assertEquals(AppUpdateState.NeedsUnknownSourcesPermission, vm.state.value)
            assertTrue(vm.homeUpdateCandidate.value != null)
            assertEquals(1, inspector.inspected.size)
            every { packageManager.canRequestPackageInstalls() } returns true
            vm.onForeground()
            vm.onForeground()
            advanceUntilIdle()
            vm.onForeground()
            advanceUntilIdle()
            assertEquals(2, inspector.inspected.size)
            coVerify(exactly = 1) { checker.download(any(), any(), any()) }
            assertEquals(1, launched.size)
        }

    @Test
    fun cancellation_lateProgressAndCompletionCannotOverwriteNewAttempt() =
        runTest {
            var downloads = 0
            coEvery { checker.download(any(), any(), any()) } coAnswers {
                val dest = secondArg<File>()
                val progress = thirdArg<(Float) -> Unit>()
                downloads++
                if (downloads == 1) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        kotlinx.coroutines.delay(1_000)
                        dest.writeBytes(apkBytes)
                        progress(0.9f)
                    }
                } else {
                    dest.writeBytes(apkBytes)
                }
                DownloadedApk(dest, apkSha, apkBytes.size.toLong())
            }
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)
            vm.cancelDownload()
            vm.startUpdate()
            advanceUntilIdle()
            assertEquals(AppUpdateState.InstallerOpened("1.0.2"), vm.state.value)
            assertEquals(1, launched.size)
            assertEquals(1, inspector.inspected.size)
            assertEquals(1, apkFiles().size)
        }

    @Test
    fun explicitUpdateAfterInstallerReturnCanReopenTheReverifiedOwnedFile() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            advanceUntilIdle()
            assertEquals(1, launched.size)
            val originalFile = inspector.inspected.single()
            vm.onForeground()
            advanceUntilIdle()
            assertTrue(vm.state.value is AppUpdateState.UpdateAvailable)
            assertEquals(1, launched.size)
            vm.startUpdate()
            advanceUntilIdle()
            assertEquals(2, launched.size)
            assertEquals(listOf(originalFile, originalFile), inspector.inspected)
            coVerify(exactly = 1) { checker.download(any(), any(), any()) }
        }

    @Test
    fun aNewReleaseAfterPriorInstallerHandoffCanStartANewOwnedAttempt() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            advanceUntilIdle()
            val nextId = "v3-${apkSha.take(16)}"
            val next =
                release().copy(
                    releaseId = nextId,
                    versionCode = 3,
                    versionName = "1.0.3",
                    apkPath = "releases/$nextId/apk",
                )
            coEvery { checker.fetchServerLatest(any()) } returns ServerLatest.Release(next)
            inspector.archiveIdentity = ApkIdentity(pkg, 3, "1.0.3", setOf(signer))
            vm.onForeground()
            advanceUntilIdle()
            assertEquals(3L, vm.homeUpdateCandidate.value?.versionCode)
            vm.startUpdate()
            advanceUntilIdle()
            coVerify(exactly = 2) { checker.download(any(), any(), any()) }
            assertEquals(2, launched.size)
            assertEquals(AppUpdateState.InstallerOpened("1.0.3"), vm.state.value)
        }

    @Test
    fun explicitInstallerReopenCannotTrustTamperedCachedBytes() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            advanceUntilIdle()
            val tamperedFile = inspector.inspected.single()
            tamperedFile.writeBytes(ByteArray(apkBytes.size))
            vm.resumeInstallAfterPermission()
            advanceUntilIdle()
            coVerify(exactly = 2) { checker.download(any(), any(), any()) }
            assertEquals(2, launched.size)
            assertFalse(inspector.inspected.last() == tamperedFile)
        }

    @Test
    fun installerUnavailable_retryChecksThenCanOwnANewAttempt() =
        runTest {
            every { app.startActivity(any()) } throws IllegalStateException("no installer")
            val vm = createViewModel()
            advanceUntilIdle()
            vm.startUpdate()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Error(UpdateFailure.INSTALLER_UNAVAILABLE), vm.state.value)
            vm.retryUpdate()
            advanceUntilIdle()
            assertTrue(vm.state.value is AppUpdateState.UpdateAvailable)
            coVerify(exactly = 2) { checker.fetchServerLatest(any()) }
            every { app.startActivity(capture(launched)) } returns Unit
            vm.startUpdate()
            advanceUntilIdle()
            assertEquals(1, launched.size)
        }

    @Test
    fun dismissCurrentUpdate_persistsDismissedTagAndHidesDialog() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            AppUpdateCache.showDialog()

            vm.dismissCurrentUpdate()
            advanceUntilIdle()

            verify(exactly = 1) { AuthManager.setDismissedUpdateTag("1.0.2") }
            assertTrue(AppUpdateCache.dismissed)
            assertFalse(AppUpdateCache.isDialogVisible)
        }
}
