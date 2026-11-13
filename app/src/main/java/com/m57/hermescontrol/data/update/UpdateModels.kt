package com.m57.hermescontrol.data.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where update metadata and the APK come from. The two sources are never
 * substituted for each other: a failed or unavailable connected server does
 * not fall back to the public upstream.
 */
enum class UpdateSource(
    val storageKey: String,
) {
    /** `/api/plugins/mobile_updates` on the connected, authenticated Hermes server. */
    CONNECTED_SERVER("connected_server"),

    /** Public Hy4ri/hermes-mobile GitHub releases (explicit opt-in only). */
    UPSTREAM_GITHUB("upstream_github"),
    ;

    companion object {
        fun fromStorage(value: String?): UpdateSource =
            entries.firstOrNull { it.storageKey == value } ?: CONNECTED_SERVER
    }
}

/**
 * The connection a check ran against. Metadata and downloads are bound to it;
 * any difference makes a result stale: server, selected/active profile, auth
 * mode, sign-out, a replaced credential ([credentialFingerprint]) or any
 * connection change since ([connectionEpoch], which also separates A→B→A).
 * Holds no credential values: the fingerprint is a per-process salted hash and
 * nothing here is persisted.
 */
data class UpdateScope(
    val source: UpdateSource,
    val baseUrl: String = "",
    val selectedProfileId: String? = null,
    val activeProfileId: String? = null,
    val gatedAuth: Boolean = false,
    val credentialPresent: Boolean = false,
    val connectionEpoch: Long = 0,
    val credentialFingerprint: String = "",
) {
    /** "host:port" of the connected server, or empty for the public source. */
    val serverLabel: String
        get() = baseUrl.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" }.orEmpty()
}

/** Why an update was not offered or not installed. Every failure is fail-closed. */
enum class UpdateFailure {
    NOT_CONNECTED,
    LOGIN_REQUIRED,
    SERVER_UPDATES_MISSING,
    SERVER_UPDATES_DISABLED,
    NO_RELEASE,
    NO_APK,
    NO_DIGEST,
    INVALID_METADATA,
    NETWORK,
    REDIRECT_REFUSED,
    WRONG_APPLICATION,
    INSTALLED_IDENTITY_UNAVAILABLE,
    SIGNER_MISMATCH,
    SIGNER_ROTATION_UNSUPPORTED,
    UNSIGNED,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    ARCHIVE_UNREADABLE,
    VERSION_MISMATCH,
    NOT_NEWER,
    CONNECTION_CHANGED,
    DOWNLOAD_FAILED,
    INSTALLER_UNAVAILABLE,
    CHECK_FAILED,
}

/** Not an IOException on purpose: transport errors and policy refusals stay distinct. */
class UpdateException(
    val failure: UpdateFailure,
) : Exception(failure.name)

/**
 * Package identity read by PackageManager (installed app or downloaded archive).
 * [signerSha256] is the set of SHA-256 digests of the current signing
 * certificates (DER), lowercase hex.
 */
data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signerSha256: Set<String>,
    val hasPastSigners: Boolean = false,
)

/**
 * One installable release, common to both sources. [versionCode] and
 * [signerSha256] are null for the upstream source, which does not publish
 * them; the downloaded archive is still checked against the installed app.
 */
data class UpdateCandidate(
    val source: UpdateSource,
    val scope: UpdateScope,
    val generation: Long,
    val versionName: String,
    val versionCode: Long?,
    val sha256: String,
    val sizeBytes: Long,
    val signerSha256: Set<String>?,
    val downloadUrl: String,
    val releaseNotes: String = "",
)

/** Upper bound for any update APK, enforced before and during download. */
const val MAX_UPDATE_APK_BYTES: Long = 200L * 1024 * 1024
