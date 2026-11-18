package dev.cpa.android.feature.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.data.repository.git.GitFile
import dev.cpa.android.data.repository.git.GitHunk
import dev.cpa.android.data.repository.git.GitLine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Answers a question raised by the agent's ask tool on the desktop. */
@Composable
internal fun AskSheet(controller: RemoteAskController, sendUnknown: Boolean, onDismiss: () -> Unit) {
    val c = Cpa.colors
    val state by controller.state.collectAsStateWithLifecycle()
    val request = state.request
    LaunchedEffect(request?.id) { if (request == null && !state.loading && state.snapshotFresh) onDismiss() }
    val enabled = state.active && state.snapshotFresh && !state.busy && !state.outcomeUnknown
    CpaSheet(onDismiss, grouped = true) {
        SheetTopBar(stringResource(R.string.ui_ask_title), leading = { GlassTextButton(stringResource(R.string.ui_ask_later), onDismiss, modifier = Modifier.testTag("ask-later")) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(top = 12.dp, bottom = 32.dp).testTag("ask-sheet")) {
            if (request == null) {
                FormGroup { FormRow { Text(stringResource(if (state.loading) R.string.ask_querying else if (state.lookupError != null) R.string.ask_unknown_status else R.string.ask_none),
                    style = cpaText(17), color = c.secondaryLabel) } }
            } else {
                FormGroup { FormRow { SelectionContainer { Text(request.question, style = cpaText(17), color = c.label) } } }
                if (!state.snapshotFresh) FormFooter(stringResource(R.string.ask_wait_fresh))
                if (request.options.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    FormGroup {
                        request.options.forEachIndexed { index, option ->
                            if (index > 0) FormDivider()
                            val title = (option["title"] as? JsonPrimitive)?.content.orEmpty()
                            val description = option["description"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                            FormRow(onClick = { controller.select(index) }, enabled = enabled, modifier = Modifier.testTag("ask-option:$index")) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(title, style = cpaText(17), color = if (enabled) c.label else c.tertiaryLabel)
                                        if (option["recommended"] == JsonPrimitive(true)) Text(stringResource(R.string.ui_ask_recommended),
                                            Modifier.background(c.accent.copy(alpha = .15f), CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
                                            style = cpaText(12, FontWeight.Medium), color = c.accent)
                                    }
                                    if (!description.isNullOrEmpty()) Text(description, style = cpaText(14), color = c.secondaryLabel)
                                }
                            }
                        }
                        if (request.countdownDeadline != null) {
                            FormDivider()
                            FormRow(onClick = controller::cancelCountdown, enabled = enabled && !state.cancellingCountdown) {
                                Text(stringResource(R.string.ui_ask_stop_auto_select), style = cpaText(17), color = c.secondaryLabel)
                            }
                        }
                    }
                    request.countdownDeadline?.let { deadline ->
                        val remaining = (((deadline - state.nowMillis).coerceAtLeast(0) + 500) / 1000).toInt()
                        FormFooter(if (remaining > 0) stringResource(R.string.ui_ask_countdown_seconds, remaining) else stringResource(R.string.ui_ask_auto_select_now))
                    }
                }
                if (request.allowCustom) {
                    Spacer(Modifier.height(20.dp))
                    FormGroup {
                        BasicTextField(state.customText, controller::setCustomText, Modifier.fillMaxWidth().semantics { contentDescription = "ask-custom" }.testTag("ask-custom"),
                            enabled = !state.busy || state.cancellingCountdown, maxLines = 5, textStyle = cpaText(17).copy(color = c.label), cursorBrush = SolidColor(c.accent),
                            decorationBox = { inner -> Box(Modifier.heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 14.dp)) {
                                if (state.customText.isEmpty()) Text(request.customPrompt ?: stringResource(R.string.ui_ask_custom_placeholder), style = cpaText(17), color = c.tertiaryLabel)
                                inner()
                            } })
                        FormDivider()
                        FormRow(onClick = controller::submitCustom, enabled = enabled && state.customText.isNotBlank(), modifier = Modifier.testTag("ask-send")) {
                            Text(stringResource(R.string.ui_ask_send_answer), style = cpaText(17), color = if (enabled && state.customText.isNotBlank()) c.blue else c.tertiaryLabel)
                        }
                    }
                }
                if (request.allowSkip) {
                    Spacer(Modifier.height(20.dp))
                    FormGroup { FormRow(onClick = controller::skip, enabled = enabled) { Text(stringResource(R.string.ui_ask_skip), style = cpaText(17), color = if (enabled) c.blue else c.tertiaryLabel) } }
                }
            }
            state.lookupError?.let { FormFooter(stringResource(it), color = c.red) }
            state.actionMessage?.let { FormFooter(stringResource(it), color = c.red) }
            if (sendUnknown) FormFooter(stringResource(R.string.ask_unknown), color = c.orange)
            if (state.outcomeUnknown) {
                Spacer(Modifier.height(20.dp))
                FormGroup {
                    FormButtonRow(stringResource(R.string.ask_refresh_verify), controller::refresh, enabled = state.active && !state.busy)
                    FormDivider()
                    FormButtonRow(stringResource(R.string.ask_acknowledge), controller::acknowledgeOutcome, enabled = state.checkedAfterFailure)
                }
            }
        }
    }
}

/** One sheet for "Changes" and "Files" (desktop right panel): diff cards or the project file tree. */
@Composable
internal fun SessionPanelSheet(vm: RemoteThreadViewModel, connected: Boolean, startOnFiles: Boolean, onDismiss: () -> Unit) {
    val c = Cpa.colors
    val scope = rememberCoroutineScope()
    val git = remember(vm) { vm.gitBrowser(scope) }
    val files = remember(vm) { vm.fileBrowser(scope) }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val session = sessions[vm.connectionId].orEmpty().find { it.id == vm.sessionId }
    val projectList = projects[vm.connectionId].orEmpty()
    var showsFiles by rememberSaveable { mutableStateOf(startOnFiles) }
    var override by rememberSaveable { mutableStateOf<String?>(null) }
    var base by rememberSaveable { mutableStateOf<String?>(null) }
    val overrideProject = projectList.find { it.id == override }
    val path = overrideProject?.allPaths?.firstOrNull() ?: session?.worktreePath ?: projectList.find { it.id == session?.projectId }?.allPaths?.firstOrNull()
    val sessionProject = projectList.find { it.id == session?.projectId }
    val projectName = overrideProject?.name ?: sessionProject?.name ?: path?.trimEnd('/', '\\')?.substringAfterLast('/') ?: stringResource(R.string.ui_panels_changes)
    val state by git.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(path, base, connected) {
        if (connected) git.load(path, base, if (override == null) session?.branch else null) else git.close()
    }
    LaunchedEffect(path, connected) { if (connected) files.root(path) else files.close() }
    LaunchedEffect(state.review) {
        val review = state.review ?: return@LaunchedEffect
        val valid = review.files.map { it.path }.toSet()
        expanded = expanded intersect valid
        if (expanded.isEmpty()) review.files.firstOrNull()?.takeIf { f -> f.hunks.sumOf { it.lines.size } <= 400 }?.let { expanded = setOf(it.path) }
    }
    DisposableEffect(git) { onDispose { git.close(); files.close() } }
    val review = state.review
    val allExpanded = review != null && review.files.isNotEmpty() && expanded.size >= review.files.size
    CpaSheet(onDismiss) {
        // Header: centered title (project switcher on the changes tab) with trailing glass controls.
        Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 22.dp)) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 110.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (showsFiles) Text(stringResource(R.string.ui_panels_files), style = cpaText(20, FontWeight.SemiBold), color = c.label)
                else {
                    var projectMenu by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.pressable { projectMenu = true }.testTag("panel-project"), verticalAlignment = Alignment.CenterVertically) {
                            Text(projectName, style = cpaText(20, FontWeight.SemiBold), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(4.dp)); CpaIcon(CpaIcons.ChevronDown, null, size = 14.dp, tint = c.label)
                        }
                        CpaMenu(projectMenu, { projectMenu = false }) {
                            projectList.forEach { p -> CpaMenuItem(p.name, checked = p.name == projectName) {
                                projectMenu = false
                                val next = if (p.id == session?.projectId) null else p.id
                                if (next != override) { override = next; base = null; expanded = emptySet() }
                            } }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.ui_panels_branch), style = cpaText(14), color = c.secondaryLabel)
                        if (review != null) {
                            Text("+${review.files.sumOf { it.additions }}", style = cpaText(14), color = c.green)
                            Text("-${review.files.sumOf { it.deletions }}", style = cpaText(14), color = c.red)
                        }
                    }
                }
            }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)) {
                if (showsFiles) GlassIconButton(CpaIcons.Close, stringResource(R.string.ui_common_close), onDismiss, modifier = Modifier.testTag("panel-close"))
                else GlassButtonPair({ BarIcon(if (allExpanded) CpaIcons.Shrink else CpaIcons.Expand,
                    stringResource(if (allExpanded) R.string.ui_panels_collapse_all else R.string.ui_panels_expand_all), {
                        expanded = if (allExpanded) emptySet() else review?.files?.map { it.path }?.toSet().orEmpty()
                    }, size = 19.dp, modifier = Modifier.testTag("panel-expand-all")) },
                    { BarIcon(CpaIcons.Close, stringResource(R.string.ui_common_close), onDismiss, modifier = Modifier.testTag("panel-close")) })
            }
        }
        SegmentedTabs(stringResource(R.string.ui_panels_modified), stringResource(R.string.ui_panels_all_files), showsFiles, { showsFiles = it },
            Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).testTag("panel-tabs"))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showsFiles) FileTree(files, connected)
            else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !connected -> CenteredState(CpaIcons.BoltOutline, stringResource(R.string.desktop_disconnected), null)
                        path == null -> CenteredState(CpaIcons.Folder, stringResource(R.string.ui_panels_no_project_title), stringResource(R.string.ui_panels_no_project_message))
                        state.loading && review == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
                        state.error != null && base != null -> CenteredState(CpaIcons.Warning, stringResource(R.string.ui_panels_compare_failed_title), stringResource(R.string.ui_panels_compare_failed_message))
                        state.error != null -> CenteredState(CpaIcons.Warning, stringResource(R.string.ui_panels_not_git_repo), path)
                        review != null && review.files.isEmpty() -> CenteredState(CpaIcons.Branch, stringResource(R.string.ui_panels_no_changes_title), stringResource(R.string.ui_panels_no_changes_message))
                        review != null -> LazyColumn(Modifier.fillMaxSize().testTag("diff-list"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(review.files, key = { it.path }) { file ->
                                DiffFileCard(file, file.path in expanded, { expanded = if (file.path in expanded) expanded - file.path else expanded + file.path }) {
                                    files.open(RemotePath.join(review.repo.root, file.path))
                                }
                            }
                        }
                    }
                }
                // Branch bar: compare target against the working tree or an origin base branch.
                if (review != null || base != null) {
                    var branchMenu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().background(c.secondaryBackground.copy(alpha = .96f)).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(review?.repo?.current ?: session?.branch ?: "HEAD", style = cpaText(16), color = c.label, maxLines = 1)
                        CpaIcon(CpaIcons.ArrowRight, null, size = 16.dp, tint = c.secondaryLabel)
                        Box {
                            Row(Modifier.pressable { branchMenu = true }.testTag("panel-base"), verticalAlignment = Alignment.CenterVertically) {
                                Text("origin/${base ?: review?.repo?.defaultBranch ?: session?.branch ?: "main"}", style = cpaText(16), color = c.blue, maxLines = 1)
                                Spacer(Modifier.width(4.dp)); CpaIcon(CpaIcons.ChevronDown, null, size = 12.dp, tint = c.blue)
                            }
                            CpaMenu(branchMenu, { branchMenu = false }) {
                                CpaMenuItem(stringResource(R.string.ui_panels_working_tree_changes), checked = base == null) { branchMenu = false; base = null }
                                CpaMenuDivider()
                                (review?.repo?.branches.orEmpty() + listOfNotNull(review?.repo?.defaultBranch, "main")).distinct().sorted().forEach { name ->
                                    CpaMenuItem("origin/$name", checked = base == name) { branchMenu = false; base = name }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    FilePreviewSheet(files, connected)
}

@Composable
private fun CenteredState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState(icon, title, message.orEmpty(), Modifier.padding(bottom = 60.dp)) }
}

@Composable
private fun DiffFileCard(file: GitFile, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val c = Cpa.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.gray6.copy(alpha = .9f)).testTag("diff:${file.path}")) {
        Row(Modifier.fillMaxWidth().pressable(pressedAlpha = .6f, onClick = onToggle).padding(start = 16.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(file.path.substringAfterLast('/'), style = cpaText(17, FontWeight.SemiBold), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val dir = file.path.substringBeforeLast('/', "")
                if (dir.isNotEmpty()) Text(dir, style = cpaText(14), color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            Spacer(Modifier.width(12.dp))
            DiffStat(file.additions, file.deletions)
            Box(Modifier.size(38.dp).pressable(label = stringResource(R.string.ui_panels_open_file), onClick = onOpen).semantics { contentDescription = "open:${file.path}" },
                contentAlignment = Alignment.Center) { CpaIcon(CpaIcons.OpenExternal, null, size = 20.dp, tint = c.label) }
        }
        if (expanded) Column(Modifier.fillMaxWidth().background(c.background)) {
            when {
                file.binary -> Text(stringResource(R.string.ui_panels_binary_changed), Modifier.padding(14.dp), style = cpaText(14), color = c.secondaryLabel)
                file.hunks.isEmpty() -> Text(stringResource(R.string.ui_panels_no_diff), Modifier.padding(14.dp), style = cpaText(14), color = c.secondaryLabel)
                else -> file.hunks.forEach { hunk ->
                    HunkHeader(hunk)
                    hunk.lines.forEach { line -> DiffLine(line, file.path) }
                }
            }
        }
    }
}

@Composable
private fun DiffStat(additions: Int, deletions: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("+$additions", style = cpaText(14, FontWeight.Medium), color = Cpa.colors.green)
        Text("-$deletions", style = cpaText(14, FontWeight.Medium), color = Cpa.colors.red)
    }
}

@Composable
private fun HunkHeader(hunk: GitHunk) {
    val c = Cpa.colors
    val numbers = hunk.lines.mapNotNull { it.newNumber }
    val start = numbers.minOrNull() ?: hunk.lines.mapNotNull { it.oldNumber }.minOrNull() ?: 0
    val end = numbers.maxOrNull() ?: start
    Row(Modifier.fillMaxWidth().background(c.gray5.copy(alpha = .7f)).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        CpaIcon(CpaIcons.ChevronDown, null, size = 12.dp, tint = c.secondaryLabel); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ui_panels_hunk_lines, start, end), Modifier.weight(1f), style = cpaText(14), color = c.label)
        DiffStat(hunk.lines.count { it.kind == '+' }, hunk.lines.count { it.kind == '-' })
    }
}

@Composable
private fun DiffLine(line: GitLine, path: String) {
    val c = Cpa.colors
    val (row, gutter, number) = when (line.kind) {
        '+' -> Triple(c.green.copy(alpha = if (c.dark) .20f else .12f), c.green.copy(alpha = if (c.dark) .32f else .24f), c.green.copy(alpha = .95f))
        '-' -> Triple(c.red.copy(alpha = if (c.dark) .18f else .10f), c.red.copy(alpha = if (c.dark) .30f else .20f), c.red.copy(alpha = .95f))
        else -> Triple(c.background, c.gray6.copy(alpha = .6f), c.secondaryLabel.copy(alpha = c.secondaryLabel.alpha * .8f))
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(row)) {
        Text((line.newNumber ?: line.oldNumber)?.toString().orEmpty(), Modifier.fillMaxHeight().background(gutter).width(42.dp).padding(end = 8.dp, top = 2.dp),
            style = cpaText(12, mono = true), color = number, textAlign = TextAlign.End)
        Text(highlightedLine(line.text, path), Modifier.weight(1f).padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            style = cpaText(13, mono = true, lineHeight = 18.sp), color = c.label)
    }
}

@Composable
internal fun FileTree(controller: RemoteFilesController, connected: Boolean) {
    val c = Cpa.colors
    val state by controller.state.collectAsStateWithLifecycle()
    val rows = remember(state.children, state.expanded, state.query, state.root) { fileRows(state) }
    Box(Modifier.fillMaxSize()) {
        when {
            state.root == null -> CenteredState(CpaIcons.Folder, stringResource(R.string.ui_panels_no_project_title), null)
            state.root in state.loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
            rows.isEmpty() -> CenteredState(CpaIcons.Search, stringResource(R.string.ui_panels_no_matching_files), null)
            else -> LazyColumn(Modifier.fillMaxSize().testTag("file-tree"), contentPadding = PaddingValues(bottom = 90.dp)) {
                items(rows, key = { it.node.path }) { row ->
                    val open = row.node.path in state.expanded
                    Row(Modifier.fillMaxWidth().pressable(connected, pressedAlpha = .5f) { controller.toggle(row.node) }
                        .padding(start = 16.dp + (row.depth.coerceAtMost(12) * 20).dp, end = 16.dp, top = 11.dp, bottom = 11.dp).testTag("file:${row.node.path}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(12.dp)) { if (row.node.directory) CpaIcon(if (open) CpaIcons.ChevronDown else CpaIcons.ChevronRight, null, size = 12.dp, tint = c.gray) }
                        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                            CpaIcon(if (row.node.directory) CpaIcons.Folder else CpaIcons.DocText, null, size = 21.dp, tint = c.secondaryLabel)
                        }
                        Text(row.node.name, Modifier.weight(1f), style = cpaText(16), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.node.path in state.loading) Spinner(size = 14.dp)
                    }
                    Box(Modifier.padding(start = 16.dp + (row.depth.coerceAtMost(12) * 20).dp + 64.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                }
            }
        }
        state.errors.entries.firstOrNull()?.let { (path, error) ->
            Text(stringResource(R.string.directory_error_path, path, stringResource(error)), Modifier.align(Alignment.TopCenter).padding(12.dp)
                .pressable(connected) { controller.load(path) }, style = cpaText(13), color = c.red)
        }
        GlassSearchField(state.query, controller::query, stringResource(R.string.ui_panels_search_files),
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth().testTag("file-search"))
    }
}

/** Read-only file viewer with line numbers and syntax highlighting. */
@Composable
internal fun FilePreviewSheet(controller: RemoteFilesController, connected: Boolean) {
    val c = Cpa.colors
    val state by controller.state.collectAsStateWithLifecycle()
    val preview = state.preview ?: return
    val clipboard = LocalClipboardManager.current
    val root = state.root
    val relative = if (root != null && preview.path.startsWith(root.trimEnd('/') + "/")) preview.path.removePrefix(root.trimEnd('/') + "/") else preview.path.substringAfterLast('/')
    CpaSheet(controller::closePreview) {
        SheetTopBar(relative, leading = { GlassTextButton(stringResource(R.string.ui_common_close), controller::closePreview, modifier = Modifier.testTag("preview-close")) },
            trailing = { GlassIconButton(CpaIcons.Copy, stringResource(R.string.ui_panels_copy_file_path), { clipboard.setText(AnnotatedString(preview.path)) },
                modifier = Modifier.testTag("preview-copy")) })
        when {
            preview.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
            preview.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(CpaIcons.Warning, stringResource(R.string.ui_panels_unable_read_file), stringResource(preview.error),
                    actionTitle = stringResource(R.string.ui_common_retry).takeIf { connected }, onAction = { controller.open(preview.path) })
            }
            else -> SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().testTag("file-preview"), contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (preview.truncated) item { Text(stringResource(R.string.ui_x_truncated), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = cpaText(12), color = c.secondaryLabel) }
                    items(preview.lines.size) { index ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            Text("${index + 1}", Modifier.width(44.dp).padding(end = 12.dp, top = 1.dp), style = cpaText(13, mono = true),
                                color = c.secondaryLabel.copy(alpha = c.secondaryLabel.alpha * .6f), textAlign = TextAlign.End)
                            Text(highlightedLine(preview.lines[index], preview.path), Modifier.weight(1f).padding(top = 1.dp), style = cpaText(13, mono = true, lineHeight = 18.sp), color = c.label)
                        }
                    }
                }
            }
        }
    }
}
