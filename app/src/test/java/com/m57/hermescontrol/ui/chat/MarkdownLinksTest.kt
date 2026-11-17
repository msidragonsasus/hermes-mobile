package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.theme.SearchHighlightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownLinksTest {
    @Test
    fun angleWrappedHostPdfUsesGatewayAttachmentWithoutChangingItsPath() {
        val path = "/var/lib/hermes/deliverables/報告 書 (最終).pdf"
        val attachment = requireNotNull(markdownFileAttachment("<$path>"))
        assertEquals("gateway:$path", attachment.uri)
        assertEquals("報告 書 (最終).pdf", attachment.name)
        assertEquals("application/pdf", attachment.mimeType)
        assertEquals(AttachmentSource.GATEWAY, attachment.source)
        val parsed =
            parseInline(
                text = "[資料](<$path>)",
                textColor = Color.Black,
                searchQuery = "",
                isCurrentMatch = false,
                linkColor = Color.Blue,
                highlights =
                    SearchHighlightColors(
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black,
                        Color.Transparent,
                        Color.Black,
                    ),
            )
        assertEquals(
            path,
            (
                parsed
                    .getLinkAnnotations(
                        0,
                        parsed.length,
                    ).single()
                    .item as androidx.compose.ui.text.LinkAnnotation.Url
            ).url,
        )
    }

    @Test
    fun ordinaryWebLinksStayExternalAndGatewayHomeStaysRemote() {
        assertNull(markdownFileAttachment("https://example.com/report.pdf"))
        assertEquals("gateway:~/report.pdf", markdownFileAttachment("<~/report.pdf>")?.uri)
        assertNull(markdownFileAttachment("report.pdf"))
        assertEquals("https://example.com", normalizeMarkdownLink("<https://example.com>"))
    }
}
