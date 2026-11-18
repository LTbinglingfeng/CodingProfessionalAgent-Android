package dev.cpa.android.feature.remote

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.data.repository.*
import dev.cpa.android.feature.remote.markdown.RemoteCodeBlock
import dev.cpa.android.feature.remote.markdown.RemoteMarkdown
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.*

/** Separator for a compacted context. */
@Composable
internal fun CompactionDivider() {
    val c = Cpa.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).height(0.5.dp).background(c.separator))
        Text(stringResource(R.string.ui_entry_compacted), style = cpaText(12), color = c.secondaryLabel)
        Box(Modifier.weight(1f).height(0.5.dp).background(c.separator))
    }
}

/** Prompt text with skill tokens shown as inline cube chips, like the desktop message bubble. */
@Composable
internal fun promptAnnotated(parts: List<PromptPart>): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val c = Cpa.colors
    val text = buildAnnotatedString {
        parts.forEach { part -> when (part) {
            is PromptPart.Text -> append(part.value)
            is PromptPart.Skill -> withStyle(SpanStyle(color = c.skillChip, fontWeight = FontWeight.Medium)) {
                appendInlineContent("cube", "◇")
                append("\u00A0" + skillDisplayName(part.name).replace(' ', '\u00A0'))
            }
        } }
    }
    val inline = mapOf("cube" to InlineTextContent(Placeholder(15.sp, 15.sp, PlaceholderVerticalAlign.TextCenter)) {
        CpaIcon(CpaIcons.Cube, null, size = 14.dp, tint = c.skillChip)
    })
    return text to inline
}

/** Sent prompt with its images above the text; a long press offers copy, edit and share. */
@Composable
internal fun UserBubble(entry: RemoteEntry, knownSkills: List<RemoteSkill>, trigger: Char, onEdit: (() -> Unit)?) {
    val c = Cpa.colors
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val parts = remember(entry, knownSkills) { entry.promptParts(knownSkills) }
    val plain = remember(parts, trigger) { parts.promptText(trigger) }
    val showMenu = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }
    Row(Modifier.fillMaxWidth().testTag("user:${entry.id}")) {
        // iOS: Spacer(minLength: 60) keeps the bubble off the leading edge even for long text.
        Spacer(Modifier.width(60.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (entry.images.isNotEmpty()) RemoteSentImages(entry.images, pending = entry.pendingStatus != null)
            if (entry.text.isNotEmpty() || entry.images.isEmpty()) Box {
                val (text, inline) = promptAnnotated(parts)
                Text(text, Modifier.background(c.userBubble, CpaTokens.BubbleShape)
                    .pressable(pressedAlpha = .85f, onLongClick = showMenu, onClick = {})
                    .testTag("bubble:${entry.id}").padding(horizontal = 15.dp, vertical = 11.dp), style = cpaText(17, lineHeight = 23.sp), color = c.userBubbleText, inlineContent = inline)
                Box(Modifier.align(Alignment.BottomEnd).size(1.dp)) { CpaMenu(menu, { menu = false }, offset = DpOffset(0.dp, 6.dp)) {
                    if (plain.isNotEmpty()) CpaMenuItem(stringResource(R.string.ui_entry_copy), CpaIcons.Copy) { menu = false; clipboard.setText(AnnotatedString(plain)) }
                    if (onEdit != null) CpaMenuItem(stringResource(R.string.ui_common_edit), CpaIcons.Pencil) { menu = false; onEdit() }
                    if (plain.isNotEmpty()) CpaMenuItem(stringResource(R.string.ui_entry_share_ellipsis), CpaIcons.Share) {
                        menu = false
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, plain) }, resources.getString(R.string.share_message))) }
                    }
                } }
            }
            entry.pendingStatus?.let { status ->
                val label = when (status) {
                    "queue" -> R.string.ui_entry_queued
                    "steer" -> R.string.ui_entry_steering
                    else -> R.string.ui_entry_follow_up_pending
                }
                Text(stringResource(label), style = cpaText(12), color = c.secondaryLabel)
            }
        }
    }
}

/** Replaces a sent prompt's bubble while it is edited; send reruns the conversation from this prompt. */
@Composable
internal fun UserMessageEditor(editor: RemoteMessageEditor, thread: RemoteThread, defaults: RemoteDefaults, connected: Boolean) {
    val c = Cpa.colors
    val state by editor.state.collectAsStateWithLifecycle()
    val original = thread.entries.find { it.id == state.entryId }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(state.entryId) { delay(150); runCatching { focus.requestFocus() } }
    val canSubmit = connected && state.draft.isNotBlank() && !state.pending && !state.unknown && !thread.outcomeUnknown && !thread.loading && !thread.running &&
        !thread.editAwaitingStart && original?.kind == "user" && original.pendingStatus == null
    Column(Modifier.fillMaxWidth().background(c.secondaryBackground, CpaTokens.BubbleShape).border(0.5.dp, c.separator, CpaTokens.BubbleShape)
        .padding(start = 15.dp, end = 15.dp, top = 13.dp, bottom = 10.dp).testTag("message-editor"), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (original?.images?.isNotEmpty() == true) RemoteSentImages(original.images)
        RemoteSkillComposer(editor.skills, state.draft, editor::edit, defaults, !state.pending, stringResource(R.string.ui_entry_edit_message_accessibility),
            maxLines = 8, minHeight = 60.dp, focusRequester = focus)
        if (state.pending) Text(stringResource(R.string.ui_x_edit_waiting), Modifier.fillMaxWidth(), style = cpaText(13), color = c.secondaryLabel)
        state.error?.let { Text(stringResource(it), Modifier.fillMaxWidth(), style = cpaText(13), color = c.red) }
        if (state.unknown || thread.outcomeUnknown) {
            Text(stringResource(R.string.ui_x_edit_unknown), Modifier.fillMaxWidth(), style = cpaText(13), color = c.orange)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.ui_x_check), connected && !state.pending, editor::inspect)
                PillButton(stringResource(R.string.ui_x_acknowledge), connected && state.inspected && !state.pending, editor::acknowledge)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(stringResource(R.string.ui_common_cancel), !state.pending, { if (state.unknown) editor.close() else editor.discard() }, modifier = Modifier.testTag("edit-cancel"))
            Box(Modifier.height(36.dp).background(if (canSubmit) c.accent else c.accent.copy(alpha = .4f), CircleShape).pressable(canSubmit, onClick = editor::submit)
                .padding(horizontal = 16.dp).testTag("edit-send"), contentAlignment = Alignment.Center) {
                if (state.pending) Spinner(size = 16.dp, color = Color.White)
                else Text(stringResource(R.string.ui_composer_send), style = cpaText(15, FontWeight.SemiBold), color = Color.White)
            }
        }
    }
}

/** Fallback rendering for entries outside the turn projection (orphan results, unknown kinds). */
@Composable
internal fun RemoteMessage(entry: RemoteEntry, wrapCode: Boolean = true, knownSkills: List<RemoteSkill> = emptyList()) {
    val c = Cpa.colors
    when {
        entry.kind.equals("user", true) -> UserBubble(entry, knownSkills, '$', null)
        entry.toolName != null || entry.kind.contains("tool", true) -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(listOfNotNull(entry.kind, entry.toolName).joinToString(" · "), style = cpaText(13), color = c.secondaryLabel)
            RemoteCodeBlock(entry.text, wrapCode)
        }
        else -> RemoteMarkdown(entry.text, wrapCode)
    }
}

@Composable
internal fun SystemPromptBuildStatus() {
    Row(Modifier.semantics(mergeDescendants = true) {}.testTag("system-prompt-build-status"),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Same icon box as ToolLine so the status reads as one more tool row.
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) { CpaIcon(CpaIcons.DocText, null, size = 15.dp, tint = Cpa.colors.secondaryLabel) }
        ShimmerText(stringResource(R.string.ui_turn_system_prompt_build_status), cpaText(15), active = true, Modifier.weight(1f, fill = false))
    }
}

/** One assistant turn in the desktop's folded style: elapsed-time header, then activity or the final reply. */
@Composable
internal fun RemoteAssistantTurn(turn: RemoteTurn.Assistant, runningTools: Map<String, RemoteRunningTool>, wrapCode: Boolean,
    subAgents: List<RemoteSubAgent> = emptyList(), onSubAgent: ((RemoteSubAgent) -> Unit)? = null, onForkMessage: ((RemoteEntry) -> Unit)? = null,
    isBuildingSystemPrompt: Boolean = false) {
    val c = Cpa.colors
    var expanded by rememberSaveable(turn.live) { mutableStateOf(turn.live) }
    var now by remember { mutableDoubleStateOf(System.currentTimeMillis().toDouble()) }
    LaunchedEffect(turn.live) { while (turn.live) { now = System.currentTimeMillis().toDouble(); delay(1000) } }
    val reply = turn.trailingText
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = turnTitle(turn, now)
            Text(title, Modifier.fillMaxWidth().then(if (turn.live) Modifier else Modifier.pressable(pressedAlpha = .5f,
                label = stringResource(if (expanded) R.string.ui_turn_collapse_history else R.string.ui_turn_expand_history)) { expanded = !expanded })
                .testTag("turn-header"), style = cpaText(13), color = c.secondaryLabel)
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        }
        if (!turn.live || turn.hasVisibleActivity) {
            if (turn.live || expanded) RemoteTurnActivity(turn, runningTools, wrapCode, subAgents, onSubAgent)
            else {
                val lastMessage = turn.items.indexOfLast { it.kind == "assistant" }
                turn.items.forEachIndexed { index, entry ->
                    if (entry.kind == "compaction") CompactionDivider()
                    else if (index == lastMessage && reply.isNotBlank()) RemoteMarkdown(reply, wrapCode)
                }
            }
        }
        if (turn.live && isBuildingSystemPrompt) SystemPromptBuildStatus()
        if (turn.status == "error") Row(verticalAlignment = Alignment.Top) {
            CpaIcon(CpaIcons.Warning, null, Modifier.padding(top = 2.dp), size = 16.dp, tint = c.red); Spacer(Modifier.width(6.dp))
            Text(turn.error ?: stringResource(R.string.ui_turn_generation_failed), style = cpaText(15), color = c.red)
        }
        if (turn.status == "done" && reply.isNotBlank()) {
            val boundary = turn.messages.lastOrNull() ?: turn.items.lastOrNull()
            MessageActions(reply, turn.completedAt ?: turn.messages.lastOrNull()?.details?.createdAt,
                onFork = if (boundary != null && onForkMessage != null) ({ onForkMessage(boundary) }) else null)
        }
    }
}

@Composable
private fun turnTitle(turn: RemoteTurn.Assistant, now: Double): String {
    val elapsed = turn.elapsed(now)
    val time = formatTurnDuration(elapsed)
    val hasDuration = turn.live || (turn.completedAt != null && elapsed >= 1000)
    return when (turn.status) {
        "running" -> stringResource(R.string.ui_turn_processing, time)
        "error", "aborted" -> if (hasDuration) stringResource(R.string.ui_turn_interrupted, time) else stringResource(R.string.ui_turn_interrupted_bare)
        else -> if (hasDuration) stringResource(R.string.ui_turn_completed, time) else stringResource(R.string.ui_turn_completed_bare)
    }
}

/** Copy / fork actions with the message timestamp, like the desktop's assistant message actions. */
@Composable
internal fun MessageActions(text: String, timestamp: Double?, onFork: (() -> Unit)?) {
    val c = Cpa.colors
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(28.dp).pressable(label = stringResource(R.string.copy_reply)) { clipboard.setText(AnnotatedString(text)) }
            .semantics { contentDescription = "copy-reply" }, contentAlignment = Alignment.Center) {
            CpaIcon(CpaIcons.Copy, null, size = 17.dp, tint = c.label.copy(alpha = .7f))
        }
        if (onFork != null) Box(Modifier.size(28.dp).pressable(label = stringResource(R.string.fork_here), onClick = onFork)
            .semantics { contentDescription = "fork-here" }, contentAlignment = Alignment.Center) {
            CpaIcon(CpaIcons.Branch, null, Modifier.rotate(90f), size = 17.dp, tint = c.label.copy(alpha = .7f))
        }
        messageTime(timestamp, locale = LocalConfiguration.current.locales[0])?.let {
            Text(it, Modifier.padding(start = 2.dp), style = cpaText(12), color = c.secondaryLabel)
        }
    }
}

@Composable
internal fun RemoteTurnActivity(turn: RemoteTurn.Assistant, running: Map<String, RemoteRunningTool>, wrap: Boolean,
    agents: List<RemoteSubAgent>, onAgent: ((RemoteSubAgent) -> Unit)?) {
    val parts = remember(turn, running) { turnActivity(turn, running) }
    parts.forEachIndexed { index, part -> key(index) {
        when (part) {
            is TurnActivity.Text -> RemoteMarkdown(part.text, wrap)
            is TurnActivity.Compaction -> CompactionDivider()
            is TurnActivity.Tools -> {
                val launches = isAgentLaunch(part.items.first().call.name)
                val active = turn.live && index == parts.lastIndex
                if (launches) {
                    val states = part.items.groupBy { normalizedToolId(it.call.id) }.mapValues { (_, items) -> items.any { it.running } }
                    SubAgentPills(subAgentPills(part.items.map { it.call }, emptyMap(), running, agents, active, launchStates = states, expandNested = false), onAgent)
                } else ToolActivityStack(part.items, active, wrap, running)
            }
        }
    } }
}

private val prettyJson = Json { prettyPrint = true }
private fun preview(value: String, limit: Int): String = if (value.codePointCount(0, value.length) <= limit) value
    else value.substring(0, value.offsetByCodePoints(0, limit)) + "\n…"

private fun toolIcon(name: String, args: JsonElement): ImageVector = when {
    isSkillRead(name, args) -> CpaIcons.DocText
    name in setOf("bash", "shell", "pwsh", "powershell") -> CpaIcons.Wrench
    name == "edit" -> CpaIcons.PencilLine
    name == "write" -> CpaIcons.DocPlus
    name == "ask" || name == "ask_user" -> CpaIcons.QuestionCircle
    else -> CpaIcons.DocText
}

/** Consecutive tool calls folded into one line showing the latest call; tapping a call shows its details. */
@Composable
private fun ToolActivityStack(items: List<TurnTool>, active: Boolean, wrap: Boolean, running: Map<String, RemoteRunningTool>) {
    val latest = items.lastOrNull() ?: return
    var history by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    val stackActive = active || items.any { it.running }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (items.size > 1) {
            ToolLine(latest, stackActive, if (history) CpaIcons.ChevronDown else CpaIcons.ChevronRight,
                stringResource(if (history) R.string.ui_turn_collapse_history else R.string.ui_turn_expand_history)) { history = !history }
            if (history) Row(Modifier.padding(start = 6.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(Cpa.colors.separator))
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEachIndexed { index, item -> key(item.owner, item.call.id, index) {
                        val id = "${item.owner}:${item.call.id}:$index"
                        ToolCall(item, (index == items.lastIndex && stackActive) || item.running, detail == id, wrap, running) { detail = if (detail == id) null else id }
                    } }
                }
            }
        } else {
            val id = "${latest.owner}:${latest.call.id}:0"
            ToolCall(latest, stackActive, detail == id, wrap, running) { detail = if (detail == id) null else id }
        }
    }
}

@Composable
private fun ToolCall(item: TurnTool, shimmering: Boolean, open: Boolean, wrap: Boolean, running: Map<String, RemoteRunningTool>, onToggle: () -> Unit) {
    val c = Cpa.colors
    ToolLine(item, shimmering, null, stringResource(if (open) R.string.ui_turn_collapse_details else R.string.ui_turn_expand_details), onToggle)
    if (open) {
        if (item.result?.details?.isError == true) Row(verticalAlignment = Alignment.CenterVertically) {
            CpaIcon(CpaIcons.Close, null, size = 13.dp, tint = c.red); Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.ui_common_error), style = cpaText(13), color = c.red)
        }
        val sections = buildList {
            if (item.call.arguments != JsonNull) add(preview(prettyJson.encodeToString(JsonElement.serializer(), item.call.arguments), 4000))
            item.result?.let { add(preview(it.text, 6000)) }
            if (item.result == null) (running[normalizedToolId(item.call.id)]?.details)?.let { add(preview(prettyJson.encodeToString(JsonElement.serializer(), it), 6000)) }
        }
        if (sections.isNotEmpty()) SelectionContainer { RemoteCodeBlock(sections.joinToString("\n\n"), wrap) }
    }
}

@Composable
private fun ToolLine(item: TurnTool, shimmering: Boolean, chevron: ImageVector?, hint: String, onClick: () -> Unit) {
    val c = Cpa.colors
    val summary = toolSummary(item.call.name, item.call.arguments, item.running).localized()
    Row(Modifier.fillMaxWidth().pressable(pressedAlpha = .5f, label = hint, onClick = onClick).testTag("tool-line"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (chevron != null) Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) { CpaIcon(chevron, null, size = 11.dp, tint = c.secondaryLabel) }
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) { CpaIcon(toolIcon(item.call.name, item.call.arguments), null, size = 15.dp, tint = c.secondaryLabel) }
        ShimmerText(summary, cpaText(15), shimmering, Modifier.weight(1f))
    }
}

/** Badges of the sub-agents launched in a turn; tapping one opens its own run view. */
@Composable
internal fun SubAgentPills(pills: List<RemoteAgentPill>, onOpen: ((RemoteSubAgent) -> Unit)?) {
    if (pills.isEmpty()) return
    val c = Cpa.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pills.forEach { pill ->
            val record = pill.record
            val label = if (pill.queued) stringResource(R.string.ui_subagent_pill_queued_label, pill.name) else pill.name
            Row(Modifier.background(c.secondaryBackground, CircleShape).border(0.5.dp, c.separator, CircleShape)
                .pressable(record != null && onOpen != null, label = stringResource(R.string.ui_subagent_open_hint)) { record?.let { onOpen?.invoke(it) } }
                .semantics { contentDescription = label }.testTag("agent-pill:${pill.id}")
                .padding(start = 6.dp, end = 11.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SubAgentAvatar(record?.icon, record?.color, 20)
                ShimmerText(pill.name, cpaText(14), pill.running, Modifier.widthIn(max = 160.dp), color = if (pill.running) c.secondaryLabel else c.label)
                if (pill.queued) Text(stringResource(R.string.ui_subagent_queued_suffix), style = cpaText(12), color = c.secondaryLabel)
            }
        }
    }
}

internal fun parseHexColor(hex: String?): Color? {
    var value = hex?.trim()?.removePrefix("#") ?: return null
    if (value.length == 3) value = value.map { "$it$it" }.joinToString("")
    if (value.length != 6) return null
    return value.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/** Round sub-agent icon tinted with its color (desktop SubAgentAvatar). */
@Composable
internal fun SubAgentAvatar(icon: String?, color: String?, size: Int) {
    val tint = parseHexColor(color) ?: Color(0xFF9B7DFF)
    val glyph = when (icon) {
        "sun" -> CpaIcons.Sparkles
        "hexagon" -> CpaIcons.Cube
        "flame" -> CpaIcons.Bolt
        "brain" -> CpaIcons.Cpu
        else -> CpaIcons.Sparkle
    }
    Box(Modifier.size(size.dp).background(tint.copy(alpha = .13f), CircleShape), contentAlignment = Alignment.Center) {
        CpaIcon(glyph, null, size = maxOf(10, (size * .55f).toInt()).dp, tint = tint)
    }
}
