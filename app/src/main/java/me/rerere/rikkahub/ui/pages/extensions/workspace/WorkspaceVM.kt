package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.SharedEnvironmentInfo

data class SharedEnvironmentState(
    val loading: Boolean = false,
    val info: SharedEnvironmentInfo? = null,
    val error: String? = null,
)

class WorkspaceVM(
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
    private val backgroundShellManager: BackgroundShellManager,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val mutableEnvironment = MutableStateFlow(SharedEnvironmentState())
    val sharedEnvironment = mutableEnvironment.asStateFlow()

    fun refreshSharedEnvironment() {
        if (sharedEnvironment.value.loading) return
        mutableEnvironment.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                mutableEnvironment.value = SharedEnvironmentState(info = repository.sharedEnvironmentInfo())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableEnvironment.update { it.copy(loading = false, error = error.message ?: "读取共享环境失败") }
            }
        }
    }

    fun create(name: String, independentEnvironment: Boolean = false) {
        viewModelScope.launch {
            runCatching { repository.create(name, independentEnvironment) }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            terminalSessionManager.closeWorkspace(workspace.root)
            backgroundShellManager.killWorkspace(workspace.id)
            repository.delete(workspace.id)
        }
    }
}
