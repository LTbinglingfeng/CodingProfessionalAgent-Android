package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.RemoteEntry
import dev.cpa.android.data.repository.RemoteSkill
import java.util.Locale

internal sealed interface PromptPart {
    data class Text(val value: String) : PromptPart
    data class Skill(val name: String) : PromptPart
}

/** Presentation only: legacy inference must never rewrite stored text or outgoing references. */
internal fun RemoteEntry.promptParts(knownSkills: List<RemoteSkill>): List<PromptPart> {
    val parts = mutableListOf<PromptPart>()
    var offset = 0
    if (hasSkillReferences) {
        for (ref in SkillDraft(text, skillReferences).validReferences) {
            if (ref.start > offset) parts += PromptPart.Text(text.substring(offset, ref.start))
            parts += PromptPart.Skill(ref.name)
            offset = ref.start + ref.length
        }
    } else {
        val names = linkedMapOf<String, String>()
        knownSkills.forEach { names.putIfAbsent(it.name.lowercase(Locale.ROOT), it.name) }
        var index = 0
        while (index < text.length) {
            val prefix = when {
                text[index] == '$' -> 1
                text.startsWith("/skill:", index) -> 7
                else -> 0
            }
            if (prefix > 0 && boundary(text, index)) {
                var end = index + prefix
                while (end < text.length && nameChar(text[end])) end++
                val name = names[text.substring(index + prefix, end).lowercase(Locale.ROOT)]
                if (end > index + prefix && (end == text.length || text[end].isWhitespace()) && name != null) {
                    if (index > offset) parts += PromptPart.Text(text.substring(offset, index))
                    parts += PromptPart.Skill(name)
                    offset = end
                    index = end
                    continue
                }
            }
            index++
        }
        if (parts.isEmpty()) {
            val trimmed = text.trim()
            val rest = when {
                trimmed.startsWith("/skill:") -> trimmed.drop(7)
                trimmed.startsWith('$') -> trimmed.drop(1)
                else -> null
            }
            // Matches iOS's command fallback, including unknown/uninstalled skills.
            val name = rest?.substringBefore(' ')
            if (!name.isNullOrEmpty()) {
                val args = rest.drop((name.length + 1).coerceAtMost(rest.length)).trim()
                return listOf(PromptPart.Skill(name)) + if (args.isEmpty()) emptyList() else listOf(PromptPart.Text(" $args"))
            }
        }
    }
    if (offset < text.length) parts += PromptPart.Text(text.substring(offset))
    return parts
}

internal fun List<PromptPart>.promptText(trigger: Char): String = joinToString("") {
    when (it) {
        is PromptPart.Text -> it.value
        is PromptPart.Skill -> (if (trigger == '/') "/skill:" else trigger.toString()) + it.name
    }
}

internal fun List<PromptPart>.promptDraft(): SkillDraft {
    val text = StringBuilder()
    val refs = mutableListOf<dev.cpa.android.data.repository.SkillReference>()
    for (part in this) when (part) {
        is PromptPart.Text -> text.append(part.value)
        is PromptPart.Skill -> {
            refs += dev.cpa.android.data.repository.SkillReference(text.length, part.name)
            text.append('$').append(part.name)
        }
    }
    return SkillDraft(text.toString(), refs)
}
