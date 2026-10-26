package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostScreenshotRenderingTest {
    private val screenshot = "/var/lib/hermes/.hermes/cache/scratch/notebook-google-login.png"

    @Test
    fun screenshotPathFromTheReportedReplyBecomesAnImage() {
        val reply =
            "現在の認証画面のスクショを保存した。\n\n`$screenshot`\n\nこのTUIでは画像を直接添付できない。"
        val blocks = parseBlocks(reply)
        assertEquals(3, blocks.size)
        assertEquals("現在の認証画面のスクショを保存した。", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals(screenshot, (blocks[1] as MdBlock.Image).uri)
        assertEquals("このTUIでは画像を直接添付できない。", (blocks[2] as MdBlock.Paragraph).text)
    }

    @Test
    fun imagePathWithoutBlankLinesKeepsBothExplanations() {
        val blocks = parseBlocks("保存しました\n$screenshot\n続きの説明")
        assertEquals(3, blocks.size)
        assertEquals("保存しました", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals(screenshot, (blocks[1] as MdBlock.Image).uri)
        assertEquals("続きの説明", (blocks[2] as MdBlock.Paragraph).text)
    }

    @Test
    fun standalonePathsAndPathOnlyTextFencesAreSupported() {
        listOf(screenshot, "`$screenshot`", "```\n$screenshot\n```", "```text\n$screenshot\n```").forEach {
            assertEquals(screenshot, (parseBlocks(it).single() as MdBlock.Image).uri)
        }
    }

    @Test
    fun quotedSpacesAndRemoteHomeArePreservedWithoutUsingThePhoneHome() {
        listOf(
            "`~/screenshots/login image.png`" to "~/screenshots/login image.png",
            "\"C:\\screenshots\\login image.png\"" to "C:\\screenshots\\login image.png",
        ).forEach { (text, path) ->
            assertEquals(path, (parseBlocks(text).single() as MdBlock.Image).uri)
        }
    }

    @Test
    fun pathsMentionedInCommandsOrProseAreNotAutomaticallyFetched() {
        listOf(
            "cat $screenshot",
            "画像は $screenshot です",
            "`open $screenshot`",
            "`/tmp/report.pdf`",
            "content://private/image.png",
            "file:///data/private/image.png",
            "//untrusted.example/image.png",
        ).forEach {
            assertTrue(parseBlocks(it).none { block -> block is MdBlock.Image })
        }
    }

    @Test
    fun shellCodeAndUnfinishedFencesRemainCode() {
        assertTrue(parseBlocks("```sh\n$screenshot\n```").single() is MdBlock.Code)
        val unfinished = parseBlocks("```\n$screenshot").single() as MdBlock.Code
        assertFalse(unfinished.complete)
    }
}
