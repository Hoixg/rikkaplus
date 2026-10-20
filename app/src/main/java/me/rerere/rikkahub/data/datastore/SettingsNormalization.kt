package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.apiKeyEntries
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

// Match the former presets exactly; reused IDs with user configuration remain editable.
private val REMOVED_RELAY_PRESETS = mapOf(
    Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953") to ("AiHubMix" to "https://aihubmix.com/v1"),
    Uuid.parse("2a05506f-3a59-450a-a493-33a82bc85a81") to ("APIMart" to "https://api.apimart.ai/v1"),
    Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7") to ("302.AI" to "https://api.302.ai/v1"),
    Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd") to ("随想AI网关" to "https://sui-xiang.com/v1"),
    Uuid.parse("afbc54ad-807e-4455-9594-7d7a546356ad") to ("MaruCode" to "https://api.muteki.site/v1"),
)
private val REMOVED_RELAY_TTS_ID = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c")

private fun ProviderSetting.isUnusedRemovedRelay(): Boolean {
    val preset = REMOVED_RELAY_PRESETS[id] ?: return false
    return this is ProviderSetting.OpenAI && name == preset.first && baseUrl.trimEnd('/') == preset.second &&
        apiKeyEntries().isEmpty() && models.isEmpty()
}

private fun TTSProviderSetting.isUnusedRemovedRelay(): Boolean =
    this is TTSProviderSetting.OpenAI && id == REMOVED_RELAY_TTS_ID && name == "AiHubMix" &&
        baseUrl.trimEnd('/') == "https://aihubmix.com/v1" && apiKey.isBlank() &&
        model == "gpt-4o-mini-tts" && voice == "alloy"

/** 读盘后和每次修改后都要过一遍，保证内存里的设置始终是规整的。 */
internal fun Settings.normalized(): Settings =
    withBuiltInDefaults().withoutInvalidReferences().withValuesInRange()

// 补齐缺失的内置提供商/助手/TTS，内置提供商的描述信息始终以代码里的为准
internal fun Settings.withBuiltInDefaults(): Settings {
    var providers = this.providers.filterNot { it.isUnusedRemovedRelay() }
        .ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
    DEFAULT_PROVIDERS.forEach { defaultProvider ->
        if (providers.none { it.id == defaultProvider.id }) {
            providers.add(defaultProvider.copyProvider())
        }
    }
    providers = providers.map { provider ->
        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
        if (defaultProvider != null) {
            provider.copyProvider(
                builtIn = defaultProvider.builtIn,
                description = defaultProvider.description,
                shortDescription = defaultProvider.shortDescription,
            )
        } else if (provider.id in REMOVED_RELAY_PRESETS) {
            provider.copyProvider(builtIn = false, description = {}, shortDescription = {})
        } else provider
    }.toMutableList()
    val assistants = this.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
    DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
        if (assistants.none { it.id == defaultAssistant.id }) {
            assistants.add(defaultAssistant.copy())
        }
    }
    val ttsProviders = this.ttsProviders.filterNot { it.isUnusedRemovedRelay() }
        .ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
    DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
        if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
            ttsProviders.add(defaultTTSProvider.copyProvider())
        }
    }
    return copy(
        providers = providers,
        assistants = assistants,
        ttsProviders = ttsProviders,
        selectedTTSProviderId = if (selectedTTSProviderId == REMOVED_RELAY_TTS_ID &&
            ttsProviders.none { it.id == selectedTTSProviderId }
        ) DEFAULT_SYSTEM_TTS_ID else selectedTTSProviderId,
    )
}

// 去重并清理无效引用
internal fun Settings.withoutInvalidReferences(): Settings {
    val settings = this
    val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
    val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
    val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
    val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
    val validModelIds by lazy { settings.providers.flatMap { it.models }.mapTo(HashSet()) { it.id } }
    val asrProviders = settings.asrProviders.distinctBy { it.id }
    return settings.copy(
        providers = settings.providers.distinctBy { it.id }.map { provider ->
            provider.copyProvider(models = provider.models.distinctBy { model -> model.id })
        },
        assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
            assistant.copy(
                // 过滤掉不存在的 MCP 服务器 ID
                mcpServers = assistant.mcpServers.filter { serverId ->
                    serverId in validMcpServerIds
                }.toSet(),
                // 过滤掉不存在的模式注入 ID
                modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                    id in validModeInjectionIds
                }.toSet(),
                // 过滤掉不存在的 Lorebook ID
                lorebookIds = assistant.lorebookIds.filter { id ->
                    id in validLorebookIds
                }.toSet(),
                // 过滤掉不存在的快捷消息 ID
                quickMessageIds = assistant.quickMessageIds.filter { id ->
                    id in validQuickMessageIds
                }.toSet()
            )
        },
        ttsProviders = settings.ttsProviders.distinctBy { it.id },
        asrProviders = asrProviders,
        selectedASRProviderId = settings.selectedASRProviderId
            ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
            ?: asrProviders.firstOrNull()?.id,
        favoriteModels = settings.favoriteModels.filter { it in validModelIds },
        modeInjections = settings.modeInjections.distinctBy { it.id },
        lorebooks = settings.lorebooks.distinctBy { it.id },
        quickMessages = settings.quickMessages.distinctBy { it.id },
    )
}

// 把取值收回合法范围，比如删掉搜索服务后选中的下标可能越界
private fun Settings.withValuesInRange(): Settings = copy(
    termuxConfig = termuxConfig.normalized(),
    searchServiceSelected = searchServiceSelected.coerceIn(0, (searchServices.size - 1).coerceAtLeast(0)),
    defaultTTSPlaybackSpeed = defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f),
)
