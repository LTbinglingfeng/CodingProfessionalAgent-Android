package dev.cpa.android.feature.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.data.repository.RemoteModel
import dev.cpa.android.data.repository.RemoteReasoningOption
import kotlin.math.roundToInt

/** Trailing composer button: send with content, stop while running, resume when paused, else an inactive arrow. */
@Composable
internal fun ComposerActionButton(hasContent: Boolean, canSend: Boolean, running: Boolean, canResume: Boolean,
    onSubmit: () -> Unit, onStop: () -> Unit = {}, onResume: () -> Unit = {}, sending: Boolean = false) {
    when {
        sending -> Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Spinner(size = 20.dp) }
        hasContent -> AccentCircleButton(CpaIcons.ArrowUp, stringResource(R.string.ui_composer_send), onSubmit, enabled = canSend, dimmed = !canSend,
            modifier = Modifier.testTag("composer-send"))
        running -> AccentCircleButton(CpaIcons.StopFill, stringResource(R.string.ui_composer_stop), onStop, iconSize = 18.dp, modifier = Modifier.testTag("composer-stop"))
        canResume -> AccentCircleButton(CpaIcons.PlayFill, stringResource(R.string.ui_composer_resume), onResume, iconSize = 15.dp, modifier = Modifier.testTag("composer-resume"))
        else -> AccentCircleButton(CpaIcons.ArrowUp, stringResource(R.string.ui_composer_send), {}, enabled = false, modifier = Modifier.testTag("composer-send"))
    }
}

/** Glass capsule composer of the home screen; return sends. */
@Composable
internal fun ComposerBar(field: @Composable (ComposerMenuState) -> Unit, hasText: Boolean, canSend: Boolean, onSubmit: () -> Unit,
    onAttach: () -> Unit, modifier: Modifier = Modifier) {
    val menu = remember { ComposerMenuState() }
    val density = LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), 10.dp).padding(2.dp).onGloballyPositioned { width = it.size.width },
            verticalAlignment = Alignment.Bottom) {
            ComposerIconButton(CpaIcons.Plus, stringResource(R.string.ui_composer_add_attachment), onAttach)
            Box(Modifier.weight(1f).padding(top = 11.dp, bottom = 11.dp, start = 6.dp)) { field(menu) }
            ComposerActionButton(hasText, canSend, running = false, canResume = false, onSubmit = onSubmit)
        }
        if (menu.items.isNotEmpty()) ComposerSuggestionPopup(menu.items, menu.onSelect,
            Modifier.width(with(density) { width.toDp() }), gapPx = with(density) { 10.dp.roundToPx() })
    }
}

/** Multi-line composer of the new thread and thread screens; return inserts a newline. */
@Composable
internal fun ExpandedComposer(field: @Composable (ComposerMenuState) -> Unit, attachments: (@Composable () -> Unit)?,
    hasContent: Boolean, canSend: Boolean, running: Boolean, canResume: Boolean, sending: Boolean,
    modelName: String, reasoningProgress: Double, onReasoning: () -> Unit, onAttach: () -> Unit, attachEnabled: Boolean,
    onSubmit: () -> Unit, onStop: () -> Unit = {}, onResume: () -> Unit = {}, modifier: Modifier = Modifier) {
    val menu = remember { ComposerMenuState() }
    val density = LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp), 12.dp).onGloballyPositioned { width = it.size.width }.animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (attachments != null) Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)) { attachments() }
            Box(Modifier.padding(start = 18.dp, end = 18.dp, top = if (attachments != null) 0.dp else 16.dp)) { field(menu) }
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ComposerIconButton(CpaIcons.Plus, stringResource(R.string.ui_composer_add_attachment), onAttach, enabled = attachEnabled,
                    modifier = Modifier.testTag("composer-attach"))
                Spacer(Modifier.weight(1f))
                Row(Modifier.pressable(label = stringResource(R.string.ui_advanced_reasoning), onClick = onReasoning).testTag("composer-reasoning"),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (modelName.isNotEmpty()) Text(modelName, style = cpaText(15), color = Cpa.colors.secondaryLabel, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp).padding(end = 2.dp))
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { ReasoningGauge(reasoningProgress) }
                }
                ComposerActionButton(hasContent, canSend, running, canResume, onSubmit, onStop, onResume, sending)
            }
        }
        if (menu.items.isNotEmpty()) ComposerSuggestionPopup(menu.items, menu.onSelect,
            Modifier.width(with(density) { width.toDp() }), gapPx = with(density) { 10.dp.roundToPx() })
    }
}

/** Speedometer-like icon: coral arc and needle point at the selected level on a 270° dial open at the bottom. */
@Composable
internal fun ReasoningGauge(progress: Double, modifier: Modifier = Modifier) {
    val c = Cpa.colors
    val clamped by animateFloatAsState(progress.toFloat().coerceIn(0f, 1f), spring(stiffness = 500f), label = "gauge")
    Canvas(modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(c.gray3, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (clamped > 0f) drawArc(c.accent, 135f, 270f * clamped, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(c.label, 2.5.dp.toPx(), center, style = Stroke(stroke))
        rotate(135f + 270f * clamped, center) {
            drawLine(c.label, Offset(center.x + 2.5.dp.toPx(), center.y), Offset(center.x + 6.8.dp.toPx(), center.y), stroke, StrokeCap.Round)
        }
    }
}

/** Discrete capsule slider that selects one of the model's reasoning levels. */
@Composable
internal fun ReasoningSlider(levels: List<RemoteReasoningOption>, selection: String?, onSelect: (String) -> Unit) {
    val c = Cpa.colors
    val haptic = LocalHapticFeedback.current
    val width = 340.dp; val height = 70.dp; val inset = 11.dp; val knob = 40.dp; val dot = 11.dp
    val inner = height - inset * 2
    val count = levels.size
    val selected = levels.indexOfFirst { it.id == selection }.coerceAtLeast(0)
    val animated by animateFloatAsState(selected.toFloat(), spring(dampingRatio = .8f, stiffness = 700f), label = "knob")
    val density = LocalDensity.current
    val stepPx = with(density) { if (count > 1) ((width - inset * 2 - inner) / (count - 1)).toPx() else 0f }
    val startPx = with(density) { (inset + inner / 2).toPx() }
    fun pick(x: Float) {
        if (count == 0 || stepPx <= 0f) return
        val index = ((x - startPx) / stepPx).roundToInt().coerceIn(0, count - 1)
        if (levels[index].id != selection) { onSelect(levels[index].id); haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }
    Box(Modifier.size(width, height).glass(CircleShape, 10.dp).clip(CircleShape)
        .pointerInput(levels, selection) { detectTapGestures { pick(it.x) } }
        .pointerInput(levels, selection) { detectDragGestures(onDragStart = { pick(it.x) }) { change, _ -> pick(change.position.x) } }
        .testTag("reasoning-slider")) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2
            val insetPx = inset.toPx(); val innerPx = inner.toPx()
            val current = startPx + animated * stepPx
            val fill = current + innerPx / 2 - insetPx
            drawRoundRect(c.accent, Offset(insetPx, insetPx), Size(fill, innerPx), androidx.compose.ui.geometry.CornerRadius(innerPx / 2))
            for (i in 0 until count) {
                if (i == selected) continue
                drawCircle(if (i < selected) Color.White.copy(alpha = .35f) else c.gray4, dot.toPx() / 2, Offset(startPx + i * stepPx, cy))
            }
        }
        val knobX = with(density) { (startPx + animated * stepPx).toDp() } - knob / 2
        Box(Modifier.offset(x = knobX, y = (height - knob) / 2).size(knob).shadow(3.dp, CircleShape).background(Color.White, CircleShape))
    }
}

/** Blurred-looking overlay hosting the reasoning slider above the composer; tapping outside dismisses. */
@Composable
internal fun ReasoningOverlay(modelTitle: String, reasoningTitle: String, levels: List<RemoteReasoningOption>, selection: String?,
    onSelect: (String) -> Unit, canChangeModel: Boolean = true, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val c = Cpa.colors
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Transparent,
            .72f to c.background.copy(alpha = .97f), 1f to c.background))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                val modelText = @Composable { Text(modelTitle, style = cpaText(22, FontWeight.Medium), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val reasoningText = @Composable {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(reasoningTitle, style = cpaText(22), color = c.secondaryLabel, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        CpaIcon(CpaIcons.ChevronRight, null, size = 16.dp, tint = c.secondaryLabel)
                    }
                }
                if (canChangeModel) Row(Modifier.pressable(onClick = onOpenSettings), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f, fill = false)) { modelText() }; Spacer(Modifier.width(8.dp)); reasoningText()
                } else {
                    Box(Modifier.weight(1f, fill = false).clickable(remember { MutableInteractionSource() }, null) {}) { modelText() }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.pressable(onClick = onOpenSettings)) { reasoningText() }
                }
            }
            ReasoningSlider(levels, selection, onSelect)
        }
    }
}

/** Half-height sheet for choosing model, reasoning level and speed. */
@Composable
internal fun AdvancedSettingsSheet(models: List<RemoteModel>, modelId: String?, onModel: (String) -> Unit,
    reasoningChoices: List<RemoteReasoningOption>, reasoning: String?, onReasoning: (String) -> Unit,
    supportsFast: Boolean, speed: String, onSpeed: (String) -> Unit, runActive: Boolean = false, enabled: Boolean = true, onDismiss: () -> Unit) {
    val c = Cpa.colors
    val defaultLabel = stringResource(R.string.ui_common_default)
    CpaSheet(onDismiss, fullHeight = false, dragHandle = true) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp)) {
            Text(stringResource(R.string.ui_advanced_title), Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp),
                style = cpaText(17, FontWeight.SemiBold), color = c.label, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(CpaTokens.GroupShape).background(c.groupedRow)) {
                FormMenuRow(stringResource(R.string.ui_advanced_model), models.find { it.id == modelId }?.label ?: defaultLabel,
                    models.map { MenuOption(it.id, it.label) }, modelId, onModel, enabled = enabled && models.isNotEmpty() && !runActive,
                    modifier = Modifier.testTag("advanced-model"))
                FormDivider()
                FormMenuRow(stringResource(R.string.ui_advanced_reasoning),
                    reasoningChoices.find { it.id == reasoning }?.let { reasoningLabel(it.id, it.label) } ?: defaultLabel,
                    reasoningChoices.map { MenuOption(it.id, reasoningLabel(it.id, it.label)) }, reasoning, onReasoning,
                    enabled = enabled && reasoningChoices.isNotEmpty(), valueColor = c.label, modifier = Modifier.testTag("advanced-reasoning"))
            }
            if (runActive) Text(stringResource(R.string.ui_advanced_active_run_hint), Modifier.padding(horizontal = 40.dp, vertical = 8.dp),
                style = cpaText(13), color = c.secondaryLabel)
            if (supportsFast) Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 34.dp).fillMaxWidth().clip(CpaTokens.GroupShape).background(c.groupedRow)) {
                FormMenuRow(stringResource(R.string.ui_advanced_speed), speedLabel(speed),
                    listOf(MenuOption("standard", speedLabel("standard")), MenuOption("fast", speedLabel("fast"))), speed, onSpeed,
                    enabled = enabled && !runActive, valueColor = c.label, modifier = Modifier.testTag("advanced-speed"))
            }
        }
    }
}

/** Progress of the selected reasoning level among the model's levels (lowest 0, highest 1). */
internal fun reasoningProgress(choices: List<RemoteReasoningOption>, selected: String?): Double {
    val index = choices.indexOfFirst { it.id == selected }
    return if (choices.size > 1 && index >= 0) index.toDouble() / (choices.size - 1) else 0.0
}

/** Card over the composer's bottom-leading corner (camera, photos, files); tapping outside closes it. */
@Composable
internal fun AttachmentMenuOverlay(visible: Boolean, pickers: AttachmentPickers, onDismiss: () -> Unit) {
    val c = Cpa.colors
    if (visible) androidx.activity.compose.BackHandler(onBack = onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
            Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().imePadding().padding(start = 15.dp, bottom = 15.dp).width(300.dp)
                .glass(RoundedCornerShape(34.dp), 18.dp).clip(RoundedCornerShape(34.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(horizontal = 24.dp, vertical = 13.dp).testTag("attachment-pickers")) {
                listOf(Triple(CpaIcons.Camera, R.string.ui_attachments_camera, pickers.camera),
                    Triple(CpaIcons.Photo, R.string.ui_attachments_photos, pickers.photos),
                    Triple(CpaIcons.Paperclip, R.string.ui_attachments_files, pickers.files)).forEach { (icon, title, action) ->
                    Row(Modifier.fillMaxWidth().pressable(enabled = pickers.enabled) { onDismiss(); action() }.padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).background(c.gray6, CircleShape), contentAlignment = Alignment.Center) {
                            CpaIcon(icon, null, size = 21.dp, tint = c.label)
                        }
                        Spacer(Modifier.width(17.dp))
                        Text(stringResource(title), style = cpaText(20), color = if (pickers.enabled) c.label else c.tertiaryLabel)
                    }
                }
            }
        }
    }
}

/** Requests focus once when the screen first appears (iOS focuses the prompt on the new thread screen). */
@Composable
internal fun AutoFocus(requester: FocusRequester, enabled: Boolean = true) {
    LaunchedEffect(requester, enabled) { if (enabled) { kotlinx.coroutines.delay(350); runCatching { requester.requestFocus() } } }
}
