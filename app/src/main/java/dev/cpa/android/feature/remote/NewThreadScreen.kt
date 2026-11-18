package dev.cpa.android.feature.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.core.storage.ConnectionIcon
import dev.cpa.android.data.repository.*

/** New chat screen: context selectors above an expanded composer; the chat is created by the first send. */
@Composable
internal fun NewThreadRoute(controller: RemoteNewThreadController, icons: Map<String, String>, onBack: () -> Unit, onOpened: (String, String) -> Unit,
    onOpenExisting: (String, String) -> Unit = onOpened) {
    val c = Cpa.colors
    val state by controller.state.collectAsStateWithLifecycle()
    val attachments by controller.attachments.state.collectAsStateWithLifecycle()
    val connections by controller.connections.collectAsStateWithLifecycle()
    val projects by controller.projects.collectAsStateWithLifecycle()
    val capabilities by controller.capabilities.collectAsStateWithLifecycle()
    val skills by controller.skills.state.collectAsStateWithLifecycle()
    val caps = capabilities[state.connectionId] ?: RemoteCapabilities()
    val connection = connections.find { it.id == state.connectionId }
    val connected = connection?.phase == ConnectionPhase.Connected
    val editable = !state.pending && state.target == null
    val model = caps.availableModels.find { it.id == state.modelId }
    val choices = caps.reasoningChoices(state.modelId)
    val notice = rememberNotice()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var advanced by rememberSaveable { mutableStateOf(false) }
    var reasoning by rememberSaveable { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var abandoning by remember { mutableStateOf(false) }
    val pickers = rememberAttachmentPickers(controller.attachments, !state.pending && !state.unknown && state.target == null)
    LaunchedEffect(state.modelSettingsRequested) {
        if (state.modelSettingsRequested) { kotlinx.coroutines.delay(400); advanced = true; controller.consumedModelSettings() }
    }
    LaunchedEffect(state.openedSession) {
        state.openedSession?.let { session -> state.connectionId?.let { id -> controller.consumedNavigation(); onOpened(id, session) } }
    }
    LifecycleStartEffect(controller) {
        controller.start()
        onStopOrDispose { controller.stopBranches() }
    }
    AutoFocus(focus, enabled = !state.modelSettingsRequested && !advanced)
    val back = { if (!state.pending) { controller.close(); onBack() } }
    androidx.activity.compose.BackHandler(onBack = back)
    val fastOn = stringResource(R.string.ui_composer_fast_mode_enabled_notice)
    val fastOff = stringResource(R.string.ui_composer_fast_mode_disabled_notice)
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                GlassIconButton(CpaIcons.ChevronLeft, stringResource(R.string.ui_x_back), back, enabled = !state.pending, modifier = Modifier.testTag("new-thread-back"))
            }
            Column(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .verticalScroll(rememberScrollState(), reverseScrolling = true), verticalArrangement = Arrangement.Bottom) {
                Spacer(Modifier.weight(1f))
                if (state.target != null && (state.unknown || state.created)) RecoveryCard(controller, state, onOpen = { id, target -> controller.close(); onOpenExisting(id, target) }) { abandoning = true }
                if (connection == null) EmptyState(CpaIcons.Display, stringResource(R.string.ui_new_thread_no_desktop_title), stringResource(R.string.ui_new_thread_no_desktop_message),
                    Modifier.padding(bottom = 80.dp))
                else Column(Modifier.padding(horizontal = 26.dp).padding(bottom = 8.dp)) {
                    ContextSelector(ConnectionIcon.of(icons[connection.id]).vector, connection.displayName(), connections.filter { it.enabled }.map { MenuOption(it.id, it.displayName()) },
                        connection.id, editable, "new-thread-device") { controller.connection(it) }
                    val projectList = projects[connection.id].orEmpty()
                    val noProject = stringResource(R.string.ui_new_thread_no_project)
                    ContextSelector(CpaIcons.Folder, projectList.find { it.id == state.projectId }?.name ?: noProject,
                        projectList.map { MenuOption<String?>(it.id, it.name) } + MenuOption<String?>(null, noProject), state.projectId, editable, "new-thread-project") { controller.project(it) }
                    ContextSelector(CpaIcons.Laptop, stringResource(if (state.workLocation == "worktree") R.string.ui_model_work_location_worktree else R.string.ui_model_work_location_local),
                        listOf(MenuOption("local", stringResource(R.string.ui_model_work_location_local)), MenuOption("worktree", stringResource(R.string.ui_model_work_location_worktree))),
                        state.workLocation, editable, "new-thread-location") { controller.location(it) }
                    if (state.branches.isNotEmpty()) ContextSelector(CpaIcons.Branch, state.branch.orEmpty(), state.branches.map { MenuOption(it, it) }, state.branch, editable,
                        "new-thread-branch") { controller.branch(it) }
                    else if (state.loadingBranches) Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(35.dp)) { Spinner(size = 16.dp) }
                        Text(stringResource(R.string.reading_branches), style = cpaText(17), color = c.secondaryLabel)
                    }
                    if (!connected) Text(stringResource(if (connection.phase == ConnectionPhase.Connecting) R.string.ui_new_thread_connecting_desktop else R.string.ui_error_desktop_not_connected),
                        Modifier.height(32.dp).wrapContentHeight(), style = cpaText(14), color = c.orange)
                }
            }
            val hasContent = state.draft.isNotBlank() || attachments.items.isNotEmpty()
            val modelValid = caps.models.isEmpty() || caps.availableModels.any { it.id == state.modelId }
            ExpandedComposer(field = { menu ->
                RemoteSkillComposer(controller.skills, state.draft, controller::draft, caps.defaults, !state.pending, stringResource(R.string.ui_composer_ask_placeholder),
                    if (!editable || !connected) emptyList() else composerCommands(
                        fast = if (model?.supportsFast == true) ({
                            val next = if (state.speed == "fast") "standard" else "fast"
                            controller.speed(next); notice.show(if (next == "fast") fastOn else fastOff)
                        }) else null,
                        compact = null, model = { reasoning = false; advanced = true }, fastOn = state.speed == "fast", currentModel = model?.label),
                    maxLines = 6, menuState = menu, focusRequester = focus, modifier = Modifier.testTag("new-thread-composer"))
            }, attachments = if (attachments.items.isNotEmpty() || attachments.processing > 0) ({ ComposerAttachmentStrip(controller.attachments, !state.pending) }) else null,
                hasContent = hasContent, canSend = connected && attachments.processing == 0 && !state.loadingBranches && !state.unknown && modelValid && skills.draft.text == state.draft,
                running = false, canResume = false, sending = state.pending,
                modelName = model?.label ?: stringResource(R.string.ui_common_default_model), reasoningProgress = reasoningProgress(choices, state.reasoning),
                onReasoning = { if (choices.isEmpty()) advanced = true else { focusManager.clearFocus(); reasoning = true } },
                onAttach = { attachMenu = true }, attachEnabled = pickers.enabled, onSubmit = controller::submit,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).testTag("new-thread-composer-card"))
        }
        AttachmentMenuOverlay(attachMenu, pickers) { attachMenu = false }
        if (reasoning) ReasoningOverlay(model?.label ?: stringResource(R.string.ui_common_default_model),
            choices.find { it.id == state.reasoning }?.let { reasoningLabel(it.id, it.label) }.orEmpty(), choices, state.reasoning, controller::reasoning,
            onOpenSettings = { reasoning = false; focusManager.clearFocus(); advanced = true }, onDismiss = { reasoning = false })
        TransientNotice(notice, Modifier.align(Alignment.TopCenter).statusBarsPadding())
    }
    if (advanced) AdvancedSettingsSheet(caps.availableModels, state.modelId, controller::model, choices, state.reasoning, controller::reasoning,
        supportsFast = model?.supportsFast == true, speed = state.speed, onSpeed = controller::speed, enabled = editable, onDismiss = { advanced = false })
    state.error?.takeIf { !state.unknown }?.let { error ->
        CpaAlert(stringResource(R.string.ui_new_thread_create_failed_title), stringResource(error),
            listOf(DialogAction(stringResource(R.string.ui_common_ok), cancel = true) { controller.clearError() }), onDismiss = controller::clearError)
    }
    if (abandoning) CpaAlert(stringResource(R.string.ui_x_discard_attempt), stringResource(R.string.abandon_target_warning), listOf(
        DialogAction(stringResource(R.string.ui_common_cancel), cancel = true) { abandoning = false },
        DialogAction(stringResource(R.string.ui_x_discard_attempt), destructive = true) { abandoning = false; controller.abandonAttempt() }), onDismiss = { abandoning = false })
}

/** Menu-backed selector for the new chat context (device, project, mode, branch). */
@Composable
private fun <T> ContextSelector(icon: ImageVector, title: String, options: List<MenuOption<T>>, selected: T?, enabled: Boolean, tag: String, onSelect: (T) -> Unit) {
    val c = Cpa.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.height(48.dp).pressable(enabled, pressedAlpha = .5f) { open = true }.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) { CpaIcon(icon, null, size = 21.dp, tint = c.secondaryLabel) }
            Spacer(Modifier.width(13.dp))
            Text(title, Modifier.widthIn(max = 260.dp), style = cpaText(17), color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            CpaIcon(CpaIcons.ChevronUpDown, null, size = 14.dp, tint = c.secondaryLabel)
        }
        CpaMenu(open, { open = false }, offset = DpOffset(0.dp, (-4).dp)) {
            options.forEach { option -> CpaMenuItem(option.title, checked = option.value == selected) { open = false; onSelect(option.value) } }
        }
    }
}

/** Android recovery for a create/send whose result was not confirmed: inspect, acknowledge, open or discard. */
@Composable
private fun RecoveryCard(controller: RemoteNewThreadController, state: NewThreadState, onOpen: (String, String) -> Unit, onAbandon: () -> Unit) {
    val c = Cpa.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(c.secondaryBackground, RoundedCornerShape(22.dp)).padding(16.dp)
        .testTag("new-thread-recovery"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CpaIcon(CpaIcons.Warning, null, size = 18.dp, tint = c.orange); Spacer(Modifier.width(8.dp))
            Text(stringResource(if (state.unknown) R.string.ui_x_recovery_title else R.string.ui_x_creating), style = cpaText(16, FontWeight.SemiBold), color = c.label)
        }
        state.target?.let { SelectionContainer { Text(stringResource(R.string.creation_target_id, it), style = cpaText(13), color = c.secondaryLabel) } }
        if (state.unknown) Text(stringResource(R.string.creation_unknown), style = cpaText(14), color = c.secondaryLabel)
        if (state.inspected) {
            if (state.preview.isEmpty()) Text(stringResource(R.string.empty_target_records), style = cpaText(14), color = c.secondaryLabel)
            else Text(stringResource(R.string.target_preview_limit), style = cpaText(13), color = c.secondaryLabel)
            state.preview.takeLast(20).forEach { entry -> SelectionContainer { Text("${entry.kind}：${entry.text.take(400)}", style = cpaText(13), color = c.label, maxLines = 4, overflow = TextOverflow.Ellipsis) } }
        }
        state.error?.let { Text(stringResource(it), style = cpaText(14), color = c.red) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.unknown) {
                PillButton(stringResource(R.string.ui_x_inspect), !state.pending, controller::inspectTarget)
                if (state.inspected) PillButton(stringResource(R.string.ui_x_acknowledge), !state.pending, controller::acknowledge)
            }
            if (state.created && !state.unknown) PillButton(stringResource(R.string.ui_x_open_created), !state.pending, onClick = {
                state.connectionId?.let { id -> state.target?.let { onOpen(id, it) } }
            })
            PillButton(stringResource(R.string.ui_x_discard_attempt), !state.pending, onAbandon, destructive = true)
        }
    }
}

@Composable
internal fun PillButton(title: String, enabled: Boolean, onClick: () -> Unit, destructive: Boolean = false, prominent: Boolean = false, modifier: Modifier = Modifier) {
    val c = Cpa.colors
    Box(modifier.height(36.dp).background(if (prominent) c.accent else c.fill, RoundedCornerShape(18.dp)).pressable(enabled, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(title, style = cpaText(15, FontWeight.Medium), color = when {
            !enabled -> c.tertiaryLabel
            prominent -> androidx.compose.ui.graphics.Color.White
            destructive -> c.red
            else -> c.label
        }, maxLines = 1)
    }
}
