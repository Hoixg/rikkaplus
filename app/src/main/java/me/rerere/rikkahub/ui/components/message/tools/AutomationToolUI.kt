@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.rikkahub.data.automation.AutomationRepository
import me.rerere.rikkahub.data.automation.TaskDefinition
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.extensions.automation.modeLabel
import me.rerere.rikkahub.ui.pages.extensions.automation.taskModelLabel
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.compose.koinInject

object AutomationToolUI : ToolUIRenderer {
    override val toolName = "scheduled_task"
    override fun icon(context: ToolUIContext) = HugeIcons.Calendar03

    @Composable
    override fun title(context: ToolUIContext): String {
        val arguments = context.arguments as? JsonObject
        val includesConversations = (arguments?.get("include_conversations") as? JsonPrimitive)?.booleanOrNull == true
        val includesModels = (arguments?.get("include_models") as? JsonPrimitive)?.booleanOrNull == true
        return when (arguments.getStringContent("action")) {
            "create" -> "创建定时任务"
            "update" -> "修改定时任务"
            "delete" -> "删除定时任务"
            "set_enabled" -> "调整任务排程"
            "run" -> "立即执行任务"
            "cancel" -> "取消任务执行"
            "list" -> "查询任务列表"
            "get" -> "查看任务详情"
            "history" -> "查看任务运行记录"
            "options" -> when {
                arguments.getStringContent("target_conversation_id") != null -> "查询目标会话的用户消息"
                includesConversations && includesModels -> "查询任务可选配置"
                includesConversations -> "选择任务目标会话"
                includesModels -> "查询可选模型"
                else -> "准备任务操作参数"
            }
            else -> "定时任务操作"
        }
    }
    override fun hasSummary(context: ToolUIContext) = context.arguments.getStringContent("action") in
        setOf("create", "update", "delete", "run", "cancel", "set_enabled")

    @Composable
    override fun Summary(context: ToolUIContext) {
        if (!hasSummary(context)) return
        val repository = koinInject<AutomationRepository>()
        val conversations = koinInject<me.rerere.rikkahub.data.repository.ConversationRepository>()
        val settings = LocalSettings.current
        val args = context.arguments as? JsonObject ?: return
        val saved by produceState<TaskDefinition?>(null, args.getStringContent("id")) {
            value = args.getStringContent("id")?.let { repository.task(it)?.definition }
        }
        val definition = remember(args, saved, context.assistant?.id) {
            runCatching {
                args["definition"]?.let { JsonInstant.decodeFromJsonElement<TaskDefinition>(it) }
                    ?.copy(assistantId = context.assistant?.id?.toString().orEmpty())
            }.getOrNull() ?: saved
        }
        val followingModel by produceState<me.rerere.ai.provider.Model?>(null, definition?.conversationId, settings) {
            value = definition?.conversationId?.let { raw ->
                runCatching { conversations.getConversationById(kotlin.uuid.Uuid.parse(raw)) }.getOrNull()
            }?.let { settings.getChatModelOf(it) }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            definition?.let {
                Text(it.name, style = MaterialTheme.typography.titleSmall)
                Text("${modeLabel(it.mode)} · ${it.schedule.describe()}", style = MaterialTheme.typography.bodySmall)
                Text("执行模型：${taskModelLabel(it, settings, followingModel)}", style = MaterialTheme.typography.bodySmall)
                if (it.resetContextBeforeRun) {
                    Text(
                        "每次执行前删除目标聊天的旧消息、分支和压缩摘要，仅保留本轮记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (args.getStringContent("action") == "set_enabled") {
                    Text(if (args["enabled"]?.jsonPrimitive?.booleanOrNull ==
                        true) {
                        "启用后续排程"
                    } else {
                        "暂停后续排程"
                    }, style = MaterialTheme.typography.bodyMedium)
                }
                if (args.getStringContent("action") in setOf("create", "update") && it.prompt.isNotBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        SelectionContainer {
                            Text(it.prompt, Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                .verticalScroll(
                                    rememberScrollState()
                                ).padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        if (!hasSummary(context)) {
            DefaultToolPreview(context)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title(context), style = MaterialTheme.typography.headlineSmall)
                Summary(context)
                if (context.tool.isExecuted) {
                    context.tool.output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().forEach {
                        Text(it.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
