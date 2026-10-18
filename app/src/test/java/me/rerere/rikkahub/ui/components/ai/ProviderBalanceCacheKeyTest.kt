package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.ApiKeyEntry
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.withApiKeyEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProviderBalanceCacheKeyTest {
    @Test
    fun `switching or replacing a key invalidates balance cache without storing the secret in its cache key`() {
        val first = ApiKeyEntry(id = "first", key = "first-secret")
        val second = ApiKeyEntry(id = "second", key = "second-secret")
        val provider = ProviderSetting.OpenAI()
        val initial = provider.withApiKeyEntries(listOf(first, second), first.id) as ProviderSetting.OpenAI
        val switched = provider.withApiKeyEntries(listOf(first, second), second.id) as ProviderSetting.OpenAI
        val edited = initial.withApiKeyEntries(listOf(first.copy(key = "replacement"))) as ProviderSetting.OpenAI
        val renamed = initial.withApiKeyEntries(listOf(first.copy(name = "new name"))) as ProviderSetting.OpenAI
        assertNotEquals(initial.balanceCacheKey(), switched.balanceCacheKey())
        assertNotEquals(initial.balanceCacheKey(), edited.balanceCacheKey())
        assertEquals(initial.balanceCacheKey(), renamed.balanceCacheKey())
        assertFalse(initial.balanceCacheKey().contains(first.key))
    }
}
