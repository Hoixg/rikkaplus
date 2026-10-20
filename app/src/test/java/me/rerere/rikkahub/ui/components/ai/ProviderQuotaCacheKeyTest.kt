package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.ApiKeyEntry
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.withApiKeyEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProviderQuotaCacheKeyTest {
    @Test
    fun `switching or replacing a key invalidates quota cache without storing the secret in its cache key`() {
        val first = ApiKeyEntry(id = "first", key = "first-secret")
        val second = ApiKeyEntry(id = "second", key = "second-secret")
        val provider = ProviderSetting.OpenAI()
        val initial = provider.withApiKeyEntries(listOf(first, second), first.id) as ProviderSetting.OpenAI
        val switched = provider.withApiKeyEntries(listOf(first, second), second.id) as ProviderSetting.OpenAI
        val edited = initial.withApiKeyEntries(listOf(first.copy(key = "replacement"))) as ProviderSetting.OpenAI
        val renamed = initial.withApiKeyEntries(listOf(first.copy(name = "new name"))) as ProviderSetting.OpenAI
        assertNotEquals(initial.quotaCacheKey(), switched.quotaCacheKey())
        assertNotEquals(initial.quotaCacheKey(), edited.quotaCacheKey())
        assertEquals(initial.quotaCacheKey(), renamed.quotaCacheKey())
        assertFalse(initial.quotaCacheKey().contains(first.key))
        assertNotEquals(initial.quotaCacheKey(), initial.copy(baseUrl = "https://other.example/v1").quotaCacheKey())
        assertNotEquals(initial.quotaCacheKey(), initial.copy(quotaQuery = initial.quotaQuery.copy(enabled = true)).quotaCacheKey())
    }

    @Test
    fun `changing subscription credentials invalidates quota cache without storing tokens`() {
        val credential = OAuthCredential(accessToken = "secret-access", accountId = "account")
        val provider = ProviderSetting.ChatGPT(credential = credential)
        val refreshed = provider.copy(credential = credential.copy(accessToken = "new-access"))
        assertNotEquals(provider.quotaCacheKey(), refreshed.quotaCacheKey())
        assertNotEquals(provider.quotaCacheKey(), provider.copy(credential = null).quotaCacheKey())
        assertFalse(provider.quotaCacheKey().contains(credential.accessToken))
    }
}
