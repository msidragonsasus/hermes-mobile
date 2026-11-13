package com.m57.hermescontrol.data.update

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

val TEST_SERVER_SCOPE =
    UpdateScope(UpdateSource.CONNECTED_SERVER, "http://100.64.0.2:9119/", "default", null, true, true)

/**
 * In-memory source selection and connection; [connect] simulates profile
 * switch / logout. [bind] mirrors production: a credential snapshot only for
 * the exact current scope.
 */
class FakeUpdateScopes(
    var selected: UpdateSource = UpdateSource.CONNECTED_SERVER,
    var serverScope: UpdateScope? = TEST_SERVER_SCOPE,
) : UpdateScopeProvider {
    private val flow = MutableStateFlow<Any?>(serverScope)
    override val changes = flow
    val bearers = mutableMapOf<UpdateScope, String>()
    val cookies = mutableMapOf<UpdateScope, String>()

    override fun source() = selected

    override fun setSource(source: UpdateSource) {
        selected = source
    }

    override fun current(source: UpdateSource): UpdateScope? =
        if (source == UpdateSource.UPSTREAM_GITHUB) UpdateScope(source) else serverScope

    override fun bind(scope: UpdateScope): UpdateBinding? {
        if (scope != current(scope.source)) return null
        if (scope.source == UpdateSource.UPSTREAM_GITHUB) return UpdateBinding(scope, null, null)
        val cookie = cookies[scope]
        val bearer = if (cookie == null) bearers[scope] ?: "test-token" else null
        return UpdateBinding(scope, bearer, cookie)
    }

    fun connect(scope: UpdateScope?) {
        serverScope = scope
        flow.value = scope
    }
}

class FakeApkInspector(
    var installedIdentity: ApkIdentity?,
    var archiveIdentity: ApkIdentity?,
) : ApkInspector {
    val inspected = mutableListOf<File>()

    override fun installed() = installedIdentity

    override fun archive(file: File): ApkIdentity? {
        inspected += file
        return archiveIdentity
    }
}
