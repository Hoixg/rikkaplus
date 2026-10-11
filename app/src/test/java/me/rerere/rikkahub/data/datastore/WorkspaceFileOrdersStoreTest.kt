package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceFileSortMode
import me.rerere.rikkahub.ui.pages.extensions.workspace.reorderWorkspaceEntries
import me.rerere.rikkahub.ui.pages.extensions.workspace.sortWorkspaceEntries
import me.rerere.rikkahub.ui.pages.extensions.workspace.workspaceFileOrderKey
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFileOrdersStoreTest {
    private suspend fun withStore(disk: MemoryPreferences, test: suspend (SettingsStore) -> Unit) {
        val job = SupervisorJob()
        try {
            val store = SettingsStore(disk, CoroutineScope(currentCoroutineContext() + job))
            store.awaitLoaded()
            test(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `serialized manual order restores and can be moved back after reopening`() = runBlocking {
        val disk = MemoryPreferences()
        val key = workspaceFileOrderKey(WorkspaceStorageArea.FILES, "")
        val entries = listOf("a", "b", "c").map { WorkspaceFileEntry(it, it, false, 1, 0) }
        withStore(disk) { store ->
            val moved = reorderWorkspaceEntries(entries, emptyList(), "a", "b")!!
            store.setWorkspaceFileOrder("workspace", key, moved)
        }
        val reopenedDisk = MemoryPreferences(disk.data.first())
        withStore(reopenedDisk) { store ->
            val order = store.workspaceFileOrdersFlow("workspace").first().getValue(key)
            assertEquals(listOf("b", "a", "c"), sortWorkspaceEntries(entries, WorkspaceFileSortMode.MANUAL, false, order).map { it.path })
            val restored = reorderWorkspaceEntries(entries, order, "a", "b")!!
            store.setWorkspaceFileOrder("workspace", key, restored)
            assertEquals(listOf("a", "b", "c"), store.workspaceFileOrdersFlow("workspace").first().getValue(key))
        }
    }

    @Test
    fun `workspaces areas and directories keep independent orders`() = runBlocking {
        withStore(MemoryPreferences()) { store ->
            val files = workspaceFileOrderKey(WorkspaceStorageArea.FILES, "src")
            val linux = workspaceFileOrderKey(WorkspaceStorageArea.LINUX, "src")
            val docs = workspaceFileOrderKey(WorkspaceStorageArea.FILES, "docs")
            store.setWorkspaceFileOrder("first", files, listOf("src/b", "src/a"))
            store.setWorkspaceFileOrder("first", linux, listOf("src/a", "src/b"))
            store.setWorkspaceFileOrder("first", docs, listOf("docs/z", "docs/x"))
            store.setWorkspaceFileOrder("second", files, listOf("src/a", "src/b"))
            assertEquals(
                mapOf(files to listOf("src/b", "src/a"), linux to listOf("src/a", "src/b"), docs to listOf("docs/z", "docs/x")),
                store.workspaceFileOrdersFlow("first").first(),
            )
            assertEquals(mapOf(files to listOf("src/a", "src/b")), store.workspaceFileOrdersFlow("second").first())
        }
    }

    @Test
    fun `clearing an order preserves others and removes duplicate paths`() = runBlocking {
        withStore(MemoryPreferences()) { store ->
            val files = workspaceFileOrderKey(WorkspaceStorageArea.FILES, "")
            val linux = workspaceFileOrderKey(WorkspaceStorageArea.LINUX, "")
            store.setWorkspaceFileOrder("first", files, listOf("b", "a", "b"))
            store.setWorkspaceFileOrder("first", linux, listOf("a", "b"))
            store.setWorkspaceFileOrder("second", files, listOf("a", "b"))
            assertEquals(listOf("b", "a"), store.workspaceFileOrdersFlow("first").first().getValue(files))
            store.setWorkspaceFileOrder("first", files, emptyList())
            assertEquals(mapOf(linux to listOf("a", "b")), store.workspaceFileOrdersFlow("first").first())
            store.clearWorkspaceFileOrders("first")
            assertEquals(emptyMap<String, List<String>>(), store.workspaceFileOrdersFlow("first").first())
            assertEquals(mapOf(files to listOf("a", "b")), store.workspaceFileOrdersFlow("second").first())
        }
    }

    private class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }
}
