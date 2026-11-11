package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Earth
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.BROWSER_TOOL_NAME
import me.rerere.rikkahub.ui.context.LocalNavController

object BrowserToolUI : ToolUIRenderer {
    override val toolName = BROWSER_TOOL_NAME
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Earth

    @Composable
    override fun title(context: ToolUIContext): String = when (context.arguments.getStringContent("action")) {
        "open", "new_tab" -> "打开网页"
        "snapshot", "extract" -> "读取网页"
        "screenshot" -> "网页截图"
        "click" -> "点击网页元素"
        "type", "select_option", "press" -> "填写网页"
        "scroll" -> "滚动网页"
        "evaluate" -> "执行网页脚本"
        "upload" -> "上传网页文件"
        "list_tabs", "switch_tab", "close_tab" -> "管理浏览器标签"
        else -> "浏览器操作"
    }
    override fun hasSummary(context: ToolUIContext) = context.content.getStringContent("conversation_id") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val nav = LocalNavController.current
        val owner = context.content.getStringContent("conversation_id") ?: return
        val url = context.content.getStringContent("url").orEmpty()
        Column {
            if (url.isNotEmpty()) Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FilledTonalButton(onClick = {
                nav.navigate(
                    Screen.Browser(
                        owner,
                        context.content.getStringContent("workspace_id"),
                        context.content.getStringContent("tab_id"),
                        url
                    )
                )
            }) { Text("打开浏览器") }
        }
    }
}
