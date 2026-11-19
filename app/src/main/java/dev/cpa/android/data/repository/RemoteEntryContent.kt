package dev.cpa.android.data.repository

import kotlinx.serialization.json.*

sealed interface RemoteContent {
    data class Text(val text: String) : RemoteContent
    data class Thinking(val text: String) : RemoteContent
    data class Image(val data: String, val mimeType: String) : RemoteContent
    data class ToolCall(val id: String, val name: String, val arguments: JsonElement) : RemoteContent
    data class Unknown(val raw: JsonElement) : RemoteContent
}
data class RemoteEntryDetails(val content: List<RemoteContent> = emptyList(), val createdAt: Double? = null,
    val completedAt: Double? = null, val pausedMs: Double = 0.0, val status: String? = null, val stopReason: String? = null,
    val errorMessage: String? = null, val toolCallId: String? = null, val isError: Boolean = false, val interrupted: Boolean = false,
    val usage: JsonObject? = null, val displayMetadata: JsonObject? = null)
data class RemoteRunningTool(val name: String? = null, val details: JsonObject? = null)
internal fun String?.isToolResultKind() = this == "toolResult" || this == "tool_result"
internal fun RemoteEntry.isConversationActivity() = kind == "user" || kind == "assistant" || kind.isToolResultKind()
internal fun normalizedToolId(id: String) = id.substringBefore('|')

internal fun parseRemoteEntry(item: JsonObject): RemoteEntry? {
    val id = item.string("id") ?: return null
    val content = (item["content"] as? JsonArray).orEmpty().map { raw ->
        val block = raw as? JsonObject
        when (block?.string("type")) {
            "text" -> RemoteContent.Text(block.string("text").orEmpty())
            "thinking" -> RemoteContent.Thinking(block.string("thinking").orEmpty())
            "image" -> RemoteContent.Image(block.string("data").orEmpty(), block.string("mimeType") ?: "image/png")
            "toolCall" -> RemoteContent.ToolCall(block.string("id").orEmpty(), block.string("name").orEmpty(), block["arguments"] ?: JsonNull)
            else -> RemoteContent.Unknown(raw)
        }
    }
    val text = content.filterIsInstance<RemoteContent.Text>().joinToString("\n") { it.text }.ifEmpty { item.string("summary").orEmpty() }
    val images = content.filterIsInstance<RemoteContent.Image>().filter { it.data.isNotEmpty() }.map { RemoteSentImage(it.data, it.mimeType) }
    val references = (item["skillReferences"] as? JsonArray).orEmpty().mapNotNull {
        val ref = it as? JsonObject ?: return@mapNotNull null
        val start = (ref["start"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        ref.string("name")?.let { name -> SkillReference(start, name) }
    }
    fun number(key: String) = (item[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
    fun flag(key: String) = (item[key] as? JsonPrimitive)?.booleanOrNull == true
    return RemoteEntry(id, item.string("kind") ?: "unknown", text, item.string("toolName"), images,
        validSkillReferences(text, references), item.string("pendingStatus"),
        RemoteEntryDetails(content, number("createdAt"), number("completedAt"), number("pausedMs")?.coerceAtLeast(0.0) ?: 0.0,
            item.string("status"), item.string("stopReason"), item.string("errorMessage"), item.string("toolCallId"), flag("isError"), flag("interrupted"),
            item["usage"] as? JsonObject, item["displayMetadata"] as? JsonObject), hasSkillReferences = item["skillReferences"] != null && item["skillReferences"] != JsonNull)
}

/** Live tool state only; caller fences connection/run IDs before applying it. */
internal fun RemoteThread.withToolEvent(event: JsonObject): RemoteThread {
    val id = event.string("toolCallId")?.let(::normalizedToolId)?.takeIf(String::isNotEmpty) ?: return this
    return when (event.string("type")) {
        "tool-start" -> copy(runningTools = runningTools + (id to RemoteRunningTool(event.string("toolName"))))
        "tool-update" -> {
            val details = (event["result"] as? JsonObject)?.get("details") as? JsonObject ?: return this
            if (details["nestedTools"] !is JsonArray) return this
            copy(runningTools = runningTools + (id to (runningTools[id] ?: RemoteRunningTool()).copy(details = details)))
        }
        "tool-end" -> copy(runningTools = runningTools - id)
        "tool-display-update" -> {
            val update = event["displayMetadata"] as? JsonObject ?: return this
            val target = entries.indexOfLast { it.kind.isToolResultKind() && it.details.toolCallId?.let(::normalizedToolId) == id }
            if (target < 0) return this
            copy(entries = entries.mapIndexed { index, entry ->
                if (index != target) entry else entry.copy(details = entry.details.copy(displayMetadata = JsonObject(entry.details.displayMetadata.orEmpty() + update)))
            })
        }
        else -> this
    }
}
