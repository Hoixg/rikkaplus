package me.rerere.rikkahub.ui.pages.extensions.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class WorkspaceDirectoryLoaderTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private fun file(path: String, size: Long = 1, updatedAt: Long = 0) =
        WorkspaceFileEntry(path, path.substringAfterLast('/'), false, size, updatedAt)

    private fun dir(path: String) = WorkspaceFileEntry(path, path.substringAfterLast('/'), true, 0, 0)

    private suspend fun withLoader(
        initial: WorkspaceDetailState = WorkspaceDetailState(),
        fileOrdersLoaded: Deferred<Unit> = CompletableDeferred(Unit),
        load: suspend (WorkspaceStorageArea, String) -> List<WorkspaceFileEntry>,
        test: suspend (MutableStateFlow<WorkspaceDetailState>, WorkspaceDirectoryLoader, suspend () -> Unit) -> Unit,
    ) {
        val job = SupervisorJob()
        val scope = CoroutineScope(currentCoroutineContext() + job)
        val state = MutableStateFlow(initial)
        try {
            test(state, WorkspaceDirectoryLoader(scope, state, fileOrdersLoaded, load)) { job.children.toList().joinAll() }
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun MutableStateFlow<WorkspaceDetailState>.awaitIdle() {
        first { !it.loading && it.loadingPaths.isEmpty() }
    }

    @Test
    fun `first entry loads only the requested directory once`() = runBlocking {
        val requests = mutableListOf<String>()
        withLoader(load = { _, path ->
            requests += path
            listOf(file("$path/a"))
        }) { state, loader, _ ->
            assertTrue(requests.isEmpty())
            loader.enter(" /src\\sub/ ", " /src\\sub\\a/ ")
            state.awaitIdle()
            assertEquals(listOf("src/sub"), requests)
            assertEquals("src/sub", state.value.path)
            assertEquals("src/sub/a", state.value.highlightPath)
            assertTrue(state.value.hasLoadedDirectory)
        }
    }

    @Test
    fun `reentry preserves current location and cached rows instead of reapplying the initial path`() = runBlocking {
        val reentryStarted = CompletableDeferred<Unit>()
        val reentryGate = CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<WorkspaceStorageArea, String>>()
        withLoader(load = { area, path ->
            requests += area to path
            if (area == WorkspaceStorageArea.LINUX) {
                reentryStarted.complete(Unit)
                reentryGate.await()
                if (path == "etc") listOf(dir("etc/sub")) else listOf(file("etc/sub/a"))
            } else listOf(file("src/a"))
        }) { state, loader, drainRequests ->
            loader.enter("src", "src/a")
            state.awaitIdle()
            val cached = state.value.copy(
                area = WorkspaceStorageArea.LINUX,
                path = "etc",
                highlightPath = "etc/sub/a",
                entries = listOf(dir("etc/sub")),
                expandedPaths = setOf("etc/sub"),
                childrenCache = mapOf("etc/sub" to listOf(file("etc/sub/a"))),
                customFileOrders = mapOf(workspaceFileOrderKey(WorkspaceStorageArea.LINUX, "etc/sub") to listOf("etc/sub/a")),
            )
            state.value = cached
            try {
                loader.enter("src", "src/a")
                reentryStarted.await()
                assertEquals(cached.copy(loading = true), state.value)
                assertEquals(listOf(WorkspaceStorageArea.FILES to "src", WorkspaceStorageArea.LINUX to "etc"), requests)
            } finally {
                reentryGate.complete(Unit)
            }
            drainRequests()
        }
    }

    @Test
    fun `initial rows wait for the saved manual order`() = runBlocking {
        val ordersReady = CompletableDeferred<Unit>()
        var requests = 0
        withLoader(fileOrdersLoaded = ordersReady, load = { _, _ ->
            requests++
            listOf(file("a"), file("b"))
        }) { state, loader, _ ->
            loader.enter()
            yield()
            assertEquals(0, requests)
            assertTrue(state.value.loading)
            assertTrue(state.value.entries.isEmpty())
            state.value = state.value.copy(
                customFileOrders = mapOf(workspaceFileOrderKey(WorkspaceStorageArea.FILES, "") to listOf("b", "a")),
            )
            ordersReady.complete(Unit)
            val firstListing = state.first { it.entries.isNotEmpty() }
            val order = firstListing.customFileOrders.getValue(workspaceFileOrderKey(WorkspaceStorageArea.FILES, ""))
            assertEquals(listOf("b", "a"), sortWorkspaceEntries(firstListing.entries, WorkspaceFileSortMode.MANUAL, false, order).map { it.path })
            assertEquals(1, requests)
        }
    }

    @Test
    fun `failed initial sort restoration reports an error instead of loading forever`() = runBlocking {
        val ordersReady = CompletableDeferred<Unit>()
        withLoader(fileOrdersLoaded = ordersReady, load = { _, _ -> error("must not list files") }) { state, loader, _ ->
            loader.enter()
            ordersReady.completeExceptionally(IllegalStateException("sort read failed"))
            state.awaitIdle()
            assertEquals("sort read failed", state.value.error)
        }
    }

    @Test
    fun `expanded rows stay visible throughout root and child refreshes`() = runBlocking {
        val rootStarted = CompletableDeferred<Unit>()
        val rootGate = CompletableDeferred<Unit>()
        val childStarted = CompletableDeferred<Unit>()
        val childGate = CompletableDeferred<Unit>()
        val cached = WorkspaceDetailState(
            entries = listOf(dir("src")),
            hasLoadedDirectory = true,
            expandedPaths = setOf("src"),
            childrenCache = mapOf("src" to listOf(file("src/a"))),
        )
        withLoader(initial = cached, load = { _, path ->
            if (path.isEmpty()) {
                rootStarted.complete(Unit)
                rootGate.await()
                listOf(dir("src"))
            } else {
                childStarted.complete(Unit)
                childGate.await()
                listOf(file("src/a", size = 20))
            }
        }) { state, loader, _ ->
            fun assertCachedRows() {
                assertEquals(cached.entries, state.value.entries)
                assertEquals(cached.childrenCache, state.value.childrenCache)
                assertEquals(cached.expandedPaths, state.value.expandedPaths)
            }
            try {
                loader.refresh()
                rootStarted.await()
                assertCachedRows()
                rootGate.complete(Unit)
                childStarted.await()
                assertCachedRows()
                childGate.complete(Unit)
                state.awaitIdle()
                assertEquals(20L, state.value.childrenCache.getValue("src").single().sizeBytes)
            } finally {
                rootGate.complete(Unit)
                childGate.complete(Unit)
            }
        }
    }

    @Test
    fun `failed refresh retains the last displayed directory and expanded rows`() = runBlocking {
        val cached = WorkspaceDetailState(
            entries = listOf(dir("src")),
            hasLoadedDirectory = true,
            expandedPaths = setOf("src"),
            childrenCache = mapOf("src" to listOf(file("src/a"))),
        )
        withLoader(initial = cached, load = { _, _ -> error("read failed") }) { state, loader, _ ->
            loader.refresh()
            state.awaitIdle()
            assertEquals(cached.copy(error = "read failed"), state.value)
        }
    }

    @Test
    fun `failed child refresh retains cached expansion`() = runBlocking {
        val cached = WorkspaceDetailState(
            entries = listOf(dir("src")),
            hasLoadedDirectory = true,
            expandedPaths = setOf("src"),
            childrenCache = mapOf("src" to listOf(file("src/a"))),
        )
        withLoader(initial = cached, load = { _, path ->
            if (path.isEmpty()) cached.entries else error("child read failed")
        }) { state, loader, _ ->
            loader.refresh()
            state.awaitIdle()
            assertEquals(cached.copy(error = "child read failed"), state.value)
        }
    }

    @Test
    fun `an already loaded empty directory stays loaded during refresh`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        withLoader(initial = WorkspaceDetailState(hasLoadedDirectory = true), load = { _, _ ->
            gate.await()
            emptyList()
        }) { state, loader, _ ->
            loader.enter()
            assertTrue(state.value.loading)
            assertTrue(state.value.hasLoadedDirectory)
            assertTrue(state.value.entries.isEmpty())
            gate.complete(Unit)
            state.awaitIdle()
            assertTrue(state.value.hasLoadedDirectory)
        }
    }

    @Test
    fun `late directory response cannot replace the new directory`() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldGate = CompletableDeferred<Unit>()
        withLoader(initial = WorkspaceDetailState(path = "old"), load = { _, path ->
            if (path == "old") withContext(NonCancellable) {
                oldStarted.complete(Unit)
                oldGate.await()
                listOf(file("old/a"))
            } else listOf(file("new/b"))
        }) { state, loader, drainRequests ->
            try {
                loader.refresh()
                oldStarted.await()
                state.value = state.value.copy(path = "new")
                loader.refresh()
                state.awaitIdle()
                oldGate.complete(Unit)
                drainRequests()
                assertEquals("new", state.value.path)
                assertEquals(listOf("new/b"), state.value.entries.map { it.path })
                assertNull(state.value.error)
            } finally {
                oldGate.complete(Unit)
            }
        }
    }

    @Test
    fun `an older refresh cannot overwrite a newer refresh of the same path`() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldGate = CompletableDeferred<Unit>()
        var requests = 0
        withLoader(load = { _, _ ->
            if (requests++ == 0) withContext(NonCancellable) {
                oldStarted.complete(Unit)
                oldGate.await()
                listOf(file("old"))
            } else listOf(file("new"))
        }) { state, loader, drainRequests ->
            try {
                loader.refresh()
                oldStarted.await()
                loader.refresh()
                state.awaitIdle()
                oldGate.complete(Unit)
                drainRequests()
                assertEquals(listOf("new"), state.value.entries.map { it.path })
            } finally {
                oldGate.complete(Unit)
            }
        }
    }

    @Test
    fun `late expanded directory response cannot contaminate another storage area`() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldGate = CompletableDeferred<Unit>()
        withLoader(initial = WorkspaceDetailState(entries = listOf(dir("src"))), load = { area, path ->
            when {
                path.isEmpty() -> listOf(dir("src"))
                area == WorkspaceStorageArea.FILES -> withContext(NonCancellable) {
                    oldStarted.complete(Unit)
                    oldGate.await()
                    listOf(file("src/files.txt"))
                }
                else -> listOf(file("src/linux.txt"))
            }
        }) { state, loader, drainRequests ->
            try {
                loader.toggleExpand(dir("src"))
                oldStarted.await()
                state.value = state.value.forStorageArea(WorkspaceStorageArea.LINUX)
                loader.refresh()
                state.awaitIdle()
                loader.toggleExpand(dir("src"))
                state.awaitIdle()
                oldGate.complete(Unit)
                drainRequests()
                assertEquals(WorkspaceStorageArea.LINUX, state.value.area)
                assertEquals(listOf("src/linux.txt"), state.value.childrenCache.getValue("src").map { it.path })
                assertNull(state.value.error)
            } finally {
                oldGate.complete(Unit)
            }
        }
    }

    @Test
    fun `collapse and reexpand ignore the old request and its error`() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldGate = CompletableDeferred<Unit>()
        var requests = 0
        withLoader(initial = WorkspaceDetailState(entries = listOf(dir("src"))), load = { _, _ ->
            if (requests++ == 0) withContext(NonCancellable) {
                oldStarted.complete(Unit)
                oldGate.await()
                error("stale failure")
            } else listOf(file("src/new"))
        }) { state, loader, drainRequests ->
            try {
                loader.toggleExpand(dir("src"))
                oldStarted.await()
                loader.toggleExpand(dir("src"))
                loader.toggleExpand(dir("src"))
                state.awaitIdle()
                oldGate.complete(Unit)
                drainRequests()
                assertEquals(setOf("src"), state.value.expandedPaths)
                assertEquals(listOf("src/new"), state.value.childrenCache.getValue("src").map { it.path })
                assertNull(state.value.error)
                assertTrue(state.value.loadingPaths.isEmpty())
            } finally {
                oldGate.complete(Unit)
            }
        }
    }

    @Test
    fun `refresh reloads expanded files and metadata without losing manual order`() = runBlocking {
        val manual = mapOf(workspaceFileOrderKey(WorkspaceStorageArea.FILES, "src") to listOf("src/b", "src/a"))
        val freshFiles = listOf(file("src/a", size = 100, updatedAt = 30), file("src/b", size = 2, updatedAt = 20))
        withLoader(
            initial = WorkspaceDetailState(
                entries = listOf(dir("src")),
                expandedPaths = setOf("src"),
                childrenCache = mapOf("src" to listOf(file("src/deleted"), file("src/a", size = 1))),
                customFileOrders = manual,
            ),
            load = { _, path -> if (path.isEmpty()) listOf(dir("src")) else freshFiles },
        ) { state, loader, _ ->
            loader.refresh()
            state.awaitIdle()
            assertEquals(freshFiles, state.value.childrenCache.getValue("src"))
            assertEquals(manual, state.value.customFileOrders)
            assertEquals(setOf("src"), state.value.expandedPaths)
            assertEquals(listOf("src/b", "src/a"), sortWorkspaceEntries(freshFiles, WorkspaceFileSortMode.SIZE, false, emptyList()).map { it.path })
            assertEquals(listOf("src/b", "src/a"), sortWorkspaceEntries(freshFiles, WorkspaceFileSortMode.MODIFIED_TIME, false, emptyList()).map { it.path })
        }
    }

    @Test
    fun `refresh reloads nested expanded directories`() = runBlocking {
        val listing = mapOf(
            "" to listOf(dir("src")),
            "src" to listOf(dir("src/sub")),
            "src/sub" to listOf(file("src/sub/a")),
        )
        withLoader(
            initial = WorkspaceDetailState(expandedPaths = setOf("src", "src/sub")),
            load = { _, path -> listing.getValue(path) },
        ) { state, loader, _ ->
            loader.refresh()
            state.awaitIdle()
            val rows = flattenWorkspaceTree(state.value.entries, state.value.expandedPaths, state.value.childrenCache)
            assertEquals(listOf("src", "src/sub", "src/sub/a"), rows.map { it.entry.path })
        }
    }

    @Test
    fun `loading failure releases the loading state and can be retried`() = runBlocking {
        var fail = true
        withLoader(load = { _, _ -> if (fail) error("read failed") else listOf(file("a")) }) { state, loader, _ ->
            loader.refresh()
            state.awaitIdle()
            assertEquals("read failed", state.value.error)
            assertFalse(state.value.loading)
            fail = false
            loader.refresh()
            state.awaitIdle()
            assertEquals(listOf("a"), state.value.entries.map { it.path })
            assertNull(state.value.error)
        }
    }
}
