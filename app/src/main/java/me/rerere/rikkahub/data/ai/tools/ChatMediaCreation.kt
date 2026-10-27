package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.await
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.data.ai.tools.local.ChatMediaReference
import me.rerere.rikkahub.data.ai.tools.local.PreparedMediaRequest
import me.rerere.rikkahub.data.ai.tools.local.awaitMediaCreationResult
import me.rerere.rikkahub.data.ai.tools.local.collectChatMediaReferences
import me.rerere.rikkahub.data.ai.tools.local.createMediaCreationTools
import me.rerere.rikkahub.data.ai.tools.local.validatePreparedMediaRequest
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.MediaCreationFiles
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationAssetType
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.data.remote.RemoteFileStore
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.service.MediaCreationSubmission
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/** Bridges chat tools to the current media pipeline. It owns no provider protocol or second generation history. */
class ChatMediaCreation(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val repository: MediaCreationRepository,
    private val service: MediaCreationService,
    private val remoteFileStore: RemoteFileStore,
    private val client: OkHttpClient,
) {
    private val submitMutex = Mutex()

    fun tools(conversationId: Uuid, getMessages: () -> List<UIMessage>): List<Tool> = createMediaCreationTools(
        getProviders = { settingsStore.settingsFlow.value.mediaGenerationProviders },
        getMessages = getMessages,
        submit = { submit(conversationId, it, getMessages) },
        read = { id -> read(conversationId, id) },
        cancel = { id ->
            record(conversationId, id)
            service.cancel(id)
            result(record(conversationId, id))
        },
    )

    private suspend fun submit(
        conversationId: Uuid,
        request: PreparedMediaRequest,
        getMessages: () -> List<UIMessage>,
    ): List<UIMessagePart> {
        val submitted = submitOnce(conversationId, request, getMessages)
        // Release the submission lock and staging files before waiting. Other chats can create media concurrently.
        return result(awaitMediaCreationResult(submitted, repository::observeRecord))
    }

    private suspend fun submitOnce(
        conversationId: Uuid,
        request: PreparedMediaRequest,
        getMessages: () -> List<UIMessage>,
    ): MediaCreationRecord = submitMutex.withLock {
        // If a chat was interrupted after submission, recover the record instead of billing a second request.
        repository.getRecord(request.recordId)?.let {
            require(it.sessionId == conversationId) { "任务不属于当前聊天" }
            return@withLock it
        }
        val (provider, model) = validatePreparedMediaRequest(
            request,
            settingsStore.awaitLoaded().mediaGenerationProviders,
            collectChatMediaReferences(getMessages()),
        )
        if (provider.capabilities(model.kind)?.requiresRemoteInputs == true && request.references.isNotEmpty()) {
            check(remoteFileStore.isConfigured) { "此视频模型需要上传参考素材，请先在媒体创作中配置素材上传存储" }
        }
        val temp = File(context.cacheDir, "media_tool/${request.recordId}")
        try {
            val assets = withContext(Dispatchers.IO) {
                request.references.zip(request.arguments.inputs) { reference, input ->
                    val file = materialize(reference, temp)
                    MediaCreationAsset(repository.relativePath(file), reference.type, input.role)
                }
            }
            validatePreparedMediaRequest(
                request,
                settingsStore.settingsFlow.value.mediaGenerationProviders,
                collectChatMediaReferences(getMessages())
            )
            currentCoroutineContext().ensureActive()
            // submit() runs in app scope; keep staging files until its input copies are complete even if chat stops.
            val created = withContext(NonCancellable) {
                val session = repository.getOrCreateChatSession(conversationId, request.arguments.modelId)
                service.submit(MediaCreationSubmission(
                    sessionId = session.id,
                    provider = provider,
                    model = model,
                    prompt = request.arguments.prompt,
                    params = request.arguments.params,
                    assets = assets,
                    recordId = request.recordId,
                ))
            }
            repository.getRecord(created.id) ?: created
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                temp.deleteRecursively()
            }
        }
    }

    private suspend fun record(conversationId: Uuid, id: Uuid): MediaCreationRecord =
        requireNotNull(repository.getRecord(id)) { "媒体记录不存在或已删除；不会自动重新提交" }.also {
            require(it.sessionId == conversationId) { "只能操作当前聊天创建的媒体任务" }
        }

    private suspend fun read(conversationId: Uuid, id: Uuid): List<UIMessagePart> {
        val initial = record(conversationId, id)
        return result(awaitMediaCreationResult(initial, repository::observeRecord))
    }

    private fun result(record: MediaCreationRecord): List<UIMessagePart> {
        val outputs = record.outputs.filter { repository.resolve(it.path).isFile }
        val json = buildJsonObject {
            put("record_id", record.id.toString())
            put("session_id", record.sessionId.toString())
            put("kind", record.kind.name.lowercase())
            put("status", record.status.name.lowercase())
            put("provider", record.providerName)
            put("model", record.modelId)
            put("output_count", outputs.size)
            put("outputs", JsonArray(outputs.map { output ->
                buildJsonObject {
                    put("uri", repository.resolve(output.path).toUri().toString())
                    put("mime_type", output.mimeType)
                    output.width?.let { put("width", it) }
                    output.height?.let { put("height", it) }
                    output.durationSeconds?.let { put("duration_seconds", it) }
                }
            }))
            record.error?.let { put("error", it) }
            if (outputs.size !=
                record.outputs.size) {
                put("error", "Some result files were deleted; only returned outputs are available.")
            }
            if (record.status.isActive) {
                put(
                    "message",
                    "Task submitted; the Media Creation card updates automatically. This is not a completed result."
                )
            }
            if (record.status ==
                MediaCreationStatus.CANCELLED) {
                put(
                    "message",
                    "Local waiting stopped. Remote work may continue; server cancellation or refund is not guaranteed."
                )
            }
        }
        return listOf(UIMessagePart.Text(json.toString())) + outputs.map { output ->
            val uri = repository.resolve(output.path).toUri().toString()
            if (output.isVideo) UIMessagePart.Video(uri) else UIMessagePart.Image(uri)
        }
    }

    /** Local references stay local until the existing media service decides whether its provider requires an upload. */
    private suspend fun materialize(reference: ChatMediaReference, dir: File): File {
        val uri = reference.url.toUri()
        val limit = if (reference.type == MediaCreationAssetType.IMAGE) 32L * 1024 * 1024 else 200L * 1024 * 1024
        val local = when (uri.scheme?.lowercase()) {
            "file", null -> File(if (uri.scheme == "file") requireNotNull(uri.path) else reference.url).also {
                check(it.isFile && it.length() in 1..limit) { "参考素材已删除、为空或过大" }
                require(
                    it.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath())
                ) { "参考素材不在应用素材目录" }
            }
            "content" -> {
                val mime = context.contentResolver.getType(uri)
                val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取参考素材" }
                input.use { stage(it, dir, mediaExtension(mime, uri.lastPathSegment), limit) }
            }
            "https", "http", "data" -> {
                dir.mkdirs()
                var target = File(dir, "${Uuid.random()}.bin")
                if (uri.scheme == "data") {
                    require(
                        reference.type == MediaCreationAssetType.IMAGE && reference.url.startsWith("data:image/") &&
                            reference.url.substringBefore(',').endsWith(";base64") &&
                            reference.url.length <= limit * 4 / 3 + 1024
                    ) { "无效的内嵌参考图片" }
                    target.writeBytes(Base64.decode(reference.url.substringAfter(',')))
                    check(target.length() in 1..limit) { "参考素材为空或过大" }
                } else {
                    client.newCall(Request.Builder().url(reference.url).build()).await().use { response ->
                        check(response.isSuccessful) { "参考素材下载失败：HTTP ${response.code}" }
                        check(response.body.contentLength() <= limit) { "参考素材过大" }
                        target = response.body.byteStream().use {
                            stage(it, dir, mediaExtension(response.header("Content-Type"), uri.lastPathSegment), limit)
                        }
                    }
                }
                target
            }
            else -> error("不支持此参考素材地址，请重新选择素材")
        }
        // Only transient submission paths point into cache; the shared service copies them into its record directory.
        val importedDir = File(dir, "inputs")
        importedDir.mkdirs()
        return if (reference.type == MediaCreationAssetType.IMAGE) {
            MediaCreationFiles.importImage(context, local.toUri(), importedDir)
        } else {
            local.copyTo(File(importedDir, "${Uuid.random()}.${mediaExtension(null, local.name)}")).also {
                require(MediaCreationFiles.videoInfo(it) != null && it.length() in 1..limit) { "无法读取参考视频或文件过大" }
            }
        }
    }

    private suspend fun stage(input: InputStream, dir: File, extension: String, limit: Long): File {
        dir.mkdirs()
        val target = File(dir, "${Uuid.random()}.$extension")
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val length = input.read(buffer)
                if (length == -1) break
                total += length
                check(total <= limit) { "参考素材过大" }
                output.write(buffer, 0, length)
            }
            check(total > 0) { "参考素材为空" }
        }
        return target
    }

    private fun mediaExtension(mime: String?, name: String?): String = when (mime?.substringBefore(';')?.lowercase()) {
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/mp4" -> "mp4"
        else -> name?.substringAfterLast('.')?.lowercase()?.takeIf { it in setOf("webm", "mov", "mp4") } ?: "mp4"
    }
}
