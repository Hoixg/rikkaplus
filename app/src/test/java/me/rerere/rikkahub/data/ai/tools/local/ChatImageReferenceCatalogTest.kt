package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatImageReferenceCatalogTest {
    private fun messages(): List<UIMessage> = listOf(
        UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("Instructions"))),
        UIMessage(
            id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("Use these pictures"),
                UIMessagePart.Image("file:///private/upload.png"),
                UIMessagePart.Image("   "),
                UIMessagePart.Image("https://images.example/private.png"),
            ),
        ),
        UIMessage(
            id = Uuid.parse("10000000-0000-0000-0000-000000000002"),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Image("content://private/direct-image"),
                UIMessagePart.Tool(
                    toolCallId = "outer",
                    toolName = IMAGE_GENERATION_TOOL_NAME,
                    input = """{"_target":{"baseUrl":"https://private-provider.example"},"prompt":"Private prompt"}""",
                    output = listOf(
                        UIMessagePart.Text("Private output text"),
                        UIMessagePart.Image("file:///private/generated.png"),
                        UIMessagePart.Tool(
                            toolCallId = "nested",
                            toolName = "another_tool",
                            input = "{}",
                            output = listOf(
                                UIMessagePart.Image(""),
                                UIMessagePart.Image("data:image/png;base64,cHJpdmF0ZQ=="),
                            ),
                        ),
                    ),
                    metadata = buildJsonObject { put("private_metadata", "private-value") },
                ),
            ),
        ),
    )

    @Test fun `catalog locates uploaded direct and nested images without changing legacy ids`() {
        val expected = Json.parseToJsonElement("""
            [
              {"id":"image_10000000-0000-0000-0000-000000000001_1","message_index":2,"role":"user","image_index":1},
              {"id":"image_10000000-0000-0000-0000-000000000001_3","message_index":2,"role":"user","image_index":2},
              {"id":"image_10000000-0000-0000-0000-000000000002_0","message_index":3,"role":"assistant","image_index":1},
              {"id":"image_10000000-0000-0000-0000-000000000002_1_outer_1","message_index":3,"role":"assistant","image_index":2},
              {"id":"image_10000000-0000-0000-0000-000000000002_1_outer_2_nested_1","message_index":3,"role":"assistant","image_index":3}
            ]
        """.trimIndent()) as JsonArray
        val messages = messages()
        assertEquals(expected, buildChatImageReferenceCatalog(messages))
        assertEquals(expected.map { it.jsonObject.getValue("id").jsonPrimitive.content },
            collectChatImageReferences(messages).map { it.id })
        assertEquals(listOf(
            "file:///private/upload.png", "https://images.example/private.png", "content://private/direct-image",
            "file:///private/generated.png", "data:image/png;base64,cHJpdmF0ZQ==",
        ), collectChatImageReferences(messages).map { it.url })
    }

    @Test fun `catalog exposes only identifiers and positions without paths content or snapshots`() {
        val catalog = buildChatImageReferenceCatalog(messages())
        catalog.forEach {
            assertEquals(setOf("id", "message_index", "role", "image_index"), it.jsonObject.keys)
        }
        val encoded = catalog.toString()
        listOf("file:", "https:", "content:", "data:", "private", "_target", "prompt", "metadata")
            .forEach { assertFalse("Unexpected information in image catalog: $it", encoded.contains(it)) }
    }

    @Test fun `reload preserves catalog and context shifts change positions without changing ids`() {
        val original = messages()
        val serializer = ListSerializer(UIMessage.serializer())
        val restored = Json.decodeFromString(serializer, Json.encodeToString(serializer, original))
        assertEquals(buildChatImageReferenceCatalog(original), buildChatImageReferenceCatalog(restored))
        assertEquals(collectChatImageReferences(original), collectChatImageReferences(restored))

        val shifted = buildChatImageReferenceCatalog(restored.drop(1))
        assertEquals(buildChatImageReferenceCatalog(original).map { it.jsonObject.getValue("id") },
            shifted.map { it.jsonObject.getValue("id") })
        assertEquals(listOf("1", "1", "2", "2", "2"),
            shifted.map { it.jsonObject.getValue("message_index").jsonPrimitive.content })
        assertEquals(listOf("1", "2", "1", "2", "3"),
            shifted.map { it.jsonObject.getValue("image_index").jsonPrimitive.content })
    }

    @Test fun `empty conversations and messages without usable images produce an empty catalog`() {
        assertEquals(JsonArray(emptyList()), buildChatImageReferenceCatalog(emptyList()))
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Text("Describe an image"),
            UIMessagePart.Image(" "),
            UIMessagePart.Tool("empty", "another_tool", "{}", listOf(UIMessagePart.Text("No image"))),
        )))
        assertEquals(JsonArray(emptyList()), buildChatImageReferenceCatalog(messages))
        assertTrue(collectChatImageReferences(messages).isEmpty())
    }
}
