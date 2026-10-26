package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.ws.toJsonElement
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatMembershipTest {
    private fun room(members: String): GroupChatRoomMeta =
        Json.decodeFromString("""{"name":"Four","members":$members}""")

    @Test
    fun legacyBlankFifthMemberDoesNotBecomeARecipient() {
        val room = room("""["boss","idea1","idea2","deny",""]""")
        assertEquals(listOf("boss", "idea1", "idea2", "deny"), room.memberNames)
        assertEquals(5, room.members!!.size)
    }

    @Test
    fun emptyOrWhitespaceNameFallsBackToValidHandle() {
        val room = room("""[{"name":"","handle":"boss"},{"name":"  ","handle":"idea1"}]""")
        assertEquals(listOf("boss", "idea1"), room.memberNames)
    }

    @Test
    fun malformedNameCanUseHandleWithoutThrowing() {
        val room = room("""[{"name":{},"handle":"boss"},{"name":null,"handle":"idea1"}]""")
        assertEquals(listOf("boss", "idea1"), room.memberNames)
    }

    @Test
    fun missingOrNonStringIdentifiersNeverInventAProfile() {
        val room = room("""["", "  ",null,123,true,{},[],{"name":null},{"name":{}},{"handle":[]}]""")
        assertTrue(room.memberNames.isEmpty())
    }

    @Test
    fun identifierWhitespaceIsNormalizedButRawDataRemains() {
        val room = room("""["  boss ","\tidea1\n",{"handle":" deny "}]""")
        assertEquals(listOf("boss", "idea1", "deny"), room.memberNames)
        assertEquals("  boss ", (room.members!!.first() as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun duplicateIdentifiersProduceOneRecipientInFirstAppearanceOrder() {
        val room = room("""["boss",{"name":"boss"},{"handle":"idea1"},"idea1","deny"]""")
        assertEquals(listOf("boss", "idea1", "deny"), room.memberNames)
    }

    @Test
    fun explicitNameWinsAndDistinctRealIdentifiersArePreserved() {
        val room = room("""[{"name":"Boss","handle":"other"},"boss","team.bot-1"]""")
        assertEquals(listOf("Boss", "boss", "team.bot-1"), room.memberNames)
    }

    @Test
    fun snapshotWriteRetainsLegacyObjectsBlanksAndOtherRoomMetadata() {
        val raw = """["boss","",null,{"name":"","handle":"idea1","source":"peer"},{}]"""
        val room = room(raw).copy(picture = "synthetic-picture", revision = 7)
        val snapshot = GroupChatSyncSnapshot(version = 3, rooms = mapOf("name:Four" to room))
        val decoded = Json.decodeFromString<GroupChatSyncSnapshot>(snapshot.toMap().toJsonElement().toString())
        assertEquals(room, decoded.rooms!!["name:Four"])
        assertEquals(listOf("boss", "idea1"), decoded.rooms!!["name:Four"]!!.memberNames)
    }

    @Test
    fun normalFourNamesAndAbsentMembersKeepTheirMeaning() {
        assertEquals(listOf("boss", "idea1", "idea2", "deny"), room("""["boss","idea1","idea2","deny"]""").memberNames)
        assertTrue(GroupChatRoomMeta(name = "Four").memberNames.isEmpty())
    }
}
