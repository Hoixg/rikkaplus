package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.InboxState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.components.ui.draggingScale
import me.rerere.rikkahub.ui.hooks.ChatInputState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun MessageQueuePanel(
    state: InboxState,
    onSend: (Uuid) -> Unit,
    onRemove: (Uuid) -> Unit,
    onCancel: (Uuid) -> Unit,
    canRestoreDraft: Boolean,
    onMove: (Uuid, Uuid) -> Unit,
    onReorderingChange: (Boolean) -> Unit,
    onBeginEdit: (Uuid) -> QueuedMessage?,
    onFinishEdit: (Uuid, List<UIMessagePart>?) -> Unit,
    onResume: () -> Unit,
) {
    var editing by remember { mutableStateOf<QueuedMessage?>(null) }
    var draggingId by remember { mutableStateOf<Uuid?>(null) }
    val reorderingChange by rememberUpdatedState(onReorderingChange)
    val move by rememberUpdatedState(onMove)
    val messages by rememberUpdatedState(state.messages)
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromMessage = messages.find { it.id.toString() == from.key }
        val toMessage = messages.find { it.id.toString() == to.key }
        if (fromMessage != null && toMessage != null) move(fromMessage.id, toMessage.id)
    }
    val haptic = LocalHapticFeedback.current
    val actionsSpatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val actionsEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val actionsSizeSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    DisposableEffect(Unit) {
        onDispose {
            if (draggingId != null) {
                draggingId = null
                reorderingChange(false)
            }
        }
    }
    if (state.items.isNotEmpty()) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_message_queue"),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            if (state.paused) R.string.chat_page_queue_paused_count
                            else R.string.chat_page_queue_pending_count,
                            state.items.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                    )
                    if (state.paused && !state.reordering) {
                        TextButton(onClick = onResume) { Text(stringResource(R.string.chat_page_queue_resume)) }
                    }
                }
                LazyColumn(state = lazyListState, modifier = Modifier.heightIn(max = 180.dp)) {
                    itemsIndexed(
                        state.messages,
                        key = { _, message -> message.id.toString() }) { index, message ->
                        var showActions by remember(message.id) { mutableStateOf(false) }
                        val actionsRotation by animateFloatAsState(
                            targetValue = if (showActions) 90f else 0f,
                            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                            label = "queueActionsRotation",
                        )
                        val interactionSource = remember(message.id) { MutableInteractionSource() }
                        DisposableEffect(message.id) {
                            onDispose {
                                if (draggingId == message.id) {
                                    draggingId = null
                                    reorderingChange(false)
                                }
                            }
                        }
                        ReorderableItem(
                            state = reorderableState,
                            key = message.id.toString(),
                            enabled = !message.isEditing,
                        ) { isDragging ->
                            Row(
                                modifier = Modifier
                                    .draggingScale(isDragging)
                                    .background(
                                        color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
                                        else MaterialTheme.colorScheme.surfaceContainer,
                                        shape = MaterialTheme.shapes.medium,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val attachmentLabels = listOf(
                                    stringResource(R.string.chat_page_queue_image),
                                    stringResource(R.string.chat_page_queue_file),
                                    stringResource(R.string.chat_page_queue_audio),
                                    stringResource(R.string.chat_page_queue_video),
                                )
                                Text(
                                    text = "${index + 1}. " + message.parts.joinToString(" ") {
                                        when (it) {
                                            is UIMessagePart.Text -> it.text
                                            is UIMessagePart.Image -> attachmentLabels[0]
                                            is UIMessagePart.Document -> attachmentLabels[1]
                                            is UIMessagePart.Audio -> attachmentLabels[2]
                                            is UIMessagePart.Video -> attachmentLabels[3]
                                            else -> ""
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                AnimatedContent(
                                    targetState = showActions,
                                    contentAlignment = Alignment.CenterEnd,
                                    transitionSpec = {
                                        val direction = if (targetState) 1 else -1
                                        (fadeIn(actionsEffectsSpec) + slideInHorizontally(actionsSpatialSpec) { it * direction / 6 }) togetherWith
                                            (fadeOut(actionsEffectsSpec) + slideOutHorizontally(actionsSpatialSpec) { -it * direction / 6 }) using
                                            SizeTransform(sizeAnimationSpec = { _, _ -> actionsSizeSpec })
                                    },
                                    label = "queueActions",
                                ) { expanded ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (expanded) {
                                            TextButton(
                                                enabled = showActions && !message.isEditing && draggingId == null,
                                                onClick = { editing = onBeginEdit(message.id) },
                                            ) {
                                                Text(stringResource(
                                                    if (message.isEditing) R.string.chat_page_queue_editing else R.string.edit,
                                                ))
                                            }
                                            TextButton(
                                                enabled = showActions && !message.isEditing && draggingId == null,
                                                onClick = { onRemove(message.id) },
                                            ) { Text(stringResource(R.string.delete)) }
                                            TextButton(
                                                enabled = showActions && !message.isEditing && draggingId == null && canRestoreDraft,
                                                onClick = { onCancel(message.id) },
                                                modifier = Modifier.testTag("chat_queue_cancel_${message.id}"),
                                            ) { Text(stringResource(R.string.cancel)) }
                                        } else {
                                            FilledTonalButton(
                                                enabled = !showActions && !message.isEditing && draggingId == null,
                                                onClick = { onSend(message.id) },
                                            ) { Text(stringResource(R.string.send)) }
                                        }
                                    }
                                }
                                IconToggleButton(
                                    checked = showActions,
                                    onCheckedChange = { if (draggingId == null) showActions = it },
                                    interactionSource = interactionSource,
                                    modifier = Modifier.longPressDraggableHandle(
                                        enabled = !message.isEditing && state.messages.size > 1,
                                        interactionSource = interactionSource,
                                        onDragStarted = {
                                            draggingId = message.id
                                            reorderingChange(true)
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            if (draggingId == message.id) {
                                                draggingId = null
                                                reorderingChange(false)
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            }
                                        },
                                    ),
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MoreVertical,
                                        contentDescription = stringResource(
                                            if (state.messages.size > 1) R.string.chat_page_queue_more_and_reorder
                                            else R.string.more_options,
                                        ),
                                        modifier = Modifier.size(20.dp).rotate(actionsRotation),
                                    )
                                }
                            }
                        }
                    }
                    items(state.notices, key = { it.id.toString() }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Notification01,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = item.notice.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { onRemove(item.id) }) {
                                Text(stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { message ->
        val input = remember(message.id) {
            ChatInputState().apply {
                editingMessage = message.id
                setContents(message.parts)
            }
        }
        val finishEdit by rememberUpdatedState(onFinishEdit)
        // Leaving the page or dismissing the dialog must release the queue item.
        DisposableEffect(message.id) {
            onDispose { finishEdit(message.id, null) }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.chat_page_queue_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (input.messageContent.isNotEmpty()) MediaFileInputRow(input)
                    TextField(
                        state = input.textContent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_queue_edit_text"),
                        lineLimits = TextFieldLineLimits.MultiLine(
                            minHeightInLines = 3,
                            maxHeightInLines = 8
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !input.isEmpty(),
                    onClick = {
                        onFinishEdit(message.id, input.getContents())
                        editing = null
                    },
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
