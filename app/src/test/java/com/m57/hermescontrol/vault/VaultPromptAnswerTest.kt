package com.m57.hermescontrol.vault

import com.m57.hermescontrol.data.ws.VaultPromptKind
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultPromptAnswerTest {
    @Test
    fun loginTrimsOnlyIdentifierAndEncodesPair() {
        assertEquals(
            """{"identifier":"alice","password":" pass "}""",
            vaultPromptAnswer(VaultPromptKind.LOGIN, " alice ", " pass "),
        )
    }

    @Test
    fun codeAndUnlockSendOriginalValue() {
        assertEquals("00123", vaultPromptAnswer(VaultPromptKind.CODE, "", "00123"))
        assertEquals(" pass ", vaultPromptAnswer(VaultPromptKind.UNLOCK, "", " pass "))
    }
}
