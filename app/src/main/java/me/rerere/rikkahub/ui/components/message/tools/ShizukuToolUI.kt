@file:Suppress("ktlint:standard:max-line-length", "ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01

object ShizukuToolUI {
    val all: List<ToolUIRenderer> = listOf(
        Renderer("shizuku_exec", "执行 Shizuku 命令"),
        Renderer("shizuku_status", "检查 Shizuku 状态"),
        Renderer("shizuku_task_read", "查看 Shizuku 任务"),
        Renderer("shizuku_task_list", "列出 Shizuku 任务"),
        Renderer("shizuku_task_cancel", "停止 Shizuku 任务"),
        Renderer("shizuku_task_forget", "清理 Shizuku 任务"),
        Renderer("shizuku_output_read", "读取 Shizuku 输出"),
    )

    private class Renderer(override val toolName: String, private val label: String) : ToolUIRenderer {
        override fun icon(context: ToolUIContext) = HugeIcons.ComputerTerminal01

        @Composable
        override fun title(context: ToolUIContext): String = label
    }
}
