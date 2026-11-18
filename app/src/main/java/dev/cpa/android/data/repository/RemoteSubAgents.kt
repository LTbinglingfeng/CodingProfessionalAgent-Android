package dev.cpa.android.data.repository

import kotlinx.serialization.json.*

data class RemoteSubAgent(val id: String, val parentSessionId: String, val sessionId: String, val name: String,
    val status: String, val modelId: String, val createdAt: Double, val updatedAt: Double,
    val parentToolCallId: String? = null, val reasoningEffort: String? = null, val speed: String? = null,
    val errorMessage: String? = null, val completedAt: Double? = null, val pausedMs: Double? = null,
    val icon: String? = null, val color: String? = null) {
    val conversationId get() = sessionId.ifEmpty { id }
    val inProgress get() = status == "running" || status == "queued"
    val statusTitle get() = when(status) { "queued" -> "排队中"; "running" -> "运行中"; "completed" -> "已完成"; "error" -> "失败"; "aborted" -> "已中断"; else -> status }
    fun elapsed(now: Double) = ((if (inProgress) now else completedAt ?: updatedAt) - createdAt - (pausedMs ?: 0.0).coerceAtLeast(0.0)).coerceAtLeast(0.0)
}
internal fun parseSubAgents(value: JsonElement?): List<RemoteSubAgent> = (value as? JsonArray).orEmpty().mapNotNull { raw ->
    val item = raw as? JsonObject ?: return@mapNotNull null
    fun text(key: String) = (item[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun number(key: String) = (item[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
    val id = text("id")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
    val parent = text("parentSessionId")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
    RemoteSubAgent(id, parent, text("sessionId").orEmpty(), text("name") ?: id, text("status") ?: "unknown", text("modelId").orEmpty(),
        number("createdAt") ?: 0.0, number("updatedAt") ?: 0.0, text("parentToolCallId"), text("reasoningEffort"), text("speed"), text("errorMessage"),
        number("completedAt"), number("pausedMs"), text("icon"), text("color"))
}
internal fun mergeSubAgents(existing: List<RemoteSubAgent>, incoming: List<RemoteSubAgent>, snapshot: Boolean): List<RemoteSubAgent> {
    val merged = existing.associateByTo(linkedMapOf()) { it.id }
    for (record in incoming) {
        val current = merged[record.id]
        if (current != null && current.updatedAt > record.updatedAt) continue
        merged[record.id] = if (snapshot && current != null) record.copy(completedAt = record.completedAt ?: current.completedAt, pausedMs = record.pausedMs ?: current.pausedMs) else record
    }
    return merged.values.sortedBy { it.createdAt }
}
