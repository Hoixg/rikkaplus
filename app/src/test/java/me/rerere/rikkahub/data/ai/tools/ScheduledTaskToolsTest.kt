package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ScheduledTaskFile
import me.rerere.rikkahub.data.model.encodeScheduledTaskFiles
import me.rerere.rikkahub.data.model.scheduledTaskFiles
import me.rerere.rikkahub.data.repository.ScheduledTaskSchedule

class ScheduledTaskToolsTest {
    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun task() = ScheduledTaskEntity("my-task", "Task", "Prompt", "assistant-a", createdAt = 1, updatedAt = 1, revision = "rev")
    @Test fun createDefaultsToDailyNineOClock() {
        val parsed = parseSchedule(args("{}"))
        assertEquals("DAILY", parsed.type.name)
        assertEquals(540, parsed.timeOfDayMinutes)
        assertEquals(1440, parsed.intervalMinutes)
    }
    @Test fun weeklyDefaultsToWeekdaysAndCanClearOneDateWithoutChangingOthers() {
        val parsed = parseSchedule(args("""{"schedule_type":"WEEKLY","start_date":"2026-10-05","end_date":"2026-10-31"}"""))
        assertEquals(0x1f, parsed.weekdaysMask)
        val original = task().copy(scheduleType = "WEEKLY", weekdaysMask = 1 shl 2,
            startDate = parsed.startDate, endDate = parsed.endDate)
        val changed = parseSchedule(args("""{"start_date":null,"time_of_day":"10:45"}"""), original)
        assertNull(changed.startDate)
        assertEquals("2026-10-31", changed.endDate)
        assertEquals(original.weekdaysMask, changed.weekdaysMask)
    }
    @Test fun invalidWeekdaysAndDateRangesAreRejected() {
        for (json in listOf("""{"schedule_type":"WEEKLY","weekdays":[]}""", """{"schedule_type":"WEEKLY","weekdays":[0]}""",
            """{"schedule_type":"WEEKLY","weekdays":[8]}""", """{"schedule_type":"DAILY","weekdays":[1]}""",
            """{"schedule_type":"DAILY","start_date":"2026-02-30"}""")) {
            assertThrows(RuntimeException::class.java) { parseSchedule(args(json)) }
        }
    }
    @Test fun updatingPromptPreservesOnceTimeWithoutTriggerArgument() {
        val original = task().copy(scheduleType = "ONCE", triggerAt = 123456789L)
        val parsed = parseSchedule(args("""{"schedule_type":"ONCE"}"""), original)
        assertEquals(original.triggerAt, parsed.triggerAt)
    }
    @Test fun updatingOneScheduleFieldPreservesOtherFields() {
        val original = task().copy(timeOfDayMinutes = 630, intervalMinutes = 90)
        val parsed = parseSchedule(args("""{"time_of_day":"10:45"}"""), original)
        assertEquals(645, parsed.timeOfDayMinutes)
        assertEquals(90, parsed.intervalMinutes)
    }
    @Test fun intervalsRejectShortAndNonIntegerValues() {
        for (value in listOf("14", "0", "1.5", "\"invalid\"")) {
            assertThrows(RuntimeException::class.java) {
                parseSchedule(args("""{"schedule_type":"INTERVAL","interval_minutes":$value}"""))
            }
        }
    }
    @Test fun invalidDatesAndTrailingTextAreRejected() {
        for (value in listOf("2026-02-30 09:00", "2026-10-02 25:00", "2026-10-02 09:00 trailing", "2026-10-02")) {
            assertThrows(IllegalArgumentException::class.java) { parseTriggerAt(value) }
        }
    }
    @Test fun timeOfDayIsValidated() {
        assertEquals(1439, parseTimeOfDay("23:59"))
        for (value in listOf("24:00", "12:60", "-1:00", "09:00 extra")) {
            assertThrows(IllegalArgumentException::class.java) { parseTimeOfDay(value) }
        }
    }
    @Test fun idLookupCannotEscapeAssistantScopedListOrFallBackToName() {
        val scoped = listOf(task())
        assertNull(findTask(args("""{"id":"other-assistant-task","name":"Task"}"""), scoped))
        assertEquals("my-task", findTask(args("""{"name":"Task"}"""), scoped)?.id)
    }
    @Test fun invalidScheduleTypeAndMissingOnceDateAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parseSchedule(args("""{"schedule_type":"CRON"}""")) }
        assertThrows(IllegalStateException::class.java) { parseSchedule(args("""{"schedule_type":"ONCE"}""")) }
    }
    @Test fun changingModeAndTargetClearsObsoleteMessageAndPreservesNotificationSettings() {
        val firstId = "00000000-0000-0000-0000-000000000001"
        val secondId = "00000000-0000-0000-0000-000000000002"
        val original = task().copy(mode = "REGENERATE", targetConversationId = firstId, targetUserMessageId = firstId, notify = false)
        val changed = applyExecutionFields(original, args("""{"target_conversation_id":"$secondId"}"""))
        assertNull(changed.targetUserMessageId); assertFalse(changed.notify)
        val newChat = applyExecutionFields(original, args("""{"mode":"NEW_CHAT","model_override_id":null,"show_preview":false}"""))
        assertNull(newChat.targetConversationId); assertNull(newChat.targetUserMessageId); assertFalse(newChat.showPreview)
        assertThrows(IllegalArgumentException::class.java) { applyExecutionFields(original, args("""{"mode":"INVALID"}""")) }
    }

    @Test fun editingNameEnabledPromptAndClearingBoundsPreservesOtherSettings() {
        val original = task().copy(scheduleType = "WEEKLY", weekdaysMask = 5,
            startDate = "2026-10-05", endDate = "2026-10-31", notify = false)
        val changed = buildTaskChange(args("""{"name":"Renamed","enabled":false,"start_date":null,"prompt":"Changed"}"""), Uuid.random(), original)
        assertEquals("Renamed", changed.name)
        assertEquals("Changed", changed.prompt)
        assertFalse(changed.enabled)
        assertNull(changed.startDate)
        assertEquals(original.endDate, changed.endDate)
        assertEquals(original.weekdaysMask, changed.weekdaysMask)
        assertFalse(changed.notify)
        assertEquals(original.id, changed.id)
    }

    @Test fun emptyPromptIsSupportedOnlyForRegeneration() {
        val original = task().copy(enabled = false, mode = "REGENERATE", targetConversationId = "conversation", targetUserMessageId = "message")
        val changed = buildTaskChange(args("""{"prompt":""}"""), Uuid.random(), original)
        assertEquals("", changed.prompt)
        ScheduledTaskSchedule.validate(changed, 0, false)
        assertThrows(IllegalArgumentException::class.java) {
            ScheduledTaskSchedule.validate(changed.copy(mode = "FOLLOW_UP"), 0, false)
        }
    }

    @Test fun fullConfigurationRoundTripsAllEditableSettings() {
        val original = task().copy(mode = "REGENERATE", scheduleType = "WEEKLY", weekdaysMask = 5,
            startDate = "2026-10-05", endDate = "2026-10-31", enabled = false, notify = false,
            showPreview = false, targetConversationId = "conversation", targetUserMessageId = "message", modelOverrideId = "model",
            filesJson = encodeScheduledTaskFiles(listOf(ScheduledTaskFile("content://provider/task-a", "notes.md"))),
            createdFilesJson = encodeScheduledTaskFiles(listOf(ScheduledTaskFile("content://provider/tree/generated", "new.md"))),
            filesEnabled = true, creationFolderUri = "content://provider/tree", allowFileCreate = true,
            allowFileRead = true, allowFileWrite = true, resetContextBeforeRun = true)
        assertEquals(taskConfiguration(original), taskConfiguration(taskFromConfiguration(taskConfiguration(original))))
    }

    @Test fun eachTaskHasItsOwnFileListAndPermissionSwitches() {
        val first = task().copy(
            filesJson = encodeScheduledTaskFiles(listOf(ScheduledTaskFile("content://provider/first", "first.md"))),
            filesEnabled = true, allowFileRead = true,
        )
        val second = task().copy(
            id = "second-task",
            filesJson = encodeScheduledTaskFiles(listOf(ScheduledTaskFile("content://provider/second", "second.txt"))),
            filesEnabled = true, allowFileDelete = true,
        )

        assertEquals(listOf("list", "read"), allowedScheduledTaskFileActions(first))
        assertEquals(listOf("list", "delete"), allowedScheduledTaskFileActions(second))
        assertEquals(first.filesJson, taskFromConfiguration(taskConfiguration(first)).filesJson)
        assertEquals(second.filesJson, taskFromConfiguration(taskConfiguration(second)).filesJson)
        assertEquals(emptyList<String>(), allowedScheduledTaskFileActions(first.copy(filesEnabled = false)))
        assertEquals(emptyList<String>(), allowedScheduledTaskFileActions(first.copy(filesJson = "[]")))
        val creator = first.copy(filesJson = "[]", creationFolderUri = "content://provider/tree", allowFileCreate = true)
        assertEquals(listOf("list", "create", "read"), allowedScheduledTaskFileActions(creator))
        assertEquals(emptyList<String>(), allowedScheduledTaskFileActions(creator.copy(creationFolderUri = null)))
        val afterCreation = creator.copy(
            createdFilesJson = encodeScheduledTaskFiles(listOf(
                ScheduledTaskFile("content://provider/tree/document/new", "new.md", "content://provider/tree")
            )),
            creationFolderUri = null,
        )
        assertEquals(listOf("new.md"), scheduledTaskFiles(afterCreation).map { it.name })
        assertEquals(listOf("list", "read"), allowedScheduledTaskFileActions(afterCreation))
        val stableId = scheduledTaskFileId(first.id, "content://provider/first")
        assertEquals(stableId, scheduledTaskFileId(first.id, "content://provider/first"))
        assertNotEquals(stableId, scheduledTaskFileId(second.id, "content://provider/first"))
    }

    @Test fun scheduledExecutionRejectsEveryMutationAndAllowsReads() {
        for (action in listOf("create", "update", "delete", "set_enabled", "cancel_run", "run_now")) {
            assertThrows(IllegalArgumentException::class.java) { checkScheduledActionAllowed(action, true) }
            checkScheduledActionAllowed(action, false)
        }
        for (action in scheduledReadActions) checkScheduledActionAllowed(action, true)
    }

    @Test fun targetsRequireOwnedConversationSelectedUserMessageAndConfiguredModel() {
        val assistantId = Uuid.random()
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Check this")))
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Reply")))
        val conversation = Conversation.ofId(Uuid.random(), assistantId).updateCurrentMessages(listOf(user, reply))
        val model = Model(modelId = "test", displayName = "Test")
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val valid = task().copy(assistantId = assistantId.toString(), mode = "REGENERATE",
            targetConversationId = conversation.id.toString(), targetUserMessageId = user.id.toString(), modelOverrideId = model.id.toString())
        validateTaskTargetSelection(valid, conversation, settings)
        assertThrows(IllegalArgumentException::class.java) { validateTaskTargetSelection(valid, null, settings) }
        assertThrows(IllegalArgumentException::class.java) { validateTaskTargetSelection(valid.copy(assistantId = Uuid.random().toString()), conversation, settings) }
        assertThrows(IllegalArgumentException::class.java) { validateTaskTargetSelection(valid.copy(targetUserMessageId = reply.id.toString()), conversation, settings) }
        assertThrows(IllegalArgumentException::class.java) { validateTaskTargetSelection(valid, conversation, Settings()) }
    }

}
