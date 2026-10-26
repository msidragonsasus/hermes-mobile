package com.m57.hermescontrol.career

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CareerCompanyOrderTest {
    @Test
    fun filteredMoveKeepsHiddenSlotsAndSupportsBothDirections() {
        val original = listOf("a", "hidden", "b", "other", "c")
        val moved = moveVisibleCompany(original, listOf("a", "b", "c"), "a", "c")
        assertEquals(listOf("b", "hidden", "c", "other", "a"), moved)
        assertEquals(original, moveVisibleCompany(moved, listOf("b", "c", "a"), "a", "b"))
    }

    @Test
    fun invalidOrBoundaryMovesPreserveOrder() {
        val order = listOf("a", "b", "c")
        assertEquals(order, moveVisibleCompany(order, order, "a", "a"))
        assertEquals(order, moveVisibleCompany(order, order, "missing", "b"))
        assertEquals(order, moveVisibleCompany(order, listOf("a", "a"), "a", "b"))
        assertEquals(order, moveVisibleCompany(order, listOf("a", "missing"), "a", "missing"))
    }

    @Test
    fun savedOrderDropsDeletedAndAppendsNewCompanies() {
        val rows = listOf("a", "b", "new").map { CareerCompanyRow(it, it) }
        assertEquals(listOf("b", "a", "new"), orderedCompanies(rows, listOf("deleted", "b", "a")).map { it.id })
        assertEquals(rows, orderedCompanies(rows, null))
    }

    @Test
    fun companyCacheRoundTripPreservesOrderAndVersion() {
        val saved =
            CareerCompanies(companies = listOf("c", "a", "b").map { CareerCompanyRow(it, it) }, order_version = 8)
        val restored = Json.decodeFromString<CareerCompanies>(Json.encodeToString(CareerCompanies.serializer(), saved))
        assertEquals(saved, restored)
        assertEquals(null, Json.decodeFromString<CareerCompanies>("{}").order_version)
    }

    @Test
    fun dragUsesUnevenNeighbourCentersAndAllowsReversalAfterLayout() {
        val before = listOf(CompanyOrderSlot("a", 50f), CompanyOrderSlot("b", 196f))
        assertEquals("b", companyDragTarget(listOf("a", "b"), before, "a", 197f))
        val after = listOf(CompanyOrderSlot("b", 80f), CompanyOrderSlot("a", 226f))
        assertEquals("b", companyDragTarget(listOf("b", "a"), after, "a", 79f))
        assertEquals(null, companyDragTarget(listOf("b", "a"), after, "a", 150f))
    }

    @Test
    fun dragWaitsForPendingLayoutAndHandlesPartialViewport() {
        val stale = listOf(CompanyOrderSlot("a", 50f), CompanyOrderSlot("b", 160f))
        assertEquals(null, companyDragTarget(listOf("b", "a"), stale, "a", 200f))
        assertEquals("b", companyDragTarget(listOf("hidden", "a", "b"), stale, "a", 200f))
        assertEquals(null, companyDragTarget(listOf("hidden", "a", "b"), stale, "hidden", 200f))
    }
}
