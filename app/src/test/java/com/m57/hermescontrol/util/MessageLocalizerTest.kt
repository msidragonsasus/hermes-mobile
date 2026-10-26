package com.m57.hermescontrol.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MessageLocalizerTest {
    private fun ja(message: String) = MessageLocalizer.localize(message, Locale.JAPANESE)

    @Test
    fun englishLocaleIsUnchanged() {
        assertEquals("Key added successfully", MessageLocalizer.localize("Key added successfully", Locale.ENGLISH))
    }

    @Test
    fun exactMessages() {
        assertEquals("キーを追加しました", ja("Key added successfully"))
        assertEquals("会話を固定しました", ja("Session pinned"))
    }

    @Test
    fun parameterizedMessages() {
        assertEquals("KEY_B を保存しました", ja("Saved KEY_B"))
        assertEquals("Gatewayを再起動しました", ja("Gateway restarted successfully"))
        assertEquals("受信設定「gh」を有効にしました", ja("Subscription \"gh\" enabled"))
        assertEquals("空の会話を3件削除しました", ja("Deleted 3 empty sessions"))
        assertEquals("プロファイル x を再表示しました", ja("Profile x unhidden"))
    }

    @Test
    fun failuresKeepServerReason() {
        assertEquals("読み込めませんでした: timeout", ja("Failed to load webhooks: timeout"))
        assertEquals("削除できませんでした: 404", ja("Failed to delete \"gh\": 404"))
        assertEquals("再集計に失敗しました: boom", ja("Rescan failed: boom"))
        assertEquals("アップデートの確認に失敗しました: x", ja("Update check failed: x"))
    }

    @Test
    fun unknownMessagesPassThrough() {
        assertEquals("server said no", ja("server said no"))
    }
}
