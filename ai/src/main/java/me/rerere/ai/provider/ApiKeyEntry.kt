package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.uuid.Uuid

@Serializable
data class ApiKeyEntry(
    val id: String = Uuid.random().toString(),
    val key: String,
    val name: String = "",
    val multiplier: Float = 1f,
)

private val API_KEY_SEPARATOR = Regex("[\\s,]+")

/** Existing upstream settings can still contain comma/whitespace-separated keys. */
fun ProviderSetting.apiKeyEntries(): List<ApiKeyEntry> {
    if (this !is ProviderSetting.ApiKey) return emptyList()
    return normalizeEntries(
        apiKeyEntries.ifEmpty {
            apiKey.split(API_KEY_SEPARATOR).filter(String::isNotBlank).map { key ->
                ApiKeyEntry(id = legacyKeyId(key), key = key)
            }
        }
    )
}

private fun legacyKeyId(key: String): String = MessageDigest.getInstance("SHA-256")
    .digest(key.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun normalizeEntries(entries: List<ApiKeyEntry>): List<ApiKeyEntry> = entries
    .map { it.copy(key = it.key.trim(), name = it.name.trim(), id = it.id.ifBlank { legacyKeyId(it.key.trim()) }) }
    .filter { it.key.isNotBlank() }
    .distinctBy { it.key }
    .distinctBy { it.id }
    .mapIndexed { index, entry ->
        entry.copy(
            name = entry.name.ifBlank { "Key ${index + 1}" },
            multiplier = entry.multiplier.takeIf { it.isFinite() && it > 0f } ?: 1f,
        )
    }

/** Every request uses the selected key; unset or removed selections fall back to the first entry. */
fun ProviderSetting.selectedApiKey(): String {
    if (this !is ProviderSetting.ApiKey) return ""
    val entries = apiKeyEntries()
    return (entries.find { it.id == selectedApiKeyId } ?: entries.firstOrNull())?.key.orEmpty()
}

/** The list is authoritative; clear the upstream string so removed keys cannot return. */
fun ProviderSetting.withApiKeyEntries(
    entries: List<ApiKeyEntry>,
    selectedId: String? = (this as? ProviderSetting.ApiKey)?.selectedApiKeyId,
): ProviderSetting {
    val normalized = normalizeEntries(entries)
    val selected = normalized.find { it.id == selectedId }?.id ?: normalized.firstOrNull()?.id
    return when (this) {
        is ProviderSetting.OpenAI -> copy(apiKey = "", apiKeyEntries = normalized, selectedApiKeyId = selected)
        is ProviderSetting.Google -> copy(apiKey = "", apiKeyEntries = normalized, selectedApiKeyId = selected)
        is ProviderSetting.Claude -> copy(apiKey = "", apiKeyEntries = normalized, selectedApiKeyId = selected)
        is ProviderSetting.Account -> this
    }
}

fun ApiKeyEntry.displayLabel(): String {
    val value = multiplier.toBigDecimal().stripTrailingZeros().toPlainString()
    return "$name · $value×"
}
