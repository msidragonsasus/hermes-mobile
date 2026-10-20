package com.m57.hermescontrol.miniapp.contract

enum class CoreMiniAppPermission(
    val wireName: String,
) {
    SESSIONS_READ("hermes.sessions.read"),
    SESSIONS_WRITE("hermes.sessions.write"),
    RUNS_CREATE("hermes.runs.create"),
    RUNS_READ("hermes.runs.read"),
    RUNS_STEER("hermes.runs.steer"),
    RUNS_INTERRUPT("hermes.runs.interrupt"),
    APPROVALS_RESPOND("hermes.approvals.respond"),
    JOBS_READ("hermes.jobs.read"),
    JOBS_MANAGE("hermes.jobs.manage"),
    MEMORY_READ("hermes.memory.read"),
    MEMORY_WRITE("hermes.memory.write"),
    FILES_READ("hermes.files.read"),
    FILES_WRITE("hermes.files.write"),
    CAMERA("device.camera"),
    PHOTOS("device.photos"),
    DEVICE_FILES("device.files"),
    MICROPHONE("device.microphone"),
    NOTIFICATIONS("device.notifications"),
    SHARE_RECEIVE("device.share.receive"),
    OPEN_URL("external.openUrl"),
}

sealed interface MiniAppPermission {
    data class Core(
        val permission: CoreMiniAppPermission,
    ) : MiniAppPermission

    data class PluginApi(
        val pluginId: String,
        val scope: String?,
    ) : MiniAppPermission

    companion object {
        private val plugin = Regex("^plugin\\.api:([a-z][a-z0-9.-]+)(?::([a-z0-9._-]+))?$")

        fun parse(value: String): MiniAppPermission? {
            CoreMiniAppPermission.entries.firstOrNull { it.wireName == value }?.let { return Core(it) }
            val match = plugin.matchEntire(value) ?: return null
            return PluginApi(match.groupValues[1], match.groupValues[2].ifEmpty { null })
        }
    }
}
