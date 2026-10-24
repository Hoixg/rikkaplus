package me.rerere.rikkahub.data.automation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AutomationDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: AutomationDAO
    private val definition = TaskDefinition("task", "prompt", Uuid.random().toString())

    @Before fun open() {
        database =
            Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AppDatabase::class.java
            )
                .openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.automationDao()
    }

    @After fun close() {
        database.close()
    }
    private suspend fun task(
        id: String = "task"
    ) = AutomationTask(id, definition.assistantId, definition, false, "revision", null, 1, 1).also { dao.put(it) }

    @Test fun aTaskCanOwnOnlyOneActiveRunAndEndingItReleasesOwnership() = runBlocking {
        task()
        val first = AutomationRun("first", "task", definition, 1, true)
        dao.put(first)
        assertTrue(runCatching { dao.put(AutomationRun("second", "task", definition, 2, true)) }.isFailure)
        dao.put(first.copy(state = RunState.SUCCESS, activeTaskId = null, endedAt = 2))
        dao.put(AutomationRun("second", "task", definition, 3, true))
        assertEquals(listOf("second"), dao.active().map { it.id })
    }

    @Test fun differentTasksCannotOwnTheSameConversationWhileAnApprovalIsPending() = runBlocking {
        task("a")
        task("b")
        dao.put(
            AutomationRun("first", "a", definition, 1, true, state = RunState.APPROVAL, activeConversationId = "chat")
        )
        assertTrue(
            runCatching {
                dao.put(
                    AutomationRun("second", "b", definition, 2, true, activeConversationId = "chat")
                )
            }.isFailure
        )
        assertEquals("first", dao.forConversation("chat")?.id)
    }

    @Test fun pruneRetainsEveryPendingRunAndOnlyTheLatestTwentyFinishedRuns() = runBlocking {
        task()
        repeat(25) { index ->
            dao.put(AutomationRun(
                "history-$index",
                "task",
                definition,
                index.toLong(),
                true,
                state = RunState.SUCCESS,
                activeTaskId = null,
                endedAt = index.toLong()
            ))
        }
        dao.put(AutomationRun("pending", "task", definition, 0, true, state = RunState.APPROVAL))
        dao.prune("task")
        assertNotNull(dao.run("pending"))
        assertNull(dao.run("history-4"))
        assertNotNull(dao.run("history-5"))
        assertNotNull(dao.run("history-24"))
    }

    @Test fun deletingATaskCascadesOnlyItsRunRecords() = runBlocking {
        task("a")
        task("b")
        dao.put(AutomationRun("run-a", "a", definition, 1, true, state = RunState.SUCCESS, activeTaskId = null))
        dao.put(AutomationRun("run-b", "b", definition, 1, true))
        dao.delete("a")
        assertNull(dao.run("run-a"))
        assertNotNull(dao.task("b"))
        assertNotNull(dao.run("run-b"))
    }
}
