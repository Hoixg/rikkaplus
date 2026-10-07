package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ApiKeyInfo
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.apiKeyInfos
import me.rerere.ai.provider.apiKeyReference
import me.rerere.ai.provider.selectedApiKeyIndex
import me.rerere.ai.provider.withApiKeyInfos
import me.rerere.rikkahub.utils.normalizeAutoCompactionThresholdPercent
import me.rerere.rikkahub.utils.normalizeAutoCompactionTokenLimit

/** 读盘后和每次修改后都要过一遍，保证内存里的设置始终是规整的。 */
internal fun Settings.normalized(): Settings =
    withBuiltInDefaults().withoutInvalidReferences().withValuesInRange()

// 补齐缺失的内置助手/TTS；提供商列表保留用户的显式选择
internal fun Settings.withBuiltInDefaults(): Settings {
    val assistants = this.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
    DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
        if (assistants.none { it.id == defaultAssistant.id }) {
            assistants.add(defaultAssistant.copy())
        }
    }
    val ttsProviders = this.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
    DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
        if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
            ttsProviders.add(defaultTTSProvider.copyProvider())
        }
    }
    return copy(
        assistants = assistants,
        ttsProviders = ttsProviders,
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
            val selectedIndex = provider.selectedApiKeyIndex()
            provider.withApiKeyInfos(provider.apiKeyInfos(), selectedIndex).let { normalized ->
                val keyReferences = buildMap<String, ApiKeyInfo> {
                    normalized.apiKeyInfos().forEach { info ->
                        put(apiKeyReference(info), info)
                        put(apiKeyReference(info.key), info)
                        put(info.key, info)
                    }
                }
                fun normalizeModel(model: Model): Model {
                    val reference = model.apiKeyRef?.trim()?.takeIf { it.isNotBlank() }
                    val normalizedReference = reference?.let(keyReferences::get)?.let(::apiKeyReference)
                    return model.copy(apiKeyRef = normalizedReference)
                }
                when (normalized) {
                    is ProviderSetting.OpenAI -> normalized.copy(
                        models = normalized.models.distinctBy { model -> model.id }.map(::normalizeModel)
                    )

                    is ProviderSetting.Google -> normalized.copy(
                        models = normalized.models.distinctBy { model -> model.id }.map(::normalizeModel)
                    )

                    is ProviderSetting.Claude -> normalized.copy(
                        models = normalized.models.distinctBy { model -> model.id }.map(::normalizeModel)
                    )
                }
            }
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
    searchServiceSelected = searchServiceSelected.coerceIn(0, (searchServices.size - 1).coerceAtLeast(0)),
    defaultTTSPlaybackSpeed = defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f),
    autoCompactionThresholdPercent = normalizeAutoCompactionThresholdPercent(autoCompactionThresholdPercent),
    autoCompactionTokenLimit = normalizeAutoCompactionTokenLimit(autoCompactionTokenLimit),
)
