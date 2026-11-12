package me.rerere.rikkahub.ui.pages.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import me.rerere.rikkahub.browser.BrowserActivity
import me.rerere.rikkahub.browser.BrowserGesture
import me.rerere.rikkahub.browser.BrowserGestureKind
import me.rerere.rikkahub.browser.BrowserManager

@Composable
internal fun BrowserAgentStatus(
    visible: Boolean,
    paused: Boolean,
    activity: BrowserActivity?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = if (paused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 2.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!paused) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = if (paused) "已由你接管" else when (activity?.action) {
                        "open", "new_tab", "render", "reload", "back", "forward" -> "AI 正在打开网页"
                        "click" -> "AI 正在点击"
                        "type" -> "AI 正在输入"
                        "select_option" -> "AI 正在选择"
                        "press" -> "AI 正在按键"
                        "scroll" -> "AI 正在滚动"
                        "snapshot", "extract", "console" -> "AI 正在读取"
                        "switch_tab" -> "AI 正在切换标签"
                        "close_tab" -> "AI 正在关闭标签"
                        "handle_dialog" -> "AI 正在处理对话框"
                        "evaluate" -> "AI 正在运行脚本"
                        "screenshot" -> "AI 正在截图"
                        "wait" -> "AI 正在等待"
                        "upload" -> "AI 正在上传"
                        else -> "AI 操作中"
                    },
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onToggle) { Text(if (paused) "继续" else "接管") }
            }
        }
    }
}

/** A browser-local drawing layer. It never intercepts touch or appears in WebView screenshots. */
@Composable
internal fun browserGestureFeedback(manager: BrowserManager, tabId: String, modifier: Modifier = Modifier): Modifier {
    val lifecycleOwner = LocalLifecycleOwner.current
    var gesture by remember(manager, tabId) { mutableStateOf<BrowserGesture?>(null) }
    val animation = remember(manager, tabId) { Animatable(1f) }
    val color = MaterialTheme.colorScheme.primary
    LaunchedEffect(manager, tabId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                manager.gestures.filter { it.tabId == tabId }.collectLatest { event ->
                    gesture = event
                    animation.snapTo(0f)
                    animation.animateTo(1f, tween(event.motionMillis + 350, easing = LinearEasing))
                    gesture = null
                }
            } finally { gesture = null }
        }
    }
    return modifier.drawWithCache {
        val outline = Stroke(2.dp.toPx())
        onDrawWithContent {
            drawContent()
            val current = gesture ?: return@onDrawWithContent
            val elapsed = animation.value * (current.motionMillis + 350)
            val progress = (elapsed / current.motionMillis).coerceIn(0f, 1f)
            val alpha = (1f - (elapsed - current.motionMillis) / 350f).coerceIn(0f, 1f)
            val start = Offset(current.normalizedX(0f) * size.width, current.normalizedY(0f) * size.height)
            val head = Offset(current.normalizedX(progress) * size.width, current.normalizedY(progress) * size.height)
            if (current.kind == BrowserGestureKind.TAP) {
                val radius = 28.dp.toPx() * (0.4f + 0.6f * progress)
                drawCircle(color.copy(alpha = 0.18f * alpha), radius, start)
                drawCircle(color.copy(alpha = 0.65f * alpha), radius, start, style = outline)
            } else {
                drawLine(Color.Black.copy(alpha = 0.15f * alpha), start, head, 20.dp.toPx(), StrokeCap.Round)
                drawLine(color.copy(alpha = 0.35f * alpha), start, head, 16.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(Color.White.copy(alpha = alpha), 10.dp.toPx(), head)
            drawCircle(color.copy(alpha = alpha), 7.dp.toPx(), head)
        }
    }
}
