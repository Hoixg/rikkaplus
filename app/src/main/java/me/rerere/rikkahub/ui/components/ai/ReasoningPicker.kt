package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.ui.components.ToggleSurface
import me.rerere.ui.icons.ReasoningHigh
import me.rerere.ui.icons.ReasoningLow
import me.rerere.ui.icons.ReasoningMedium
import kotlin.math.roundToInt

private val levels = ReasoningLevel.entries
private val levelCount = levels.size

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningLevel = reasoningLevel,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningLevel = onUpdateReasoningLevel
        )
    }

    ToggleSurface(
        checked = reasoningLevel.isEnabled,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                ReasoningIcon(reasoningLevel)
            }
            if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
        }
    }
}

@Composable
fun ReasoningPicker(
    reasoningLevel: ReasoningLevel,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    val sliderState = remember {
        SliderState(
            value = currentIndex.toFloat(),
            trackRange = 0f..(levelCount - 1).toFloat(),
            // Keep the thumb continuous while dragging; snap to the nearest level on release.
            steps = 0,
        )
    }
    var targetSliderValue by remember { mutableStateOf(currentIndex.toFloat()) }
    val animatedSliderValue by animateFloatAsState(
        targetValue = targetSliderValue,
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 160f),
        label = "reasoning_thumb_follow",
    )
    val animatedFill by animateFloatAsState(
        targetValue = animatedSliderValue,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 105f),
        label = "reasoning_track_follow",
    )
    val displayedLevel = levels[animatedSliderValue.roundToInt().coerceIn(0, levelCount - 1)]
    val levelLabels = levels.map { it.label() }
    val levelLabelStyle = MaterialTheme.typography.titleSmall
    val textMeasurer = rememberTextMeasurer()
    val levelLabelWidthPx = remember(levelLabels, levelLabelStyle, textMeasurer) {
        levelLabels.maxOf { label ->
            textMeasurer.measure(AnnotatedString(label), style = levelLabelStyle).size.width
        }
    }
    val levelLabelWidth = with(LocalDensity.current) { levelLabelWidthPx.toDp() }

    SideEffect {
        sliderState.value = animatedSliderValue
    }

    LaunchedEffect(currentIndex) {
        targetSliderValue = currentIndex.toFloat()
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.reasoning_picker_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.width(26.dp + levelLabelWidth),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AnimatedContent(
                        targetState = displayedLevel,
                        modifier = Modifier.size(20.dp),
                        contentAlignment = Alignment.Center,
                        transitionSpec = {
                            (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.9f)) togetherWith
                                (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.95f))
                        },
                        label = "reasoning_icon_switch",
                    ) {
                        level ->
                        val iconColor = if (level.isEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                        ReasoningIcon(level = level, modifier = Modifier.size(20.dp), tint = iconColor)
                    }
                    AnimatedContent(
                        targetState = displayedLevel,
                        modifier = Modifier.width(levelLabelWidth),
                        contentAlignment = Alignment.CenterStart,
                        transitionSpec = {
                            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            (
                                fadeIn(tween(150)) +
                                    slideInVertically(tween(150, easing = FastOutSlowInEasing)) { direction * it / 6 }
                                ).togetherWith(
                                fadeOut(tween(100)) +
                                    slideOutVertically(tween(100)) { -direction * it / 6 }
                            )
                        },
                        label = "reasoning_level_switch",
                    ) { level ->
                        val labelColor = if (level.isEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            text = level.label(),
                            modifier = Modifier.fillMaxWidth(),
                            style = levelLabelStyle,
                            color = labelColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Slider(
                state = sliderState,
                onValueChange = { value ->
                    targetSliderValue = value
                },
                onValueChangeFinished = {
                    val snappedIndex = targetSliderValue.roundToInt().coerceIn(0, levelCount - 1)
                    targetSliderValue = snappedIndex.toFloat()
                    onUpdateReasoningLevel(levels[snappedIndex])
                },
                modifier = Modifier.fillMaxWidth(),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onPrimary)
                        )
                    }
                },
                track = {
                    ElasticReasoningTrack(
                        value = sliderState.value,
                        fillValue = animatedFill,
                    )
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                levels.forEachIndexed { index, level ->
                    Text(
                        text = levelLabels[index],
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (level == displayedLevel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = stringResource(R.string.reasoning_picker_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ElasticReasoningTrack(value: Float, fillValue: Float) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
    val inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
        val left = 0f
        val width = size.width
        val centerY = size.height / 2f
        val halfHeight = 9.dp.toPx()
        val tickRadius = 2.dp.toPx()
        // The first and last tick centers coincide with the thumb's travel limits.
        // Extend the capsule slightly past those centers so the end ticks sit fully on it.
        val trackLeft = left - tickRadius
        val trackRight = left + width + tickRadius
        val corner = CornerRadius(halfHeight)
        val currentX = left + width * (value / (levelCount - 1)).coerceIn(0f, 1f)
        val followingValue = fillValue.coerceIn(value - 0.35f, value + 0.35f)
        val fillX = left + width * (followingValue / (levelCount - 1)).coerceIn(0f, 1f)

        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(trackLeft, centerY - halfHeight),
            size = Size(trackRight - trackLeft, halfHeight * 2f),
            cornerRadius = corner,
        )
        val activeTrackRight = (fillX + tickRadius).coerceAtMost(trackRight)
        if (activeTrackRight > trackLeft) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(trackLeft, centerY - halfHeight),
                size = Size(activeTrackRight - trackLeft, halfHeight * 2f),
                cornerRadius = corner,
            )
        }
        if (currentX > fillX + 1f) {
            val neckStart = (fillX - halfHeight).coerceAtLeast(left)
            val gap = currentX - neckStart
            val neck = Path().apply {
                moveTo(neckStart, centerY - halfHeight)
                cubicTo(
                    neckStart + gap * 0.35f, centerY - halfHeight,
                    currentX - gap * 0.2f, centerY - halfHeight * 0.6f,
                    currentX, centerY - halfHeight * 0.6f,
                )
                lineTo(currentX, centerY + halfHeight * 0.6f)
                cubicTo(
                    currentX - gap * 0.2f, centerY + halfHeight * 0.6f,
                    neckStart + gap * 0.35f, centerY + halfHeight,
                    neckStart, centerY + halfHeight,
                )
                close()
            }
            drawPath(neck, activeColor)
        }
        repeat(levelCount) { index ->
            val tickX = left + width * index / (levelCount - 1)
            drawCircle(
                color = if (tickX <= fillX) activeTickColor else inactiveTickColor,
                radius = tickRadius,
                center = Offset(tickX, centerY),
            )
        }
    }
}

@Composable
private fun ReasoningIcon(
    level: ReasoningLevel,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val icon = when (level) {
        ReasoningLevel.OFF -> HugeIcons.Idea
        ReasoningLevel.AUTO -> HugeIcons.Idea01
        ReasoningLevel.LOW -> ReasoningLow
        ReasoningLevel.MEDIUM -> ReasoningMedium
        ReasoningLevel.HIGH -> ReasoningHigh
        ReasoningLevel.XHIGH -> ReasoningHigh
        ReasoningLevel.MAX -> ReasoningHigh
    }
    Icon(icon, contentDescription = null, modifier = modifier, tint = tint ?: LocalContentColor.current)
}

@Composable
private fun ReasoningLevel.label(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
}

@Composable
@Preview(showBackground = true)
private fun ReasoningPickerPreview() {
    MaterialTheme {
        var level by remember { mutableStateOf(ReasoningLevel.AUTO) }
        ReasoningPicker(
            reasoningLevel = level,
            onUpdateReasoningLevel = { level = it }
        )
    }
}
