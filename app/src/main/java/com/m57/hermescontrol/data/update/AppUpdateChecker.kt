package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Latest release metadata from the GitHub releases API (issue #867) — the
 * public upstream source. Fields map to the JSON via the app's shared
 * snake_case [OkHttpProvider.json].
 */
@Serializable
data class UpdateInfo(
    val tagName: String = "",
    val body: String = "",
    val assets: List<Asset> = emptyList(),
) {
    @Serializable
    data class Asset(
        val name: String = "",
        val size: Long = 0L,
        val browserDownloadUrl: String = "",
        /** GitHub asset digest, `sha256:<hex>`; required before an upstream APK is offered. */
        val digest: String = "",
    ) {
        val sha256: String?
            get() =
                digest
                    .takeIf { it.startsWith("sha256:") }
                    ?.removePrefix("sha256:")
                    ?.takeIf { SHA256_HEX.matches(it) }
    }

    /** The release APK asset — release.yml ships `hermes-mobile-<tag>.apk`. */
    val apkAsset: Asset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}

/** Strip a leading 'v' from a release tag ("v1.21.0" → "1.21.0"). */
fun normalizedVersion(tag: String): String = tag.trim().removePrefix("v")

/**
 * True when [latest] is strictly newer than [current]. Numeric dot-segment
 * comparison ("1.21.0" > "1.2"). Non-numeric suffixes (e.g. "1.0-dev")
 * compare as 0; an unparseable version never claims an update exists.
 */
fun isNewerVersion(
    latest: String,
    current: String,
): Boolean {
    val a = versionSegments(latest) ?: return false
    val b = versionSegments(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun versionSegments(version: String): List<Int>? {
    val cleaned = normalizedVersion(version)
    if (cleaned.isBlank()) return null
    return cleaned.split('.').map { segment ->
        // Leading numeric portion of each segment: "0-dev" → 0, "21" → 21.
        segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
}

/** `<connected base>/api/plugins/mobile_updates/<path>`, keeping any reverse-proxy prefix. */
fun serverUpdatesUrl(
    scope: UpdateScope,
    path: String,
): HttpUrl = ServerEndpoint.parseForBuild(scope.baseUrl).resolve("$SERVER_UPDATES_PATH/$path")

/**
 * Fetches update metadata and APKs for both sources (issues #867, connected
 * server updates). Network methods are `open` so tests can fake them.
 *
 * - Upstream: [upstreamClient] carries no app credentials.
 * - Connected server: every request first obtains an [UpdateBinding] for the
 *   candidate's exact scope ([bindings]); if the connection changed in any way
 *   nothing is sent. [serverClientFactory] ([boundUpdateClient]) then attaches
 *   only that snapshot, only to its own origin and plugin path. Every URL is
 *   built from the bound base, never taken from the server.
 */
open class AppUpdateChecker(
    private val upstreamClient: OkHttpClient = OkHttpProvider.base,
    private val bindings: (UpdateScope) -> UpdateBinding? = AuthManagerUpdateScopes::bind,
    private val serverClientFactory: (UpdateBinding) -> OkHttpClient = { boundUpdateClient(it) },
) {
    private fun bound(scope: UpdateScope): UpdateBinding {
        if (scope.source != UpdateSource.CONNECTED_SERVER) throw UpdateException(UpdateFailure.CONNECTION_CHANGED)
        return bindings(scope) ?: throw UpdateException(UpdateFailure.CONNECTION_CHANGED)
    }

    /**
     * Fetch the latest upstream release metadata. Returns null when there is
     * no release yet (404) or the body can't be parsed. Throws [IOException]
     * on network failure so the caller can surface a friendly error.
     */
    open suspend fun fetchLatestRelease(): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("https://api.github.com/repos/Hy4ri/hermes-mobile/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            upstreamClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string()
                parseUpdateInfo(body)
            }
        }

    /** `GET .../mobile_updates/latest` on the bound connection. Throws [UpdateException] or [IOException]. */
    open suspend fun fetchServerLatest(scope: UpdateScope): ServerLatest =
        withContext(Dispatchers.IO) {
            val binding = bound(scope)
            val request =
                Request
                    .Builder()
                    .url(serverUpdatesUrl(binding.scope, "latest"))
                    .header("Accept", "application/json")
                    .build()
            val call = serverClientFactory(binding).newCall(request)
            val executed =
                try {
                    call.execute()
                } catch (e: BoundRequestRefusedException) {
                    throw UpdateException(UpdateFailure.CONNECTION_CHANGED)
                }
            executed.use { response ->
                if (response.code in 300..399) throw UpdateException(UpdateFailure.REDIRECT_REFUSED)
                when (response.code) {
                    401, 403 -> throw UpdateException(UpdateFailure.LOGIN_REQUIRED)
                    404 -> throw UpdateException(UpdateFailure.SERVER_UPDATES_MISSING)
                    503 -> throw UpdateException(UpdateFailure.SERVER_UPDATES_DISABLED)
                }
                if (!response.isSuccessful) throw UpdateException(UpdateFailure.CHECK_FAILED)
                val source = response.body.source()
                if (source.request(MAX_METADATA_BYTES + 1)) throw UpdateException(UpdateFailure.INVALID_METADATA)
                parseServerLatest(source.readUtf8())
            }
        }

    /**
     * Download [candidate] to [dest], reporting progress 0..1. Returns the
     * received bytes' SHA-256 and size; throws [UpdateException] (partial file
     * deleted) on any failure. Verification of the archive is the caller's job.
     */
    open suspend fun download(
        candidate: UpdateCandidate,
        dest: File,
        onProgress: (Float) -> Unit,
    ): DownloadedApk {
        val url = candidate.downloadUrl.toHttpUrlOrNull() ?: throw UpdateException(UpdateFailure.INVALID_METADATA)
        val client =
            when (candidate.source) {
                UpdateSource.CONNECTED_SERVER -> {
                    val base = ServerEndpoint.parseForBuild(candidate.scope.baseUrl).baseUrl
                    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) {
                        throw UpdateException(UpdateFailure.CONNECTION_CHANGED)
                    }
                    val binding = bound(candidate.scope)
                    if (!binding.allows(url)) throw UpdateException(UpdateFailure.CONNECTION_CHANGED)
                    serverClientFactory(binding)
                }

                // GitHub serves assets via an https redirect to its object store; never downgrade to http.
                UpdateSource.UPSTREAM_GITHUB -> {
                    upstreamClient.newBuilder().followSslRedirects(false).build()
                }
            }
        return UpdateDownloader(client).download(url, candidate.sizeBytes, dest, onProgress)
    }

    private companion object {
        const val MAX_METADATA_BYTES = 256L * 1024
    }
}

/** Parse a GitHub `releases/latest` JSON body into [UpdateInfo], or null. */
fun parseUpdateInfo(json: String): UpdateInfo? =
    runCatching {
        OkHttpProvider.json.decodeFromString<UpdateInfo>(json)
    }.getOrNull()
