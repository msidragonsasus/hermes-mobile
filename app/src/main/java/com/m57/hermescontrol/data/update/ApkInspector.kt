package com.m57.hermescontrol.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/** Reads package identity of the installed app and of a downloaded archive. */
interface ApkInspector {
    fun installed(): ApkIdentity?

    fun archive(file: File): ApkIdentity?
}

/**
 * PackageManager-backed identity. API 28+ uses SigningInfo (current signers
 * plus rotation history); API 26-27 uses the legacy `signatures` array. Any
 * failure returns null, which the policy treats as fail-closed.
 */
class PackageManagerApkInspector(
    private val context: Context,
) : ApkInspector {
    override fun installed(): ApkIdentity? =
        runCatching {
            val pm = context.packageManager
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                }
            identity(info)
        }.getOrNull()

    @Suppress("DEPRECATION")
    override fun archive(file: File): ApkIdentity? =
        runCatching {
            val pm = context.packageManager
            val path = file.absolutePath
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Some API 28 builds return archives without signingInfo; the legacy field still
                    // reports the archive's signer, and identity() fails closed if neither is present.
                    pm
                        .getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
                        ?.takeIf { it.signingInfo != null }
                        ?: pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
                } else {
                    pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
                }
            info?.let(::identity)
        }.getOrNull()

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo else null
        val signatures: List<Signature> =
            if (signing != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signing.apkContentsSigners?.toList().orEmpty()
            } else {
                info.signatures?.toList().orEmpty()
            }
        val rotated =
            signing != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                !signing.hasMultipleSigners() &&
                signing.hasPastSigningCertificates()
        return ApkIdentity(
            packageName = info.packageName.orEmpty(),
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            versionName = info.versionName,
            signerSha256 = signatures.map { sha256(it.toByteArray()) }.toSet(),
            hasPastSigners = rotated,
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()
}
