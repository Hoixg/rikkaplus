package me.rerere.rikkahub.ui.components.message.tools

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.BROWSER_TOOL_NAME
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon

object BrowserToolUI : ToolUIRenderer {
    private val cardActions = setOf("open", "new_tab", "render", "switch_tab")
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
    override fun hasSummary(context: ToolUIContext) =
        context.content.getStringContent("conversation_id") != null &&
            context.arguments.getStringContent("action") in cardActions

    @Composable
    override fun Summary(context: ToolUIContext) {
        val nav = LocalNavController.current
        val owner = context.content.getStringContent("conversation_id") ?: return
        val url = context.content.getStringContent("url").orEmpty()
        val host = when {
            url.startsWith("rikkaplus://workspace/") -> "工作区网页"
            else -> Uri.parse(url).host.orEmpty().removePrefix("www.")
        }
        val title = context.content.getStringContent("title")?.takeIf { it.isNotBlank() }
            ?: context.content?.jsonObjectOrNull?.get("page").getStringContent("title")?.takeIf { it.isNotBlank() }
            ?: host.ifBlank { if (context.arguments.getStringContent("action") == "render") "临时网页" else "浏览器标签" }
        Surface(
            onClick = {
                nav.navigate(
                    Screen.Browser(
                        owner,
                        context.content.getStringContent("workspace_id"),
                        context.content.getStringContent("tab_id"),
                        url
                    )
                )
            },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CardGroupIcon(HugeIcons.Earth)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmallEmphasized)
                    if (host.isNotBlank() && host != title) Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(HugeIcons.ArrowRight01, contentDescription = "查看网页", modifier = Modifier.size(20.dp))
            }
        }
    }
}
