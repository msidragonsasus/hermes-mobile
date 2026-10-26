package com.m57.hermescontrol.career

import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.miniapp.sdui.CareerMyPageSduiKey
import com.m57.hermescontrol.navigation.AppContribution
import com.m57.hermescontrol.navigation.DestinationMetadata
import kotlinx.serialization.Serializable

@Serializable
data object CareerScreenKey : NavKey

@Serializable
data class CareerCompanyKey(
    val companyId: String,
    val tab: CareerCompanyTab = CareerCompanyTab.OVERVIEW,
    val entityId: String? = null,
) : NavKey

@Serializable
data class CareerConversationKey(
    val threadId: String = "global",
    val prompt: String = "",
    val target: CareerTarget? = null,
    val handoffId: String? = null,
    val title: String = "就活の相談",
    val readOnly: Boolean = false,
    val legacyEsQuestionId: String? = null,
) : NavKey

@Serializable
enum class CareerCompanyTab { OVERVIEW, OFFERS, MAIL, SELECTION, NOTES }

@Serializable
data class CareerThreadsKey(
    val companyId: String? = null,
    val offerId: String? = null,
    val prompt: String = "",
    val target: CareerTarget? = null,
    val handoffId: String? = null,
) : NavKey

@Serializable
data class CareerEntityKey(
    val entityId: String,
) : NavKey

@Serializable
data class CareerCompanySettingsKey(
    val companyId: String,
) : NavKey

@Serializable
data class CareerMyPageBrowserKey(
    val companyId: String,
    val linkId: String? = null,
) : NavKey

@Serializable
data class CareerNoticeDetailKey(
    val companyId: String,
    val noticeId: String,
    val scope: String,
    val notices: List<CareerNotice> = emptyList(),
) : NavKey

@Serializable
data object CareerSettingsKey : NavKey

@Serializable
data class CareerEsKey(
    val companyId: String,
    val offerId: String,
    val questionId: String? = null,
) : NavKey

@Serializable
data class CareerMailKey(
    val companyId: String,
) : NavKey

@Serializable
data object CareerMailSettingsKey : NavKey

class CareerContribution : AppContribution {
    override val id = "career.native"
    override val destinations =
        listOf(
            DestinationMetadata("career.threads", "career/threads", CareerThreadsKey::class, 1107),
            DestinationMetadata("career.entity", "career/entity", CareerEntityKey::class, 1108),
            DestinationMetadata(
                "career.company-settings",
                "career/company-settings",
                CareerCompanySettingsKey::class,
                1109,
            ),
            DestinationMetadata("career.home", "career/home", CareerScreenKey::class, 1100),
            DestinationMetadata("career.company", "career/company", CareerCompanyKey::class, 1101),
            DestinationMetadata("career.conversation", "career/conversation", CareerConversationKey::class, 1102),
            DestinationMetadata("career.settings", "career/settings", CareerSettingsKey::class, 1103),
            DestinationMetadata("career.es", "career/es", CareerEsKey::class, 1104),
            DestinationMetadata("career.mail", "career/mail", CareerMailKey::class, 1105),
            DestinationMetadata("career.mail-settings", "career/mail-settings", CareerMailSettingsKey::class, 1106),
            DestinationMetadata("career.notice", "career/notice", CareerNoticeDetailKey::class, 1110),
            DestinationMetadata("career.mypage-browser", "career/mypage-browser", CareerMyPageBrowserKey::class, 1111),
            DestinationMetadata("career.mypage-sdui", "career/mypage-sdui", CareerMyPageSduiKey::class, 1112),
            DestinationMetadata(
                "career.mypage-workbench",
                "career/mypage-workbench",
                com.m57.hermescontrol.miniapp.workbench.CareerMyPageWorkbenchKey::class,
                1113,
            ),
        )
}
