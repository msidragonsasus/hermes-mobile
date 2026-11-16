package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.RichAnswerPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

class QuotedReplyTest {
    @Test
    fun quotesEveryLineAndPreservesExistingDraftWhitespace() {
        val message = ChatMessage(role = MessageRole.ASSISTANT, content = "one\n\ntwo")
        assertEquals("> one\n> \n> two\n\n  draft\n", quotedReplyDraft(message, "  draft\n"))
    }

    @Test
    fun quotesOnlyVisibleUserTextAndPositionsCursorAtEnd() {
        val message = ChatMessage(role = MessageRole.USER, content = RichAnswerPrompt.append("比較して"))
        val reply = quotedReplyField(message, TextFieldValue("続き", TextRange(1)))
        assertEquals("> 比較して\n\n続き", reply.text)
        assertEquals(TextRange(reply.text.length), reply.selection)
    }

    @Test
    fun blankMessagesLeaveDraftUnchanged() {
        assertEquals("draft", quotedReplyDraft(ChatMessage(role = MessageRole.ASSISTANT, content = "  "), "draft"))
    }
}
