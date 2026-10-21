package me.rerere.workspace.ssh

import me.rerere.workspace.WorkspaceStorageArea
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SshDirectoryCreationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: SshServer
    private val pool = SshConnectionPool()
    private lateinit var backend: SshWorkspaceBackend

    @Before
    fun setUp() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(temp.root, "host-key").toPath())
            passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                username == "test" && password == "test"
            }
            fileSystemFactory = VirtualFileSystemFactory(temp.root.toPath())
            subsystemFactories = listOf(SftpSubsystemFactory())
            // No exec/shell handler: creating folders must only use SFTP.
            start()
        }
        val config = SshConnectionConfig(
            host = "127.0.0.1",
            port = server.port,
            username = "test",
            authType = SshAuthType.PASSWORD,
            password = "test",
            remoteDir = "/project",
        )
        val hostKey = assertThrows(SshUnknownHostKeyException::class.java) { pool.probe(config) }.hostKey
        backend = SshWorkspaceBackend(pool, "ssh-project", config.copy(hostKey = hostKey))
    }

    @After
    fun tearDown() {
        pool.close()
        server.stop(true)
    }

    @Test
    fun createsDirectoryOnRemoteFileAreaWithoutShell() {
        val entry = backend.createDirectory(WorkspaceStorageArea.FILES, "资料 [draft]")

        assertTrue(entry.isDirectory)
        assertEquals("资料 [draft]", entry.path)
        assertTrue(File(temp.root, "project/资料 [draft]").isDirectory)
        backend.writeText("资料 [draft]/notes.md", "hello", overwrite = false)
        assertEquals("hello", backend.readText("资料 [draft]/notes.md"))
    }

    @Test
    fun rejectsExistingDirectoriesAndFilesWithoutChangingTheirContents() {
        backend.createDirectory(WorkspaceStorageArea.FILES, "notes")
        backend.writeText("keep.txt", "original", overwrite = false)

        assertThrows(IllegalArgumentException::class.java) {
            backend.createDirectory(WorkspaceStorageArea.FILES, "notes")
        }
        assertThrows(IllegalArgumentException::class.java) {
            backend.createDirectory(WorkspaceStorageArea.FILES, "keep.txt")
        }
        assertEquals("original", backend.readText("keep.txt"))
    }

    @Test
    fun rejectsPathsOutsideRemoteWorkspace() {
        assertThrows(IllegalArgumentException::class.java) {
            backend.createDirectory(WorkspaceStorageArea.FILES, "../outside")
        }
        assertFalse(File(temp.root, "outside").exists())
    }
}
