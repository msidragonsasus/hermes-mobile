package com.m57.hermescontrol.career

/** Commit a scanned page only after all its company notifications were handed to Android. */
internal suspend fun drainCareerNoticePages(
    readCursor: suspend () -> Long,
    fetch: suspend (Long) -> CareerNoticeFeed,
    publish: suspend (List<CareerNotice>) -> Boolean,
    commit: suspend (Long) -> Unit,
    pageLimit: Int = 10,
) {
    require(pageLimit > 0)
    repeat(pageLimit) {
        val cursor = readCursor()
        val feed = fetch(cursor)
        check(feed.cursor >= cursor && (!feed.more || feed.cursor > cursor))
        if (!publish(feed.notices)) return
        commit(feed.cursor)
        if (!feed.more) return
    }
}
