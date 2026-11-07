package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.PHONE_TOOL_NAMES

object PhoneControlToolUI {
    val all: List<ToolUIRenderer> = PHONE_TOOL_NAMES.map { Renderer(it) }
    private class Renderer(override val toolName: String) : ToolUIRenderer {
        override fun icon(context: ToolUIContext) = HugeIcons.SmartPhone01

        @Composable
        override fun title(context: ToolUIContext): String {
            val label = when (toolName) {
                "phone_status" -> R.string.phone_control_status_tool
                "read_window_tree", "find_node", "take_screenshot" -> R.string.phone_control_screen_tool
                "launch_app", "list_installed_apps" -> R.string.phone_control_apps_tool
                else -> R.string.phone_control_action_tool
            }
            val target =
                context.arguments.getStringContent("value") ?: context.arguments.getStringContent("target_package")
                    ?: context.arguments.getStringContent("package_name")
            return stringResource(label) + if (target.isNullOrBlank()) "" else " · $target"
        }
    }
}
