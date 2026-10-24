package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.automation.AutomationRepository
import me.rerere.rikkahub.data.automation.RunState
import org.koin.android.ext.android.inject

/** Only dispatches alarms/queued runs. ChatService owns generation and its existing foreground lease. */
class AutomationService : Service() {
    companion object {
        fun start(context: Context, source: Intent? = null) {
            ContextCompat.startForegroundService(context, Intent(context, AutomationService::class.java).apply {
                source?.extras?.let { putExtras(it) }
                data = source?.data
            })
        }
    }
    private val scope: AppScope by inject()
    private val repository: AutomationRepository by inject()
    private val chat: ChatService by inject()
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub).setContentTitle("定时任务")
            .setContentText("正在安排任务执行").setOngoing(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 2005, Intent(this, RouteActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, 2005, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(2005, notification)
        scope.launch {
            try {
                repository.recover()
                intent?.data?.lastPathSegment?.let { id ->
                    val revision = intent.getStringExtra("revision") ?: return@let
                    repository.queue(id, revision, dueAt = intent.getLongExtra("at", 0))
                }
                repository.active().filter { it.state == RunState.QUEUED }.forEach { run ->
                    try { chat.startAutomation(run) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { repository.finish(run.id, RunState.FAILED, e.message.orEmpty()) }
                }
                repository.refreshRetry()
            } catch (e: Exception) {
                android.util.Log.e("Automation", "Unable to dispatch tasks", e)
            } finally { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
}
