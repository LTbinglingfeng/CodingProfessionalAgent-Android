package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*

internal sealed interface ActivityPart {
    data class Text(val text: String) : ActivityPart
    data class Tools(val calls: List<RemoteContent.ToolCall>) : ActivityPart
}
internal fun activityParts(entry: RemoteEntry): List<ActivityPart> {
    if (entry.details.content.isEmpty()) return listOfNotNull(entry.text.takeIf(String::isNotBlank)?.let(ActivityPart::Text))
    val result = mutableListOf<ActivityPart>(); val calls = mutableListOf<RemoteContent.ToolCall>()
    fun flush() { if (calls.isNotEmpty()) { result += ActivityPart.Tools(calls.toList()); calls.clear() } }
    for (block in entry.details.content) when (block) {
        is RemoteContent.Text -> if (block.text.isNotBlank()) { flush(); result += ActivityPart.Text(block.text) }
        is RemoteContent.ToolCall -> calls += block
        else -> Unit // Thinking does not split a consecutive tool group, matching iOS.
    }
    flush(); return result
}
internal data class ActivityProjection(val entries: List<RemoteEntry>, val results: Map<String, Map<String, RemoteEntry>>)
internal fun activityProjection(entries: List<RemoteEntry>): ActivityProjection {
    val results = linkedMapOf<String, MutableMap<String, RemoteEntry>>()
    val visible = mutableListOf<RemoteEntry>(); var owner: RemoteEntry? = null
    for (entry in entries) {
        if (entry.kind == "toolResult" || entry.kind == "tool_result") {
            val id = entry.details.toolCallId?.let(::normalizedToolId)
            val matched = id != null && owner?.details?.content.orEmpty().filterIsInstance<RemoteContent.ToolCall>().any { normalizedToolId(it.id) == id }
            if (matched) results.getOrPut(owner!!.id) { linkedMapOf() }.putIfAbsent(id!!, entry)
            else visible += entry // Retain orphan/legacy results instead of making existing data disappear.
            continue
        }
        owner = if (entry.kind == "assistant") entry else null
        visible += entry
    }
    return ActivityProjection(visible, results)
}
