package com.m57.hermescontrol.miniapp.sdui

/**
 * In-memory stand-in for the disk behind [SduiPendingStore]. Keeping one instance across several
 * view models simulates process death: only this survives. Records are keyed exactly like the real
 * store, so a different host, profile or company sees a different record.
 */
internal class FakePendingDisk {
    val records = mutableMapOf<String, SduiPendingRead>()

    /** Ordered log of disk operations, for "committed before the request" assertions. */
    val events = mutableListOf<String>()

    var failRead = false
    var failCommit = false
    var failClear = false

    fun key(
        scope: String,
        companyId: String,
    ): String = sduiPendingScopeKey(scope, companyId)

    fun store(
        scope: String,
        companyId: String,
    ): SduiPendingStore {
        val key = key(scope, companyId)
        return object : SduiPendingStore {
            override suspend fun read(): SduiPendingRead =
                if (failRead) SduiPendingRead.Unreadable else records[key] ?: SduiPendingRead.None

            override suspend fun commit(pending: SduiPendingCheck): Boolean {
                if (failCommit) return false
                records[key] = SduiPendingRead.Found(pending)
                events += "commit:${pending.operationId}"
                return true
            }

            override suspend fun clear(): Boolean {
                if (failClear) return false
                records.remove(key)
                events += "clear"
                return true
            }
        }
    }
}
