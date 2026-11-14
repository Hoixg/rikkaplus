package me.rerere.rikkahub.ui.pages.browser

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.browser.BrowserManager
import me.rerere.rikkahub.browser.BrowserTab
import me.rerere.rikkahub.ui.components.nav.BackButton

/** Ke-style compact address controls. Loading progress never invalidates the native page. */
@Composable
internal fun BrowserToolbar(manager: BrowserManager, tab: BrowserTab?, loading: Boolean, onOpen: (String) -> Unit, onReload: () -> Unit) {
    val url = tab?.url?.takeUnless { it == "about:blank" }.orEmpty()
    val display = remember(url) { if (url.startsWith("rikkaplus://workspace/")) "工作区网页" else Uri.parse(url).host ?: url }
    var input by rememberSaveable(tab?.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(display)) }
    var editing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val progress by remember(manager, tab?.id) {
        manager.progress.map { it[tab?.id] ?: 0 }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = 0)
    LaunchedEffect(display, url) { if (!editing) input = TextFieldValue(display) }
    BackHandler(editing) { focusManager.clearFocus(force = true); keyboard?.hide() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BackButton()
            Box(Modifier.weight(1f)) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(HugeIcons.Earth, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onGo = { if (input.text.isNotBlank()) onOpen(input.text) }),
                            decorationBox = { field ->
                                Box {
                                    if (input.text.isEmpty()) Text("搜索或输入网址", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                    field()
                                }
                            },
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp).onFocusChanged { focus ->
                                if (editing != focus.isFocused) {
                                    editing = focus.isFocused
                                    val value = if (editing) url else display
                                    input = TextFieldValue(value, if (editing) TextRange(0, value.length) else TextRange.Zero)
                                }
                            },
                        )
                        if (editing && input.text.isNotEmpty()) {
                            IconButton(onClick = { input = TextFieldValue("") }) { Icon(HugeIcons.Cancel01, "清空地址", Modifier.size(20.dp)) }
                        } else IconButton(enabled = tab != null, onClick = onReload) {
                            Icon(if (loading) HugeIcons.Cancel01 else HugeIcons.Refresh01, if (loading) "停止加载" else "刷新", Modifier.size(20.dp))
                        }
                    }
                }
                if (loading) LinearProgressIndicator(progress = { progress / 100f },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp).height(2.dp))
            }
        }
    }
}
