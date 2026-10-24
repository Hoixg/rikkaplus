@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01

object ShizukuToolUI : ToolUIRenderer {
    override val toolName = "shizuku_exec"
    override fun icon(context: ToolUIContext) = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String = "执行 Shizuku 命令"
}
