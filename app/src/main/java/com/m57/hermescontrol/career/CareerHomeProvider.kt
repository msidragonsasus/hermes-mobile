package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.home.HomeContributionProvider
import com.m57.hermescontrol.home.HomePage
import retrofit2.HttpException

class CareerHomeProvider : HomeContributionProvider {
    override val id = "career"
    private val scope = AuthManager.uiPreferenceScope()
    private val profile = AuthManager.activeProfileId.value ?: "default"
    private val api = ApiClient.hermesApi

    override suspend fun read(): HomePage {
        check(AuthManager.isGatedMode())
        // A changed snapshot is retried once, never committed as a partial list.
        for (attempt in 0..1) {
            try {
                var result = HomePage()
                var cursor: String? = null
                val visited = mutableSetOf<String>()
                do {
                    check(scope == AuthManager.uiPreferenceScope())
                    val page = api.careerHome(profile, cursor)
                    check(scope == AuthManager.uiPreferenceScope() && page.api_version == 1)
                    check(page.items.all { it.provider == id })
                    result = page.copy(items = result.items + page.items)
                    cursor = page.next_cursor
                    check(cursor == null || visited.add(cursor))
                } while (cursor != null)
                return result
            } catch (error: HttpException) {
                if (error.code() != 409 || attempt == 1) throw error
            }
        }
        error("ホームを読み直してください。")
    }
}
