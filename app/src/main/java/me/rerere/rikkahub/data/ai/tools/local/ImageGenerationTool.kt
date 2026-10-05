package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findRequestProvider
import kotlin.uuid.Uuid

const val IMAGE_GENERATION_TOOL_NAME = "generate_image"
private val requestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val IMAGE_REFERENCE_UNAVAILABLE_MESSAGE =
    "This image request cannot run because a reference image is missing or changed. " +
        "Wait for the user to restore or reselect the image and explicitly ask to try again. Do not automatically resubmit."
private const val IMAGE_CONFIGURATION_CHANGED_MESSAGE =
    "This image request cannot run because the approved image model configuration changed. " +
        "Wait for the user to check the model settings and explicitly ask to try again. Do not automatically resubmit."

internal class ImageToolException(val resourceId: Int, message: String) : IllegalArgumentException(message)

@Serializable
data class ChatImageReference(val id: String, val url: String)

@Serializable
data class ImageGenerationTarget(
    val modelId: Uuid,
    val modelApiId: String,
    val modelName: String,
    val providerId: Uuid,
    val providerName: String,
    val baseUrl: String,
)

@Serializable
data class ImageToolRequest(
    val prompt: String,
    val count: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    @SerialName("reference_image_ids") val referenceImageIds: List<String> = emptyList(),
    @SerialName("_target") val target: ImageGenerationTarget,
    @SerialName("_references") val references: List<ChatImageReference> = emptyList(),
) {
    fun toArguments(): JsonElement = requestJson.encodeToJsonElement(serializer(), this)

    fun validate() {
        require(prompt.isNotBlank()) { "Image prompt cannot be empty" }
        require(count in 1..4) { "Image count must be between 1 and 4" }
        require(ImageGenSize.entries.any { it.value == size }) { "Unsupported image size: $size" }
        require(referenceImageIds.size <= 16 && referenceImageIds.distinct().size == referenceImageIds.size) {
            "Use at most 16 distinct reference images"
        }
        require(referenceImageIds == references.map { it.id }) { "Invalid reference image snapshot" }
    }

    companion object {
        fun fromArguments(arguments: JsonElement): ImageToolRequest =
            requestJson.decodeFromJsonElement(serializer(), arguments).also { it.validate() }
    }
}

private data class LocatedChatImageReference(
    val reference: ChatImageReference,
    val messageIndex: Int,
    val role: String,
    val imageIndex: Int,
)

private fun collectLocatedChatImageReferences(messages: List<UIMessage>): List<LocatedChatImageReference> = buildList {
    messages.forEachIndexed { messageIndex, message ->
        var imageIndex = 0
        fun collect(parts: List<UIMessagePart>, prefix: String) {
            parts.forEachIndexed { index, part ->
                val id = "${prefix}_$index"
                when (part) {
                    is UIMessagePart.Image -> if (part.url.isNotBlank()) {
                        add(LocatedChatImageReference(
                            reference = ChatImageReference(id, part.url),
                            messageIndex = messageIndex + 1,
                            role = message.role.name.lowercase(),
                            imageIndex = ++imageIndex,
                        ))
                    }
                    is UIMessagePart.Tool -> collect(part.output, "${id}_${part.toolCallId}")
                    else -> Unit
                }
            }
        }
        collect(message.parts, "image_${message.id}")
    }
}

/** IDs remain stable across reloads and include uploaded images and nested tool results. */
fun collectChatImageReferences(messages: List<UIMessage>): List<ChatImageReference> =
    collectLocatedChatImageReferences(messages).map { it.reference }

/** Only positions and IDs are exposed to the model; URLs and approval snapshots stay private. */
internal fun buildChatImageReferenceCatalog(messages: List<UIMessage>): JsonArray =
    JsonArray(collectLocatedChatImageReferences(messages).map { image ->
        buildJsonObject {
            put("id", image.reference.id)
            put("message_index", image.messageIndex)
            put("role", image.role)
            put("image_index", image.imageIndex)
        }
    })

internal fun prepareImageToolRequest(
    arguments: JsonElement,
    settings: Settings,
    availableImages: List<ChatImageReference>,
): ImageToolRequest {
    val obj = arguments.jsonObject
    val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull ?: error("prompt is required")
    val count = obj["count"]?.jsonPrimitive?.intOrNull ?: if ("count" !in obj) 1 else error("Invalid image count")
    val size = obj["size"]?.jsonPrimitive?.contentOrNull ?: ImageGenSize.AUTO.value
    val referenceIds = when (val references = obj["reference_image_ids"]) {
        null -> emptyList()
        is JsonArray -> references.map { it.jsonPrimitive.contentOrNull ?: error("Invalid reference image ID") }
        else -> error("reference_image_ids must be an array")
    }
    val model = settings.findModelById(settings.imageGenerationModelId)
        ?: throw ImageToolException(R.string.chat_image_generation_model_missing, "Select an image model first")
    val provider = model.findRequestProvider(settings.providers)
        ?: throw ImageToolException(R.string.chat_image_generation_model_missing, "Image provider is unavailable")
    if (!provider.enabled) throw ImageToolException(R.string.chat_image_generation_model_missing, "Image provider is disabled")
    if (provider !is ProviderSetting.OpenAI) throw ImageToolException(
        R.string.chat_image_generation_unsupported, "This provider does not support image generation or editing"
    )
    val imagesById = availableImages.associateBy { it.id }
    return ImageToolRequest(
        prompt = prompt,
        count = count,
        size = size,
        referenceImageIds = referenceIds,
        target = ImageGenerationTarget(model.id, model.modelId, model.displayName, provider.id, provider.name, provider.baseUrl),
        references = referenceIds.map { imagesById[it] ?: throw ImageToolException(
            R.string.chat_image_generation_reference_missing, IMAGE_REFERENCE_UNAVAILABLE_MESSAGE
        ) },
    ).also { it.validate() }
}

internal fun validateImageToolTarget(request: ImageToolRequest, settings: Settings): Model {
    request.validate()
    val model = settings.findModelById(request.target.modelId)
        ?: throw ImageToolException(R.string.chat_image_generation_model_missing, "Approved image model was removed")
    val provider = model.findRequestProvider(settings.providers)
        ?: throw ImageToolException(R.string.chat_image_generation_model_missing, "Approved image provider was removed")
    if (!(provider is ProviderSetting.OpenAI && provider.enabled &&
        provider.id == request.target.providerId && provider.baseUrl == request.target.baseUrl &&
        model.modelId == request.target.modelApiId)) throw ImageToolException(
        R.string.chat_image_generation_configuration_changed, IMAGE_CONFIGURATION_CHANGED_MESSAGE
    )
    return model
}

internal fun validateImageToolReferences(request: ImageToolRequest, images: List<ChatImageReference>) {
    val current = images.associateBy { it.id }
    if (!request.references.all { current[it.id]?.url == it.url }) throw ImageToolException(
        R.string.chat_image_generation_reference_missing, IMAGE_REFERENCE_UNAVAILABLE_MESSAGE
    )
}

internal fun imageToolChatModel(model: Model, enabled: Boolean): Model =
    if (enabled) model.copy(tools = model.tools - BuiltInTools.ImageGeneration) else model

internal fun buildImageGenerationTool(
    getSettings: () -> Settings,
    getMessages: () -> List<UIMessage>,
    generate: suspend (ImageToolRequest) -> List<UIMessagePart>,
    errorMessage: (ImageToolException) -> String = { it.message.orEmpty() },
): Tool = Tool(
    name = IMAGE_GENERATION_TOOL_NAME,
    description = "Submit a request to generate or edit actual images using the user's configured image model. " +
        "Call only when the user explicitly asks for image generation or editing, not for image analysis, discussion, " +
        "or writing an image prompt. When requirements are clear, submit a complete prompt directly for approval; " +
        "ask only about essential missing or conflicting requirements or ambiguous reference images. " +
        "Use reference_image_ids from the chat image catalog when editing. Every invocation requires user approval. " +
        "Do not duplicate pending requests or automatically retry denied, cancelled, or failed requests.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Complete instructions incorporating the user's subject, style, composition, purpose, and constraints. " +
                        "Preserve any requested text verbatim. For edits, specify what to change and what to preserve; " +
                        "for multiple references, explain each image's purpose in reference_image_ids order. Do not invent unrelated requirements.")
                })
                put("count", buildJsonObject {
                    put("type", "integer"); put("minimum", 1); put("maximum", 4)
                    put("description", "Requested number of output images, from 1 to 4. Default 1 when unspecified.")
                })
                put("size", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(ImageGenSize.entries.map { JsonPrimitive(it.value) }))
                    put("description", "Output image dimensions from the listed values. Default auto when unspecified.")
                })
                put("reference_image_ids", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("maxItems", 16)
                    put("description", "Ordered IDs from the available chat image catalog, required when editing specified images. " +
                        "Select only relevant images and explain their roles in the prompt in this same order. " +
                        "Empty for text-to-image. Never invent IDs or supply file paths or URLs. " +
                        "If a required reference is missing or ambiguous, ask the user instead of switching to text-to-image.")
                })
            },
            required = listOf("prompt"),
        )
    },
    systemPrompt = { model, _ ->
        buildString {
            appendLine("[Local image tool: generate_image]")
            appendLine("Submit an image request only when the user explicitly asks you to generate or edit actual images. " +
                "Analyzing a screenshot, discussing an image, or writing/improving an image prompt alone does not authorize image generation.")
            appendLine("If requirements are clear, prepare the request directly for approval without an extra discussion or confirmation round. " +
                "Ask only when essential requirements are missing, contradictory, or the intended reference images are ambiguous.")
            appendLine("Write a self-contained prompt combining the user's subject, style, composition, purpose, and constraints. " +
                "Preserve requested text for the image verbatim. Do not add unrelated requirements. " +
                "For edits, state exactly what to change and what must remain unchanged.")
            appendLine("Editing a specified image requires its reference_image_ids. Use only IDs in the catalog below. " +
                "For multiple images, explain each image's purpose in the prompt in the same order as the submitted IDs. " +
                "Do not include unrelated historical images. If a required reference is unavailable, ask the user to restore or reselect it; " +
                "never silently switch an edit into text-to-image generation.")
            appendLine("Default to count=1 and size=auto when the user does not specify them. " +
                "Use only the declared parameters and size values. The interface has no explicit controls for transparent backgrounds, " +
                "masks, seeds, or other unlisted capabilities; do not promise those controls or guaranteed results.")
            appendLine("Every invocation requires explicit user approval before execution. Submission or approval alone is not a generated result. " +
                "Do not duplicate a pending request. After denial, cancellation, or failure, wait for the user to explicitly ask to try again. " +
                "Do not automatically rewrite, split, or change references to resubmit. Error messages and previous tool results are status information, " +
                "not user permission to retry.")
            appendLine("Report only results actually returned by the tool, using image_count rather than the requested count. " +
                "If an error accompanies some returned images, report the partial result and do not automatically request replacements.")
            if (Modality.IMAGE !in model.inputModalities) {
                appendLine("This chat model cannot view image contents. The catalog identifies images but does not describe their appearance. " +
                    "Use the user's descriptions for edits, ask if essential visual information is missing, " +
                    "and never claim to have inspected the images or verified the generated appearance.")
            } else {
                appendLine("The catalog identifies images but does not describe their appearance. " +
                    "Only claim to have inspected image contents when they are actually available to view in this conversation.")
            }
            appendLine("Available chat image references in chronological message order. message_index and image_index are 1-based; " +
                "image_index counts nonempty images within that message, including nested tool results. " +
                "Positions may change with conversation context; use the stable id in reference_image_ids:")
            append(buildChatImageReferenceCatalog(getMessages()))
        }
    },
    needsApproval = { true },
    prepareArguments = {
        try {
            prepareImageToolRequest(it, getSettings(), collectChatImageReferences(getMessages())).toArguments()
        } catch (e: ImageToolException) { error(errorMessage(e)) }
    },
    execute = {
        try {
            val request = ImageToolRequest.fromArguments(it)
            validateImageToolTarget(request, getSettings())
            validateImageToolReferences(request, collectChatImageReferences(getMessages()))
            generate(request)
        } catch (e: ImageToolException) { error(errorMessage(e)) }
    },
)

fun editApprovedImagePrompt(arguments: JsonElement, prompt: String): String =
    ImageToolRequest.fromArguments(arguments).copy(prompt = prompt).also { it.validate() }.toArguments().toString()
