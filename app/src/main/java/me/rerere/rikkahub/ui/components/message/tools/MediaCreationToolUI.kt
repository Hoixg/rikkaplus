package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ImageToVideo
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_GENERATE_TOOL
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_TASK_CANCEL_TOOL
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_TASK_READ_TOOL
import me.rerere.rikkahub.data.ai.tools.local.PreparedMediaRequest
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.VideoPlayerDialog
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.mediacreation.MediaThumbnail
import me.rerere.rikkahub.ui.pages.mediacreation.label
import me.rerere.rikkahub.ui.pages.mediacreation.summary
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

object MediaCreationToolUI {
    val all: List<ToolUIRenderer> = listOf(MEDIA_GENERATE_TOOL, MEDIA_TASK_READ_TOOL, MEDIA_TASK_CANCEL_TOOL).map(::Renderer)

    private class Renderer(override val toolName: String) : ToolUIRenderer {
        override val rendersMediaOutputs = true
        override fun icon(context: ToolUIContext) = HugeIcons.ImageToVideo

        @Composable
        override fun title(context: ToolUIContext): String = when (toolName) {
            MEDIA_TASK_READ_TOOL -> "查看创作进度"
            MEDIA_TASK_CANCEL_TOOL -> "停止媒体生成等待"
            else -> if (context.arguments.getStringContent("kind") == "video") "生成视频" else "生成或编辑图片"
        }

        override fun hasSummary(context: ToolUIContext) = true

        @Composable
        override fun Summary(context: ToolUIContext) = MediaToolContent(context, detail = false)

        @Composable
        override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title(context), style = MaterialTheme.typography.headlineSmall)
                MediaToolContent(context, detail = true, onNavigate = onDismissRequest)
            }
        }
    }
}

/** Observe the original record, so a long video task updates even after the assistant has finished its reply. */
@Composable
private fun MediaToolContent(context: ToolUIContext, detail: Boolean, onNavigate: () -> Unit = {}) {
    val repository = koinInject<MediaCreationRepository>()
    val service = koinInject<MediaCreationService>()
    val navigator = LocalNavController.current
    val scope = rememberCoroutineScope()
    val prepared = remember(context.arguments) { runCatching { PreparedMediaRequest.fromJson(context.arguments) }.getOrNull() }
    val id = remember(context.arguments, context.content) {
        prepared?.recordId ?: (context.content.getStringContent("record_id") ?: context.arguments.getStringContent("record_id"))
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    }
    var loaded by remember(id) { mutableStateOf(false) }
    val record by produceState<MediaCreationRecord?>(null, id) {
        if (id != null) repository.observeRecord(id).collect { value = it; loaded = true }
        else loaded = true
    }
    var preview by remember(id) { mutableStateOf<Pair<File, Boolean>?>(null) }
    var cancelling by remember(id) { mutableStateOf(false) }
    var actionError by remember(id) { mutableStateOf<String?>(null) }
    val current = record
    val error = actionError ?: context.content.getStringContent("error") ?: current?.error
    val model = current?.modelId ?: prepared?.target?.modelName
    val provider = current?.providerName ?: prepared?.target?.providerName

    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            model?.let { Text(listOfNotNull(provider, model).joinToString(" · "), style = MaterialTheme.typography.titleSmall) }
            Text(
                text = current?.status?.label ?: when {
                    context.tool.isPending -> "等待确认"
                    context.loading -> "正在提交"
                    loaded && context.tool.isExecuted && error == null -> "创作记录已删除"
                    else -> "媒体创作"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            val prompt = current?.prompt ?: prepared?.arguments?.prompt ?: context.arguments.getStringContent("prompt")
            if (!detail && !prompt.isNullOrBlank()) Text(prompt, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (current != null) {
                val outputs = current.outputs.filter { repository.resolve(it.path).isFile }
                if (outputs.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(outputs, key = { it.path }) { output ->
                        val file = repository.resolve(output.path)
                        MediaThumbnail(file, output.isVideo,
                            modifier = Modifier.size(if (detail) 160.dp else 112.dp).clip(MaterialTheme.shapes.medium)
                                .clickable { preview = file to output.isVideo },
                            poster = output.posterPath?.let(repository::resolve),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        onNavigate()
                        navigator.navigate(Screen.MediaCreation(current.sessionId.toString())) { launchSingleTop = true }
                    }) { Text("打开媒体创作") }
                    if (current.status.isActive) TextButton(enabled = !cancelling, onClick = {
                        cancelling = true
                        scope.launch {
                            try { service.cancel(current.id) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { actionError = e.message ?: "停止等待失败" }
                            finally { cancelling = false }
                        }
                    }) { Text("停止等待") }
                }
                if (current.status.isActive) Text("离开聊天后会继续生成", style = MaterialTheme.typography.labelSmall)
            }

            if (detail && prepared != null) {
                CardGroup {
                    formItem(headlineContent = { Text("创作要求") }) {
                        SelectionContainer { Text(prompt.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
                    }
                    val kind = if (prepared.arguments.kind == "video") me.rerere.mediagen.model.MediaKind.VIDEO else me.rerere.mediagen.model.MediaKind.IMAGE
                    formItem(headlineContent = { Text("生成参数") }) {
                        val parameters = prepared.arguments.params.summary(kind)
                        Text(parameters.joinToString(" · ").ifEmpty { "使用接口默认值" }, style = MaterialTheme.typography.bodySmall)
                    }
                    if (prepared.references.isNotEmpty()) formItem(headlineContent = { Text("参考素材") }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(prepared.references.zip(prepared.arguments.inputs), key = { it.first.id }) { (reference, input) ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val uri = reference.url.toUri()
                                    val video = reference.type == me.rerere.rikkahub.data.model.MediaCreationAssetType.VIDEO
                                    if (video && uri.scheme == "file" && uri.path != null) {
                                        MediaThumbnail(File(requireNotNull(uri.path)), true,
                                            modifier = Modifier.size(112.dp).clip(MaterialTheme.shapes.medium)
                                                .clickable { preview = File(requireNotNull(uri.path)) to true })
                                    } else if (!video) {
                                        ZoomableAsyncImage(model = reference.url, contentDescription = input.role.label,
                                            modifier = Modifier.size(112.dp).clip(MaterialTheme.shapes.medium))
                                    } else Text("参考视频", style = MaterialTheme.typography.bodySmall)
                                    Text(input.role.label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (context.tool.isPending && context.tool.toolName == MEDIA_GENERATE_TOOL) {
                Text("确认后将使用所选供应商生成，可能产生费用", style = MaterialTheme.typography.labelSmall)
            }
            if (context.tool.toolName == MEDIA_TASK_CANCEL_TOOL || current?.status == me.rerere.rikkahub.data.model.MediaCreationStatus.CANCELLED) {
                Text("停止本地等待后，供应商任务可能仍在执行", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    preview?.let { (file, video) ->
        if (video) VideoPlayerDialog(file, onDismissRequest = { preview = null })
        else ImagePreviewDialog(images = listOf(file.absolutePath), onDismissRequest = { preview = null })
    }
}
