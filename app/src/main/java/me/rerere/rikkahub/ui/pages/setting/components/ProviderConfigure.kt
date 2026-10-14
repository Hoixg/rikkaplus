package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import me.rerere.ai.provider.ApiKeyInfo
import me.rerere.ai.provider.apiKeyInfos
import me.rerere.ai.provider.selectedApiKeyIndex
import me.rerere.ai.provider.withApiKeyInfos
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import kotlin.uuid.Uuid
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.ui.toast.ToastType
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass

@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onApiKeySelected: ((provider: ProviderSetting) -> Unit)? = null,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
    ) {
        provider.description()

        CardGroup {
            // 内置服务商的类型是定死的
            if (!provider.builtIn) {
                formItem {
                    ConnectedChoiceButtons(
                        options = ProviderSetting.Types,
                        selected = provider::class,
                        onSelect = { onEdit(provider.convertTo(it)) },
                    ) { type, selected ->
                        ConnectedChoiceLabel(text = type.simpleName ?: "", selected = selected)
                    }
                }
            }
            switchItem(
                checked = provider.enabled,
                onCheckedChange = { onEdit(provider.copyProvider(enabled = it)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_enable)) },
            )
        }

        when (provider) {
            is ProviderSetting.OpenAI -> ProviderConfigureOpenAI(provider, onEdit, onApiKeySelected ?: onEdit)
            is ProviderSetting.Google -> ProviderConfigureGoogle(provider, onEdit, onApiKeySelected ?: onEdit)
            is ProviderSetting.Claude -> ProviderConfigureClaude(provider, onEdit, onApiKeySelected ?: onEdit)
        }
    }
}

fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) return this

    val normalizedApiKeyInfos = this.apiKeyInfos()
    val apiKeys = normalizedApiKeyInfos.map { it.key }
    val storedApiKeyInfos = when (this) {
        is ProviderSetting.OpenAI -> this.apiKeyInfos
        is ProviderSetting.Google -> this.apiKeyInfos
        is ProviderSetting.Claude -> this.apiKeyInfos
    }.filter { it.key.trim() in apiKeys }
    val selectedApiKeyIndex = this.selectedApiKeyIndex()
    val normalizedSelectedIndex = selectedApiKeyIndex.takeIf { it in apiKeys.indices } ?: 0
    val apiKey = apiKeys.getOrNull(normalizedSelectedIndex).orEmpty()
    val sourceBaseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
    }
    val targetDefaultBaseUrl = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().baseUrl
        ProviderSetting.Google::class -> ProviderSetting.Google().baseUrl
        ProviderSetting.Claude::class -> ProviderSetting.Claude().baseUrl
        else -> error("Unsupported provider type: $type")
    }
    val convertedBaseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)

    return when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, apiKeys = apiKeys, selectedApiKeyIndex = normalizedSelectedIndex, apiKeyInfos = storedApiKeyInfos, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, apiKeys = apiKeys, selectedApiKeyIndex = normalizedSelectedIndex, apiKeyInfos = storedApiKeyInfos, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, apiKeys = apiKeys, selectedApiKeyIndex = normalizedSelectedIndex, apiKeyInfos = storedApiKeyInfos, baseUrl = convertedBaseUrl
        )
        else -> error("Unsupported provider type: $type")
    }
}

internal fun ProviderSetting.defaultBaseUrlForReset(): String {
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    if (defaultProvider != null) {
        when (this) {
            is ProviderSetting.OpenAI -> if (defaultProvider is ProviderSetting.OpenAI) return defaultProvider.baseUrl
            is ProviderSetting.Google -> if (defaultProvider is ProviderSetting.Google) return defaultProvider.baseUrl
            is ProviderSetting.Claude -> if (defaultProvider is ProviderSetting.Claude) return defaultProvider.baseUrl
        }
    }
    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
    }
}

internal fun ProviderSetting.isUsingDefaultBaseUrl(): Boolean {
    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
    }
    return baseUrl == defaultBaseUrlForReset()
}

// 地址不是默认值时，输入框右侧出现的恢复默认按钮
private fun resetBaseUrlButton(
    provider: ProviderSetting,
    onReset: () -> Unit,
): (@Composable () -> Unit)? {
    if (provider.isUsingDefaultBaseUrl()) return null
    return {
        IconButton(onClick = onReset) {
            Icon(
                imageVector = HugeIcons.Refresh03,
                contentDescription = stringResource(R.string.setting_model_page_reset_to_default),
            )
        }
    }
}

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) return targetDefaultBaseUrl
    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder().encodedPath(convertedPath).build().toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = this.normalizePath()
    val target = targetPath.normalizePath()
    val replaced = when {
        source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
        source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
        source.isBlank() -> target
        else -> source + target
    }
    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = this.trim()
    if (value.isEmpty() || value == "/") return ""
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

private fun String.isValidBaseUrl(): Boolean = this.toHttpUrlOrNull() != null

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST
)

// 标题在上、输入框在下的表单项；secret 为 true 时默认遮住内容，右侧是显示/隐藏按钮
private fun CardGroupScope.textFieldItem(
    @StringRes label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
    secret: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    monospace: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) = formItem(
    headlineContent = { Text(stringResource(label)) },
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = isError,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono)
        } else LocalTextStyle.current,
        supportingText = supportingText,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            }
        } else trailingIcon,
    )
}

private fun CardGroupScope.nameItem(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) = textFieldItem(
    label = R.string.setting_provider_page_name,
    value = provider.name,
    onValueChange = { onEdit(provider.copyProvider(name = it)) },
)

private fun CardGroupScope.apiKeyItem(
    apiKey: String,
    onValueChange: (String) -> Unit,
) = textFieldItem(
    label = R.string.setting_provider_page_api_key,
    value = apiKey,
    onValueChange = { onValueChange(it.trim()) },
    secret = true,
    maxLines = 3,
)

@Composable
private fun ApiKeyEditor(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    onApiKeySelected: (ProviderSetting) -> Unit,
) {
    val entries = provider.apiKeyInfos()
    val selectedIndex = provider.selectedApiKeyIndex().coerceIn(0, (entries.size - 1).coerceAtLeast(0))
    var showEditor by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var draftKey by remember { mutableStateOf("") }
    var draftName by remember { mutableStateOf("") }
    var draftMultiplier by remember { mutableStateOf("1.0") }
    var draftShowKey by remember { mutableStateOf(false) }

    fun openEditor(index: Int?) {
        val entry = index?.let { entries.getOrNull(it) }
        editingIndex = index
        showEditor = true
        draftKey = entry?.key.orEmpty()
        draftName = entry?.name.orEmpty()
        draftMultiplier = entry?.let { formatMultiplier(it.multiplier) } ?: "1.0"
        draftShowKey = false
    }

    val normalizedDraftKey = draftKey.trim()
    val draftMultiplierValue = draftMultiplier.toFloatOrNull()
    val duplicateDraftKey = entries.withIndex().any { (index, entry) ->
        index != editingIndex && entry.key == normalizedDraftKey
    }
    val draftValid = normalizedDraftKey.isNotBlank() && !duplicateDraftKey &&
        draftMultiplierValue != null && draftMultiplierValue > 0f

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_api_key),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { openEditor(null) }) {
                Icon(
                    HugeIcons.Add01,
                    contentDescription = stringResource(R.string.setting_provider_page_api_key_add),
                )
            }
        }
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.setting_provider_page_api_key_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entries.forEachIndexed { index, entry ->
            val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
            val dismissState = remember(index, entry.key) {
                SwipeToDismissBoxState(
                    initialValue = SwipeToDismissBoxValue.Settled,
                    positionalThreshold = positionThreshold,
                )
            }
            LaunchedEffect(dismissState.currentValue) {
                if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                    val remaining = entries.toMutableList().apply { removeAt(index) }
                    val nextIndex = when {
                        remaining.isEmpty() -> 0
                        index < selectedIndex -> selectedIndex - 1
                        index == selectedIndex -> index.coerceAtMost(remaining.lastIndex)
                        else -> selectedIndex
                    }
                    onEdit(provider.withApiKeyInfos(remaining, nextIndex))
                }
            }
            SwipeToDismissBox(
                state = dismissState,
                enableDismissFromStartToEnd = false,
                backgroundContent = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.setting_provider_page_api_key_delete), tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                },
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onApiKeySelected(provider.withApiKeyInfos(entries, index))
                        },
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = null,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.name.ifBlank { stringResource(R.string.setting_provider_page_api_key_unnamed) },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "x${formatMultiplier(entry.multiplier)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { openEditor(index) }) {
                            Icon(
                                HugeIcons.PencilEdit01,
                                contentDescription = stringResource(R.string.setting_provider_page_api_key_edit),
                            )
                        }
                    }
                }
            }
            if (index < entries.lastIndex) {
                HorizontalDivider()
            }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = {
                Text(
                    if (editingIndex == null) stringResource(R.string.setting_provider_page_api_key_add)
                    else stringResource(R.string.setting_provider_page_api_key_edit),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draftKey,
                        onValueChange = { draftKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
                        singleLine = true,
                        visualTransformation = if (draftShowKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { draftShowKey = !draftShowKey }) {
                                Icon(if (draftShowKey) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                            }
                        },
                    )
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setting_provider_page_api_key_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draftMultiplier,
                        onValueChange = { draftMultiplier = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setting_provider_page_api_key_multiplier)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val info = ApiKeyInfo(
                            key = normalizedDraftKey,
                            name = draftName,
                            multiplier = draftMultiplierValue ?: 1f,
                            id = editingIndex?.let(entries::getOrNull)?.id ?: Uuid.random().toString(),
                        )
                        val updated = if (editingIndex == null) entries + info else entries.mapIndexed { index, entry ->
                            if (index == editingIndex) info else entry
                        }
                        onEdit(provider.withApiKeyInfos(updated, if (editingIndex == null && entries.isEmpty()) 0 else selectedIndex))
                        showEditor = false
                    },
                    enabled = draftValid,
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

}

private fun formatMultiplier(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()

private fun maskApiKey(key: String): String = when {
    key.length <= 8 -> "*".repeat(key.length)
    else -> key.take(4) + "..." + key.takeLast(4)
}

@Composable
private fun ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting) -> Unit,
    onApiKeySelected: (provider: ProviderSetting) -> Unit,
) {
    val toaster = LocalToaster.current
    val responseAPIWarning = stringResource(R.string.setting_provider_page_response_api_warning)

    CardGroup {
        nameItem(provider, onEdit)
        textFieldItem(
            label = R.string.setting_provider_page_api_base_url,
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
            trailingIcon = resetBaseUrlButton(provider) {
                onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
            },
        )
        textFieldItem(
            label = R.string.setting_provider_page_api_path,
            value = if (provider.useResponseApi) provider.responsesPath else provider.chatCompletionsPath,
            onValueChange = {
                onEdit(
                    if (provider.useResponseApi) {
                        provider.copy(responsesPath = it.trim())
                    } else {
                        provider.copy(chatCompletionsPath = it.trim())
                    }
                )
            },
            enabled = !provider.builtIn,
        )
    }

    ApiKeyEditor(provider = provider, onEdit = onEdit, onApiKeySelected = onApiKeySelected)

    CardGroup {
        switchItem(
            checked = provider.useResponseApi,
            onCheckedChange = {
                onEdit(provider.copy(useResponseApi = it))
                if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                    toaster.show(message = responseAPIWarning, type = ToastType.Warning)
                }
            },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_response_api)) },
        )
        switchItem(
            checked = provider.includeHistoryReasoning,
            onCheckedChange = { onEdit(provider.copy(includeHistoryReasoning = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_include_history_reasoning)) },
        )
    }
}

@Composable
private fun ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting) -> Unit,
    onApiKeySelected: (provider: ProviderSetting) -> Unit,
) {
    CardGroup {
        nameItem(provider, onEdit)
        textFieldItem(
            label = R.string.setting_provider_page_api_base_url,
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
            trailingIcon = resetBaseUrlButton(provider) {
                onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
            },
        )
    }

    ApiKeyEditor(provider = provider, onEdit = onEdit, onApiKeySelected = onApiKeySelected)

    CardGroup {
        switchItem(
            checked = provider.promptCaching,
            onCheckedChange = { onEdit(provider.copy(promptCaching = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_prompt_caching)) },
        )
        if (provider.promptCaching) {
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl)) },
            ) {
                ConnectedChoiceButtons(
                    options = ClaudePromptCacheTtl.entries,
                    selected = provider.promptCacheTtl,
                    onSelect = { onEdit(provider.copy(promptCacheTtl = it)) },
                ) { ttl, selected ->
                    ConnectedChoiceLabel(
                        text = when (ttl) {
                            ClaudePromptCacheTtl.FIVE_MINUTES -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_5m)
                            ClaudePromptCacheTtl.ONE_HOUR -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_1h)
                        },
                        selected = selected,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigureGoogle(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting) -> Unit,
    onApiKeySelected: (provider: ProviderSetting) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readText()
                ?: return@rememberLauncherForActivityResult
            val json = Json.parseToJsonElement(content).jsonObject
            onEdit(
                provider.copy(
                    projectId = json["project_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.projectId,
                    serviceAccountEmail = json["client_email"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.serviceAccountEmail,
                    privateKey = json["private_key"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.privateKey,
                )
            )
            toaster.show("Service account imported", type = ToastType.Success)
        } catch (e: Exception) {
            toaster.show("Failed to import: ${e.message}", type = ToastType.Error)
        }
    }
    val useServiceAccount = provider.vertexAI && provider.useServiceAccount

    CardGroup {
        nameItem(provider, onEdit)
        if (!provider.vertexAI) {
            textFieldItem(
                label = R.string.setting_provider_page_api_base_url,
                value = provider.baseUrl,
                onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
                isError = provider.baseUrl.isNotBlank() && (
                    !provider.baseUrl.isValidBaseUrl() || !provider.baseUrl.endsWith("/v1beta")
                    ),
                supportingText = if (!provider.baseUrl.endsWith("/v1beta")) {
                    { Text("The base URL usually ends with `/v1beta`") }
                } else null,
                trailingIcon = resetBaseUrlButton(provider) {
                    onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
                },
            )
        }
    }

    if (!useServiceAccount) {
        ApiKeyEditor(provider = provider, onEdit = onEdit, onApiKeySelected = onApiKeySelected)
    }

    CardGroup {
        switchItem(
            checked = provider.vertexAI,
            onCheckedChange = { onEdit(provider.copy(vertexAI = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_vertex_ai)) },
        )
        if (provider.vertexAI) {
            switchItem(
                checked = provider.useServiceAccount,
                onCheckedChange = { onEdit(provider.copy(useServiceAccount = it)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_use_service_account)) },
            )
        } else {
            switchItem(
                checked = provider.useInteractionsApi,
                onCheckedChange = { onEdit(provider.copy(useInteractionsApi = it)) },
                headlineContent = { Text("Interactions API (Beta)") },
            )
        }
    }

    if (useServiceAccount) {
        CardGroup {
            item(
                onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_import_service_account_json)) },
                trailingContent = { Icon(HugeIcons.FileImport, contentDescription = null) },
            )
            textFieldItem(
                label = R.string.setting_provider_page_service_account_email,
                value = provider.serviceAccountEmail,
                onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
            )
            textFieldItem(
                label = R.string.setting_provider_page_private_key,
                value = provider.privateKey,
                onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
                secret = true,
                minLines = 3,
                maxLines = 6,
                monospace = true,
            )
            textFieldItem(
                label = R.string.setting_provider_page_location,
                value = provider.location,
                onValueChange = { onEdit(provider.copy(location = it.trim())) },
            )
            textFieldItem(
                label = R.string.setting_provider_page_project_id,
                value = provider.projectId,
                onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
            )
        }
    }
}
