package dev.cpa.android.feature.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.data.repository.*

internal data class ComposerCommand(val id: String, val label: Int, val keywords: List<String>, val action: () -> Unit,
    val on: Boolean = false, val current: String? = null)
internal fun composerCommands(fast: (() -> Unit)?, compact: (() -> Unit)?, model: () -> Unit, fastOn: Boolean = false, currentModel: String? = null): List<ComposerCommand> = buildList {
    fast?.let { add(ComposerCommand("fast", R.string.ui_composer_fast_title, listOf("fast", "speed", "快速", "kuaisu"), it, on = fastOn)) }
    compact?.let { add(ComposerCommand("compact", R.string.ui_composer_compact_title, listOf("compact", "compress", "压缩", "yasuo"), it)) }
    add(ComposerCommand("model", R.string.ui_composer_model_title, listOf("model", "models", "模型", "moxing"), model, current = currentModel))
}

/** iOS RemoteSkill.displayName: "gh-issue" → "Gh Issue". */
internal fun skillDisplayName(name: String) = name.trim().split('-', '_').filter(String::isNotEmpty)
    .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }

/** Row of the "/" (commands) or skill trigger suggestion menu. */
internal sealed interface ComposerMenuItem {
    val key: String
    data class Command(val command: ComposerCommand) : ComposerMenuItem { override val key = "command:${command.id}" }
    data class Skill(val skill: RemoteSkill) : ComposerMenuItem { override val key = "skill:${skill.name}" }
}

/** Hoisted suggestions so a composer container can float the menu above its own top edge. */
internal class ComposerMenuState {
    var items by mutableStateOf<List<ComposerMenuItem>>(emptyList())
    var onSelect by mutableStateOf<(ComposerMenuItem) -> Unit>({})
}

/** Skill references keep their raw "$name" text but read as chips: skill accent and medium weight. */
private object SkillChipTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val refs = text.getStringAnnotations(SKILL_EDITOR_TAG, 0, text.length)
        if (refs.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val styled = AnnotatedString.Builder(text).apply {
            refs.forEach { addStyle(SpanStyle(color = CpaTokens.SkillChip, fontWeight = FontWeight.Medium), it.start, it.end) }
        }.toAnnotatedString()
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

@Composable
internal fun RemoteSkillComposer(controller: RemoteSkillsController, text: String, onEdit: (String) -> Unit,
    defaults: RemoteDefaults, enabled: Boolean, label: String, commands: List<ComposerCommand> = emptyList(), onSubmit: (() -> Unit)? = null,
    submitEnabled: Boolean = true, modifier: Modifier = Modifier, maxLines: Int = 6, minHeight: Dp = 0.dp,
    textStyle: TextStyle = cpaText(17, lineHeight = 22.sp), contentPadding: PaddingValues = PaddingValues(0.dp),
    menuState: ComposerMenuState? = null, focusRequester: FocusRequester? = null, onFocusChanged: (Boolean) -> Unit = {}) {
    val state by controller.state.collectAsStateWithLifecycle()
    var field by remember { mutableStateOf(state.draft.copy(text = text).editorValue()) }
    // Preserve IME composition for ordinary typing; programmatic selections use the controller's caret.
    if (field.text != text && text == state.draft.text) field = state.draft.editorValue()
    var editorEpoch by remember { mutableIntStateOf(0) }
    var resumeTyping by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val requester = focusRequester ?: remember { FocusRequester() }
    LaunchedEffect(editorEpoch) { if (resumeTyping) runCatching { requester.requestFocus() } }
    var focused by remember { mutableStateOf(false) }
    val query = state.draft.slashQuery?.lowercase()?.takeIf { focused }
    val skillQuery = if (query != null) query.takeIf { defaults.skillTrigger == '/' } else state.draft.skillQuery(defaults.skillTrigger)?.lowercase()?.takeIf { focused }
    val choices = if (skillQuery == null) emptyList() else state.catalog.enabled(defaults).filter {
        if (defaults.skillTrigger == '/') it.name.startsWith(skillQuery, true) else it.name.contains(skillQuery, true)
    }
    val matchingCommands = if (query == null) emptyList() else commands.filter { command -> query.isEmpty() || command.keywords.any { it.startsWith(query, true) } }
    fun selectCommand(command: ComposerCommand) {
        editorEpoch++; resumeTyping = false; focus.clearFocus()
        field = TextFieldValue(""); controller.edit(""); onEdit(""); command.action()
    }
    fun selectSkill(skill: RemoteSkill) {
        editorEpoch++; resumeTyping = true; focus.clearFocus()
        val selected = controller.select(skill, defaults.skillTrigger)
        field = controller.state.value.draft.editorValue()
        onEdit(selected)
    }
    val items = if (!enabled) emptyList() else matchingCommands.map { ComposerMenuItem.Command(it) } + choices.map { ComposerMenuItem.Skill(it) }
    val select: (ComposerMenuItem) -> Unit = { item -> when (item) {
        is ComposerMenuItem.Command -> selectCommand(item.command)
        is ComposerMenuItem.Skill -> selectSkill(item.skill)
    } }
    if (menuState != null) SideEffect { menuState.items = items; menuState.onSelect = select }
    DisposableEffect(menuState) { onDispose { menuState?.items = emptyList() } }
    fun handleReturn(): Boolean {
        if (!enabled || field.composition != null) return false
        items.firstOrNull()?.let { select(it); return true }
        if (onSubmit != null) {
            if (submitEnabled && field.text.isNotBlank()) onSubmit()
            return true
        }
        return false
    }
    val systemClipboard = androidx.compose.ui.platform.LocalClipboard.current
    val clipboardSelection = field.selection
    val clipboardDraft = state.draft
    val clipboard = object : androidx.compose.ui.platform.Clipboard by systemClipboard {
        override suspend fun setClipEntry(clipEntry: androidx.compose.ui.platform.ClipEntry?) {
            val data = clipEntry?.clipData
            val selected = clipboardSelection
            val raw = clipboardDraft.text.substring(selected.min.coerceIn(0, clipboardDraft.text.length),
                selected.max.coerceIn(0, clipboardDraft.text.length))
            if (!selected.collapsed && data?.itemCount == 1 && data.getItemAt(0).text?.toString() == raw) {
                val copied = clipboardDraft.copySelection(selected, defaults.skillTrigger)
                systemClipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("", copied)))
            } else systemClipboard.setClipEntry(clipEntry)
        }
    }
    val c = Cpa.colors
    var fieldWidth by remember { mutableIntStateOf(0) }
    Box(modifier) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalClipboard provides clipboard) {
            key(editorEpoch) {
                val epoch = editorEpoch
                BasicTextField(field, { value ->
                    // An old IME may commit composition while its editor is being disposed.
                    if (epoch == editorEpoch) {
                        if (isPlainReturnEdit(field, value) && handleReturn()) return@BasicTextField
                        val edited = atomicSkillEdit(field, value, controller.state.value.draft)
                        controller.editFromEditor(edited)
                        field = controller.state.value.draft.editorValue(edited.selection, edited.composition)
                        onEdit(edited.text)
                    }
                }, enabled = enabled, maxLines = maxLines,
                    textStyle = textStyle.copy(color = c.label), cursorBrush = SolidColor(c.accent),
                    visualTransformation = SkillChipTransformation,
                    keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Send else ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = { handleReturn() }),
                    modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).focusRequester(requester)
                        .onGloballyPositioned { fieldWidth = it.size.width }
                        .semantics { contentDescription = label }
                        .onFocusChanged {
                            focused = it.isFocused
                            onFocusChanged(it.isFocused)
                        }.onPreviewKeyEvent { event ->
                            if (event.key != Key.Enter && event.key != Key.NumPadEnter || event.type != KeyEventType.KeyDown) false
                            else if (event.nativeKeyEvent.repeatCount > 0 && onSubmit != null && field.composition == null) true
                            else handleReturn()
                        },
                    decorationBox = { inner ->
                        Box(Modifier.padding(contentPadding)) {
                            if (field.text.isEmpty()) Text(label, style = textStyle, color = c.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    })
            }
        }
        if (menuState == null && items.isNotEmpty()) {
            val width = with(LocalDensity.current) { fieldWidth.toDp() }
            ComposerSuggestionPopup(items, select, Modifier.width(width.coerceAtLeast(240.dp)), gapPx = with(LocalDensity.current) { 10.dp.roundToPx() })
        }
    }
    state.error?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = cpaText(13), color = c.red) }
}


/** Places the popup right above its anchor, `gapPx` away, aligned to the anchor's leading edge. */
internal class AboveAnchor(private val gapPx: Int, private val insetPx: Int = 0) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset((anchorBounds.left - insetPx).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0))
}

@Composable
internal fun ComposerSuggestionPopup(items: List<ComposerMenuItem>, onSelect: (ComposerMenuItem) -> Unit, modifier: Modifier = Modifier,
    gapPx: Int, insetPx: Int = 0) {
    Popup(popupPositionProvider = remember(gapPx, insetPx) { AboveAnchor(gapPx, insetPx) }, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        dev.cpa.android.core.designsystem.CpaWindowContent { ComposerSuggestionMenu(items, onSelect, modifier) }
    }
}

/** Floating card shown above the composer; the first row is the one picked by the return key. */
@Composable
internal fun ComposerSuggestionMenu(items: List<ComposerMenuItem>, onSelect: (ComposerMenuItem) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Column(modifier.padding(4.dp).glass(shape, 14.dp).clip(shape).heightIn(max = 236.dp).verticalScroll(rememberScrollState())
        .padding(7.dp).testTag("composer-suggestions")) {
        items.forEachIndexed { index, item -> key(item.key) { ComposerMenuRow(item, index == 0) { onSelect(item) } } }
    }
}

@Composable
private fun ComposerMenuRow(item: ComposerMenuItem, active: Boolean, onClick: () -> Unit) {
    val c = Cpa.colors
    val icon: ImageVector
    val title: String
    val subtitle: String
    val kind: String
    when (item) {
        is ComposerMenuItem.Command -> {
            val command = item.command
            icon = when (command.id) { "fast" -> CpaIcons.Bolt; "compact" -> CpaIcons.Shrink; else -> CpaIcons.Cpu }
            title = stringResource(command.label)
            subtitle = when (command.id) {
                "fast" -> stringResource(if (command.on) R.string.ui_composer_fast_subtitle_on else R.string.ui_composer_fast_subtitle_off)
                "compact" -> stringResource(R.string.ui_composer_compact_subtitle)
                else -> command.current?.let { stringResource(R.string.ui_composer_model_subtitle_current, it) } ?: stringResource(R.string.ui_composer_model_subtitle_default)
            }
            kind = stringResource(R.string.ui_composer_command_kind)
        }
        is ComposerMenuItem.Skill -> {
            icon = CpaIcons.Sparkles
            title = skillDisplayName(item.skill.name)
            subtitle = item.skill.description.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
            kind = stringResource(R.string.ui_composer_skill_kind)
        }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (active) c.label.copy(alpha = .06f) else androidx.compose.ui.graphics.Color.Transparent)
        .pressable(pressedAlpha = .6f, onClick = onClick).padding(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp, 22.dp), contentAlignment = Alignment.Center) { CpaIcon(icon, null, size = 19.dp, tint = c.secondaryLabel) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = cpaText(17), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = cpaText(15), color = c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(kind, Modifier.padding(top = 2.dp), style = cpaText(13), color = c.secondaryLabel)
    }
}

/** Only a standalone Return edit, never an IME composition commit or a multiline paste. */
internal fun isPlainReturnEdit(before: TextFieldValue, after: TextFieldValue): Boolean =
    before.composition == null && after.composition == null &&
        after.text == before.text.replaceRange(before.selection.min, before.selection.max, "\n") &&
        after.selection.collapsed && after.selection.start == before.selection.min + 1
