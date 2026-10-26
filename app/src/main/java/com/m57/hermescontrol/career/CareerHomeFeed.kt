package com.m57.hermescontrol.career

import com.m57.hermescontrol.home.HomeItem
import com.m57.hermescontrol.home.HomePage
import com.m57.hermescontrol.home.homeItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Use the same server-selected action feed as the shell Home, including deadline/mail actions. */
internal fun careerHomeActions(
    page: HomePage?,
    now: Instant = Instant.now(),
): List<HomeItem> =
    homeItems(page?.items.orEmpty().filter { it.provider == "career" }, emptyMap(), now)
        .filter { it.category == "action" }

internal fun careerHomeActions(
    page: HomePage?,
    today: LocalDate,
): List<HomeItem> = careerHomeActions(page, today.atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant())

internal data class CareerHomeFeedState(
    val page: HomePage? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Read-only feed refresh does not depend on the unrelated entity snapshot changing. */
internal class CareerHomeFeed(
    private val readPage: suspend () -> HomePage,
) {
    private val mutableState = MutableStateFlow(CareerHomeFeedState())
    val state = mutableState.asStateFlow()

    suspend fun refresh() {
        mutableState.update { it.copy(loading = true) }
        try {
            val page = readPage()
            currentCoroutineContext().ensureActive()
            mutableState.value = CareerHomeFeedState(page = page, loading = false)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.update { it.copy(error = "ホームを更新できません。接続を確認してください。") }
        } finally {
            mutableState.update { it.copy(loading = false) }
        }
    }
}
