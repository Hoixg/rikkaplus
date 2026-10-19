package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01

object TermuxToolUI {
    val all: List<ToolUIRenderer> = listOf(
        Renderer("termux_run_command", "执行 Termux 命令"),
        Renderer("termux_session_start", "启动 Termux 会话"),
        Renderer("termux_session_send", "输入 Termux 会话"),
        Renderer("termux_session_read", "读取 Termux 会话"),
        Renderer("termux_session_kill", "关闭 Termux 会话"),
        Renderer("termux_session_list", "查看 Termux 会话"),
    )

    private class Renderer(override val toolName: String, private val label: String) : ToolUIRenderer {
        override fun icon(context: ToolUIContext) = HugeIcons.ComputerTerminal01

        @Composable
        override fun title(context: ToolUIContext): String = label
    }
}
