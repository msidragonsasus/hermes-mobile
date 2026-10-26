package com.m57.hermescontrol.home

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

@Serializable
data class TodayTask(
    val id: String,
    val title: String,
    val reason: String,
    val source: String,
    val source_title: String,
    val url: String,
    val priority: Int,
    val deadline: String? = null,
    val done: Boolean = false,
    val stale: Boolean = false,
    val day: String = "",
    val source_checked_at: String = "",
)

@Serializable
data class TodayPlan(
    val day: String = "",
    val plan_day: String = "",
    val checked_at: String = "",
    val state: String = "idle",
    val fresh: Boolean = false,
    val tasks: List<TodayTask> = emptyList(),
    val coverage: List<TodayCoverage> = emptyList(),
)

@Serializable
data class TodayCoverage(
    val id: String,
    val label: String,
    val state: String,
    val checked_at: String = "",
)

@Serializable
data class TodaySettings(
    val email: String = "",
    val manaba_url: String = "",
    val enabled: Boolean = false,
)

@Serializable
data class TodayRefresh(
    val request_id: String,
    val automatic: Boolean = false,
)

@Serializable
data class TodayCompletion(
    val done: Boolean,
)

internal fun TodayPlan.isCurrent(now: Instant = Instant.now()): Boolean =
    fresh && plan_day == now.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate().toString()

internal fun todayCoverageLabel(state: String): String =
    when (state) {
        "updated" -> "確認済み"
        "checked" -> "検索・閲覧した範囲を確認済み"
        "partial" -> "一部確認・未確認の情報があります"
        "auth_required" -> "ログインが必要"
        "not_configured" -> "未設定"
        "setup_required" -> "接続の準備が必要"
        "failed" -> "確認できませんでした"
        "stale" -> "前回の情報"
        "cached" -> "キャッシュを使用・最新の状況は未確認"
        else -> "未確認"
    }
