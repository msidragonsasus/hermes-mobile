package com.m57.hermescontrol

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.m57.hermescontrol.data.local.StorageStartup
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.transport.DashboardRpcTransport
import com.m57.hermescontrol.data.update.UpdateNoticeManager
import com.m57.hermescontrol.domain.hermes.AttentionCoordinator
import com.m57.hermescontrol.domain.hermes.AttentionRepository
import com.m57.hermescontrol.domain.hermes.RunRepository
import com.m57.hermescontrol.ui.analytics.AnalyticsPreloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HermesControlApp :
    Application(),
    SingletonImageLoader.Factory {
    val attentionRepository = AttentionRepository()
    private val shellTransport by lazy { DashboardRpcTransport() }
    val runRepository by lazy { RunRepository(shellTransport) }
    val attentionCoordinator by lazy {
        AttentionCoordinator(attentionRepository, shellTransport, shellTransport, shellTransport)
    }
    private val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        NetworkMonitor.init(this)
        // Issue #537 follow-up (A): preload analytics in the background after launch
        // so the tab renders instantly when opened (the usage endpoint is slow on a
        // cold backend). Fire-and-forget; never blocks UI startup.
        // PR03: preserve unreadable storage and block normal startup instead of recreating it.
        StorageStartup.start(this) {
            runRepository.start(observationScope)
            attentionCoordinator.start(observationScope)
            AnalyticsPreloader.preload(this)
            // Issue #890: retain the existing update notice after storage validation.
            UpdateNoticeManager.checkOnLaunch()
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { OkHttpProvider.base },
                    ),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }.crossfade(true)
            .build()
}
