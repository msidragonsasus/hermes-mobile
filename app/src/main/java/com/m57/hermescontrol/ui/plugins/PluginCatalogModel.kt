package com.m57.hermescontrol.ui.plugins

import androidx.annotation.StringRes
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.PluginCatalogCapabilities
import com.m57.hermescontrol.data.model.PluginCatalogEntry
import com.m57.hermescontrol.data.model.PluginInfo

/*
 * Plain-language view of the Nous-curated plugin catalog (`plugin-catalog/` on the server, refreshed
 * from the Hermes docs site). Kept free of Compose so the rules are unit tested.
 */

/** Shelves shown on the phone, in display order. "desktop" extends the Hermes Desktop app only. */
internal val PhoneCatalogCategories =
    listOf("memory", "tools", "web", "automation", "platform", "voice", "models", "general")

/** Tiers whose entries Nous Research or a verified maintainer stands behind. */
internal val RecommendedTiers = setOf("official", "verified")

/**
 * True when the entry can do anything on this headless Linux server: not a Desktop-app extension
 * and not limited to other operating systems. Unknown categories fall under "general".
 */
internal fun PluginCatalogEntry.runsOnServer(): Boolean =
    category != "desktop" && (platforms.isEmpty() || platforms.any { it.equals("linux", ignoreCase = true) })

internal fun PluginCatalogEntry.shelf(): String = category.takeIf { it in PhoneCatalogCategories } ?: "general"

internal fun PluginCatalogEntry.displayName(): String = title.ifBlank { name }

@StringRes
internal fun catalogCategoryLabel(category: String): Int =
    when (category) {
        "memory" -> R.string.plugins_category_memory
        "platform" -> R.string.plugins_category_platform
        "web" -> R.string.plugins_category_web
        "tools" -> R.string.plugins_category_tools
        "voice" -> R.string.plugins_category_voice
        "automation" -> R.string.plugins_category_automation
        "models" -> R.string.plugins_category_models
        else -> R.string.plugins_category_general
    }

@StringRes
internal fun catalogTierLabel(tier: String): Int =
    when (tier) {
        "official" -> R.string.plugins_tier_official
        "verified" -> R.string.plugins_tier_verified
        else -> R.string.plugins_tier_community
    }

/** Groups of lifecycle hooks, named by what a person would notice. */
internal enum class HookKind(
    @StringRes val label: Int,
) {
    TOOL_CALLS(R.string.plugins_cap_hook_tool_calls),
    AI_REPLIES(R.string.plugins_cap_hook_ai),
    SESSIONS(R.string.plugins_cap_hook_sessions),
    SUBAGENTS(R.string.plugins_cap_hook_subagents),
    API_REQUESTS(R.string.plugins_cap_hook_api),
    OTHER(R.string.plugins_cap_hook_other),
}

internal fun hookKind(hook: String): HookKind =
    when {
        hook.endsWith("_tool_call") -> HookKind.TOOL_CALLS
        hook.endsWith("_llm_call") || hook == "transform_llm_output" || hook == "llm_request" -> HookKind.AI_REPLIES
        hook.startsWith("on_session") -> HookKind.SESSIONS
        hook.startsWith("subagent_") -> HookKind.SUBAGENTS
        hook.contains("api_request") -> HookKind.API_REQUESTS
        else -> HookKind.OTHER
    }

/** What installing the plugin grants, reduced to facts the UI can phrase. */
internal data class CapabilityFacts(
    val toolCount: Int,
    val hookKinds: List<HookKind>,
    val middleware: Boolean,
    val requiredEnv: List<String>,
) {
    val declaresNothing: Boolean
        get() = toolCount == 0 && hookKinds.isEmpty() && !middleware && requiredEnv.isEmpty()

    val automatic: Boolean
        get() = hookKinds.isNotEmpty() || middleware
}

internal fun PluginCatalogCapabilities.facts(): CapabilityFacts =
    CapabilityFacts(
        toolCount = providesTools.distinct().size,
        hookKinds = providesHooks.map(::hookKind).distinct().sorted(),
        middleware = providesMiddleware.isNotEmpty(),
        requiredEnv = requiresEnv.distinct(),
    )

/** First sentence of an English catalog description, for one-line rows. */
internal fun firstSentence(
    text: String,
    maxLength: Int = 140,
): String {
    val trimmed = text.trim()
    val end =
        Regex("""[.!?](\s|$)|。""")
            .find(trimmed)
            ?.let { it.range.first + 1 }
            ?: trimmed.length
    val sentence = trimmed.substring(0, end).trim()
    return if (sentence.length <= maxLength) sentence else sentence.take(maxLength - 1).trimEnd() + "…"
}

private fun normalizedPluginName(name: String): String =
    name
        .lowercase()
        .replace('_', '-')
        .removePrefix("hermes-plugin-")
        .removePrefix("hermes-")
        .removeSuffix("-plugin")

/**
 * The catalog entry an installed plugin most likely came from. Catalog keys rarely equal manifest
 * names ("hermes-plugin-x" vs "x"), so only installed entries are considered and names are compared
 * after dropping the usual prefixes. Returns null rather than guessing between several candidates.
 */
internal fun catalogEntryFor(
    plugin: PluginInfo,
    catalog: List<PluginCatalogEntry>,
): PluginCatalogEntry? {
    val installed = catalog.filter { it.installed }
    installed.firstOrNull { it.name == plugin.name }?.let { return it }
    val target = normalizedPluginName(plugin.name)
    return installed.filter { normalizedPluginName(it.name) == target }.singleOrNull()
}

/** Entries for the phone catalog: runnable here, matching the tier filter and the search text. */
internal fun visibleCatalogEntries(
    catalog: List<PluginCatalogEntry>,
    includeCommunity: Boolean,
    query: String,
): List<PluginCatalogEntry> {
    val q = query.trim()
    return catalog.filter { entry ->
        entry.runsOnServer() &&
            (includeCommunity || entry.tier in RecommendedTiers) &&
            (
                q.isEmpty() ||
                    entry.displayName().contains(q, ignoreCase = true) ||
                    entry.name.contains(q, ignoreCase = true) ||
                    entry.description.contains(q, ignoreCase = true)
            )
    }
}
