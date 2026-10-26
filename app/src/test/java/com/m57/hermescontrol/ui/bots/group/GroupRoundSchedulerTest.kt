package com.m57.hermescontrol.ui.bots.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GroupRoundSchedulerTest {
    @Test fun cyclesRemainFiniteEvenWhenEveryTurnMentionsAllPeers() {
        val order = listOf("a", "b", "c", "d")
        val scheduler = GroupRoundScheduler(order, 3, 2)
        val turns = mutableListOf<GroupDiscussionTurn>()
        while (scheduler.hasPending) {
            val turn = requireNotNull(scheduler.nextTurn())
            turns.add(turn)
            scheduler.prioritize(order + order + "invalid", turn.member)
            check(turns.size <= 14) { "Scheduler exceeded rounds plus bounded extras" }
        }
        assertEquals(14, turns.size)
        assertEquals(listOf(1, 2, 3), turns.map { it.round }.distinct())
        for (round in 1..3) assertEquals(order.toSet(), turns.filter { it.round == round }.map { it.member }.toSet())
        assertNull(scheduler.nextTurn())
    }

    @Test fun latestMentionsTakePriorityWithoutDroppingAlreadyPromotedSlots() {
        val scheduler = GroupRoundScheduler(listOf("a", "b", "c", "d"), 1, 0)
        assertEquals("a", scheduler.nextTurn()!!.member)
        scheduler.prioritize(listOf("d", "c", "d", "a", "invalid"), "a")
        assertEquals("d", scheduler.nextTurn()!!.member)
        scheduler.prioritize(listOf("b"), "d")
        assertEquals("b", scheduler.nextTurn()!!.member)
        assertEquals("c", scheduler.nextTurn()!!.member)
        assertFalse(scheduler.hasPending)
    }

    @Test fun directlyAddressedSubsetCannotRecruitPeersOrAddHandoffs() {
        val scheduler = GroupRoundScheduler(listOf("b", "d"), 1, 20, allowMentions = false)
        assertEquals("b", scheduler.nextTurn()!!.member)
        scheduler.prioritize(listOf("a", "b", "d"), "b")
        assertEquals("d", scheduler.nextTurn()!!.member)
        scheduler.prioritize(listOf("b"), "d")
        assertFalse(scheduler.hasPending)
    }

    @Test fun emptyOrBlankRosterNeverSchedulesAPhantomTurn() {
        for (order in listOf(emptyList(), listOf("", " "))) {
            val scheduler = GroupRoundScheduler(order, 999, 999)
            assertFalse(scheduler.hasPending)
            assertNull(scheduler.nextTurn())
        }
    }
}
