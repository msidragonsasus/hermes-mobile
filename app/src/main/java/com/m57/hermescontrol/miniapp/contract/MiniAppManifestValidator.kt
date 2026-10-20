package com.m57.hermescontrol.miniapp.contract

import androidx.navigation3.runtime.NavKey
import java.net.URI

/** Validation checks a declaration, not a signature, permission grant, renderer or server availability. */
class ValidatedMiniApp internal constructor(
    val manifest: MiniAppManifest,
    val permissions: List<MiniAppPermission>,
    val nativeEntry: NavKey?,
)

object MiniAppManifestValidator {
    private val appId = Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")
    private val featureId = Regex("^[a-z][a-z0-9.-]+$")
    private val contributionId = Regex("^[a-z][a-z0-9._-]+$")
    private val locale = Regex("^[a-z]{2,3}(?:-[A-Z]{2})?$")
    private val version =
        Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
    private val hash = Regex("^[A-Fa-f0-9]{64}$")
    private val route = Regex("^[A-Za-z][A-Za-z0-9._/-]*$")
    private val path = Regex("^/[A-Za-z0-9._~/-]*$")

    fun validate(
        manifest: MiniAppManifest,
        nativeFeatures: NativeMiniAppFeatures,
        hostVersion: String,
    ): ValidatedMiniApp {
        val permissions = validateMetadata(manifest)
        manifest.minHostVersion?.let {
            try {
                checkVersion(hostVersion)
            } catch (_: MiniAppContractException) {
                throw MiniAppContractException(MiniAppContractError.HOST_VERSION)
            }
            check(compareVersions(hostVersion, it) >= 0, MiniAppContractError.HOST_VERSION)
        }
        val native =
            (manifest.entry as? MiniAppEntry.Native)?.let {
                nativeFeatures.resolve(it.featureId, it.initialRoute)
                    ?: throw MiniAppContractException(MiniAppContractError.UNKNOWN_NATIVE_FEATURE)
            }
        return ValidatedMiniApp(manifest, permissions, native)
    }

    /** Used for bundled, explicitly non-runnable examples before a native feature/renderer exists. */
    fun validateMetadata(manifest: MiniAppManifest): List<MiniAppPermission> {
        check(manifest.schemaVersion == "1.0", MiniAppContractError.UNSUPPORTED_SCHEMA)
        check(manifest.id.length in 3..80 && appId.matches(manifest.id))
        localized(manifest.name)
        manifest.description?.let(::localized)
        checkVersion(manifest.version)
        manifest.minHostVersion?.let(::checkVersion)
        check(manifest.publisher.id.length <= 80 && featureId.matches(manifest.publisher.id))
        checkText(manifest.publisher.name, 100)
        unique(manifest.permissions)
        val permissions =
            manifest.permissions.map {
                MiniAppPermission.parse(it) ?: throw MiniAppContractException(MiniAppContractError.UNKNOWN_PERMISSION)
            }
        val network = origins(manifest.network.allowedOrigins)
        when (val entry = manifest.entry) {
            is MiniAppEntry.Native -> {
                check(entry.featureId.length <= 80 && featureId.matches(entry.featureId))
                entry.initialRoute?.let(::checkRoute)
            }

            is MiniAppEntry.Sdui -> {
                check(entry.componentVersion == "1.0", MiniAppContractError.UNSUPPORTED_SCHEMA)
                checkRoute(entry.initialRoute)
                val prefix = "/api/plugins/${manifest.id}/mobile/"
                val uri = uri(entry.documentUrl)
                val documentPath =
                    if (uri.isAbsolute) {
                        remoteUrl(entry.documentUrl, network)
                    } else {
                        check(
                            uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null,
                            MiniAppContractError.INVALID_PATH,
                        )
                        uri.rawPath.orEmpty().also(::checkPath)
                    }
                check(
                    documentPath.startsWith(prefix) && documentPath.length > prefix.length,
                    MiniAppContractError.INVALID_PATH,
                )
            }

            is MiniAppEntry.Web -> {
                check(entry.messageProtocolVersion == "1.0", MiniAppContractError.UNSUPPORTED_SCHEMA)
                check(entry.allowedOrigins.isNotEmpty(), MiniAppContractError.INVALID_ORIGIN)
                val webOrigins = origins(entry.allowedOrigins)
                check(network.containsAll(webOrigins), MiniAppContractError.INVALID_ORIGIN)
                remoteUrl(entry.url, webOrigins)
            }
        }
        when (val icon = manifest.icon) {
            is MiniAppIcon.Material -> {
                checkText(icon.name, 80)
            }

            is MiniAppIcon.Remote -> {
                remoteUrl(icon.url, network)
                icon.sha256?.let { check(hash.matches(it)) }
            }

            null -> {}
        }
        check(manifest.offline.maxAgeSeconds in 0..2592000)
        manifest.integrity?.let {
            it.manifestSha256?.let { value -> check(hash.matches(value)) }
            it.bundleSha256?.let { value -> check(hash.matches(value)) }
            it.signature?.let { value -> checkText(value, 4096) }
            it.keyId?.let { value -> checkText(value, 256) }
        }
        val routes =
            manifest.contributions.run {
                homeCards + composerActions + activityRenderers + settingsSections + notificationRoutes
            }
        unique(routes.map { it.id } + manifest.contributions.commands.map { it.id })
        routes.forEach {
            check(it.id.length <= 128 && contributionId.matches(it.id))
            checkRoute(it.route)
            check(it.priority in -1000..1000)
            it.renderer?.let(::checkRoute)
        }
        manifest.contributions.commands.forEach {
            check(it.id.length <= 128 && contributionId.matches(it.id))
            localized(it.label)
            it.description?.let(::localized)
            checkRoute(it.action)
            unique(it.keywords)
            it.keywords.forEach { keyword -> checkText(keyword, 40) }
        }
        return permissions
    }

    private fun localized(values: Map<String, String>) {
        check(values.isNotEmpty())
        values.forEach { (language, text) ->
            check(locale.matches(language))
            checkText(text, 240)
        }
    }

    private fun checkText(
        value: String,
        maxLength: Int,
    ) = check(value.isNotBlank() && value.codePointCount(0, value.length) <= maxLength)

    private fun checkVersion(value: String) {
        check(value.length <= 128 && version.matches(value))
        val prerelease = version.matchEntire(value)!!.groupValues[4]
        if (prerelease.isNotEmpty()) {
            check(
                prerelease.split('.').all {
                    it.isNotEmpty() &&
                        !(it.length > 1 && it.all(Char::isDigit) && it.startsWith('0'))
                },
            )
        }
        if ('+' in value) check(value.substringAfter('+').split('.').all(String::isNotEmpty))
    }

    private fun unique(values: List<String>) =
        check(values.distinct().size == values.size, MiniAppContractError.DUPLICATE)

    private fun checkRoute(value: String) {
        check(
            value.length <= 256 && route.matches(value) && !value.contains("..") && !value.contains("//"),
            MiniAppContractError.INVALID_PATH,
        )
    }

    private fun checkPath(value: String) {
        check(
            value.length <= 2048 && path.matches(value) && !value.contains("//") &&
                value.split('/').none { it == "." || it == ".." },
            MiniAppContractError.INVALID_PATH,
        )
    }

    private fun uri(value: String): URI =
        try {
            URI(value)
        } catch (_: Exception) {
            throw MiniAppContractException(MiniAppContractError.INVALID_ORIGIN)
        }

    private fun origin(uri: URI): String {
        check(
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port in 1..65535),
            MiniAppContractError.INVALID_ORIGIN,
        )
        return "https://${uri.host.lowercase()}" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    }

    private fun origins(values: List<String>): Set<String> {
        val result =
            values.map {
                val parsed = uri(it)
                check(
                    parsed.rawPath in listOf("", "/") && parsed.rawQuery == null && parsed.rawFragment == null,
                    MiniAppContractError.INVALID_ORIGIN,
                )
                origin(parsed)
            }
        unique(result)
        return result.toSet()
    }

    private fun remoteUrl(
        value: String,
        allowed: Set<String>,
    ): String {
        val parsed = uri(value)
        check(
            origin(parsed) in allowed && parsed.rawQuery == null && parsed.rawFragment == null,
            MiniAppContractError.INVALID_ORIGIN,
        )
        return parsed.rawPath.ifEmpty { "/" }.also(::checkPath)
    }

    private fun compareVersions(
        left: String,
        right: String,
    ): Int {
        checkVersion(left)
        checkVersion(right)
        val a = version.matchEntire(left)!!
        val b = version.matchEntire(right)!!
        for (part in 1..3) {
            val comparison = a.groupValues[part].toBigInteger().compareTo(b.groupValues[part].toBigInteger())
            if (comparison != 0) return comparison
        }
        val preA = a.groupValues[4]
        val preB = b.groupValues[4]
        if (preA.isEmpty() || preB.isEmpty()) {
            return when {
                preA == preB -> 0
                preA.isEmpty() -> 1
                else -> -1
            }
        }
        val partsA = preA.split('.')
        val partsB = preB.split('.')
        for ((partA, partB) in partsA.zip(partsB)) {
            val numberA = partA.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
            val numberB = partB.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
            val comparison =
                when {
                    numberA != null && numberB != null -> numberA.compareTo(numberB)
                    numberA != null -> -1
                    numberB != null -> 1
                    else -> partA.compareTo(partB)
                }
            if (comparison != 0) return comparison
        }
        return partsA.size.compareTo(partsB.size)
    }

    private fun check(
        condition: Boolean,
        reason: MiniAppContractError = MiniAppContractError.INVALID_FIELD,
    ) {
        if (!condition) throw MiniAppContractException(reason)
    }
}
