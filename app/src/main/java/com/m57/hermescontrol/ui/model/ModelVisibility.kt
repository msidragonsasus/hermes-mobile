package com.m57.hermescontrol.ui.model

import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import kotlinx.serialization.Serializable

/** Picker preferences only: hiding a model never changes the model running a conversation. */
@Serializable
data class ModelVisibility(
    val hiddenModels: Set<String> = emptySet(),
    val hiddenProviders: Set<String> = emptySet(),
) {
    fun isVisible(
        provider: String,
        model: String,
    ): Boolean = provider !in hiddenProviders && key(provider, model) !in hiddenModels

    fun setModelVisible(
        provider: ModelProvider,
        model: String,
        visible: Boolean,
    ): ModelVisibility {
        val hidden = hiddenModels.toMutableSet()
        // Re-enabling one model of a hidden provider restores only that model.
        if (visible && provider.slug in hiddenProviders) {
            provider.models.orEmpty().forEach { hidden.add(key(provider.slug, it)) }
        }
        if (visible) hidden.remove(key(provider.slug, model)) else hidden.add(key(provider.slug, model))
        return copy(
            hiddenModels = hidden,
            hiddenProviders = if (visible) hiddenProviders - provider.slug else hiddenProviders,
        )
    }

    fun setProviderVisible(
        provider: String,
        visible: Boolean,
    ): ModelVisibility =
        copy(
            hiddenModels = hiddenModels.filterNot { it.startsWith("$provider::") }.toSet(),
            hiddenProviders = if (visible) hiddenProviders - provider else hiddenProviders + provider,
        )

    fun filterProviders(providers: List<ModelProvider>): List<ModelProvider> =
        providers.mapNotNull { provider ->
            val models = provider.models.orEmpty().filter { isVisible(provider.slug, it) }
            if (models.isEmpty()) null else provider.copy(models = models)
        }

    fun filterPinned(models: List<PinnedModel>): List<PinnedModel> =
        models.filter { isVisible(it.providerSlug, it.modelName) }

    companion object {
        private fun key(
            provider: String,
            model: String,
        ): String = "$provider::$model"
    }
}
