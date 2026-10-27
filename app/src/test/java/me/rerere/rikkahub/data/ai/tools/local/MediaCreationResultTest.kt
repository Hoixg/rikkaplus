package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.model.MediaCreationOutput
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MediaCreationResultTest {
    private val provider = MediaGenerationProviderSetting.OpenAI()
    private val sessionId = Uuid.random()

    private fun record(kind: MediaKind = MediaKind.IMAGE, id: Uuid = Uuid.random()) = MediaCreationRecord(
        id = id,
        sessionId = sessionId,
        providerId = provider.id,
        providerName = provider.name,
        modelId = "test-media-model",
        kind = kind,
        prompt = "create media",
        status = MediaCreationStatus.RUNNING,
    )

    private fun tools(
        submit: suspend (PreparedMediaRequest) -> List<UIMessagePart> = { error("Unexpected resubmission") },
        read: suspend (Uuid) -> List<UIMessagePart> = { error("Unexpected result query") },
    ) = createMediaCreationTools(
        { listOf(provider) },
        { emptyList() },
        submit,
        read,
        { error("Unexpected cancellation") }
    )

    @Test
    fun `images and videos wait through generation and downloads without twenty second returns`() = runTest {
        for (kind in listOf(MediaKind.IMAGE, MediaKind.VIDEO)) {
            val initial = record(kind).copy(status = MediaCreationStatus.PREPARING)
            val state = MutableStateFlow<MediaCreationRecord?>(initial)
            val pending = async { awaitMediaCreationResult(initial) { state } }
            runCurrent()

            for (status in listOf(
                MediaCreationStatus.PREPARING,
                MediaCreationStatus.QUEUED,
                MediaCreationStatus.RUNNING,
                MediaCreationStatus.DOWNLOADING
            )) {
                state.value = initial.copy(status = status)
                advanceTimeBy(65_000)
                runCurrent()
                assertFalse(status.name, pending.isCompleted)
                assertEquals(1, state.subscriptionCount.value)
            }

            val completed = initial.copy(
                status = MediaCreationStatus.SUCCEEDED,
                outputs = listOf(MediaCreationOutput("result", if (kind ==
                    MediaKind.IMAGE) {
                    "image/png"
                } else {
                    "video/mp4"
                })),
            )
            state.value = completed
            assertSame(completed, pending.await())
            assertEquals(0, state.subscriptionCount.value)
        }
    }

    @Test
    fun `failure and task cancellation finish the wait with their actual result`() = runTest {
        for (status in listOf(MediaCreationStatus.FAILED, MediaCreationStatus.CANCELLED)) {
            val initial = record()
            val state = MutableStateFlow<MediaCreationRecord?>(initial)
            val pending = async { awaitMediaCreationResult(initial) { state } }
            runCurrent()

            val terminal = initial.copy(status = status, error = "provider outcome")
            state.value = terminal

            assertSame(terminal, pending.await())
            assertEquals(0, state.subscriptionCount.value)
        }
    }

    @Test
    fun `restored terminal records return immediately without subscribing or resubmitting`() = runTest {
        for (status in listOf(
            MediaCreationStatus.SUCCEEDED,
            MediaCreationStatus.FAILED,
            MediaCreationStatus.CANCELLED
        )) {
            val terminal = record().copy(status = status)
            assertSame(terminal, awaitMediaCreationResult(terminal) { error("Terminal records need no wait") })
        }
    }

    @Test
    fun `stopping the chat wait leaves the background task available to resume`() = runTest {
        val initial = record()
        val state = MutableStateFlow<MediaCreationRecord?>(initial)
        val pending = async { awaitMediaCreationResult(initial) { state } }
        runCurrent()

        pending.cancelAndJoin()

        assertTrue(pending.isCancelled)
        assertSame(initial, state.value)
        assertEquals(0, state.subscriptionCount.value)
        val completed = initial.copy(status = MediaCreationStatus.SUCCEEDED)
        state.value = completed
        assertSame(completed, awaitMediaCreationResult(initial) { state })
    }

    @Test
    fun `deleting a record ends the wait with an error instead of recreating it`() = runTest {
        val initial = record()
        val state = MutableStateFlow<MediaCreationRecord?>(initial)
        val pending = async { runCatching { awaitMediaCreationResult(initial) { state } } }
        runCurrent()

        state.value = null

        val error = pending.await().exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("已删除"))
        assertEquals(0, state.subscriptionCount.value)
    }

    @Test
    fun `a final result must belong to the same record and conversation`() = runTest {
        val initial = record()
        for (foreign in listOf(initial.copy(id = Uuid.random()), initial.copy(sessionId = Uuid.random()))) {
            val state = MutableStateFlow<MediaCreationRecord?>(initial)
            val pending = async { runCatching { awaitMediaCreationResult(initial) { state } } }
            runCurrent()
            state.value = foreign.copy(status = MediaCreationStatus.SUCCEEDED)
            assertTrue(pending.await().exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `different task waits can finish independently`() = runTest {
        val first = record()
        val second = record(MediaKind.VIDEO)
        val firstState = MutableStateFlow<MediaCreationRecord?>(first)
        val secondState = MutableStateFlow<MediaCreationRecord?>(second)
        val firstWait = async { awaitMediaCreationResult(first) { firstState } }
        val secondWait = async { awaitMediaCreationResult(second) { secondState } }
        runCurrent()

        val firstResult = first.copy(status = MediaCreationStatus.SUCCEEDED)
        firstState.value = firstResult
        assertSame(firstResult, firstWait.await())
        assertFalse(secondWait.isCompleted)

        val secondResult = second.copy(status = MediaCreationStatus.FAILED)
        secondState.value = secondResult
        assertSame(secondResult, secondWait.await())
    }

    @Test
    fun `result reads accept only a record id and wait until completion`() = runTest {
        val initial = record()
        val state = MutableStateFlow<MediaCreationRecord?>(initial)
        var reads = 0
        val read = tools(read = { id ->
            assertEquals(initial.id, id)
            reads++
            val result = awaitMediaCreationResult(initial) { state }
            listOf(UIMessagePart.Text(result.status.name))
        }).single { it.name == MEDIA_TASK_READ_TOOL }
        assertEquals(setOf("record_id"), (read.parameters() as InputSchema.Obj).properties.keys)
        val pending = async { read.execute(buildJsonObject { put("record_id", initial.id.toString()) }) }
        runCurrent()
        advanceTimeBy(65_000)
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(1, reads)

        state.value = initial.copy(status = MediaCreationStatus.SUCCEEDED)
        assertEquals(listOf(UIMessagePart.Text("SUCCEEDED")), pending.await())
        assertEquals(1, reads)
    }

    @Test
    fun `removed wait arguments are rejected before any task operation`() = runTest {
        var operations = 0
        val definitions = createMediaCreationTools(
            { listOf(provider) },
            { emptyList() },
            { error("Unexpected generation") },
            {
                operations++
                emptyList()
            },
            {
                operations++
                emptyList()
            },
        ).filter { it.name != MEDIA_GENERATE_TOOL }
        for (tool in definitions) {
            for (wait in listOf(0, 20_000)) {
                val error = runCatching {
                    tool.execute(buildJsonObject {
                        put("record_id", Uuid.random().toString())
                        put("wait_ms", wait)
                    })
                }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
            }
        }
        assertEquals(0, operations)
    }

    @Test
    fun `one generation call returns final outputs without a follow up result query`() = runTest {
        val state = MutableStateFlow<MediaCreationRecord?>(null)
        var submitted = 0
        val generate = tools(submit = { request ->
            submitted++
            val initial = record(id = request.recordId)
            state.value = initial
            val result = awaitMediaCreationResult(initial) { state }
            listOf(UIMessagePart.Text(result.status.name), UIMessagePart.Image("file:///media/final.png"))
        }).single { it.name == MEDIA_GENERATE_TOOL }
        val arguments =
            MediaToolArguments("image", provider.models.first { it.kind == MediaKind.IMAGE }.id, "create an image")
        val prepared = generate.prepareArguments(mediaToolJson.encodeToJsonElement(arguments))
        val pending = async { generate.execute(prepared) }
        runCurrent()
        advanceTimeBy(90_000)
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(1, submitted)

        state.value = requireNotNull(state.value).copy(status = MediaCreationStatus.SUCCEEDED)
        assertEquals(
            listOf(UIMessagePart.Text("SUCCEEDED"), UIMessagePart.Image("file:///media/final.png")),
            pending.await()
        )
        assertEquals(1, submitted)
    }
}
