package dev.cpa.android.feature.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.CancellationException

/** Matched parts are emphasized and underlined, like the desktop chat search. */
internal fun highlighted(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val needle = query.trim()
    if (needle.isEmpty()) return@buildAnnotatedString
    var index = text.indexOf(needle, ignoreCase = true)
    while (index >= 0) {
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline), index, index + needle.length)
        index = text.indexOf(needle, index + needle.length, ignoreCase = true)
    }
}

/** Chat search: recent chats and quick actions while empty, otherwise matches from every listed desktop. */
@Composable
internal fun SearchSheet(controller: RemoteSearchController, connections: List<ConnectionSummary>, projects: Map<String, List<RemoteProject>>,
    sessions: Map<String, List<RemoteSession>>, onDismiss: () -> Unit, onOpen: (String, String) -> Unit, onNewChat: (String?) -> Unit, onNotice: (String) -> Unit) {
    val c = Cpa.colors
    val state by controller.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(controller) {
        controller.search(controller.state.value.query)
        onStopOrDispose { controller.close() }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); runCatching { focus.requestFocus() } }
    val showsDevice = connections.size > 1
    val comingSoon = stringResource(R.string.ui_common_coming_soon)
    CpaSheet(onDismiss, grouped = true) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassSearchField(state.query, controller::search, stringResource(R.string.ui_search_placeholder), Modifier.weight(1f).testTag("search-field"), focus)
            Spacer(Modifier.width(10.dp))
            GlassIconButton(CpaIcons.Close, stringResource(R.string.ui_common_close), onDismiss, modifier = Modifier.testTag("search-close"))
        }
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                FormSectionHeader(stringResource(R.string.ui_search_chats), Modifier.padding(top = 0.dp)) {
                    if (state.loading) Spinner(size = 14.dp)
                }
            }
            item {
                FormGroup(Modifier.testTag("search-results")) {
                    if (state.results.isEmpty() && !state.loading) Text(stringResource(R.string.ui_search_no_results), Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        style = cpaText(15), color = c.secondaryLabel, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    state.results.forEachIndexed { index, result ->
                        if (index > 0) FormDivider()
                        val connection = connections.find { it.id == result.connectionId }
                        val session = sessions[result.connectionId].orEmpty().find { it.id == result.sessionId }
                        val project = projects[result.connectionId].orEmpty().find { it.id == session?.projectId }
                        SearchRow(result, connection, project, session?.branch, showsDevice, state.query) { onOpen(result.connectionId, result.sessionId) }
                    }
                }
                if (state.fallbackConnections.isNotEmpty()) FormFooter(stringResource(R.string.search_local_fallback))
            }
            if (state.query.isBlank()) item {
                FormSectionHeader(stringResource(R.string.ui_search_quick_actions))
                FormGroup {
                    QuickAction(CpaIcons.Compose, stringResource(R.string.ui_search_new_chat), Modifier.testTag("search-new-chat")) {
                        onNewChat((connections.firstOrNull { it.phase == ConnectionPhase.Connected } ?: connections.firstOrNull())?.id)
                    }
                    FormDivider(start = 64.dp)
                    QuickAction(CpaIcons.Folder, stringResource(R.string.ui_search_open_folder)) { onNotice(comingSoon) }
                    FormDivider(start = 64.dp)
                    QuickAction(CpaIcons.Search, stringResource(R.string.ui_search_search_files)) { onNotice(comingSoon) }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(result: RemoteSearchResult, connection: ConnectionSummary?, project: RemoteProject?, branch: String?, showsDevice: Boolean, query: String, onClick: () -> Unit) {
    val c = Cpa.colors
    val detail = listOfNotNull(if (showsDevice) connection?.displayName() else null, project?.name).filter(String::isNotEmpty).joinToString(" · ")
    Column(Modifier.fillMaxWidth().pressable(pressedAlpha = .5f, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)
        .testTag(homeKey("search", result.connectionId, result.sessionId)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!branch.isNullOrEmpty()) CpaIcon(CpaIcons.Branch, stringResource(R.string.ui_search_branch), size = 15.dp, tint = c.purple)
            Text(highlighted(result.title.ifBlank { stringResource(R.string.ui_home_new_chat) }, query), Modifier.weight(1f), style = cpaText(16), color = c.label,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, Modifier.widthIn(max = 160.dp), style = cpaText(13), color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val snippet = result.snippet
        if (!snippet.isNullOrBlank() && query.isNotBlank()) Text(highlighted(snippet, query), style = cpaText(13), color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FormRow(modifier, onClick = onClick) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { CpaIcon(icon, null, size = 22.dp, tint = Cpa.colors.label) }
        Spacer(Modifier.width(18.dp))
        Text(title, style = cpaText(16), color = Cpa.colors.label)
    }
}

/** "Continue in New Worktree": fork into a new git worktree with an optional environment. */
@Composable
internal fun WorktreeForkSheet(vm: RemoteHomeViewModel, connection: ConnectionSummary, session: RemoteSession, projects: List<RemoteProject>,
    submitting: Boolean, onDismiss: () -> Unit, onSubmit: (RemoteWorktreeFork) -> Unit) {
    val c = Cpa.colors
    val project = projects.find { it.id == session.projectId }
    val source = project?.allPaths?.firstOrNull()
    var branch by rememberSaveable { mutableStateOf(session.branch ?: "main") }
    var branches by remember { mutableStateOf(emptyList<String>()) }
    var environment by rememberSaveable { mutableStateOf(session.environmentId) }
    var environments by remember { mutableStateOf(emptyList<RemoteProject>()) }
    var detecting by remember { mutableStateOf(true) }
    LaunchedEffect(connection.id, session.id) {
        for (path in listOfNotNull(session.worktreePath, source)) {
            val result = try { vm.gitBranches(connection.id, path) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (result == null || result.names.isEmpty()) continue
            result.current?.let { branch = it }
            branches = if (branch in result.names) result.names else listOf(branch) + result.names
            break
        }
    }
    LaunchedEffect(connection.id) {
        environments = try { vm.environmentProjects(connection.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        detecting = false
    }
    CpaSheet(onDismiss, grouped = true, dismissible = !submitting) {
        SheetTopBar(stringResource(R.string.ui_worktree_title),
            leading = { GlassTextButton(stringResource(R.string.ui_common_cancel), onDismiss, enabled = !submitting) },
            trailing = { if (submitting) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner() }
                else GlassTextButton(stringResource(R.string.ui_worktree_continue), { onSubmit(RemoteWorktreeFork(branch, environment)) },
                    enabled = source != null && branch.isNotBlank(), emphasized = true, modifier = Modifier.testTag("worktree-continue")) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 12.dp, bottom = 32.dp)) {
            FormGroup {
                if (branches.size > 1) FormMenuRow(stringResource(R.string.ui_worktree_branch), branch, branches.map { MenuOption(it, it) }, branch, { branch = it })
                else FormRow {
                    Text(stringResource(R.string.ui_worktree_branch), style = cpaText(17), color = c.label); Spacer(Modifier.weight(1f))
                    Text(branch, style = cpaText(17), color = c.secondaryLabel)
                }
                if (project != null) {
                    FormDivider()
                    FormRow {
                        Text(stringResource(R.string.ui_worktree_current_project), style = cpaText(17), color = c.label); Spacer(Modifier.weight(1f))
                        Text(project.name, style = cpaText(17), color = c.secondaryLabel, maxLines = 1)
                    }
                }
            }
            FormFooter(stringResource(if (source == null) R.string.ui_worktree_no_project_path else R.string.ui_worktree_create_hint), color = if (source == null) c.red else c.secondaryLabel)
            FormSectionHeader(stringResource(R.string.ui_worktree_environment_section)) { if (detecting) Spinner(size = 14.dp) }
            FormGroup {
                EnvironmentRow(stringResource(R.string.ui_worktree_without_env_title), stringResource(R.string.ui_worktree_without_env_detail), environment == null) { environment = null }
                environments.forEach { candidate ->
                    FormDivider()
                    EnvironmentRow(candidate.name, candidate.allPaths.firstOrNull() ?: "environment.toml", environment == candidate.id) { environment = candidate.id }
                }
            }
            FormFooter(stringResource(R.string.ui_worktree_environment_footer))
        }
    }
}

@Composable
private fun EnvironmentRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val c = Cpa.colors
    FormRow(onClick = onClick, modifier = Modifier.semantics { this.selected = selected }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = cpaText(17), color = c.label)
            Text(detail, style = cpaText(13), color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        if (selected) CpaIcon(CpaIcons.Check, null, size = 18.dp, tint = c.blue)
    }
}

/** Edits a project's name and source folders, or removes it (desktop ProjectEditDialog). */
@Composable
internal fun RemoteProjectEditor(controller: RemoteProjectController, projects: Map<String, List<RemoteProject>>) {
    val state by controller.state.collectAsStateWithLifecycle()
    val connection = state.connectionId ?: return
    val projectId = state.projectId ?: return
    val c = Cpa.colors
    val remote = projects[connection].orEmpty().find { it.id == projectId }
    var browse by rememberSaveable(connection, projectId) { mutableStateOf(false) }
    var confirm by rememberSaveable(connection, projectId) { mutableStateOf(false) }
    val enabled = !state.pending && !state.unknown
    CpaSheet(controller::close, grouped = true, dismissible = !state.pending) {
        if (browse) {
            val scope = rememberCoroutineScope()
            val browser = remember(connection) { controller.browser(connection, scope) }
            RemoteDirectoryBrowser(browser, { browse = false }) { path -> controller.paths(state.paths + path); browse = false }
            return@CpaSheet
        }
        SheetTopBar(stringResource(R.string.ui_project_edit),
            leading = { GlassTextButton(stringResource(R.string.ui_common_cancel), controller::close, enabled = !state.pending) },
            trailing = { if (state.pending) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner() }
                else GlassTextButton(stringResource(R.string.ui_common_save), controller::save, enabled = enabled && state.name.isNotBlank() && remote != null,
                    emphasized = true, modifier = Modifier.testTag("project-save")) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(bottom = 32.dp)) {
            FormSectionHeader(stringResource(R.string.ui_project_name))
            FormGroup { FormTextField(state.name, controller::name, stringResource(R.string.ui_project_name), enabled = !state.pending) }
            FormSectionHeader(stringResource(R.string.ui_project_source_folders))
            FormGroup {
                state.paths.forEach { path ->
                    FormRow {
                        CpaIcon(CpaIcons.Folder, null, size = 20.dp, tint = c.secondaryLabel)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'), style = cpaText(17), color = c.label, maxLines = 1)
                            Text(path, style = cpaText(13), color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(32.dp).pressable(!state.pending, label = stringResource(R.string.ui_project_remove_source_folder, path)) { controller.paths(state.paths - path) }
                            .semantics { contentDescription = "remove-folder:$path" }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(20.dp).background(c.gray3, CircleShape), contentAlignment = Alignment.Center) { CpaIcon(CpaIcons.Close, null, size = 10.dp, tint = Color.White) }
                        }
                    }
                    FormDivider(start = 52.dp)
                }
                FormRow(onClick = { browse = true }, enabled = !state.pending, modifier = Modifier.testTag("project-add-folder")) {
                    CpaIcon(CpaIcons.FolderPlus, null, size = 24.dp, tint = c.blue); Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.ui_project_add_folder), style = cpaText(17), color = c.blue)
                }
            }
            state.error?.let { FormFooter(stringResource(it), color = c.red) }
            if (state.unknown) {
                FormSectionHeader(stringResource(R.string.project_unknown))
                FormGroup {
                    FormButtonRow(stringResource(R.string.refresh_remote_project), controller::refresh, enabled = !state.pending)
                    if (state.refreshed) {
                        FormDivider()
                        FormRow { Text(if (remote == null) stringResource(R.string.remote_project_missing)
                            else stringResource(R.string.remote_project_snapshot, remote.name, remote.allPaths.joinToString(), remote.pinned.toString()),
                            style = cpaText(15), color = c.secondaryLabel) }
                        FormDivider()
                        FormButtonRow(stringResource(R.string.acknowledge_manual), controller::acknowledge, enabled = !state.pending)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            FormGroup { FormButtonRow(stringResource(R.string.ui_project_remove), { confirm = true }, destructive = true, enabled = enabled && remote != null) }
        }
    }
    if (confirm) CpaAlert(stringResource(R.string.ui_project_remove_confirm_named, remote?.name ?: state.name), stringResource(R.string.ui_project_remove_confirm_message), listOf(
        DialogAction(stringResource(R.string.ui_common_cancel), cancel = true) { confirm = false },
        DialogAction(stringResource(R.string.ui_project_remove), destructive = true) { confirm = false; controller.remove() }), onDismiss = { confirm = false })
}

/** Browses the desktop's folders to pick a project source folder (desktop DirectoryBrowserModal). */
@Composable
internal fun RemoteDirectoryBrowser(controller: RemoteDirectoryController, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val c = Cpa.colors
    val state by controller.state.collectAsStateWithLifecycle()
    var query by rememberSaveable(state.path) { mutableStateOf("") }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf<String?>(null) }
    LifecycleStartEffect(controller) {
        if (controller.state.value.path.isEmpty()) controller.home() else controller.load(controller.state.value.path)
        onStopOrDispose { controller.close() }
    }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val folders = state.entries.filter { it.isDirectory && (hidden || !it.name.startsWith('.')) && it.name.contains(query.trim(), true) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SheetTopBar(stringResource(R.string.ui_directory_title),
                leading = { GlassIconButton(CpaIcons.ChevronLeft, stringResource(R.string.ui_x_back), onDismiss) },
                trailing = { Box {
                    GlassIconButton(CpaIcons.Ellipsis, stringResource(R.string.ui_x_more), { menu = true }, modifier = Modifier.testTag("directory-menu"))
                    CpaMenu(menu, { menu = false }, offset = DpOffset(0.dp, 4.dp)) {
                        CpaMenuItem(stringResource(R.string.ui_directory_home), CpaIcons.House, enabled = state.home.isNotEmpty()) { menu = false; controller.home() }
                        CpaMenuItem(stringResource(R.string.ui_directory_root), CpaIcons.Drive) { menu = false; controller.load(RemotePath.root(state.path.ifEmpty { state.home })) }
                        CpaMenuItem(stringResource(R.string.ui_directory_go_to_path_ellipsis), CpaIcons.ArrowCircleRight) { menu = false; goTo = state.path }
                        CpaMenuItem(stringResource(R.string.ui_common_refresh), CpaIcons.Refresh, enabled = state.path.isNotEmpty()) { menu = false; controller.load(state.path) }
                        CpaMenuItem(stringResource(R.string.ui_directory_show_hidden), CpaIcons.Eye, checked = hidden) { menu = false; hidden = !hidden }
                    }
                } })
            GlassSearchField(query, { query = it }, stringResource(R.string.ui_directory_filter_placeholder), Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            LazyColumn(Modifier.weight(1f).testTag("desktop-directory-list"), contentPadding = PaddingValues(bottom = 110.dp)) {
                item {
                    Spacer(Modifier.height(16.dp))
                    FormGroup {
                        FormRow { SelectionContainer { Text(state.path.ifEmpty { " " }, style = cpaText(13, mono = true), color = c.secondaryLabel) } }
                        state.error?.let { error ->
                            FormDivider()
                            FormRow { CpaIcon(CpaIcons.Warning, null, size = 18.dp, tint = c.red); Spacer(Modifier.width(8.dp)); Text(stringResource(error), style = cpaText(15), color = c.red) }
                        }
                        RemotePath.parent(state.path)?.let { parent ->
                            FormDivider()
                            FormRow(onClick = { controller.load(parent) }) {
                                CpaIcon(CpaIcons.TurnUpLeft, null, size = 20.dp, tint = c.blue); Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.ui_directory_up), style = cpaText(17), color = c.blue)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                if (folders.isNotEmpty()) item {
                    FormGroup {
                        folders.forEachIndexed { index, entry ->
                            if (index > 0) FormDivider(start = 54.dp)
                            FormRow(onClick = { controller.load(RemotePath.join(state.path, entry.name)) }, enabled = !state.loading) {
                                CpaIcon(CpaIcons.Folder, null, size = 20.dp, tint = c.blue); Spacer(Modifier.width(14.dp))
                                Text(entry.name, Modifier.weight(1f), style = cpaText(17), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                CpaIcon(CpaIcons.ChevronRight, null, size = 13.dp, tint = c.tertiaryLabel)
                            }
                        }
                    }
                }
                if (state.loading && folders.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Spinner() } }
                else if (!state.loading && folders.isEmpty() && state.path.isNotEmpty()) item {
                    EmptyState(CpaIcons.Folder, stringResource(R.string.ui_directory_no_subfolders), "", Modifier.padding(top = 40.dp))
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp).height(52.dp).shadow(8.dp, CircleShape)
            .background(if (state.path.isNotEmpty() && !state.loading) c.accent else c.gray3, CircleShape)
            .pressable(state.path.isNotEmpty() && !state.loading) { onSelect(state.path) }.padding(horizontal = 28.dp).testTag("directory-select"),
            contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.ui_directory_select_this), style = cpaText(17, FontWeight.SemiBold), color = Color.White)
        }
    }
    goTo?.let { initial ->
        var text by remember { mutableStateOf(initial) }
        CpaAlert(stringResource(R.string.ui_directory_go_to_path), null, listOf(
            DialogAction(stringResource(R.string.ui_common_cancel), cancel = true) { goTo = null },
            DialogAction(stringResource(R.string.ui_directory_go), enabled = text.isNotBlank()) { goTo = null; controller.load(text.trim()) }),
            onDismiss = { goTo = null }) { AlertTextField(text, { text = it }, "/Users/name/Projects", androidx.compose.ui.text.input.KeyboardType.Uri) }
    }
}
