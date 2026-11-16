package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_35_36_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun addsEmptyMountsWithoutChangingExistingWorkspaces() {
        val name = "migration-35-36-test"
        helper.createDatabase(name, 35).apply {
            listOf("shared" to 0, "independent" to 1, "ssh" to 0).forEach { (id, independent) ->
                execSQL(
                    "INSERT INTO workspaces " +
                        "(id, name, root, type, shell_status, created_at, updated_at, independent_environment) " +
                        "VALUES (?, ?, ?, ?, 'READY', 1, 2, ?)",
                    arrayOf<Any?>(id, id, id, if (id == "ssh") "SSH" else "LOCAL", independent),
                )
            }
            close()
        }

        helper.runMigrationsAndValidate(name, 36, true).use { db ->
            db.query("SELECT id, independent_environment, storage_mounts FROM workspaces ORDER BY id").use { cursor ->
                val rows = buildList {
                    while (cursor.moveToNext()) {
                        add(Triple(cursor.getString(0), cursor.getInt(1), cursor.getString(2)))
                    }
                }
                assertEquals(
                    listOf(Triple("independent", 1, "[]"), Triple("shared", 0, "[]"), Triple("ssh", 0, "[]")),
                    rows,
                )
            }
        }
    }
}
