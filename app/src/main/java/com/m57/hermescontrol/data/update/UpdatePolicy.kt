package com.m57.hermescontrol.data.update

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Result of one update check, shared by the About tab, the chat dialog and the launch check. */
sealed interface UpdateCheck {
    data class Available(
        val candidate: UpdateCandidate,
    ) : UpdateCheck

    data class UpToDate(
        val versionName: String,
    ) : UpdateCheck

    data class Failed(
        val failure: UpdateFailure,
    ) : UpdateCheck
}

/**
 * The single update policy. Server metadata only decides whether an update is
 * offered; installation requires the downloaded archive itself to match the
 * installed app ([verifyArchive]). Key rotation is not supported: the current
 * signer sets must be strictly equal.
 */
object UpdatePolicy {
    fun requireInstalled(
        installed: ApkIdentity?,
        expectedPackage: String,
    ): ApkIdentity {
        if (installed == null || installed.packageName != expectedPackage || installed.signerSha256.isEmpty()) {
            throw UpdateException(UpdateFailure.INSTALLED_IDENTITY_UNAVAILABLE)
        }
        if (installed.hasPastSigners) throw UpdateException(UpdateFailure.SIGNER_ROTATION_UNSUPPORTED)
        return installed
    }

    fun offerFromServer(
        release: ServerRelease,
        scope: UpdateScope,
        generation: Long,
        downloadUrl: String,
        installed: ApkIdentity?,
        expectedPackage: String,
    ): UpdateCheck {
        if (!release.isWellFormed()) throw UpdateException(UpdateFailure.INVALID_METADATA)
        if (release.applicationId != expectedPackage) throw UpdateException(UpdateFailure.WRONG_APPLICATION)
        val current = requireInstalled(installed, expectedPackage)
        if (release.versionCode <= current.versionCode) return UpdateCheck.UpToDate(release.versionName)
        if (release.signerSha256.toSet() != current.signerSha256) throw UpdateException(UpdateFailure.SIGNER_MISMATCH)
        return UpdateCheck.Available(
            UpdateCandidate(
                source = UpdateSource.CONNECTED_SERVER,
                scope = scope,
                generation = generation,
                versionName = release.versionName,
                versionCode = release.versionCode,
                sha256 = release.sha256,
                sizeBytes = release.sizeBytes,
                signerSha256 = release.signerSha256.toSet(),
                downloadUrl = downloadUrl,
                releaseNotes = release.notes,
            ),
        )
    }

    fun offerFromUpstream(
        info: UpdateInfo,
        scope: UpdateScope,
        generation: Long,
        currentVersion: String,
    ): UpdateCheck {
        val apk = info.apkAsset ?: throw UpdateException(UpdateFailure.NO_APK)
        if (!isNewerVersion(info.tagName, currentVersion)) return UpdateCheck.UpToDate(info.tagName)
        val sha = apk.sha256 ?: throw UpdateException(UpdateFailure.NO_DIGEST)
        val url = apk.browserDownloadUrl.toHttpUrlOrNull()
        if (url == null || !url.isHttps || apk.size !in 1..MAX_UPDATE_APK_BYTES) {
            throw UpdateException(UpdateFailure.INVALID_METADATA)
        }
        return UpdateCheck.Available(
            UpdateCandidate(
                source = UpdateSource.UPSTREAM_GITHUB,
                scope = scope,
                generation = generation,
                versionName = info.tagName,
                versionCode = null,
                sha256 = sha,
                sizeBytes = apk.size,
                signerSha256 = null,
                downloadUrl = url.toString(),
                releaseNotes = info.body,
            ),
        )
    }

    /** Null when [downloaded] may be handed to the system installer; otherwise why not. */
    fun verifyArchive(
        candidate: UpdateCandidate,
        downloaded: DownloadedApk,
        archive: ApkIdentity?,
        installed: ApkIdentity?,
        expectedPackage: String,
    ): UpdateFailure? {
        if (downloaded.sizeBytes != candidate.sizeBytes) return UpdateFailure.SIZE_MISMATCH
        if (downloaded.sha256 != candidate.sha256) return UpdateFailure.HASH_MISMATCH
        if (archive == null) return UpdateFailure.ARCHIVE_UNREADABLE
        if (archive.packageName != expectedPackage) return UpdateFailure.WRONG_APPLICATION
        val current =
            try {
                requireInstalled(installed, expectedPackage)
            } catch (e: UpdateException) {
                return e.failure
            }
        if (archive.signerSha256.isEmpty()) return UpdateFailure.UNSIGNED
        if (archive.hasPastSigners) return UpdateFailure.SIGNER_ROTATION_UNSUPPORTED
        if (archive.signerSha256 != current.signerSha256) return UpdateFailure.SIGNER_MISMATCH
        if (archive.versionCode <= current.versionCode) return UpdateFailure.NOT_NEWER
        candidate.versionCode?.let { if (archive.versionCode != it) return UpdateFailure.VERSION_MISMATCH }
        val expectedName =
            if (candidate.source == UpdateSource.UPSTREAM_GITHUB) {
                normalizedVersion(candidate.versionName)
            } else {
                candidate.versionName
            }
        if (archive.versionName != expectedName) return UpdateFailure.VERSION_MISMATCH
        return null
    }
}

/**
 * Run one check against [scope]'s source only. A connected-server failure is
 * reported as such; it never falls back to the public upstream.
 */
suspend fun resolveUpdate(
    checker: AppUpdateChecker,
    scope: UpdateScope,
    generation: Long,
    inspector: ApkInspector?,
    expectedPackage: String,
    currentVersion: String,
): UpdateCheck =
    try {
        when (scope.source) {
            UpdateSource.CONNECTED_SERVER -> {
                when (val latest = checker.fetchServerLatest(scope)) {
                    ServerLatest.NoRelease -> {
                        UpdateCheck.Failed(UpdateFailure.NO_RELEASE)
                    }

                    is ServerLatest.Release -> {
                        UpdatePolicy.offerFromServer(
                            latest.release,
                            scope,
                            generation,
                            serverUpdatesUrl(scope, latest.release.apkPath).toString(),
                            inspector?.installed(),
                            expectedPackage,
                        )
                    }
                }
            }

            UpdateSource.UPSTREAM_GITHUB -> {
                val info = checker.fetchLatestRelease()
                if (info == null) {
                    UpdateCheck.Failed(UpdateFailure.NO_RELEASE)
                } else {
                    UpdatePolicy.offerFromUpstream(info, scope, generation, currentVersion)
                }
            }
        }
    } catch (e: UpdateException) {
        UpdateCheck.Failed(e.failure)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        UpdateCheck.Failed(UpdateFailure.NETWORK)
    } catch (e: Exception) {
        UpdateCheck.Failed(UpdateFailure.CHECK_FAILED)
    }
