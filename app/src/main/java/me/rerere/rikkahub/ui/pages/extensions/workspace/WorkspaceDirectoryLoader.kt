package me.rerere.rikkahub.ui.pages.extensions.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

internal class WorkspaceDirectoryLoader(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<WorkspaceDetailState>,
    private val fileOrdersLoaded: Deferred<Unit> = CompletableDeferred(Unit),
    private val listFiles: suspend (WorkspaceStorageArea, String) -> List<WorkspaceFileEntry>,
) {
    private var entered = false
    private var generation = 0L
    private var refreshJob: Job? = null
    private val childJobs = mutableMapOf<String, Job>()

    fun enter(initialPath: String? = null, highlightPath: String? = null) {
        // Returning from an editor must not replay the route's initial location.
        if (!entered) {
            entered = true
            val path = initialPath?.trim()?.replace('\\', '/')?.trim('/') ?: state.value.path
            val highlight = highlightPath?.trim()?.replace('\\', '/')?.trim('/')
            state.update {
                it.copy(
                    path = path,
                    highlightPath = highlight,
                    entries = if (path == it.path) it.entries else emptyList(),
                    hasLoadedDirectory = path == it.path && it.hasLoadedDirectory,
                )
            }
        }
        refresh()
    }

    fun refresh() {
        val area = state.value.area
        val path = state.value.path
        val requestGeneration = ++generation
        refreshJob?.cancel()
        childJobs.values.toList().forEach { it.cancel() }
        childJobs.clear()
        state.update {
            it.copy(loading = true, error = null, loadingPaths = emptySet())
        }
        refreshJob = scope.launch {
            try {
                fileOrdersLoaded.await()
                val entries = listFiles(area, path)
                ensureActive()
                if (!isCurrent(area, requestGeneration) || state.value.path != path) return@launch
                state.update { it.withDirectoryListing(path, entries).copy(loading = false) }
                loadExpandedDirectories(entries, area, requestGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ensureActive()
                if (isCurrent(area, requestGeneration) && state.value.path == path) {
                    state.update {
                        it.copy(loading = false, error = error.message ?: "Directory load failed")
                    }
                }
            }
        }
    }

    fun toggleExpand(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        val path = entry.path
        if (path in state.value.expandedPaths) {
            val cancelled = childJobs.filterKeys { isWorkspacePathInSubtree(it, path) }
            cancelled.forEach { (key, job) ->
                childJobs.remove(key)
                job.cancel()
            }
            state.update {
                it.copy(
                    expandedPaths = it.expandedPaths - path,
                    loadingPaths = it.loadingPaths.filterNot { candidate -> isWorkspacePathInSubtree(candidate, path) }.toSet(),
                )
            }
        } else {
            state.update { it.copy(expandedPaths = it.expandedPaths + path) }
            loadChildren(path, state.value.area, generation)
        }
    }

    private fun isCurrent(area: WorkspaceStorageArea, requestGeneration: Long): Boolean =
        state.value.area == area && generation == requestGeneration

    private fun loadExpandedDirectories(entries: List<WorkspaceFileEntry>, area: WorkspaceStorageArea, requestGeneration: Long) {
        entries.filter { it.isDirectory && it.path in state.value.expandedPaths }
            .forEach { loadChildren(it.path, area, requestGeneration) }
    }

    private fun loadChildren(path: String, area: WorkspaceStorageArea, requestGeneration: Long) {
        childJobs.remove(path)?.cancel()
        state.update { it.copy(loadingPaths = it.loadingPaths + path) }
        // Register the job before an immediate dispatcher can complete the listing.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val entries = listFiles(area, path)
                ensureActive()
                if (!isCurrent(area, requestGeneration) || path !in state.value.expandedPaths) return@launch
                state.update { it.withDirectoryListing(path, entries) }
                loadExpandedDirectories(entries, area, requestGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ensureActive()
                if (isCurrent(area, requestGeneration) && path in state.value.expandedPaths) {
                    state.update {
                        it.copy(
                            expandedPaths = if (path in it.childrenCache) it.expandedPaths else it.expandedPaths - path,
                            error = error.message ?: "Directory load failed",
                        )
                    }
                }
            } finally {
                if (childJobs[path] === coroutineContext[Job]) {
                    childJobs.remove(path)
                    state.update { it.copy(loadingPaths = it.loadingPaths - path) }
                }
            }
        }
        childJobs[path] = job
        job.start()
    }
}

internal fun isWorkspacePathInSubtree(path: String, root: String): Boolean =
    path == root || path.startsWith("$root/")

internal fun remapWorkspacePath(path: String, oldPath: String, newPath: String): String =
    if (isWorkspacePathInSubtree(path, oldPath)) newPath + path.removePrefix(oldPath) else path

internal fun WorkspaceDetailState.withRenamedWorkspaceEntry(
    original: WorkspaceFileEntry,
    renamed: WorkspaceFileEntry,
): WorkspaceDetailState {
    fun remap(entry: WorkspaceFileEntry): WorkspaceFileEntry = when {
        entry.path == original.path -> renamed
        isWorkspacePathInSubtree(entry.path, original.path) ->
            entry.copy(path = remapWorkspacePath(entry.path, original.path, renamed.path))
        else -> entry
    }
    return copy(
        path = remapWorkspacePath(path, original.path, renamed.path),
        highlightPath = highlightPath?.let { remapWorkspacePath(it, original.path, renamed.path) },
        entries = entries.map(::remap),
        childrenCache = childrenCache.map { (directoryPath, children) ->
            remapWorkspacePath(directoryPath, original.path, renamed.path) to children.map(::remap)
        }.toMap(),
        expandedPaths = expandedPaths.map { remapWorkspacePath(it, original.path, renamed.path) }.toSet(),
        loadingPaths = loadingPaths.map { remapWorkspacePath(it, original.path, renamed.path) }.toSet(),
    )
}

internal fun WorkspaceDetailState.forStorageArea(area: WorkspaceStorageArea): WorkspaceDetailState = copy(
    area = area,
    path = "",
    highlightPath = null,
    entries = emptyList(),
    hasLoadedDirectory = false,
    expandedPaths = emptySet(),
    childrenCache = emptyMap(),
    loadingPaths = emptySet(),
    error = null,
)

internal fun WorkspaceDetailState.withoutWorkspaceEntry(deletedPath: String): WorkspaceDetailState = copy(
    entries = entries.filterNot { isWorkspacePathInSubtree(it.path, deletedPath) },
    childrenCache = childrenCache.filterKeys { !isWorkspacePathInSubtree(it, deletedPath) }
        .mapValues { (_, children) -> children.filterNot { isWorkspacePathInSubtree(it.path, deletedPath) } },
    expandedPaths = expandedPaths.filterNot { isWorkspacePathInSubtree(it, deletedPath) }.toSet(),
    highlightPath = highlightPath?.takeUnless { isWorkspacePathInSubtree(it, deletedPath) },
)

internal fun WorkspaceDetailState.withDirectoryListing(
    directoryPath: String,
    listing: List<WorkspaceFileEntry>,
): WorkspaceDetailState {
    val prefix = if (directoryPath.isEmpty()) "" else "$directoryPath/"
    val directories = listing.filter { it.isDirectory }.map { it.path }.toSet()
    fun retainPath(candidate: String): Boolean {
        if (candidate == directoryPath || !candidate.startsWith(prefix)) return true
        val childPath = prefix + candidate.removePrefix(prefix).substringBefore('/')
        return childPath in directories
    }
    val retainedCache = childrenCache.filterKeys(::retainPath)
    return copy(
        entries = if (directoryPath == path) listing else entries,
        hasLoadedDirectory = hasLoadedDirectory || directoryPath == path,
        childrenCache = if (directoryPath == path) retainedCache else retainedCache + (directoryPath to listing),
        expandedPaths = expandedPaths.filter(::retainPath).toSet(),
    )
}
