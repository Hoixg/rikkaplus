package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningLow
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningMedium
import kotlin.math.abs
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
                Icon(reasoningLevel.icon(), null)
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
            // Let the thumb follow the finger continuously and snap on release.
            steps = 0,
        )
    }
    val interactionSource = remember { MutableInteractionSource() }
    val hapticFeedback = LocalHapticFeedback.current
    var targetSliderValue by remember { mutableStateOf(currentIndex.toFloat()) }
    val animatedSliderValue by animateFloatAsState(
        targetValue = targetSliderValue,
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 160f),
        label = "reasoning_thumb_follow",
    )
    var motionImpulse by remember { mutableStateOf(0f) }
    var lastMotionNanos by remember { mutableStateOf(0L) }
    val animatedImpulse by animateFloatAsState(
        targetValue = motionImpulse,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 250f),
        label = "reasoning_thumb_elasticity",
    )
    val animatedFill by animateFloatAsState(
        targetValue = animatedSliderValue,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 105f),
        label = "reasoning_track_follow",
    )
    // 拖动过程中就跟随滑块预览，松手后才真正提交
    val previewLevel = levels[animatedSliderValue.roundToInt().coerceIn(0, levelCount - 1)]

    SideEffect {
        sliderState.value = animatedSliderValue
    }

    LaunchedEffect(currentIndex) {
        targetSliderValue = currentIndex.toFloat()
        motionImpulse = 0f
        lastMotionNanos = 0L
    }

    LaunchedEffect(lastMotionNanos) {
        if (lastMotionNanos != 0L) {
            delay(80)
            motionImpulse = 0f
        }
    }

    LaunchedEffect(hapticFeedback) {
        snapshotFlow { targetSliderValue.roundToInt() }
            .drop(1)
            .collect { hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 左侧标题与说明，右侧随等级变形的形状
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.reasoning_picker_title),
                        // 各语言标题长短差别很大，放不下两行时自动缩小字号
                        autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 36.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.displaySmallEmphasized.copy(
                            fontWeight = FontWeight.Black,
                            lineHeight = 1.2.em,
                            lineBreak = LineBreak.Heading,
                        ),
                    )
                    Text(
                        text = stringResource(R.string.reasoning_picker_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ReasoningLevelHero(level = previewLevel)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReasoningLevelLabel(level = previewLevel)
                Slider(
                    state = sliderState,
                    onValueChange = { value ->
                        val delta = value - targetSliderValue
                        if (abs(delta) > 0.0001f) {
                            val now = System.nanoTime()
                            val elapsed = if (lastMotionNanos == 0L) 0.016f
                            else ((now - lastMotionNanos) / 1_000_000_000f).coerceAtLeast(0.008f)
                            val direction = if (delta > 0f) 1f else -1f
                            val speed = abs(delta) / elapsed
                            motionImpulse = direction * (speed / (speed + 4f))
                            lastMotionNanos = now
                        }
                        targetSliderValue = value
                    },
                    onValueChangeFinished = {
                        val snappedIndex = targetSliderValue.roundToInt().coerceIn(0, levelCount - 1)
                        targetSliderValue = snappedIndex.toFloat()
                        motionImpulse = 0f
                        lastMotionNanos = 0L
                        onUpdateReasoningLevel(levels[snappedIndex])
                    },
                    modifier = Modifier.fillMaxWidth(),
                    interactionSource = interactionSource,
                    thumb = {
                        Box(Modifier.graphicsLayer {
                            val strength = abs(animatedImpulse).coerceAtMost(1f)
                            scaleX = 1f + 0.12f * strength
                            scaleY = 1f - 0.06f * strength
                        }) {
                            SliderDefaults.Thumb(
                                interactionSource = interactionSource,
                                isVertical = false,
                                thumbSize = DpSize(4.dp, 52.dp),
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
            }
        }
    }
}

@Composable
private fun ElasticReasoningTrack(value: Float, fillValue: Float) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
    val inactiveTickColor = activeColor.copy(alpha = 0.85f)
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val width = size.width
        val centerY = size.height / 2f
        val halfHeight = 20.dp.toPx()
        val tickRadius = 2.dp.toPx()
        val corner = CornerRadius(12.dp.toPx())
        val currentX = width * (value / (levelCount - 1)).coerceIn(0f, 1f)
        val followingValue = fillValue.coerceIn(value - 0.35f, value + 0.35f)
        val fillX = width * (followingValue / (levelCount - 1)).coerceIn(0f, 1f)

        drawRoundRect(
            color = inactiveColor,
            size = Size(width, size.height),
            cornerRadius = corner,
        )
        if (fillX > 0f) {
            drawRoundRect(
                color = activeColor,
                size = Size(fillX.coerceAtMost(width), size.height),
                cornerRadius = corner,
            )
        }
        if (currentX > fillX + 1f) {
            val neckStart = (fillX - halfHeight).coerceAtLeast(0f)
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
            val tickX = width * index / (levelCount - 1)
            drawCircle(
                color = if (tickX <= fillX) activeTickColor else inactiveTickColor,
                radius = tickRadius,
                center = Offset(tickX, centerY),
            )
        }
    }
}

// 等级越高形状越「激烈」，相邻等级之间用 Morph 连续过渡
@Composable
private fun ReasoningLevelHero(
    level: ReasoningLevel,
    modifier: Modifier = Modifier,
) {
    val morphs = remember { levels.zipWithNext { from, to -> Morph(from.shape(), to.shape()) } }
    val path = remember { Path() }
    val position by animateFloatAsState(
        targetValue = levels.indexOf(level).toFloat(),
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            !level.isEnabled -> MaterialTheme.colorScheme.surfaceContainerHighest
            level >= ReasoningLevel.HIGH -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !level.isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
            level >= ReasoningLevel.HIGH -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val iconSpatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val iconEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    Box(
        modifier = modifier
            .size(96.dp)
            .drawBehind {
                // 弹簧会过冲，position 可能略微越界
                val segment = position.toInt().coerceIn(0, morphs.lastIndex)
                morphs[segment].toPath(
                    progress = (position - segment).coerceIn(0f, 1f),
                    path = path,
                )
                withTransform({
                    rotate(position * 30f)
                    // MaterialShapes 是归一化到 1x1 的
                    scale(size.width, size.height, pivot = Offset.Zero)
                }) {
                    drawPath(path, containerColor)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = level.icon(),
            transitionSpec = {
                (fadeIn(iconEffectsSpec) + scaleIn(iconSpatialSpec, initialScale = 0.6f)) togetherWith
                    (fadeOut(iconEffectsSpec) + scaleOut(iconSpatialSpec, targetScale = 0.6f))
            },
        ) { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = contentColor,
            )
        }
    }
}

@Composable
private fun ReasoningLevelLabel(level: ReasoningLevel) {
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = level,
        transitionSpec = {
            // 调高时向上滚动，调低时向下滚动
            val direction = if (targetState > initialState) 1 else -1
            (slideInVertically(spatialSpec) { it * direction / 2 } + fadeIn(effectsSpec)) togetherWith
                (slideOutVertically(spatialSpec) { -it * direction / 2 } + fadeOut(effectsSpec)) using
                SizeTransform(clip = false)
        },
    ) {
        Text(
            text = it.label(),
            style = MaterialTheme.typography.headlineSmallEmphasized,
        )
    }
}

private fun ReasoningLevel.icon(): ImageVector = when (this) {
    ReasoningLevel.OFF -> HugeIcons.Idea
    ReasoningLevel.AUTO -> HugeIcons.Idea01
    ReasoningLevel.LOW -> ReasoningLow
    ReasoningLevel.MEDIUM -> ReasoningMedium
    ReasoningLevel.HIGH -> ReasoningHigh
    ReasoningLevel.XHIGH -> ReasoningHigh
    ReasoningLevel.MAX -> ReasoningHigh
}

private fun ReasoningLevel.shape(): RoundedPolygon = when (this) {
    ReasoningLevel.OFF -> MaterialShapes.Circle
    ReasoningLevel.AUTO -> MaterialShapes.Cookie4Sided
    ReasoningLevel.LOW -> MaterialShapes.Cookie6Sided
    ReasoningLevel.MEDIUM -> MaterialShapes.Cookie7Sided
    ReasoningLevel.HIGH -> MaterialShapes.Cookie9Sided
    ReasoningLevel.XHIGH -> MaterialShapes.Cookie12Sided
    ReasoningLevel.MAX -> MaterialShapes.SoftBurst
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
