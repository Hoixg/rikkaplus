package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import me.rerere.rikkahub.utils.AUTO_COMPACTION_THRESHOLD_STEP_PERCENT
import me.rerere.rikkahub.utils.MAX_AUTO_COMPACTION_THRESHOLD_PERCENT
import me.rerere.rikkahub.utils.MIN_AUTO_COMPACTION_THRESHOLD_PERCENT
import me.rerere.rikkahub.utils.formatContextLength
import me.rerere.rikkahub.utils.normalizeAutoCompactionThresholdPercent
import me.rerere.rikkahub.utils.parseContextLengthInput
import me.rerere.rikkahub.utils.plus
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.ScanText
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ModelListState
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

private enum class ModelSettingTab(
    override val icon: ImageVector,
    @param:StringRes override val label: Int,
) : PagerTab {
    MODEL(HugeIcons.AiBrain01, R.string.setting_model_page_tab_model),
    PROMPT(HugeIcons.AiEditing, R.string.setting_model_page_tab_prompt),
}

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { ModelSettingTab.entries.size }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = ModelSettingTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            // 导航条的位置留在列表的内边距里，内容可以滚到它下面
            val contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (ModelSettingTab.entries[page]) {
                    ModelSettingTab.MODEL -> ModelSettingsPage(
                        settings = settings,
                        vm = vm,
                        contentPadding = contentPadding,
                    )

                    ModelSettingTab.PROMPT -> PromptSettingsPage(
                        settings = settings,
                        vm = vm,
                        contentPadding = contentPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    // CardGroup 里放不了弹窗，各项的选择状态先在这里建好，弹窗统一放在列表后面
    val chatModel = rememberModelListState(
        modelId = settings.chatModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val fastModel = rememberModelListState(
        modelId = settings.fastModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val translateModel = rememberModelListState(
        modelId = settings.translateModeId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val ocrModel = rememberModelListState(
        modelId = settings.ocrModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    var thresholdSliderValue by remember(settings.autoCompactionThresholdPercent) {
        mutableFloatStateOf(settings.autoCompactionThresholdPercent.toFloat())
    }
    var isThresholdSliderDragging by remember { mutableStateOf(false) }
    val settledThresholdSliderValue by animateFloatAsState(
        targetValue = thresholdSliderValue,
        animationSpec = if (isThresholdSliderDragging) {
            snap()
        } else {
            spring(dampingRatio = 0.82f, stiffness = 700f)
        },
        label = "auto_compaction_threshold_slider",
    )
    val visibleThresholdPercent = normalizeAutoCompactionThresholdPercent(
        (if (isThresholdSliderDragging) thresholdSliderValue else settledThresholdSliderValue).roundToInt()
    )
    val activeThresholdTickColor = MaterialTheme.colorScheme.surface
    val inactiveThresholdTickColor = MaterialTheme.colorScheme.onSurfaceVariant
    var showTokenLimitDialog by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            CardGroup {
                modelItem(
                    icon = HugeIcons.BubbleChat,
                    title = R.string.setting_model_page_chat_model,
                    description = R.string.setting_model_page_chat_model_desc,
                    state = chatModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.tertiaryContainer
            val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            // 标题和聊天建议都由快速模型生成，相关的选项跟它放在一组
            CardGroup {
                modelItem(
                    icon = HugeIcons.Zap,
                    title = R.string.setting_model_page_fast_model,
                    description = R.string.setting_model_page_fast_model_desc,
                    state = fastModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                    trailingContent = {
                        ReasoningButton(
                            reasoningLevel = settings.fastModelReasoningLevel,
                            onUpdateReasoningLevel = {
                                vm.updateSettings { latest -> latest.copy(fastModelReasoningLevel = it) }
                            },
                        )
                    },
                )
                switchItem(
                    checked = settings.enableAutoTitle,
                    onCheckedChange = {
                        vm.updateSettings { latest -> latest.copy(enableAutoTitle = it) }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_enable_auto_title)) },
                )
                switchItem(
                    checked = settings.enableSuggestion,
                    onCheckedChange = {
                        vm.updateSettings { latest -> latest.copy(enableSuggestion = it) }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                )
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.secondaryContainer
            val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            CardGroup {
                modelItem(
                    icon = HugeIcons.Translate,
                    title = R.string.setting_model_page_translate_model,
                    description = R.string.setting_model_page_translate_model_desc,
                    state = translateModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
                modelItem(
                    icon = HugeIcons.ScanText,
                    title = R.string.setting_model_page_ocr_model,
                    description = R.string.setting_model_page_ocr_model_desc,
                    state = ocrModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
            }
        }
        item {
            CardGroup {
                item(
                    headlineContent = {
                        Text(stringResource(R.string.setting_model_page_enable_auto_compaction))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_model_page_enable_auto_compaction_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.enableAutoCompaction,
                            onCheckedChange = {
                                vm.updateSettings { latest -> latest.copy(enableAutoCompaction = it) }
                            },
                        )
                    },
                )
                if (settings.enableAutoCompaction) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_model_page_auto_compaction_threshold))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.setting_model_page_auto_compaction_threshold_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Slider(
                                        value = if (isThresholdSliderDragging) {
                                            thresholdSliderValue
                                        } else {
                                            settledThresholdSliderValue
                                        },
                                        onValueChange = { value ->
                                            isThresholdSliderDragging = true
                                            thresholdSliderValue = value
                                        },
                                        onValueChangeFinished = {
                                            val snappedPercent = normalizeAutoCompactionThresholdPercent(
                                                thresholdSliderValue.roundToInt()
                                            )
                                            isThresholdSliderDragging = false
                                            thresholdSliderValue = snappedPercent.toFloat()
                                            if (snappedPercent != settings.autoCompactionThresholdPercent) {
                                                vm.updateSettings(
                                                    { latest -> latest.copy(autoCompactionThresholdPercent = snappedPercent) }
                                                )
                                            }
                                        },
                                        valueRange = MIN_AUTO_COMPACTION_THRESHOLD_PERCENT.toFloat()..
                                            MAX_AUTO_COMPACTION_THRESHOLD_PERCENT.toFloat(),
                                        steps = 0,
                                        modifier = Modifier
                                            .weight(1f)
                                            .drawWithContent {
                                                drawContent()
                                                val horizontalInset = 10.dp.toPx()
                                                val trackWidth = size.width - horizontalInset * 2
                                                val range = MAX_AUTO_COMPACTION_THRESHOLD_PERCENT -
                                                    MIN_AUTO_COMPACTION_THRESHOLD_PERCENT
                                                val sliderValue = if (isThresholdSliderDragging) {
                                                    thresholdSliderValue
                                                } else {
                                                    settledThresholdSliderValue
                                                }
                                                val thumbFraction = (
                                                    (sliderValue - MIN_AUTO_COMPACTION_THRESHOLD_PERCENT) / range
                                                    ).coerceIn(0f, 1f)
                                                val thumbX = horizontalInset + trackWidth * thumbFraction
                                                val thumbExclusionRadius = 11.dp.toPx()
                                                for (step in 1 until range / AUTO_COMPACTION_THRESHOLD_STEP_PERCENT) {
                                                    val tickX = horizontalInset + trackWidth *
                                                        step / (range / AUTO_COMPACTION_THRESHOLD_STEP_PERCENT)
                                                    if (kotlin.math.abs(tickX - thumbX) > thumbExclusionRadius) {
                                                            drawCircle(
                                                            color = if (tickX < thumbX) {
                                                                activeThresholdTickColor
                                                            } else {
                                                                inactiveThresholdTickColor
                                                            },
                                                            radius = 2.dp.toPx(),
                                                            center = Offset(tickX, size.height / 2),
                                                        )
                                                    }
                                                }
                                            },
                                    )
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.padding(start = 8.dp),
                                    ) {
                                        Text(
                                            text = "$visibleThresholdPercent%",
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    listOf(50, 65, 80, 95).forEach { value ->
                                        Text(
                                            text = "$value%",
                                            color = if (value == visibleThresholdPercent) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        if (settings.enableAutoCompaction) {
            item {
                CardGroup {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_model_page_auto_compaction_token_limit))
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_model_page_auto_compaction_token_limit_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            OutlinedButton(onClick = { showTokenLimitDialog = true }) {
                                Text(
                                    settings.autoCompactionTokenLimit?.let(::formatContextLength)
                                        ?: stringResource(R.string.setting_model_page_auto_compaction_token_limit_unlimited)
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (showTokenLimitDialog) {
        AutoCompactionTokenLimitDialog(
            initialValue = settings.autoCompactionTokenLimit,
            onConfirm = { value ->
                vm.updateSettings { latest -> latest.copy(autoCompactionTokenLimit = value) }
                showTokenLimitDialog = false
            },
            onDismiss = { showTokenLimitDialog = false },
        )
    }

    ModelListSheet(state = chatModel) {
        vm.updateSettings { latest -> latest.copy(chatModelId = it.id) }
    }
    ModelListSheet(state = fastModel) {
        vm.updateSettings { latest -> latest.copy(fastModelId = it.id) }
    }
    ModelListSheet(state = translateModel) {
        vm.updateSettings { latest -> latest.copy(translateModeId = it.id) }
    }
    ModelListSheet(state = ocrModel) {
        vm.updateSettings { latest -> latest.copy(ocrModelId = it.id) }
    }
}

@Composable
private fun AutoCompactionTokenLimitDialog(
    initialValue: Int?,
    onConfirm: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(formatContextLength(initialValue)) }
    var hasError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_model_page_auto_compaction_token_limit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.setting_model_page_auto_compaction_token_limit_dialog_desc))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        hasError = false
                    },
                    placeholder = { Text(stringResource(R.string.setting_model_page_auto_compaction_token_limit_placeholder)) },
                    singleLine = true,
                    isError = hasError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasError) {
                    Text(
                        text = stringResource(R.string.setting_model_page_auto_compaction_token_limit_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = if (text.isBlank()) null else parseContextLengthInput(text)
                    if (text.isNotBlank() && parsed == null) {
                        hasError = true
                    } else {
                        onConfirm(parsed)
                    }
                },
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

// 一项默认模型：图标、用途说明，下面是显示当前模型的选择按钮
private fun CardGroupScope.modelItem(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes description: Int,
    state: ModelListState,
    iconContainerColor: Color,
    iconContentColor: Color,
) = formItem(
    headlineContent = { Text(stringResource(title)) },
    supportingContent = { Text(stringResource(description)) },
    leadingContent = {
        CardGroupIcon(icon, containerColor = iconContainerColor, contentColor = iconContentColor)
    },
) {
    ModelField(
        model = state.currentModel,
        onClick = { state.open() },
    )
}
