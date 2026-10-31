package me.rerere.rikkahub.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceType
import me.rerere.workspace.ssh.SshConnectionPool
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedWorkspaceRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixture(scope: CoroutineScope, installed: Boolean = false): Fixture {
        val manager = WorkspaceManager(temp.newFolder(), environmentDir = temp.newFolder())
        if (installed) {
            File(manager.sharedLinuxDir, "bin").mkdirs()
            File(manager.sharedLinuxDir, "bin/sh").writeText("installed")
        }
        val settings = SettingsStore(object : DataStore<Preferences> {
            private val values = MutableStateFlow(emptyPreferences())
            override val data = values
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(values.value).also { values.value = it }
        }, scope)
        val dao = MemoryWorkspaceDao()
        val repository = WorkspaceRepository(dao, manager, RootfsInstaller(manager), settings, SshConnectionPool())
        return Fixture(manager, dao, repository)
    }

    private data class Fixture(val manager: WorkspaceManager, val dao: MemoryWorkspaceDao, val repository: WorkspaceRepository)

    @Test
    fun `new local workspaces immediately see the installed shared environment`() = runTest {
        val f = fixture(backgroundScope, installed = true)
        val first = f.repository.create("first")
        val second = f.repository.create("second")

        assertEquals(WorkspaceShellStatus.READY.name, first.shellStatus)
        assertEquals(WorkspaceShellStatus.READY.name, second.shellStatus)
        assertTrue(f.repository.listFlow().first().all { it.shellStatus == WorkspaceShellStatus.READY.name })
        assertFalse(File(f.manager.workspaceDir(second.root), "linux").exists())
        // An unused URL must never be contacted merely to reuse an installed environment.
        assertTrue(f.repository.installRootfs(second.id, "http://127.0.0.1:1/unused.tar.gz"))
    }

    @Test
    fun `install status is shared only by local workspaces and cancellation restores readiness`() = runTest {
        val f = fixture(backgroundScope, installed = true)
        val first = f.repository.create("first")
        val second = f.repository.create("second")
        val ssh = remote()
        f.dao.upsert(ssh)
        val entered = CompletableDeferred<Unit>()
        val keepInstalling = CompletableDeferred<Unit>()
        val installation = async {
            f.repository.installRootfs(first.id, "unused", replaceExisting = true, beforeInstall = {
                entered.complete(Unit)
                keepInstalling.await()
            })
        }
        entered.await()

        assertTrue(f.repository.isEnvironmentInstalling)
        assertEquals(WorkspaceShellStatus.INSTALLING.name, f.repository.getById(second.id)!!.shellStatus)
        assertEquals(ssh, f.repository.getById(ssh.id))
        assertEquals(ssh, f.repository.listFlow().first().single { it.id == ssh.id })
        assertTrue(runCatching { f.repository.executeCommand(first.id, "echo local") }.exceptionOrNull()!!.message!!.contains("Linux 环境"))
        // SSH still follows its own backend instead of being rejected as a local installation.
        assertTrue(runCatching { f.repository.executeCommand(ssh.id, "echo remote") }.exceptionOrNull()!!.message!!.contains("SSH workspace config"))
        val duplicate = runCatching { f.repository.installRootfs(second.id, "unused", replaceExisting = true) }
        assertTrue(duplicate.isFailure)
        assertTrue(f.repository.isEnvironmentInstalling)

        installation.cancelAndJoin()

        assertFalse(f.repository.isEnvironmentInstalling)
        assertEquals(WorkspaceShellStatus.READY.name, f.repository.getById(first.id)!!.shellStatus)
        assertEquals(WorkspaceShellStatus.READY.name, f.repository.getById(second.id)!!.shellStatus)
        assertEquals(ssh, f.dao.getById(ssh.id))
    }

    @Test
    fun `deleting every local workspace retains the shared environment for future workspaces`() = runTest {
        val f = fixture(backgroundScope, installed = true)
        val workspace = f.repository.create("first")
        f.manager.writeText(workspace.root, "project.txt", "data")

        assertTrue(f.repository.delete(workspace.id))

        assertFalse(f.manager.workspaceDir(workspace.root).exists())
        assertEquals("installed", File(f.manager.sharedLinuxDir, "bin/sh").readText())
        assertEquals(WorkspaceShellStatus.READY.name, f.repository.create("next").shellStatus)
    }

    @Test
    fun `SSH installation is rejected without changing local or remote state`() = runTest {
        val f = fixture(backgroundScope, installed = true)
        val local = f.repository.create("local")
        val ssh = remote()
        f.dao.upsert(ssh)

        assertTrue(runCatching { f.repository.installRootfs(ssh.id, "unused") }.exceptionOrNull() is IllegalArgumentException)

        assertFalse(f.repository.isEnvironmentInstalling)
        assertEquals(ssh, f.repository.getById(ssh.id))
        assertEquals(WorkspaceShellStatus.READY.name, f.repository.getById(local.id)!!.shellStatus)
    }

    private fun remote() = WorkspaceEntity(
        id = "ssh", root = "ssh", name = "remote", type = WorkspaceType.SSH.name,
        shellStatus = WorkspaceShellStatus.READY.name, createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `independent choice survives reload and is unaffected by shared installation state`() = runTest {
        val f = fixture(backgroundScope, installed = true)
        val shared = f.repository.create("shared")
        val isolated = f.repository.create("private", independentEnvironment = true)
        assertTrue(f.dao.getById(isolated.id)!!.independentEnvironment)
        assertEquals(WorkspaceShellStatus.DISABLED.name, f.repository.getById(isolated.id)!!.shellStatus)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = async {
            f.repository.installRootfs(shared.id, "unused", replaceExisting = true, beforeInstall = {
                entered.complete(Unit); gate.await()
            })
        }
        entered.await()
        assertFalse(f.repository.isEnvironmentInstalling(isolated))
        assertEquals(WorkspaceShellStatus.DISABLED.name, f.repository.getById(isolated.id)!!.shellStatus)
        assertNull(f.repository.installProgress(isolated.id).first())
        job.cancelAndJoin()

        File(f.manager.linuxDir(isolated.root), "bin").mkdirs()
        File(f.manager.linuxDir(isolated.root), "bin/sh").writeText("private system")
        val reloadedManager = WorkspaceManager(f.manager.workspaceDir(isolated.root).parentFile!!, environmentDir = f.manager.environmentDir)
        val reloaded = WorkspaceRepository(f.dao, reloadedManager, RootfsInstaller(reloadedManager), SettingsStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value)
        }, backgroundScope), SshConnectionPool())
        assertTrue(reloaded.getById(isolated.id)!!.independentEnvironment)
        assertEquals(WorkspaceShellStatus.READY.name, reloaded.getById(isolated.id)!!.shellStatus)
    }

    @Test
    fun `independent install does not mark shared or SSH workspaces as busy`() = runTest {
        val f = fixture(backgroundScope, installed = true)
        val shared = f.repository.create("shared")
        val isolated = f.repository.create("private", independentEnvironment = true)
        f.dao.upsert(remote())
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = async {
            f.repository.installRootfs(isolated.id, "unused", beforeInstall = { entered.complete(Unit); gate.await() })
        }
        entered.await()
        assertTrue(f.repository.isEnvironmentInstalling(isolated))
        assertFalse(f.repository.isEnvironmentInstalling(shared))
        assertFalse(f.repository.isEnvironmentInstalling(remote()))
        assertEquals(WorkspaceShellStatus.READY.name, f.repository.getById(shared.id)!!.shellStatus)
        job.cancelAndJoin()
        assertEquals(WorkspaceShellStatus.DISABLED.name, f.repository.getById(isolated.id)!!.shellStatus)
    }

    private class MemoryWorkspaceDao : WorkspaceDAO {
        private val values = MutableStateFlow<List<WorkspaceEntity>>(emptyList())
        override fun listFlow(): Flow<List<WorkspaceEntity>> = values
        override suspend fun getById(id: String) = values.value.firstOrNull { it.id == id }
        override suspend fun getAll() = values.value
        override suspend fun upsert(workspace: WorkspaceEntity) {
            values.value = values.value.filterNot { it.id == workspace.id } + workspace
        }
        override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Int =
            getById(id)?.let { upsert(it.copy(shellStatus = shellStatus, updatedAt = updatedAt)); 1 } ?: 0
        override suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long): Int =
            getById(id)?.let { upsert(it.copy(shellCompatibilityMode = enabled, updatedAt = updatedAt)); 1 } ?: 0
        override suspend fun deleteById(id: String): Int {
            val exists = getById(id) != null
            values.value = values.value.filterNot { it.id == id }
            return if (exists) 1 else 0
        }
    }
}
