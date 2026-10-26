package com.m57.hermescontrol.miniapp.sdui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Route to the server-described MyPage status of one company. Only the company id is navigated. */
@Serializable
data class CareerMyPageSduiKey(
    val companyId: String,
) : NavKey

/** The one contract this host understands. Anything else is shown as unavailable, never executed. */
internal object SduiContract {
    const val APP_ID = "career.mypage"
    const val PROTOCOL_VERSION = 1
    const val COMPONENT_VERSION = 1
    const val ROUTE = "company"
    const val PERMISSION_READ = "mypage.read"
    const val PERMISSION_CHECK = "mypage.check"
    val PERMISSIONS = setOf(PERMISSION_READ, PERMISSION_CHECK)

    const val MAX_BODY_BYTES = 64 * 1024

    /** Error bodies are read only to find one machine code; anything larger is treated as unreadable. */
    const val MAX_ERROR_BODY_BYTES = 4 * 1024
    const val MAX_NODES = 64

    /** Top-level components are depth 1; a node at depth [MAX_DEPTH] + 1 is rejected. */
    const val MAX_DEPTH = 6
    const val MAX_TEXT = 2000
    const val MAX_LABEL = 200
    const val MAX_ID = 128
    const val MAX_PERMISSIONS = 16
}

internal enum class SduiAction(
    val wire: String,
) {
    REFRESH("refresh"),
    MYPAGE_CHECK("mypage.check"),
    NATIVE_SETTINGS("native.settings"),
    NATIVE_BROWSER("native.browser"),
    ;

    companion object {
        fun fromWire(value: String): SduiAction? = entries.firstOrNull { it.wire == value }
    }
}

internal enum class SduiTone(
    val wire: String,
) {
    INFO("info"),
    SUCCESS("success"),
    WARNING("warning"),
    ERROR("error"),
    ;

    companion object {
        fun fromWire(value: String): SduiTone? = entries.firstOrNull { it.wire == value }
    }
}

/** The closed component set. The renderer matches this exhaustively; there is no generic fallback. */
internal sealed interface SduiNode

internal data class SduiSection(
    val title: String,
    val children: List<SduiNode>,
) : SduiNode

internal data class SduiColumn(
    val children: List<SduiNode>,
) : SduiNode

internal data class SduiText(
    val text: String,
) : SduiNode

internal data class SduiStatus(
    val text: String,
    val tone: SduiTone,
) : SduiNode

internal data class SduiKeyValue(
    val label: String,
    val value: String,
) : SduiNode

internal data class SduiButton(
    val label: String,
    val action: SduiAction,
    val enabled: Boolean,
) : SduiNode

internal data class SduiManifest(
    val appId: String,
    val protocolVersion: Int,
    val componentVersion: Int,
    val releaseId: String,
    val permissions: Set<String>,
    val route: String,
) {
    val canCheck: Boolean get() = SduiContract.PERMISSION_CHECK in permissions
}

internal data class SduiScreen(
    val appId: String,
    val protocolVersion: Int,
    val componentVersion: Int,
    val releaseId: String,
    val companyId: String,
    val profile: String,
    val title: String,
    val state: String,
    val components: List<SduiNode>,
)

internal data class SduiReceipt(
    val releaseId: String,
    val companyId: String,
    val profile: String,
    val operationId: String,
    val state: SduiReceiptState,
    val runId: String,
)

internal enum class SduiReceiptState { QUEUED, RUNNING }

/** Exact body of `POST /mobile/actions/mypage.check`. No defaults: every field is always encoded. */
@Serializable
data class SduiMyPageCheckRequest(
    val protocol_version: Int,
    val release_id: String,
    val company_id: String,
    val operation_id: String,
)
