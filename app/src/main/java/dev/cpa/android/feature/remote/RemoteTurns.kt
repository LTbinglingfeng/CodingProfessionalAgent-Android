package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*

internal sealed interface RemoteTurn {
    val id: String
    data class Entry(val entry: RemoteEntry) : RemoteTurn { override val id = "entry:${entry.id}" }
    data class Assistant(val items: List<RemoteEntry>, val results: Map<String, Map<String, RemoteEntry>>,
        val startedAt: Double?, val pausedMs: Double = 0.0, val live: Boolean = false, val resultCompletedAt: Double? = null) : RemoteTurn {
        override val id = "turn:${items.firstOrNull()?.id ?: "pending"}"
        val messages get() = items.filter { it.kind == "assistant" }
        val status get() = when {
            live -> "running"
            messages.any { it.details.status == "error" } -> "error"
            messages.any { it.details.status == "aborted" || it.details.stopReason == "aborted" || it.details.interrupted } -> "aborted"
            else -> "done"
        }
        val error get() = messages.lastOrNull { !it.details.errorMessage.isNullOrBlank() }?.details?.errorMessage
        val completedAt get() = (listOfNotNull(resultCompletedAt) + messages.mapNotNull { it.details.completedAt } + results.values.flatMap { it.values }.mapNotNull { it.details.createdAt }).filter(Double::isFinite).maxOrNull()
        val effectivePausedMs get() = if (pausedMs > 0) pausedMs else messages.maxOfOrNull { it.details.pausedMs } ?: 0.0
        fun elapsed(now: Double): Double = ((if (live) now else completedAt) ?.let { end -> startedAt?.let { end - it - effectivePausedMs } } ?: 0.0).coerceAtLeast(0.0)
        val trailingText: String get() {
            val message = messages.lastOrNull() ?: return ""
            val blocks = message.details.content
            if (blocks.isEmpty()) return message.text
            val start = blocks.indexOfLast { it is RemoteContent.ToolCall || it is RemoteContent.Thinking } + 1
            return blocks.drop(start).filterIsInstance<RemoteContent.Text>().joinToString("") { it.text }.ifEmpty {
                blocks.filterIsInstance<RemoteContent.Text>().lastOrNull { it.text.isNotBlank() }?.text.orEmpty()
            }
        }

    }
}

internal fun formatTurnDuration(ms: Double): String {
    val seconds = (if (ms.isFinite()) ms.coerceAtLeast(0.0) / 1000 else 0.0).coerceAtMost(1e15).toLong()
    val days = seconds / 86400
    val pieces = mutableListOf<String>()
    if (days / 365 > 0) pieces += "${days / 365}y"
    if (days % 365 / 30 > 0) pieces += "${days % 365 / 30}mo"
    if (days % 365 % 30 > 0) pieces += "${days % 365 % 30}d"
    if (seconds / 3600 % 24 > 0 || pieces.isNotEmpty()) pieces += "${seconds / 3600 % 24}h"
    if (seconds / 60 % 60 > 0 || pieces.isNotEmpty()) pieces += "${seconds / 60 % 60}m"
    pieces += "${seconds % 60}s"
    return pieces.joinToString(" ")
}

internal fun remoteTurns(entries: List<RemoteEntry>, running: Boolean): List<RemoteTurn> {
    val projection = activityProjection(entries)
    // Completion considers every adjacent result, even when duplicate call IDs display only the first.
    val resultTimes = mutableMapOf<String, Double>()
    var owner: String? = null
    for (entry in entries) {
        if (entry.kind == "toolResult" || entry.kind == "tool_result") {
            val time = entry.details.createdAt
            if (owner != null && time != null && time.isFinite()) resultTimes[owner] = maxOf(resultTimes[owner] ?: time, time)
        } else owner = entry.id.takeIf { entry.kind == "assistant" }
    }
    val turns = mutableListOf<RemoteTurn>()
    val buffer = mutableListOf<RemoteEntry>()
    var start: Double? = null
    var paused = 0.0
    fun flush() {
        if (buffer.isEmpty()) return
        if (buffer.any { it.kind == "assistant" } || start != null) {
            turns += RemoteTurn.Assistant(buffer.toList(), projection.results.filterKeys { id -> buffer.any { it.id == id } }, start ?: buffer.first().details.createdAt, paused, resultCompletedAt = buffer.mapNotNull { resultTimes[it.id] }.maxOrNull())
        } else buffer.forEach { turns += RemoteTurn.Entry(it) }
        buffer.clear(); paused = 0.0
    }
    for (entry in projection.entries) when (entry.kind) {
        "assistant", "compaction" -> buffer += entry
        "user" -> {
            flush()
            start = if (entry.pendingStatus == null) entry.details.createdAt else null
            paused = if (entry.pendingStatus == null) entry.details.pausedMs else 0.0
            turns += RemoteTurn.Entry(entry)
        }
        else -> { flush(); turns += RemoteTurn.Entry(entry) }
    }
    flush()
    if (!running) return turns
    for (index in turns.indices.reversed()) {
        val turn = turns[index]
        if (turn is RemoteTurn.Assistant) { turns[index] = turn.copy(live = true); return turns }
        if (turn is RemoteTurn.Entry && turn.entry.kind == "user" && turn.entry.pendingStatus != null) continue
        break
    }
    val last = turns.lastOrNull()
    if (last is RemoteTurn.Entry && last.entry.pendingStatus != null) return turns
    turns += RemoteTurn.Assistant(emptyList(), emptyMap(), start, live = true)
    return turns
}
