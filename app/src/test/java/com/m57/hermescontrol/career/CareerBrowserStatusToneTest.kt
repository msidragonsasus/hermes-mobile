package com.m57.hermescontrol.career

import org.junit.Assert.assertEquals
import org.junit.Test

class CareerBrowserStatusToneTest {
    @Test
    fun progressMessagesAreBusy() {
        assertEquals(CareerBrowserStatusTone.BUSY, careerBrowserStatusTone("読み込み中…"))
        assertEquals(CareerBrowserStatusTone.BUSY, careerBrowserStatusTone("Hermesの保存済み接続を確認中…"))
    }

    @Test
    fun failuresNeedAttentionEvenWhenTheyMentionLoading() {
        assertEquals(
            CareerBrowserStatusTone.ATTENTION,
            careerBrowserStatusTone("ブラウザを閉じました。開き直すと保存済みの接続で自動入力を再開します。"),
        )
        assertEquals(
            CareerBrowserStatusTone.ATTENTION,
            careerBrowserStatusTone("このログインフォームには自動入力できません。手動で入力してください。"),
        )
    }

    @Test
    fun completedStepsAreDoneAndGuidanceIsInfo() {
        assertEquals(CareerBrowserStatusTone.DONE, careerBrowserStatusTone("ページを読み込みました。"))
        assertEquals(
            CareerBrowserStatusTone.DONE,
            careerBrowserStatusTone("パスワードを入力しました。内容を確認し、ログインボタンを押してください。"),
        )
        assertEquals(
            CareerBrowserStatusTone.INFO,
            careerBrowserStatusTone("パスワード欄を待っています。サイトの次へ進む操作をしてください。"),
        )
    }
}
