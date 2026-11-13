package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.automation.AutomationRun
import me.rerere.rikkahub.data.automation.AutomationTask
import me.rerere.rikkahub.data.automation.ScheduleKind
import me.rerere.rikkahub.data.automation.TaskDefinition
import me.rerere.rikkahub.data.automation.TaskMode
import me.rerere.rikkahub.data.automation.TaskSchedule
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationToolEditingTest {
    private val assistantId = "11111111-1111-4111-8111-111111111111"
    private val definition = TaskDefinition(
        name = "检查服务器",
        prompt = "检查 CPU。\n检查磁盘。\n中文汇报。",
        assistantId = assistantId,
        mode = TaskMode.FOLLOW_UP,
        conversationId = "22222222-2222-4222-8222-222222222222",
        modelId = "33333333-3333-4333-8333-333333333333",
        schedule = TaskSchedule(
            kind = ScheduleKind.WEEKLY,
            timeMinutes = 600,
            timesMinutes = listOf(600, 1080),
            weekdays = setOf(1, 3),
            startDate = "2026-11-13",
            endDate = "2026-12-31",
        ),
        notify = false,
        resetContextBeforeRun = true,
    )

    private fun edit(arguments: String, current: TaskDefinition? = definition) =
        resolveAutomationDefinition(JsonInstant.parseToJsonElement(arguments).jsonObject, current, assistantId)

    private val taskId = "44444444-4444-4444-8444-444444444444"
    private val task get() = AutomationTask(taskId, assistantId, definition, true, "revision", 1000L, 0, 0)

    private fun stage(changes: String, previous: JsonObject? = null, current: AutomationTask? = task): JsonObject =
        editAutomationDraft(buildJsonObject {
            put("action", "update")
            put("id", taskId)
            put("revision", current?.revision)
            JsonInstant.parseToJsonElement(changes).jsonObject.forEach { (key, value) -> put(key, value) }
        }, current, previous, assistantId)

    private fun draftMessage(draft: JsonObject) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("edit", "scheduled_task", "{}", output = listOf(
            UIMessagePart.Text("draft", metadata = buildJsonObject { put("automation_draft", draft) })
        ))),
    )

    @Test fun `renaming or replacing a prompt preserves every omitted field`() {
        assertEquals(definition.copy(name = "日报"), edit("""{"definition":{"name":"日报"}}"""))
        assertEquals(definition.copy(prompt = "新要求"), edit("""{"definition":{"prompt":"新要求"}}"""))
    }

    @Test fun `nested schedule edits preserve the prompt weekdays and date range`() {
        val updated = edit("""{"definition":{"schedule":{"timesMinutes":[660,1080]}}}""")
        assertEquals(definition.copy(schedule = definition.schedule.copy(timesMinutes = listOf(660, 1080))), updated)
        val single = edit("""{"definition":{"schedule":{"timeMinutes":900}}}""")
        assertEquals(listOf(900), single.schedule.recurringTimes)
        assertEquals(definition.schedule.copy(timeMinutes = 900, timesMinutes = emptyList()), single.schedule)
    }

    @Test fun `one time scalar updates replace an existing multiple time schedule`() {
        val current = definition.copy(schedule = TaskSchedule(ScheduleKind.ONCE, atTimes = listOf(1000, 2000)))
        val single = edit("""{"definition":{"schedule":{"at":3000}}}""", current)
        assertEquals(listOf(3000L), single.schedule.onceTimes)
        val multiple = edit("""{"definition":{"schedule":{"at":3000,"atTimes":[3000,4000]}}}""", current)
        assertEquals(listOf(3000L, 4000L), multiple.schedule.onceTimes)
        assertEquals(current.prompt, multiple.prompt)
    }

    @Test fun `explicit null and false values clear settings instead of inheriting them`() {
        val updated =
            edit(
                """{"definition":{"modelId":null,"notify":false,"resetContextBeforeRun":false,"schedule":{"endDate":null}}}""",
                definition.copy(notify = true)
            )
        assertEquals(definition.copy(
            modelId = null,
            notify = false,
            resetContextBeforeRun = false,
            schedule = definition.schedule.copy(endDate = null)
        ), updated)
    }

    @Test fun `workspace style edits replace insert and delete prompt passages`() {
        val replaced = edit("""{"old_text":"检查 CPU。","new_text":"检查 CPU 和内存。"}""")
        assertEquals(definition.copy(prompt = "检查 CPU 和内存。\n检查磁盘。\n中文汇报。"), replaced)
        val inserted = edit("""{"old_text":"中文汇报。","new_text":"检查网络。\n中文汇报。"}""")
        assertEquals(definition.copy(prompt = "检查 CPU。\n检查磁盘。\n检查网络。\n中文汇报。"), inserted)
        val deleted = edit("""{"old_text":"检查磁盘。\n","new_text":""}""")
        assertEquals(definition.copy(prompt = "检查 CPU。\n中文汇报。"), deleted)
    }

    @Test fun `workspace matching and replace all are reused for prompt editing`() {
        val indented = definition.copy(prompt = "  检查 CPU。\n  中文汇报。")
        assertEquals(
            "  检查内存。\n  中文汇报。",
            edit("""{"old_text":"检查 CPU。\n中文汇报。","new_text":"检查内存。\n中文汇报。"}""", indented).prompt
        )
        val repeated = definition.copy(prompt = "日报\n日报")
        assertTrue(runCatching { edit("""{"old_text":"日报","new_text":"周报"}""", repeated) }.isFailure)
        assertEquals("周报\n周报", edit("""{"old_text":"日报","new_text":"周报","replace_all":true}""", repeated).prompt)
    }

    @Test fun `invalid text edits fail without producing a replacement definition`() {
        listOf(
            """{"old_text":"不存在的内容","new_text":"新内容"}""",
            """{"old_text":"检查 CPU。"}""",
            """{"new_text":"新内容"}""",
            """{"old_text":"","new_text":"新内容"}""",
            """{"definition":{"prompt":"新内容"},"old_text":"检查 CPU。","new_text":"其他内容"}""",
        ).forEach { assertTrue(it, runCatching { edit(it) }.isFailure) }
        assertTrue(runCatching { edit("""{"old_text":"检查 CPU。","new_text":"新内容"}""", null) }.isFailure)
    }

    @Test fun `creating a task uses existing defaults and cannot change the assistant scope`() {
        val created =
            edit(
                """{"definition":{"name":"日报","prompt":"报告","assistantId":"other","schedule":{"timesMinutes":[600,1080]}}}""",
                null
            )
        assertEquals(TaskDefinition(
            name = "日报",
            prompt = "报告",
            assistantId = assistantId,
            schedule = TaskSchedule(timesMinutes = listOf(600, 1080))
        ), created)
        created.validate()
        assertTrue(runCatching { edit("""{"definition":{"name":"日报","prompt":"报告"}}""", null) }.isFailure)
    }

    @Test fun `content edits retain the pending time and active run snapshot`() {
        val task = AutomationTask("task", assistantId, definition, true, "revision", 1000L, 0, 0)
        val run = AutomationRun("run", task.id, definition, 1000L, false)
        val updated = task.reconfigure(
            edit("""{"old_text":"检查 CPU。","new_text":"检查内存。"}"""),
            null,
            2000L,
            "new-revision"
        )
        assertEquals(task.nextRunAt, updated.nextRunAt)
        assertEquals(task.enabled, updated.enabled)
        assertEquals(definition, run.definition)
        assertEquals("检查内存。\n检查磁盘。\n中文汇报。", updated.definition.prompt)
    }

    @Test fun `multiple draft edits accumulate into one final save without changing the original task`() {
        val renamed = stage("""{"definition":{"name":"日报"}}""")
        val prompt = stage("""{"old_text":"检查 CPU。","new_text":"检查内存。"}""", renamed)
        val timing = stage("""{"definition":{"schedule":{"timesMinutes":[660,1080]}}}""", prompt)
        val paused = stage("""{"action":"set_enabled","enabled":false}""", timing)
        val prepared = prepareAutomationSave(listOf(draftMessage(paused)), taskId, assistantId)
        val final = JsonInstant.decodeFromJsonElement<TaskDefinition>(prepared.getValue("definition"))
        assertEquals(definition.copy(
            name = "日报",
            prompt = "检查内存。\n检查磁盘。\n中文汇报。",
            schedule = definition.schedule.copy(timesMinutes = listOf(660, 1080))
        ), final)
        assertEquals("save", prepared.getValue("action").jsonPrimitive.content)
        assertEquals("false", prepared.getValue("enabled").jsonPrimitive.content)
        assertEquals(setOf("action", "id", "revision", "definition", "enabled"), prepared.keys)
        assertEquals(definition, task.definition)
        assertTrue(task.enabled)
    }

    @Test fun `drafts survive normal chat serialization and lookup selects the latest matching task`() {
        val first = stage("""{"definition":{"name":"第一版"}}""")
        val last = stage("""{"definition":{"name":"第二版"}}""", first)
        val messages = listOf(draftMessage(first), draftMessage(last))
        val restored = JsonInstant.decodeFromString<List<UIMessage>>(JsonInstant.encodeToString(messages))
        assertEquals(last, findAutomationDraft(restored, taskId, assistantId))
        assertNull(findAutomationDraft(restored, taskId, "other-assistant"))
        assertNull(findAutomationDraft(restored, "other-task", assistantId))
        assertEquals(last["definition"], prepareAutomationSave(restored, taskId, assistantId)["definition"])
    }

    @Test fun `approval arguments stay fixed if another draft edit is made later`() {
        val first = stage("""{"definition":{"name":"最终预览"}}""")
        val prepared = prepareAutomationSave(listOf(draftMessage(first)), taskId, assistantId)
        val later = stage("""{"definition":{"name":"后续修改"}}""", first)
        assertEquals("最终预览", prepared.getValue("definition").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("后续修改", later.getValue("definition").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test fun `successful save consumes a draft and a later edit starts a new draft`() {
        val edited = stage("""{"definition":{"name":"新名称"}}""")
        val saved = buildJsonObject {
            put("id", taskId)
            put("assistant_id", assistantId)
            put("saved", true)
        }
        val messages = listOf(draftMessage(edited), draftMessage(saved))
        assertNull(findAutomationDraft(messages, taskId, assistantId))
        assertTrue(runCatching { prepareAutomationSave(messages, taskId, assistantId) }.isFailure)
        assertEquals(edited, findAutomationDraft(messages + draftMessage(edited), taskId, assistantId))
    }

    @Test fun `creation and subsequent edits remain a new task draft until final save`() {
        val created =
            stage("""{"action":"create","definition":{"name":"日报","prompt":"报告","schedule":{}}}""", current = null)
        val edited = stage("""{"old_text":"报告","new_text":"中文报告"}""", created, current = null)
        val prepared = prepareAutomationSave(listOf(draftMessage(edited)), taskId, assistantId)
        assertEquals(JsonNull, prepared["revision"])
        assertEquals("true", prepared.getValue("enabled").jsonPrimitive.content)
        assertEquals("中文报告", prepared.getValue("definition").jsonObject.getValue("prompt").jsonPrimitive.content)
    }

    @Test fun `stale drafts cannot overwrite a changed or deleted task`() {
        val edited = stage("""{"definition":{"name":"草稿"}}""")
        val changed = task.copy(revision = "changed", definition = definition.copy(name = "外部修改"))
        assertTrue(runCatching {
            editAutomationDraft(buildJsonObject {
                put("action", "update")
                put("id", taskId)
                put("revision", "revision")
            }, changed, edited, assistantId)
        }.isFailure)
        val fresh = stage("""{"definition":{"notify":true}}""", edited, changed)
        assertEquals("外部修改", fresh.getValue("definition").jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(runCatching { stage("{}", edited, current = null) }.isFailure)
    }

    @Test fun `save waits for pending draft edits instead of approving an incomplete snapshot`() {
        val edited = stage("""{"definition":{"name":"草稿"}}""")
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("pending", "scheduled_task", """{"action":"update","id":"$taskId"}""")
        ))
        assertTrue(
            runCatching { prepareAutomationSave(listOf(draftMessage(edited), pending), taskId, assistantId) }.isFailure
        )
    }

    @Test fun `consecutive passage edits can turn one two three into one three four before saving`() {
        val current = task.copy(definition = definition.copy(prompt = "1\n2\n3"))
        val first = stage("""{"old_text":"2","new_text":"3"}""", current = current)
        val second = stage("""{"old_text":"3\n3","new_text":"3\n4"}""", first, current)
        val prepared = prepareAutomationSave(listOf(draftMessage(first), draftMessage(second)), taskId, assistantId)
        assertEquals("1\n3\n4", prepared.getValue("definition").jsonObject.getValue("prompt").jsonPrimitive.content)
        assertEquals("1\n2\n3", current.definition.prompt)
    }
}
