package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ApiKeyEntry
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.apiKeyEntries
import me.rerere.ai.provider.withApiKeyEntries
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class RemovedRelayPresetsTest {
    private val oldRelay = ProviderSetting.OpenAI(
        id = Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        builtIn = true,
    )
    private val oldTts = TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
    )

    @Test
    fun `normalization removes unused relay presets and never restores them`() {
        val cleaned = Settings(providers = listOf(oldRelay)).withBuiltInDefaults().withBuiltInDefaults()
        assertFalse(cleaned.providers.any { it.id == oldRelay.id })
        assertTrue(cleaned.providers.any { it.name == "OpenAI" })
        assertTrue(cleaned.providers.any { it.name == "Gemini" })
    }

    @Test
    fun `configured legacy and multi key relays remain editable with models intact`() {
        val key = ApiKeyEntry(key = "saved-secret", name = "custom", multiplier = 0.5f)
        val model = Model(modelId = "saved-model")
        val cases = listOf(
            oldRelay.copy(apiKey = "legacy-secret"),
            oldRelay.withApiKeyEntries(listOf(key)),
            oldRelay.copy(models = listOf(model)),
        )
        cases.forEach { provider ->
            val saved = Settings(providers = listOf(provider)).withBuiltInDefaults()
                .providers.single { it.id == provider.id }
            assertFalse(saved.builtIn)
            assertEquals(provider.apiKeyEntries(), saved.apiKeyEntries())
            assertEquals(provider.models, saved.models)
        }
    }

    @Test
    fun `renamed reused and manually added providers are preserved`() {
        val cases = listOf(
            oldRelay.copy(name = "My endpoint"),
            oldRelay.copy(baseUrl = "https://my-endpoint.example/v1"),
            oldRelay.copy(id = Uuid.random(), builtIn = false),
        )
        cases.forEach { provider ->
            assertTrue(Settings(providers = listOf(provider)).withBuiltInDefaults().providers.any {
                it.id == provider.id && it.name == provider.name
            })
        }
    }

    @Test
    fun `unused relay tts is removed and selected voice provider falls back to system`() {
        val settings = Settings(ttsProviders = listOf(oldTts), selectedTTSProviderId = oldTts.id)
            .withBuiltInDefaults()
        assertFalse(settings.ttsProviders.any { it.id == oldTts.id })
        assertEquals(DEFAULT_SYSTEM_TTS_ID, settings.selectedTTSProviderId)
    }

    @Test
    fun `configured tts and its selection are preserved`() {
        val configured = oldTts.copy(apiKey = "saved-secret")
        val settings = Settings(ttsProviders = listOf(configured), selectedTTSProviderId = configured.id)
            .withBuiltInDefaults()
        assertEquals(configured, settings.ttsProviders.single { it.id == configured.id })
        assertEquals(configured.id, settings.selectedTTSProviderId)
    }
}
