package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import kotlinx.serialization.json.*

internal data class TurnTool(val owner: String, val call: RemoteContent.ToolCall, val result: RemoteEntry?, val live: Boolean, val running: Boolean = live && result == null)
internal sealed interface TurnActivity {
    data class Text(val text: String) : TurnActivity
    data class Tools(val items: List<TurnTool>) : TurnActivity
    data class Compaction(val id: String) : TurnActivity
}
internal fun isAgentLaunch(name: String) = name in setOf("spawn_agent", "delegate_agent", "subagent")

/** Keep each result bound to its message even when call IDs repeat across stages. */
internal fun turnActivity(turn: RemoteTurn.Assistant, details: Map<String, RemoteRunningTool> = emptyMap()): List<TurnActivity> {
    val parts = mutableListOf<TurnActivity>()
    val tools = mutableListOf<TurnTool>()
    val lastCompaction = turn.items.indexOfLast { it.kind == "compaction" }
    var seen = mutableSetOf<String>()
    fun beginSection(start: Int) {
        seen = turn.items.drop(start).takeWhile { it.kind != "compaction" }
            .flatMap { it.details.content.filterIsInstance<RemoteContent.ToolCall>() }
            .map { normalizedToolId(it.id) }.toMutableSet()
    }
    beginSection(0)
    fun flush() { if (tools.isNotEmpty()) { parts += TurnActivity.Tools(tools.toList()); tools.clear() } }
    fun append(item: TurnTool) {
        if (tools.isNotEmpty() && isAgentLaunch(tools.last().call.name) != isAgentLaunch(item.call.name)) flush()
        tools += item
    }
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    for ((index, entry) in turn.items.withIndex()) {
        if (entry.kind == "compaction") { flush(); parts += TurnActivity.Compaction(entry.id); beginSection(index + 1); continue }
        for (part in activityParts(entry)) when (part) {
            is ActivityPart.Text -> { flush(); parts += TurnActivity.Text(part.text) }
            is ActivityPart.Tools -> for (call in part.calls) {
                val id = normalizedToolId(call.id)
                val result = turn.results[entry.id]?.get(id)
                val live = turn.live && index > lastCompaction
                append(TurnTool(entry.id, call, result, live))
                val liveNested = details[id]?.details?.get("nestedTools") as? JsonArray
                val nested = liveNested?.takeIf { it.isNotEmpty() } ?: result?.details?.displayMetadata?.get("nestedTools") as? JsonArray
                for (raw in nested.orEmpty()) {
                    val record = raw as? JsonObject ?: continue
                    val name = record.text("name") ?: record.text("toolName") ?: continue
                    val nestedId = record.text("id")?.takeIf(String::isNotEmpty) ?: continue
                    if (!isAgentLaunch(name) || !seen.add(normalizedToolId(nestedId))) continue
                    val active = record.text("status") in setOf("queued", "running", "awaiting_approval")
                    append(TurnTool(entry.id, RemoteContent.ToolCall(nestedId, name, agentArguments(record["args"] ?: record["arguments"])), null, live, live && active))
                }
            }
        }
    }
    flush()
    return parts
}

internal val RemoteTurn.Assistant.hasVisibleActivity: Boolean get() = messages.any { message ->
    if (message.details.content.isEmpty()) message.text.isNotBlank()
    else message.details.content.any { block -> when (block) {
        is RemoteContent.Text -> block.text.isNotBlank()
        is RemoteContent.ToolCall -> !isSkillRead(block.name, block.arguments)
        else -> false
    } }
}
