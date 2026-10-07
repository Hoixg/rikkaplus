package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.SubagentTask
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.mirrorForRtl
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Represents different types of items in the conversation list
 */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String,
        val sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ) : ConversationListItem()
    data object PinnedHeader : ConversationListItem()
    data class Item(
        val conversation: Conversation
    ) : ConversationListItem()
}

@Composable
fun ColumnScope.ConversationList(
    current: Conversation,
    conversations: LazyPagingItems<ConversationListItem>,
    conversationJobs: Collection<Uuid>,
    listState: LazyListState,
    subagentTasks: Map<Uuid, SubagentTask> = emptyMap(),
    runningSessionIds: Collection<Uuid> = emptyList(),
    expandedSubagentIds: Set<Uuid> = emptySet(),
    onToggleSubagentExpand: (Uuid) -> Unit = {},
    modifier: Modifier = Modifier,
    onClick: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRequestDeleteChild: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {}
) {
    var hasScrolledToCurrent by remember(current.id) { mutableStateOf(false) }

    LaunchedEffect(current.id, conversations.itemCount, hasScrolledToCurrent) {
        if (hasScrolledToCurrent) return@LaunchedEffect
        val currentIndex = conversations.itemSnapshotList.items.indexOfFirst {
            (it as? ConversationListItem.Item)?.conversation?.id == (current.parentConversationId ?: current.id)
        }
        if (currentIndex >= 0) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
            if (!isVisible) {
                listState.scrollToItem(currentIndex)
            }
            hasScrolledToCurrent = true
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (conversations.itemCount == 0) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = stringResource(id = R.string.chat_page_no_conversations),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(
            count = conversations.itemCount,
            key = conversations.itemKey { item ->
                when (item) {
                    // key 带上排序方式，避免切换排序后列表锚定到另一种排序下的同日期分组而发生跳动
                    is ConversationListItem.DateHeader -> "date_${item.sortOrder}_${item.date}"
                    is ConversationListItem.PinnedHeader -> "pinned_header"
                    is ConversationListItem.Item -> item.conversation.id.toString()
                }
            }
        ) { index ->
            when (val item = conversations[index]) {
                is ConversationListItem.DateHeader -> {
                    DateHeaderItem(
                        label = item.label,
                        modifier = Modifier.animateItem()
                    )
                }

                is ConversationListItem.PinnedHeader -> {
                    PinnedHeader(
                        modifier = Modifier.animateItem()
                    )
                }

                is ConversationListItem.Item -> {
                    ConversationItem(
                        conversation = item.conversation,
                        selected = item.conversation.id == current.id || item.conversation.id == current.parentConversationId,
                        loading = item.conversation.id in conversationJobs,
                        currentId = current.id,
                        subagentTasks = subagentTasks,
                        runningSessionIds = runningSessionIds,
                        subagentExpanded = item.conversation.id in expandedSubagentIds,
                        onToggleSubagentExpand = { onToggleSubagentExpand(item.conversation.id) },
                        onClick = onClick,
                        onDelete = onDelete,
                        onRequestDeleteChild = onRequestDeleteChild,
                        onRegenerateTitle = onRegenerateTitle,
                        onPin = onPin,
                        onMoveToAssistant = onMoveToAssistant,
                        onMoveToFolder = onMoveToFolder,
                        modifier = Modifier.animateItem()
                    )
                }

                null -> {
                    // Placeholder for loading state
                }
            }
        }
    }
}

@Composable
private fun DateHeaderItem(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PinnedHeader(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = HugeIcons.Pin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pinned_chats),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    loading: Boolean,
    currentId: Uuid,
    subagentTasks: Map<Uuid, SubagentTask>,
    runningSessionIds: Collection<Uuid>,
    subagentExpanded: Boolean,
    onToggleSubagentExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (Conversation) -> Unit = {},
    onRequestDeleteChild: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    onClick: (Conversation) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
    } else {
        Color.Transparent
    }
    var showDropdownMenu by remember {
        mutableStateOf(false)
    }
    val children = rememberSubagentChildren(conversation.id)
    SubagentConversationContent(
        parentId = conversation.id,
        currentId = currentId,
        children = children,
        tasks = subagentTasks,
        sessionJobIds = runningSessionIds,
        expanded = subagentExpanded,
        modifier = modifier,
        onToggle = onToggleSubagentExpand,
        onChildClick = onClick,
        onRequestDeleteChild = onRequestDeleteChild,
        parentRow = { expanded, hasChildren, onToggle ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50f))
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = { onClick(conversation) },
                        onLongClick = {
                            // Also clear chat input focus when the drawer is permanently visible.
                            focusManager.clearFocus(force = true)
                            showDropdownMenu = true
                        }
                    )
                    .background(backgroundColor),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 置顶图标
                    AnimatedVisibility(conversation.isPinned) {
                        Icon(
                            imageVector = HugeIcons.Pin,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    AnimatedVisibility(loading) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.extendColors.green6)
                                .size(4.dp)
                                .semantics {
                                    contentDescription = "Loading"
                                }
                        )
                    }
                    SubagentExpandIcon(
                        expanded = expanded,
                        hasChildren = hasChildren,
                        onToggle = onToggle,
                    )
                    DropdownMenu(
                        expanded = showDropdownMenu,
                        onDismissRequest = { showDropdownMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (conversation.isPinned) stringResource(R.string.unpin_chat) else stringResource(R.string.pin_chat)
                                )
                            },
                            onClick = {
                                onPin(conversation)
                                showDropdownMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                                    null
                                )
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(stringResource(id = R.string.chat_page_regenerate_title))
                            },
                            onClick = {
                                onRegenerateTitle(conversation)
                                showDropdownMenu = false
                            },
                            leadingIcon = {
                                Icon(HugeIcons.Refresh01, null)
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.chat_page_move_to_assistant))
                            },
                            onClick = {
                                onMoveToAssistant(conversation)
                                showDropdownMenu = false
                            },
                            leadingIcon = {
                                Icon(HugeIcons.Forward02, null, modifier = Modifier.mirrorForRtl())
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.chat_page_move_to_folder))
                            },
                            onClick = {
                                onMoveToFolder(conversation)
                                showDropdownMenu = false
                            },
                            leadingIcon = {
                                Icon(HugeIcons.Folder01, null)
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(stringResource(id = R.string.chat_page_delete))
                            },
                            onClick = {
                                onDelete(conversation)
                                showDropdownMenu = false
                            },
                            leadingIcon = {
                                Icon(HugeIcons.Delete01, null)
                            }
                        )
                    }
                }
            }
        },
    )
}
