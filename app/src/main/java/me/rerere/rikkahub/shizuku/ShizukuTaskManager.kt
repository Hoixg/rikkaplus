@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ShizukuManagedProcess(val process: Process, val stop: () -> Unit = { process.destroyForcibly() })

/** Lives in the privileged UserService. No command, environment, stdin or logs are written to disk. */
internal class ShizukuTaskManager(
    private val launch: (
        ShizukuCommandRequest
    ) -> ShizukuManagedProcess = { ShizukuManagedProcess(it.processBuilder().start()) },
    private val outputCapacity: Int = 1024 * 1024,
    private val maxTasks: Int = 16,
    private val maxRunning: Int = 4,
    private val retentionMs: Long = 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    private class Task(val id: String, val created: Long, capacity: Int) {
        val stdout = ShizukuTaskOutput(capacity)
        val stderr = ShizukuTaskOutput(capacity)
        val done = CountDownLatch(1)

        @Volatile var state = "starting"

        @Volatile var exitCode: Int? = null

        @Volatile var error: String? = null

        @Volatile var finishedAt = 0L

        @Volatile var stopReason: String? = null
        var worker: Thread? = null
        var managed: ShizukuManagedProcess? = null
    }

    private val lock = Any()
    private val tasks = linkedMapOf<String, Task>()
    private var closed = false

    fun start(id: String, request: ShizukuCommandRequest): JsonObject {
        require(isShizukuTaskId(id)) { "Invalid task_id." }
        request.validate()
        synchronized(lock) {
            check(!closed) { "Task service is shutting down." }
            reap()
            // A retry with the same ID can never launch the command a second time.
            tasks[id]?.let { return metadata(it) }
            if (tasks.values.count { it.done.count > 0 } >= maxRunning) return failure("too_many_running_tasks")
            if (tasks.size >=
                maxTasks) {
                return failure("task_limit_reached", "Forget a finished task before starting another.")
            }
            val task = Task(id, clock(), outputCapacity)
            tasks[id] = task
            task.worker = Thread({ run(task, request) }, "shizuku-task-$id").apply {
                isDaemon = true
                start()
            }
            return metadata(task)
        }
    }

    fun read(id: String, waitMs: Int = 0): JsonObject {
        val task = find(id) ?: return missing()
        task.done.await(waitMs.coerceIn(0, 20_000).toLong(), TimeUnit.MILLISECONDS)
        return metadata(task)
    }

    fun output(id: String, stream: String, offset: Int, limit: Int, query: String?): JsonObject {
        val task = find(id) ?: return missing()
        require(stream == "stdout" || stream == "stderr") { "stream must be stdout or stderr." }
        val page = (if (stream ==
            "stdout") {
            task.stdout
        } else {
            task.stderr
        }).page(offset, limit, query, task.done.count == 0L)
        return JsonObject(metadata(task) + page + buildJsonObject { put("stream", stream) })
    }

    fun list(): JsonObject = synchronized(lock) {
        reap()
        buildJsonObject {
            put("success", true)
            put("tasks", buildJsonArray { tasks.values.forEach { add(metadata(it)) } })
            put("max_running", maxRunning)
            put("max_tasks", maxTasks)
            put("output_limit_per_stream", outputCapacity)
            put("retention_ms", retentionMs)
        }
    }

    fun cancel(id: String): JsonObject {
        val task = find(id) ?: return missing()
        synchronized(task) {
            if (task.done.count > 0 && task.stopReason == null) {
                task.stopReason = "cancelled"
                task.worker?.interrupt()
            }
        }
        return metadata(task)
    }

    fun forget(id: String): JsonObject = synchronized(lock) {
        val task = tasks[id] ?: return missing()
        if (task.done.count >
            0) {
            return failure("task_running", "Cancel the task and wait for completion before forgetting it.")
        }
        tasks.remove(id)
        buildJsonObject {
            put("success", true)
            put("forgotten", id)
        }
    }

    override fun close() {
        val active = synchronized(lock) {
            closed = true
            tasks.values.toList()
        }
        active.forEach { cancel(it.id) }
        active.forEach { it.done.await(3, TimeUnit.SECONDS) }
        synchronized(lock) { tasks.clear() }
    }

    private fun find(id: String): Task? = synchronized(lock) {
        reap()
        tasks[id]
    }
    private fun reap() {
        tasks.entries.removeAll {
            it.value.done.count == 0L &&
                clock() - it.value.finishedAt >= retentionMs
        }
    }

    private fun run(task: Task, request: ShizukuCommandRequest) {
        val readers = mutableListOf<Thread>()
        var writer: Thread? = null
        try {
            synchronized(task) { if (task.stopReason != null) return }
            val managed = launch(request)
            synchronized(task) {
                task.managed = managed
                task.state = "running"
            }
            if (task.stopReason != null) throw InterruptedException()
            fun drain(input: InputStream, target: ShizukuTaskOutput) = Thread({
                runCatching {
                    input.use { source ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            target.write(buffer, count)
                        }
                    }
                }
            }, "shizuku-output").apply {
                isDaemon = true
                start()
                readers.add(this)
            }
            drain(managed.process.inputStream, task.stdout)
            drain(managed.process.errorStream, task.stderr)
            writer = Thread({
                runCatching {
                    managed.process.outputStream.use {
                        request.stdin?.let { text ->
                            it.write(
                                text.toByteArray(Charsets.UTF_8)
                            )
                        }
                    }
                }
            }, "shizuku-stdin").apply {
                isDaemon = true
                start()
            }
            if (!managed.process.waitFor(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                synchronized(task) { if (task.stopReason == null) task.stopReason = "timed_out" }
            } else {
                task.exitCode = managed.process.exitValue()
            }
        } catch (_: InterruptedException) {
            synchronized(task) { if (task.stopReason == null) task.stopReason = "cancelled" }
        } catch (e: Exception) {
            task.error = e.message ?: e.javaClass.simpleName
        } finally {
            // Clear interrupt before joining readers, and always clean up the owned process group.
            Thread.interrupted()
            task.managed?.let { managed ->
                runCatching { managed.stop() }
                runCatching { managed.process.outputStream.close() }
                runCatching { managed.process.waitFor(1, TimeUnit.SECONDS) }
            }
            readers.forEach { runCatching { it.join(1000) } }
            task.managed?.let { managed ->
                runCatching { managed.process.inputStream.close() }
                runCatching { managed.process.errorStream.close() }
            }
            writer?.interrupt()
            synchronized(task) {
                task.state = task.stopReason ?: if (task.error != null) "failed" else "completed"
                task.finishedAt = clock()
                task.managed = null
                task.done.countDown()
            }
        }
    }

    private fun metadata(task: Task): JsonObject = buildJsonObject {
        put("success", task.error == null && (task.done.count > 0 || (task.state == "completed" && task.exitCode == 0)))
        put("task_id", task.id)
        put("output_id", task.id)
        put("execution_status", if (task.stopReason != null && task.done.count > 0) "stopping" else task.state)
        put("finished", task.done.count == 0L)
        put("created_at", task.created)
        if (task.done.count == 0L) put("finished_at", task.finishedAt)
        task.exitCode?.let { put("exit_code", it) }
        task.error?.let {
            put("error", "exec_failed")
            put("reason", it)
        }
    }

    private fun missing() = failure(
        "task_not_found",
        "The task expired, was forgotten, or the Shizuku UserService restarted."
    )
    private fun failure(error: String, recovery: String? = null) = buildJsonObject {
        put("error", error)
        recovery?.let { put("recovery", it) }
    }
}
