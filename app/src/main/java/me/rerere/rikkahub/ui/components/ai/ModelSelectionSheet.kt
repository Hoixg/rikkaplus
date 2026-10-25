package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.rikkahub.R
import kotlin.math.roundToInt

/** 整个面板禁止接管列表滚动，仅顶部按钮能驱动下拉位移。 */
@Composable
internal fun ModelSelectionSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(onDismiss: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val dragState = remember {
        AnchoredDraggableState(
            initialValue = SheetValue.Expanded,
            anchors = DraggableAnchors { SheetValue.Expanded at 0f },
        )
    }
    var sheetHeight by remember { mutableIntStateOf(0) }
    val dismissThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = dragState,
        positionalThreshold = { distance -> minOf(dismissThreshold, distance / 2f) },
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val dragProgress = (dragState.requireOffset() / sheetHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
    val scrimColor = BottomSheetDefaults.ScrimColor

    LaunchedEffect(dragState.settledValue) {
        if (dragState.settledValue == SheetValue.Hidden) onDismissRequest()
    }

    val dismiss: () -> Unit = remember(scope, sheetState, onDismissRequest) {
        {
            scope.launch {
                sheetState.hide()
                onDismissRequest()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
        scrimColor = scrimColor.copy(alpha = scrimColor.alpha * (1f - dragProgress)),
        modifier = Modifier
            .offset { IntOffset(0, dragState.requireOffset().roundToInt()) }
            .onSizeChanged { size ->
                sheetHeight = size.height
                dragState.updateAnchors(
                    DraggableAnchors {
                        SheetValue.Expanded at 0f
                        if (size.height > 0) SheetValue.Hidden at size.height.toFloat()
                    }
                )
            },
    ) {
        Row(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) {
            FilledTonalButton(
                onClick = dismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .anchoredDraggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        flingBehavior = flingBehavior,
                    ),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = ButtonDefaults.IconSpacing)
                        .size(ButtonDefaults.IconSize),
                )
                Text(stringResource(R.string.model_list_close))
            }
        }
        content(dismiss)
    }
}
