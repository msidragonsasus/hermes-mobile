package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntelligentUiPresentationTest {
    @Test
    fun themeValuesCannotInjectMarkupOrNonFiniteDimensions() {
        listOf("#ffffff;display:none", "\"/><script>", "red").forEach { color ->
            assertTrue(runCatching { IntelligentUiTheme(color, "#111111", "#2255AA") }.isFailure)
        }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 10000f).forEach { radius ->
            assertTrue(runCatching { IntelligentUiTheme("#FFFFFF", "#111111", "#2255AA", corner = radius) }.isFailure)
        }
    }

    @Test
    fun heightReportsAreOnlyFiniteBoundedNumbers() {
        assertEquals(317, IntelligentUiSizing.reportedHeight("316.2"))
        assertEquals(10_000, IntelligentUiSizing.reportedHeight("10000"))
        listOf(null, "null", "0", "-1", "10001", "Infinity", "NaN", "\"300\"", "{\"height\":300}").forEach {
            assertNull(IntelligentUiSizing.reportedHeight(it))
        }
    }

    @Test
    fun actualHostDocumentExposesOnlyMeasuredHeightFromItsOwnFrame() {
        val doc = IntelligentUiDocument.build("<p>Example</p>", "#FFFFFF", "#111111", "#2255AA", false)
        assertTrue(doc.contains("event.source!==frame?.contentWindow"))
        assertTrue(doc.contains("Number.isFinite(data.height)"))
        assertTrue(doc.contains("data.height<=10000"))
        assertTrue(doc.contains("new ResizeObserver(schedule)"))
        assertTrue(doc.contains("--hermes-on-primary"))
        assertTrue(doc.contains("prefers-reduced-motion"))
    }

    @Test
    fun writesStyledExamplesUsingTheActualProductionDocumentBuilder() {
        val root = File("build/reports/rich-answers/styled").apply { mkdirs() }
        val light =
            IntelligentUiTheme(
                "#F1F6F5",
                "#172422",
                "#216B64",
                "#FFFFFF",
                "#526461",
                "#E4ECEA",
                "#516A76",
                "#6C6490",
                "#337A4A",
                "#326E9E",
                "#806018",
                "#BA1A1A",
            )
        val dark =
            IntelligentUiTheme(
                "#171C1E",
                "#DEE3E5",
                "#80D5D2",
                "#0F1416",
                "#BEC8CA",
                "#252B2D",
                "#B2CBD0",
                "#A3C9EC",
                "#81D692",
                "#8ECEFF",
                "#ECC248",
                "#FFB4AB",
            )
        listOf("calculator", "comparison", "circuit", "nisa").forEach { name ->
            val source =
                requireNotNull(javaClass.getResource("/rich-answers/$name.html"))
                    .readText()
            File(root, "$name.html").writeText(IntelligentUiDocument.build(source, light, false))
            File(root, "$name-dark.html").writeText(IntelligentUiDocument.build(source, dark, true))
        }
        assertEquals(8, root.listFiles()?.count { it.extension == "html" })
    }
}
