package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.model.MediaCreationAssetType
import me.rerere.rikkahub.data.model.MediaCreationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCreationToolsTest {
    private val openAI = MediaGenerationProviderSetting.OpenAI(apiKey = "private-api-key")
    private val volcano = MediaGenerationProviderSetting.Volcengine()
    private val minimax = MediaGenerationProviderSetting.MiniMax()
    private val providers = listOf(openAI, volcano, minimax)
    private val image = ChatMediaReference("ref-image", "file:///app/upload/photo.png", MediaCreationAssetType.IMAGE)
    private val last = image.copy(id = "ref-last", url = "file:///app/upload/last.png")
    private val video = ChatMediaReference("ref-video", "file:///app/upload/clip.mp4", MediaCreationAssetType.VIDEO)

    private fun arguments(
        provider: MediaGenerationProviderSetting = openAI,
        kind: MediaKind = MediaKind.IMAGE,
        params: MediaCreationParams = MediaCreationParams(),
        inputs: List<MediaToolInput> = emptyList(),
    ) = MediaToolArguments(
        kind.name.lowercase(),
        provider.models.first { it.kind == kind }.id,
        "保留产品主体，生成封面",
        params,
        inputs
    )

    private fun prepare(args: MediaToolArguments, refs: List<ChatMediaReference> = emptyList()) =
        prepareMediaToolRequest(mediaToolJson.encodeToJsonElement(args), providers, refs)

    @Test fun `image and video use separate configured media models`() {
        val img = prepare(arguments(params = MediaCreationParams(count = 2, resolution = "1536x1024")))
        val vid =
            prepare(arguments(volcano, MediaKind.VIDEO, MediaCreationParams(durationSeconds = 5, generateAudio = true)))
        assertEquals("image", img.arguments.kind)
        assertEquals("video", vid.arguments.kind)
        assertEquals(volcano.id, vid.target.providerId)
        assertFalse(img.toJson().toString().contains(openAI.apiKey))
        assertEquals(img, PreparedMediaRequest.fromJson(img.toJson()))
    }

    @Test fun `video accepts first and last frames without changing their roles`() {
        val request = prepare(arguments(volcano, MediaKind.VIDEO, inputs = listOf(
            MediaToolInput(image.id, ImageRole.FIRST_FRAME),
            MediaToolInput(last.id, ImageRole.LAST_FRAME),
        )), listOf(image, last))
        assertEquals(listOf(ImageRole.FIRST_FRAME, ImageRole.LAST_FRAME), request.arguments.inputs.map { it.role })
        assertEquals(listOf(image, last), request.references)
    }

    @Test fun `video accepts reference images and video together`() {
        val request =
            prepare(
                arguments(
                    volcano,
                    MediaKind.VIDEO,
                    inputs = listOf(MediaToolInput(image.id), MediaToolInput(video.id))
                ),
                listOf(image, video)
            )
        assertEquals(2, request.references.size)
    }

    @Test fun `frames cannot be silently mixed with reference video`() {
        assertThrows(IllegalArgumentException::class.java) {
            prepare(
                arguments(
                    volcano,
                    MediaKind.VIDEO,
                    inputs = listOf(MediaToolInput(image.id, ImageRole.FIRST_FRAME), MediaToolInput(video.id))
                ),
                listOf(image, video)
            )
        }
    }

    @Test fun `duplicate frame roles are rejected rather than dropping an image`() {
        assertThrows(IllegalArgumentException::class.java) {
            prepare(
                arguments(
                    volcano,
                    MediaKind.VIDEO,
                    inputs = listOf(
                        MediaToolInput(image.id, ImageRole.FIRST_FRAME),
                        MediaToolInput(last.id, ImageRole.FIRST_FRAME)
                    )
                ),
                listOf(image, last)
            )
        }
    }

    @Test fun `image models reject videos and frame roles`() {
        for (input in listOf(MediaToolInput(video.id), MediaToolInput(image.id, ImageRole.FIRST_FRAME))) {
            assertThrows(
                IllegalArgumentException::class.java
            ) { prepare(arguments(inputs = listOf(input)), listOf(image, video)) }
        }
    }

    @Test fun `unsupported or blank parameters are rejected rather than stripped`() {
        for (params in listOf(
            MediaCreationParams(durationSeconds = 5),
            MediaCreationParams(aspectRatio = "16:9"),
            MediaCreationParams(resolution = " ")
        )) {
            assertThrows(IllegalArgumentException::class.java) { prepare(arguments(params = params)) }
        }
    }

    @Test fun `video count cannot be smuggled through image parameter`() {
        assertThrows(
            IllegalArgumentException::class.java
        ) { prepare(arguments(volcano, MediaKind.VIDEO, MediaCreationParams(count = 4))) }
    }

    @Test fun `required video parameters must be explicit`() {
        assertThrows(IllegalArgumentException::class.java) { prepare(arguments(minimax, MediaKind.VIDEO)) }
        val params = MediaCreationParams(resolution = "768P", durationSeconds = 5, aspectRatio = "16:9")
        assertEquals(params, prepare(arguments(minimax, MediaKind.VIDEO, params)).arguments.params)
    }

    @Test fun `missing and repeated references never become text only generation`() {
        assertThrows(
            IllegalStateException::class.java
        ) { prepare(arguments(inputs = listOf(MediaToolInput("missing"))), listOf(image)) }
        assertThrows(
            IllegalArgumentException::class.java
        ) { prepare(arguments(inputs = listOf(MediaToolInput(image.id), MediaToolInput(image.id))), listOf(image)) }
    }

    @Test fun `model kind and configured id must match`() {
        assertThrows(IllegalArgumentException::class.java) { prepare(arguments().copy(kind = "video")) }
        assertThrows(
            IllegalArgumentException::class.java
        ) { prepare(arguments().copy(modelId = kotlin.uuid.Uuid.random())) }
    }

    @Test fun `provider endpoint credential and workspace changes invalidate approval`() {
        val request = prepare(arguments())
        for (changed in listOf(openAI.copy(baseUrl = "https://other.example/v1"), openAI.copy(apiKey = "changed"))) {
            assertThrows(
                IllegalArgumentException::class.java
            ) { validatePreparedMediaRequest(request, listOf(changed), emptyList()) }
        }
        val ali = MediaGenerationProviderSetting.Aliyun(workspaceId = "old")
        val approved =
            prepareMediaToolRequest(mediaToolJson.encodeToJsonElement(arguments(ali)), listOf(ali), emptyList())
        assertThrows(
            IllegalArgumentException::class.java
        ) { validatePreparedMediaRequest(approved, listOf(ali.copy(workspaceId = "new")), emptyList()) }
    }

    @Test fun `changed or removed reference invalidates approval`() {
        val request = prepare(arguments(inputs = listOf(MediaToolInput(image.id))), listOf(image))
        assertThrows(
            IllegalArgumentException::class.java
        ) { validatePreparedMediaRequest(request, providers, emptyList()) }
        assertThrows(
            IllegalArgumentException::class.java
        ) { validatePreparedMediaRequest(request, providers, listOf(image.copy(url = "file:///changed.png"))) }
        validatePreparedMediaRequest(request, providers, listOf(image))
    }

    @Test fun `model cannot choose internal record ids`() {
        val public = mediaToolJson.encodeToJsonElement(arguments()) as JsonObject
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            prepareMediaToolRequest(
                JsonObject(public + ("_record_id" to JsonPrimitive("forged"))),
                providers,
                emptyList()
            )
        }
    }

    @Test fun `references from nested tool results have stable ids across reloads`() {
        val message = UIMessage.user("edit").copy(parts = listOf(
            UIMessagePart.Image(image.url),
            UIMessagePart.Tool("call-1", MEDIA_TASK_READ_TOOL, "{}", output = listOf(UIMessagePart.Video(video.url))),
        ))
        val first = collectChatMediaReferences(listOf(message))
        val reloaded = mediaToolJson.decodeFromString<UIMessage>(
            mediaToolJson.encodeToString(UIMessage.serializer(), message)
        )
        assertEquals(first, collectChatMediaReferences(listOf(UIMessage.user("older"), reloaded)))
        assertEquals(listOf(MediaCreationAssetType.IMAGE, MediaCreationAssetType.VIDEO), first.map { it.type })
    }

    @Test fun `model catalog exposes both kinds without credentials or endpoints`() {
        val catalog = mediaModelCatalog(providers).toString()
        assertTrue(catalog.contains("image") && catalog.contains("video"))
        assertTrue(catalog.contains("durationSeconds") && catalog.contains("first_frame"))
        assertFalse(catalog.contains(openAI.apiKey) || catalog.contains(openAI.baseUrl))
    }

    @Test fun `preparing tools performs no generation`() = runTest {
        var submitted = 0
        val tools =
            createMediaCreationTools({ providers }, { emptyList() }, {
                submitted++
                emptyList()
            }, { emptyList() }, { emptyList() })
        assertEquals(
            setOf(MEDIA_GENERATE_TOOL, MEDIA_TASK_READ_TOOL, MEDIA_TASK_CANCEL_TOOL),
            tools.map { it.name }.toSet()
        )
        val tool = tools.first()
        val prepared = tool.prepareArguments(mediaToolJson.encodeToJsonElement(arguments()))
        assertTrue(tool.needsApproval(prepared))
        assertEquals(0, submitted)
        assertNotNull(PreparedMediaRequest.fromJson(prepared).recordId)
    }

    @Test fun `local media tools suppress duplicate native image generation only when enabled`() {
        val model = Model(tools = setOf(BuiltInTools.ImageGeneration, BuiltInTools.Search))
        assertEquals(model, mediaToolChatModel(model, false))
        assertEquals(setOf(BuiltInTools.Search), mediaToolChatModel(model, true).tools)
    }
}
