package com.m57.hermescontrol.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.update.APK_MIME_TYPE
import com.m57.hermescontrol.data.update.ApkInspector
import com.m57.hermescontrol.data.update.AppUpdateCache
import com.m57.hermescontrol.data.update.AppUpdateChecker
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.AuthManagerUpdateScopes
import com.m57.hermescontrol.data.update.DownloadedApk
import com.m57.hermescontrol.data.update.PackageManagerApkInspector
import com.m57.hermescontrol.data.update.UpdateCandidate
import com.m57.hermescontrol.data.update.UpdateCheck
import com.m57.hermescontrol.data.update.UpdateConnectionWatcher
import com.m57.hermescontrol.data.update.UpdateException
import com.m57.hermescontrol.data.update.UpdateFailure
import com.m57.hermescontrol.data.update.UpdatePolicy
import com.m57.hermescontrol.data.update.UpdateScope
import com.m57.hermescontrol.data.update.UpdateScopeProvider
import com.m57.hermescontrol.data.update.UpdateSource
import com.m57.hermescontrol.data.update.releaseTag
import com.m57.hermescontrol.data.update.resolveUpdate
import com.m57.hermescontrol.data.update.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Selected source and, for the connected server, which server it is bound to. */
data class UpdateSourceInfo(
    val source: UpdateSource,
    val serverLabel: String,
)

/**
 * Drives the in-app self-update flow (issue #867): check the selected source,
 * download the APK, verify it, and hand it to the system package installer.
 *
 * - The connected server is the default source; it never falls back to the
 *   public upstream when unavailable.
 * - Every candidate is bound to the exact connection ([UpdateScope], incl.
 *   connection epoch and credential fingerprint) and to
 *   [AppUpdateCache.generation]. A change anywhere (also detected by the
 *   process-wide [UpdateConnectionWatcher]) discards results and cancels the
 *   attempt.
 * - Each download is an [Attempt] with its own file. Only the current attempt
 *   may change state; an old attempt's late progress, failure or cleanup can
 *   never touch a newer attempt's state or file.
 * - The installer opens only after the file at rest passes
 *   [UpdatePolicy.verifyArchive], and only for the still-current, uncancelled
 *   attempt. Opening the installer is not reported as an installed update.
 */
class AppUpdateViewModel(
    application: Application,
    private val checker: AppUpdateChecker = AppUpdateChecker(),
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val inspector: ApkInspector = PackageManagerApkInspector(application),
    private val scopes: UpdateScopeProvider = AuthManagerUpdateScopes,
    private val expectedPackage: String = BuildConfig.APPLICATION_ID,
    private val installIntentFactory: (Uri) -> Intent = ::buildInstallIntent,
) : AndroidViewModel(application) {
    /** One download/verify/install attempt and the file it exclusively owns. */
    private class Attempt(
        val id: Long,
        val candidate: UpdateCandidate,
        val file: File,
    ) {
        var job: Job? = null

        @Volatile
        var handedToInstaller = false

        fun discard() {
            file.delete()
            File(file.path + ".part").delete()
            ownedFiles.remove(file.path)
        }
    }

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /** Home visibility is independent of the optional chat notice and its dismissal. */
    private val _homeUpdateCandidate = MutableStateFlow<UpdateCandidate?>(null)
    val homeUpdateCandidate: StateFlow<UpdateCandidate?> = _homeUpdateCandidate.asStateFlow()

    /** Refresh on return, but never replace an owned transfer or replay installer handoff. */
    fun onForeground() {
        if (onConnectionSignal()) return
        synchronized(lock) {
            val candidate = pending
            if (candidate != null && !isOfferValid(candidate)) resetForNewConnectionLocked()
            refreshHomeCandidate()
            if (_state.value is AppUpdateState.NeedsUnknownSourcesPermission) {
                resumeInstallAfterPermission()
                return
            }
            // A recoverable transfer error retains its confirmed offer for explicit retry.
            if (isBusy() || (_state.value is AppUpdateState.Error && pending != null)) return
        }
        checkForUpdate()
    }

    /** Reopen the shared dialog; visibility is not permission to start another attempt. */
    fun openUpdateDialog() {
        onConnectionSignal()
        synchronized(lock) {
            if (pending?.let { !isOfferValid(it) } == true) resetForNewConnectionLocked()
            refreshHomeCandidate()
            AppUpdateCache.showDialog()
        }
    }

    /** A check failure has no candidate. Only a retained transfer candidate may retry directly. */
    fun retryUpdate() {
        if (onConnectionSignal()) return
        synchronized(lock) {
            if (isBusy()) return
            if (pending?.let { isOfferValid(it) } == true) {
                if (_state.value is AppUpdateState.NeedsUnknownSourcesPermission) {
                    resumeInstallAfterPermission()
                } else {
                    startUpdate()
                }
                return
            }
        }
        checkForUpdate()
    }

    private val _sourceInfo = MutableStateFlow(sourceInfoFor(scopes.source()))
    val sourceInfo: StateFlow<UpdateSourceInfo> = _sourceInfo.asStateFlow()

    private val lock = Any()
    private var checkJob: Job? = null
    private var checkGeneration = -1L
    private var attempt: Attempt? = null
    private var pending: UpdateCandidate? = null
    private var lastScope: UpdateScope? = null
    private var lastGeneration = AppUpdateCache.generation
    private var checkId = 0L
    private var cleared = false

    init {
        // Drop a cached result for another connection before adopting anything.
        UpdateConnectionWatcher.invalidateIfStale(scopes)
        lastScope = scopes.current(scopes.source())
        lastGeneration = AppUpdateCache.generation
        val cached = AppUpdateCache.state.value
        if (isCurrentResult(cached)) {
            _state.value = cached
            pending = (cached as? AppUpdateState.UpdateAvailable)?.candidate
            refreshHomeCandidate()
        } else {
            checkForUpdate()
        }
        viewModelScope.launch {
            merge(scopes.changes, AppUpdateCache.generationFlow).collect { onConnectionSignal() }
        }
    }

    /** Explicit source choice from the About tab. Discards everything bound to the old source. */
    fun selectSource(source: UpdateSource) {
        if (source == _sourceInfo.value.source) return
        scopes.setSource(source)
        AppUpdateCache.invalidate()
        resetForNewConnection()
        checkForUpdate()
    }

    /** Manual check from About/Home. Checking deliberately revokes old evidence. */
    fun checkForUpdate() {
        synchronized(lock) {
            if (cleared || isBusy()) return
            val source = scopes.source()
            val scope = scopes.current(source)
            if (source != _sourceInfo.value.source || scope != lastScope) {
                AppUpdateCache.invalidate()
                resetForNewConnectionLocked()
            }
            pending = null
            refreshHomeCandidate()
            // A failed check must not leave a globally cached candidate installable.
            AppUpdateCache.update(AppUpdateState.Idle)
            if (scope == null) {
                _state.value = AppUpdateState.Error(UpdateFailure.NOT_CONNECTED)
                return
            }
            val generation = AppUpdateCache.generation
            val ownCheck = ++checkId
            checkGeneration = generation
            _state.value = AppUpdateState.Checking
            checkJob =
                viewModelScope.launch(ioDispatcher) {
                    AuthManager.setUpdateCheckDoneForVersion(currentVersion)
                    AuthManager.setLastUpdateCheckTimestamp(System.currentTimeMillis())
                    val result = resolveUpdate(checker, scope, generation, inspector, expectedPackage, currentVersion)
                    synchronized(lock) {
                        if (cleared || ownCheck != checkId || !isActive ||
                            generation != AppUpdateCache.generation || scopes.source() != source ||
                            scopes.current(source) != scope
                        ) {
                            return@launch
                        }
                        val next =
                            when (result) {
                                is UpdateCheck.Available -> AppUpdateState.UpdateAvailable(result.candidate)
                                is UpdateCheck.UpToDate -> AppUpdateState.UpToDate(result.versionName, scope)
                                is UpdateCheck.Failed -> AppUpdateState.Error(result.failure)
                            }
                        pending = (next as? AppUpdateState.UpdateAvailable)?.candidate
                        _state.value = next
                        refreshHomeCandidate()
                        if (next !is AppUpdateState.Error) {
                            AppUpdateCache.update(next)
                            next.releaseTag()?.let { AuthManager.setLastKnownLatestTag(it) }
                        }
                    }
                }
        }
    }

    /** Download the APK, verify it, then open the system installer. Ignored while one is running. */
    fun startUpdate() {
        synchronized(lock) {
            if (cleared || isBusy()) return
            // No fallback to AppUpdateCache: it may predate a failed fresh check.
            val candidate = pending ?: return
            if (!isCurrent(candidate)) {
                AppUpdateCache.invalidate()
                resetForNewConnectionLocked()
                _state.value = AppUpdateState.Error(UpdateFailure.CONNECTION_CHANGED)
                return
            }
            if (!isOfferValid(candidate)) {
                pending = null
                refreshHomeCandidate()
                _state.value = AppUpdateState.Error(UpdateFailure.NOT_NEWER)
                return
            }
            pending = candidate
            refreshHomeCandidate()
            if (attempt?.let { it.handedToInstaller && it.candidate == candidate } == true) {
                // This is an explicit button action, not a lifecycle resume.
                resumeInstallAfterPermission()
                return
            }
            if (!canRequestInstalls()) {
                _state.value = AppUpdateState.NeedsUnknownSourcesPermission
                return
            }
            attempt?.discard()
            val next = newAttempt(candidate)
            attempt = next
            _state.value = AppUpdateState.Downloading(0f)
            next.job = viewModelScope.launch(ioDispatcher) { runDownload(next) }
        }
    }

    /** Cancel an in-flight download/verification; nothing is installed afterwards. */
    fun cancelDownload() {
        synchronized(lock) {
            val current = attempt ?: return@synchronized
            attempt = null
            current.job?.cancel()
            current.discard()
        }
        val candidate = pending
        _state.value =
            if (candidate != null && isCurrent(candidate)) {
                AppUpdateState.UpdateAvailable(candidate)
            } else {
                AppUpdateState.Idle
            }
    }

    /** Dismiss the current update tag so it stops nagging. */
    fun dismissCurrentUpdate() {
        val tag = _state.value.releaseTag()
        if (tag != null) {
            AuthManager.setDismissedUpdateTag(tag)
        }
        AppUpdateCache.dismiss()
        AppUpdateCache.hideDialog()
    }

    /** Resume a requested permission wait, or explicitly reopen and re-verify an installer. */
    fun resumeInstallAfterPermission() {
        synchronized(lock) {
            if (cleared || isBusy()) return
            val current = attempt
            val permissionWait = _state.value is AppUpdateState.NeedsUnknownSourcesPermission
            val explicitReopen =
                current?.handedToInstaller == true &&
                    (_state.value is AppUpdateState.InstallerOpened || _state.value is AppUpdateState.UpdateAvailable)
            if (!permissionWait && !explicitReopen) return
            if (!canRequestInstalls()) {
                _state.value = AppUpdateState.NeedsUnknownSourcesPermission
                return
            }
            val candidate = pending ?: return
            if (!isCurrent(candidate)) {
                AppUpdateCache.invalidate()
                resetForNewConnectionLocked()
                _state.value = AppUpdateState.Error(UpdateFailure.CONNECTION_CHANGED)
                return
            }
            val reusable =
                current != null &&
                    current.candidate == candidate &&
                    current.file.isFile &&
                    current.file.length() == candidate.sizeBytes &&
                    runCatching { sha256Hex(current.file) }.getOrNull() == candidate.sha256
            if (current == null || !reusable) {
                current?.discard()
                attempt = null
                _state.value = AppUpdateState.UpdateAvailable(candidate)
            } else {
                current.handedToInstaller = false
                _state.value = AppUpdateState.Verifying
                current.job = viewModelScope.launch(ioDispatcher) { verifyAndInstall(current) }
                return
            }
        }
        startUpdate()
    }

    override fun onCleared() {
        synchronized(lock) {
            cleared = true
            checkId++
            _homeUpdateCandidate.value = null
            checkJob?.cancel()
            val current = attempt
            attempt = null
            current?.job?.cancel()
            // A file already handed to the system installer stays for it to read; the next
            // attempt's orphan sweep removes it. Anything else is deleted now.
            if (current != null && !current.handedToInstaller) current.discard()
            current?.let { ownedFiles.remove(it.file.path) }
        }
        super.onCleared()
    }

    @VisibleForTesting
    internal fun clearForTest() = onCleared()

    private suspend fun CoroutineScope.runDownload(own: Attempt) {
        try {
            checker.download(own.candidate, own.file) { progress ->
                if (isActive) postIfCurrent(own, AppUpdateState.Downloading(progress))
            }
            // Include the cancellation boundary in cleanup even if a downloader returns late.
            verifyAndInstall(own)
        } catch (e: CancellationException) {
            own.discard()
            throw e
        } catch (e: UpdateException) {
            failIfCurrent(own, e.failure)
            return
        } catch (e: Exception) {
            failIfCurrent(own, UpdateFailure.DOWNLOAD_FAILED)
            return
        }
    }

    private fun CoroutineScope.verifyAndInstall(own: Attempt) {
        postIfCurrent(own, AppUpdateState.Verifying)
        ensureActive()
        val candidate = own.candidate
        val file = own.file
        val failure =
            if (!isCurrent(candidate)) {
                UpdateFailure.CONNECTION_CHANGED
            } else {
                runCatching {
                    // Hash the file at rest; never trust the downloader's claim alone.
                    val downloaded = DownloadedApk(file, sha256Hex(file), file.length())
                    UpdatePolicy.verifyArchive(
                        candidate,
                        downloaded,
                        inspector.archive(file),
                        inspector.installed(),
                        expectedPackage,
                    )
                }.getOrElse { UpdateFailure.ARCHIVE_UNREADABLE }
            }
        if (failure != null) {
            failIfCurrent(own, failure)
            return
        }
        synchronized(lock) {
            if (attempt !== own || !isActive || !isCurrent(candidate)) {
                if (attempt !== own) own.discard()
                return
            }
            if (own.handedToInstaller) return
            if (!canRequestInstalls()) {
                _state.value = AppUpdateState.NeedsUnknownSourcesPermission
                refreshHomeCandidate()
                return
            }
            _state.value = AppUpdateState.InstallerOpened(candidate.versionName)
            own.handedToInstaller = true
            if (!launchInstaller(file)) {
                own.handedToInstaller = false
                failIfCurrent(own, UpdateFailure.INSTALLER_UNAVAILABLE)
            }
        }
    }

    /** Reconcile exact scope/generation before any action and on source/auth signals. */
    private fun onConnectionSignal(): Boolean {
        if (cleared) return false
        UpdateConnectionWatcher.invalidateIfStale(scopes)
        val changed =
            synchronized(lock) {
                val source = scopes.source()
                val scope = scopes.current(source)
                val staleCandidate = (attempt?.candidate ?: pending)?.let { !isCurrent(it) } == true
                val staleCheck = checkJob?.isActive == true && checkGeneration != AppUpdateCache.generation
                if (source != _sourceInfo.value.source || scope != lastScope ||
                    lastGeneration != AppUpdateCache.generation || staleCandidate || staleCheck
                ) {
                    // Also fence uncached in-flight checks when no watcher cache existed to invalidate.
                    if (lastGeneration == AppUpdateCache.generation) AppUpdateCache.invalidate()
                    resetForNewConnectionLocked()
                    true
                } else {
                    false
                }
            }
        if (changed && scopes.current(scopes.source()) != null) checkForUpdate()
        return changed
    }

    private fun resetForNewConnection() = synchronized(lock) { resetForNewConnectionLocked() }

    /** Discard everything bound to the previous connection or source (caller holds [lock]). */
    private fun resetForNewConnectionLocked() {
        checkId++
        checkJob?.cancel()
        checkJob = null
        val current = attempt
        attempt = null
        current?.job?.cancel()
        current?.discard()
        pending = null
        _homeUpdateCandidate.value = null
        lastScope = scopes.current(scopes.source())
        lastGeneration = AppUpdateCache.generation
        _sourceInfo.value = sourceInfoFor(scopes.source())
        _state.value = AppUpdateState.Idle
    }

    private fun postIfCurrent(
        own: Attempt,
        next: AppUpdateState,
    ) = synchronized(lock) {
        if (attempt === own && !cleared && isCurrent(own.candidate)) _state.value = next
    }

    private fun failIfCurrent(
        own: Attempt,
        failure: UpdateFailure,
    ) = synchronized(lock) {
        own.discard()
        if (attempt === own) {
            attempt = null
            if (failure !in setOf(UpdateFailure.NETWORK, UpdateFailure.DOWNLOAD_FAILED) ||
                !isOfferValid(own.candidate)
            ) {
                pending = null
                AppUpdateCache.update(AppUpdateState.Idle)
            }
            refreshHomeCandidate()
            _state.value = AppUpdateState.Error(failure)
        }
    }

    private fun isBusy(): Boolean {
        val current = _state.value
        return current is AppUpdateState.Checking ||
            current is AppUpdateState.Downloading ||
            current is AppUpdateState.Verifying ||
            attempt?.job?.isActive == true
    }

    /** Re-read installed identity, not a persisted latest-tag or banner preference. */
    private fun isOfferValid(candidate: UpdateCandidate): Boolean {
        if (!isCurrent(candidate)) return false
        return runCatching {
            val installed = UpdatePolicy.requireInstalled(inspector.installed(), expectedPackage)
            if (candidate.source == UpdateSource.CONNECTED_SERVER) {
                candidate.versionCode != null && candidate.versionCode > installed.versionCode &&
                    candidate.signerSha256 == installed.signerSha256
            } else {
                com.m57.hermescontrol.data.update
                    .isNewerVersion(candidate.versionName, currentVersion)
            }
        }.getOrDefault(false)
    }

    private fun refreshHomeCandidate() {
        _homeUpdateCandidate.value = pending?.takeIf { isOfferValid(it) }
    }

    private fun isCurrent(candidate: UpdateCandidate): Boolean =
        !cleared && scopes.source() == candidate.source &&
            candidate.generation == AppUpdateCache.generation &&
            candidate.source == _sourceInfo.value.source &&
            candidate.scope == scopes.current(candidate.source)

    private fun isCurrentResult(state: AppUpdateState): Boolean =
        when (state) {
            is AppUpdateState.UpdateAvailable -> {
                isOfferValid(state.candidate)
            }

            is AppUpdateState.UpToDate -> {
                state.scope != null && state.scope == scopes.current(_sourceInfo.value.source)
            }

            else -> {
                false
            }
        }

    private fun sourceInfoFor(source: UpdateSource) =
        UpdateSourceInfo(
            source,
            if (source == UpdateSource.CONNECTED_SERVER) scopes.current(source)?.serverLabel.orEmpty() else "",
        )

    /** A fresh, exclusively owned file; files left by dead attempts or processes are swept. */
    private fun newAttempt(candidate: UpdateCandidate): Attempt {
        val dir = File(getApplication<Application>().cacheDir, UPDATE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { stale ->
            val ownerPath = stale.path.removeSuffix(".part")
            if (ownerPath !in ownedFiles) stale.delete()
        }
        val id = attemptIds.incrementAndGet()
        val file = File(dir, "hermes-update-$id.apk")
        ownedFiles.add(file.path)
        return Attempt(id, candidate, file)
    }

    private fun canRequestInstalls(): Boolean =
        try {
            getApplication<Application>().packageManager.canRequestPackageInstalls()
        } catch (e: Exception) {
            false
        }

    private fun launchInstaller(apkFile: File): Boolean =
        try {
            val context = getApplication<Application>()
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile,
                )
            context.startActivity(installIntentFactory(uri))
            true
        } catch (e: Exception) {
            false
        }

    private companion object {
        /** Under the existing FileProvider `cache-path` root. */
        const val UPDATE_DIR = "updates"

        /** Process-wide: attempt ids and files owned by live attempts of any ViewModel. */
        val attemptIds = AtomicLong(0)
        val ownedFiles: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    }
}

/** MIME type of an installable APK (issue #867). */
internal const val APK_MIME = APK_MIME_TYPE

/**
 * The system-installer intent for a downloaded APK: ACTION_VIEW with the
 * package-archive mime, a read grant for the FileProvider URI, and
 * NEW_TASK (launched from the application context). The system installer
 * asks the user to confirm; nothing installs silently.
 */
internal fun buildInstallIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
