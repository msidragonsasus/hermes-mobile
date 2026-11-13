package com.m57.hermescontrol.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Connected-server update contract: strict metadata parsing, the pre-download
 * offer policy and post-download archive verification. Pure JVM, synthetic data.
 */
class UpdatePolicyTest {
    private val pkg = "com.m57.hermescontrol"
    private val signer = "a".repeat(64)
    private val sha = "0123456789abcdef" + "1".repeat(48)
    private val scope = UpdateScope(UpdateSource.CONNECTED_SERVER, "http://100.64.0.2:9119/", credentialPresent = true)
    private val installed = ApkIdentity(pkg, 1, "1.0-dev", setOf(signer))

    private fun release(
        versionCode: Long = 2,
        sha256: String = sha,
        releaseId: String = "v$versionCode-${sha256.take(16)}",
        apkPath: String = "releases/$releaseId/apk",
        signers: List<String> = listOf(signer),
        applicationId: String = pkg,
        sizeBytes: Long = 1234,
    ) = ServerRelease(releaseId, applicationId, versionCode, "1.0.$versionCode", sha256, sizeBytes, signers, apkPath)

    private fun latestJson(releaseJson: String?) =
        """{"schema":"hermes-mobile-update-latest","protocol":1,"release":${releaseJson ?: "null"}}"""

    private fun releaseJson(
        id: String = "v2-0123456789abcdef",
        path: String = "releases/v2-0123456789abcdef/apk",
        size: String = "1234",
    ) = """{"release_id":"$id","application_id":"$pkg","version_code":2,"version_name":"1.0.2",
        "sha256":"$sha","size_bytes":$size,"signer_sha256":["$signer"],"apk_path":"$path",
        "notes":"Fix","published_at":"2026-10-27T00:00:00Z","future_field":true}"""

    private fun assertInvalid(body: String) {
        try {
            parseServerLatest(body)
            fail("expected INVALID_METADATA for $body")
        } catch (e: UpdateException) {
            assertEquals(UpdateFailure.INVALID_METADATA, e.failure)
        }
    }

    private fun candidate(
        versionCode: Long? = 2,
        source: UpdateSource = UpdateSource.CONNECTED_SERVER,
        versionName: String = "1.0.2",
    ) = UpdateCandidate(source, scope, 0, versionName, versionCode, sha, 1234, setOf(signer), "http://x/")

    private fun downloaded(
        sha256: String = sha,
        size: Long = 1234,
    ) = DownloadedApk(File("unused"), sha256, size)

    // ── Metadata protocol ───────────────────────────────────────────────

    @Test
    fun parse_acceptsDocumentedShapeAndIgnoresAdditiveFields() {
        val latest = parseServerLatest(latestJson(releaseJson()))
        val parsed = (latest as ServerLatest.Release).release
        assertEquals(2L, parsed.versionCode)
        assertEquals(listOf(signer), parsed.signerSha256)
        assertEquals("releases/v2-0123456789abcdef/apk", parsed.apkPath)
    }

    @Test
    fun parse_nullReleaseMeansNothingPublished() {
        assertEquals(ServerLatest.NoRelease, parseServerLatest(latestJson(null)))
    }

    @Test
    fun parse_rejectsWrongSchemaProtocolAndMalformedJson() {
        assertInvalid("""{"schema":"other","protocol":1,"release":null}""")
        assertInvalid("""{"schema":"hermes-mobile-update-latest","protocol":2,"release":null}""")
        assertInvalid("not json")
        assertInvalid("")
    }

    @Test
    fun parse_rejectsServerChosenUrlsAndInconsistentIds() {
        assertInvalid(latestJson(releaseJson(path = "https://evil.example/app.apk")))
        assertInvalid(latestJson(releaseJson(path = "releases/../../etc/apk")))
        assertInvalid(latestJson(releaseJson(id = "v3-0123456789abcdef", path = "releases/v3-0123456789abcdef/apk")))
        assertInvalid(latestJson(releaseJson(size = "0")))
        assertInvalid(latestJson(releaseJson(size = "${MAX_UPDATE_APK_BYTES + 1}")))
    }

    @Test
    fun wellFormed_rejectsBadDigestsAndSigners() {
        assertTrue(release().isWellFormed())
        assertTrue(!release(sha256 = "Z".repeat(64)).isWellFormed())
        assertTrue(!release(signers = emptyList()).isWellFormed())
        assertTrue(!release(signers = listOf(signer, signer)).isWellFormed())
        assertTrue(!release(versionCode = 0).isWellFormed())
    }

    // ── Pre-download offer ──────────────────────────────────────────────

    @Test
    fun serverOffer_newerSameSignerIsAvailableAndBoundToScope() {
        val offer = UpdatePolicy.offerFromServer(release(), scope, 7, "http://x/apk", installed, pkg)
        val available = offer as UpdateCheck.Available
        assertEquals(scope, available.candidate.scope)
        assertEquals(7L, available.candidate.generation)
        assertEquals(2L, available.candidate.versionCode)
    }

    @Test
    fun serverOffer_sameOrOlderVersionCodeIsUpToDate() {
        val offer = UpdatePolicy.offerFromServer(release(versionCode = 1), scope, 0, "http://x/apk", installed, pkg)
        assertEquals(UpdateCheck.UpToDate("1.0.1"), offer)
    }

    @Test
    fun serverOffer_failsClosedOnSignerPackageOrUnknownInstalledIdentity() {
        val cases =
            listOf(
                Triple(release(signers = listOf("b".repeat(64))), installed, UpdateFailure.SIGNER_MISMATCH),
                Triple(release(applicationId = "com.example.other"), installed, UpdateFailure.WRONG_APPLICATION),
                Triple(release(), null, UpdateFailure.INSTALLED_IDENTITY_UNAVAILABLE),
                Triple(
                    release(),
                    installed.copy(signerSha256 = emptySet()),
                    UpdateFailure.INSTALLED_IDENTITY_UNAVAILABLE,
                ),
                Triple(release(), installed.copy(hasPastSigners = true), UpdateFailure.SIGNER_ROTATION_UNSUPPORTED),
            )
        for ((rel, current, expected) in cases) {
            try {
                UpdatePolicy.offerFromServer(rel, scope, 0, "http://x/apk", current, pkg)
                fail("expected $expected")
            } catch (e: UpdateException) {
                assertEquals(expected, e.failure)
            }
        }
    }

    @Test
    fun upstreamOffer_requiresPublishedDigest() {
        val asset = UpdateInfo.Asset("hermes-mobile-v9.0.0.apk", 1234, "https://github.com/x/y.apk")
        val upstream = UpdateScope(UpdateSource.UPSTREAM_GITHUB)
        try {
            UpdatePolicy.offerFromUpstream(UpdateInfo("v9.0.0", "", listOf(asset)), upstream, 0, "1.0")
            fail("expected NO_DIGEST")
        } catch (e: UpdateException) {
            assertEquals(UpdateFailure.NO_DIGEST, e.failure)
        }
        val withDigest = asset.copy(digest = "sha256:$sha")
        val offer = UpdatePolicy.offerFromUpstream(UpdateInfo("v9.0.0", "", listOf(withDigest)), upstream, 0, "1.0")
        val candidate = (offer as UpdateCheck.Available).candidate
        assertNull("upstream publishes no signer; the archive check decides", candidate.signerSha256)
        assertEquals(sha, candidate.sha256)
    }

    // ── Post-download verification ──────────────────────────────────────

    @Test
    fun verify_acceptsOnlyExactBytesPackageNewerVersionAndSameSigner() {
        val archive = ApkIdentity(pkg, 2, "1.0.2", setOf(signer))
        assertNull(UpdatePolicy.verifyArchive(candidate(), downloaded(), archive, installed, pkg))
    }

    @Test
    fun verify_rejectsEveryMismatch() {
        val good = ApkIdentity(pkg, 2, "1.0.2", setOf(signer))
        val cases =
            listOf(
                Triple(downloaded(size = 1233), good, UpdateFailure.SIZE_MISMATCH),
                Triple(downloaded(sha256 = "f".repeat(64)), good, UpdateFailure.HASH_MISMATCH),
                Triple(downloaded(), null, UpdateFailure.ARCHIVE_UNREADABLE),
                Triple(downloaded(), good.copy(packageName = "com.example.other"), UpdateFailure.WRONG_APPLICATION),
                Triple(downloaded(), good.copy(signerSha256 = emptySet()), UpdateFailure.UNSIGNED),
                Triple(downloaded(), good.copy(signerSha256 = setOf("b".repeat(64))), UpdateFailure.SIGNER_MISMATCH),
                Triple(
                    downloaded(),
                    good.copy(signerSha256 = setOf(signer, "b".repeat(64))),
                    UpdateFailure.SIGNER_MISMATCH,
                ),
                Triple(downloaded(), good.copy(hasPastSigners = true), UpdateFailure.SIGNER_ROTATION_UNSUPPORTED),
                Triple(downloaded(), good.copy(versionCode = 1), UpdateFailure.NOT_NEWER),
                Triple(downloaded(), good.copy(versionCode = 3), UpdateFailure.VERSION_MISMATCH),
                Triple(downloaded(), good.copy(versionName = "1.0.3"), UpdateFailure.VERSION_MISMATCH),
            )
        for ((file, archive, expected) in cases) {
            assertEquals(expected, UpdatePolicy.verifyArchive(candidate(), file, archive, installed, pkg))
        }
        assertEquals(
            UpdateFailure.INSTALLED_IDENTITY_UNAVAILABLE,
            UpdatePolicy.verifyArchive(candidate(), downloaded(), good, null, pkg),
        )
    }

    @Test
    fun verify_upstreamArchiveSignedByAnotherKeyIsNeverInstallable() {
        val upstream = candidate(versionCode = null, source = UpdateSource.UPSTREAM_GITHUB, versionName = "v1.0.2")
        val foreign = ApkIdentity(pkg, 2, "1.0.2", setOf("c".repeat(64)))
        assertEquals(
            UpdateFailure.SIGNER_MISMATCH,
            UpdatePolicy.verifyArchive(upstream, downloaded(), foreign, installed, pkg),
        )
        val sameKey = foreign.copy(signerSha256 = setOf(signer))
        assertNull(UpdatePolicy.verifyArchive(upstream, downloaded(), sameKey, installed, pkg))
    }
}
