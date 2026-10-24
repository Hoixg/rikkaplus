@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.automation.AutomationRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.extensions.automation.AutomationError
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.BackgroundAccess
import me.rerere.rikkahub.utils.appDetailsIntent
import me.rerere.rikkahub.utils.autoStartSettingsIntents
import me.rerere.rikkahub.utils.openBackgroundSettings
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingBackgroundPage() {
    val context = LocalContext.current
    val nav = LocalNavController.current
    val settings = LocalSettings.current
    val repository = koinInject<AutomationRepository>()
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(BackgroundAccess.read(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    val notifications = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= 33) setOf(PermissionNotification) else emptySet(),
    )
    PermissionManager(notifications)
    // Runtime notification requests can return without leaving the activity.
    val runtimeNotificationGranted = notifications.allPermissionsGranted
    LifecycleResumeEffect(runtimeNotificationGranted) {
        val current = BackgroundAccess.read(context)
        val alarmAccessChanged = access.exactAlarms != current.exactAlarms
        access = current
        scope.launch {
            try { repository.reconcile(clockChanged = alarmAccessChanged) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "无法更新任务排程" }
        }
        onPauseOrDispose {}
    }

    fun open(vararg intents: Intent) {
        if (!openBackgroundSettings(context, *intents)) error = "无法打开系统设置，请从手机设置中进入应用详情。"
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("后台运行与权限") }, navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior, colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = padding + PaddingValues(16.dp, 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                CardGroup(title = { Text("系统授权") }) {
                    item(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && !notifications.allPermissionsGranted) notifications.requestPermissions()
                            else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        },
                        headlineContent = { Text("通知权限") },
                        supportingContent = { Text("接收任务完成与工具审批提醒，管理系统通知。") },
                        trailingContent = { Text(if (access.notifications) "已开启" else "未开启") },
                    )
                    item(
                        onClick = if (Build.VERSION.SDK_INT >= 31) ({
                            open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
                        }) else null,
                        headlineContent = { Text("精确闹钟") },
                        supportingContent = { Text("自动排程所需；未授权也可保存停用任务或立即执行。") },
                        trailingContent = { Text(if (access.exactAlarms) "已允许" else "待授权") },
                    )
                    item(
                        onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                        headlineContent = { Text("电池优化") },
                        supportingContent = { Text("在系统列表中找到本应用，可设为不优化。") },
                        trailingContent = { Text(if (access.batteryExempt) "已豁免" else "受优化") },
                    )
                    item(
                        onClick = { open(*autoStartSettingsIntents().toTypedArray()) },
                        headlineContent = { Text("自启动与后台活动") },
                        supportingContent = { Text("在手机设置中允许自启动与后台运行。授权状态需在系统中确认。") },
                        trailingContent = { Text("去确认") },
                    )
                    item(
                        onClick = { open(appDetailsIntent(context)) },
                        headlineContent = { Text("应用系统设置") },
                        supportingContent = { Text("管理后台电池限制、通知及其他应用权限。") },
                    )
                }
            }
            item {
                CardGroup(title = { Text("应用内通知") }) {
                    item(
                        onClick = { nav.navigate(Screen.SettingPreferencesNotification) },
                        headlineContent = { Text("生成通知设置") },
                        supportingContent = { Text("定时任务提醒同时受此设置和每个任务的通知开关控制。") },
                        trailingContent = { Text(if (settings.displaySetting.enableNotificationOnMessageGeneration) "已开启" else "已关闭") },
                    )
                }
            }
            item {
                Text(
                    "生成期间使用前台服务和有超时限制的 CPU 唤醒锁，等待或完成后释放。电池与自启动设置不会阻止手动执行；系统强行停止或厂商后台限制仍可能影响自动触发。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    AutomationError(error) { error = null }
}
