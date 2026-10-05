package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_34_35_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun parentColumnAndIndexAreAdditiveAndScheduledStateIsPreserved() {
        val name = "subagents-34-to-35"
        helper.createDatabase(name, 34).apply {
            execSQL("PRAGMA foreign_keys=ON")
            execSQL("""INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at,folder_id,model_override_id,compression_summaries,custom_system_prompt)
                VALUES('parent','Parent','[]',10,20,'folder','model','[{"content":"checkpoint"}]','custom')""")
            execSQL("""INSERT INTO message_node(id,conversation_id,node_index,messages,select_index) VALUES('node','parent',0,'[]',0)""")
            execSQL("""INSERT INTO scheduled_task(id,name,prompt,assistantId,scheduleType,triggerAt,intervalMinutes,timeOfDayMinutes,enabled,
                createdAt,updatedAt,lastRunAt,lastRunId,lastManualRunId,lastRunStatus,lastConversationId,lastError,revision,activeManual,
                mode,targetConversationId,modelOverrideId,notify,showPreview)
                VALUES('task','Task','Prompt','assistant','INTERVAL',0,60,540,1,1,2,3,'run','','SUCCESS','parent','','rev',0,'FOLLOW_UP','parent','model',0,0)""")
            execSQL("""INSERT INTO scheduled_task_run(id,taskId,source,dueAt,startedAt,endedAt,status,conversationId,preview,error,generationMs)
                VALUES('run','task','SCHEDULED',1,2,3,'SUCCESS','parent','preview','',4)""")
            close()
        }
        helper.runMigrationsAndValidate(name, 35, true, Migration_34_35).apply {
            query("SELECT parent_conversation_id,folder_id,model_override_id,compression_summaries,custom_system_prompt FROM ConversationEntity").use {
                assertTrue(it.moveToFirst()); assertEquals("", it.getString(0)); assertEquals("folder", it.getString(1))
                assertEquals("model", it.getString(2)); assertEquals("[{\"content\":\"checkpoint\"}]", it.getString(3)); assertEquals("custom", it.getString(4))
            }
            query("PRAGMA index_info('index_ConversationEntity_parent_conversation_id')").use {
                assertTrue(it.moveToFirst()); assertEquals("parent_conversation_id", it.getString(2))
            }
            query("SELECT mode,targetConversationId,modelOverrideId,notify,showPreview,lastRunStatus FROM scheduled_task").use {
                assertTrue(it.moveToFirst()); assertEquals("FOLLOW_UP", it.getString(0)); assertEquals("parent", it.getString(1))
                assertEquals("model", it.getString(2)); assertEquals(0, it.getInt(3)); assertEquals(0, it.getInt(4)); assertEquals("SUCCESS", it.getString(5))
            }
            query("SELECT status,generationMs FROM scheduled_task_run").use {
                assertTrue(it.moveToFirst()); assertEquals("SUCCESS", it.getString(0)); assertEquals(4, it.getInt(1))
            }
            query("SELECT COUNT(*) FROM message_node").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            execSQL("""INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at,parent_conversation_id) VALUES('child','Child','[]',30,40,'parent')""")
            query("SELECT id FROM ConversationEntity WHERE parent_conversation_id='parent'").use { assertTrue(it.moveToFirst()); assertEquals("child", it.getString(0)) }
            close()
        }
    }
}
