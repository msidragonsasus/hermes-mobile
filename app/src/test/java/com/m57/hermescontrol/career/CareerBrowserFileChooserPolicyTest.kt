package com.m57.hermescontrol.career

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerBrowserFileChooserPolicyTest {
    private val extensions = mapOf("jpg" to "image/jpeg", "png" to "image/png", "pdf" to "application/pdf")

    @Test
    fun emptyAcceptAndWildcardAllowBrowsingAnyFile() {
        assertEquals(CareerBrowserFileTypes("*/*", emptyList()), types())
        assertEquals(CareerBrowserFileTypes("*/*", emptyList()), types(" ", "*/*", "image/png"))
    }

    @Test
    fun imageMimeTypesUseCommonTypeAndKeepPreciseFilters() {
        assertEquals(
            CareerBrowserFileTypes("image/*", listOf("image/jpeg", "image/png")),
            types("image/jpeg", "image/png"),
        )
    }

    @Test
    fun extensionsCommaSeparatedValuesAndCaseAreNormalized() {
        assertEquals(
            CareerBrowserFileTypes("*/*", listOf("image/jpeg", "application/pdf")),
            types(" .JPG, application/PDF ", ".pdf", "image/jpeg"),
        )
    }

    @Test
    fun singleExtensionUsesSpecificMimeType() {
        assertEquals(CareerBrowserFileTypes("application/pdf", listOf("application/pdf")), types(".pdf"))
    }

    @Test
    fun unknownExtensionDoesNotHideAFileThePageAllows() {
        assertEquals(CareerBrowserFileTypes("*/*", emptyList()), types("image/png", ".unknown-extension"))
    }

    @Test
    fun malformedFiltersAreIgnoredAndCannotBecomeIntentTypes() {
        assertEquals(
            CareerBrowserFileTypes("image/png", listOf("image/png")),
            types("text/html;param=x", "*/png", "image/", "file:///private", "image/png"),
        )
        assertEquals(CareerBrowserFileTypes("*/*", emptyList()), types("not-a-type", "image/png\r\nx-danger"))
    }

    @Test
    fun vendorTypesAndMimeWildcardsRemainAvailable() {
        assertEquals(
            CareerBrowserFileTypes(
                "*/*",
                listOf("image/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            ),
            types("image/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
    }

    @Test
    fun inFlightRequestCannotBeReplacedByAnotherOwner() {
        val slot = CareerBrowserPickerSlot<String>()
        assertTrue(slot.begin("first"))
        assertFalse(slot.begin("second"))
        assertEquals("first", slot.finish())
        assertFalse(slot.isAwaitingResult)
        assertNull(slot.finish())
    }

    @Test
    fun cancellingCallbackKeepsOldResultReservedUntilDrained() {
        val slot = CareerBrowserPickerSlot<String>()
        assertTrue(slot.begin("old document"))
        assertEquals("old document", slot.cancel())
        assertNull(slot.cancel())
        assertTrue(slot.isAwaitingResult)
        assertFalse(slot.begin("new document"))
        assertNull(slot.finish())
        assertTrue(slot.begin("fresh selection"))
        assertEquals("fresh selection", slot.finish())
    }

    @Test
    fun recreationReservesAndDrainsOldResultWithoutRevivingCallback() {
        val slot = CareerBrowserPickerSlot<String>(initiallyAwaitingResult = true)
        assertTrue(slot.isAwaitingResult)
        assertFalse(slot.begin("new instance"))
        assertNull(slot.finish())
        assertTrue(slot.begin("fresh selection"))
    }

    @Test
    fun closingIsFinalEvenAfterOldResultHasBeenDrained() {
        val slot = CareerBrowserPickerSlot<String>()
        assertTrue(slot.begin("document"))
        assertEquals("document", slot.close())
        assertNull(slot.close())
        assertTrue(slot.isAwaitingResult)
        assertNull(slot.finish())
        assertFalse(slot.begin("too late"))
    }

    @Test
    fun launchFailureCanFinishSlotAndPermitUserRetry() {
        val slot = CareerBrowserPickerSlot<String>()
        assertTrue(slot.begin("failed launch"))
        assertEquals("failed launch", slot.finish())
        assertTrue(slot.begin("retry"))
    }

    private fun types(vararg accept: String) = careerBrowserFileTypes(accept.toList(), extensions::get)
}
