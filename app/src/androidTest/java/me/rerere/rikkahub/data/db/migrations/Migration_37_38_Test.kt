package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_37_38_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun memoryMigrationPreservesCustomConversationsAndScheduledTasks() {
        val name = "memory-37-to-38"
        helper.createDatabase(name, 37).apply {
            execSQL("PRAGMA foreign_keys=ON")
            execSQL(
                """INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at,parent_conversation_id,
                    compression_summaries,custom_system_prompt,plan_mode_enabled)
                    VALUES('parent','Parent','[]',10,20,'','[{"content":"checkpoint"}]','custom',1)"""
            )
            execSQL(
                """INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at,parent_conversation_id)
                    VALUES('child','Child','[]',30,40,'parent')"""
            )
            execSQL(
                """INSERT INTO message_node(id,conversation_id,node_index,messages,select_index)
                    VALUES('node','parent',0,'[]',0)"""
            )
            execSQL(
                """INSERT INTO scheduled_task(id,name,prompt,assistantId,scheduleType,triggerAt,
                    intervalMinutes,timeOfDayMinutes,enabled,createdAt,updatedAt,lastRunAt,lastRunId,
                    lastManualRunId,lastRunStatus,lastConversationId,lastError,revision,activeManual,
                    mode,targetConversationId,modelOverrideId,notify,showPreview,resetContextBeforeRun)
                    VALUES('task','Task','Prompt','assistant','INTERVAL',0,60,540,1,1,2,3,'run','',
                    'SUCCESS','parent','','rev',0,'FOLLOW_UP','parent','model',0,0,1)"""
            )
            execSQL(
                """INSERT INTO scheduled_task_run(id,taskId,source,dueAt,startedAt,endedAt,status,
                    conversationId,preview,error,generationMs)
                    VALUES('run','task','SCHEDULED',1,2,3,'SUCCESS','parent','preview','',4)"""
            )
            execSQL("INSERT INTO MemoryEntity(assistant_id,content) VALUES('assistant','Brief replies')")
            execSQL("INSERT INTO MemoryEntity(assistant_id,content) VALUES('assistant','Lives in Shenzhen')")
            execSQL("INSERT INTO MemoryEntity(assistant_id,content) VALUES('other','Separate memory')")
            execSQL("INSERT INTO MemoryEntity(assistant_id,content) VALUES('empty','  ')")
            close()
        }

        helper.runMigrationsAndValidate(name, 38, true, Migration_37_38).apply {
            query("SELECT path,content,updated_at FROM memory_file WHERE assistant_id='assistant'").use {
                assertTrue(it.moveToFirst())
                assertEquals(MemoryFile.PROFILE_PATH, it.getString(0))
                assertEquals(legacyMemoriesToProfile(listOf("Brief replies", "Lives in Shenzhen")), it.getString(1))
                assertTrue(it.getLong(2) > 0)
                assertFalse(it.moveToNext())
            }
            query("SELECT COUNT(*) FROM memory_file").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
            query("SELECT conversation_id,consolidated_at FROM memory_consolidation ORDER BY conversation_id").use {
                assertTrue(it.moveToFirst())
                assertEquals("child", it.getString(0))
                assertEquals(40L, it.getLong(1))
                assertTrue(it.moveToNext())
                assertEquals("parent", it.getString(0))
                assertEquals(20L, it.getLong(1))
                assertFalse(it.moveToNext())
            }
            query("SELECT parent_conversation_id FROM ConversationEntity WHERE id='child'").use {
                assertTrue(it.moveToFirst())
                assertEquals("parent", it.getString(0))
            }
            query("SELECT compression_summaries,custom_system_prompt,plan_mode_enabled FROM ConversationEntity WHERE id='parent'").use {
                assertTrue(it.moveToFirst())
                assertEquals("[{\"content\":\"checkpoint\"}]", it.getString(0))
                assertEquals("custom", it.getString(1))
                assertEquals(1, it.getInt(2))
            }
            query("SELECT mode,targetConversationId,modelOverrideId,notify,showPreview,resetContextBeforeRun FROM scheduled_task").use {
                assertTrue(it.moveToFirst())
                assertEquals("FOLLOW_UP", it.getString(0))
                assertEquals("parent", it.getString(1))
                assertEquals("model", it.getString(2))
                assertEquals(0, it.getInt(3))
                assertEquals(0, it.getInt(4))
                assertEquals(1, it.getInt(5))
            }
            query("SELECT status,generationMs FROM scheduled_task_run").use {
                assertTrue(it.moveToFirst())
                assertEquals("SUCCESS", it.getString(0))
                assertEquals(4L, it.getLong(1))
            }
            query("SELECT COUNT(*) FROM message_node").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            query("SELECT name FROM sqlite_master WHERE name='MemoryEntity'").use {
                assertFalse(it.moveToFirst())
            }
            execSQL("PRAGMA foreign_keys=ON")
            execSQL("DELETE FROM ConversationEntity WHERE id='child'")
            query("SELECT COUNT(*) FROM memory_consolidation WHERE conversation_id='child'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            close()
        }
    }
}
