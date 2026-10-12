package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceFileGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(WorkspaceDetailState())
    private val openedPaths = mutableListOf<String>()
    private val moves = mutableListOf<Pair<String, String>>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val handleDescription = context.getString(R.string.workspace_detail_reorder_file)

    private fun show(directories: Boolean = false, loading: Boolean = false) {
        state = WorkspaceDetailState(
            entries = listOf("a", "b", "c").map { name ->
                val path = if (directories) name else "$name.txt"
                WorkspaceFileEntry(path, path, directories, 1, 0)
            },
            loading = loading,
            hasLoadedDirectory = true,
        )
        compose.setContent {
            MaterialTheme {
                WorkspaceFilesPage(
                    workspaceId = "gesture-test",
                    state = state,
                    isActive = true,
                    contentPadding = PaddingValues(),
                    onSelectArea = {},
                    onGoUp = {},
                    onToggleExpand = {},
                    onResolvePreview = { _, callback -> callback(null) },
                    highlightPath = null,
                    onOpen = { openedPaths += it.path },
                    onDelete = {},
                    onRename = {},
                    onReorderFile = { area, from, to ->
                        val key = workspaceFileOrderKey(area, state.path)
                        reorderWorkspaceEntries(
                            entries = state.entries,
                            manualOrder = state.customFileOrders[key].orEmpty(),
                            fromPath = from,
                            toPath = to,
                        )?.let { order ->
                            moves += from to to
                            state = state.copy(customFileOrders = state.customFileOrders + (key to order))
                        }
                    },
                    onExport = {},
                    onBatchExport = {},
                    onShare = {},
                    onRunScript = {},
                )
            }
        }
    }

    private fun assertNotSelecting() {
        compose.onNodeWithText("取消多选").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(handleDescription).assertCountEquals(3)
        compose.runOnIdle { assertTrue(openedPaths.isEmpty()) }
    }

    private fun dragHandle(fromIndex: Int, toIndex: Int) {
        val handles = compose.onAllNodesWithContentDescription(handleDescription)
        val from = handles[fromIndex].fetchSemanticsNode().boundsInRoot.center
        val to = handles[toIndex].fetchSemanticsNode().boundsInRoot.center
        handles[fromIndex].performTouchInput {
            down(center)
            advanceEventTime(1_500)
            moveBy(Offset.Zero)
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveBy(to - from, delayMillis = 200) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun holdingFileHandleDoesNotEnterBatchSelectionOrOpenFile() {
        show()
        repeat(2) {
            compose.onAllNodesWithContentDescription(handleDescription)[0]
                .performTouchInput { longClick(durationMillis = 1_500) }
            assertNotSelecting()
        }
        compose.runOnIdle { assertTrue(moves.isEmpty()) }
    }

    @Test
    fun fileHandleCanDragDownAndBackUpWithoutEnteringBatchSelection() {
        show()
        val key = workspaceFileOrderKey(WorkspaceStorageArea.FILES, "")
        dragHandle(0, 1)
        assertNotSelecting()
        compose.runOnIdle {
            assertEquals(listOf("b.txt", "a.txt", "c.txt"), state.customFileOrders[key])
        }
        dragHandle(1, 0)
        assertNotSelecting()
        compose.runOnIdle {
            assertEquals(listOf("a.txt", "b.txt", "c.txt"), state.customFileOrders[key])
        }
    }

    @Test
    fun fileContentStillSupportsLongPressSelectionAndClickToOpen() {
        show()
        compose.onNodeWithText("a.txt").performClick()
        compose.runOnIdle {
            assertEquals(listOf("a.txt"), openedPaths)
            openedPaths.clear()
        }
        compose.onNodeWithText("a.txt").performTouchInput { longClick() }
        compose.onNodeWithText("取消多选").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(handleDescription).assertCountEquals(0)
        compose.runOnIdle { assertTrue(openedPaths.isEmpty()) }
        compose.onNodeWithText("取消多选").performClick()
        assertNotSelecting()
    }

    @Test
    fun folderHandleStillSupportsDraggingWithoutBatchSelection() {
        show(directories = true)
        dragHandle(0, 1)
        assertNotSelecting()
        compose.runOnIdle {
            assertEquals(
                listOf("b", "a", "c"),
                state.customFileOrders[workspaceFileOrderKey(WorkspaceStorageArea.FILES, "")],
            )
        }
    }

    @Test
    fun disabledHandleDuringLoadingDoesNotFallThroughToFileSelection() {
        show(loading = true)
        compose.onAllNodesWithContentDescription(handleDescription)[0]
            .performTouchInput { longClick(durationMillis = 1_500) }
        assertNotSelecting()
        compose.runOnIdle { assertTrue(moves.isEmpty()) }
    }
}
