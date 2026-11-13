package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Startup update check (issue #890): one silent check per installed version
 * (or per 24h), fired right after launch, so the chat screen can show an
 * update banner without the user ever opening the About tab.
 *
 * It uses the same source selection and policy as the About tab
 * ([resolveUpdate]). For the connected server it runs only while signed in
 * and never falls back to the public upstream. The result lands in
 * [AppUpdateCache]; failures leave the cache Idle.
 */
object UpdateNoticeManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Release-only feature (issue #890): the launch check and chat banner are
     * disabled in debug builds (BuildConfig.DEBUG) so daily dev builds never
     * nag about updates. Tests flip this to true.
     */
    var enabled: Boolean = !BuildConfig.DEBUG
        internal set

    /** Release check interval (24 hours). */
    const val CHECK_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

    /** Run from Application.onCreate, after AuthManager.init. Checks at most once every 24h. */
    fun checkOnLaunch(
        checker: AppUpdateChecker = AppUpdateChecker(),
        currentVersion: String = BuildConfig.VERSION_NAME,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        now: Long = System.currentTimeMillis(),
        inspector: ApkInspector? = null,
        scopes: UpdateScopeProvider = AuthManagerUpdateScopes,
        expectedPackage: String = BuildConfig.APPLICATION_ID,
    ) {
        if (!enabled) return
        val lastCheck = AuthManager.getLastUpdateCheckTimestamp()
        val versionChanged = AuthManager.getUpdateCheckDoneForVersion() != currentVersion
        if (!versionChanged && (now - lastCheck < CHECK_INTERVAL_MS)) return
        val updateScope = scopes.current(scopes.source()) ?: return
        val generation = AppUpdateCache.generation

        scope.launch(ioDispatcher) {
            AuthManager.setUpdateCheckDoneForVersion(currentVersion)
            AuthManager.setLastUpdateCheckTimestamp(now)
            val result = resolveUpdate(checker, updateScope, generation, inspector, expectedPackage, currentVersion)
            val state =
                when (result) {
                    is UpdateCheck.Available -> AppUpdateState.UpdateAvailable(result.candidate)
                    is UpdateCheck.UpToDate -> AppUpdateState.UpToDate(result.versionName, updateScope)
                    is UpdateCheck.Failed -> return@launch
                }
            if (generation != AppUpdateCache.generation) return@launch
            AppUpdateCache.update(state)
            state.releaseTag()?.let { AuthManager.setLastKnownLatestTag(it) }
        }
    }

    /**
     * The tag the chat banner should advertise, or null when nothing newer is
     * known or if the user explicitly dismissed this tag. A connected-server
     * result counts only for its live connection; the persisted tag (shown
     * after restart without a new check) is used only for the upstream source.
     */
    fun noticeTag(
        currentVersion: String = BuildConfig.VERSION_NAME,
        scopes: UpdateScopeProvider = AuthManagerUpdateScopes,
    ): String? {
        if (!enabled) return null
        val dismissed = AuthManager.getDismissedUpdateTag()
        val source = scopes.source()
        val cached =
            (AppUpdateCache.state.value as? AppUpdateState.UpdateAvailable)
                ?.takeIf { it.candidate.source == source && it.candidate.scope == scopes.current(source) }
        val candidateTag =
            cached?.latestTag
                ?: AuthManager.getLastKnownLatestTag()?.takeIf { source == UpdateSource.UPSTREAM_GITHUB }
                ?: return null

        if (candidateTag == dismissed) return null
        // A cached candidate already passed the versionCode policy.
        return if (cached != null) candidateTag else candidateTag.takeIf { isNewerVersion(it, currentVersion) }
    }
}
