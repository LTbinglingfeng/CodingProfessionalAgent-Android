package dev.cpa.android.data.repository

import kotlinx.serialization.json.*
import java.util.UUID

internal data class ForkPayload(val meta: JsonObject, val session: JsonObject)

/** Preserve complete entry content, not the lossy UI projection. Mirrors desktop/iOS fork semantics. */
internal fun buildForkPayload(source: JsonObject, snapshot: JsonObject, newId: String, atEntryId: String?,
    worktree: RemoteWorktreeFork?, now: Long = System.currentTimeMillis()): ForkPayload {
    fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val entries = snapshot["entries"] as? JsonArray ?: error("Invalid session snapshot")
    val boundary = atEntryId?.let { id ->
        entries.indexOfFirst { (it as? JsonObject)?.string("id") == id }.takeIf { it >= 0 }
            ?: entries.indexOfFirst {
                val item = it as? JsonObject
                item?.string("kind") == "assistant" && (item["content"] as? JsonArray).orEmpty().any { block ->
                    (block as? JsonObject)?.string("id") == id
                }
            }.takeIf { it >= 0 }
    }
    var end = boundary?.plus(1) ?: entries.size
    if (boundary != null) while (end < entries.size && ((entries[end] as? JsonObject)?.string("kind")).isToolResultKind()) end++
    val copied = JsonArray(entries.take(end).map { entry ->
        val item = entry as? JsonObject ?: return@map entry
        JsonObject(item + buildJsonObject {
            put("id", UUID.randomUUID().toString()); put("sessionId", newId)
            if (item.string("kind") == "assistant" && item.string("status") == "streaming") put("status", "done")
        })
    })
    val location = if (worktree != null) "worktree" else source.string("workLocation")
    val newWorktree = location == "worktree"
    val title = source.string("title").orEmpty().ifEmpty { "Chat" }
    val meta = buildJsonObject {
        put("id", newId); put("title", if (title.endsWith(" (Fork)")) title else "$title (Fork)")
        put("pinned", false); put("unread", false); put("createdAt", now); put("updatedAt", now)
        listOf("projectId", "branch", "baseBranch", "workLocation", "environmentId", "modelId", "reasoningEffort", "speed").forEach { field ->
            if (field != "environmentId" || worktree == null) source.string(field)?.let { put(field, it) }
        }
        if (!newWorktree) {
            source.string("worktreePath")?.let { put("worktreePath", it) }
            source["worktreeSetup"]?.let { put("worktreeSetup", it) }
        }
        if (worktree != null) {
            put("branch", worktree.branch); put("workLocation", "worktree")
            // Explicit "without environment" must not inherit the source environment.
            worktree.environmentId?.let { put("environmentId", it) }
        }
        val firstPrompt = (source["firstPromptAt"] as? JsonPrimitive)?.doubleOrNull
        if (firstPrompt != null && firstPrompt > 0) put("firstPromptAt", source.getValue("firstPromptAt"))
    }
    return ForkPayload(meta, JsonObject(meta + ("entries" to copied)))
}

internal fun parseBranches(stdout: String): RemoteBranches {
    var current: String? = null
    val branches = stdout.lineSequence().mapNotNull { line ->
        if (line.isEmpty()) return@mapNotNull null
        val name = line.drop(1).trim()
        if (name.isEmpty() || name.startsWith('(')) return@mapNotNull null
        if (line.startsWith('*')) current = name
        name
    }.distinct().toList()
    return RemoteBranches(current, branches)
}
