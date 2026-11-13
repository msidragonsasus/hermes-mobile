package com.m57.hermescontrol.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Plugin route prefix, relative to the connected endpoint (keeps a reverse-proxy prefix). */
const val SERVER_UPDATES_PATH = "api/plugins/mobile_updates"
const val SERVER_LATEST_SCHEMA = "hermes-mobile-update-latest"
const val SERVER_UPDATE_PROTOCOL = 1

private const val MAX_VERSION_CODE = 2_100_000_000L
private const val MAX_NOTES = 8000
private val RELEASE_ID = Regex("^v[1-9][0-9]{0,9}-[0-9a-f]{16}$")
internal val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/** `GET /api/plugins/mobile_updates/latest` (docs/APP_SERVER_UPDATES.md). */
@Serializable
data class ServerLatestResponse(
    @SerialName("schema") val schema: String,
    @SerialName("protocol") val protocol: Int,
    @SerialName("release") val release: ServerRelease? = null,
)

@Serializable
data class ServerRelease(
    @SerialName("release_id") val releaseId: String,
    @SerialName("application_id") val applicationId: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("signer_sha256") val signerSha256: List<String>,
    @SerialName("apk_path") val apkPath: String,
    @SerialName("notes") val notes: String = "",
    @SerialName("published_at") val publishedAt: String = "",
)

sealed interface ServerLatest {
    data class Release(
        val release: ServerRelease,
    ) : ServerLatest

    data object NoRelease : ServerLatest
}

private val protocolJson = Json { ignoreUnknownKeys = true }

/**
 * Parse and validate a `/latest` body. The metadata is advisory (the APK is
 * re-verified after download) but malformed or self-inconsistent metadata is
 * refused outright. Throws [UpdateException] with [UpdateFailure.INVALID_METADATA].
 */
fun parseServerLatest(body: String): ServerLatest {
    val response =
        try {
            protocolJson.decodeFromString<ServerLatestResponse>(body)
        } catch (e: Exception) {
            throw UpdateException(UpdateFailure.INVALID_METADATA)
        }
    if (response.schema != SERVER_LATEST_SCHEMA || response.protocol != SERVER_UPDATE_PROTOCOL) {
        throw UpdateException(UpdateFailure.INVALID_METADATA)
    }
    val release = response.release ?: return ServerLatest.NoRelease
    if (!release.isWellFormed()) throw UpdateException(UpdateFailure.INVALID_METADATA)
    return ServerLatest.Release(release)
}

internal fun ServerRelease.isWellFormed(): Boolean =
    versionCode in 1..MAX_VERSION_CODE &&
        SHA256_HEX.matches(sha256) &&
        RELEASE_ID.matches(releaseId) &&
        releaseId == "v$versionCode-${sha256.take(16)}" &&
        // Content-addressed and fixed: the client never follows a server-chosen URL.
        apkPath == "releases/$releaseId/apk" &&
        sizeBytes in 1..MAX_UPDATE_APK_BYTES &&
        signerSha256.size in 1..8 &&
        signerSha256.toSet().size == signerSha256.size &&
        signerSha256.all { SHA256_HEX.matches(it) } &&
        applicationId.isNotBlank() &&
        versionName.isNotBlank() &&
        versionName.length <= 128 &&
        versionName.none { it.isISOControl() } &&
        notes.length <= MAX_NOTES
