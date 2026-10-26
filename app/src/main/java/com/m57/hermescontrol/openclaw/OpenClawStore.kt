package com.m57.hermescontrol.openclaw

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Keystore-backed storage, isolated from Hermes tokens/cookies and app backups. */
internal class OpenClawStore(
    context: Context,
    private val scope: String,
) {
    @Suppress("DEPRECATION")
    private val prefs =
        EncryptedSharedPreferences.create(
            "openclaw_secure_prefs",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun read(name: String): String? = prefs.getString("$scope:$name", null)

    fun write(
        name: String,
        text: String?,
    ) {
        val editor = prefs.edit()
        if (text == null) editor.remove("$scope:$name") else editor.putString("$scope:$name", text)
        check(editor.commit()) { "OpenClawの設定を保存できません" }
    }

    fun sessionName(
        kind: String,
        key: String,
    ): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest("${read("url")}|$key".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return "$kind:$digest"
    }

    fun identity(): ClawIdentity {
        val seed =
            read("identity")?.let { Base64.getDecoder().decode(it) }
                ?: Ed25519PrivateKeyParameters(SecureRandom()).encoded.also {
                    write("identity", Base64.getEncoder().encodeToString(it))
                }
        return ClawIdentity(seed)
    }

    fun saveConnection(
        url: String,
        token: String,
    ) {
        if (read("url") != url) {
            write("device-token", null)
            write("last-session", null)
            write("device-scopes", null)
        }
        // A changed shared secret may represent a new server at the same URL.
        if (read("token") != token) {
            write("device-token", null)
            write("device-scopes", null)
        }
        write("url", url)
        write("token", token)
    }
}

internal class ClawIdentity(
    seed: ByteArray,
) {
    private val privateKey = Ed25519PrivateKeyParameters(seed, 0)
    private val publicBytes = privateKey.generatePublicKey().encoded
    val id: String = MessageDigest.getInstance("SHA-256").digest(publicBytes).joinToString("") { "%02x".format(it) }
    val publicKey: String = Base64.getUrlEncoder().withoutPadding().encodeToString(publicBytes)

    /** Canonical v3 payload from openclaw/openclaw (MIT), packages/gateway-client/src/device-auth.ts. */
    fun sign(
        nonce: String,
        timestamp: Long,
        token: String?,
        scopes: List<String>,
    ): String {
        val payload =
            listOf(
                "v3",
                id,
                "openclaw-android",
                "ui",
                "operator",
                scopes.joinToString(","),
                timestamp.toString(),
                token.orEmpty(),
                nonce,
                "android",
                "",
            ).joinToString("|").toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(payload, 0, payload.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature())
    }
}
