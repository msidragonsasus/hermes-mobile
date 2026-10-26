package com.m57.hermescontrol.ui.model

import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelVisibilityTest {
    private val provider = ModelProvider("openai", "OpenAI", models = listOf("one", "two:fast"))

    @Test
    fun `last model remains hidden after serialization and a catalog refresh`() {
        val hidden =
            ModelVisibility()
                .setModelVisible(provider, "one", false)
                .setModelVisible(provider, "two:fast", false)
        val restored = Json.decodeFromString<ModelVisibility>(Json.encodeToString(ModelVisibility.serializer(), hidden))
        assertTrue(restored.filterProviders(listOf(provider)).isEmpty())
        assertTrue(restored.filterPinned(listOf(PinnedModel("openai", "one"))).isEmpty())
        assertFalse(restored.isVisible("openai", "two:fast"))
        // Unjudged new models remain discoverable when only individual models were hidden.
        assertTrue(restored.isVisible("openai", "new"))
    }

    @Test
    fun `hide provider also hides future models without affecting another provider`() {
        val hidden = ModelVisibility().setProviderVisible("openai", false)
        assertFalse(hidden.isVisible("openai", "new"))
        assertTrue(hidden.isVisible("other", "one"))
    }

    @Test
    fun `restoring one model from hidden provider restores only that model`() {
        val restored =
            ModelVisibility().setProviderVisible("openai", false).setModelVisible(provider, "one", true)
        assertTrue(restored.isVisible("openai", "one"))
        assertFalse(restored.isVisible("openai", "two:fast"))
        assertFalse(restored.copy().isVisible("openai", "two:fast"))
    }

    @Test
    fun `restoring provider clears its individual exclusions and preserves others`() {
        val other = ModelProvider("other", "Other", models = listOf("one"))
        val restored =
            ModelVisibility()
                .setModelVisible(provider, "one", false)
                .setModelVisible(other, "one", false)
                .setProviderVisible("openai", true)
        assertTrue(restored.isVisible("openai", "one"))
        assertFalse(restored.isVisible("other", "one"))
    }

    @Test
    fun `filters leave catalog and pinned data intact and preserve model metadata`() {
        val hidden = ModelVisibility().setModelVisible(provider, "one", false)
        val filtered = hidden.filterProviders(listOf(provider)).single()
        assertEquals(listOf("two:fast"), filtered.models)
        assertEquals(provider.name, filtered.name)
        assertEquals(listOf("one", "two:fast"), provider.models)
        assertEquals(
            listOf(PinnedModel("other", "one")),
            hidden.filterPinned(listOf(PinnedModel("openai", "one"), PinnedModel("other", "one"))),
        )
        assertEquals(listOf(provider), ModelVisibility().filterProviders(listOf(provider)))
    }

    @Test
    fun `provider and model identifiers containing colons stay distinct`() {
        val hidden = ModelVisibility().setModelVisible(provider, "two:fast", false)
        assertFalse(hidden.isVisible("openai", "two:fast"))
        assertTrue(hidden.isVisible("other", "two:fast"))
        assertTrue(hidden.isVisible("openai", "two"))
    }
}
