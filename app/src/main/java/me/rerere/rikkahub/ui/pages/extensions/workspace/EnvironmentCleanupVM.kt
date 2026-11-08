package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.UnusedWorkspaceEnvironment
import me.rerere.rikkahub.data.repository.WorkspaceRepository

data class EnvironmentCleanupState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val cleaningRoot: String? = null,
    val sharedReady: Boolean = false,
    val entries: List<UnusedWorkspaceEnvironment> = emptyList(),
    val error: String? = null,
)

class EnvironmentCleanupVM(private val repository: WorkspaceRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(EnvironmentCleanupState())
    val state = mutableState.asStateFlow()

    fun open() {
        if (state.value.loading || state.value.cleaningRoot != null) {
            mutableState.update { it.copy(visible = true) }
            return
        }
        mutableState.update { it.copy(visible = true, loading = true, error = null) }
        viewModelScope.launch {
            try {
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = error.message ?: "Scan failed") }
            }
        }
    }

    fun close() {
        mutableState.update { it.copy(visible = false) }
    }

    fun clean(entry: UnusedWorkspaceEnvironment) {
        val current = state.value
        if (current.loading || current.cleaningRoot != null || !current.sharedReady || entry !in current.entries) return
        mutableState.update { it.copy(cleaningRoot = entry.environment.root, error = null) }
        viewModelScope.launch {
            try {
                repository.deleteUnusedEnvironment(entry.environment)
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(cleaningRoot = null, error = error.message ?: "Cleanup failed") }
            }
        }
    }

    private suspend fun refresh() {
        val result = repository.unusedEnvironments()
        mutableState.update {
            it.copy(loading = false, cleaningRoot = null, entries = result.entries, sharedReady = result.sharedReady)
        }
    }
}
