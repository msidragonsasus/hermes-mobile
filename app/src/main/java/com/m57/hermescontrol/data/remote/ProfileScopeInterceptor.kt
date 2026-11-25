package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Appends `?profile=<activeProfile>` to profile-scoped management endpoints,
 * mirroring the desktop/web dashboard contract
 * (`web/src/lib/api.ts` → `PROFILE_SCOPED_PREFIXES` + `withManagementProfile`).
 *
 * Without this, mobile fired all management REST calls with NO profile scope,
 * so every profile read/wrote the backend's implicit default profile —
 * multi-profile setups silently corrupted config/skills/toolsets/mcp/model/env
 * (issue #528).
 *
 * Built-in owner App routes always select Hermes Main. Other management and
 * plugin routes use their explicit profile, or the current bot when omitted.
 */
object ProfileScopeInterceptor : Interceptor {
    private val OWNER_APP_PREFIXES =
        listOf(
            "/api/plugins/career",
            "/api/plugins/pc-power",
            "/api/plugins/mobile-terminal",
            "/api/plugins/today_home",
            "/api/plugins/mobile_worker",
            "/api/connections/google-workspace",
            "/api/connections/slack",
        )

    private val PROFILE_SCOPED_PREFIXES =
        listOf(
            "/api/analytics",
            "/api/config",
            "/api/cron",
            "/api/env",
            "/api/gateway",
            "/api/mcp",
            "/api/memory",
            "/api/messaging/platforms",
            "/api/messaging/telegram/onboarding",
            "/api/messaging/whatsapp/onboarding",
            "/api/model/info",
            "/api/model/set",
            "/api/model/auxiliary",
            "/api/model/moa",
            "/api/model/options",
            "/api/dashboard/plugins",
            "/api/plugins",
            "/api/sessions",
            "/api/skills",
            "/api/status",
            "/api/tools/toolsets",
        )

    /**
     * True only when [path] matches a scoped prefix as a whole path segment —
     * e.g. `/api/status` or `/api/status/health`, but NOT `/api/statusXYZ`
     * or `/api/gatewayExtra` (Sourcery review, PR #540).
     */
    private fun isProfileScopedPath(path: String): Boolean =
        PROFILE_SCOPED_PREFIXES.any { prefix -> path == prefix || path.startsWith("$prefix/") }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // Derive the path relative to the endpoint's proxy prefix so profile
        // scoping works when the dashboard is served behind a reverse proxy
        // (e.g. `/hermes/api/status` → relative `/api/status`). A transient
        // DataStore read failure mid-intercept must not crash every request.
        val relativePath =
            runCatching { AuthManager.endpoint().relativeRequestPath(url) }
                .getOrDefault(url.encodedPath)

        val profile =
            if (OWNER_APP_PREFIXES.any { prefix -> relativePath == prefix || relativePath.startsWith("$prefix/") }) {
                AppScope.PROFILE
            } else {
                if ("profile" in url.queryParameterNames || !isProfileScopedPath(relativePath)) {
                    return chain.proceed(request)
                }
                AuthManager.activeProfileId.value ?: return chain.proceed(request)
            }

        val scopedUrl =
            url
                .newBuilder()
                .setQueryParameter("profile", profile)
                .build()

        return chain.proceed(request.newBuilder().url(scopedUrl).build())
    }
}
