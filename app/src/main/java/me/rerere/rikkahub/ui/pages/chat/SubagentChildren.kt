package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.SubagentTask
import me.rerere.rikkahub.service.SubagentTaskStatus
import me.rerere.rikkahub.ui.theme.extendColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun rememberSubagentChildren(parentId: Uuid): List<Conversation> {
    val repository: ConversationRepository = koinInject()
    val children by remember(parentId) {
        repository.getSubconversationsOfParent(parentId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    return children
}

/** Parent row and its animated child rows remain one conversation-list item. */
@Composable
internal fun SubagentConversationContent(
    parentId: Uuid,
    currentId: Uuid,
    children: List<Conversation>,
    tasks: Map<Uuid, SubagentTask>,
    sessionJobIds: Collection<Uuid>,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onChildClick: (Conversation) -> Unit,
    onRequestDeleteChild: (Conversation) -> Unit,
    parentRow: @Composable (expanded: Boolean, hasChildren: Boolean, onToggle: () -> Unit) -> Unit,
) {
    LaunchedEffect(currentId, children.map { it.id }) {
        if (!expanded && children.any { it.id == currentId }) onToggle()
    }

    val runningIds = remember(tasks, sessionJobIds) {
        tasks.values.asSequence()
            .filter { it.status == SubagentTaskStatus.RUNNING }
            .map { it.taskId }
            .toSet() + sessionJobIds
    }

    Column(modifier = modifier) {
        parentRow(expanded, children.isNotEmpty(), onToggle)
        AnimatedVisibility(
            visible = expanded && children.isNotEmpty(),
            enter = expandVertically(tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(240, easing = FastOutSlowInEasing)) + fadeOut(tween(160)),
        ) {
            Column(Modifier.padding(start = 18.dp, bottom = 4.dp)) {
                children.forEach { child ->
                    SubagentRow(
                        conversation = child,
                        running = child.id in runningIds,
                        selected = child.id == currentId,
                        onClick = { onChildClick(child) },
                        onLongClick = { onRequestDeleteChild(child) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SubagentExpandIcon(
    expanded: Boolean,
    hasChildren: Boolean,
    onToggle: () -> Unit,
) {
    AnimatedVisibility(visible = hasChildren) {
        val rotation by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (expanded) 90f else 0f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "subagentChevron",
        )
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = "Subagents",
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .padding(1.dp)
                .graphicsLayer { rotationZ = rotation },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubagentRow(
    conversation: Conversation,
    running: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
    } else {
        Color.Transparent
    }
    val pulseAlpha: Float by if (running) {
        rememberInfiniteTransition(label = "subagentPulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "subagentPulseAlpha",
        )
    } else {
        remember { mutableStateOf(1f) }
    }
    val barColor = if (running) {
        MaterialTheme.extendColors.green6
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(background)
            .padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(end = 8.dp)
                .size(width = 2.5.dp, height = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(barColor.copy(alpha = barColor.alpha * pulseAlpha))
                .semantics { contentDescription = if (running) "Running" else "Finished" },
        )
        Text(
            text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
            style = MaterialTheme.typography.labelMedium,
            color = if (running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
