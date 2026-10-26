package com.m57.hermescontrol.career

import com.m57.hermescontrol.home.HomeActionIdentity
import com.m57.hermescontrol.home.HomeItem

/** Projection only: each original item/version remains independently readable and resolvable. */
internal data class CareerActionGroup(
    val sources: List<HomeItem>,
) {
    val item: HomeItem get() = sources.first()
    val id: String get() = item.id
    val identity: HomeActionIdentity? get() = item.action_identity
}

internal fun careerActionGroups(items: List<HomeItem>): List<CareerActionGroup> =
    items
        .groupBy { item ->
            val identity = item.action_identity
            val company = item.target.company_id
            when {
                item.provider != "career" || item.category != "action" || item.target.kind != "mail" ||
                    company.isNullOrBlank() -> {
                    listOf("single", item.id)
                }

                identity != null && identity.method == "model_evidence" &&
                    identity.source_id == item.id && identity.source_version == item.version &&
                    identity.company_id == company && identity.deadline == item.deadline &&
                    identity.key.isNotBlank() && identity.program.isNotBlank() &&
                    identity.cohort.isNotBlank() && identity.round.isNotBlank() &&
                    identity.action in setOf("submit_es", "answer_survey", "reserve", "reply", "submit_document") &&
                    !identity.deadline.isNullOrBlank() && identity.evidence.map { it.field }.toSet() ==
                    setOf("program", "cohort", "round", "action", "deadline") &&
                    identity.evidence.all {
                        it.source_id == item.id && it.source_version == item.version &&
                            it.start < it.end
                    } -> {
                    listOf(
                        "model",
                        company,
                        identity.key,
                        identity.program,
                        identity.cohort,
                        identity.round,
                        identity.action,
                        identity.deadline,
                    )
                }

                identity == null && !item.content_hash.isNullOrBlank() -> {
                    // Exact saved subject+body only. No keyword/URL-based semantic fallback.
                    listOf("exact", company, item.content_hash, item.deadline)
                }

                else -> {
                    listOf("single", item.id)
                }
            }
        }.values
        .map { CareerActionGroup(it.sortedBy(HomeItem::id)) }
