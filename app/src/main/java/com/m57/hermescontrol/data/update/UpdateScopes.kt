package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.ServerEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.security.SecureRandom

/** Selected update source and the live connection it is bound to. Injectable for tests. */
interface UpdateScopeProvider {
    fun source(): UpdateSource

    fun setSource(source: UpdateSource)

    /** Current scope for [source], or null when the connected server is not signed in. */
    fun current(source: UpdateSource): UpdateScope?

    /**
     * Credential snapshot for [scope], only if [scope] is still exactly the
     * current one (same server, profiles, epoch and credential). Otherwise null.
     */
    fun bind(scope: UpdateScope): UpdateBinding?

    /** Emits whenever the connection (server, profiles, credentials) may have changed. */
    val changes: Flow<Any?>
}

object AuthManagerUpdateScopes : UpdateScopeProvider {
    /** Per-process salt: fingerprints identify a credential within this process only. */
    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }

    internal val defaultCookieHeader: (HttpUrl) -> String? = { url ->
        runCatching { CookieManager.cookieJar.loadForRequest(url) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** Test seam; production reads the cookie jar's cookies for the bound server URL. */
    internal var cookieHeaderFor: (HttpUrl) -> String? = defaultCookieHeader

    override fun source(): UpdateSource =
        UpdateSource.fromStorage(runCatching { AuthManager.getUpdateSource() }.getOrNull())

    override fun setSource(source: UpdateSource) {
        AuthManager.setUpdateSource(source.storageKey)
    }

    override fun current(source: UpdateSource): UpdateScope? = snapshot(source)?.scope

    override fun bind(scope: UpdateScope): UpdateBinding? = snapshot(scope.source)?.takeIf { it.scope == scope }

    override val changes: Flow<Any?>
        get() = merge(AuthManager.connectionEpoch, AuthManager.contextFlow, AuthManager.activeProfileId)

    private fun snapshot(source: UpdateSource): UpdateBinding? {
        if (source == UpdateSource.UPSTREAM_GITHUB) return UpdateBinding(UpdateScope(source), null, null)
        return runCatching {
            // Seqlock read: AuthManager makes the epoch odd while a connection setter runs
            // and even again afterwards; a snapshot spanning a change is discarded.
            val epoch = AuthManager.connectionEpoch.value
            if (epoch % 2L != 0L) return@runCatching null
            val baseUrl = AuthManager.getBaseUrl()
            if (baseUrl.isBlank()) return@runCatching null
            val endpoint = ServerEndpoint.parseForBuild(baseUrl)
            val gated = AuthManager.isGatedMode()
            val selected = AuthManager.getSelectedProfileId()
            val active = AuthManager.activeProfileId.value
            val bearer = if (gated) null else AuthManager.getToken()?.takeIf { it.isNotBlank() }
            val cookie = if (gated) cookieHeaderFor(endpoint.resolve("$SERVER_UPDATES_PATH/latest")) else null
            val credential = bearer ?: cookie ?: return@runCatching null
            if (AuthManager.connectionEpoch.value != epoch) return@runCatching null
            val scope =
                UpdateScope(
                    source = source,
                    baseUrl = baseUrl,
                    selectedProfileId = selected,
                    activeProfileId = active,
                    gatedAuth = gated,
                    credentialPresent = true,
                    connectionEpoch = epoch,
                    credentialFingerprint = fingerprint(credential),
                )
            UpdateBinding(scope, bearer, cookie)
        }.getOrNull()
    }

    private fun fingerprint(credential: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(credential.toByteArray(Charsets.UTF_8))
        return digest.digest().toLowerHex().take(32)
    }
}

/**
 * Process-wide stale guard. Started once from the Application so a cached
 * result for a previous connection is discarded (and [AppUpdateCache.generation]
 * bumped, cancelling any in-flight attempt) even when no updater screen is open.
 */
object UpdateConnectionWatcher {
    /** Invalidate the shared cache when its result belongs to another connection or source. */
    fun invalidateIfStale(scopes: UpdateScopeProvider) {
        val cachedScope =
            when (val cached = AppUpdateCache.state.value) {
                is AppUpdateState.UpdateAvailable -> cached.candidate.scope
                is AppUpdateState.UpToDate -> cached.scope
                else -> return
            }
        val source = scopes.source()
        if (cachedScope == null || cachedScope.source != source || cachedScope != scopes.current(source)) {
            AppUpdateCache.invalidate()
        }
    }

    suspend fun watch(scopes: UpdateScopeProvider = AuthManagerUpdateScopes) {
        scopes.changes.collect { invalidateIfStale(scopes) }
    }
}
