package dev.cpa.android.data.repository

import kotlinx.serialization.json.*

internal enum class UnreadOutcome { Completed, Error, Incomplete, Pending }

/** Matches iOS's terminal classification; a trailing tool/compaction is not a completed answer. */
internal fun classifyUnreadOutcome(entries: List<RemoteEntry>, baseline: Double): UnreadOutcome {
    val entry = entries.lastOrNull { it.isConversationActivity() || it.kind == "compaction" }
        ?: return UnreadOutcome.Pending
    val details = entry.details
    if (baseline > 0 && maxOf(details.createdAt ?: 0.0, details.completedAt ?: 0.0) <= baseline) return UnreadOutcome.Pending
    if (entry.kind != "assistant") return UnreadOutcome.Pending
    if (details.status == "aborted" || details.stopReason == "aborted" || details.interrupted ||
        details.errorMessage in setOf("Response stream ended prematurely before completion", "Interrupted by session restart")) return UnreadOutcome.Incomplete
    if (details.status == "error" || details.stopReason == "error" || !details.errorMessage.isNullOrEmpty()) return UnreadOutcome.Error
    return if (details.status == "done" && details.stopReason !in setOf("toolUse", "pending")) UnreadOutcome.Completed else UnreadOutcome.Pending
}

internal data class UnreadWrite(val outcome: UnreadOutcome, val updatedAt: Long)

internal class UnreadRun(val id: String, var baseline: Double, val explicitCompact: Boolean = false, var initialUnread: String = "read") {
    var idle = false
    var settled = false
    var pendingWrite: UnreadWrite? = null
    var suppressed = false
    var viewEpoch = 0L
    var latestTimestamp = 0.0
    var completed = false
    var error = false
    var aborted = false
    var assistantActivity = false
    var compaction = false
    var retries = 2
    fun outcome(): UnreadOutcome = when {
        aborted || explicitCompact || (compaction && !assistantActivity && !error && !completed) -> UnreadOutcome.Incomplete
        error -> UnreadOutcome.Error
        completed -> UnreadOutcome.Completed
        else -> UnreadOutcome.Pending
    }
    fun consume(event: JsonObject) {
        when ((event["type"] as? JsonPrimitive)?.contentOrNull) {
            "assistant-start", "tool-start" -> { assistantActivity = true; completed = false }
            "assistant-delta", "tool-end" -> assistantActivity = true
            "compaction-start", "compaction-end" -> compaction = true
            "assistant-end" -> {
                assistantActivity = true
                val entry = (event["entry"] as? JsonObject)?.let(::parseRemoteEntry) ?: return
                latestTimestamp = maxOf(latestTimestamp, entry.details.createdAt ?: 0.0, entry.details.completedAt ?: 0.0)
                // Live events follow iOS's run tracker; offline snapshots use the stricter classifier above.
                val details = entry.details
                when {
                    details.status == "aborted" || details.stopReason == "aborted" -> { aborted = true; completed = false }
                    details.status == "error" || !details.errorMessage.isNullOrEmpty() -> { error = true; completed = false }
                    details.status == "done" && details.stopReason != "toolUse" -> completed = true
                    details.stopReason == "toolUse" -> completed = false
                }
            }
            "aborted" -> { aborted = true; completed = false }
            "error" -> { error = true; completed = false }
        }
    }
}
