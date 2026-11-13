package com.m57.hermescontrol.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.UpdateFailure
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateDialogHomeStatesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun idleCheckingAndLatestNeverOfferAnInstallAction() {
        val state = mutableStateOf<AppUpdateState>(AppUpdateState.Idle)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val installLabel = context.getString(R.string.update_dialog_action_install)
        compose.setContent {
            MaterialTheme {
                AppUpdateDialog(state.value, {}, {}, {}, {}, {})
            }
        }
        for (next in listOf(AppUpdateState.Idle, AppUpdateState.Checking, AppUpdateState.UpToDate("1.0.82"))) {
            compose.runOnIdle { state.value = next }
            compose.onNodeWithText(installLabel).assertDoesNotExist()
        }
    }

    @Test
    fun checkFailureUsesRetryRatherThanBlindlyStartingAnOldDownload() {
        var retries = 0
        var starts = 0
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            MaterialTheme {
                AppUpdateDialog(
                    state = AppUpdateState.Error(UpdateFailure.CHECK_FAILED),
                    onDismiss = {},
                    onStartUpdate = { starts++ },
                    onCancelDownload = {},
                    onNeverAskAgain = {},
                    onOpenSettings = {},
                    onRetry = { retries++ },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_about_update_retry)).performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, starts)
        }
    }
}
