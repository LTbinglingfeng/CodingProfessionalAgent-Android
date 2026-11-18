package dev.cpa.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Source P3 values are not sRGB hex. System colors below are neutral approximations. */
object CpaTokens {
    val Accent = Color(.933f, .525f, .427f, 1f, ColorSpaces.DisplayP3)
    val Online = Color(.353f, .745f, .510f, 1f, ColorSpaces.DisplayP3)
    val BubbleLight = Color(.988f, .945f, .933f, 1f, ColorSpaces.DisplayP3)
    val BubbleDark = Color(.235f, .165f, .145f, 1f, ColorSpaces.DisplayP3)
    val BubbleTextLight = Color(.776f, .220f, .157f, 1f, ColorSpaces.DisplayP3)
    val BubbleTextDark = Color(.961f, .686f, .608f, 1f, ColorSpaces.DisplayP3)
    val SkillChip = Color(0xFF6EA8FE)
    val PageInset = 16.dp
    val TurnGap = 24.dp
    val BubbleInsetX = 15.dp
    val BubbleInsetY = 11.dp
    val BubbleLeading = 60.dp
    val BubbleShape = RoundedCornerShape(22.dp)
    val CardShape = RoundedCornerShape(14.dp)
    val CodeShape = RoundedCornerShape(12.dp)
    val ComposerShape = RoundedCornerShape(30.dp)
    val ActionShape = RoundedCornerShape(14.dp)
    val GroupShape = RoundedCornerShape(26.dp)
    val BodySize = 17.sp
    // Android semantic approximations, NOT exact UIKit system green/red.
    val DiffGreen = Color(0xFF248A3D)
    val DiffRed = Color(0xFFD02030)
    val DiffGreenDark = Color(0xFF78D890)
    val DiffRedDark = Color(0xFFFF9098)
}

/** iOS-like semantic palette used by every CPA screen (dynamic system colors approximated in sRGB). */
@Immutable
data class CpaColors(
    val dark: Boolean,
    val background: Color,
    val secondaryBackground: Color,
    val groupedBackground: Color,
    val groupedCard: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val gray: Color, val gray2: Color, val gray3: Color, val gray4: Color, val gray5: Color, val gray6: Color,
    val fill: Color,
    val tertiaryFill: Color,
    val red: Color, val green: Color, val blue: Color, val orange: Color, val purple: Color,
    val pink: Color, val brown: Color, val indigo: Color,
    val accent: Color = CpaTokens.Accent,
    val online: Color = CpaTokens.Online,
    val userBubble: Color,
    val userBubbleText: Color,
    val chipBackground: Color,
    val skillChip: Color = CpaTokens.SkillChip,
    val glass: Color,
    val menu: Color,
    val glassBorder: Color,
    val scrim: Color,
    val groupedRow: Color,
    val codeBackground: Color,
) {
    companion object {
        val Light = CpaColors(
            dark = false,
            background = Color.White,
            secondaryBackground = Color(0xFFF2F2F7),
            groupedBackground = Color(0xFFF2F2F7),
            groupedCard = Color.White,
            label = Color.Black,
            secondaryLabel = Color(0x993C3C43),
            tertiaryLabel = Color(0x4D3C3C43),
            separator = Color(0x4A3C3C43),
            gray = Color(0xFF8E8E93), gray2 = Color(0xFFAEAEB2), gray3 = Color(0xFFC7C7CC),
            gray4 = Color(0xFFD1D1D6), gray5 = Color(0xFFE5E5EA), gray6 = Color(0xFFF2F2F7),
            fill = Color(0x29787880),
            tertiaryFill = Color(0x1F767680),
            red = Color(0xFFFF3B30), green = Color(0xFF34C759), blue = Color(0xFF007AFF), orange = Color(0xFFFF9500),
            purple = Color(0xFFAF52DE), pink = Color(0xFFFF2D55), brown = Color(0xFFA2845E), indigo = Color(0xFF5856D6),
            userBubble = CpaTokens.BubbleLight,
            userBubbleText = CpaTokens.BubbleTextLight,
            chipBackground = Color(0xFFF5F5F5),
            glass = Color(0xF7FBFBFD),
            menu = Color(0xFFF9F9FB),
            glassBorder = Color(0x14000000),
            scrim = Color(0x33000000),
            groupedRow = Color(0x0F000000),
            codeBackground = Color(0xFFF2F2F7),
        )
        val Dark = CpaColors(
            dark = true,
            background = Color.Black,
            secondaryBackground = Color(0xFF1C1C1E),
            groupedBackground = Color.Black,
            groupedCard = Color(0xFF1C1C1E),
            label = Color.White,
            secondaryLabel = Color(0x99EBEBF5),
            tertiaryLabel = Color(0x4DEBEBF5),
            separator = Color(0xA6545458),
            gray = Color(0xFF8E8E93), gray2 = Color(0xFF636366), gray3 = Color(0xFF48484A),
            gray4 = Color(0xFF3A3A3C), gray5 = Color(0xFF2C2C2E), gray6 = Color(0xFF1C1C1E),
            fill = Color(0x52787880),
            tertiaryFill = Color(0x3D767680),
            red = Color(0xFFFF453A), green = Color(0xFF30D158), blue = Color(0xFF0A84FF), orange = Color(0xFFFF9F0A),
            purple = Color(0xFFBF5AF2), pink = Color(0xFFFF375F), brown = Color(0xFFAC8E68), indigo = Color(0xFF5E5CE6),
            userBubble = CpaTokens.BubbleDark,
            userBubbleText = CpaTokens.BubbleTextDark,
            chipBackground = Color(0xFF1C1C1E),
            glass = Color(0xF22A2A2D),
            menu = Color(0xFF2C2C2E),
            glassBorder = Color(0x1FFFFFFF),
            scrim = Color(0x66000000),
            groupedRow = Color(0x0FFFFFFF),
            codeBackground = Color(0xFF1C1C1E),
        )
    }
}

val LocalCpaColors = staticCompositionLocalOf { CpaColors.Light }

/** Shortcut for the current CPA palette. */
object Cpa {
    val colors: CpaColors @Composable get() = LocalCpaColors.current
}

/** Text style with iOS-like default leading for the given point size. */
fun cpaText(size: Int, weight: FontWeight = FontWeight.Normal, mono: Boolean = false, lineHeight: TextUnit? = null): TextStyle =
    TextStyle(fontSize = size.sp, fontWeight = weight, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        lineHeight = lineHeight ?: (size * 1.3f).sp)

/**
 * Whole-UI scale. Sizes in this app are iOS point values written as dp/sp, but on Android the same numbers
 * render visibly larger (CJK/Roboto metrics, narrower dp widths, vendor "display size"/"font size" defaults).
 * Scaling Density in one place shrinks text, spacing, icons and row heights together, keeping iOS proportions.
 */
object CpaScale {
    /** iPhone logical width the dimensions were designed against. */
    const val BaselineWidthDp = 393f
    /** Constant compensation for Android rendering the same numbers larger than iOS. */
    const val Compensation = .92f
    const val Min = .82f
    const val Max = 1f
    /** Portion of a user's enlarged system font scale that is kept (accessibility is still honored, just softened). */
    const val FontScaleDamping = .6f

    fun ui(screenWidthDp: Int): Float =
        (minOf(1f, screenWidthDp / BaselineWidthDp) * Compensation).coerceIn(Min, Max)

    fun font(systemFontScale: Float): Float =
        if (systemFontScale <= 1f) systemFontScale else 1f + (systemFontScale - 1f) * FontScaleDamping
}

/** The scaled density, kept in a CPA-owned local because platform locals do not survive into new windows. */
private val LocalCpaDensity = staticCompositionLocalOf<Density?> { null }

@Composable
private fun CpaScaledDensity(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val scaled = remember(base, widthDp) {
        val ui = CpaScale.ui(widthDp)
        Density(density = base.density * ui, fontScale = CpaScale.font(base.fontScale))
    }
    CompositionLocalProvider(LocalDensity provides scaled, LocalCpaDensity provides scaled, content = content)
}

/**
 * Dialog, ModalBottomSheet and Popup host their content in a new ComposeView, which re-provides LocalDensity from
 * the system and silently drops [CpaScale]. Wrap the content of every such window in this to restore it.
 */
@Composable
fun CpaWindowContent(content: @Composable () -> Unit) {
    val scaled = LocalCpaDensity.current
    if (scaled == null) content() else CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

@Composable
fun CpaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) = CpaScaledDensity {
    val palette = if (dark) CpaColors.Dark else CpaColors.Light
    val colors = if (dark) darkColorScheme(
        primary = CpaTokens.Accent, onPrimary = Color.White,
        background = palette.background, onBackground = palette.label,
        surface = palette.background, onSurface = palette.label,
        surfaceVariant = palette.secondaryBackground, onSurfaceVariant = palette.secondaryLabel,
        surfaceContainer = palette.secondaryBackground, surfaceContainerHigh = palette.gray5,
        surfaceContainerLow = palette.secondaryBackground, surfaceContainerLowest = palette.background,
        surfaceContainerHighest = palette.gray5,
        primaryContainer = CpaTokens.BubbleDark, onPrimaryContainer = CpaTokens.BubbleTextDark,
        error = palette.red, outline = palette.separator, outlineVariant = palette.separator,
    ) else lightColorScheme(
        primary = CpaTokens.Accent, onPrimary = Color.White,
        background = palette.background, onBackground = palette.label,
        surface = palette.background, onSurface = palette.label,
        surfaceVariant = palette.secondaryBackground, onSurfaceVariant = palette.secondaryLabel,
        surfaceContainer = palette.secondaryBackground, surfaceContainerHigh = palette.gray6,
        surfaceContainerLow = palette.secondaryBackground, surfaceContainerLowest = palette.background,
        surfaceContainerHighest = palette.gray5,
        primaryContainer = CpaTokens.BubbleLight, onPrimaryContainer = CpaTokens.BubbleTextLight,
        error = palette.red, outline = palette.separator, outlineVariant = palette.separator,
    )
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 39.sp),
        headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = CpaTokens.BodySize, lineHeight = 22.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontSize = CpaTokens.BodySize, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    )) {
        CompositionLocalProvider(
            LocalCpaColors provides palette,
            LocalContentColor provides palette.label,
            LocalTextSelectionColors provides TextSelectionColors(CpaTokens.Accent, CpaTokens.Accent.copy(alpha = .3f)),
            content = content,
        )
    }
}

@Composable
fun CpaAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, filled: Boolean = false) {
    val c = Cpa.colors
    Box(modifier.clip(CpaTokens.ActionShape)
        .background(if (filled) c.accent else if (selected) c.chipBackground else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .sizeIn(minWidth = 48.dp, minHeight = 44.dp).padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (filled) Color.White else if (!enabled) c.tertiaryLabel else if (selected) c.label else c.blue,
            style = cpaText(15))
    }
}

@Composable
fun Paper(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Cpa.colors
    Column(modifier.clip(CpaTokens.CardShape).background(c.groupedCard)
        .border(0.5.dp, c.separator, CpaTokens.CardShape)
        .padding(CpaTokens.PageInset), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun Eyebrow(text: String) {
    Text(text, color = Cpa.colors.secondaryLabel, style = cpaText(12))
}
