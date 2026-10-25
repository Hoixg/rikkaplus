package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_GENERATE_TOOL
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_TASK_CANCEL_TOOL
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_TASK_READ_TOOL
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

internal const val CHART_DISPLAY_TOOL_NAME = "chart_display"

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool 和 ServerTool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
        val showSummary: Boolean = true,
    ) : ThinkingStep

    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock

    /** 成功执行的 chart_display 工具调用, 在正文中以图表卡片展示 */
    data class ChartBlock(val tool: UIMessagePart.Tool, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning、客户端 Tool 和 ServerTool 会被分组到一个 ThinkingBlock 中
 * 成功执行的 chart_display 原地替换为 ChartBlock (会切断所在的 ThinkingBlock);
 * 生成中或失败的调用仍作为普通 ToolStep 展示
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()
    // Repeated polls observe the same live record. Keep every call, but show its card only at the latest step.
    val mediaRecordIds = map { (it as? UIMessagePart.Tool)?.mediaSummaryRecordId() }
    val lastMediaSummaryIndices = mutableMapOf<Uuid, Int>()
    mediaRecordIds.forEachIndexed { index, id -> if (id != null) lastMediaSummaryIndices[id] = index }

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                if (part.isSuccessfulChartDisplay()) {
                    flushThinkingSteps()
                    result.add(MessagePartBlock.ChartBlock(part, index))
                } else {
                    val showSummary = mediaRecordIds[index]?.let { lastMediaSummaryIndices[it] == index } ?: true
                    currentThinkingSteps.add(ThinkingStep.ToolStep(part, showSummary))
                }
            }

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

/** Unknown results, errors and approval requests keep their own summary rather than hiding useful information. */
private fun UIMessagePart.Tool.mediaSummaryRecordId(): Uuid? {
    if (toolName != MEDIA_GENERATE_TOOL && toolName != MEDIA_TASK_READ_TOOL &&
        toolName != MEDIA_TASK_CANCEL_TOOL) {
        return null
    }
    if (isPending || approvalState is ToolApprovalState.Denied) return null
    if (!isExecuted && toolName != MEDIA_TASK_READ_TOOL) return null

    val arguments = inputAsJson() as? JsonObject
    val inputKey = if (toolName == MEDIA_GENERATE_TOOL) "_record_id" else "record_id"
    val inputId = (arguments?.get(inputKey) as? JsonPrimitive)?.contentOrNull
        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    if (!isExecuted) return inputId

    val outputText = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val content = runCatching { JsonInstant.parseToJsonElement(outputText) }.getOrNull() as? JsonObject ?: return null
    if (content["error"]?.let { it != JsonNull } == true) return null
    val outputId = (content["record_id"] as? JsonPrimitive)?.contentOrNull
        ?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return null
    if (inputId != null && inputId != outputId) return null
    return outputId
}

private fun UIMessagePart.Tool.isSuccessfulChartDisplay(): Boolean {
    if (toolName != CHART_DISPLAY_TOOL_NAME || !isExecuted) return false
    val outputText = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val result = runCatching { JsonInstant.parseToJsonElement(outputText) }.getOrNull() as? JsonObject
    return (result?.get("success") as? JsonPrimitive)?.booleanOrNull == true
}
