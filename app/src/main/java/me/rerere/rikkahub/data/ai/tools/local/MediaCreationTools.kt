package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationParameter
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationAssetType
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.canSubmit
import me.rerere.rikkahub.data.model.supportedBy
import java.security.MessageDigest
import kotlin.uuid.Uuid

const val MEDIA_GENERATE_TOOL = "media_generate"
const val MEDIA_TASK_READ_TOOL = "media_task_read"
const val MEDIA_TASK_CANCEL_TOOL = "media_task_cancel"
internal val mediaToolJson = Json { encodeDefaults = true }

@Serializable
data class ChatMediaReference(val id: String, val url: String, val type: MediaCreationAssetType)

@Serializable
data class MediaToolInput(
    @SerialName("reference_id") val referenceId: String,
    val role: ImageRole = ImageRole.REFERENCE,
)

@Serializable
data class MediaToolArguments(
    val kind: String,
    @SerialName("model_id") val modelId: Uuid,
    val prompt: String,
    val params: MediaCreationParams = MediaCreationParams(),
    val inputs: List<MediaToolInput> = emptyList(),
)

@Serializable
data class MediaToolTarget(
    val providerId: Uuid,
    val providerName: String,
    val modelApiId: String,
    val modelName: String,
    val configurationHash: String,
)

/** No credentials are persisted with chat messages. IDs, parameters and references are frozen before approval. */
data class PreparedMediaRequest(
    val arguments: MediaToolArguments,
    val target: MediaToolTarget,
    val references: List<ChatMediaReference>,
    val recordId: Uuid = Uuid.random(),
) {
    fun toJson(): JsonObject = JsonObject(mediaToolJson.encodeToJsonElement(arguments).jsonObject + mapOf(
        "_target" to mediaToolJson.encodeToJsonElement(target),
        "_references" to mediaToolJson.encodeToJsonElement(references),
        "_record_id" to JsonPrimitive(recordId.toString()),
    ))

    companion object {
        fun fromJson(value: JsonElement): PreparedMediaRequest {
            val obj = value.jsonObject
            return PreparedMediaRequest(
                arguments = mediaToolJson.decodeFromJsonElement(JsonObject(obj.filterKeys { !it.startsWith("_") })),
                target = mediaToolJson.decodeFromJsonElement(obj.getValue("_target")),
                references = mediaToolJson.decodeFromJsonElement(obj.getValue("_references")),
                recordId = Uuid.parse(obj.getValue("_record_id").jsonPrimitive.content),
            )
        }
    }
}

/** Stable across reloads, including uploads and previous tool results. URLs are never part of the model catalog. */
fun collectChatMediaReferences(messages: List<UIMessage>): List<ChatMediaReference> = buildList {
    fun collect(parts: List<UIMessagePart>, prefix: String) {
        parts.forEachIndexed { index, part ->
            val id = "${prefix}_$index"
            when (part) {
                is UIMessagePart.Image -> if (part.url.isNotBlank()) add(ChatMediaReference(id, part.url, MediaCreationAssetType.IMAGE))
                is UIMessagePart.Video -> if (part.url.isNotBlank()) add(ChatMediaReference(id, part.url, MediaCreationAssetType.VIDEO))
                is UIMessagePart.Tool -> collect(part.output, "${id}_${part.toolCallId}")
                else -> Unit
            }
        }
    }
    messages.forEach { collect(it.parts, "media_${it.id}") }
}

private fun MediaGenerationProviderSetting.configurationHash(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(mediaToolJson.encodeToString<MediaGenerationProviderSetting>(copyProvider(models = emptyList())).toByteArray())
        .joinToString("") { "%02x".format(it) }

internal fun resolveMediaToolModel(
    arguments: MediaToolArguments,
    providers: List<MediaGenerationProviderSetting>,
): Pair<MediaGenerationProviderSetting, MediaGenerationModel> {
    val kind = when (arguments.kind) {
        "image" -> MediaKind.IMAGE
        "video" -> MediaKind.VIDEO
        else -> error("kind must be image or video")
    }
    val matches = providers.flatMap { provider ->
        provider.models.filter { it.id == arguments.modelId && it.kind == kind }.map { provider to it }
    }
    require(matches.size == 1) { "请选择媒体创作中已配置的对应图片或视频模型" }
    return matches.single().also { (provider, _) ->
        require(provider.capabilities(kind) != null) { "此供应商尚不支持该媒体类型" }
    }
}

internal fun validateMediaToolArguments(
    arguments: MediaToolArguments,
    provider: MediaGenerationProviderSetting,
    model: MediaGenerationModel,
    references: List<ChatMediaReference>,
) {
    val capabilities = requireNotNull(provider.capabilities(model.kind))
    require(arguments.prompt.length <= 32000) { "提示词过长" }
    require(arguments.inputs.size <= 16 && arguments.inputs.map { it.referenceId }.distinct().size == arguments.inputs.size) {
        "最多选择 16 个不重复的参考素材"
    }
    require(arguments.inputs.map { it.referenceId } == references.map { it.id }) { "参考素材缺失，请重新选择" }
    require(arguments.params == arguments.params.supportedBy(capabilities)) { "参数不受此适配器支持，请按模型目录中的参数提交" }
    arguments.params.count?.let { require(it in 1..4) { "图片数量须为 1–4" } }
    arguments.params.durationSeconds?.let { require(it == -1 || it in 1..300) { "视频时长须为 1–300 秒，或 -1 表示自动" } }
    arguments.params.resolution?.let { require(it.length <= 64) { "分辨率无效" } }
    arguments.params.aspectRatio?.let { require(it.length <= 32) { "比例无效" } }
    val required = capabilities.requiredParameters
    require(MediaGenerationParameter.RESOLUTION !in required || !arguments.params.resolution.isNullOrBlank()) { "此模型需要指定分辨率" }
    require(MediaGenerationParameter.DURATION !in required || arguments.params.durationSeconds != null) { "此模型需要指定时长" }
    require(MediaGenerationParameter.ASPECT_RATIO !in required || !arguments.params.aspectRatio.isNullOrBlank()) { "此模型需要指定比例" }
    val assets = arguments.inputs.zip(references) { input, reference ->
        when (reference.type) {
            MediaCreationAssetType.IMAGE -> require(input.role in capabilities.imageRoles) { "此模型不支持所选图片角色" }
            MediaCreationAssetType.VIDEO -> require(capabilities.videoInput && input.role == ImageRole.REFERENCE) { "此模型不支持参考视频或视频角色不正确" }
        }
        MediaCreationAsset(path = reference.url, type = reference.type, role = input.role)
    }
    require(assets == assets.supportedBy(capabilities)) { "首帧和尾帧各只能选择一张" }
    require(MediaCreationDraft(prompt = arguments.prompt, assets = assets).canSubmit(capabilities)) {
        "请填写提示词，并按模型要求分开使用首尾帧和参考素材"
    }
}

internal fun prepareMediaToolRequest(
    value: JsonElement,
    providers: List<MediaGenerationProviderSetting>,
    available: List<ChatMediaReference>,
): PreparedMediaRequest {
    require(value.jsonObject.keys.none { it.startsWith("_") }) { "内部请求字段由应用填写" }
    val arguments = mediaToolJson.decodeFromJsonElement<MediaToolArguments>(value)
    val (provider, model) = resolveMediaToolModel(arguments, providers)
    val byId = available.associateBy { it.id }
    val references = arguments.inputs.map { byId[it.referenceId] ?: error("参考素材已不存在，请重新选择；不会自动改为纯文本生成") }
    validateMediaToolArguments(arguments, provider, model, references)
    return PreparedMediaRequest(arguments, MediaToolTarget(
        provider.id, provider.name, model.modelId, model.displayName.ifBlank { model.modelId }, provider.configurationHash(),
    ), references)
}

internal fun validatePreparedMediaRequest(
    request: PreparedMediaRequest,
    providers: List<MediaGenerationProviderSetting>,
    available: List<ChatMediaReference>,
): Pair<MediaGenerationProviderSetting, MediaGenerationModel> {
    val (provider, model) = resolveMediaToolModel(request.arguments, providers)
    require(provider.id == request.target.providerId && model.modelId == request.target.modelApiId &&
        provider.configurationHash() == request.target.configurationHash) { "审批后模型配置已改变，请检查设置后重新发起" }
    val current = available.associateBy { it.id }
    require(request.references.all { current[it.id] == it }) { "审批后参考素材已改变，请重新选择后发起" }
    validateMediaToolArguments(request.arguments, provider, model, request.references)
    return provider to model
}

internal fun mediaToolChatModel(model: Model, enabled: Boolean): Model =
    if (enabled) model.copy(tools = model.tools - BuiltInTools.ImageGeneration) else model

internal fun mediaModelCatalog(providers: List<MediaGenerationProviderSetting>): JsonArray = JsonArray(providers.flatMap { provider ->
    provider.models.mapNotNull { model ->
        val capabilities = provider.capabilities(model.kind) ?: return@mapNotNull null
        buildJsonObject {
            put("model_id", model.id.toString())
            put("model", model.displayName.ifBlank { model.modelId })
            put("provider", provider.name)
            put("kind", model.kind.name.lowercase())
            put("parameters", JsonArray(capabilities.parameters.map { JsonPrimitive(it.toolParameterName()) }))
            put("required_parameters", JsonArray(capabilities.requiredParameters.map { JsonPrimitive(it.toolParameterName()) }))
            put("image_roles", JsonArray(capabilities.imageRoles.map { JsonPrimitive(it.name.lowercase()) }))
            put("reference_video", capabilities.videoInput)
            put("frames_exclude_references", capabilities.framesExcludeReferences)
            put("remote_inputs_required", capabilities.requiresRemoteInputs)
        }
    }
})

private fun MediaGenerationParameter.toolParameterName(): String = when (this) {
    MediaGenerationParameter.COUNT -> "count"
    MediaGenerationParameter.RESOLUTION -> "resolution"
    MediaGenerationParameter.ASPECT_RATIO -> "aspectRatio"
    MediaGenerationParameter.DURATION -> "durationSeconds"
    MediaGenerationParameter.GENERATE_AUDIO -> "generateAudio"
    MediaGenerationParameter.WATERMARK -> "watermark"
    MediaGenerationParameter.SEED -> "seed"
    MediaGenerationParameter.PROMPT_ENHANCEMENT -> "promptEnhancement"
}

private fun mediaGenerateSchema() = InputSchema.Obj(
    properties = buildJsonObject {
        put("kind", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("image", "video").map(::JsonPrimitive))) })
        put("model_id", buildJsonObject { put("type", "string"); put("description", "Exact model_id from the configured media model catalog; never a chat model ID.") })
        put("prompt", buildJsonObject { put("type", "string"); put("description", "Complete generation/edit instructions. Preserve requested wording and describe each reference's purpose in input order.") })
        put("params", buildJsonObject {
            put("type", "object")
            put("description", "Only parameters listed for this model. Omit unspecified optional values; no silently dropped parameters. Pixel size and quality tiers depend on the selected model.")
            put("properties", buildJsonObject {
                for (name in listOf("resolution", "aspectRatio")) put(name, buildJsonObject { put("type", "string") })
                for (name in listOf("count", "durationSeconds", "seed")) put(name, buildJsonObject { put("type", "integer") })
                for (name in listOf("generateAudio", "watermark", "promptEnhancement")) put(name, buildJsonObject { put("type", "boolean") })
            })
        })
        put("inputs", buildJsonObject {
            put("type", "array"); put("maxItems", 16)
            put("description", "Ordered chat reference IDs. Images may be reference, first_frame, or last_frame if supported; videos must use reference. Never supply paths or URLs. Empty for text-only generation.")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("reference_id", buildJsonObject { put("type", "string") })
                    put("role", buildJsonObject { put("type", "string"); put("enum", JsonArray(ImageRole.entries.map { JsonPrimitive(it.name.lowercase()) })) })
                })
                put("required", JsonArray(listOf(JsonPrimitive("reference_id"))))
            })
        })
    },
    required = listOf("kind", "model_id", "prompt"),
)

internal fun createMediaCreationTools(
    getProviders: () -> List<MediaGenerationProviderSetting>,
    getMessages: () -> List<UIMessage>,
    submit: suspend (PreparedMediaRequest) -> List<UIMessagePart>,
    read: suspend (Uuid) -> List<UIMessagePart>,
    cancel: suspend (Uuid) -> List<UIMessagePart>,
): List<Tool> {
    fun taskId(value: JsonElement): Uuid {
        val arguments = value.jsonObject
        require(arguments.keys == setOf("record_id")) { "媒体任务工具只接受 record_id 参数" }
        return Uuid.parse(arguments.getValue("record_id").jsonPrimitive.content)
    }
    fun taskSchema() = InputSchema.Obj(buildJsonObject {
        put("record_id", buildJsonObject { put("type", "string"); put("description", "Record ID returned by this chat's media_generate, including its prepared request ID.") })
    }, listOf("record_id"))
    return listOf(
        Tool(
            name = MEDIA_GENERATE_TOOL,
            description = "Generate/edit images or generate videos through the configured Media Creation providers. Supports reference images/videos and first/last frames according to the selected model. Requires approval. Submits once, then waits locally until generation and output downloads finish, or the task fails or is cancelled. Returns the final record and available outputs in this same call. Do not poll, duplicate or automatically retry requests.",
            parameters = ::mediaGenerateSchema,
            systemPrompt = { model, messages -> buildString {
                appendLine("[Media Creation: images and videos]")
                appendLine("Use media_generate only when the user asks to produce/edit actual media. Analysis, discussion and prompt writing alone do not request generation. Build a complete prompt directly when clear. Select kind and model_id from this catalog; do not use legacy chat image models. Ask only about essential ambiguous requirements or references.")
                appendLine("Only pass supported parameters, including all required ones. For edits and image-to-video, select the intended chat reference IDs explicitly; never replace missing references with text-only generation. First/last frames cannot be mixed with reference images/videos when frames_exclude_references is true. Reference inputs for video may need the user's configured upload storage.")
                appendLine("media_generate waits inside the app until generation and output downloads finish, or the task fails or is cancelled. Progress is shown by the live UI card without extra model requests. The same tool call returns the final record and outputs; after success use that result directly, without calling media_task_read to confirm it. Never implement polling loops, timed status checks or repeated reads to wait for a task. Use media_task_read only to retrieve an earlier task's outputs or resume waiting for an existing task; an active task waits locally until its final result, with no wait_ms parameter. Failed, denied, cancelled or unknown outcomes must not trigger automatic resubmission. Stop waiting with media_task_cancel only when requested; it does not guarantee server cancellation or refunds.")
                appendLine("The model catalog describes adapter capabilities, not every model's accepted parameter values. Unspecified optional parameters use service defaults. Never promise unsupported controls, inspect media you cannot view, or claim completion without successful outputs.")
                appendLine("This chat model accepts: ${model.inputModalities.joinToString { it.name.lowercase() }}.")
                appendLine("Configured media models:")
                appendLine(mediaModelCatalog(getProviders()))
                appendLine("Available chat references in chronological order. message_index and media_index are 1-based. The catalog gives positions and IDs, not appearance:")
                append(JsonArray(messages.flatMapIndexed { messageIndex, message ->
                    collectChatMediaReferences(listOf(message)).mapIndexed { mediaIndex, reference -> buildJsonObject {
                        put("reference_id", reference.id)
                        put("kind", reference.type.name.lowercase())
                        put("message_index", messageIndex + 1)
                        put("role", message.role.name.lowercase())
                        put("media_index", mediaIndex + 1)
                    } }
                }))
            } },
            needsApproval = { true },
            prepareArguments = { prepareMediaToolRequest(it, getProviders(), collectChatMediaReferences(getMessages())).toJson() },
            execute = { submit(PreparedMediaRequest.fromJson(it)) },
        ),
        Tool(
            name = MEDIA_TASK_READ_TOOL,
            description = "Retrieve the final record and actual outputs of an existing task in this chat. If it is active, wait locally until generation and downloads finish, or it fails or is cancelled. Completed tasks return immediately. Use for earlier results or interrupted waits, not for polling or confirming a just-completed media_generate. Never cancels or resubmits the task.",
            parameters = { taskSchema() },
            execute = { read(taskId(it)) },
        ),
        Tool(
            name = MEDIA_TASK_CANCEL_TOOL,
            description = "Stop this chat's local media generation/waiting and retain its history. A remote provider task may continue and may still incur charges. Never claims a server-side cancellation or refund.",
            parameters = { taskSchema() },
            needsApproval = { true },
            execute = { cancel(taskId(it)) },
        ),
    )
}
