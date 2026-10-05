package me.rerere.rikkahub.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class SubagentConversationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "subagents-${Uuid.random()}"
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private val scope = AppScope()

    @Before fun setup() {
        database = AppDatabaseFactory.create(context, name)
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(), database,
            FilesManager(context, FilesRepository(database.managedFileDao()), scope), MessageFtsManager(database))
    }
    @After fun teardown() { scope.cancel(); database.close(); context.deleteDatabase(name) }

    @Test fun rootListsExcludeChildrenButTraceLoadsAndAssistantMoveKeepOwnership() = runBlocking {
        val root = Conversation.ofId(Uuid.random()).copy(title = "root").updateCurrentMessages(listOf(UIMessage.user("private parent context")))
        val child = Conversation.ofId(Uuid.random(), root.assistantId).copy(title = "child", parentConversationId = root.id,
            workspaceCwd = "/workspace/subagents", modelOverrideId = Uuid.random())
            .updateCurrentMessages(listOf(UIMessage.user("self contained task"), UIMessage.assistant("answer")))
        repository.insertConversation(root); repository.insertConversation(child)
        assertEquals(listOf(root.id), repository.getConversationsOfAssistant(root.assistantId).first().map { it.id })
        assertEquals(listOf(root.id), repository.getRecentConversations(root.assistantId).map { it.id })
        assertEquals(listOf(child.id), repository.getSubconversationsOfParent(root.id).first().map { it.id })
        val trace = repository.getConversationById(child.id)!!
        assertEquals(child.parentConversationId, trace.parentConversationId)
        assertEquals(child.workspaceCwd, trace.workspaceCwd); assertEquals(child.modelOverrideId, trace.modelOverrideId)
        assertEquals(child.messageNodes, trace.messageNodes)
        val moved = Uuid.random()
        repository.moveConversationTreeToAssistant(root.id, moved)
        assertEquals(moved, repository.getConversationById(root.id)?.assistantId)
        assertEquals(moved, repository.getConversationById(child.id)?.assistantId)
        assertEquals(root.id, repository.getConversationById(child.id)?.parentConversationId)
        assertTrue(repository.getConversationById(child.id)?.currentMessages?.none { it.toText().contains("private parent") } == true)
    }

    @Test fun deletingParentRemovesChildMessagesAndSearchIndexWhileKeepingUnrelatedConversation() = runBlocking {
        val root = Conversation.ofId(Uuid.random()).copy(title = "parent")
        val child = Conversation.ofId(Uuid.random(), root.assistantId).copy(title = "child", parentConversationId = root.id)
            .updateCurrentMessages(listOf(UIMessage.user("uniquechildtrace")))
        val unrelated = Conversation.ofId(Uuid.random()).copy(title = "unrelated")
        listOf(root, child, unrelated).forEach { repository.insertConversation(it) }
        repository.deleteConversation(root)
        assertNull(repository.getConversationById(root.id)); assertNull(repository.getConversationById(child.id))
        assertNotNull(repository.getConversationById(unrelated.id))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM message_node WHERE conversation_id='${child.id}'").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        assertTrue(repository.searchMessages("uniquechildtrace").isEmpty())
    }
}
