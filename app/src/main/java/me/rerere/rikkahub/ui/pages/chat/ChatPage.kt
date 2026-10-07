package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspacePathReference
import me.rerere.rikkahub.ui.pages.extensions.workspace.parentPath
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.rememberAppLifecycleState
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.contextUsageDisplayCapacity
import me.rerere.rikkahub.utils.contextUsageFraction
import me.rerere.rikkahub.utils.effectiveContextLength
import me.rerere.rikkahub.utils.estimateTokenCount
import me.rerere.rikkahub.utils.estimateWindowTokens
import me.rerere.rikkahub.utils.getConversationChatModel
import me.rerere.rikkahub.utils.autoCompactionThresholdTokens
import me.rerere.rikkahub.utils.formatContextLength
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Clear input focus so popup transitions cannot reopen the keyboard.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    onStartVoiceMode: () -> Unit,
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    assistant: Assistant,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    var showFilesSheet by remember { mutableStateOf(false) }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = setting,
        onAttachmentAdded = { showFilesSheet = false },
    )
    val conversationChatModel = currentChatModel
    val allowAudioVideoAttachments =
        currentChatModel?.findProvider(setting.providers) is ProviderSetting.Google
    val pendingText = inputState.textContent.text.toString()
    val pendingParts = inputState.messageContent
    val editingMessageId = inputState.editingMessage
    val pendingTokens = remember(pendingText, pendingParts, editingMessageId, setting.enableAutoCompaction) {
        if (setting.enableAutoCompaction && editingMessageId == null) {
            val parts = inputState.getContents()
            if (parts.isNotEmpty()) {
                estimateTokenCount(listOf(UIMessage(role = MessageRole.USER, parts = parts)))
            } else {
                0
            }
        } else {
            0
        }
    }
    val contextUsage = remember(
        conversation,
        conversationChatModel,
        setting.enableAutoCompaction,
        setting.autoCompactionTokenLimit,
        pendingTokens,
    ) {
        if (setting.enableAutoCompaction && conversationChatModel != null) {
            ContextUsage(
                usedTokens = conversation.estimateWindowTokens(conversationChatModel) + pendingTokens,
                modelWindowTokens = conversationChatModel.effectiveContextLength(),
                displayCapacityTokens = contextUsageDisplayCapacity(setting.autoCompactionTokenLimit),
            )
        } else {
            null
        }
    }

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    assistant = assistant,
                    chatModel = currentChatModel,
                    conversation = conversation,
                    contextUsage = contextUsage,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                ChatInput(
                    modelOverrideId = conversation.modelOverrideId,
                    modelSelectionLocked = conversation.modelOverrideId != null && conversation.parentConversationId == null,
                    onStartVoiceMode = onStartVoiceMode,
                    voiceState = voiceState,
                    onStopVoiceMode = vm.voiceSession::stop,
                    state = inputState,
                    messageQueue = messageQueue,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onMoveQueuedMessage = vm::moveQueuedMessage,
                    onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                    onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                    onSendQueuedMessageImmediately = vm::sendQueuedMessageImmediately,
                    onResumeMessageQueue = vm::resumeMessageQueue,
                    loading = loadingJob != null,
                    settings = setting.copy(assistantId = assistant.id),
                    assistant = assistant,
                    chatModel = currentChatModel,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    workspace = boundWorkspace,
                    onWorkspaceClick = {
                        boundWorkspace?.let {
                            navController.navigate(
                                Screen.WorkspaceDetail(
                                    id = it.id,
                                    openFiles = true,
                                )
                            )
                        }
                    },
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    onUpdateSearchMode = { mode ->
                        vm.updateSearch(
                            enableWebSearch = mode == SearchMode.LOCAL,
                            builtInSearch = mode == SearchMode.BUILT_IN,
                        )
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                delay(100.milliseconds)
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        if (conversation.parentConversationId != null) {
                            vm.setConversationChatModel(it)
                        } else {
                            vm.setChatModel(assistant = assistant, model = it)
                        }
                    },
                    onUpdateAssistant = vm::updateAssistant,
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason, editedPrompt ->
                    vm.handleToolApproval(toolCallId, approved, reason, editedPrompt)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
                onWorkspacePathClick = { reference: WorkspacePathReference ->
                    val workspace = boundWorkspace
                    if (workspace == null) {
                        toaster.show("当前助手未绑定工作区", type = ToastType.Error)
                    } else {
                        navController.navigate(
                            Screen.WorkspaceDetail(
                                id = workspace.id,
                                openFiles = true,
                                initialPath = reference.parentPath(),
                                highlightPath = reference.path,
                            )
                        )
                    }
                },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                chatModel = currentChatModel,
                vm = vm,
                attachmentPickerActions = attachmentPickerActions,
                onStartVoiceMode = onStartVoiceMode,
                onDismiss = { showFilesSheet = false },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    chatModel: Model?,
    vm: ChatVM,
    attachmentPickerActions: ChatAttachmentPickerActions,
    onStartVoiceMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showCompressDialog = false
        onDismiss()
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            chatModel = chatModel,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = vm::updateAssistant,
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = attachmentPickerActions.onTakePicture,
            onPickImage = attachmentPickerActions.onPickImage,
            onPickVideo = attachmentPickerActions.onPickVideo,
            onPickAudio = attachmentPickerActions.onPickAudio,
            onPickFile = attachmentPickerActions.onPickFile,
            onSketch = attachmentPickerActions.onSketch,
            onStartVoiceMode = if (
                setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onStartVoiceMode()
                }
            } else null,
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    assistant: Assistant,
    chatModel: Model?,
    conversation: Conversation,
    contextUsage: ContextUsage?,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val provider = chatModel?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chatModel != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${chatModel.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            contextUsage?.let { usage ->
                key(conversation.id) {
                    ContextUsageRingButton(
                        usedTokens = usage.usedTokens,
                        modelWindowTokens = usage.modelWindowTokens,
                        displayCapacityTokens = usage.displayCapacityTokens,
                        thresholdPercent = settings.autoCompactionThresholdPercent,
                        tokenLimit = settings.autoCompactionTokenLimit,
                        enableAnimation = settings.enableContextUsageRingAnimation,
                    )
                }
            }

            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

private data class ContextUsage(
    val usedTokens: Int,
    val modelWindowTokens: Int,
    val displayCapacityTokens: Int,
)

@Composable
internal fun ContextUsageRingButton(
    usedTokens: Int,
    modelWindowTokens: Int,
    displayCapacityTokens: Int,
    thresholdPercent: Int,
    tokenLimit: Int?,
    enableAnimation: Boolean = true,
) {
    var showPopup by remember { mutableStateOf(false) }
    val fraction = contextUsageFraction(usedTokens, displayCapacityTokens)
    val lifecycleState by rememberAppLifecycleState()
    val animateRing = enableAnimation && lifecycleState == Lifecycle.State.RESUMED
    val displayedFraction = if (animateRing) {
        animateFloatAsState(
            targetValue = fraction,
            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
            label = "Context usage",
        )
    } else {
        rememberUpdatedState(fraction)
    }
    // Removing the transition stops frame requests on navigation and in the background.
    // Compose suspends infinite animations when the system duration scale is zero.
    val phase = if (animateRing && fraction > 0f) {
        rememberInfiniteTransition(label = "Context ring flow").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(durationMillis = 9_000, easing = LinearEasing)),
            label = "Gradient direction",
        )
    } else {
        null
    }
    val warningThresholdTokens = autoCompactionThresholdTokens(
        windowTokens = modelWindowTokens,
        thresholdPercent = thresholdPercent,
        tokenLimit = tokenLimit,
    )
    val isWarning = warningThresholdTokens != null && usedTokens >= warningThresholdTokens
    val colorScheme = MaterialTheme.colorScheme
    val errorColor = MaterialTheme.colorScheme.error
    val ringColors = remember(
        isWarning,
        errorColor,
        colorScheme.primary,
        colorScheme.secondary,
        colorScheme.tertiary,
    ) {
        when {
            isWarning -> listOf(lerp(errorColor, Color.White, 0.12f), errorColor, lerp(errorColor, Color.Black, 0.12f))
            else -> listOf(colorScheme.primary, colorScheme.secondary, colorScheme.tertiary)
        }
    }
    val trackColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable { showPopup = true },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(25.dp)) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val visibleFraction = displayedFraction.value
            if (visibleFraction > 0f) {
                // Read the phase only while drawing: flowing colors never recompose the top bar.
                val angle = phase?.value ?: 0f
                val radius = size.minDimension / 2
                val direction = Offset(cos(angle) * radius, sin(angle) * radius)
                drawArc(
                    brush = Brush.linearGradient(ringColors, start = center - direction, end = center + direction),
                    startAngle = -90f,
                    sweepAngle = 360f * visibleFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        ContextUsagePercent(displayedFraction, isWarning)
    }

    DropdownMenu(
        expanded = showPopup,
        onDismissRequest = { showPopup = false },
    ) {
        Text(
            text = stringResource(
                R.string.chat_context_usage_tooltip,
                formatContextLength(usedTokens),
                formatContextLength(displayCapacityTokens),
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ContextUsagePercent(fraction: State<Float>, isWarning: Boolean) {
    val percent by remember(fraction) { derivedStateOf { (fraction.value * 100f).roundToInt() } }
    Text(
        text = percent.toString(),
        fontSize = when {
            percent >= 100 -> 7.sp
            percent >= 10 -> 8.sp
            else -> 9.sp
        },
        lineHeight = 9.sp,
        color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
