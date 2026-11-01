package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.accessibility.AndroidPhoneControlBackend
import me.rerere.rikkahub.accessibility.PhoneAccessibilityService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPhoneControlPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val connected by PhoneAccessibilityService.connected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var permission by remember { mutableStateOf(AndroidPhoneControlBackend.permissionEnabled(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = AndroidPhoneControlBackend.permissionEnabled(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.phone_control_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup {
                item(
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            .onFailure { toaster.show(it.message.orEmpty(), ToastType.Error) }
                    },
                    headlineContent = { Text(stringResource(R.string.phone_control_service_label)) },
                    supportingContent = { Text(stringResource(when {
                        connected -> R.string.phone_control_connected
                        permission -> R.string.phone_control_waiting
                        else -> R.string.phone_control_not_enabled
                    })) },
                )
            }
            CardGroup {
                switchItem(
                    checked = settings.phoneControlConfig.enabled,
                    onCheckedChange = { enabled ->
                        vm.updateSettings { it.copy(phoneControlConfig = it.phoneControlConfig.copy(enabled = enabled)) }
                        if (!enabled) PhoneAccessibilityService.instance?.snapshots?.clear()
                    },
                    headlineContent = { Text(stringResource(R.string.phone_control_ai_enabled)) },
                    supportingContent = { Text(stringResource(R.string.phone_control_ai_enabled_desc)) },
                )
                switchItem(
                    checked = settings.phoneControlConfig.approvalRequired,
                    onCheckedChange = { required ->
                        vm.updateSettings { it.copy(phoneControlConfig = it.phoneControlConfig.copy(approvalRequired = required)) }
                    },
                    headlineContent = { Text(stringResource(R.string.phone_control_approval_title)) },
                    supportingContent = { Text(stringResource(R.string.phone_control_approval_desc)) },
                )
            }
            CardGroup {
                formItem {
                    Text(stringResource(R.string.phone_control_setup), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.phone_control_capabilities), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
