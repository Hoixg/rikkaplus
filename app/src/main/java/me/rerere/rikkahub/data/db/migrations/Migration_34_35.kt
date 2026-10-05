package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add metadata in place. Recreating ConversationEntity can cascade-delete its message rows. */
val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `parent_conversation_id` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ConversationEntity_parent_conversation_id` ON `ConversationEntity` (`parent_conversation_id`)")
    }
}
