package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ApiKeyEntry
import me.rerere.ai.provider.apiKeyEntries
import me.rerere.ai.provider.withApiKeyEntries
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class FindProviderTest {
    @Test
    fun `model key wins without changing persisted provider selection`() {
        val first = ApiKeyEntry(id = "first", key = "first-secret")
        val second = ApiKeyEntry(id = "second", key = "second-secret")
        val model = Model(modelId = "test", apiKeyId = second.id)
        val provider = ProviderSetting.OpenAI(models = listOf(model))
            .withApiKeyEntries(listOf(first, second), selectedId = first.id) as ProviderSetting.ApiKey
        val resolved = model.findProvider(listOf(provider)) as ProviderSetting.ApiKey
        assertEquals(second.id, resolved.selectedApiKeyId)
        assertEquals(listOf(second.copy(name = "Key 2")), resolved.apiKeyEntries())
        assertEquals(first.id, provider.selectedApiKeyId)
        assertEquals(2, model.findProvider(listOf(provider), checkOverwrite = false)!!.apiKeyEntries().size)
        val fallback = model.copy(apiKeyId = "deleted").findProvider(listOf(provider)) as ProviderSetting.ApiKey
        assertEquals(first.id, fallback.selectedApiKeyId)
    }

    @Test
    fun `model override resolves keys from override configuration`() {
        val key = ApiKeyEntry(id = "override-key", key = "override-secret")
        val override = ProviderSetting.Google().withApiKeyEntries(listOf(key))
        val model = Model(modelId = "test", apiKeyId = key.id, providerOverwrite = override)
        val provider = ProviderSetting.OpenAI(models = listOf(model), apiKey = "base-secret")
        val resolved = model.findProvider(listOf(provider)) as ProviderSetting.ApiKey
        assertEquals(override.id, resolved.id)
        assertEquals(key.id, resolved.selectedApiKeyId)
        assertEquals("override-secret", resolved.apiKeyEntries().single().key)
    }

    @Test
    fun `findProvider returns base provider when model has no overwrite`() {
        val model = Model(modelId = "gpt-image-2", displayName = "gpt-image-2", type = ModelType.IMAGE)
        val provider = ProviderSetting.OpenAI(
            name = "OpenAI",
            models = listOf(model),
        )

        val resolved = model.findProvider(listOf(provider))

        assertEquals(provider.id, resolved?.id)
        assertEquals("OpenAI", resolved?.name)
    }

    @Test
    fun `findProvider returns overwrite instead of looking it up in settings providers`() {
        val overwrite = ProviderSetting.OpenAI(
            name = "Override",
            apiKey = "override-key",
            baseUrl = "https://override.example/v1",
        )
        val model = Model(
            modelId = "gpt-image-2",
            displayName = "gpt-image-2",
            type = ModelType.IMAGE,
            providerOverwrite = overwrite,
        )
        val provider = ProviderSetting.OpenAI(
            name = "OpenAI",
            apiKey = "base-key",
            models = listOf(model),
        )
        val providers = listOf(provider)

        val resolved = model.findProvider(providers)

        assertNotNull(resolved)
        assertEquals(overwrite.id, resolved?.id)
        assertEquals("Override", resolved?.name)
        assertEquals("https://override.example/v1", (resolved as ProviderSetting.OpenAI).baseUrl)
        assertEquals("override-key", resolved.apiKey)
        assertNotEquals(provider.id, resolved.id)
        assertNull(providers.find { it.id == resolved.id })
    }
}
