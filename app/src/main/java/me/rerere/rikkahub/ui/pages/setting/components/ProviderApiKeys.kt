@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ApiKeyEntry
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.apiKeyEntries
import me.rerere.ai.provider.displayLabel
import me.rerere.ai.provider.withApiKeyEntries
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun ProviderApiKeys(provider: ProviderSetting.ApiKey, onChange: (ProviderSetting) -> Unit) {
    val entries = provider.apiKeyEntries()
    val selectedId = provider.selectedApiKeyId ?: entries.firstOrNull()?.id
    var editing by remember(provider.id) { mutableStateOf(false) }
    var editingId by remember(provider.id) { mutableStateOf<String?>(null) }

    CardGroup {
        item(
            headlineContent = { Text("API 密钥") },
            supportingContent = { Text("已保存 ${entries.size} 个 · 点选切换请求密钥") },
            trailingContent = {
                IconButton(onClick = {
                    editingId = null
                    editing = true
                }) {
                    Icon(HugeIcons.Add01, contentDescription = "添加密钥")
                }
            },
        )
        entries.forEach { entry ->
            item(
                onClick = { onChange(provider.withApiKeyEntries(entries, selectedId = entry.id)) },
                leadingContent = {
                    RadioButton(selected = entry.id == selectedId, onClick = null)
                },
                headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardGroupValue(entry.displayLabel().substringAfterLast(" · "))
                        IconButton(onClick = {
                            editingId = entry.id
                            editing = true
                        }) {
                            Icon(HugeIcons.PencilEdit01, contentDescription = "编辑 ${entry.name}")
                        }
                    }
                },
            )
        }
    }

    if (editing) {
        ApiKeyEditSheet(
            entry = entries.find { it.id == editingId },
            entries = entries,
            onDismiss = { editing = false },
            onSave = { replacements ->
                val updated = if (editingId == null) {
                    entries + replacements
                } else {
                    entries.map {
                        if (it.id == editingId) replacements.single() else it
                    }
                }
                onChange(provider.withApiKeyEntries(updated))
                editing = false
            },
            onDelete = {
                onChange(provider.withApiKeyEntries(entries.filterNot { it.id == editingId }))
                editing = false
            },
        )
    }
}

@Composable
private fun ApiKeyEditSheet(
    entry: ApiKeyEntry?,
    entries: List<ApiKeyEntry>,
    onDismiss: () -> Unit,
    onSave: (List<ApiKeyEntry>) -> Unit,
    onDelete: () -> Unit,
) {
    var secret by remember { mutableStateOf(entry?.key.orEmpty()) }
    var name by remember { mutableStateOf(entry?.name.orEmpty()) }
    var multiplier by remember { mutableStateOf(entry?.multiplier?.toString() ?: "1") }
    var visible by remember { mutableStateOf(false) }
    val keys = secret.split(Regex("[\\s,]+")).filter(String::isNotBlank).distinct()
    val rate = multiplier.toFloatOrNull()
    val duplicate = keys.any { value -> entries.any { it.id != entry?.id && it.key == value } }
    val invalidKey = duplicate || (entry != null && keys.size > 1)
    val valid = keys.isNotEmpty() && !invalidKey && rate != null && rate.isFinite() && rate > 0f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        containerColor = CustomColors.topBarColors.containerColor,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(if (entry == null) "添加密钥" else "编辑密钥", style = MaterialTheme.typography.headlineSmall)
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CardGroup {
                    formItem {
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API Key") },
                            maxLines = 4,
                            isError = invalidKey,
                            visualTransformation = if (visible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { visible = !visible }) {
                                    Icon(
                                        if (visible) HugeIcons.ViewOff else HugeIcons.View,
                                        contentDescription = if (visible) "隐藏密钥" else "显示密钥",
                                    )
                                }
                            },
                            supportingText = {
                                Text(
                                    when {
                                        duplicate -> "该密钥已存在"
                                        entry != null && keys.size > 1 -> "编辑时只能填写一个密钥"
                                        entry == null -> "支持粘贴多个密钥，以换行、空格或逗号分隔"
                                        else -> "修改密钥内容后，模型绑定保持不变"
                                    }
                                )
                            },
                        )
                    }
                    formItem {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("名称（可选）") },
                            singleLine = true,
                        )
                    }
                    formItem {
                        OutlinedTextField(
                            value = multiplier,
                            onValueChange = { multiplier = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("倍率") },
                            supportingText = { Text("渠道计费倍率备注，不影响请求参数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = rate == null || !rate.isFinite() || rate <= 0f,
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = valid,
                    onClick = {
                        onSave(keys.mapIndexed { index, key ->
                            val label = name.trim().let {
                                if (keys.size > 1 && it.isNotBlank()) "$it ${index + 1}" else it
                            }
                            entry?.copy(key = key, name = label, multiplier = rate!!)
                                ?: ApiKeyEntry(key = key, name = label, multiplier = rate!!)
                        })
                    },
                ) { Text("保存") }
            }
        }
    }
}

@Composable
fun ModelApiKeySelector(model: Model, provider: ProviderSetting?, onSelected: (String?) -> Unit) {
    val effectiveProvider = model.providerOverwrite ?: provider
    if (effectiveProvider !is ProviderSetting.ApiKey) return
    if (effectiveProvider is ProviderSetting.Google &&
        effectiveProvider.vertexAI && effectiveProvider.useServiceAccount
    ) {
        return
    }
    val entries = effectiveProvider.apiKeyEntries()
    val selected = entries.find { it.id == model.apiKeyId }
    var expanded by remember { mutableStateOf(false) }
    CardGroup {
        item(
            onClick = { expanded = !expanded },
            headlineContent = { Text("模型请求密钥") },
            supportingContent = {
                Text(selected?.displayLabel() ?: if (model.apiKeyId == null) "跟随服务商" else "指定密钥已移除，将跟随服务商")
            },
            trailingContent = { Text(if (expanded) "收起" else "选择", color = MaterialTheme.colorScheme.primary) },
        )
        if (expanded) {
            item(
                onClick = {
                    onSelected(null)
                    expanded = false
                },
                leadingContent = { RadioButton(selected = model.apiKeyId == null, onClick = null) },
                headlineContent = { Text("跟随服务商") },
                supportingContent = { Text("使用服务商当前选中的密钥") },
            )
            entries.forEach { entry ->
                item(
                    onClick = {
                        onSelected(entry.id)
                        expanded = false
                    },
                    leadingContent = { RadioButton(selected = entry.id == model.apiKeyId, onClick = null) },
                    headlineContent = { Text(entry.displayLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}
