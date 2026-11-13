package com.m57.hermescontrol.ui.common

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.update.ApkIdentity
import com.m57.hermescontrol.data.update.ApkInspector
import com.m57.hermescontrol.data.update.AppUpdateChecker
import com.m57.hermescontrol.data.update.UpdateBinding
import com.m57.hermescontrol.data.update.UpdateScope
import com.m57.hermescontrol.data.update.UpdateScopeProvider
import com.m57.hermescontrol.data.update.UpdateSource
import com.m57.hermescontrol.ui.settings.AppUpdateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** No network/account credentials: a shell-provided updater must be reused at each entry point. */
@RunWith(AndroidJUnit4::class)
class SharedAppUpdateViewModelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun providedControllerIsTheSameForHomeAboutAndDialogConsumers() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val scopes =
            object : UpdateScopeProvider {
                override val changes = MutableStateFlow<Any?>(null)

                override fun source() = UpdateSource.CONNECTED_SERVER

                override fun setSource(source: UpdateSource) = Unit

                override fun current(source: UpdateSource): UpdateScope? = null

                override fun bind(scope: UpdateScope): UpdateBinding? = null
            }
        val inspector =
            object : ApkInspector {
                override fun installed(): ApkIdentity? = null

                override fun archive(file: File): ApkIdentity? = null
            }
        lateinit var shell: AppUpdateViewModel
        lateinit var home: AppUpdateViewModel
        lateinit var about: AppUpdateViewModel
        lateinit var dialog: AppUpdateViewModel
        compose.setContent {
            val owner: AppUpdateViewModel =
                viewModel(key = "test-shared-updater") {
                    AppUpdateViewModel(
                        application,
                        checker = AppUpdateChecker(upstreamClient = OkHttpClient()),
                        inspector = inspector,
                        scopes = scopes,
                    )
                }
            shell = owner
            CompositionLocalProvider(LocalAppUpdateViewModel provides owner) {
                home = sharedAppUpdateViewModel()
                about = sharedAppUpdateViewModel()
                dialog = sharedAppUpdateViewModel()
            }
        }
        compose.runOnIdle {
            assertSame(shell, home)
            assertSame(shell, about)
            assertSame(shell, dialog)
        }
    }
}
