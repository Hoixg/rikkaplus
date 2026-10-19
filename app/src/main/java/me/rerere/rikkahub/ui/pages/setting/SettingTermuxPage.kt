@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:max-line-length")

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.model.TermuxConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionTermux
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingTermuxPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.termuxConfig
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val permission = rememberPermissionState(PermissionTermux)
    PermissionManager(permission)
    var state by remember { mutableStateOf(TermuxIntegration.state(context)) }
    var verifying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = TermuxIntegration.state(context) }
    LaunchedEffect(permission.allRequiredPermissionsGranted) { state = TermuxIntegration.state(context) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    fun update(transform: (TermuxConfig) -> TermuxConfig) {
        vm.updateSettings { it.copy(termuxConfig = transform(it.termuxConfig).normalized()) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Termux") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardGroup(title = { Text("连接") }) {
                item(
                    onClick = {
                        context.packageManager.getLaunchIntentForPackage("com.termux")?.let(context::startActivity)
                            ?: uriHandler.openUri("https://github.com/termux/termux-app/releases")
                    },
                    headlineContent = { Text("打开 Termux") },
                    supportingContent = {
                        Text(if (state ==
                            TermuxIntegration.State.NOT_INSTALLED) {
                            "尚未安装，点击获取"
                        } else {
                            "已安装"
                        })
                    },
                )
                item(
                    onClick = { permission.requestPermissions() },
                    headlineContent = { Text("命令执行权限") },
                    supportingContent = {
                        Text(
                            if (permission.allRequiredPermissionsGranted) "已授权" else "点击授权在 Termux 中执行命令"
                        )
                    },
                )
                formItem(
                    headlineContent = { Text("连接检查") },
                    supportingContent = {
                        Text(result ?: if (config.lastVerifiedAtMs > 0) "上次检查通过" else "尚未检查")
                    },
                ) {
                    Button(
                        enabled = !verifying && state != TermuxIntegration.State.NOT_INSTALLED,
                        onClick = {
                            if (!permission.allRequiredPermissionsGranted) {
                                permission.requestPermissions()
                            } else {
                                scope.launch {
                                    verifying = true
                                    try {
                                        val check = TermuxIntegration.verify(context, config.verifyTimeoutMs)
                                        result = when (check) {
                                            TermuxIntegration.VerifyResult.Ok -> "连接正常"
                                            TermuxIntegration.VerifyResult.NotInstalled -> "请先安装并打开 Termux"
                                            TermuxIntegration.VerifyResult.NoPermission -> "请授予 Termux 命令执行权限"
                                            TermuxIntegration.VerifyResult.TimedOut -> "检查超时，请确认已允许外部应用并打开 Termux"
                                            is TermuxIntegration.VerifyResult.UnexpectedOutput -> "返回内容异常：${check.stdout}"
                                            is TermuxIntegration.VerifyResult.OtherError -> check.message
                                        }
                                        update {
                                            it.copy(lastVerifiedAtMs = if (check ==
                                                TermuxIntegration.VerifyResult.Ok) {
                                                System.currentTimeMillis()
                                            } else {
                                                0
                                            })
                                        }
                                    } finally {
                                        verifying = false
                                        state = TermuxIntegration.state(context)
                                    }
                                }
                            }
                        },
                    ) { Text(if (verifying) "正在检查…" else "检查连接") }
                }
            }
            CardGroup(title = { Text("工具权限") }) {
                switchItem(
                    checked = config.approvalRequired,
                    onCheckedChange = { enabled -> update { it.copy(approvalRequired = enabled) } },
                    headlineContent = { Text("执行前审批") },
                    supportingContent = { Text("AI 执行命令、启动会话、输入内容或关闭会话前需要你批准") },
                )
            }
            CardGroup(title = { Text("执行参数") }) {
                formItem(headlineContent = { Text("命令超时（秒）") }) {
                    TermuxNumberInput((config.commandTimeoutMs / 1000).toInt(), 5..600) { value ->
                        update { it.copy(commandTimeoutMs = value * 1000L) }
                    }
                }
                formItem(headlineContent = { Text("连接检查超时（秒）") }) {
                    TermuxNumberInput((config.verifyTimeoutMs / 1000).toInt(), 3..30) { value ->
                        update { it.copy(verifyTimeoutMs = value * 1000L) }
                    }
                }
                formItem(headlineContent = { Text("默认工作目录") }) {
                    var directory by remember(config.defaultWorkingDir) { mutableStateOf(config.defaultWorkingDir) }
                    var focused by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = directory,
                        onValueChange = { directory = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().onFocusChanged {
                            if (focused &&
                                !it.isFocused) {
                                update { latest -> latest.copy(defaultWorkingDir = directory) }
                            }
                            focused = it.isFocused
                        },
                    )
                }
                formItem(headlineContent = { Text("标准输出上限（字节）") }) {
                    TermuxNumberInput(config.maxStdoutBytes, 1000..64000) { value ->
                        update { it.copy(maxStdoutBytes = value) }
                    }
                }
                formItem(headlineContent = { Text("错误输出上限（字节）") }) {
                    TermuxNumberInput(config.maxStderrBytes, 500..16000) { value ->
                        update { it.copy(maxStderrBytes = value) }
                    }
                }
                switchItem(
                    checked = config.aptWrapEnabled,
                    onCheckedChange = { enabled -> update { it.copy(aptWrapEnabled = enabled) } },
                    headlineContent = { Text("包管理兼容处理") },
                    supportingContent = { Text("避免 apt 停在配置确认提示，保留已有配置文件") },
                )
            }
            CardGroup(title = { Text("使用说明") }) {
                formItem {
                    Text("先在 Termux 中完成初始化，再在 ~/.termux/termux.properties 中加入以下配置，然后重新打开 Termux：")
                    SelectionContainer { Text("allow-external-apps=true") }
                    Text("在助手的「本地工具」中开启 Termux 后，AI 才能使用。持久会话首次使用时会安装 tmux。")
                }
            }
        }
    }
}

@Composable
private fun TermuxNumberInput(value: Int, range: IntRange, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.all(Char::isDigit)) text = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text("${range.first}–${range.last}") },
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (focused && !it.isFocused) {
                val updated = (text.toIntOrNull() ?: value).coerceIn(range)
                text = updated.toString()
                onCommit(updated)
            }
            focused = it.isFocused
        },
    )
}
