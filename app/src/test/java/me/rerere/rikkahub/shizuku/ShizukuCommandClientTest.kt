@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuCommandClientTest {
    private fun running(
        id: String
    ) = buildJsonObject {
        put("task_id", id)
        put("finished", false)
        put("execution_status", "running")
    }
    private fun client(
        start: suspend (String, ShizukuCommandRequest) -> JsonObject,
        read: suspend (String) -> JsonObject = { running(it) },
        cancel: suspend (String) -> JsonObject = { buildJsonObject {} },
        output: suspend (String, String, Int) -> JsonObject = { _, _, _ -> buildJsonObject { put("output", "text") } },
    ) = ShizukuCommandClient(start, read, output, cancel)

    @Test
    fun foregroundCancellationSendsExactlyOneCancelForItsOwnId() = runBlocking {
        val started = CompletableDeferred<String>()
        val cancelled = mutableListOf<String>()
        val api =
            client(start = { id, _ ->
                started.complete(id)
                running(id)
            }, cancel = { id ->
                cancelled.add(id)
                buildJsonObject {}
            })
        val job = launch { api.execute(ShizukuCommandRequest(command = "sleep 5")) }
        val id = started.await()
        job.cancelAndJoin()
        assertEquals(listOf(id), cancelled)
    }

    @Test
    fun cancellationDuringStartStillCancelsTheKnownForegroundId() = runBlocking {
        val started = CompletableDeferred<String>()
        val cancelled = CompletableDeferred<String>()
        val api =
            client(start = { id, _ ->
                started.complete(id)
                awaitCancellation()
            }, cancel = { id ->
                cancelled.complete(id)
                buildJsonObject {}
            })
        val job = launch { api.execute(ShizukuCommandRequest(command = "sleep 5")) }
        val id = started.await()
        job.cancelAndJoin()
        assertEquals(id, cancelled.await())
    }

    @Test
    fun backgroundCommandReturnsItsIdWithoutWaitingOrCancelling() = runBlocking {
        var reads = 0
        var cancels = 0
        val api =
            client(start = { id, _ -> running(id) }, read = {
                reads++
                running(it)
            }, cancel = {
                cancels++
                buildJsonObject {}
            })
        val result = api.execute(ShizukuCommandRequest(command = "sleep 5", background = true))
        assertTrue(isShizukuTaskId(result["task_id"]!!.jsonPrimitive.content))
        assertEquals(0, reads)
        assertEquals(0, cancels)
    }

    @Test
    fun unknownStartOutcomeKeepsTheIdAndNeverReplaysTheCommand() = runBlocking {
        var starts = 0
        val api =
            client(start = { id, _ ->
                starts++
                shizukuCallFailed(id)
            }, read = { error("Must not assume the command did not execute") })
        val result = api.execute(ShizukuCommandRequest(command = "write something"))
        assertEquals(1, starts)
        assertEquals("unknown", result["execution_status"]!!.jsonPrimitive.content)
        assertTrue(isShizukuTaskId(result["task_id"]!!.jsonPrimitive.content))
    }

    @Test
    fun failedExitAndPreviewTruncationRemainVisible() = runBlocking {
        val api = client(start = { id, _ ->
            buildJsonObject {
                put("task_id", id)
                put("output_id", id)
                put("finished", true)
                put("execution_status", "completed")
                put("exit_code", 7)
            }
        }, output = { _, stream, _ ->
            buildJsonObject {
                put("output", stream)
                put("has_more", true)
            }
        })
        val result = api.execute(ShizukuCommandRequest(command = "false"))
        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        assertEquals(7, result["exit_code"]!!.jsonPrimitive.int)
        assertEquals("stderr", result["stderr"]!!.jsonPrimitive.content)
        assertTrue(result["stdout_truncated"]!!.jsonPrimitive.boolean)
        assertNotNull(result["output_id"])
    }

    @Test
    fun timeoutKeepsPartialOutputAndTheOutputId() = runBlocking {
        val api = client(start = { id, _ ->
            buildJsonObject {
                put("task_id", id)
                put("output_id", id)
                put("finished", true)
                put("execution_status", "timed_out")
            }
        })
        val result = api.execute(ShizukuCommandRequest(command = "sleep 5"))
        assertEquals("command_timeout", result["error"]!!.jsonPrimitive.content)
        assertEquals("text", result["partial_stdout"]!!.jsonPrimitive.content)
        assertNotNull(result["output_id"])
    }

    @Test
    fun backgroundCancellationDuringDispatchLeavesTheTaskManagedByItsId() = runBlocking {
        val dispatched = CompletableDeferred<Unit>()
        var cancels = 0
        val api =
            client(start = { _, _ ->
                dispatched.complete(Unit)
                awaitCancellation()
            }, cancel = {
                cancels++
                buildJsonObject {}
            })
        val job = launch { api.execute(ShizukuCommandRequest(command = "sleep 5", background = true)) }
        dispatched.await()
        job.cancelAndJoin()
        assertEquals(0, cancels)
    }
}
