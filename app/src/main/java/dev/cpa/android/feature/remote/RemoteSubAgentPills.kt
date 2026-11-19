package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import kotlinx.serialization.json.*

internal data class RemoteAgentPill(val id: String, val name: String, val record: RemoteSubAgent?, val running: Boolean, val queued: Boolean)
private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun agentArguments(value: JsonElement?): JsonObject = (value as? JsonObject)
    ?: (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    ?: JsonObject(emptyMap())
private fun spawn(name: String) = name in setOf("spawn_agent", "delegate_agent", "subagent")
private fun inFlight(status: String?) = status in setOf("queued", "running", "awaiting_approval")
private fun target(args: JsonObject) = sequenceOf("agent_id", "agentId", "target").mapNotNull { args.text(it)?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()

internal fun subAgentPills(calls: List<RemoteContent.ToolCall>, results: Map<String, RemoteEntry>, details: Map<String, RemoteRunningTool>, records: List<RemoteSubAgent>, live: Boolean, knownCalls: Set<String> = calls.map { normalizedToolId(it.id) }.toSet(), launchStates: Map<String, Boolean>? = null, expandNested: Boolean = true): List<RemoteAgentPill> {
    data class Launch(val call: RemoteContent.ToolCall, val running: Boolean)
    val launches = mutableListOf<Launch>()
    val seen = knownCalls.toMutableSet()
    for (call in calls) {
        val id = normalizedToolId(call.id)
        if (spawn(call.name)) launches += Launch(call, launchStates?.get(id) ?: (live && results[id] == null))
        if (!expandNested) continue
        val liveNested = details[id]?.details?.get("nestedTools") as? JsonArray
        val nested = liveNested?.takeIf { it.isNotEmpty() } ?: results[id]?.details?.displayMetadata?.get("nestedTools") as? JsonArray
        for (raw in nested.orEmpty()) {
            val item = raw as? JsonObject ?: continue
            val name = item.text("name") ?: item.text("toolName") ?: continue
            val nestedId = item.text("id")?.takeIf(String::isNotEmpty) ?: continue
            if (!spawn(name) || !seen.add(normalizedToolId(nestedId))) continue
            launches += Launch(RemoteContent.ToolCall(nestedId, name, agentArguments(item["args"] ?: item["arguments"])), live && inFlight(item.text("status")))
        }
    }
    val pills = linkedMapOf<String, RemoteAgentPill>()
    for ((call, running) in launches) {
        val args = agentArguments(call.arguments)
        val requested = args.text("name")?.trim()?.takeIf(String::isNotEmpty)
        val record = records.firstOrNull { it.parentToolCallId?.let(::normalizedToolId) == normalizedToolId(call.id) }
            ?: records.firstOrNull { requested != null && it.name == requested && it.parentToolCallId.isNullOrEmpty() }
        val prompt = args.text("prompt")?.trim().orEmpty()
        if (record == null && !(running && prompt.isNotBlank())) continue
        val terminal = record?.status in setOf("completed", "error", "aborted") || (!running && record?.status != "running")
        val queued = record?.status == "queued"
        val followUp = record != null && details.values.any { tool ->
            (tool.details?.get("nestedTools") as? JsonArray).orEmpty().any { raw ->
                val nested = raw as? JsonObject
                val name = nested?.text("name") ?: nested?.text("toolName")
                val target = nested?.let { target(agentArguments(it["args"] ?: it["arguments"])) }
                name in setOf("send_message", "send_input") && inFlight(nested?.text("status")) && target != null &&
                    (target == record.id || target == record.sessionId || target.equals(record.name, ignoreCase = true))
            }
        }
        val busy = followUp || (!terminal && (record?.status == "running" || queued || running || live))
        val id = record?.id ?: "part:${call.id}"
        val fallback = requested ?: prompt.takeIf(String::isNotEmpty)?.let { it.substring(0, it.offsetByCodePoints(0, minOf(18, it.codePointCount(0, it.length)))) } ?: target(args) ?: "Agent"
        val old = pills[id]
        pills[id] = RemoteAgentPill(id, record?.name ?: fallback, record, busy || old?.running == true, queued || old?.queued == true)
    }
    return pills.values.toList()
}
