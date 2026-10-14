package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import android.webkit.MimeTypeMap
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.Tag01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceMountDir
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

private enum class WorkspaceDetailTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    BASIC(HugeIcons.Settings03, R.string.workspace_detail_tab_basic),
    FILES(HugeIcons.File02, R.string.workspace_detail_tab_files);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun WorkspaceDetailPage(
    id: String,
    openFiles: Boolean = false,
    initialPath: String? = null,
    highlightPath: String? = null,
) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val settingsError by vm.settingsError.collectAsStateWithLifecycle()
    val mountError by vm.mountError.collectAsStateWithLifecycle()
    val folderExportResult by vm.folderExportResult.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(
        initialPage = if (openFiles) 1 else 0,
    ) { 2 }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var renameFileName by rememberSaveable { mutableStateOf("") }
    var renamingFile by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var showMountDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by rememberSaveable { mutableStateOf(false) }
    var createFileName by rememberSaveable { mutableStateOf("") }
    var creatingFile by remember { mutableStateOf(false) }
    var showCreateFolderDialog by rememberSaveable { mutableStateOf(false) }
    var createFolderName by rememberSaveable { mutableStateOf("") }
    var creatingFolder by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(vm, initialPath, highlightPath) {
        vm.onPageEntered(initialPath, highlightPath)
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.importFile {
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            } ?: uri.lastPathSegment ?: "imported_file"
            context.contentResolver.openInputStream(uri)?.let { it to fileName }
        }
    }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportFile(entry) { context.contentResolver.openOutputStream(uri) }
    }
    var folderExportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val folderExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val entry = folderExportTarget.also { folderExportTarget = null }
            ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportFolder(
            entry = entry,
            openDestinationTree = { DocumentFile.fromTreeUri(context, uri) },
            openOutputStream = { childUri -> context.contentResolver.openOutputStream(childUri) },
        )
    }
    val directoryExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        vm.exportFilesToDirectory(uri, context.contentResolver)
    }

    BackHandler(enabled = pagerState.currentPage == 1 && state.path.isNotBlank()) {
        vm.goUp()
    }

    LaunchedEffect(folderExportResult) {
        val result = folderExportResult ?: return@LaunchedEffect
        val message = if (result.failures == 0) {
            context.getString(R.string.workspace_detail_folder_export_success, result.folderName)
        } else {
            context.getString(
                R.string.workspace_detail_folder_export_partial,
                result.folderName,
                result.failures,
            )
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        vm.dismissFolderExportResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (pagerState.currentPage == 1 && state.area == WorkspaceStorageArea.FILES) {
                        Box {
                            IconButton(onClick = { showCreateMenu = true }) {
                                Icon(
                                    HugeIcons.Add01,
                                    contentDescription = stringResource(R.string.workspace_detail_create_entry),
                                )
                            }
                            DropdownMenu(
                                expanded = showCreateMenu,
                                onDismissRequest = { showCreateMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.workspace_detail_new_file)) },
                                    leadingIcon = { Icon(HugeIcons.FileAdd, contentDescription = null) },
                                    onClick = {
                                        showCreateMenu = false
                                        createFileName = ""
                                        showCreateFileDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.workspace_detail_new_folder)) },
                                    leadingIcon = { Icon(HugeIcons.FolderAdd, contentDescription = null) },
                                    onClick = {
                                        showCreateMenu = false
                                        createFolderName = ""
                                        showCreateFolderDialog = true
                                    },
                                )
                            }
                        }
                    }
                    if (pagerState.currentPage == 1) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    if (state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .fillMaxSize(),
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = WorkspaceDetailTab.entries,
                modifier = Modifier.padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (page) {
                0 -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    toolApprovalExpanded = state.toolApprovalExpanded,
                    installProgress = installProgress,
                    onInstallRootfs = { showInstallDialog = true },
                    onToolApprovalExpandedChange = vm::setToolApprovalExpanded,
                    onToolApprovalChange = vm::setToolApproval,
                    onShellCompatibilityModeChange = vm::setShellCompatibilityMode,
                    onAddMount = { showMountDialog = true },
                    onRemoveMount = vm::removeMountDir,
                    onMountReadOnlyChange = vm::setMountDirReadOnly,
                )

                1 -> WorkspaceFilesPage(
                    workspaceId = id,
                    state = state,
                    isActive = pagerState.currentPage == 1,
                    contentPadding = PaddingValues(),
                    onSelectArea = vm::selectArea,
                    onGoUp = vm::goUp,
                    onToggleExpand = vm::toggleExpand,
                    onResolvePreview = vm::resolvePreviewFileSilently,
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> vm.open(entry)

                            entry.name.substringAfterLast('.').equals("svg", ignoreCase = true) ->
                                navController.navigate(
                                    Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                )

                            else -> when (entry.detectFileType()) {
                                WorkspaceFileType.TEXT -> navController.navigate(
                                    Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                )

                                WorkspaceFileType.DOCUMENT -> navController.navigate(
                                    Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                )

                                WorkspaceFileType.IMAGE -> vm.resolvePreviewFile(entry) { file ->
                                    previewImageUri = file.absolutePath
                                }

                                WorkspaceFileType.OTHER -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                        file.extension.lowercase()
                                    ) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(intent, null))
                                    }
                                }
                            }
                        }
                    },
                    highlightPath = state.highlightPath,
                    onDelete = { deleteTarget = it },
                    onRename = {
                        renameTarget = it
                        renameFileName = it.name
                    },
                    onReorderFile = vm::moveWorkspaceFile,
                    onBatchExport = { entries ->
                        if (vm.prepareBatchExport(entries)) directoryExportLauncher.launch(null)
                    },
                    onExport = { entry ->
                        if (entry.isDirectory) {
                            folderExportTarget = entry
                            folderExportLauncher.launch(null)
                        } else {
                            exportTarget = entry
                            exportLauncher.launch(entry.name)
                        }
                    },
                    onShare = { entry ->
                        vm.exportToCacheFile(entry, context.cacheDir) { file ->
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    },
                    onRunScript = { entry ->
                        navController.navigate(Screen.WorkspaceTerminal(id))
                        vm.runScriptInTerminal(entry)
                    },
                )
                }
            }
        }
    }

    state.exportResult?.let { result ->
        AlertDialog(
            onDismissRequest = vm::dismissExportResult,
            title = { Text("导出结果") },
            text = { Text(result, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = vm::dismissExportResult) { Text(stringResource(R.string.common_confirm)) }
            },
        )
    }

    if (showCreateFileDialog) {
        val normalizedName = createFileName.trim()
        val fileNameInvalid = normalizedName.isNotEmpty() && !isValidWorkspaceEntryName(normalizedName)
        AlertDialog(
            onDismissRequest = {
                if (!creatingFile) {
                    showCreateFileDialog = false
                    createFileName = ""
                }
            },
            title = { Text(stringResource(R.string.workspace_detail_new_file)) },
            text = {
                OutlinedTextField(
                    value = createFileName,
                    onValueChange = { createFileName = it },
                    label = { Text(stringResource(R.string.workspace_detail_file_name)) },
                    singleLine = true,
                    isError = fileNameInvalid,
                    supportingText = if (fileNameInvalid) {
                        { Text(stringResource(R.string.workspace_detail_file_name_invalid)) }
                    } else {
                        { Text(stringResource(R.string.workspace_detail_file_name_desc)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        creatingFile = true
                        vm.createFile(normalizedName) { entry, error ->
                            creatingFile = false
                            if (entry != null) {
                                showCreateFileDialog = false
                                createFileName = ""
                            } else {
                                Toast.makeText(
                                    context,
                                    error ?: context.getString(R.string.workspace_file_save_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                    enabled = isValidWorkspaceEntryName(normalizedName) && !creatingFile,
                ) {
                    Text(stringResource(R.string.workspace_detail_create_file))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateFileDialog = false
                        createFileName = ""
                    },
                    enabled = !creatingFile,
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    renameTarget?.let { entry ->
        val normalizedName = renameFileName.trim()
        val fileNameInvalid = normalizedName.isNotEmpty() && !isValidWorkspaceEntryName(normalizedName)
        AlertDialog(
            onDismissRequest = {
                if (!renamingFile) {
                    renameTarget = null
                    renameFileName = ""
                }
            },
            title = { Text(stringResource(R.string.common_rename)) },
            text = {
                OutlinedTextField(
                    value = renameFileName,
                    onValueChange = { renameFileName = it },
                    label = { Text(stringResource(R.string.workspace_detail_file_name)) },
                    singleLine = true,
                    isError = fileNameInvalid,
                    supportingText = if (fileNameInvalid) {
                        { Text(stringResource(R.string.workspace_detail_file_name_invalid)) }
                    } else {
                        { Text(stringResource(R.string.workspace_detail_rename_file_desc)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renamingFile = true
                        vm.renameFile(entry, normalizedName) { error ->
                            renamingFile = false
                            if (error == null) {
                                renameTarget = null
                                renameFileName = ""
                            } else {
                                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = isValidWorkspaceEntryName(normalizedName) &&
                        normalizedName != entry.name && !renamingFile,
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        renameTarget = null
                        renameFileName = ""
                    },
                    enabled = !renamingFile,
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showCreateFolderDialog) {
        val normalizedName = createFolderName.trim()
        val folderNameInvalid = normalizedName.isNotEmpty() && !isValidWorkspaceEntryName(normalizedName)
        AlertDialog(
            onDismissRequest = {
                if (!creatingFolder) {
                    showCreateFolderDialog = false
                    createFolderName = ""
                }
            },
            title = { Text(stringResource(R.string.workspace_detail_new_folder)) },
            text = {
                OutlinedTextField(
                    value = createFolderName,
                    onValueChange = { createFolderName = it },
                    label = { Text(stringResource(R.string.workspace_detail_folder_name)) },
                    singleLine = true,
                    isError = folderNameInvalid,
                    supportingText = if (folderNameInvalid) {
                        { Text(stringResource(R.string.workspace_detail_folder_name_invalid)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        creatingFolder = true
                        vm.createFolder(normalizedName) { error ->
                            creatingFolder = false
                            if (error == null) {
                                showCreateFolderDialog = false
                                createFolderName = ""
                            } else {
                                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = isValidWorkspaceEntryName(normalizedName) && !creatingFolder,
                ) {
                    Text(stringResource(R.string.workspace_detail_create_folder))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateFolderDialog = false
                        createFolderName = ""
                    },
                    enabled = !creatingFolder,
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    settingsError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissSettingsError,
            title = { Text(stringResource(R.string.workspace_detail_settings_save_failed)) },
            text = { Text(message.ifBlank { stringResource(R.string.workspace_detail_settings_save_failed) }) },
            confirmButton = {
                TextButton(onClick = vm::dismissSettingsError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    mountError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissMountError,
            title = { Text(stringResource(R.string.workspace_detail_mount_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissMountError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    if (showMountDialog) {
        AddMountDirDialog(
            onDismiss = { showMountDialog = false },
            onConfirm = { mount ->
                vm.addMountDir(mount)
                showMountDialog = false
            },
        )
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }
}

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    toolApprovalExpanded: Boolean?,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    onToolApprovalExpandedChange: (Boolean) -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onShellCompatibilityModeChange: (Boolean) -> Unit,
    onAddMount: () -> Unit,
    onRemoveMount: (String) -> Unit,
    onMountReadOnlyChange: (String, Boolean) -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_workspace_info)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_name)) },
                    supportingContent = {
                        Text(workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Tag01) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_shell_status)) },
                    leadingContent = { CardGroupIcon(HugeIcons.ComputerTerminal01) },
                    trailingContent = { ShellStatusBadge(shellStatus) },
                )
            }
        }

        item {
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_enable_shell)) },
            ) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_area_rootfs)) },
                    supportingContent = { Text(stringResource(R.string.workspace_detail_enable_shell_desc)) },
                    leadingContent = { CardGroupIcon(HugeIcons.Bash) },
                ) {
                    Button(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Bash,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(installButtonText)
                    }
                    installProgress?.let { RootfsProgress(it) }
                }
                switchItem(
                    checked = workspace?.shellCompatibilityMode ?: false,
                    onCheckedChange = onShellCompatibilityModeChange,
                    enabled = workspace != null,
                    supportingContent = {
                        Text(stringResource(R.string.workspace_detail_compatibility_mode_desc))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Wrench01) },
                    headlineContent = { Text(stringResource(R.string.workspace_detail_compatibility_mode)) },
                )
            }
        }

        if (toolApprovalExpanded != null) {
            item {
                WorkspaceToolApprovalCard(
                    workspace = workspace,
                    expanded = toolApprovalExpanded,
                    onExpandedChange = onToolApprovalExpandedChange,
                    onToolApprovalChange = onToolApprovalChange,
                )
            }
        }

        item {
            WorkspaceMountDirCard(
                workspace = workspace,
                onAdd = onAddMount,
                onRemove = onRemoveMount,
                onReadOnlyChange = onMountReadOnlyChange,
            )
        }
    }
}

@Composable
private fun ShellStatusBadge(status: String?) {
    val colorScheme = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when (status) {
        WorkspaceShellStatus.READY.name -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        WorkspaceShellStatus.BROKEN.name -> colorScheme.errorContainer to colorScheme.onErrorContainer
        else -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = status?.toShellStatusLabel() ?: "-",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_tool_approval),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_tool_approval_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onExpandedChange(!expanded) }) {
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = stringResource(
                            if (expanded) R.string.code_block_collapse else R.string.code_block_expand
                        ),
                    )
                }
            }

            if (expanded) {
                workspaceToolApprovalItems().forEach { (toolName, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = toolName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = resolveWorkspaceToolApproval(toolName, overrides),
                            onCheckedChange = { onToolApprovalChange(toolName, it) },
                            enabled = workspace != null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceMountDirCard(
    workspace: WorkspaceEntity?,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onReadOnlyChange: (String, Boolean) -> Unit,
) {
    val mounts = workspace?.mountDirList().orEmpty()
    val context = LocalContext.current
    var permissionRefreshKey by remember { mutableIntStateOf(0) }
    val hasAllFilesAccess = remember(context, permissionRefreshKey) {
        PermissionHelper.hasAllFilesAccess(context)
    }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { permissionRefreshKey++ }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_mount_dirs),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_mount_dirs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (mounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.workspace_detail_mount_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                mounts.forEach { mount ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = mount.target,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = mount.sourcePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onRemove(mount.target) }) {
                                Icon(HugeIcons.Delete01, contentDescription = null)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.workspace_detail_mount_read_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = mount.readOnly,
                                onCheckedChange = { onReadOnlyChange(mount.target, it) },
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(
                        if (hasAllFilesAccess) {
                            R.string.workspace_detail_mount_all_files_access_granted
                        } else {
                            R.string.workspace_detail_mount_all_files_access_missing
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasAllFilesAccess) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                TextButton(
                    onClick = {
                        runCatching {
                            allFilesAccessLauncher.launch(PermissionHelper.allFilesAccessIntent(context))
                        }
                    },
                ) {
                    Text(stringResource(R.string.workspace_detail_mount_all_files_access))
                }
            }

            Button(
                onClick = onAdd,
                enabled = workspace != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Folder01, contentDescription = null)
                Text(
                    text = stringResource(R.string.workspace_detail_mount_add),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AddMountDirDialog(
    onDismiss: () -> Unit,
    onConfirm: (WorkspaceMountDir) -> Unit,
) {
    val context = LocalContext.current
    var sourcePath by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var readOnly by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val derived = derivePrimaryStoragePath(uri)
        if (derived == null) {
            error = context.getString(R.string.workspace_detail_mount_uri_unsupported)
        } else {
            sourcePath = derived
            error = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_mount_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sourcePath,
                    onValueChange = {
                        sourcePath = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.workspace_detail_mount_source)) },
                    placeholder = { Text("/storage/emulated/0/Documents") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { treeLauncher.launch(null) }) {
                    Text(stringResource(R.string.workspace_detail_mount_pick_directory))
                }
                OutlinedTextField(
                    value = target,
                    onValueChange = {
                        target = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.workspace_detail_mount_target)) },
                    placeholder = { Text("/data/documents") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_mount_read_only),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        WorkspaceMountDir(
                            sourcePath = sourcePath,
                            target = target,
                            readOnly = readOnly,
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

private fun derivePrimaryStoragePath(uri: android.net.Uri): String? {
    val documentId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }
        .getOrNull() ?: return null
    val segments = documentId.split(':')
    if (segments.size < 2 || segments[0] != "primary") return null
    val relative = segments[1].trim('/')
    return if (relative.isEmpty()) {
        "/storage/emulated/0"
    } else {
        "/storage/emulated/0/$relative"
    }
}

@Composable
private fun workspaceToolApprovalItems() = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
    "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
    "workspace_terminal_start" to stringResource(R.string.workspace_detail_tool_terminal_start),
    "workspace_terminal_send" to stringResource(R.string.workspace_detail_tool_terminal_send),
    "workspace_terminal_read" to stringResource(R.string.workspace_detail_tool_terminal_read),
    "workspace_terminal_kill" to stringResource(R.string.workspace_detail_tool_terminal_kill),
    "workspace_terminal_list" to stringResource(R.string.workspace_detail_tool_terminal_list),
)

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(DEFAULT_ROOTFS_URL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
internal fun WorkspaceFilesPage(
    workspaceId: String,
    state: WorkspaceDetailState,
    isActive: Boolean,
    contentPadding: PaddingValues,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onToggleExpand: (WorkspaceFileEntry) -> Unit,
    onResolvePreview: (WorkspaceFileEntry, (File?) -> Unit) -> Unit,
    highlightPath: String?,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onRename: (WorkspaceFileEntry) -> Unit,
    onReorderFile: (WorkspaceStorageArea, String, String) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onBatchExport: (List<WorkspaceFileEntry>) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
    onRunScript: (WorkspaceFileEntry) -> Unit,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var sortMode by rememberSaveable(workspaceId) {
        mutableStateOf(WorkspaceFileSortMode.MANUAL)
    }
    var descending by rememberSaveable(workspaceId) { mutableStateOf(false) }
    var selecting by remember(state.area, state.path) { mutableStateOf(false) }
    var selectedPaths by remember(state.area, state.path) { mutableStateOf(emptySet<String>()) }
    var draggingPath by remember(state.area, state.path) { mutableStateOf<String?>(null) }
    val showDragHandle = sortMode == WorkspaceFileSortMode.MANUAL && !selecting
    val manualSortEnabled = showDragHandle && !state.loading && state.loadingPaths.isEmpty()
    LaunchedEffect(manualSortEnabled) {
        if (!manualSortEnabled) draggingPath = null
    }
    val lazyListState = rememberLazyListState()
    val rows = remember(
        state.entries,
        state.expandedPaths,
        state.childrenCache,
        state.customFileOrders,
        state.area,
        state.path,
        sortMode,
        descending,
    ) {
        flattenWorkspaceTree(
            entries = state.entries,
            expandedPaths = state.expandedPaths,
            childrenCache = state.childrenCache,
            parentPath = state.path,
            sortEntries = { directoryPath, entries ->
                sortWorkspaceEntries(
                    entries = entries,
                    mode = sortMode,
                    descending = descending,
                    manualOrder = state.customFileOrders[
                        workspaceFileOrderKey(state.area, directoryPath)
                    ].orEmpty(),
                )
            },
        )
    }
    val files = rows.map { it.entry }.filterNot { it.isDirectory }
    val selectedFiles = files.filter { it.path in selectedPaths }
    val allSelected = files.isNotEmpty() && selectedFiles.size == files.size
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (!manualSortEnabled) {
            return@rememberReorderableLazyListState
        }
        val keyPrefix = "${state.area.name}:"
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        if (!fromKey.startsWith(keyPrefix) || !toKey.startsWith(keyPrefix)) {
            return@rememberReorderableLazyListState
        }
        val fromPath = fromKey.removePrefix(keyPrefix)
        val toPath = toKey.removePrefix(keyPrefix)
        onReorderFile(state.area, fromPath, toPath)
    }
    fun toggleSelection(entry: WorkspaceFileEntry) {
        selectedPaths = if (entry.path in selectedPaths) selectedPaths - entry.path else selectedPaths + entry.path
    }
    fun exitSelection() {
        selecting = false
        selectedPaths = emptySet()
    }
    BackHandler(enabled = selecting && isActive) {
        exitSelection()
    }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = if (selecting) 128.dp else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        state = lazyListState,
    ) {
        item {
            WorkspaceAreaSelector(
                selected = state.area,
                onSelected = onSelectArea,
            )
        }

        item {
            WorkspacePathBar(
                path = state.path,
                canGoUp = state.path.isNotBlank(),
                onGoUp = onGoUp,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.workspace_detail_sort),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { sortMenuExpanded = true }) {
                            Icon(HugeIcons.Sorting01, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(
                                stringResource(
                                    when (sortMode) {
                                        WorkspaceFileSortMode.MANUAL -> R.string.workspace_detail_sort_manual
                                        WorkspaceFileSortMode.NAME -> R.string.workspace_detail_sort_name
                                        WorkspaceFileSortMode.MODIFIED_TIME -> R.string.workspace_detail_sort_modified
                                        WorkspaceFileSortMode.SIZE -> R.string.workspace_detail_sort_size
                                        WorkspaceFileSortMode.TYPE -> R.string.workspace_detail_sort_type
                                    }
                                )
                            )
                            Icon(HugeIcons.ArrowDown01, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            WorkspaceFileSortMode.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                when (option) {
                                                    WorkspaceFileSortMode.MANUAL -> R.string.workspace_detail_sort_manual
                                                    WorkspaceFileSortMode.NAME -> R.string.workspace_detail_sort_name
                                                    WorkspaceFileSortMode.MODIFIED_TIME -> R.string.workspace_detail_sort_modified
                                                    WorkspaceFileSortMode.SIZE -> R.string.workspace_detail_sort_size
                                                    WorkspaceFileSortMode.TYPE -> R.string.workspace_detail_sort_type
                                                }
                                            )
                                        )
                                    },
                                    trailingIcon = {
                                        if (option == sortMode) Icon(HugeIcons.Tick01, contentDescription = null)
                                    },
                                    onClick = {
                                        sortMode = option
                                        descending = option == WorkspaceFileSortMode.MODIFIED_TIME ||
                                            option == WorkspaceFileSortMode.SIZE
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    if (sortMode != WorkspaceFileSortMode.MANUAL) {
                        IconButton(
                            onClick = { descending = !descending },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = if (descending) HugeIcons.ArrowDown01 else HugeIcons.ArrowUp01,
                                contentDescription = stringResource(R.string.workspace_detail_sort_direction),
                            )
                        }
                    }
                }
            }
        }

        if (state.exporting) item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = CustomColors.listItemColors.containerColor,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("正在导出 ${state.exportCompleted}/${state.exportTotal}")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        state.error?.let { error ->
            item {
                ErrorCard(error)
            }
        }

        if ((!state.loading || state.hasLoadedDirectory) && state.entries.isEmpty() && state.error == null) {
            item {
                EmptyDirectoryState()
            }
        }

        itemsIndexed(rows, key = { _, row -> "${state.area.name}:${row.entry.path}" }) { index, row ->
            val itemKey = "${state.area.name}:${row.entry.path}"
            val reorderEnabled = manualSortEnabled && isWorkspaceReorderTarget(row.entry.path, draggingPath)
            ReorderableItem(state = reorderableState, key = itemKey, enabled = reorderEnabled) { isDragging ->
                WorkspaceFileCard(
                    entry = row.entry,
                    shape = cardGroupItemShape(index, rows.size),
                    depth = row.depth,
                    expanded = row.entry.path in state.expandedPaths,
                    onToggleExpand = { onToggleExpand(row.entry) },
                    onDelete = { onDelete(row.entry) },
                    onRename = { onRename(row.entry) },
                    onExport = { onExport(row.entry) },
                    onShare = { onShare(row.entry) },
                    onRunScript = { onRunScript(row.entry) },
                    highlighted = row.entry.path == highlightPath,
                    workspaceId = workspaceId,
                    area = state.area,
                    onResolvePreview = onResolvePreview,
                    selecting = selecting,
                    selected = row.entry.path in selectedPaths,
                    showDragHandle = showDragHandle,
                    isDragging = isDragging,
                    dragHandleModifier = if (showDragHandle) Modifier.longPressDraggableHandle(
                        enabled = reorderEnabled,
                        onDragStarted = { draggingPath = row.entry.path },
                        onDragStopped = { draggingPath = null },
                    ) else Modifier,
                    onToggleSelection = { toggleSelection(row.entry) },
                    onLongClick = {
                        selecting = true
                        selectedPaths = selectedPaths + row.entry.path
                    },
                    onOpen = {
                        if (selecting && !row.entry.isDirectory) toggleSelection(row.entry) else onOpen(row.entry)
                    },
                )
            }
        }
    }
        AnimatedVisibility(
            visible = selecting,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = CustomColors.floatingToolbarColors,
                leadingContent = {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(HugeIcons.Cancel01, contentDescription = "取消多选")
                    }
                },
                trailingContent = {
                    IconButton(onClick = {
                        selectedPaths = if (allSelected) emptySet() else files.map { it.path }.toSet()
                    }) {
                        Icon(HugeIcons.CheckList, contentDescription = if (allSelected) "取消全选" else "全选")
                    }
                },
            ) {
                Button(
                    onClick = { onBatchExport(selectedFiles) },
                    enabled = selectedFiles.isNotEmpty() && !state.exporting,
                ) {
                    Icon(HugeIcons.FileExport, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("导出 (${selectedFiles.size})")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    ConnectedChoiceButtons(
        options = WorkspaceStorageArea.entries,
        selected = selected,
        onSelect = onSelected,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) { area, isSelected ->
        ConnectedChoiceLabel(
            text = when (area) {
                WorkspaceStorageArea.FILES -> stringResource(R.string.workspace_detail_area_files)
                WorkspaceStorageArea.LINUX -> stringResource(R.string.workspace_detail_area_rootfs)
            },
            selected = isSelected,
            icon = HugeIcons.Tick01,
        )
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.FilledTonalIconButton(
                enabled = canGoUp,
                onClick = onGoUp,
            ) {
                Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
            }
            Text(
                text = path.ifBlank { "/" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    shape: Shape,
    depth: Int,
    expanded: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onLongClick: () -> Unit,
    onOpen: () -> Unit,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRunScript: () -> Unit,
    showDragHandle: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    highlighted: Boolean,
    workspaceId: String,
    area: WorkspaceStorageArea,
    onResolvePreview: (WorkspaceFileEntry, (File?) -> Unit) -> Unit,
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .graphicsLayer { alpha = if (isDragging) 0.85f else 1f },
        shape = shape,
        color = if (highlighted || selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            CustomColors.listItemColors.containerColor
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Keep selection gestures outside the drag handle's pointer-input ancestry.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = if (entry.isDirectory) null else onLongClick,
                        onLongClickLabel = if (entry.isDirectory) null else "选择文件",
                    )
                    .padding(start = 16.dp + (depth * 20).dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting && !entry.isDirectory) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                }
                if (entry.isDirectory) {
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(28.dp))
                }
                WorkspaceFileLeadingVisual(
                    entry = entry,
                    workspaceId = workspaceId,
                    area = area,
                    onResolvePreview = onResolvePreview,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (entry.isDirectory) entry.path else "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showDragHandle) {
                Box(
                    modifier = Modifier.size(40.dp).then(dragHandleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.DragDropHorizontal,
                        contentDescription = stringResource(R.string.workspace_detail_reorder_file),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!selecting) {
                ItemActionMenu(
                    actions = buildList {
                        add(ItemAction(
                            text = stringResource(R.string.workspace_detail_copy_path),
                            icon = HugeIcons.Copy01,
                            onClick = {
                                context.writeClipboardText(entry.rootfsPath(area))
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    Toast.makeText(context, R.string.workspace_detail_path_copied, Toast.LENGTH_SHORT).show()
                                }
                            },
                        ))
                        if (!entry.isDirectory && area == WorkspaceStorageArea.FILES) {
                            add(ItemAction(
                                text = stringResource(R.string.common_rename),
                                icon = HugeIcons.PencilEdit01,
                                onClick = onRename,
                            ))
                        }
                        add(ItemAction(
                            text = stringResource(R.string.common_export),
                            icon = HugeIcons.FileImport,
                            onClick = onExport,
                        ))
                        if (!entry.isDirectory) {
                            if (area == WorkspaceStorageArea.FILES && isRunnableScript(entry.name)) {
                                add(ItemAction(
                                    text = stringResource(R.string.workspace_script_run),
                                    icon = HugeIcons.Play,
                                    onClick = onRunScript,
                                ))
                            }
                            add(ItemAction(
                                text = stringResource(R.string.common_share),
                                icon = HugeIcons.Share08,
                                onClick = onShare,
                            ))
                        }
                        add(ItemAction(
                            text = stringResource(R.string.common_delete),
                            icon = HugeIcons.Delete01,
                            destructive = true,
                            onClick = onDelete,
                        ))
                    },
                )
            }
        }
    }
}

private fun isRunnableScript(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf(
        "sh", "bash", "zsh", "py", "js", "mjs", "cjs", "kts", "kt", "rb", "pl", "php",
    ) || name.equals("makefile", ignoreCase = true)

private fun WorkspaceFileEntry.rootfsPath(area: WorkspaceStorageArea): String = when (area) {
    WorkspaceStorageArea.FILES -> "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$path"
    WorkspaceStorageArea.LINUX -> "/$path"
}


@Composable
private fun WorkspaceFileLeadingVisual(
    entry: WorkspaceFileEntry,
    workspaceId: String,
    area: WorkspaceStorageArea,
    onResolvePreview: (WorkspaceFileEntry, (File?) -> Unit) -> Unit,
) {
    val isImage = !entry.isDirectory && entry.detectFileType() == WorkspaceFileType.IMAGE
    if (isImage) {
        var previewFile by remember(workspaceId, area, entry.path, entry.updatedAt) { mutableStateOf<File?>(null) }
        var failed by remember(workspaceId, area, entry.path, entry.updatedAt) { mutableStateOf(false) }
        LaunchedEffect(workspaceId, area, entry.path, entry.updatedAt) {
            onResolvePreview(entry) { file ->
                previewFile = file
                failed = file == null
            }
        }
        val file = previewFile
        if (file != null && !failed) {
            val context = LocalContext.current
            val cacheKey = "workspace:$workspaceId:${area.name}:${entry.path}:${entry.updatedAt}"
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .build(),
                contentDescription = entry.name,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
            return
        }
    }

    CardGroupIcon(
        icon = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
        containerColor = if (entry.isDirectory) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    )
}

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_detail_empty_directory),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
    else -> lowercase()
}

private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
