package com.m57.hermescontrol.career

/** Move only visible slots; filtered-out companies keep their full-list positions. */
internal fun moveVisibleCompany(
    order: List<String>,
    visible: List<String>,
    from: String,
    to: String,
): List<String> {
    if (order.toSet().size != order.size || visible.toSet().size != visible.size ||
        !order.containsAll(visible)
    ) {
        return order
    }
    val source = visible.indexOf(from)
    val destination = visible.indexOf(to)
    if (source < 0 || destination < 0 || source == destination) return order
    val moved = visible.toMutableList().apply { add(destination, removeAt(source)) }
    val shown = visible.toSet()
    var index = 0
    return order.map { if (it in shown) moved[index++] else it }
}

internal fun orderedCompanies(
    rows: List<CareerCompanyRow>,
    order: List<String>?,
): List<CareerCompanyRow> {
    if (order == null) return rows
    val byId = rows.associateBy { it.id }
    val chosen = order.distinct().mapNotNull { byId[it] }
    val selected = chosen.map { it.id }.toSet()
    return chosen + rows.filter { it.id !in selected }
}

internal data class CompanyOrderRequest(
    val ids: List<String>,
    val version: Int,
    val requestId: String,
)

internal fun CareerCompanyRow.matchesCompanyFilter(
    search: String,
    onlyNew: Boolean,
    filter: String,
): Boolean =
    name.contains(search, ignoreCase = true) &&
        (!onlyNew || new_count > 0) &&
        when (filter) {
            "all" -> {
                true
            }

            "login_missing" -> {
                mypage_readiness?.credential_state == "not_configured"
            }

            "login_attention" -> {
                mypage_readiness?.login_state == "needs_attention"
            }

            "login_unverified" -> {
                mypage_readiness?.credential_state in setOf("configured", "legacy_configured") &&
                    mypage_readiness?.login_state == "unknown"
            }

            else -> {
                (application_counts[filter] ?: 0) > 0
            }
        }

internal data class CompanyOrderSlot(
    val id: String,
    val center: Float,
)

/** Wait for layout to reflect a move, then cross only an immediate neighbour. */
internal fun companyDragTarget(
    current: List<String>,
    slots: List<CompanyOrderSlot>,
    id: String,
    center: Float,
): String? {
    val laidOut = slots.map { it.id }
    if (laidOut != current.filter { it in laidOut }) return null
    val index = laidOut.indexOf(id)
    if (index < 0) return null
    return when {
        index > 0 && center < slots[index - 1].center -> laidOut[index - 1]
        index < slots.lastIndex && center > slots[index + 1].center -> laidOut[index + 1]
        else -> null
    }
}
