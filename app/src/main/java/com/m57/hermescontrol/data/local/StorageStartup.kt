package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore

sealed interface StorageStartupState {
    data object Loading : StorageStartupState

    data object Ready : StorageStartupState

    data class Blocked(
        val reason: StorageFailure,
    ) : StorageStartupState
}

/** PR03: complete the real Room open/migration before constructing data-dependent screens. */
object StorageStartup {
    private val mutableState = MutableStateFlow<StorageStartupState>(StorageStartupState.Loading)
    val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(
        context: Context,
        onReady: () -> Unit,
    ) {
        scope.launch {
            try {
                // Check before AuthManager can ask MasterKeys to generate a replacement.
                val existingDatabase = DatabaseProtection.inspect(context.getDatabasePath("hermes_control.db"))
                val encryptedPrefs = File(context.applicationInfo.dataDir, "shared_prefs/hermes_secure_prefs.xml")
                if (existingDatabase || encryptedPrefs.exists()) {
                    val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
                    if (!keyStore.containsAlias(MasterKeys.AES256_GCM_SPEC.keystoreAlias)) {
                        throw StorageUnavailableException(StorageFailure.MISSING_KEY)
                    }
                }
                AuthManager.init(context)
                AuthManager.ensureDefaultProfile()
                // Room build() is lazy. This query forces schema and decryption validation.
                HermesDatabase.get(context).chatMessageDao().sessionExists("")
                mutableState.value = StorageStartupState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageUnavailableException) {
                mutableState.value = StorageStartupState.Blocked(e.reason)
            } catch (_: Exception) {
                mutableState.value = StorageStartupState.Blocked(StorageFailure.OPEN_OR_MIGRATION_FAILED)
            } catch (_: LinkageError) {
                mutableState.value = StorageStartupState.Blocked(StorageFailure.OPEN_OR_MIGRATION_FAILED)
            }
            if (mutableState.value == StorageStartupState.Ready) onReady()
        }
    }
}
