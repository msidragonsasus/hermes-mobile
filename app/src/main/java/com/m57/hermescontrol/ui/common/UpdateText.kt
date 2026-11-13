package com.m57.hermescontrol.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.update.UpdateFailure
import com.m57.hermescontrol.data.update.UpdateSource

/** One wording per updater failure, shared by the About row and the update dialog. */
@Composable
fun updateFailureText(failure: UpdateFailure): String =
    stringResource(
        when (failure) {
            UpdateFailure.NOT_CONNECTED -> R.string.update_error_not_connected
            UpdateFailure.LOGIN_REQUIRED -> R.string.update_error_login_required
            UpdateFailure.SERVER_UPDATES_MISSING -> R.string.update_error_server_missing
            UpdateFailure.SERVER_UPDATES_DISABLED -> R.string.update_error_server_disabled
            UpdateFailure.NO_RELEASE -> R.string.update_error_no_release
            UpdateFailure.NO_APK -> R.string.update_error_no_apk
            UpdateFailure.NO_DIGEST -> R.string.update_error_no_digest
            UpdateFailure.INVALID_METADATA -> R.string.update_error_invalid_metadata
            UpdateFailure.NETWORK -> R.string.update_error_network
            UpdateFailure.REDIRECT_REFUSED -> R.string.update_error_redirect
            UpdateFailure.WRONG_APPLICATION -> R.string.update_error_wrong_app
            UpdateFailure.INSTALLED_IDENTITY_UNAVAILABLE -> R.string.update_error_identity_unavailable
            UpdateFailure.SIGNER_MISMATCH -> R.string.update_error_signer_mismatch
            UpdateFailure.SIGNER_ROTATION_UNSUPPORTED -> R.string.update_error_rotation
            UpdateFailure.UNSIGNED -> R.string.update_error_unsigned
            UpdateFailure.SIZE_MISMATCH, UpdateFailure.HASH_MISMATCH -> R.string.update_error_integrity
            UpdateFailure.ARCHIVE_UNREADABLE -> R.string.update_error_unreadable
            UpdateFailure.VERSION_MISMATCH -> R.string.update_error_version_mismatch
            UpdateFailure.NOT_NEWER -> R.string.update_error_not_newer
            UpdateFailure.CONNECTION_CHANGED -> R.string.update_error_connection_changed
            UpdateFailure.DOWNLOAD_FAILED -> R.string.update_error_download
            UpdateFailure.INSTALLER_UNAVAILABLE -> R.string.update_error_installer
            UpdateFailure.CHECK_FAILED -> R.string.update_error_check
        },
    )

/** "Connected server · host:port" or "Public upstream (GitHub)": always says where an update comes from. */
@Composable
fun updateSourceLabel(
    source: UpdateSource,
    serverLabel: String = "",
): String =
    when (source) {
        UpdateSource.CONNECTED_SERVER -> {
            if (serverLabel.isBlank()) {
                stringResource(R.string.settings_about_update_source_server)
            } else {
                stringResource(R.string.settings_about_update_source_server_at, serverLabel)
            }
        }

        UpdateSource.UPSTREAM_GITHUB -> {
            stringResource(R.string.settings_about_update_source_upstream)
        }
    }
