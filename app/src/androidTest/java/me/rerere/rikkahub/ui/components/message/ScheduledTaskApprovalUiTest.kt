package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
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
import me.rerere.rikkahub.data.ai.tools.SCHEDULED_PREPARED_ARGUMENT
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
        put("model_name", "执行模型 A")
        put("model_provider", "服务商 B")
    }

    private fun call(action: String, before: JsonObject?, after: JsonObject) =
        UIMessagePart.Tool(action, SCHEDULED_TASK_TOOL_NAME, buildJsonObject {
                put("action", action)
                put(SCHEDULED_PREPARED_ARGUMENT, buildJsonObject {
                    put("before", before ?: JsonNull)
                    put("after", after)
                })
            }.toString(), approvalState = ToolApprovalState.Pending)

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
        compose.onNodeWithText("执行模型 A · 服务商 B").assertExists()
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

    @Test fun updateShowsOnlyTheNewPromptWithoutAComparisonButton() {
        val selected = call("update", config("任务", "旧提示词内容"), config("任务", "新提示词内容"))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    ChainOfThought(steps = listOf(selected)) { tool ->
                        ScheduledTaskApprovalStep(tool) { _, _, _, _ -> }
                    }
                }
            }
        }
        compose.onNodeWithText("新提示词内容").assertExists()
        compose.onNodeWithText("旧提示词内容").assertDoesNotExist()
        compose.onNodeWithText("对比旧提示词").assertDoesNotExist()
        compose.onNodeWithText("收起旧提示词").assertDoesNotExist()
    }

    @Test fun longPromptStaysWithinItsViewportAndScrollsBothDirections() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    ScheduledTaskPromptPreview((1..100).joinToString("\n") { "第 $it 行提示词内容" },
                        modifier = Modifier.testTag("prompt-scroll"))
                }
            }
        }
        val viewport = compose.onNodeWithTag("prompt-scroll")
        val maximumHeight = with(compose.density) { 240.dp.toPx() }
        assertTrue(viewport.fetchSemanticsNode().boundsInRoot.height <= maximumHeight + 1f)
        val range = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > 0f)
        val initial = range.value()
        viewport.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val scrolled = range.value()
        assertTrue(scrolled > initial)
        viewport.performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue(range.value() < scrolled)
        compose.onNodeWithText("上下滑动查看完整提示词").assertIsDisplayed()
    }

    @Test fun oldExpiredDeadlineDoesNotHideApprovalButtonsOrShowACountdown() {
        val selected = call("create", null, config("任务", "提示词")).copy(metadata = buildJsonObject {
            put(SCHEDULED_APPROVAL_METADATA, buildJsonObject {
                put("expires_at", 1L)
                put("timeout_ms", 60_000L)
            })
        })
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
        compose.onNodeWithText("待审批").assertIsDisplayed()
        compose.onNodeWithText("超时自动拒绝").assertDoesNotExist()
        compose.onNodeWithText("审批已超时，未执行").assertDoesNotExist()
        compose.onNodeWithText("批准").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, decision) }
    }
}
