package me.rerere.rikkahub

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.remote.ModelsDevUpdater
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.RestoreFailedException
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import java.io.File

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val MEDIA_CREATION_NOTIFICATION_CHANNEL_ID = "media_creation"
const val MEMORY_DREAM_NOTIFICATION_CHANNEL_ID = "memory_dream"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore files and settings before eager Koin singletons or workers can access them.
        try {
            val restored = runBlocking(Dispatchers.IO) {
                BackupManager.applyPendingRestore(this@RikkaHubApp, JsonInstant)
            }
            if (restored) {
                Toast.makeText(this, R.string.backup_page_restore_success, Toast.LENGTH_LONG).show()
            }
        } catch (e: RestoreFailedException) {
            Log.e(TAG, "Backup restore rolled back", e)
            Toast.makeText(this, "备份恢复失败，已保留原数据。请重新导入备份。", Toast.LENGTH_LONG).show()
        }
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        // Extract builtin skills from assets after install/update
        extractBuiltinSkills()

        // Load models.dev data for model capability matching, then refresh it in background
        updateModelsDev()

        // Resume media generations interrupted by the last process death
        resumeMediaCreations()
        resumeMemoryConsolidation()
        resumeAutomation()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun resumeAutomation() {
        fun reconcile() {
            get<AppScope>().launch {
                runCatching { get<me.rerere.rikkahub.data.automation.AutomationRepository>().reconcile() }
                    .onFailure { Log.e(TAG, "Unable to restore scheduled tasks", it) }
            }
        }
        reconcile()
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) reconcile()
        })
    }

    private fun resumeMediaCreations() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<MediaCreationService>().resumePending()
            }.onFailure {
                Log.e(TAG, "resumeMediaCreations failed", it)
            }
        }
    }

    private fun resumeMemoryConsolidation() {
        // 应用被切走后进程随时可能被冻结，等不到空闲计时结束，这时直接开始整理
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) get<MemoryConsolidationScheduler>().onAppHidden()
            }
        )
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<MemoryConsolidationScheduler>().resumePending()
            }.onFailure {
                Log.e(TAG, "resumeMemoryConsolidation failed", it)
            }
        }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val count = get<SettingsStore>().incrementLaunchCount()
                Log.i(TAG, "incrementLaunchCount: $count")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun extractBuiltinSkills() {
        get<AppScope>().launch(Dispatchers.IO) {
            get<SkillManager>().ensureBuiltinSkillsExtracted()
        }
    }

    private fun updateModelsDev() {
        get<AppScope>().launch {
            get<ModelsDevUpdater>().start()
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val mediaCreationChannel = NotificationChannelCompat
            .Builder(MEDIA_CREATION_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(getString(R.string.media_creation_title))
            .build()
        notificationManager.createNotificationChannel(mediaCreationChannel)

        val memoryDreamChannel = NotificationChannelCompat
            .Builder(MEMORY_DREAM_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.memory_dream_title))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(memoryDreamChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob() +
        Dispatchers.Main +
        CoroutineName("AppScope") +
        CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "AppScope exception", e)
        }
)
