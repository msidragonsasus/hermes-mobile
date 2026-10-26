package com.m57.hermescontrol.agents

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the installed Dashboard session; never starts consent or reads messages. */
class GoogleConnectionReadTest {
    @Test
    fun authenticatedGoogleConnectionStatusIsAvailable() =
        runBlocking {
            AuthManager.init(InstrumentationRegistry.getInstrumentation().targetContext)
            AuthManager.getToken()
            withTimeout(40_000) {
                val status = ApiClient.hermesApi.googleConnectionStatus()
                assertTrue(status.state in setOf("connected", "disconnected", "setup_required", "reauth_required"))
                val mailStatus = ApiClient.hermesApi.careerConnections().mail_status
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    Bundle().apply {
                        putString("google_connection_state", status.state)
                        putString("google_multi_account_protocol", status.multi_account.toString())
                        putString("google_connected_account_count", status.accounts.size.toString())
                        putString(
                            "google_workspace_reason",
                            mailStatus?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "unknown",
                        )
                        putString("google_account_label_present", (!status.email.isNullOrBlank()).toString())
                        putString("google_consent_started", "false")
                        putString("mailbox_contents_read", "false")
                    },
                )
            }
        }
}
