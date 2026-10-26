package com.m57.hermescontrol.ui.bots

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainPreviewTest {
    @Test
    fun stripsMarkdownSyntax() {
        assertEquals("SMA端子を付けるだけじゃ無理。", plainPreview("**SMA端子**を付けるだけじゃ無理。"))
        assertEquals("見出し 本文", plainPreview("## 見出し\n本文"))
        assertEquals("docs を見て", plainPreview("[docs](https://example.com) を見て"))
        assertEquals("run npm test", plainPreview("run `npm test`"))
        assertEquals("a b", plainPreview("- a\n- b"))
    }
}
