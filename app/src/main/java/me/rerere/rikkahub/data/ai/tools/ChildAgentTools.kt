package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation

val SUBAGENT_TOOL_NAMES = setOf(SPAWN_AGENT_TOOL_NAME, FOLLOWUP_AGENT_TOOL_NAME, POLL_AGENT_TOOL_NAME, CANCEL_AGENT_TOOL_NAME, LIST_AGENTS_TOOL_NAME)
private val CHILD_FORBIDDEN_TOOLS = SUBAGENT_TOOL_NAMES + setOf("ask_user", "grant_directory_access", "manage_skill", "manage_mcp_server", "scheduled_task", "todo_write")

internal fun childAssistant(assistant: Assistant, parent: Conversation): Assistant = assistant.copy(
    enableSubagents = false,
    modeInjectionIds = parent.modeInjectionIds.ifEmpty { assistant.modeInjectionIds },
    lorebookIds = parent.lorebookIds.ifEmpty { assistant.lorebookIds },
    // Resolve the parent's effective prompt before appending child execution constraints.
    allowConversationSystemPrompt = false,
    systemPrompt = buildString {
        append(if (assistant.allowConversationSystemPrompt && !parent.customSystemPrompt.isNullOrBlank()) parent.customSystemPrompt else assistant.systemPrompt)
        append("\n\nYou are a child agent. You cannot spawn or manage agents, ask the user, or modify shared MCP, skill or scheduled-task configuration. Tools requiring user approval must be delegated back to the parent. Produce files under /workspace/subagents/ and report their exact paths. Your input is self-contained; do not assume access to the parent's chat history.")
    },
)

/** The real prepared arguments, not an empty object, determine approval requirements. */
internal fun restrictChildTools(tools: List<Tool>): List<Tool> = tools
    .filter { it.name !in CHILD_FORBIDDEN_TOOLS }
    .map { tool ->
        tool.copy(
            needsApproval = { false },
            execute = { args ->
                check(!tool.needsApproval(args)) {
                    "Tool '${tool.name}' requires user approval. Ask the parent agent to perform it with approval."
                }
                tool.execute(args)
            },
        )
    }

/** Only adjacent independent child calls overlap. Ordinary operations retain their exact ordering. */
internal suspend fun <T, R> executeToolsInOrder(
    calls: List<T>,
    name: (T) -> String,
    execute: suspend (T) -> R,
): List<R> = buildList {
    var index = 0
    while (index < calls.size) {
        if (name(calls[index]) !in setOf(SPAWN_AGENT_TOOL_NAME, FOLLOWUP_AGENT_TOOL_NAME)) {
            add(execute(calls[index++]))
        } else {
            val start = index
            while (index < calls.size && name(calls[index]) in setOf(SPAWN_AGENT_TOOL_NAME, FOLLOWUP_AGENT_TOOL_NAME)) index++
            addAll(coroutineScope { calls.subList(start, index).map { call -> async { execute(call) } }.awaitAll() })
        }
    }
}
