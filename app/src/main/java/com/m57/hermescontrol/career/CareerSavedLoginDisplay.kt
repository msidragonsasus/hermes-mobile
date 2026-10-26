package com.m57.hermescontrol.career

/** Add the exact server connection's username without overwriting local accounts or persisting a read. */
internal fun savedLoginAccounts(
    local: List<ShukatsuLoginAccount>,
    companyId: String,
    companyName: String,
    savedLogin: String?,
): List<ShukatsuLoginAccount> {
    val scoped =
        local.filter {
            it.companySourceId == companyId || "shukatsu:Company:" + it.companySourceId == companyId
        }
    if (savedLogin.isNullOrBlank() || scoped.any { it.username == savedLogin }) return scoped
    return scoped.filterNot { it.sourceId == "server-mypage:$companyId" } +
        ShukatsuLoginAccount(
            "server-mypage:$companyId",
            companyId,
            companyName,
            "MyPage",
            "サーバー保存済み",
            savedLogin,
        )
}

/** Prefer the registered server account, but never override an explicit selection or edited account. */
internal fun selectedLoginAccount(
    accounts: List<ShukatsuLoginAccount>,
    selectedId: String?,
    savedLogin: String?,
): ShukatsuLoginAccount? =
    if (selectedId != null) {
        accounts.firstOrNull { it.sourceId == selectedId }
    } else {
        savedLogin?.let { value -> accounts.firstOrNull { it.username == value } } ?: accounts.singleOrNull()
    }
