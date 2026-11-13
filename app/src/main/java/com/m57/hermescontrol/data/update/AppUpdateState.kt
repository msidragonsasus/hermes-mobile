package com.m57.hermescontrol.data.update

/**
 * State of the in-app updater (issue #867) — shared by the About tab's
 * [com.m57.hermescontrol.ui.settings.AppUpdateViewModel], the chat dialog and
 * the launch notice (issue #890) via [AppUpdateCache].
 */
sealed interface AppUpdateState {
    /** Nothing shown yet (no check run in this session, or the connection changed). */
    data object Idle : AppUpdateState

    /** A check is in flight. */
    data object Checking : AppUpdateState

    /** The source has nothing newer for [scope]. */
    data class UpToDate(
        val latestTag: String,
        val scope: UpdateScope? = null,
    ) : AppUpdateState

    /** A newer release passed the pre-download policy for its bound connection. */
    data class UpdateAvailable(
        val candidate: UpdateCandidate,
    ) : AppUpdateState {
        val latestTag: String get() = candidate.versionName
        val sizeBytes: Long get() = candidate.sizeBytes
        val releaseNotes: String get() = candidate.releaseNotes
    }

    /** APK download in progress; [progress] is 0..1. */
    data class Downloading(
        val progress: Float,
    ) : AppUpdateState

    /** Checking hash, package, version and signer of the downloaded APK. */
    data object Verifying : AppUpdateState

    /**
     * The verified APK was handed to the system installer. The user still has
     * to confirm there; this state does not mean the update was installed.
     */
    data class InstallerOpened(
        val latestTag: String,
    ) : AppUpdateState

    /** Install-from-unknown-sources not granted for this app yet. */
    data object NeedsUnknownSourcesPermission : AppUpdateState

    data class Error(
        val failure: UpdateFailure,
    ) : AppUpdateState
}

/** The release tag a state carries, when it carries one. */
fun AppUpdateState.releaseTag(): String? =
    when (this) {
        is AppUpdateState.UpToDate -> latestTag
        is AppUpdateState.UpdateAvailable -> latestTag
        is AppUpdateState.InstallerOpened -> latestTag
        else -> null
    }
