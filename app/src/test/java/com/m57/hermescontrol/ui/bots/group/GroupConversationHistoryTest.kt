package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.model.GroupChatRoomMeta
import com.m57.hermescontrol.data.model.GroupChatSyncFrom
import com.m57.hermescontrol.data.model.GroupChatSyncLogEntry
import com.m57.hermescontrol.data.model.GroupChatSyncSnapshot
import com.m57.hermescontrol.ui.bots.GroupInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupConversationHistoryTest {
    @Test
    fun generatedNameWinsOverAnOlderServersFallbackWithoutDuplicatingTheSession() {
        val store = MemoryGroupConversationStore()
        val room = GroupConversation(id = "saved", title = "設計方針の比較", generatedTitle = "設計方針の比較")
        store.write("scope\u0000team", GroupConversationArchive(activeId = room.id, conversations = listOf(room)))
        val cached = readGroupHistory(listOf(group("team", "開発会議")), "scope", store).entries
        val remote = listOf(GroupHistoryEntry("team", "saved", "開発会議", "最初の長い投稿", 999))
        val entry = mergeGroupHistory(remote, cached).single()
        assertEquals("開発会議：設計方針の比較", entry.label("新しい会話"))
        assertEquals(999L, entry.lastActivity)
        val restored =
            Json.decodeFromString<GroupConversation>(
                Json.encodeToString(GroupConversation.serializer(), room),
            )
        assertEquals("設計方針の比較", restored.generatedTitle)
        assertNull(Json.decodeFromString<GroupConversation>("{\"id\":\"older\"}").generatedTitle)
    }

    @Test
    fun allSessionsHaveDistinctTargetsEvenWithTheSameTitleAndIdInDifferentGroups() {
        val store = MemoryGroupConversationStore()
        val first = GroupConversation(id = "one", title = "同じテーマ", createdAt = 1, draft = "下書き")
        val second = GroupConversation(id = "two", title = "同じテーマ", createdAt = 3)
        store.write(
            "scope\u0000team",
            GroupConversationArchive(activeId = first.id, conversations = listOf(first, second)),
        )
        store.write("scope\u0000other", GroupConversationArchive(activeId = first.id, conversations = listOf(first)))
        val result = readGroupHistory(listOf(group("team", "開発会議"), group("other", "企画会議")), "scope", store)
        assertEquals(listOf("two", "one", "one"), result.entries.map { it.conversationId })
        assertEquals(
            3,
            result.entries
                .map { it.key }
                .distinct()
                .size,
        )
        assertEquals("開発会議：同じテーマ", result.entries.first().label("新しい会話"))
        assertEquals("one", store.read("scope\u0000team")!!.activeId)
        assertEquals(
            "下書き",
            store
                .read("scope\u0000team")!!
                .conversations
                .first()
                .draft,
        )
        assertTrue(readGroupHistory(listOf(group("team")), "different-scope", store).entries.isEmpty())
    }

    @Test
    fun legacyHistoryUsesTheExactMigrationIdWithoutCreatingAnArchive() {
        val store = MemoryGroupConversationStore()
        val room =
            group("team", "会議").copy(
                legacyLog = listOf(GroupChatSyncLogEntry(text = "過去の相談", from = GroupChatSyncFrom(kind = "user"))),
            )
        val entry = readGroupHistory(listOf(room), "scope", store).entries.single()
        assertEquals(legacyGroupConversationId("scope\u0000team"), entry.conversationId)
        assertEquals("会議：過去の相談", entry.label("新しい会話"))
        assertNull(store.read("scope\u0000team"))
    }

    @Test
    fun savedArchiveTakesPrecedenceOverTheLegacyLogAndEmptySessionsRemainSelectable() {
        val store = MemoryGroupConversationStore()
        val session = GroupConversation(id = "empty", createdAt = 5)
        store.write("scope\u0000team", GroupConversationArchive(activeId = session.id, conversations = listOf(session)))
        val room = group("team").copy(legacyLog = listOf(GroupChatSyncLogEntry(text = "stale")))
        val entry = readGroupHistory(listOf(room), "scope", store).entries.single()
        assertEquals("empty", entry.conversationId)
        assertEquals("team：新しい会話", entry.label("新しい会話"))
    }

    @Test
    fun unusedLegacySlotIsOmittedButItsDraftIsNeverHidden() {
        val store = MemoryGroupConversationStore()
        val legacy = GroupConversation(id = legacyGroupConversationId("scope\u0000team"))
        val fresh = GroupConversation(id = "fresh")
        val archive = GroupConversationArchive(activeId = fresh.id, conversations = listOf(legacy, fresh))
        store.write("scope\u0000team", archive)
        assertEquals(
            listOf("fresh"),
            readGroupHistory(listOf(group("team")), "scope", store).entries.map { it.conversationId },
        )
        store.write("scope\u0000team", archive.copy(conversations = listOf(legacy.copy(draft = "keep"), fresh)))
        assertEquals(2, readGroupHistory(listOf(group("team")), "scope", store).entries.size)
    }

    @Test
    fun unreadableArchiveIsReportedWithoutFallingBackToStaleHistoryOrWriting() {
        val broken =
            object : GroupConversationStore {
                override fun read(key: String): GroupConversationArchive? = error("unreadable")

                override fun write(
                    key: String,
                    archive: GroupConversationArchive,
                ) = error("must not write")
            }
        val result =
            readGroupHistory(
                listOf(group("team").copy(legacyLog = listOf(GroupChatSyncLogEntry(text = "stale")))),
                "scope",
                broken,
            )
        assertTrue(result.entries.isEmpty())
        assertEquals(setOf("team"), result.failedGroups)
    }

    @Test
    fun displayNameSurvivesSnapshotWritesWhileRoomKeyAndMembershipStayTheSame() {
        val room = GroupChatRoomMeta(name = "team", displayName = "開発会議")
        val snapshot = GroupChatSyncSnapshot(rooms = mapOf("name:team" to room))

        @Suppress("UNCHECKED_CAST")
        val rooms = snapshot.toMap()["rooms"] as Map<String, Map<String, Any?>>
        assertEquals("開発会議", rooms["name:team"]!!["displayName"])
        assertEquals("team", rooms["name:team"]!!["name"])
        assertNull(Json.decodeFromString<GroupChatRoomMeta>("{\"name\":\"legacy\"}").displayName)
    }

    private fun group(
        name: String,
        label: String = name,
    ) = GroupInfo(name, emptyList(), displayName = label)
}
