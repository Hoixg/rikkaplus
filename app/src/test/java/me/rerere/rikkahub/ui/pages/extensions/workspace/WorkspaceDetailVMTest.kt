package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDetailVMTest {
    private fun file(name: String, path: String) = WorkspaceFileEntry(path, name, false, 1, 0)
    private fun dir(name: String, path: String) = WorkspaceFileEntry(path, name, true, 0, 0)

    @Test
    fun `drag targets exclude other directory levels`() {
        assertTrue(isWorkspaceReorderTarget("src/b", "src/a"))
        assertTrue(isWorkspaceReorderTarget("b", "a"))
        assertTrue(isWorkspaceReorderTarget("src", "a"))
        assertFalse(isWorkspaceReorderTarget("src/a", "a"))
        assertFalse(isWorkspaceReorderTarget("src/sub/a", "src/a"))
        assertFalse(isWorkspaceReorderTarget("other/b", "src/a"))
        assertTrue(isWorkspaceReorderTarget("src/a", null))
    }

    @Test
    fun `changing storage area clears directory data but retains area scoped orders`() {
        val original = WorkspaceDetailState(
            path = "src",
            entries = listOf(file("a", "src/a")),
            expandedPaths = setOf("src/sub"),
            childrenCache = mapOf("src/sub" to listOf(file("b", "src/sub/b"))),
            loadingPaths = setOf("src/sub"),
            highlightPath = "src/a",
            customFileOrders = mapOf(workspaceFileOrderKey(WorkspaceStorageArea.FILES, "src") to listOf("src/a")),
        )
        val changed = original.forStorageArea(WorkspaceStorageArea.LINUX)
        assertEquals(WorkspaceStorageArea.LINUX, changed.area)
        assertEquals("", changed.path)
        assertTrue(changed.entries.isEmpty())
        assertTrue(changed.childrenCache.isEmpty())
        assertTrue(changed.expandedPaths.isEmpty())
        assertTrue(changed.loadingPaths.isEmpty())
        assertNull(changed.highlightPath)
        assertEquals(original.customFileOrders, changed.customFileOrders)
    }

    @Test
    fun `deleting expanded child immediately removes it from displayed rows`() {
        val original = WorkspaceDetailState(
            entries = listOf(dir("src", "src")),
            expandedPaths = setOf("src"),
            childrenCache = mapOf("src" to listOf(file("a", "src/a"), file("b", "src/b"))),
        )
        val deleted = original.withoutWorkspaceEntry("src/a")
        val rows = flattenWorkspaceTree(deleted.entries, deleted.expandedPaths, deleted.childrenCache)
        assertEquals(listOf("src", "src/b"), rows.map { it.entry.path })
        assertNull(reorderWorkspaceEntries(deleted.childrenCache.getValue("src"), emptyList(), "src/a", "src/b"))
    }

    @Test
    fun `deleting a directory drops its subtree without matching similarly named siblings`() {
        val original = WorkspaceDetailState(
            entries = listOf(dir("src", "src"), dir("src2", "src2")),
            expandedPaths = setOf("src", "src/sub", "src2"),
            childrenCache = mapOf(
                "src" to listOf(dir("sub", "src/sub")),
                "src/sub" to listOf(file("a", "src/sub/a")),
                "src2" to listOf(file("b", "src2/b")),
            ),
            highlightPath = "src/sub/a",
        )
        val deleted = original.withoutWorkspaceEntry("src")
        assertEquals(listOf("src2"), deleted.entries.map { it.path })
        assertEquals(setOf("src2"), deleted.expandedPaths)
        assertEquals(setOf("src2"), deleted.childrenCache.keys)
        assertNull(deleted.highlightPath)
    }

    @Test
    fun `fresh listings prune caches of directories that no longer exist`() {
        val original = WorkspaceDetailState(
            entries = listOf(dir("src", "src"), dir("docs", "docs")),
            expandedPaths = setOf("src", "src/sub", "docs"),
            childrenCache = mapOf(
                "src" to listOf(dir("sub", "src/sub")),
                "src/sub" to listOf(file("a", "src/sub/a")),
                "docs" to listOf(file("b", "docs/b")),
            ),
        )
        val loaded = original.withDirectoryListing("", listOf(dir("docs", "docs")))
        assertEquals(setOf("docs"), loaded.childrenCache.keys)
        assertEquals(setOf("docs"), loaded.expandedPaths)
        val childrenLoaded = original.withDirectoryListing("src", listOf(file("new", "src/new")))
        assertEquals(setOf("src", "docs"), childrenLoaded.expandedPaths)
        assertFalse("src/sub" in childrenLoaded.childrenCache)
        assertEquals(listOf("src/new"), childrenLoaded.childrenCache.getValue("src").map { it.path })
    }

    @Test
    fun `manual move can return a file to its original position`() {
        val entries = listOf(file("a", "a"), file("b", "b"), file("c", "c"))

        val moved = reorderWorkspaceEntries(entries, emptyList(), "a", "b")!!
        assertEquals(listOf("b", "a", "c"), moved)
        assertEquals(listOf("a", "b", "c"), reorderWorkspaceEntries(entries, moved, "a", "b"))
    }

    @Test
    fun `consecutive manual moves use the latest saved order`() {
        val entries = listOf(file("c", "c"), file("a", "a"), file("b", "b"))
        var order = reorderWorkspaceEntries(entries, emptyList(), "a", "b")!!
        order = reorderWorkspaceEntries(entries, order, "a", "c")!!
        assertEquals(listOf("b", "c", "a"), order)
        order = reorderWorkspaceEntries(entries, order, "a", "b")!!
        assertEquals(listOf("a", "b", "c"), order)
        assertEquals(order, sortWorkspaceEntries(entries, WorkspaceFileSortMode.MANUAL, false, order).map { it.path })
    }

    @Test
    fun `expanded child directories retain their own manual order`() {
        val entries = listOf(dir("src", "src"), file("readme.md", "readme.md"))
        val children = listOf(file("a", "src/a"), file("b", "src/b"), file("c", "src/c"))
        val moved = reorderWorkspaceEntries(children, emptyList(), "src/a", "src/b")!!
        val restored = reorderWorkspaceEntries(children, moved, "src/a", "src/b")!!
        val rows = flattenWorkspaceTree(entries, setOf("src"), mapOf("src" to children)) { parent, siblings ->
            sortWorkspaceEntries(siblings, WorkspaceFileSortMode.MANUAL, false, if (parent == "src") restored else emptyList())
        }
        assertEquals(listOf("src", "src/a", "src/b", "src/c", "readme.md"), rows.map { it.entry.path })
    }

    @Test
    fun `manual order ignores removed paths and includes new files`() {
        val entries = listOf(file("c", "c"), file("a", "a"), file("b", "b"))
        assertEquals(
            listOf("b", "a", "c"),
            sortWorkspaceEntries(entries, WorkspaceFileSortMode.MANUAL, false, listOf("missing", "b", "a")).map { it.path },
        )
        assertEquals(listOf("b", "c", "a"), reorderWorkspaceEntries(entries, listOf("missing", "b", "a"), "a", "c"))
    }

    @Test
    fun `manual moves reject missing entries same positions and different parents`() {
        val entries = listOf(file("a", "a"), file("b", "b"), file("a", "src/a"))
        assertNull(reorderWorkspaceEntries(entries, emptyList(), "missing", "a"))
        assertNull(reorderWorkspaceEntries(entries, emptyList(), "a", "missing"))
        assertNull(reorderWorkspaceEntries(entries, emptyList(), "a", "a"))
        assertNull(reorderWorkspaceEntries(entries, emptyList(), "a", "src/a"))
    }

    @Test
    fun `automatic sorting keeps folders first in both directions`() {
        val entries = listOf(
            file("b.txt", "b.txt").copy(sizeBytes = 10, updatedAt = 30),
            file("a.md", "a.md").copy(sizeBytes = 30, updatedAt = 10),
            dir("folder", "folder"),
        )
        val expectedOrders = mapOf(
            WorkspaceFileSortMode.NAME to listOf("folder", "a.md", "b.txt"),
            WorkspaceFileSortMode.MODIFIED_TIME to listOf("folder", "a.md", "b.txt"),
            WorkspaceFileSortMode.SIZE to listOf("folder", "b.txt", "a.md"),
            WorkspaceFileSortMode.TYPE to listOf("folder", "a.md", "b.txt"),
        )
        for ((mode, expected) in expectedOrders) {
            val ascending = sortWorkspaceEntries(entries, mode, false, listOf("b.txt", "a.md")).map { it.path }
            val descending = sortWorkspaceEntries(entries, mode, true, emptyList()).map { it.path }
            assertEquals(expected, ascending)
            assertEquals("folder", descending.first())
            assertEquals(ascending.drop(1).reversed(), descending.drop(1))
        }
    }

    @Test
    fun `expanded directory inlines cached children`() {
        val entries = listOf(dir("src", "src"), file("readme.md", "readme.md"))
        val cache = mapOf("src" to listOf(file("main.kt", "src/main.kt")))

        val rows = flattenWorkspaceTree(entries, setOf("src"), cache)

        assertEquals(
            listOf("src" to 0, "src/main.kt" to 1, "readme.md" to 0),
            rows.map { it.entry.path to it.depth },
        )
    }

    @Test
    fun `folder export plan always puts parents before children`() {
        val listing = mapOf(
            "notes" to listOf(dir("sub", "notes/sub"), file("a.txt", "notes/a.txt")),
            "notes/sub" to listOf(file("b.txt", "notes/sub/b.txt")),
        )

        val plan = planWorkspaceFolderExport("notes", listing)

        assertEquals(
            listOf("notes/sub", "notes/sub/b.txt", "notes/a.txt"),
            plan.map { it.sourcePath },
        )
    }

    @Test
    fun `script command uses the real workspace path`() {
        assertEquals(
            "python3 '/workspace/scripts/test_preview.py'",
            scriptCommand(file("test_preview.py", "scripts/test_preview.py")),
        )
    }

    @Test
    fun `script command quotes paths with spaces`() {
        assertEquals(
            "python3 '/workspace/my scripts/demo.py'",
            scriptCommand(file("demo.py", "my scripts/demo.py")),
        )
    }
}
