package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.RemoteSkill
import dev.cpa.android.data.repository.SkillReference
import dev.cpa.android.data.repository.validSkillReferences
import java.util.Locale

/** Kotlin String indices are UTF-16 offsets, matching the desktop wire format. */
data class SkillDraft(val text: String = "", val references: List<SkillReference> = emptyList(), val cursor: Int = text.length) {
    val validReferences: List<SkillReference> get() = validSkillReferences(text, references)
    val slashQuery: String? get() = text.trimStart().takeIf { validReferences.isEmpty() && it.startsWith('/') && it.none(Char::isWhitespace) }?.drop(1)
    fun skillQuery(trigger: Char): String? {
        val caret = cursor.coerceIn(0, text.length)
        var index = caret - 1
        while (index >= 0 && nameChar(text[index])) index--
        if (index < 0 || text[index] != trigger || !boundary(text, index) || validReferences.any { index >= it.start && index < it.start + it.length }) return null
        return text.substring(index + 1, caret)
    }
    fun insertingSkill(name: String, trigger: Char): SkillDraft? {
        val query = skillQuery(trigger) ?: return null
        val caret = cursor.coerceIn(0, text.length); val start = caret - query.length - 1
        val replacement = "$${name} "; val delta = replacement.length - (caret - start)
        return SkillDraft(text.replaceRange(start, caret, replacement),
            validReferences.filter { it.start + it.length <= start } + SkillReference(start, name) +
                validReferences.filter { it.start >= caret }.map { it.copy(start = it.start + delta) }, start + replacement.length)
    }
    /** Adjust references through a single contiguous text edit, dropping any chip that was touched. */
    fun edited(newText: String, newCursor: Int = newText.length): SkillDraft {
        var prefix = 0
        while (prefix < minOf(text.length, newText.length) && text[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(text.length, newText.length) - prefix && text[text.lastIndex - suffix] == newText[newText.lastIndex - suffix]) suffix++
        val oldEnd = text.length - suffix; val delta = newText.length - text.length
        return SkillDraft(newText, validReferences.mapNotNull {
            when {
                it.start + it.length <= prefix -> it
                it.start >= oldEnd -> it.copy(start = it.start + delta)
                else -> null
            }
        }, newCursor)
    }
    fun payload(skills: List<RemoteSkill>, trigger: Char): SkillDraft {
        val names = linkedMapOf<String, String>()
        skills.forEach { names.putIfAbsent(it.name.lowercase(Locale.ROOT), it.name) }
        val output = StringBuilder(); val refs = mutableListOf<SkillReference>()
        fun appendSegment(segment: String) {
            var index = 0
            while (index < segment.length) {
                if (segment[index] == trigger && boundary(segment, index)) {
                    val explicit = trigger == '/' && segment.startsWith("skill:", index + 1)
                    val start = index + 1 + if (explicit) 6 else 0
                    var end = start
                    while (end < segment.length && nameChar(segment[end])) end++
                    val token = segment.substring(start, end).lowercase(Locale.ROOT)
                    val reserved = trigger == '/' && !explicit && token in setOf("compact", "compress", "model", "models") && output.all(Char::isWhitespace)
                    val name = names[token]
                    if (end > start && (end == segment.length || segment[end].isWhitespace()) && !reserved && name != null) {
                        refs += SkillReference(output.length, name); output.append('$').append(name); index = end; continue
                    }
                }
                output.append(segment[index++])
            }
        }
        var offset = 0
        for (ref in validReferences) {
            appendSegment(text.substring(offset, ref.start))
            refs += SkillReference(output.length, ref.name); output.append('$').append(ref.name)
            offset = ref.start + ref.length
        }
        appendSegment(text.substring(offset))
        val leading = output.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) output.length else it }
        val trimmed = output.toString().trim()
        return SkillDraft(trimmed, refs.filter { it.start >= leading && it.start + it.length <= leading + trimmed.length }.map { it.copy(start = it.start - leading) })
    }
    fun plainText(trigger: Char): String {
        val result = StringBuilder(); var offset = 0
        for (ref in validReferences) {
            result.append(text.substring(offset, ref.start)).append(if (trigger == '/') "/skill:" else trigger.toString()).append(ref.name)
            offset = ref.start + ref.length
        }
        return result.append(text.substring(offset)).toString()
    }
}
internal fun nameChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-'
internal fun boundary(text: String, index: Int): Boolean {
    if (index == 0) return true
    val previous = text[index - 1]
    if (previous in "#$/\\") return false
    return previous.isWhitespace() || Character.getType(previous) in setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
}
