package dev.cpa.android.feature.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.core.storage.ConnectionIcon
import dev.cpa.android.data.repository.*
import kotlin.math.roundToInt

private enum class PanelTab { Changes, Files }

/** Conversation screen: folded turns, expanded composer, model/reasoning controls and session panels. */
@Composable
fun RemoteThreadRoute(vm: RemoteThreadViewModel, onForked: (String) -> Unit = {}, onNewThread: (String?) -> Unit = {},
    onSubAgent: (RemoteSubAgent) -> Unit = {}, icons: Map<String, String> = emptyMap(), onBack: () -> Unit) {
    val c = Cpa.colors
    val thread by vm.thread.collectAsStateWithLifecycle()
    val rawComposer by vm.composer.collectAsStateWithLifecycle()
    val skillsState by vm.skills.state.collectAsStateWithLifecycle()
    val editing by vm.editor.state.collectAsStateWithLifecycle()
    val completedEdits by vm.editor.completedEdits.collectAsStateWithLifecycle()
    val composer = rawComposer.copy(pending = rawComposer.pending || editing.pending, unknown = rawComposer.unknown || editing.unknown)
    val attachments by vm.attachments.state.collectAsStateWithLifecycle()
    val wrapCode by vm.wrapCode.collectAsStateWithLifecycle()
    val collapseKeyboard by vm.collapseKeyboardAfterSend.collectAsStateWithLifecycle()
    val showContext by vm.showContextUsage.collectAsStateWithLifecycle()
    val connections by vm.connections.collectAsStateWithLifecycle()
    val capsByConnection by vm.capabilities.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val ask by vm.ask.state.collectAsStateWithLifecycle()
    val forked by vm.forkOpened.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val notice = rememberNotice()
    val connection = connections.find { it.id == vm.connectionId }
    val connected = connection?.phase == ConnectionPhase.Connected
    val caps = capsByConnection[vm.connectionId] ?: RemoteCapabilities()
    val session = sessions[vm.connectionId].orEmpty().find { it.id == vm.sessionId }
    val project = projects[vm.connectionId].orEmpty().find { it.id == session?.projectId }
    val currentModelId = session?.modelId?.takeIf(String::isNotEmpty) ?: caps.availableModels.find { it.id == caps.defaults.modelId }?.id
        ?: caps.availableModels.firstOrNull()?.id
    val currentModel = caps.models.find { it.id == currentModelId }
    val choices = caps.reasoningChoices(currentModelId)
    val currentReasoning = session?.reasoningEffort?.takeIf(String::isNotEmpty) ?: caps.defaults.reasoningLevel
    val currentSpeed = if ((session?.speed ?: caps.defaults.speed) == "fast") "fast" else "standard"
    var pickedReasoning by rememberSaveable { mutableStateOf<String?>(null) }
    val shownReasoning = pickedReasoning ?: currentReasoning
    val commandEnabled = connected && !thread.editAwaitingStart && !thread.running && !thread.loading && !thread.outcomeUnknown && !composer.pending && !composer.unknown
    val modelTitle = currentModel?.label ?: currentModelId ?: stringResource(R.string.ui_common_default_model)
    var reasoningOpen by rememberSaveable { mutableStateOf(false) }
    var modelSheet by rememberSaveable { mutableStateOf(false) }
    var sheetModel by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetReasoning by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetSpeed by rememberSaveable { mutableStateOf("standard") }
    var sheetLocked by rememberSaveable { mutableStateOf(false) }
    var askOpen by rememberSaveable { mutableStateOf(false) }
    var presentedAsk by rememberSaveable { mutableStateOf<String?>(null) }
    var panel by rememberSaveable { mutableStateOf<PanelTab?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var archiving by rememberSaveable { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val pickers = rememberAttachmentPickers(vm.attachments, !composer.pending && !composer.unknown && !thread.outcomeUnknown)
    val copied = stringResource(R.string.ui_common_copied_to_clipboard)
    val compactNotice = stringResource(R.string.ui_thread_compact_requested)
    val fastOnNotice = stringResource(R.string.ui_composer_fast_mode_enabled_notice)
    val fastOffNotice = stringResource(R.string.ui_composer_fast_mode_disabled_notice)
    val modelUpdated = stringResource(R.string.ui_thread_model_updated)

    LaunchedEffect(forked) { forked?.let { vm.consumedForkNavigation(); onForked(it) } }
    LaunchedEffect(session?.archivedAt, archiving) { if (archiving && (session?.archivedAt ?: 0) > 0) { archiving = false; onBack() } }
    LaunchedEffect(thread.running) { if (thread.running && !sheetLocked) modelSheet = false; if (!thread.running) sheetLocked = false }
    val askIdentity = ask.request?.let { homeKey("ask", it.id, it.toolCallId) }
    LaunchedEffect(askIdentity, ask.snapshotFresh) {
        if (ask.snapshotFresh && askIdentity != presentedAsk) { presentedAsk = askIdentity; askOpen = askIdentity != null }
    }
    LifecycleStartEffect(vm) { vm.beginViewing(); onStopOrDispose { vm.endViewing() } }
    LifecycleStartEffect(vm.ask, connected) {
        if (connected) vm.ask.start()
        onStopOrDispose { vm.ask.close() }
    }
    fun applyPicked() {
        pickedReasoning?.takeIf { it != currentReasoning }?.let { vm.runtime(null, it, null) }
        pickedReasoning = null // Outside the picker, only display the desktop’s confirmed value.
    }
    fun openModelSheet() {
        sheetLocked = thread.running
        sheetModel = currentModelId
        sheetReasoning = shownReasoning?.takeIf { level -> caps.reasoningChoices(currentModelId).any { it.id == level } } ?: caps.preferredReasoning(currentModelId)
        sheetSpeed = currentSpeed
        modelSheet = true
    }
    fun closeModelSheet() {
        modelSheet = false
        val reasoning = sheetReasoning ?: "off"
        if (sheetLocked) { if (reasoning != currentReasoning) vm.runtime(null, reasoning, null); return }
        if (thread.running || sheetModel == null) return
        val changed = sheetModel != currentModelId || reasoning != shownReasoning || sheetSpeed != currentSpeed
        if (changed) { vm.runtime(sheetModel, reasoning, sheetSpeed) { notice.show(modelUpdated) } }
    }
    val title = session?.title?.ifBlank { null } ?: stringResource(if (thread.loading && session == null) R.string.ui_thread_chat_title else R.string.ui_home_new_chat)
    val subtitle = listOfNotNull(connection?.displayName(), project?.name).joinToString(" • ")
    val placeholder = when {
        connected -> stringResource(R.string.ui_thread_work_on_desktop, connection?.displayName().orEmpty())
        connection?.phase == ConnectionPhase.Connecting -> stringResource(R.string.ui_home_client_connecting, connection.displayName())
        else -> stringResource(R.string.ui_error_desktop_not_connected)
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.background, .6f to c.background, 1f to c.gray6))) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // Toolbar: back, title/subtitle, then new chat + more in one glass capsule.
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(CpaIcons.ChevronLeft, stringResource(R.string.ui_x_back), onBack, modifier = Modifier.testTag("thread-back"))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, Modifier.semantics { heading() }.testTag("thread-title"), style = cpaText(17, FontWeight.SemiBold), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotEmpty()) Text(subtitle, style = cpaText(13), color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    GlassButtonPair({ BarIcon(CpaIcons.Compose, stringResource(R.string.ui_x_new_chat), { onNewThread(session?.projectId) }, modifier = Modifier.testTag("thread-new")) },
                        { BarIcon(CpaIcons.Ellipsis, stringResource(R.string.ui_x_more), { menu = true }, modifier = Modifier.testTag("thread-more")) })
                    CpaMenu(menu, { menu = false }, offset = DpOffset(0.dp, 6.dp)) {
                        val pinned = session?.pinned == true
                        CpaMenuItem(stringResource(if (pinned) R.string.ui_home_unpin else R.string.ui_thread_pin), if (pinned) CpaIcons.PinOff else CpaIcons.Pin, enabled = connected && session != null) {
                            menu = false; vm.pin(!pinned)
                        }
                        CpaMenuItem(stringResource(R.string.ui_thread_rename), CpaIcons.Pencil, enabled = connected) { menu = false; renaming = session?.title.orEmpty() }
                        CpaMenuItem(stringResource(R.string.ui_thread_copy_thread_id), CpaIcons.Copy) { menu = false; clipboard.setText(AnnotatedString(vm.sessionId)); notice.show(copied) }
                        CpaMenuItem(stringResource(R.string.ui_thread_archive), CpaIcons.Archive, destructive = true, enabled = connected && session != null) {
                            menu = false; archiving = true; vm.archive()
                        }
                        CpaMenuDivider()
                        CpaMenuItem(stringResource(R.string.ui_panels_changes), CpaIcons.Branch) { menu = false; panel = PanelTab.Changes }
                        CpaMenuItem(stringResource(R.string.ui_panels_files), CpaIcons.Folder) { menu = false; panel = PanelTab.Files }
                        if (thread.subAgents.isNotEmpty()) {
                            CpaMenuDivider()
                            thread.subAgents.forEach { agent -> CpaMenuItem(agent.name, CpaIcons.Sparkle) { menu = false; onSubAgent(agent) } }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
                key(thread.connectionId, thread.sessionId, thread.entries.isEmpty()) {
                    RemoteTimeline(thread.entries, thread.loading, Modifier.fillMaxSize(),
                        onEditMessage = if (commandEnabled && !thread.editAwaitingStart) vm.editor::open else null, wrapCode = wrapCode,
                        running = thread.running, isBuildingSystemPrompt = thread.isBuildingSystemPrompt,
                        runningTools = thread.runningTools, subAgents = thread.subAgents, onSubAgent = onSubAgent,
                        onForkMessage = if (connected && !thread.loading && !thread.outcomeUnknown && !thread.editAwaitingStart && !composer.pending && !composer.unknown) ({ vm.fork(it.id) }) else null,
                        skillTrigger = caps.defaults.skillTrigger, knownSkills = skillsState.catalog.enabled(caps.defaults),
                        startsFollowing = thread.running, isPaused = editing.open, followRequests = completedEdits,
                        editingEntryId = editing.entryId.takeIf { editing.open }, inlineEditor = { UserMessageEditor(vm.editor, thread, caps.defaults, connected) },
                        footer = { thread.error?.takeIf { thread.entries.isNotEmpty() && !thread.running }?.let { problem ->
                            Row(verticalAlignment = Alignment.Top) {
                                CpaIcon(CpaIcons.Warning, null, Modifier.padding(top = 2.dp), size = 16.dp, tint = c.red); Spacer(Modifier.width(6.dp))
                                Text(repositoryProblem(problem), style = cpaText(15), color = c.red)
                            }
                        } }, emptyText = false)
                }
                ThreadPlaceholder(thread, connection, icons, onRetry = { if (connection?.enabled == true && !connected) vm.refreshCapabilities(); vm.reload() })
            }
            // Bottom accessory stack: Ask banner, context usage, unconfirmed-send recovery, then the composer.
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ask.request != null && !askOpen) Row(Modifier.fillMaxWidth().height(44.dp).glass(CircleShape, 6.dp).pressable { askOpen = true }
                    .padding(horizontal = 16.dp).testTag("ask-banner"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CpaIcon(CpaIcons.QuestionBubble, null, size = 18.dp, tint = c.accent)
                    Text(stringResource(R.string.ui_ask_banner), Modifier.weight(1f), style = cpaText(15, FontWeight.Medium), color = c.accent)
                    CpaIcon(CpaIcons.ChevronUp, null, size = 13.dp, tint = c.accent)
                }
                if (showContext) remoteContextUsage(thread.entries, session?.modelId, caps)?.takeIf { it.isFinite() }?.let {
                    Text(stringResource(R.string.ui_thread_context_usage, (it * 100).roundToInt()), Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        style = cpaText(12), color = c.secondaryLabel, textAlign = TextAlign.End)
                }
                if (thread.outcomeUnknown || rawComposer.unknown) Row(Modifier.fillMaxWidth().background(c.secondaryBackground, RoundedCornerShape(18.dp)).padding(12.dp)
                    .testTag("send-unknown"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CpaIcon(CpaIcons.Warning, null, size = 18.dp, tint = c.orange)
                    Text(stringResource(R.string.ui_x_send_unknown), Modifier.weight(1f), style = cpaText(13), color = c.label)
                    PillButton(stringResource(R.string.ui_x_reload), !thread.loading, vm::reload, modifier = Modifier.testTag("send-reload"))
                    PillButton(stringResource(R.string.ui_x_verify), connected && !composer.pending && !thread.loading, vm::acknowledgeUnknown,
                        modifier = Modifier.testTag("send-acknowledge"))
                }
                if (!editing.open) {
                    if (editing.entryId != null) PillButton(stringResource(R.string.ui_x_restore_edit), true, vm.editor::resume, modifier = Modifier.testTag("restore-edit"))
                    val hasContent = composer.draft.isNotBlank() || attachments.items.isNotEmpty()
                    ExpandedComposer(field = { menuState ->
                        RemoteSkillComposer(vm.skills, composer.draft, vm::edit, caps.defaults, !composer.pending, placeholder,
                            if (!commandEnabled) emptyList() else composerCommands(
                                fast = if (currentModel?.supportsFast == true) ({
                                    val next = if (currentSpeed == "fast") "standard" else "fast"
                                    vm.runtime(null, null, next); notice.show(if (next == "fast") fastOnNotice else fastOffNotice)
                                }) else null,
                                compact = if (thread.entries.isNotEmpty()) ({ vm.runAction(RemoteRunAction.Compact); notice.show(compactNotice) }) else null,
                                model = ::openModelSheet, fastOn = currentSpeed == "fast", currentModel = currentModel?.label),
                            maxLines = 6, menuState = menuState, focusRequester = focus, modifier = Modifier.testTag("thread-composer"))
                    }, attachments = if (attachments.items.isNotEmpty() || attachments.processing > 0) ({ ComposerAttachmentStrip(vm.attachments, !composer.pending && !composer.unknown) }) else null,
                        hasContent = hasContent,
                        canSend = connected && attachments.processing == 0 && !composer.pending && !composer.unknown && !thread.outcomeUnknown && !thread.loading && !thread.editAwaitingStart,
                        running = thread.running, canResume = connected && thread.canResume() && !composer.pending, sending = rawComposer.pending,
                        modelName = modelTitle, reasoningProgress = reasoningProgress(choices, shownReasoning),
                        onReasoning = { if (choices.isEmpty()) { if (!thread.running) { focusManager.clearFocus(); openModelSheet() } } else { focusManager.clearFocus(); reasoningOpen = true } },
                        onAttach = { attachMenu = true }, attachEnabled = pickers.enabled,
                        onSubmit = { vm.sendWithAcceptance { if (collapseKeyboard) { focusManager.clearFocus(); keyboard?.hide() } } },
                        onStop = vm::stop, onResume = { vm.runAction(RemoteRunAction.Resume) }, modifier = Modifier.testTag("thread-composer-card"))
                }
            }
        }
        AttachmentMenuOverlay(attachMenu, pickers) { attachMenu = false }
        if (reasoningOpen) ReasoningOverlay(modelTitle, choices.find { it.id == shownReasoning }?.let { reasoningLabel(it.id, it.label) }.orEmpty(), choices, shownReasoning,
            onSelect = { pickedReasoning = it }, canChangeModel = !thread.running,
            onOpenSettings = { reasoningOpen = false; openModelSheet(); pickedReasoning = null }, onDismiss = { reasoningOpen = false; applyPicked() })
        TransientNotice(notice, Modifier.align(Alignment.TopCenter).statusBarsPadding())
    }
    if (modelSheet) AdvancedSettingsSheet(caps.availableModels, sheetModel, { id ->
        sheetModel = id
        val levels = caps.reasoningChoices(id)
        if (levels.none { it.id == sheetReasoning }) sheetReasoning = caps.preferredReasoning(id)
        if (sheetSpeed == "fast" && caps.models.none { it.id == id && it.supportsFast }) sheetSpeed = "standard"
    }, caps.reasoningChoices(sheetModel), sheetReasoning, { sheetReasoning = it }, supportsFast = caps.models.any { it.id == sheetModel && it.supportsFast },
        speed = sheetSpeed, onSpeed = { sheetSpeed = it }, runActive = sheetLocked, onDismiss = ::closeModelSheet)
    if (askOpen) AskSheet(vm.ask, thread.outcomeUnknown) { askOpen = false }
    panel?.let { tab -> SessionPanelSheet(vm, connected, tab == PanelTab.Files) { panel = null } }
    renaming?.let { initial ->
        var text by remember { mutableStateOf(initial) }
        val requester = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
        CpaAlert(stringResource(R.string.ui_thread_rename_title), null, listOf(
            DialogAction(stringResource(R.string.ui_common_cancel), cancel = true) { renaming = null },
            DialogAction(stringResource(R.string.ui_common_save), enabled = text.isNotBlank()) { renaming = null; if (text.trim() != initial) vm.rename(text.trim()) }),
            onDismiss = { renaming = null }) { AlertTextField(text, { text = it }, stringResource(R.string.ui_common_title), focusRequester = requester) }
    }
    rawComposer.error?.let { error ->
        CpaAlert(stringResource(R.string.ui_common_action_failed), stringResource(error), listOf(DialogAction(stringResource(R.string.ui_common_ok), cancel = true) { vm.clearError() }),
            onDismiss = vm::clearError)
    }
}

/** Placeholder shown over an empty conversation: loading emblem, connection failure or load failure. */
@Composable
private fun ThreadPlaceholder(thread: RemoteThread, connection: ConnectionSummary?, icons: Map<String, String>, onRetry: () -> Unit) {
    val visible = thread.entries.any { it.kind == "user" || it.kind == "assistant" || it.kind == "compaction" }
    if (visible) return
    val name = connection?.displayName().orEmpty()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            thread.error == RemoteProblem.SessionDeleted -> EmptyState(CpaIcons.Trash, stringResource(R.string.ui_thread_deleted_title), stringResource(R.string.ui_thread_deleted_message))
            connection == null -> EmptyState(CpaIcons.BoltOutline, stringResource(R.string.ui_thread_connection_unavailable_title), stringResource(R.string.ui_thread_connection_unavailable_message))
            connection.phase == ConnectionPhase.Failed || connection.phase == ConnectionPhase.AuthenticationRequired || (!connection.enabled) ->
                EmptyState(CpaIcons.BoltOutline, stringResource(R.string.ui_thread_cannot_connect_title),
                    connection.error?.let { stringResource(R.string.ui_common_error_with_detail, name, repositoryProblem(it)) } ?: stringResource(R.string.ui_thread_cannot_connect_detail, name),
                    actionTitle = if (connection.enabled) stringResource(R.string.ui_common_retry) else null, onAction = onRetry)
            thread.error != null && !thread.loading -> EmptyState(CpaIcons.Warning, stringResource(R.string.ui_thread_load_failed_title), repositoryProblem(thread.error),
                actionTitle = stringResource(R.string.ui_common_retry), onAction = onRetry)
            thread.loading || connection.phase != ConnectionPhase.Connected -> ConversationLoading(ConnectionIcon.of(icons[connection.id]), name, connection.phase == ConnectionPhase.Connected)
            else -> Unit
        }
    }
}

/** Centered loading indicator: coral ring around the desktop glyph and a shimmering status line. */
@Composable
internal fun ConversationLoading(icon: ConnectionIcon, desktopName: String, connected: Boolean) {
    val c = Cpa.colors
    val transition = rememberInfiniteTransition(label = "loading")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1150, easing = LinearEasing)), label = "ring")
    Column(Modifier.padding(horizontal = 32.dp).testTag("conversation-loading"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(c.userBubble)
                val stroke = 2.dp.toPx()
                drawCircle(c.accent.copy(alpha = .18f), radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                drawArc(c.accent, angle - 90f, 100f, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            CpaIcon(icon.vector, null, size = 24.dp, tint = c.accent)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ShimmerText(if (connected) stringResource(R.string.ui_thread_loading_messages_title) else stringResource(R.string.ui_thread_loading_connecting_title, desktopName),
                cpaText(16, FontWeight.Medium), true)
            Text(stringResource(if (connected) R.string.ui_thread_loading_messages_detail else R.string.ui_thread_loading_waiting_detail), style = cpaText(13),
                color = c.secondaryLabel, textAlign = TextAlign.Center)
        }
    }
}
