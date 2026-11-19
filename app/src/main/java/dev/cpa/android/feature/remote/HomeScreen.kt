package dev.cpa.android.feature.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.core.storage.ConnectionIcon
import dev.cpa.android.core.storage.ConnectionMode
import dev.cpa.android.data.repository.*

internal val ConnectionIcon.vector: ImageVector get() = when (this) {
    ConnectionIcon.Code -> CpaIcons.Code
    ConnectionIcon.Terminal -> CpaIcons.Terminal
    ConnectionIcon.Laptop -> CpaIcons.Laptop
    ConnectionIcon.Display -> CpaIcons.Display
}

@Composable
internal fun ConnectionSummary.displayName(): String = name.ifBlank { tunnelServerName.ifBlank {
    if (mode == ConnectionMode.Tunnel) tunnelServerId else address.substringAfter("://").trimEnd('/')
}.ifBlank { stringResource(R.string.remote_desktop) } }

@Composable
internal fun statusColor(connection: ConnectionSummary): Color {
    val c = Cpa.colors
    if (!connection.enabled) return c.gray3
    return when (connection.phase) {
        ConnectionPhase.Connected -> c.online
        ConnectionPhase.Connecting -> c.orange
        ConnectionPhase.Failed, ConnectionPhase.AuthenticationRequired -> c.red
        ConnectionPhase.Disconnected -> c.gray3
    }
}

/** Direct desktop web link for a session (iOS DesktopClient.deepLink). */
internal fun sessionDeepLink(connection: ConnectionSummary?, sessionId: String): String? =
    connection?.takeIf { it.mode == ConnectionMode.Direct }?.address?.trimEnd('/')?.takeIf(String::isNotBlank)
        ?.let { "$it/#/chat/${android.net.Uri.encode(sessionId)}" }

private sealed interface HomeSheet {
    data object Settings : HomeSheet
    data object Search : HomeSheet
    data class Worktree(val connection: String, val session: String) : HomeSheet
}

@Composable
fun RemoteHomeRoute(vm: RemoteHomeViewModel, onThread: (String, String) -> Unit, onNewThread: () -> Unit) {
    val homeComposer by vm.homeComposer.state.collectAsStateWithLifecycle()
    val collapsedGroups by vm.collapsedGroups.collectAsStateWithLifecycle()
    val connections by vm.connections.collectAsStateWithLifecycle()
    val runningSessions by vm.runningSessions.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val operation by vm.operation.collectAsStateWithLifecycle()
    val storageError by vm.storageError.collectAsStateWithLifecycle()
    val icons by vm.connectionIcons.collectAsStateWithLifecycle()
    val newThread by vm.newThread.state.collectAsStateWithLifecycle()
    LaunchedEffect(operation.opened) { operation.opened?.let { vm.consumedNavigation(); onThread(it.first, it.second) } }
    LaunchedEffect(newThread.open) { if (newThread.open) onNewThread() }
    LaunchedEffect(newThread.openedSession) {
        newThread.openedSession?.let { session ->
            val connection = newThread.connectionId
            if (connection != null) { vm.newThread.consumedNavigation(); onThread(connection, session) }
        }
    }
    val c = Cpa.colors
    val notice = rememberNotice()
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.ui_common_copied_to_clipboard)
    fun copy(text: String?) { if (!text.isNullOrEmpty()) { clipboard.setText(AnnotatedString(text)); notice.show(copied) } }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = connections.filter { it.enabled }
    LaunchedEffect(enabled.map { it.id }, selected) { if (selected != null && enabled.none { it.id == selected }) selected = null }
    LaunchedEffect(selected) { vm.homeComposer.selectConnection(selected) }
    val visible = enabled.filter { selected == null || it.id == selected }
    val showsDevice = selected == null && visible.size > 1
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    var expandedGroups by rememberSaveable { mutableStateOf(listOf<String>()) }
    var rename by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var removal by remember { mutableStateOf<Pair<String, RemoteProject>?>(null) }
    var addChoice by remember { mutableStateOf(false) }
    var connectionEditor by remember { mutableStateOf<ConnectionEditorTarget?>(null) }
    val fastOn = stringResource(R.string.ui_composer_fast_mode_enabled_notice)
    val fastOff = stringResource(R.string.ui_composer_fast_mode_disabled_notice)

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            // Toolbar: settings (menu glyph) and chat search, both round glass buttons.
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(CpaIcons.Menu, stringResource(R.string.ui_home_settings_accessibility), { sheet = HomeSheet.Settings },
                    modifier = Modifier.testTag("home-settings"))
                Spacer(Modifier.weight(1f))
                GlassIconButton(CpaIcons.Search, stringResource(R.string.ui_home_search_chats_accessibility), { sheet = HomeSheet.Search },
                    enabled = enabled.isNotEmpty(), modifier = Modifier.testTag("home-search"))
            }
            PullToRefreshBox(operation.refreshing, onRefresh = { vm.refreshVisible(visible.map { it.id }) }, modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().testTag("home-list"), contentPadding = PaddingValues(top = 5.dp, bottom = 120.dp)) {
                    if (enabled.isNotEmpty()) item(key = "filters") {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(stringResource(R.string.ui_home_filter_all), null, null, selected == null) { selected = null }
                            enabled.forEach { connection ->
                                FilterChip(connection.displayName(), ConnectionIcon.of(icons[connection.id]).vector, statusColor(connection), selected == connection.id,
                                    Modifier.testTag(homeKey("filter", connection.id))) { selected = connection.id }
                            }
                        }
                    }
                    storageError?.let { problem -> item(key = "storage-error") { StatusLine(CpaIcons.Warning, c.orange, repositoryProblem(problem)) } }
                    if (connections.isEmpty() && storageError == null) item(key = "empty") {
                        EmptyState(CpaIcons.Display, stringResource(R.string.ui_home_empty_no_desktop_title), stringResource(R.string.ui_home_empty_no_desktop_message),
                            Modifier.padding(top = 120.dp), stringResource(R.string.ui_home_empty_no_desktop_action)) { addChoice = true }
                    } else if (enabled.isEmpty() && connections.isNotEmpty()) item(key = "all-disconnected") {
                        EmptyState(CpaIcons.BoltOutline, stringResource(R.string.ui_home_empty_all_disconnected_title),
                            stringResource(R.string.ui_home_empty_all_disconnected_message), Modifier.padding(top = 120.dp),
                            stringResource(R.string.ui_home_empty_all_disconnected_action)) { sheet = HomeSheet.Settings }
                    } else {
                        val pinned = homePinnedSessions(connections, sessions, selected)
                        if (pinned.isNotEmpty()) {
                            item(key = "pinned-title") { SectionTitle(stringResource(R.string.ui_home_pinned_section)) }
                            items(pinned, key = { homeKey("pinned", it.connection.id, it.session.id) }) { item ->
                                Box(Modifier.padding(horizontal = 20.dp)) {
                                    ThreadRow(item.connection, item.session, if (showsDevice) item.connection.displayName() else null, 0,
                                        item.session.id in runningSessions[item.connection.id].orEmpty(), projects[item.connection.id].orEmpty(),
                                        onOpen = { openThreadOrRecovery(vm, newThread, item.connection.id, item.session.id, onThread) },
                                        actions = sessionActions(vm, item.connection, item.session, projects[item.connection.id].orEmpty(), ::copy,
                                            onRename = { rename = Triple(item.connection.id, item.session.id, item.session.title) },
                                            onWorktree = { sheet = HomeSheet.Worktree(item.connection.id, item.session.id) }))
                                }
                            }
                            item(key = "pinned-gap") { Spacer(Modifier.height(5.dp)) }
                        }
                        item(key = "projects-title") { SectionTitle(stringResource(R.string.ui_home_projects_section)) }
                        visible.forEach { connection ->
                            val running = runningSessions[connection.id].orEmpty()
                            val all = homeProjectSessions(sessions[connection.id].orEmpty(), running)
                            val groups = projects[connection.id].orEmpty().let { list -> list.filter { it.pinned } + list.filterNot { it.pinned } }
                            if (connection.phase != ConnectionPhase.Connected) item(key = homeKey("status", connection.id)) {
                                ClientStatusRow(connection) { vm.connect(connection.id) }
                            } else if (groups.isEmpty() && all.isEmpty() && sessions[connection.id] == null) item(key = homeKey("syncing", connection.id)) {
                                Row(Modifier.height(48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Spinner(size = 16.dp); Spacer(Modifier.width(10.dp))
                                    Text(stringResource(R.string.ui_home_syncing, connection.displayName()), style = cpaText(15), color = c.secondaryLabel)
                                }
                            }
                            fun group(project: RemoteProject?, threads: List<RemoteSession>) {
                                val key = homeGroupKey(connection.id, project?.id)
                                val collapsed = key in collapsedGroups
                                val expanded = key in expandedGroups
                                val limit = homeGroupLimit(threads, running)
                                item(key = key) {
                                    ProjectHeader(project, connection, showsDevice, collapsed,
                                        onToggle = {
                                            if (!collapsed) expandedGroups = expandedGroups - key
                                            vm.toggleGroup(key)
                                        },
                                        onNew = { vm.newThread.open(connection.id, project?.id) },
                                        menu = project?.let { p -> { dismiss -> ProjectMenuItems(p, dismiss,
                                            onPin = { vm.pinProject(connection.id, p.id, !p.pinned) },
                                            onEdit = { vm.projectEditor.open(connection.id, p) },
                                            onRemove = { removal = connection.id to p }) } })
                                }
                                if (!collapsed) {
                                    items(if (expanded) threads else threads.take(limit), key = { homeKey("session", connection.id, it.id) }) { session ->
                                        Box(Modifier.padding(horizontal = 20.dp)) {
                                            ThreadRow(connection, session, null, 36, session.id in running, projects[connection.id].orEmpty(),
                                                onOpen = { openThreadOrRecovery(vm, newThread, connection.id, session.id, onThread) },
                                                actions = sessionActions(vm, connection, session, projects[connection.id].orEmpty(), ::copy,
                                                    onRename = { rename = Triple(connection.id, session.id, session.title) },
                                                    onWorktree = { sheet = HomeSheet.Worktree(connection.id, session.id) }))
                                        }
                                    }
                                    if (threads.size > limit) item(key = homeKey("group-more", key)) {
                                        Text(if (expanded) stringResource(R.string.ui_home_show_less) else stringResource(R.string.ui_home_show_all, threads.size),
                                            Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 40.dp)
                                                .pressable { expandedGroups = if (expanded) expandedGroups - key else expandedGroups + key }
                                                .padding(start = 36.dp, top = 10.dp, bottom = 10.dp), style = cpaText(15), color = c.secondaryLabel)
                                    }
                                }
                                item(key = homeKey("group-gap", key)) { Spacer(Modifier.height(5.dp)) }
                            }
                            groups.forEach { project -> group(project, all.filter { it.projectId == project.id }) }
                            val ids = groups.map { it.id }.toSet()
                            val orphans = all.filter { it.projectId == null || it.projectId !in ids }
                            if (orphans.isNotEmpty()) group(null, orphans)
                        }
                    }
                }
            }
        }
        // Compact composer floating over the list; return prepares a new chat on the selected desktop.
        if (enabled.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 38.dp).padding(bottom = 4.dp)) {
                val skills by vm.homeComposer.skills.state.collectAsStateWithLifecycle()
                ComposerBar(field = { menu ->
                    RemoteSkillComposer(vm.homeComposer.skills, homeComposer.draft, vm.homeComposer::edit, homeComposer.defaults, true,
                        stringResource(R.string.ui_composer_ask_placeholder),
                        if (!homeComposer.connected) emptyList() else composerCommands(
                            fast = if (homeComposer.model?.supportsFast == true) ({
                                vm.homeComposer.toggleSpeed()
                                notice.show(if (vm.homeComposer.state.value.speed == "fast") fastOn else fastOff)
                            }) else null, compact = null, model = vm.homeComposer::modelSettings,
                            fastOn = homeComposer.speed == "fast", currentModel = homeComposer.model?.label),
                        onSubmit = vm.homeComposer::submit, submitEnabled = homeComposer.connectionId != null, maxLines = 5, menuState = menu,
                        modifier = Modifier.testTag("home-composer"))
                }, hasText = homeComposer.draft.isNotBlank() && skills.draft.text.isNotBlank(), canSend = homeComposer.connectionId != null,
                    onSubmit = vm.homeComposer::submit, onAttach = { vm.newThread.open(homeComposer.connectionId, autoProject = true) })
            }
        }
        TransientNotice(notice, Modifier.align(Alignment.TopCenter).statusBarsPadding())
    }

    when (val current = sheet) {
        HomeSheet.Settings -> SettingsSheet(vm, connections, icons, onDismiss = { sheet = null }, onAdd = { addChoice = true },
            onEdit = { connectionEditor = ConnectionEditorTarget(it) })
        HomeSheet.Search -> SearchSheet(vm.search, enabled, projects, sessions, onDismiss = { sheet = null }, onOpen = { connection, session ->
            sheet = null; openThreadOrRecovery(vm, newThread, connection, session, onThread)
        }, onNewChat = { connection -> sheet = null; vm.newThread.open(connection, autoProject = true) }, onNotice = notice::show)
        is HomeSheet.Worktree -> {
            val connection = connections.find { it.id == current.connection }
            val session = sessions[current.connection].orEmpty().find { it.id == current.session }
            if (connection == null || session == null) LaunchedEffect(Unit) { sheet = null }
            else WorktreeForkSheet(vm, connection, session, projects[connection.id].orEmpty(), operation.pending,
                onDismiss = { if (!operation.pending) sheet = null }) { worktree -> vm.forkSession(connection.id, session.id, worktree) { sheet = null } }
        }
        null -> Unit
    }
    RemoteProjectEditor(vm.projectEditor, projects)
    if (addChoice) CpaActionSheet(stringResource(R.string.ui_connection_add_dialog_title), listOf(
        DialogAction(stringResource(R.string.ui_connection_add_direct)) { addChoice = false; connectionEditor = ConnectionEditorTarget(null, tunnel = false) },
        DialogAction(stringResource(R.string.ui_connection_add_tunnel)) { addChoice = false; connectionEditor = ConnectionEditorTarget(null, tunnel = true) },
        DialogAction(stringResource(R.string.ui_common_cancel), cancel = true) { addChoice = false }), onDismiss = { addChoice = false })
    connectionEditor?.let { target ->
        ConnectionEditorSheet(vm, target, connections, icons, operation, onDismiss = { if (!operation.pending) connectionEditor = null }, onSaved = { connectionEditor = null })
    }
    rename?.let { (connection, session, title) ->
        var text by remember(session) { mutableStateOf(title) }
        val requester = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
        CpaAlert(stringResource(R.string.ui_home_rename_chat), null, listOf(
            DialogAction(stringResource(R.string.ui_common_cancel), cancel = true) { rename = null },
            DialogAction(stringResource(R.string.ui_common_save), enabled = text.isNotBlank()) {
                rename = null
                if (text.trim() != title) vm.renameSession(connection, session, text.trim())
            }), onDismiss = { rename = null }) {
            AlertTextField(text, { text = it }, stringResource(R.string.ui_common_title), focusRequester = requester)
        }
    }
    removal?.let { (connection, project) ->
        CpaAlert(stringResource(R.string.ui_project_remove_confirm_named, project.name), stringResource(R.string.ui_project_remove_confirm_message), listOf(
            DialogAction(stringResource(R.string.ui_common_cancel), cancel = true) { removal = null },
            DialogAction(stringResource(R.string.ui_project_remove), destructive = true) { removal = null; vm.removeProject(connection, project.id) }),
            onDismiss = { removal = null })
    }
    operation.error?.let { error ->
        if (connectionEditor == null) CpaAlert(stringResource(R.string.ui_common_action_failed), stringResource(error),
            listOf(DialogAction(stringResource(R.string.ui_common_ok), cancel = true) { vm.dismissError() }), onDismiss = vm::dismissError)
    }
}

/** An unconfirmed new-chat target reopens its recovery form instead of the half-created conversation. */
private fun openThreadOrRecovery(vm: RemoteHomeViewModel, state: NewThreadState, connection: String, session: String, onThread: (String, String) -> Unit) {
    if (state.unknown && state.connectionId == connection && state.target == session) vm.newThread.open() else onThread(connection, session)
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, Modifier.padding(horizontal = 20.dp).height(48.dp).wrapContentHeight(Alignment.CenterVertically).semantics { heading() },
        style = cpaText(17, FontWeight.SemiBold), color = Cpa.colors.label)
}

@Composable
private fun StatusLine(icon: ImageVector, tint: Color, text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        CpaIcon(icon, null, size = 18.dp, tint = tint); Spacer(Modifier.width(10.dp))
        Text(text, style = cpaText(15), color = Cpa.colors.secondaryLabel)
    }
}

/** Shown in place of a desktop's projects while it is not connected. */
@Composable
private fun ClientStatusRow(connection: ConnectionSummary, onRetry: () -> Unit) {
    val c = Cpa.colors
    val name = connection.displayName()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 4.dp).testTag(homeKey("connection", connection.id)),
        verticalAlignment = Alignment.CenterVertically) {
        when (connection.phase) {
            ConnectionPhase.Connecting -> {
                Spinner(size = 16.dp); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ui_home_client_connecting, name), style = cpaText(15), color = c.secondaryLabel)
            }
            ConnectionPhase.Failed, ConnectionPhase.AuthenticationRequired -> {
                CpaIcon(CpaIcons.Warning, null, size = 18.dp, tint = c.orange); Spacer(Modifier.width(10.dp))
                val detail = connection.error?.let { repositoryProblem(it) } ?: stringResource(
                    if (connection.phase == ConnectionPhase.AuthenticationRequired) R.string.password_expired else R.string.connection_failed)
                Text(stringResource(R.string.ui_common_error_with_detail, name, detail), Modifier.weight(1f), style = cpaText(15), color = c.secondaryLabel, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.ui_common_retry), Modifier.pressable(onClick = onRetry).padding(vertical = 8.dp), style = cpaText(15, FontWeight.Medium), color = c.secondaryLabel)
            }
            else -> Text(stringResource(R.string.ui_home_client_not_connected, name), style = cpaText(15), color = c.secondaryLabel)
        }
    }
}

/** Device filter capsule shown under the toolbar. */
@Composable
private fun FilterChip(title: String, icon: ImageVector?, dot: Color?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Cpa.colors
    Row(modifier.height(43.dp).background(if (selected) c.chipBackground else Color.Transparent, CircleShape).padding(horizontal = 16.dp)
        .pressable(role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
        .semantics { stateDescription = if (selected) "selected" else "" }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (dot != null) Box(Modifier.size(7.dp).background(dot, CircleShape))
        if (icon != null) CpaIcon(icon, null, size = 16.dp, tint = if (selected) c.label else c.secondaryLabel)
        Text(title, style = cpaText(17), color = if (selected) c.label else c.secondaryLabel, maxLines = 1)
    }
}

@Composable
private fun ProjectHeader(project: RemoteProject?, connection: ConnectionSummary, showsDevice: Boolean, collapsed: Boolean,
    onToggle: () -> Unit, onNew: () -> Unit, menu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)?) {
    val c = Cpa.colors
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val title = project?.name ?: stringResource(R.string.ui_home_chats_title)
    val state = stringResource(if (collapsed) R.string.ui_home_collapsed else R.string.ui_home_expanded)
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize().pressable(pressedAlpha = .6f, onLongClick = menu?.let { { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open = true } }, onClick = onToggle)
                .semantics { heading(); stateDescription = state }.testTag(homeKey("group", connection.id, project?.id.orEmpty())),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                    CpaIcon(if (project == null) CpaIcons.Chats else CpaIcons.Folder, null, size = 21.dp, tint = c.label)
                }
                Spacer(Modifier.width(14.dp))
                Text(title, Modifier.weight(1f, fill = false), style = cpaText(17), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showsDevice) { Spacer(Modifier.width(14.dp)); Text(connection.displayName(), style = cpaText(13), color = c.secondaryLabel, maxLines = 1) }
            }
            if (menu != null) CpaMenu(open, { open = false }, offset = DpOffset(30.dp, 0.dp)) { menu { open = false } }
        }
        Box(Modifier.size(44.dp).pressable(enabled = connection.phase == ConnectionPhase.Connected, label = stringResource(R.string.ui_search_new_chat), onClick = onNew)
            .semantics { contentDescription = "new:$title" }.testTag(homeKey("new", connection.id, project?.id.orEmpty())), contentAlignment = Alignment.Center) {
            CpaIcon(CpaIcons.Compose, null, size = 21.dp, tint = c.secondaryLabel)
        }
    }
}

@Composable
private fun ColumnScope.ProjectMenuItems(project: RemoteProject, dismiss: () -> Unit, onPin: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    CpaMenuItem(stringResource(if (project.pinned) R.string.ui_project_unpin else R.string.ui_project_pin), if (project.pinned) CpaIcons.PinOff else CpaIcons.Pin) { dismiss(); onPin() }
    CpaMenuItem(stringResource(R.string.ui_project_edit), CpaIcons.Gear) { dismiss(); onEdit() }
    CpaMenuItem(stringResource(R.string.ui_project_remove), CpaIcons.Close, destructive = true) { dismiss(); onRemove() }
}

private class SessionActions(val content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit)

@Composable
private fun sessionActions(vm: RemoteHomeViewModel, connection: ConnectionSummary, session: RemoteSession, projects: List<RemoteProject>,
    copy: (String?) -> Unit, onRename: () -> Unit, onWorktree: () -> Unit): SessionActions {
    val project = projects.find { it.id == session.projectId }
    val unread = session.unread != "read"
    val link = sessionDeepLink(connection, session.id)
    val connected = connection.phase == ConnectionPhase.Connected
    return SessionActions { dismiss ->
        var moving by remember { mutableStateOf(false) }
        if (moving) {
            CpaMenuItem(stringResource(R.string.ui_home_move_to_project), CpaIcons.ChevronLeft) { moving = false }
            CpaMenuDivider()
            CpaMenuItem(stringResource(R.string.ui_home_no_project), checked = session.projectId == null) {
                dismiss(); if (session.projectId != null) vm.moveSession(connection.id, session.id, null)
            }
            projects.let { list -> list.filter { it.pinned } + list.filterNot { it.pinned } }.forEach { candidate ->
                CpaMenuItem(candidate.name, checked = session.projectId == candidate.id) {
                    dismiss(); if (session.projectId != candidate.id) vm.moveSession(connection.id, session.id, candidate.id)
                }
            }
            return@SessionActions
        }
        CpaMenuItem(stringResource(if (session.pinned) R.string.ui_home_unpin else R.string.ui_home_pin_chat), if (session.pinned) CpaIcons.PinOff else CpaIcons.Pin, enabled = connected) {
            dismiss(); vm.pinSession(connection.id, session.id, !session.pinned)
        }
        CpaMenuItem(stringResource(R.string.ui_home_move_to_project), CpaIcons.Folder, enabled = connected, trailing = CpaIcons.ChevronRight) { moving = true }
        if (project != null) CpaMenuItem(stringResource(R.string.ui_home_remove_from_project), CpaIcons.FolderMinus, enabled = connected) {
            dismiss(); vm.moveSession(connection.id, session.id, null)
        }
        CpaMenuItem(stringResource(R.string.ui_home_rename_chat), CpaIcons.Pencil, enabled = connected) { dismiss(); onRename() }
        CpaMenuItem(stringResource(if (unread) R.string.ui_home_mark_read else R.string.ui_home_mark_unread), if (unread) CpaIcons.MailOpen else CpaIcons.MailBadge, enabled = connected) {
            dismiss(); vm.markUnread(connection.id, session.id, !unread)
        }
        CpaMenuItem(stringResource(R.string.ui_home_archive_chat), CpaIcons.Archive, destructive = true, enabled = connected) {
            dismiss(); vm.archiveSession(connection.id, session.id)
        }
        CpaMenuDivider()
        CpaMenuItem(stringResource(R.string.ui_home_copy_working_directory), CpaIcons.Copy, enabled = project?.allPaths?.firstOrNull() != null) {
            dismiss(); copy(session.worktreePath ?: project?.allPaths?.firstOrNull())
        }
        CpaMenuItem(stringResource(R.string.ui_home_copy_session_id), CpaIcons.Hash) { dismiss(); copy(session.id) }
        CpaMenuItem(stringResource(R.string.ui_home_copy_deep_link), CpaIcons.Link, enabled = link != null) { dismiss(); copy(link) }
        CpaMenuDivider()
        CpaMenuItem(stringResource(R.string.ui_home_continue_in_new_chat), CpaIcons.MessagePlus, enabled = connected) { dismiss(); vm.forkSession(connection.id, session.id) }
        CpaMenuItem(stringResource(R.string.ui_home_continue_in_new_worktree), CpaIcons.Branch, enabled = connected) { dismiss(); onWorktree() }
    }
}

@Composable
private fun ThreadRow(connection: ConnectionSummary, session: RemoteSession, detail: String?, leading: Int, running: Boolean,
    projects: List<RemoteProject>, onOpen: () -> Unit, actions: SessionActions) {
    val c = Cpa.colors
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val title = session.title.ifBlank { stringResource(R.string.ui_home_new_chat) }
    val label = listOfNotNull(title, detail, if (running) stringResource(R.string.ui_home_running) else null,
        if (session.unread != "read") stringResource(if (session.unread == "error") R.string.ui_home_unread_error else R.string.ui_home_unread) else null)
        .joinToString(stringResource(R.string.ui_home_accessibility_list_separator))
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .pressable(pressedAlpha = .5f, label = label, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open = true }, onClick = onOpen)
            .semantics { contentDescription = label }.testTag(homeKey("open", connection.id, session.id))
            .padding(start = leading.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f, fill = detail == null), style = cpaText(17), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) { Text(detail, style = cpaText(13), color = c.secondaryLabel, maxLines = 1); Spacer(Modifier.weight(1f)) }
            if (running) Spinner(size = 16.dp)
            if (session.unread != "read") Box(Modifier.size(8.dp).background(if (session.unread == "error") c.red else c.blue, CircleShape))
        }
        CpaMenu(open, { open = false }, offset = DpOffset((leading + 20).dp, 0.dp)) { actions.content(this) { open = false } }
    }
}

// Length framing makes keys unique even for IDs containing separators, across connections.
internal fun homeKey(kind: String, connection: String, child: String = "") =
    "$kind:${connection.length}:$connection:${child.length}:$child"
