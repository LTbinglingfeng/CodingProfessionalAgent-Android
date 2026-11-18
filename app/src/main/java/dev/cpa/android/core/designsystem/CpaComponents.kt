package dev.cpa.android.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.res.stringResource
import dev.cpa.android.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Press feedback and glass surfaces

/** iOS-style press feedback: content dims while pressed, no ripple. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onLongClick: (() -> Unit)? = null,
    label: String? = null,
    pressedAlpha: Float = .45f,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) pressedAlpha else 1f, tween(if (pressed) 40 else 220), label = "press")
    this.combinedClickable(source, null, enabled = enabled, role = role, onClickLabel = label,
        onLongClick = onLongClick, onClick = onClick).alpha(alpha)
}

/** Translucent "liquid glass" approximation: frosted fill, hairline rim and a soft shadow. */
fun Modifier.glass(shape: Shape, elevation: Dp = 10.dp): Modifier = composed {
    val c = Cpa.colors
    this.shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = if (c.dark) .5f else .10f),
        spotColor = Color.Black.copy(alpha = if (c.dark) .5f else .12f))
        .background(c.glass, shape)
        .border(0.5.dp, c.glassBorder, shape)
}

@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, iconSize: Dp = 22.dp, tint: Color = Cpa.colors.label) {
    Box(modifier.size(48.dp).glass(CircleShape, 6.dp).clip(CircleShape)
        .pressable(enabled, onClick = onClick, label = contentDescription)
        .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center) {
        CpaIcon(icon, null, size = iconSize, tint = if (enabled) tint else Cpa.colors.tertiaryLabel)
    }
}

/** Two toolbar buttons sharing one glass capsule (e.g. new chat + more). */
@Composable
fun GlassButtonPair(first: @Composable () -> Unit, second: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.height(48.dp).glass(RoundedCornerShape(24.dp), 6.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        first(); second()
    }
}

@Composable
fun BarIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean = true, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(width = 50.dp, height = 44.dp).clip(CircleShape).pressable(enabled, label = contentDescription, onClick = onClick)
        .semantics { this.contentDescription = contentDescription }, contentAlignment = Alignment.Center) {
        CpaIcon(icon, null, size = size, tint = if (enabled) Cpa.colors.label else Cpa.colors.tertiaryLabel)
    }
}

/** Capsule text button used in sheet toolbars ("Cancel", "Save"). */
@Composable
fun GlassTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, emphasized: Boolean = false) {
    val c = Cpa.colors
    Box(modifier.height(48.dp).widthIn(min = 64.dp).glass(RoundedCornerShape(24.dp), 6.dp).clip(RoundedCornerShape(24.dp))
        .pressable(enabled, onClick = onClick).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, style = cpaText(17, if (emphasized) FontWeight.SemiBold else FontWeight.Normal),
            color = if (enabled) c.label else c.tertiaryLabel, maxLines = 1)
    }
}

/** Coral circular button used for send, stop and resume. */
@Composable
fun AccentCircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, dimmed: Boolean = false, iconSize: Dp = 16.dp) {
    Box(modifier.size(44.dp).pressable(enabled, label = contentDescription, onClick = onClick)
        .semantics { this.contentDescription = contentDescription }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).alpha(if (dimmed) .5f else 1f).background(Cpa.colors.accent, CircleShape), contentAlignment = Alignment.Center) {
            CpaIcon(icon, null, size = iconSize, tint = Color.White)
        }
    }
}

@Composable
fun ComposerIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.size(44.dp).pressable(enabled, label = contentDescription, onClick = onClick)
        .semantics { this.contentDescription = contentDescription }, contentAlignment = Alignment.Center) {
        CpaIcon(icon, null, size = 22.dp, tint = if (enabled) Cpa.colors.label else Cpa.colors.tertiaryLabel)
    }
}

// ---------------------------------------------------------------------------------------------
// Controls

/** iOS-style toggle (51×31) tinted green. */
@Composable
fun CpaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: String? = null) {
    val c = Cpa.colors
    val progress by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = .75f, stiffness = 600f), label = "toggle")
    val track = lerpColor(if (c.dark) c.gray4 else c.gray5, c.green, progress)
    Box(modifier.size(width = 54.dp, height = 32.dp).alpha(if (enabled) 1f else .45f).clip(RoundedCornerShape(16.dp))
        .background(track).toggleable(checked, remember { MutableInteractionSource() }, null, enabled, Role.Switch, onCheckedChange)
        .semantics { if (label != null) contentDescription = label }) {
        Box(Modifier.padding(2.dp).offset(x = 22.dp * progress).size(28.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t)

/** iOS activity indicator: eight fading spokes. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = Cpa.colors.secondaryLabel) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val step by transition.animateValue(0, 8, Int.VectorConverter, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "step")
    val loading = stringResource(R.string.accessibility_loading)
    Canvas(modifier.size(size).semantics { contentDescription = loading }) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val outer = this.size.minDimension / 2
        val inner = outer * .5f
        val width = outer * .24f
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45.0) - 90)
            val alpha = ((i - step + 8) % 8 + 1) / 8f
            val dir = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
            drawLine(color.copy(alpha = color.alpha * (0.15f + .85f * alpha)), center + dir * inner, center + dir * (outer - width / 2), width, StrokeCap.Round)
        }
    }
}

/** Text whose highlight sweeps across while work is in progress (desktop text shimmer). */
@Composable
fun ShimmerText(text: String, style: TextStyle, active: Boolean, modifier: Modifier = Modifier, color: Color = Cpa.colors.secondaryLabel,
    maxLines: Int = 1) {
    if (!active) { Text(text, modifier, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis); return }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "sweep")
    var width by remember { mutableFloatStateOf(300f) }
    val center = (progress * 2 - .5f) * width
    val brush = Brush.linearGradient(listOf(color, Cpa.colors.label, color), start = Offset(center - width * .35f, 0f), end = Offset(center + width * .35f, 0f))
    Text(text, modifier.onSizeChangedPx { width = it.toFloat().coerceAtLeast(1f) }, style = style.copy(brush = brush), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private fun Modifier.onSizeChangedPx(block: (Int) -> Unit) = this.onSizeChanged { block(it.width) }

/** Two-tab segmented control like the iOS panel tabs. */
@Composable
fun SegmentedTabs(left: String, right: String, rightSelected: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Cpa.colors
    Row(modifier.selectableGroup().fillMaxWidth().background(c.gray6, RoundedCornerShape(17.dp)).padding(2.dp)) {
        listOf(false to left, true to right).forEach { (isRight, title) ->
            val selected = isRight == rightSelected
            Box(Modifier.weight(1f).height(34.dp).then(if (selected) Modifier.shadow(2.dp, CircleShape).background(c.background, CircleShape) else Modifier)
                .clip(CircleShape).selectable(selected = selected, role = Role.Tab) { onSelect(isRight) }, contentAlignment = Alignment.Center) {
                Text(title, style = cpaText(15, if (selected) FontWeight.SemiBold else FontWeight.Normal), color = c.label)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Inset grouped forms

@Composable
fun FormSectionHeader(title: String, modifier: Modifier = Modifier, prominent: Boolean = false, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() },
            style = if (prominent) cpaText(17, FontWeight.SemiBold) else cpaText(15, FontWeight.Medium),
            color = if (prominent) Cpa.colors.label else Cpa.colors.secondaryLabel)
        trailing?.invoke(this)
    }
}

@Composable
fun FormGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CpaTokens.GroupShape).background(Cpa.colors.groupedCard), content = content)
}

@Composable
fun FormFooter(text: String, modifier: Modifier = Modifier, color: Color = Cpa.colors.secondaryLabel) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), style = cpaText(13), color = color)
}

@Composable
fun FormDivider(start: Dp = 20.dp) {
    Box(Modifier.padding(start = start, end = 20.dp).fillMaxWidth().height(0.5.dp).background(Cpa.colors.separator))
}

@Composable
fun FormRow(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, enabled: Boolean = true, minHeight: Dp = 52.dp,
    content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = minHeight)
        .then(if (onClick != null) Modifier.pressable(enabled, pressedAlpha = .6f, onClick = onClick) else Modifier)
        .padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Read-only "Title ……… value" row (iOS LabeledContent): the title keeps its width, the value takes the rest and truncates. */
@Composable
fun FormValueRow(title: String, value: String, overflow: TextOverflow = TextOverflow.Ellipsis, modifier: Modifier = Modifier) {
    FormRow(modifier.semantics(mergeDescendants = true) {}) {
        Text(title, style = cpaText(17), color = Cpa.colors.label, maxLines = 1)
        Spacer(Modifier.width(16.dp))
        Text(value, Modifier.weight(1f), style = cpaText(17), color = Cpa.colors.secondaryLabel, maxLines = 1, overflow = overflow, textAlign = TextAlign.End)
    }
}

@Composable
fun FormToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    FormRow(modifier, onClick = { onCheckedChange(!checked) }, enabled = enabled) {
        Text(title, Modifier.weight(1f), style = cpaText(17), color = Cpa.colors.label)
        Spacer(Modifier.width(12.dp))
        CpaSwitch(checked, onCheckedChange, enabled = enabled, label = title)
    }
}

data class MenuOption<T>(val value: T, val title: String)

/** "Title ……… value ⌃⌄" row that opens a check-marked menu. */
@Composable
fun <T> FormMenuRow(title: String, value: String, options: List<MenuOption<T>>, selected: T?, onSelect: (T) -> Unit,
    enabled: Boolean = true, valueColor: Color = Cpa.colors.secondaryLabel, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FormRow(onClick = { open = true }, enabled = enabled) {
            Text(title, style = cpaText(17), color = Cpa.colors.label, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text(value, style = cpaText(17), color = if (enabled) valueColor else Cpa.colors.tertiaryLabel, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            }
            Spacer(Modifier.width(6.dp))
            CpaIcon(CpaIcons.ChevronUpDown, null, size = 15.dp, tint = Cpa.colors.secondaryLabel)
        }
        CpaMenu(open, { open = false }, Modifier.align(Alignment.CenterEnd), offset = DpOffset((-16).dp, 0.dp)) {
            options.forEach { option ->
                CpaMenuItem(option.title, checked = option.value == selected) { open = false; onSelect(option.value) }
            }
        }
    }
}

/** Label on the leading edge, trailing-aligned plain text field (iOS LabeledContent + TextField). */
@Composable
fun FormFieldRow(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text, secret: Boolean = false, enabled: Boolean = true, fieldModifier: Modifier = Modifier) {
    val c = Cpa.colors
    FormRow(modifier) {
        Text(label, style = cpaText(17), color = c.label)
        Spacer(Modifier.width(16.dp))
        BasicTextField(value, onValueChange, fieldModifier.weight(1f).semantics { contentDescription = label }, enabled = enabled, singleLine = true,
            textStyle = cpaText(17).copy(color = c.label, textAlign = TextAlign.End),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = !secret && keyboardType == KeyboardType.Text),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(placeholder, style = cpaText(17), color = c.tertiaryLabel, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    inner()
                }
            })
    }
}

/** Full-width editable field row (no label), e.g. project name. */
@Composable
fun FormTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, singleLine: Boolean = true, keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Default, onDone: (() -> Unit)? = null,
    style: TextStyle = cpaText(17)) {
    val c = Cpa.colors
    BasicTextField(value, onValueChange, modifier.fillMaxWidth().semantics { contentDescription = placeholder }, enabled = enabled, singleLine = singleLine,
        textStyle = style.copy(color = c.label), cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = keyboardType == KeyboardType.Text),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }, onSearch = { onDone?.invoke() }),
        decorationBox = { inner ->
            Box(Modifier.heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style, color = c.tertiaryLabel, maxLines = 1)
                inner()
            }
        })
}

/** Centered action row inside a form group ("Delete", "Remove project"). */
@Composable
fun FormButtonRow(title: String, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true, centered: Boolean = true,
    icon: ImageVector? = null) {
    val c = Cpa.colors
    FormRow(onClick = onClick, enabled = enabled) {
        if (icon != null) { CpaIcon(icon, null, size = 24.dp, tint = if (destructive) c.red else c.blue); Spacer(Modifier.width(14.dp)) }
        Text(title, Modifier.weight(1f), style = cpaText(17), textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            color = if (!enabled) c.tertiaryLabel else if (destructive) c.red else c.blue)
    }
}

// ---------------------------------------------------------------------------------------------
// Menus

@Composable
fun CpaMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit) {
    val c = Cpa.colors
    MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(26.dp))) {
        DropdownMenu(expanded, onDismiss, modifier.widthIn(min = 230.dp), offset = offset,
            shape = RoundedCornerShape(26.dp), containerColor = c.menu, tonalElevation = 0.dp, shadowElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, c.glassBorder)) { val scope = this; CpaWindowContent { scope.content() } }
    }
}

@Composable
fun CpaMenuItem(text: String, icon: ImageVector? = null, destructive: Boolean = false, checked: Boolean = false, enabled: Boolean = true,
    trailing: ImageVector? = null, onClick: () -> Unit) {
    val c = Cpa.colors
    val color = if (!enabled) c.tertiaryLabel else if (destructive) c.red else c.label
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(enabled, pressedAlpha = .5f, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (checked) CpaIcon(CpaIcons.Check, null, size = 16.dp, tint = color, modifier = Modifier.padding(end = 10.dp))
        else if (icon != null) CpaIcon(icon, null, size = 21.dp, tint = color, modifier = Modifier.padding(end = 14.dp))
        Text(text, Modifier.weight(1f), style = cpaText(17), color = color)
        if (trailing != null) CpaIcon(trailing, null, size = 16.dp, tint = c.secondaryLabel)
    }
}

@Composable
fun CpaMenuDivider() {
    Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().height(0.5.dp).background(Cpa.colors.separator))
}

// ---------------------------------------------------------------------------------------------
// Dialogs

data class DialogAction(val title: String, val destructive: Boolean = false, val cancel: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/** Centered alert like UIAlertController: title, message, optional field, horizontal/vertical buttons. */
@Composable
fun CpaAlert(title: String, message: String? = null, actions: List<DialogAction>, onDismiss: () -> Unit,
    field: (@Composable () -> Unit)? = null) {
    val c = Cpa.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { CpaWindowContent {
        LightDim()
        Column(Modifier.padding(horizontal = 44.dp).widthIn(max = 340.dp).fillMaxWidth().clip(RoundedCornerShape(30.dp))
            .background(c.groupedCard).verticalScroll(rememberScrollState()).padding(top = 22.dp, bottom = 14.dp, start = 18.dp, end = 18.dp)
            .semantics { paneTitle = title }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = cpaText(17, FontWeight.SemiBold), color = c.label, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            if (!message.isNullOrEmpty()) Text(message, Modifier.padding(top = 6.dp), style = cpaText(14), color = c.secondaryLabel, textAlign = TextAlign.Center)
            if (field != null) Box(Modifier.padding(top = 14.dp).fillMaxWidth()) { field() }
            Spacer(Modifier.height(18.dp))
            if (actions.size == 2) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEach { AlertButton(it, Modifier.weight(1f)) }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions.forEach { AlertButton(it, Modifier.fillMaxWidth()) } }
        }
    } }
}

@Composable
private fun AlertButton(action: DialogAction, modifier: Modifier) {
    val c = Cpa.colors
    Box(modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.fill.copy(alpha = c.fill.alpha * .8f))
        .pressable(action.enabled, onClick = action.onClick), contentAlignment = Alignment.Center) {
        Text(action.title, style = cpaText(17, if (action.cancel) FontWeight.Normal else FontWeight.Medium),
            color = if (!action.enabled) c.tertiaryLabel else if (action.destructive) c.red else c.label)
    }
}

/** Plain text field used inside alerts. */
@Composable
fun AlertTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null) {
    val c = Cpa.colors
    BasicTextField(value, onValueChange, Modifier.fillMaxWidth()
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .semantics { contentDescription = placeholder }, singleLine = true,
        textStyle = cpaText(16).copy(color = c.label), cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { inner ->
            Box(Modifier.background(c.secondaryBackground, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (value.isEmpty()) Text(placeholder, style = cpaText(16), color = c.tertiaryLabel)
                inner()
            }
        })
}


/** Bottom action sheet like confirmationDialog: title and full-width choices. */
@Composable
fun CpaActionSheet(title: String, actions: List<DialogAction>, onDismiss: () -> Unit) {
    val c = Cpa.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { CpaWindowContent {
        LightDim()
        Column(Modifier.padding(horizontal = 20.dp).widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(34.dp))
            .background(c.groupedCard).verticalScroll(rememberScrollState()).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = cpaText(15, FontWeight.Medium), color = c.secondaryLabel, modifier = Modifier.padding(bottom = 4.dp))
            actions.forEach { AlertButton(it, Modifier.fillMaxWidth()) }
        }
    } }
}

/** iOS alerts dim the screen far less than the Android default (0.6). */
@Composable
private fun LightDim() {
    val view = androidx.compose.ui.platform.LocalView.current
    val dark = Cpa.colors.dark
    SideEffect { (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(if (dark) .45f else .22f) }
}

// ---------------------------------------------------------------------------------------------
// Sheets

/** iOS-like sheet: rounded top, grouped or plain background, optional grabber. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpaSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, grouped: Boolean = false, fullHeight: Boolean = true,
    dragHandle: Boolean = false, dismissible: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = Cpa.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { dismissible || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (dismissible) onDismiss() }, sheetState = state,
        modifier = modifier.then(if (fullHeight) Modifier.statusBarsPadding().padding(top = 8.dp) else Modifier),
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        containerColor = if (grouped) c.groupedBackground else c.background, contentColor = c.label,
        scrimColor = c.scrim, tonalElevation = 0.dp,
        dragHandle = if (dragHandle) ({ Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(36.dp, 5.dp).background(c.gray3, CircleShape)) }) else null,
        contentWindowInsets = { WindowInsets(0) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = dismissible)) {
        CpaWindowContent { Column(if (fullHeight) Modifier.fillMaxHeight() else Modifier) { content() } }
    }
}

/** Sheet toolbar: optional leading/trailing glass controls around a centered title. */
@Composable
fun SheetTopBar(title: String, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading?.invoke() }
        Text(title, Modifier.align(Alignment.Center).padding(horizontal = 96.dp).semantics { heading() }, style = cpaText(17, FontWeight.SemiBold),
            color = Cpa.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.align(Alignment.CenterEnd)) { trailing?.invoke() }
    }
}

// ---------------------------------------------------------------------------------------------
// Feedback

class NoticeState { var text by mutableStateOf<String?>(null); var serial by mutableIntStateOf(0)
    fun show(value: String) { text = value; serial++ } }

@Composable fun rememberNotice() = remember { NoticeState() }

/** Short capsule confirmation at the top of the screen ("Copied to clipboard"). */
@Composable
fun TransientNotice(state: NoticeState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.serial) { if (state.text != null) { delay(2000); state.text = null } }
    var last by remember { mutableStateOf("") }
    state.text?.let { last = it }
    AnimatedVisibility(state.text != null, modifier, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        Box(Modifier.padding(top = 8.dp).height(40.dp).glass(CircleShape, 8.dp).padding(horizontal = 18.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
            Text(last, style = cpaText(15, FontWeight.Medium), color = Cpa.colors.label)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, actionTitle: String? = null, onAction: (() -> Unit)? = null) {
    val c = Cpa.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CpaIcon(icon, null, size = 44.dp, tint = c.secondaryLabel)
        Text(title, style = cpaText(20, FontWeight.SemiBold), color = c.label, textAlign = TextAlign.Center)
        Text(message, style = cpaText(15), color = c.secondaryLabel, textAlign = TextAlign.Center)
        if (actionTitle != null && onAction != null) Box(Modifier.padding(top = 6.dp).height(46.dp).shadow(6.dp, CircleShape)
            .background(c.accent, CircleShape).clip(CircleShape).pressable(onClick = onAction).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(actionTitle, style = cpaText(17, FontWeight.SemiBold), color = Color.White)
        }
    }
}

/** Capsule glass search field. */
@Composable
fun GlassSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null) {
    val c = Cpa.colors
    Row(modifier.height(48.dp).glass(CircleShape, 4.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CpaIcon(CpaIcons.Search, null, size = 19.dp, tint = c.label)
        Spacer(Modifier.width(10.dp))
        BasicTextField(value, onValueChange, Modifier.weight(1f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics { contentDescription = placeholder }, singleLine = true,
            textStyle = cpaText(17).copy(color = c.label), cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = cpaText(17), color = c.tertiaryLabel, maxLines = 1); inner() } })
        if (value.isNotEmpty()) Box(Modifier.size(28.dp).pressable(label = stringResource(R.string.accessibility_clear)) { onValueChange("") }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(18.dp).background(c.gray2, CircleShape), contentAlignment = Alignment.Center) {
                CpaIcon(CpaIcons.Close, null, size = 10.dp, tint = c.background)
            }
        }
    }
}

/** Small rotating chevron used for disclosure rows. */
@Composable
fun Disclosure(expanded: Boolean, size: Dp = 12.dp, tint: Color = Cpa.colors.secondaryLabel) {
    val angle by animateFloatAsState(if (expanded) 90f else 0f, label = "disclosure")
    CpaIcon(CpaIcons.ChevronRight, null, Modifier.rotate(angle), size = size, tint = tint)
}
