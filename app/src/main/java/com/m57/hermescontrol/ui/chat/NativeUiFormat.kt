package com.m57.hermescontrol.ui.chat

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToLong

/** Display formatting shared by every native component. Models send raw numbers only. */
internal object NativeFormat {
    const val MINUS = "−"

    /** Group digits and keep at most [decimals] fraction digits; trailing zeros are dropped. */
    fun number(
        value: Double,
        decimals: Int = 1,
    ): String {
        val rounded = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
        val negative = rounded.signum() < 0
        val plain = rounded.abs().toPlainString()
        val whole = plain.substringBefore('.')
        val fraction = plain.substringAfter('.', "")
        val grouped =
            whole
                .reversed()
                .chunked(3)
                .joinToString(",")
                .reversed()
        return (if (negative) MINUS else "") + grouped + if (fraction.isEmpty()) "" else ".$fraction"
    }

    /** 9,800円 / 12.3万円 / 1,542万円 / 1億2,345万円. */
    fun yen(value: Double): String {
        val sign = if (value < 0 && abs(value) >= 0.5) MINUS else ""
        val a = abs(value)
        val units = a.roundToLong()
        if (units < 10_000) return "$sign${number(units.toDouble(), 0)}円"
        val tenths = (a / 1_000).roundToLong()
        if (tenths < 1_000) return "$sign${number(tenths / 10.0)}万円"
        val man = (a / 10_000).roundToLong()
        if (man < 10_000) return "$sign${number(man.toDouble(), 0)}万円"
        val oku = man / 10_000
        val rest = man % 10_000
        return sign + number(oku.toDouble(), 0) + "億" + if (rest == 0L) "円" else "${number(rest.toDouble(), 0)}万円"
    }

    fun value(
        value: Double,
        unit: String,
    ): String =
        when (unit) {
            "yen" -> yen(value)
            "pct" -> "${number(value)}%"
            else -> number(value) + unit
        }

    /** Short form for tight spots such as a donut center: 18.2万 (yen) or the full value. */
    fun compact(
        value: Double,
        unit: String,
    ): String = if (unit == "yen") yen(value).removeSuffix("円") else value(value, unit)

    /** Signed change for deltas: + / − are always written out. */
    fun signed(
        value: Double,
        unit: String,
    ): String = if (value < 0) value(value, unit) else "+" + value(value, unit)

    /** Split "1,542万円" into the number part and its trailing unit for two-size typography. */
    fun split(formatted: String): Pair<String, String> {
        val end = formatted.indexOfLast { it.isDigit() }
        return if (end < 0) formatted to "" else formatted.substring(0, end + 1) to formatted.substring(end + 1)
    }

    /** Axis unit caption; yen axes are labeled in compact form (200万) under a 円 caption. */
    fun axisUnit(unit: String): String =
        when (unit) {
            "yen" -> "円"
            "pct" -> "%"
            else -> unit
        }

    fun axisLabel(
        value: Double,
        step: Double,
        unit: String,
    ): String =
        if (unit == "yen" && abs(value) >= 10_000) {
            yen(value).removeSuffix("円")
        } else {
            number(value, decimalsFor(step))
        }

    private fun decimalsFor(step: Double): Int = if (step >= 1) 0 else ceil(-log10(step) - 1e-9).toInt().coerceIn(0, 6)
}

/** Round ("1, 2, 5 x 10^n") ticks that always include 0 and cover [low]..[high]. */
internal data class NiceTicks(
    val values: List<Double>,
    val step: Double,
) {
    val low: Double get() = values.first()
    val high: Double get() = values.last()
}

internal fun niceTicks(
    low: Double,
    high: Double,
): NiceTicks {
    var lo = minOf(0.0, low)
    var hi = maxOf(0.0, high)
    if (!(hi > lo)) hi = lo + 1.0
    val raw = (hi - lo) / 5
    val exponent = floor(log10(raw)).toInt() - 1
    for (power in exponent..exponent + 4) {
        for (multiplier in listOf(1, 2, 5)) {
            val exact = BigDecimal.valueOf(multiplier.toLong()).scaleByPowerOfTen(power)
            val step = exact.toDouble()
            val first = floor(lo / step + 1e-9).toLong()
            val last = ceil(hi / step - 1e-9).toLong()
            if (last - first + 1 <= 6) {
                return NiceTicks((first..last).map { BigDecimal.valueOf(it).multiply(exact).toDouble() }, step)
            }
        }
    }
    lo = floor(lo)
    hi = ceil(hi)
    return NiceTicks(listOf(lo, hi), hi - lo)
}

/** Days from the device's today; the model never writes "today" or a countdown. */
internal fun daysFromToday(
    date: LocalDate,
    clock: Clock,
): Long = ChronoUnit.DAYS.between(LocalDate.now(clock), date)

internal fun relativeDayLabel(days: Long): String =
    when {
        days == 0L -> "今日"
        days == 1L -> "明日"
        days > 1 -> "あと${days}日"
        else -> ""
    }
