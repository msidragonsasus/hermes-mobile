package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.ws.EventParser
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.ui.chat.MdBlock
import com.m57.hermescontrol.ui.chat.parseBlocks
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageContentTextTest {
    @Test
    fun restoredMultimodalAnswerRendersTextAndImageInOrder() {
        val content =
            Json.parseToJsonElement(
                """[{"type":"text","text":"こちらです"},{"type":"image_url","image_url":{"url":"https://example.test/a(b).png"}},{"type":"text","text":"続きです"}]""",
            )
        val blocks = parseBlocks(SessionMessage(role = "assistant", content = content).contentText)
        assertEquals(3, blocks.size)
        assertEquals("こちらです", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals("https://example.test/a(b).png", (blocks[1] as MdBlock.Image).uri)
        assertEquals("続きです", (blocks[2] as MdBlock.Paragraph).text)
    }

    @Test
    fun mcpAndAnthropicBase64ImagesAreSupported() {
        listOf(
            """{"type":"image","mimeType":"image/png","data":"aGVsbG8="}""",
            """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGVsbG8="}}""",
        ).forEach { raw ->
            assertTrue(messageContentText(Json.parseToJsonElement(raw)).contains("data:image/png;base64,aGVsbG8="))
        }
    }

    @Test
    fun liveCompletionUsesTheSameImageRepresentationAsRestoredHistory() {
        val event =
            EventParser.parseParams(
                mapOf(
                    "type" to "message.complete",
                    "session_id" to "fixture",
                    "payload" to
                        mapOf(
                            "text" to "",
                            "content" to
                                listOf(mapOf("type" to "image_url", "image_url" to "https://example.test/p.png")),
                        ),
                ),
            ) as WsEvent.MessageComplete
        assertEquals("https://example.test/p.png", (parseBlocks(event.text).single() as MdBlock.Image).uri)
    }

    @Test
    fun unknownContentAndUntrustedDeviceUrisRemainReadableWithoutImageLoading() {
        val unknown = Json.parseToJsonElement("""{"type":"custom","value":42}""")
        assertEquals(unknown.toString(), messageContentText(unknown))
        val local = Json.parseToJsonElement("""{"type":"image_url","image_url":"content://private/image"}""")
        assertEquals(local.toString(), messageContentText(local))
    }
}
