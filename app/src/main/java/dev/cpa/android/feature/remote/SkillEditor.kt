package dev.cpa.android.feature.remote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Keep canonical UTF-16 skill tokens atomic in the native text editor. */
internal fun atomicSkillEdit(before: TextFieldValue, after: TextFieldValue, draft: SkillDraft): TextFieldValue {
    if (before.text != draft.text) return after
    val refs = draft.validReferences
    if (before.text == after.text) {
        fun edge(offset: Int, end: Boolean): Int {
            val ref = refs.firstOrNull { offset > it.start && offset < it.start + it.length } ?: return offset
            return if (end) ref.start + ref.length else ref.start
        }
        val selection = if (after.selection.collapsed) {
            val ref = refs.firstOrNull { after.selection.start > it.start && after.selection.start < it.start + it.length }
            val caret = if (ref == null) after.selection.start
                else if (before.selection.collapsed && before.selection.start == ref.start + ref.length) ref.start
                else ref.start + ref.length
            TextRange(caret)
        } else {
            val low = edge(after.selection.min, false)
            val high = edge(after.selection.max, true)
            if (after.selection.reversed) TextRange(high, low) else TextRange(low, high)
        }
        return after.copy(selection = selection)
    }
    var start = 0
    while (start < minOf(before.text.length, after.text.length) && before.text[start] == after.text[start]) start++
    var suffix = 0
    while (suffix < minOf(before.text.length, after.text.length) - start &&
        before.text[before.text.lastIndex - suffix] == after.text[after.text.lastIndex - suffix]) suffix++
    val end = before.text.length - suffix
    val touched = refs.filter { ref ->
        if (start == end) start > ref.start && start < ref.start + ref.length
        else start < ref.start + ref.length && end > ref.start
    }
    if (touched.isEmpty()) return after
    val expandedStart = minOf(start, touched.minOf { it.start })
    val expandedEnd = maxOf(end, touched.maxOf { it.start + it.length })
    if (expandedStart == start && expandedEnd == end) return after
    val replacement = after.text.substring(start, after.text.length - suffix)
    return TextFieldValue(before.text.replaceRange(expandedStart, expandedEnd, replacement),
        TextRange(expandedStart + replacement.length))
}

/** Clipboard text uses the configured trigger; unselected/unreferenced dollars remain literal. */
internal fun SkillDraft.copySelection(selection: TextRange, trigger: Char): String {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    val selected = SkillDraft(text.substring(start, end), validReferences
        .filter { it.start >= start && it.start + it.length <= end }
        .map { it.copy(start = it.start - start) })
    return selected.plainText(trigger)
}

internal const val SKILL_EDITOR_TAG = "dev.cpa.skill-reference"

/** In-process metadata travels with native undo snapshots; clipboard output remains plain text. */
internal fun SkillDraft.editorValue(
    selection: TextRange = TextRange(cursor.coerceIn(0, text.length)),
    composition: TextRange? = null
): TextFieldValue {
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        validReferences.forEach { addStringAnnotation(SKILL_EDITOR_TAG, it.name, it.start, it.start + it.length) }
    }
    return TextFieldValue(annotated, selection, composition)
}

internal fun TextFieldValue.editorSkillReferences(): List<dev.cpa.android.data.repository.SkillReference> =
    dev.cpa.android.data.repository.validSkillReferences(text,
        annotatedString.getStringAnnotations(SKILL_EDITOR_TAG, 0, text.length).mapNotNull { annotation ->
            val ref = dev.cpa.android.data.repository.SkillReference(annotation.start, annotation.item)
            ref.takeIf { ref.start + ref.length == annotation.end }
        })
