package me.rerere.rikkahub.ui.subagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.SubagentTask
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.message.ChatMessageActionButtons
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.pages.assistant.detail.subagentControls
import me.rerere.rikkahub.ui.pages.chat.SubagentConversationContent
import me.rerere.rikkahub.ui.pages.chat.SubagentExpandIcon
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class SubagentUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun assistantToggleIsIsolatedAndRoleEntryIsGone() {
        val first = Assistant()
        val second = Assistant()
        assertFalse(first.enableSubagents)
        assertFalse(second.enableSubagents)
        var assistants by mutableStateOf(listOf(first, second))
        var selected by mutableStateOf(first.id)
        compose.setContent {
            MaterialTheme {
                CardGroup {
                    subagentControls(assistants.single { it.id == selected }, { updated ->
                        assistants = assistants.map { if (it.id == updated.id) updated else it }
                    })
                }
            }
        }
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { selected = second.id }
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("子代理角色").assertDoesNotExist()
        compose.runOnIdle { assertTrue(assistants[0].enableSubagents); assertFalse(assistants[1].enableSubagents) }
    }

    @Test fun drawerExpandsChildAndOpensFullTrace() {
        val parentId = Uuid.random()
        val child = Conversation.ofId(Uuid.random()).copy(title = "Review trace", parentConversationId = parentId)
        var currentId by mutableStateOf(parentId)
        var expanded by mutableStateOf(false)
        var opened: Conversation? = null
        var deleteRequested: Conversation? = null
        compose.setContent {
            MaterialTheme {
                SubagentConversationContent(
                    parentId = parentId,
                    currentId = currentId,
                    children = listOf(child),
                    tasks = mapOf(child.id to SubagentTask(child.id, parentId, "review", startedAt = 1, async = true)),
                    sessionJobIds = emptySet(),
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    onChildClick = { opened = it },
                    onRequestDeleteChild = { deleteRequested = it },
                    parentRow = { rowExpanded, hasChildren, onToggle ->
                        Row(Modifier.testTag("parent-row")) {
                            SubagentExpandIcon(rowExpanded, hasChildren, onToggle)
                        }
                    },
                )
            }
        }
        compose.onNodeWithText("Review trace").assertDoesNotExist()
        compose.onNode(hasContentDescription("Subagents") and hasParent(hasTestTag("parent-row"))).performClick()
        compose.onNodeWithText("Review trace").assertIsDisplayed()
        compose.onNodeWithContentDescription("Running").assertExists()
        compose.onNodeWithContentDescription("Subagents").performClick()
        compose.onNodeWithText("Review trace").assertDoesNotExist()
        compose.runOnIdle { currentId = child.id }
        compose.onNodeWithText("Review trace").assertIsDisplayed()
        compose.onNodeWithText("Review trace").performClick()
        compose.runOnIdle { assertEquals(child.id, opened?.id); assertEquals(parentId, opened?.parentConversationId) }
        compose.onNodeWithText("Review trace").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(child.id, deleteRequested?.id) }
    }

    @Test fun childMessagesUseNormalRegenerateAndMoreActions() {
        val message = UIMessage.user("Continue this child conversation")
        var regenerated = false
        var openedActions = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings()) {
                    Column {
                        ChatMessageActionButtons(message, message.toMessageNode(), {},
                            { regenerated = true }, { openedActions = true })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.regenerate)).performClick()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        compose.runOnIdle { assertTrue(regenerated); assertTrue(openedActions) }
    }
}
