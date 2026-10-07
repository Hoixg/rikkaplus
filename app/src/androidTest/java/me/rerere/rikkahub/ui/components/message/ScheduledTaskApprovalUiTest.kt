package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.SCHEDULED_APPROVAL_METADATA
import me.rerere.rikkahub.data.ai.tools.SCHEDULED_TASK_TOOL_NAME
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledTaskApprovalUiTest {
    @get:Rule val compose = createComposeRule()

    private fun config(name: String, prompt: String) = buildJsonObject {
        put("name", name)
        put("prompt", prompt)
        put("schedule_type", "ONCE")
        put("trigger_at", "2026-10-09 10:00")
        put("mode", "NEW_CHAT")
        put("enabled", true)
        put("notify", true)
        put("show_preview", true)
    }

    private fun call(action: String, before: JsonObject?, after: JsonObject) =
        UIMessagePart.Tool(action, SCHEDULED_TASK_TOOL_NAME, """{"action":"$action"}""",
            approvalState = ToolApprovalState.Pending,
            metadata = buildJsonObject {
                put(SCHEDULED_APPROVAL_METADATA, buildJsonObject {
                    put("before", before ?: JsonNull)
                    put("after", after)
                    put("timeout_ms", 60_000L)
                    put("expires_at", System.currentTimeMillis() + 60_000L)
                })
            })

    @Test fun threeActionsKeepButtonsVisibleAboveLongPromptOnNarrowCard() {
        val old = config("旧任务", "旧提示词")
        val updated = config("新任务", "很长的提示词。".repeat(80))
        var selected by mutableStateOf(call("create", null, updated))
        var decision: Boolean? = null
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    ChainOfThought(steps = listOf(selected)) { tool ->
                        ScheduledTaskApprovalStep(tool) { _, approved, _, _ -> decision = approved }
                    }
                }
            }
        }
        compose.onNodeWithText("创建定时任务").assertIsDisplayed()
        compose.onNodeWithText("仅执行一次：2026-10-09 10:00").assertExists()
        compose.onNodeWithText("很长的提示词。".repeat(80)).assertExists()
        compose.onNodeWithText("执行模式").assertDoesNotExist()
        compose.onNodeWithText("通知").assertDoesNotExist()
        assertTrue(
            compose.onNodeWithText("批准").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithText("很长的提示词。".repeat(80)).fetchSemanticsNode().boundsInRoot.top
        )
        compose.onNodeWithText("批准").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, decision); selected = call("update", old, updated) }

        compose.onNodeWithText("修改定时任务").assertIsDisplayed()
        compose.onNodeWithText("旧任务 → 新任务").assertExists()
        compose.onNodeWithText("拒绝").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(false, decision); selected = call("run_now", updated, updated) }

        compose.onNodeWithText("立即执行定时任务").assertIsDisplayed()
        compose.onNodeWithText("批准").assertIsDisplayed()
        compose.onNodeWithText("仅执行一次：2026-10-09 10:00").assertExists()
        compose.onNodeWithText("内容预览").assertDoesNotExist()
    }
}
