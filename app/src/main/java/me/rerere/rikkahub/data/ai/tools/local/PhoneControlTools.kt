package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.accessibility.PhoneControlBackend
import me.rerere.rikkahub.accessibility.PhoneControlConfig

val PHONE_ACTION_TOOLS = setOf("tap", "long_press", "swipe", "scroll", "click_node", "set_text", "global_action", "launch_app")
val PHONE_TOOL_NAMES = PHONE_ACTION_TOOLS + setOf("phone_status", "read_window_tree", "find_node", "take_screenshot", "list_installed_apps")

/** Existing app settings/approval adapter around RikkaHub Agent's tool factories. */
fun createPhoneControlTools(backend: PhoneControlBackend, config: () -> PhoneControlConfig): List<Tool> {
    fun requireEnabled() = check(config().enabled) { "AI phone control is disabled in Extensions > Phone Control" }
    val status = Tool(
        name = "phone_status",
        description = "Check phone-control permission, accessibility connection, lock state and screenshot support. Setup: Extensions > Phone Control; enable the system service and AI phone control.",
        parameters = { InputSchema.Obj(JsonObject(emptyMap())) },
        execute = { listOf(UIMessagePart.Text(backend.status().toString())) },
    )
    if (!config().enabled) return listOf(status)
    return listOf(status) + backend.tools().map { tool ->
        tool.copy(
            needsApproval = { tool.name in PHONE_ACTION_TOOLS && config().approvalRequired },
            prepareArguments = { input ->
                requireEnabled()
                if (tool.name in PHONE_ACTION_TOOLS) backend.prepare(input.jsonObject) else input
            },
            execute = { input ->
                requireEnabled()
                val result = backend.execute(tool, input.jsonObject)
                requireEnabled()
                result
            },
        )
    }
}
