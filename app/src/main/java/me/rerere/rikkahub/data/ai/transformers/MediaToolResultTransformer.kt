package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_GENERATE_TOOL
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_TASK_CANCEL_TOOL
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_TASK_READ_TOOL

object MediaToolResultTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        mediaToolRequestMessages(messages, Modality.IMAGE in ctx.model.inputModalities)
}

/** Keep approval snapshots in storage/UI and avoid sending unsupported media to text models. */
internal fun mediaToolRequestMessages(
    messages: List<UIMessage>,
    supportsImages: Boolean
): List<UIMessage> = messages.map { message ->
    message.copy(parts = message.parts.map { part ->
        if (part !is UIMessagePart.Tool ||
            part.toolName !in setOf(MEDIA_GENERATE_TOOL, MEDIA_TASK_READ_TOOL, MEDIA_TASK_CANCEL_TOOL)) {
            part
        } else {
            part.copy(
                input =
                (part.inputAsJson() as? JsonObject)?.let {
                    JsonObject(
                        it.filterKeys { key -> !key.startsWith("_") }
                    ).toString()
                }
                    ?: part.input,
                output = part.output.map { output ->
                    when {
                        output is UIMessagePart.Video -> UIMessagePart.Text(
                            "[A video result is available in Media Creation; this chat model cannot view its content.]"
                        )
                        output is UIMessagePart.Image && !supportsImages -> UIMessagePart.Text(
                            "[An image result is displayed to the user; this chat model cannot view its content.]"
                        )
                        else -> output
                    }
                },
            )
        }
    })
}
