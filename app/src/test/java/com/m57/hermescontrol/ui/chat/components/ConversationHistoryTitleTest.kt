package com.m57.hermescontrol.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryTitleTest {
    @Test
    fun slackEnvelopeUsesHumanTextWithoutTransportIdentifiers() {
        val raw = """{"channel":"C123456789","user":"U123456789","ts":"123.456","text":"ブラウザの修正を続けて"}"""
        assertEquals("ブラウザの修正を続けて", conversationHistoryTitle(raw, null, "無題の会話"))
        assertTrue(raw.contains("C123456789"))
    }

    @Test
    fun bracketedSlackAndFileHeadersAreRemovedOnlyForPresentation() {
        val raw = "[Slack message from U123456789 in C123456789] 履歴を整理して\n[File: /tmp/private/context.txt]"
        assertEquals("履歴を整理して", conversationHistoryTitle(raw, null, "無題の会話"))
    }

    @Test
    fun pathOnlyTitleFallsBackToReadablePreviewOrUntitled() {
        assertEquals("バグを調べて", conversationHistoryTitle("E:\\private\\log.txt", "バグを調べて", "無題の会話"))
        assertEquals("無題の会話", conversationHistoryTitle("/tmp/private/log.txt", "U123456789", "無題の会話"))
    }

    @Test
    fun absolutePathsAndMetadataAreRemovedButUrlsAndSlashCommandsRemainReadable() {
        val raw =
            "channel_id: C123456789\nuser_id: U123456789\n/tmp/private/log.txt\n" +
                "/model を確認 https://example.com/docs"
        assertEquals("/model を確認 https://example.com/docs", conversationHistoryTitle(raw, null, "無題の会話"))
    }

    @Test
    fun longMultilineTitlesAreOneLineAndDoNotSplitUnicodeCharacters() {
        val result = conversationHistoryTitle("修正\n" + "😀".repeat(110), null, "無題の会話")
        assertFalse(result.contains('\n'))
        assertEquals(97, result.codePointCount(0, result.length))
        assertFalse(result.dropLast(1).last().isHighSurrogate())
    }

    @Test
    fun normalHumanTitlesAndNonTransportJsonArePreserved() {
        assertEquals("ブラウザの修正", conversationHistoryTitle("ブラウザの修正", "別の内容", "無題の会話"))
        assertEquals("{\"text\":\"Example\"}", conversationHistoryTitle("{\"text\":\"Example\"}", null, "無題の会話"))
    }
}
