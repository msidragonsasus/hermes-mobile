package com.m57.hermescontrol.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.ui.settings.AppUpdateViewModel

/** The shell owns one updater: screens share its attempt, not merely cached metadata. */
internal val LocalAppUpdateViewModel = staticCompositionLocalOf<AppUpdateViewModel?> { null }

/** Explicit Application factory is retained for isolated screens/previews outside the shell. */
@Composable
fun sharedAppUpdateViewModel(): AppUpdateViewModel =
    LocalAppUpdateViewModel.current ?: viewModel {
        val application =
            this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("Application not available")
        AppUpdateViewModel(application)
    }
