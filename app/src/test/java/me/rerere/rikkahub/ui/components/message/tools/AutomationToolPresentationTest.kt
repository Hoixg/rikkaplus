package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.automation.TaskDefinition
import me.rerere.rikkahub.data.automation.TaskMode
import me.rerere.rikkahub.data.automation.TaskSchedule
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationToolPresentationTest {
    private val definition = TaskDefinition(
        name = "每日检查",
        prompt = "1\n3\n4",
        assistantId = "11111111-1111-4111-8111-111111111111",
        mode = TaskMode.FOLLOW_UP,
        conversationId = "22222222-2222-4222-8222-222222222222",
        schedule = TaskSchedule(timesMinutes = listOf(600, 1080)),
        resetContextBeforeRun = true,
    )

    private fun context(
        action: String,
        result: JsonElement? = null,
        approval: ToolApprovalState = ToolApprovalState.Auto,
        metadata: JsonObject? = null,
        fields: JsonObject = JsonObject(emptyMap()),
    ): ToolUIContext {
        val args = buildJsonObject {
            put("action", action)
            fields.forEach { (key, value) -> put(key, value) }
        }
        val tool = UIMessagePart.Tool(
            "operation",
            "scheduled_task",
            args.toString(),
            approvalState = approval,
            output = result?.let { listOf(UIMessagePart.Text(it.toString(), metadata = metadata)) }.orEmpty(),
        )
        // Chat persistence must retain the exact same preview, including draft metadata.
        val restored = JsonInstant.decodeFromString<UIMessagePart.Tool>(JsonInstant.encodeToString(tool))
        return ToolUIContext(restored, args, result, loading = false)
    }

    private fun definitionFields(enabled: Boolean? = null) = buildJsonObject {
        put("definition", JsonInstant.encodeToJsonElement(definition))
        put("enabled", enabled)
    }

    @Test fun `intermediate draft edits remain compact and are never called saved`() {
        val metadata = buildJsonObject { put("automation_draft", definitionFields()) }
        listOf("create", "update", "set_enabled").forEach { action ->
            val data =
                AutomationToolPresentation(context(action, buildJsonObject { put("draft", true) }, metadata = metadata))
            assertEquals(definition, data.task?.definition)
            assertEquals("草稿待保存，尚未生效", data.status)
            assertFalse(data.showSummary)
        }
    }

    @Test fun `final approval displays the complete frozen draft without a tool result`() {
        val data =
            AutomationToolPresentation(
                context("save", approval = ToolApprovalState.Pending, fields = definitionFields(false))
            )
        assertEquals("等待审批", data.status)
        assertTrue(data.showSummary)
        assertEquals(definition, data.task?.definition)
        assertEquals(false, data.task?.enabled)
        assertTrue(data.task!!.schedule.contains("10:00、18:00"))
        assertTrue(data.task!!.definition!!.resetContextBeforeRun)
    }

    @Test fun `successful save keeps the approved definition and uses the actual resulting enabled state`() {
        val data =
            AutomationToolPresentation(context(
                "save",
                fields = definitionFields(),
                approval = ToolApprovalState.Approved,
                result = buildJsonObject {
                    put("name", "不可替换审批快照")
                    put("enabled", true)
                    put("next_run_at", 1234L)
                }
            ))
        assertEquals("已保存", data.status)
        assertEquals(definition, data.task?.definition)
        assertEquals(true, data.task?.enabled)
        assertEquals(1234L, data.task?.nextRunAt)
    }

    @Test fun `operation cards retain their snapshot after a task is renamed or deleted`() {
        val snapshot = buildJsonObject {
            put("name", "原来的任务")
            put("schedule", "每天 10:00")
            put("enabled", true)
        }
        listOf("delete", "run", "cancel").forEach { action ->
            val data = AutomationToolPresentation(context(
                action,
                result = buildJsonObject { put("name", "后来改名") },
                fields = buildJsonObject { put("task_preview", snapshot) }
            ))
            assertEquals("原来的任务", data.task?.name)
            assertEquals("每天 10:00", data.task?.schedule)
            assertNull(data.task?.enabled)
            assertNull(data.task?.nextRunAt)
            assertTrue(data.showSummary)
        }
    }

    @Test fun `empty task list is distinct from a failed list query`() {
        val empty = AutomationToolPresentation(context("list", buildJsonArray {}))
        assertEquals("暂无定时任务", empty.status)
        val failed = AutomationToolPresentation(context("list", buildJsonObject { put("error", "读取失败") }))
        assertEquals("操作失败", failed.status)
        assertEquals("读取失败", failed.error)
        assertTrue(failed.showSummary)
    }

    @Test fun `list preserves task names schedule and next time without fetching definitions`() {
        val data = AutomationToolPresentation(context("list", buildJsonArray {
            repeat(5) { i ->
                add(buildJsonObject {
                    put("name", "任务$i")
                    put("schedule", "每天 18:00")
                    put(
                        "enabled",
                        i != 0
                    )
                    put("next_run_at", 1000L + i)
                })
            }
        }))
        assertEquals("共 5 个任务", data.status)
        assertEquals(5, data.tasks.size)
        assertEquals(false, data.tasks.first().enabled)
        assertEquals(1004L, data.tasks.last().nextRunAt)
        assertTrue(data.tasks.all { it.definition == null })
    }

    @Test fun `reading an unsaved draft cannot look like a persisted task`() {
        val data = AutomationToolPresentation(context("get", buildJsonObject {
            definitionFields().forEach { (key, value) -> put(key, value) }
            put("draft", true)
        }))
        assertEquals("草稿待保存，尚未生效", data.status)
        assertEquals(definition.prompt, data.task?.definition?.prompt)
    }

    @Test fun `denial and execution errors do not report successful saves`() {
        val denied = AutomationToolPresentation(context(
            "save",
            buildJsonObject { put("error", "denied") },
            approval = ToolApprovalState.Denied("不保存"),
            fields = definitionFields()
        ))
        assertEquals("未批准，本次操作未执行", denied.status)
        assertFalse(denied.showSummary)
        val failed =
            AutomationToolPresentation(
                context("save", buildJsonObject { put("error", "版本已变化") }, fields = definitionFields())
            )
        assertEquals("操作失败", failed.status)
        assertEquals("版本已变化", failed.error)
    }

    @Test fun `history translates run states and retains the chat destination`() {
        val data = AutomationToolPresentation(context("history", buildJsonArray {
            add(buildJsonObject {
                put("state", "APPROVAL")
                put("due_at", 1000L)
                put("detail", "等待工具审批")
                put("conversation_id", "chat")
            })
        }))
        assertEquals("等待审批", data.runs.single().state)
        assertEquals("chat", data.runs.single().conversationId)
        assertEquals(1000L, data.runs.single().time)
    }

    @Test fun `parameter preparation stays compact and choices have human readable labels`() {
        val data = AutomationToolPresentation(context("options", buildJsonObject {
            put("models", buildJsonArray {
                add(buildJsonObject {
                    put("id", "hidden-id")
                    put("name", "模型名称")
                })
            })
        }, fields = buildJsonObject { put("include_models", true) }))
        assertEquals("查询可选模型", data.title)
        assertEquals(listOf("可选模型" to listOf("模型名称")), data.choices)
        assertFalse(data.showSummary)
    }

    @Test fun `incomplete or malformed tool fields do not crash the renderer`() {
        val data =
            AutomationToolPresentation(context("save", fields = buildJsonObject { put("definition", "invalid") }))
        assertNull(data.task)
        assertFalse(data.showSummary)
        val options = AutomationToolPresentation(context("options", buildJsonObject { put("models", "invalid") }))
        assertTrue(options.choices.isEmpty())
    }
}
