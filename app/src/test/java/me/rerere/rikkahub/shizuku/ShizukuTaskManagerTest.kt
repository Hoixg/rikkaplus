@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ShizukuTaskManagerTest {
    private val first = "sz_" + "a".repeat(32)
    private val second = "sz_" + "b".repeat(32)
    private fun ShizukuTaskManager.finished(id: String): JsonObject {
        val state = read(id, 10_000)
        assertTrue(state.toString(), state["finished"]!!.jsonPrimitive.boolean)
        return state
    }

    @Test
    fun backgroundTaskReturnsImmediatelyAndPreservesExitCodeAndSeparateOutputs() {
        val gate = CountDownLatch(1)
        ShizukuTaskManager(launch = {
            gate.await()
            ShizukuManagedProcess(it.processBuilder().start())
        }).use { tasks ->
            val starting = tasks.start(first, ShizukuCommandRequest(command = "echo stdout; echo stderr >&2; exit 7"))
            assertFalse(starting["finished"]!!.jsonPrimitive.boolean)
            gate.countDown()
            val result = tasks.finished(first)
            assertEquals("completed", result["execution_status"]!!.jsonPrimitive.content)
            assertEquals(7, result["exit_code"]!!.jsonPrimitive.int)
            assertEquals("stdout\n", tasks.output(first, "stdout", 0, 100, null)["output"]!!.jsonPrimitive.content)
            assertEquals("stderr\n", tasks.output(first, "stderr", 0, 100, null)["output"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun stdinEnvironmentAndDirectoryReachTheChildWithoutReparsing() {
        val directory = Files.createTempDirectory("shizuku-task").toFile()
        try {
            ShizukuTaskManager().use { tasks ->
                tasks.start(
                    first,
                    ShizukuCommandRequest(
                        command = "cat; printf '%s' \"\$RK_LITERAL\"; pwd",
                        stdin = "你好\n'quoted'\n",
                        workingDir = directory.absolutePath,
                        environment = mapOf("RK_LITERAL" to "$(do_not_execute)")
                    )
                )
                tasks.finished(first)
                val output = tasks.output(first, "stdout", 0, 8000, null)["output"]!!.jsonPrimitive.content
                assertTrue(output, output.startsWith("你好\n'quoted'\n$(do_not_execute)"))
                assertTrue(output, output.contains(directory.name))
            }
        } finally {
            directory.delete()
        }
    }

    @Test
    fun duplicateTaskIdNeverLaunchesASecondProcess() {
        val launches = AtomicInteger()
        ShizukuTaskManager(launch = { request ->
            launches.incrementAndGet()
            ShizukuManagedProcess(request.processBuilder().start())
        }).use { tasks ->
            val request = ShizukuCommandRequest(command = "echo once")
            tasks.start(first, request)
            tasks.start(first, request)
            tasks.finished(first)
            assertEquals(1, launches.get())
        }
    }

    @Test
    fun cancellingOneTaskKeepsAnotherTaskAndItsOutputAlive() {
        ShizukuTaskManager().use { tasks ->
            tasks.start(first, ShizukuCommandRequest(command = "echo before; sleep 5; echo after"))
            tasks.start(second, ShizukuCommandRequest(command = "echo unaffected"))
            assertEquals("task_running", tasks.forget(first)["error"]!!.jsonPrimitive.content)
            tasks.cancel(first)
            assertEquals("cancelled", tasks.finished(first)["execution_status"]!!.jsonPrimitive.content)
            assertEquals("completed", tasks.finished(second)["execution_status"]!!.jsonPrimitive.content)
            assertEquals("unaffected\n", tasks.output(second, "stdout", 0, 100, null)["output"]!!.jsonPrimitive.content)
            assertFalse(tasks.output(first, "stdout", 0, 100, null)["output"]!!.jsonPrimitive.content.contains("after"))
            tasks.forget(first)
            assertEquals("task_not_found", tasks.read(first)["error"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun timeoutReturnsPartialOutputAndExplicitFinishedState() {
        ShizukuTaskManager().use { tasks ->
            tasks.start(first, ShizukuCommandRequest(command = "echo before; sleep 5; echo after", timeoutMs = 1000))
            assertEquals("timed_out", tasks.finished(first)["execution_status"]!!.jsonPrimitive.content)
            val output = tasks.output(first, "stdout", 0, 100, null)["output"]!!.jsonPrimitive.content
            assertTrue(output.contains("before"))
            assertFalse(output.contains("after"))
        }
    }

    @Test
    fun taskAndOutputLimitsAndExpirationAreBounded() {
        var now = 100L
        ShizukuTaskManager(maxTasks = 1, outputCapacity = 10, retentionMs = 1000, clock = { now }).use { tasks ->
            tasks.start(first, ShizukuCommandRequest(command = "printf 12345678901234567890"))
            tasks.finished(first)
            assertEquals(
                "task_limit_reached",
                tasks.start(second, ShizukuCommandRequest(command = "true"))["error"]!!.jsonPrimitive.content
            )
            val page = tasks.output(first, "stdout", 0, 100, null)
            assertEquals(10, page["dropped_bytes"]!!.jsonPrimitive.int)
            now += 1000
            assertTrue(tasks.list()["tasks"]!!.jsonArray.isEmpty())
            assertEquals("task_not_found", tasks.read(first)["error"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun runningLimitAndLaunchFailureAreStructured() {
        ShizukuTaskManager(maxRunning = 1).use { tasks ->
            tasks.start(first, ShizukuCommandRequest(command = "sleep 5"))
            assertEquals(
                "too_many_running_tasks",
                tasks.start(second, ShizukuCommandRequest(command = "true"))["error"]!!.jsonPrimitive.content
            )
        }
        ShizukuTaskManager(launch = { throw java.io.IOException("launch failed") }).use { tasks ->
            tasks.start(first, ShizukuCommandRequest(command = "true"))
            val result = tasks.finished(first)
            assertEquals("failed", result["execution_status"]!!.jsonPrimitive.content)
            assertEquals("exec_failed", result["error"]!!.jsonPrimitive.content)
        }
    }
}
