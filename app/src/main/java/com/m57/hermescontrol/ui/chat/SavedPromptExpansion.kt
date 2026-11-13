package com.m57.hermescontrol.ui.chat

import androidx.compose.runtime.saveable.listSaver
import java.security.MessageDigest

/** Carries identity in saved state: rememberSaveable inputs are not checked on restoration. */
internal data class SavedPromptExpansion(
    val messageId: String,
    val contentFingerprint: String,
    val expanded: Boolean,
) {
    fun expandedFor(
        messageId: String,
        contentFingerprint: String,
    ): Boolean = expanded && this.messageId == messageId && this.contentFingerprint == contentFingerprint

    companion object {
        val Saver =
            listSaver<SavedPromptExpansion, Any>(
                save = { listOf(it.messageId, it.contentFingerprint, it.expanded) },
                restore = { SavedPromptExpansion(it[0] as String, it[1] as String, it[2] as Boolean) },
            )
    }
}

/** Store a fixed-size fingerprint rather than duplicating a potentially huge prompt in saved state. */
internal fun promptContentFingerprint(content: String): String =
    MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }
