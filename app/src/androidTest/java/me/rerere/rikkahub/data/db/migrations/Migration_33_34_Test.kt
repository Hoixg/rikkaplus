package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_33_34_Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), RequerySQLiteOpenHelperFactory(),
    )

    @Test fun environmentChoiceDefaultsToSharedAndPreservesSshSettings() {
        val name = "workspace-environment-migration-33-34"
        helper.createDatabase(name, 33).apply {
            execSQL("INSERT INTO workspaces (id,name,root,shell_status,created_at,updated_at,type,ssh_config) VALUES ('local','Local','local','READY',1,1,'LOCAL',NULL)")
            execSQL("INSERT INTO workspaces (id,name,root,shell_status,created_at,updated_at,type,ssh_config) VALUES ('ssh','Remote','ssh','READY',1,1,'SSH','preserved')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 34, true)
        db.query("SELECT independent_environment, type, ssh_config, shell_status FROM workspaces ORDER BY id").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
            assertEquals("LOCAL", it.getString(1))
            assertTrue(it.moveToNext())
            assertEquals(0, it.getInt(0))
            assertEquals("SSH", it.getString(1))
            assertEquals("preserved", it.getString(2))
            assertEquals("READY", it.getString(3))
        }
        db.close()
    }
}
