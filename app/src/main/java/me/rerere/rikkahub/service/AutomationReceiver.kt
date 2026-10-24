package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.automation.AutomationAlarms
import me.rerere.rikkahub.data.automation.AutomationRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AutomationReceiver : BroadcastReceiver(), KoinComponent {
    private val scope: AppScope by inject()
    private val repository: AutomationRepository by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AutomationAlarms.FIRE || intent.action == AutomationAlarms.RETRY) {
            runCatching { AutomationService.start(context, intent.takeIf { it.action == AutomationAlarms.FIRE }) }
                .onFailure { android.util.Log.e("Automation", "Unable to dispatch alarm; configuration retained", it) }
            return
        }
        val pending = goAsync()
        scope.launch {
            try { repository.reconcile(intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) }
            catch (e: Exception) { android.util.Log.e("Automation", "Unable to restore alarms", e) }
            finally { pending.finish() }
        }
    }
}
