package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R

object PhoneControlToolUI {
    val all: List<ToolUIRenderer> = listOf(
        Renderer("phone_status", R.string.phone_control_status_tool),
        Renderer("phone_screen", R.string.phone_control_screen_tool),
        Renderer("phone_action", R.string.phone_control_action_tool),
        Renderer("phone_apps", R.string.phone_control_apps_tool),
    )

    private class Renderer(override val toolName: String, private val label: Int) : ToolUIRenderer {
        override fun icon(context: ToolUIContext) = HugeIcons.SmartPhone01

        @Composable
        override fun title(context: ToolUIContext): String {
            val target = context.arguments.getStringContent("target_label")?.takeIf { it.isNotBlank() }
                ?: context.arguments.getStringContent("target_package")
                ?: context.arguments.getStringContent("package_name")
            return stringResource(label) + if (target.isNullOrBlank()) "" else " · $target"
        }
    }
}
