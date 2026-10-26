package com.m57.hermescontrol.ui.bots.group

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.m57.hermescontrol.data.model.ProfileInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class GroupTurnRecord(
    val id: String,
    val userMessageId: String,
    val responseMessageId: String,
    val member: String,
    val runtimeSessionId: String,
    val storedSessionId: String?,
    val promptHash: String,
    val delivery: GroupDelivery = GroupDelivery.UNKNOWN,
)

@Serializable
data class GroupConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val messages: List<GroupChatMessage> = emptyList(),
    val turns: List<GroupTurnRecord> = emptyList(),
    val draft: String = "",
    val pendingServerRequest: GroupServerRequest? = null,
    val generatedTitle: String? = null,
)

@Serializable
data class GroupConversationArchive(
    val version: Int = 1,
    val legacyImported: Boolean = false,
    val members: List<ProfileInfo> = emptyList(),
    val activeId: String,
    val conversations: List<GroupConversation>,
    val displayName: String? = null,
)

/** Read failures never become an empty archive. All writes must be durable before submit. */
interface GroupConversationStore {
    fun read(key: String): GroupConversationArchive?

    fun write(
        key: String,
        archive: GroupConversationArchive,
    )
}

class MemoryGroupConversationStore : GroupConversationStore {
    private val values = mutableMapOf<String, GroupConversationArchive>()

    @Synchronized
    override fun read(key: String): GroupConversationArchive? = values[key]

    @Synchronized
    override fun write(
        key: String,
        archive: GroupConversationArchive,
    ) {
        values[key] = archive
    }
}

class EncryptedGroupConversationStore(
    context: Context,
) : GroupConversationStore {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            "hermes_group_conversations",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Synchronized
    override fun read(key: String): GroupConversationArchive? =
        prefs.getString(groupPromptHash(key), null)?.let { raw ->
            json.decodeFromString<GroupConversationArchive>(raw).also {
                check(it.version == 1 && it.conversations.isNotEmpty())
                check(
                    it.conversations
                        .map(GroupConversation::id)
                        .distinct()
                        .size == it.conversations.size,
                )
                check(it.conversations.any { conversation -> conversation.id == it.activeId })
            }
        }

    @Synchronized
    override fun write(
        key: String,
        archive: GroupConversationArchive,
    ) {
        check(prefs.edit().putString(groupPromptHash(key), json.encodeToString(archive)).commit())
    }
}

internal fun groupConversationScope(): String =
    listOf(
        com.m57.hermescontrol.data.local.AuthManager.baseUrlFlow.value,
        com.m57.hermescontrol.data.local.AuthManager.selectedProfileFlow.value,
        com.m57.hermescontrol.data.local.AuthManager.activeProfileId.value,
    ).joinToString("\u0000")

internal fun legacyGroupConversationId(key: String): String =
    UUID.nameUUIDFromBytes("$key/legacy".toByteArray(Charsets.UTF_8)).toString()

internal fun groupPromptHash(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun GroupConversation.recoverInterrupted(): GroupConversation =
    copy(
        messages =
            messages.map { message ->
                message.copy(
                    isStreaming = false,
                    isIncomplete = message.isIncomplete || message.isStreaming,
                    deliveries =
                        message.deliveries.mapValues { (_, delivery) ->
                            when (delivery) {
                                GroupDelivery.PENDING -> GroupDelivery.NOT_SENT
                                GroupDelivery.ACCEPTED -> GroupDelivery.UNKNOWN
                                else -> delivery
                            }
                        },
                )
            },
        turns =
            turns.map { turn ->
                if (turn.delivery == GroupDelivery.ACCEPTED) turn.copy(delivery = GroupDelivery.UNKNOWN) else turn
            },
    )
