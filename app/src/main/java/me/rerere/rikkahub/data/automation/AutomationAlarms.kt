package me.rerere.rikkahub.data.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.service.AutomationReceiver

object AutomationAlarms {
    val FIRE = "${BuildConfig.APPLICATION_ID}.automation.FIRE"
    val RETRY = "${BuildConfig.APPLICATION_ID}.automation.RETRY"
    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pending(context: Context, id: String, revision: String = "", at: Long = 0): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AutomationReceiver::class.java).setAction(if (id == "retry") RETRY else FIRE)
            .setData(Uri.Builder().scheme("rikkaplus").authority("automation").appendPath(id).build())
            .putExtra("revision", revision).putExtra("at", at), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancel(context: Context, id: String) {
        val pending = pending(context, id)
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    fun schedule(context: Context, task: AutomationTask) {
        cancel(context, task.id)
        if (!task.enabled || task.nextRunAt == null || !allowed(context)) return
        exact(context, task.nextRunAt, pending(context, task.id, task.revision, task.nextRunAt))
    }

    fun retry(context: Context, waiting: Boolean) {
        cancel(context, "retry")
        if (waiting && allowed(context)) exact(context, System.currentTimeMillis() + 900_000, pending(context, "retry"))
    }

    private fun exact(context: Context, at: Long, pending: PendingIntent) {
        runCatching { context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            .onFailure { android.util.Log.w("Automation", "Exact alarm permission changed", it) }
    }
}
