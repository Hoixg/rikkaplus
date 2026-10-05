package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.BuiltInTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class SubagentManagerTest {
    @Test fun `child stop cancels a waiting tool run and keeps its followup handle`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = SubagentManager(scope)
            val snapshot = config()
            val active = AtomicInteger()
            val fourStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            repeat(4) {
                manager.spawn(snapshot, "blocking", "prompt", true) { child, _, _, _ ->
                    if (active.incrementAndGet() == 4) fourStarted.complete(Unit)
                    release.await()
                    ok(child)
                }
            }
            withTimeout(5_000) { fourStarted.await() }
            var ran = false
            val waiting = id(manager.spawn(snapshot, "waiting", "prompt", true) { child, _, _, followup ->
                ran = true
                assertTrue(followup)
                ok(child, "resumed")
            })
            manager.stopChild(waiting)
            assertFalse(ran)
            assertEquals(SubagentTaskStatus.CANCELLED, manager.tasks.value[waiting]?.status)
            assertEquals(4, manager.tasks.value.values.count { it.status == SubagentTaskStatus.RUNNING })
            release.complete(Unit)
            withTimeout(5_000) { manager.awaitChildren(snapshot.parent.id) }
            assertTrue(manager.followup(snapshot.parent.id, waiting, "continue").contains("resumed"))
            assertTrue(ran)
        } finally { scope.cancel() }
    }

    @Test fun `cancelling a synchronous child returns partial result without cancelling its parent`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = SubagentManager(scope)
            val snapshot = config()
            val running = CompletableDeferred<Uuid>()
            val parent = async {
                manager.spawn(snapshot, "sync", "prompt", false) { child, _, _, _ ->
                    try { running.complete(child); awaitCancellation() } finally {
                        manager.recordPartial(child, SubagentManager.errorJson("cancelled", "partial trace", child))
                    }
                }
            }
            val child = running.await()
            manager.cancel(snapshot.parent.id, child)
            assertTrue(withTimeout(5_000) { parent.await() }.contains("partial trace"))
            assertTrue(isActive)
            val poll = Json.parseToJsonElement(manager.poll(snapshot.parent.id, child)).jsonObject
            assertEquals("cancelled", poll.getValue("status").jsonPrimitive.content)
            assertEquals(child.toString(), poll.getValue("sessionId").jsonPrimitive.content)
            assertTrue(Json.parseToJsonElement(poll.getValue("result").jsonPrimitive.content).jsonObject.containsKey("message"))
        } finally { scope.cancel() }
    }

    private fun config(parent: Uuid = Uuid.random(), timeout: Long = 10_000) = SubagentExecutionConfig(
        settings = Settings.dummy(), assistant = Assistant(), model = Model(),
        parent = Conversation.ofId(parent), timeoutMs = timeout,
    )
    private fun ok(id: Uuid, text: String = "answer") = buildJsonObject {
        put("status", "ok"); put("sessionId", id.toString()); put("result", text)
    }.toString()
    private fun id(result: String) = Uuid.parse(Json.parseToJsonElement(result).jsonObject.let {
        (it["taskId"] ?: it.getValue("sessionId")).jsonPrimitive.content
    })

    @Test fun `sync and async results stay discoverable and followup reuses snapshot and identity`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = SubagentManager(scope)
            val captured = config().copy(
                assistant = Assistant(systemPrompt = "parent instructions", workspaceId = Uuid.random(), enableSubagents = true),
                model = Model(tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext, BuiltInTools.ImageGeneration)),
                parent = Conversation.ofId(Uuid.random()).copy(workspaceCwd = "project"),
            )
            val history = mutableListOf<String>()
            val first = manager.spawn(captured, "review", "first", false) { child, snapshot, message, followup ->
                assertSame(captured, snapshot)
                assertEquals(captured.model, snapshot.model)
                assertEquals(captured.assistant, snapshot.assistant)
                assertEquals("project", snapshot.parent.workspaceCwd)
                assertEquals(history.isNotEmpty(), followup)
                history.add(message)
                ok(child, history.joinToString(","))
            }
            val child = id(first)
            assertEquals(SubagentTaskStatus.COMPLETED, manager.tasks.value[child]?.status)
            assertTrue(manager.list(captured.parent.id).contains(child.toString()))
            val followup = manager.followup(captured.parent.id, child, "second")
            assertEquals(child, id(followup))
            assertTrue(followup.contains("first,second"))
            val asynchronous = manager.spawn(config(captured.parent.id), "async", "third", true) { childId, _, _, _ -> ok(childId) }
            manager.awaitChildren(captured.parent.id)
            assertTrue(manager.poll(captured.parent.id, id(asynchronous)).contains("answer"))
        } finally { scope.cancel() }
    }

    @Test fun `poll cancel and followup reject another parent and expired handles`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var clock = 100L
            val manager = SubagentManager(scope) { clock }
            val snapshot = config()
            val result = manager.spawn(snapshot, "task", "prompt", false) { child, _, _, _ -> ok(child) }
            val child = id(result)
            val other = Uuid.random()
            assertTrue(manager.poll(other, child).contains("AGENT_TASK_NOT_FOUND"))
            assertTrue(manager.cancel(other, child).contains("AGENT_TASK_NOT_FOUND"))
            assertTrue(manager.followup(other, child, "message").contains("AGENT_SESSION_NOT_FOUND"))
            assertEquals("0", Json.parseToJsonElement(manager.list(other)).jsonObject.getValue("count").jsonPrimitive.content)
            clock += SubagentManager.SESSION_TTL_MS + 1
            assertTrue(manager.followup(snapshot.parent.id, child, "message").contains("AGENT_SESSION_NOT_FOUND"))
            assertTrue(manager.tasks.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun `global permit count covers async tasks across parent conversations`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = SubagentManager(scope)
            val gate = CompletableDeferred<Unit>()
            val fourStarted = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val parents = listOf(Uuid.random(), Uuid.random())
            repeat(8) { index ->
                manager.spawn(config(parents[index % 2]), "task $index", "prompt", true) { child, _, _, _ ->
                    val concurrent = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, concurrent) }
                    if (concurrent == 4) fourStarted.complete(Unit)
                    try { gate.await(); ok(child) } finally { active.decrementAndGet() }
                }
            }
            withTimeout(5_000) { fourStarted.await() }
            assertEquals(4, active.get())
            gate.complete(Unit)
            parents.forEach { manager.awaitChildren(it) }
            assertEquals(4, maximum.get())
            assertEquals(8, manager.tasks.value.size)
            assertTrue(manager.tasks.value.values.all { it.status == SubagentTaskStatus.COMPLETED })
        } finally { scope.cancel() }
    }

    @Test fun `cancel waits for finalization preserves partial output and never changes a finished task`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = SubagentManager(scope)
            val snapshot = config()
            val started = CompletableDeferred<Unit>()
            var finalized = false
            val result = manager.spawn(snapshot, "task", "prompt", true) { child, _, _, _ ->
                try { started.complete(Unit); awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        manager.recordPartial(child, SubagentManager.errorJson("cancelled", "partial output", child))
                        finalized = true
                    }
                }
            }
            started.await()
            val child = id(result)
            assertTrue(manager.cancel(snapshot.parent.id, child).contains("cancelling"))
            withTimeout(5_000) { manager.awaitChildren(snapshot.parent.id) }
            assertTrue(finalized)
            assertEquals(SubagentTaskStatus.CANCELLED, manager.tasks.value[child]?.status)
            assertTrue(manager.poll(snapshot.parent.id, child).contains("partial output"))
            assertTrue(manager.cancel(snapshot.parent.id, child).contains("AGENT_TASK_NOT_FOUND"))
            assertEquals(SubagentTaskStatus.CANCELLED, manager.tasks.value[child]?.status)
        } finally { scope.cancel() }
    }

    @Test fun `timeout and failure have distinct terminal states and normal completion does not hang`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = SubagentManager(scope)
            val snapshot = config(timeout = 50)
            val timed = manager.spawn(snapshot, "timed", "prompt", true) { _, _, _, _ -> awaitCancellation() }
            withTimeout(5_000) { manager.awaitChildren(snapshot.parent.id) }
            assertEquals(SubagentTaskStatus.TIMEOUT, manager.tasks.value[id(timed)]?.status)
            val failed = manager.spawn(config(), "failed", "prompt", false) { _, _, _, _ -> error("provider failure") }
            assertEquals(SubagentTaskStatus.FAILED, manager.tasks.value[id(failed)]?.status)
            assertTrue(failed.contains("provider failure"))
            val complete = withTimeout(5_000) { manager.spawn(config(), "complete", "prompt", false) { child, _, _, _ -> ok(child) } }
            assertEquals(SubagentTaskStatus.COMPLETED, manager.tasks.value[id(complete)]?.status)
        } finally { scope.cancel() }
    }

    @Test fun `stopping parent joins queued and active children blocks stale spawning and allows new turn`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = SubagentManager(scope)
            val snapshot = config()
            repeat(8) { manager.spawn(snapshot, "task", "prompt", true) { _, _, _, _ -> awaitCancellation() } }
            withTimeout(5_000) { manager.stopParent(snapshot.parent.id, forget = true) }
            assertTrue(manager.tasks.value.isEmpty())
            assertTrue(runCatching { manager.spawn(snapshot, "stale", "prompt", true) { child, _, _, _ -> ok(child) } }.isFailure)
            manager.resumeParent(snapshot.parent.id)
            assertTrue(manager.spawn(snapshot, "new", "prompt", false) { child, _, _, _ -> ok(child) }.contains("answer"))
        } finally { scope.cancel() }
    }

    @Test fun `same child refuses concurrent followup and deleting child forgets its handle`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = SubagentManager(scope)
            val snapshot = config()
            val gate = CompletableDeferred<Unit>()
            val running = CompletableDeferred<Unit>()
            val original = manager.spawn(snapshot, "task", "prompt", false) { child, _, _, followup ->
                if (followup) { running.complete(Unit); gate.await() }
                ok(child)
            }
            val child = id(original)
            val first = async { manager.followup(snapshot.parent.id, child, "first") }
            running.await()
            assertTrue(manager.followup(snapshot.parent.id, child, "second").contains("AGENT_SESSION_BUSY"))
            gate.complete(Unit); first.await()
            manager.forgetChild(child)
            assertTrue(manager.followup(snapshot.parent.id, child, "third").contains("AGENT_SESSION_NOT_FOUND"))
        } finally { scope.cancel() }
    }
}
