package com.m57.hermescontrol.ui.settings

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.StorageStartup
import com.m57.hermescontrol.data.local.StorageStartupState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit deployment check: reports only startup state, never credentials or saved contents. */
class InstalledStorageReadinessTest {
    @Test
    fun existingInstalledStorageIsReadable() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("verifyInstalledStorage") == "true")
            val state =
                withTimeout(30_000) {
                    StorageStartup.state.first { it != StorageStartupState.Loading }
                }
            val code = (state as? StorageStartupState.Blocked)?.reason?.name ?: "READY"
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply { putString("storage_state", code) },
            )
            assertEquals(StorageStartupState.Ready, state)
        }
}
