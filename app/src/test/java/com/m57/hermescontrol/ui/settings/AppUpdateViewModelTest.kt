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

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    private fun createViewModel() =
        AppUpdateViewModel(app, checker, "1.0-dev", testDispatcher, inspector, scopes, pkg) { intentMock }

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

            assertEquals(cached, createViewModel().state.value)
            advanceUntilIdle()
            coVerify(exactly = 1) { checker.fetchServerLatest(any()) }

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

            assertEquals(AppUpdateState.Idle, vm.state.value)
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

            // Re-opening the installer re-verifies the cached file again.
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
