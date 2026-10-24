package me.rerere.rikkahub.data.automation

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDAO {
    @Query("SELECT * FROM automation_task ORDER BY createdAt DESC")
    fun tasksFlow(): Flow<List<AutomationTask>>
    @Query("SELECT * FROM automation_task ORDER BY createdAt DESC")
    suspend fun tasks(): List<AutomationTask>
    @Query("SELECT * FROM automation_task WHERE id = :id")
    suspend fun task(id: String): AutomationTask?
    @Upsert suspend fun put(task: AutomationTask)
    @Query("DELETE FROM automation_task WHERE id = :id") suspend fun delete(id: String)

    @Query("SELECT * FROM automation_run WHERE id = :id") suspend fun run(id: String): AutomationRun?
    @Upsert suspend fun put(run: AutomationRun)
    @Query("SELECT * FROM automation_run WHERE activeTaskId IS NOT NULL ORDER BY dueAt, id")
    suspend fun active(): List<AutomationRun>
    @Query("SELECT * FROM automation_run WHERE activeTaskId IS NOT NULL ORDER BY dueAt, id")
    fun activeFlow(): Flow<List<AutomationRun>>
    @Query("SELECT * FROM automation_run WHERE activeConversationId = :id LIMIT 1")
    suspend fun forConversation(id: String): AutomationRun?
    @Query("SELECT * FROM automation_run WHERE taskId = :id ORDER BY dueAt DESC, id DESC")
    fun historyFlow(id: String): Flow<List<AutomationRun>>
    @Query("SELECT * FROM automation_run WHERE taskId = :id ORDER BY dueAt DESC, id DESC LIMIT 20")
    suspend fun history(id: String): List<AutomationRun>
    @Query("DELETE FROM automation_run WHERE taskId = :id AND activeTaskId IS NULL AND id NOT IN (SELECT id FROM automation_run WHERE taskId = :id AND activeTaskId IS NULL ORDER BY endedAt DESC, id DESC LIMIT 20)")
    suspend fun prune(id: String)
}
