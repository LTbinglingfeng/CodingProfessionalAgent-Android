package dev.cpa.android.data.repository

/** iOS ThreadModel completion/resumability rules, evaluated against the current merged snapshot. */
fun RemoteThread.canResume(): Boolean {
    if (running || loading || outcomeUnknown || editAwaitingStart || error == RemoteProblem.SessionDeleted || error == RemoteProblem.ConnectionRemoved) return false
    val last = entries.lastOrNull(RemoteEntry::isConversationActivity)
    val details = last?.details
    // If the last activity is an assistant call, there cannot be a result after it.
    val hasCalls = details?.content.orEmpty().any { it is RemoteContent.ToolCall && normalizedToolId(it.id).isNotEmpty() }
    val restarted = details?.errorMessage in setOf("Response stream ended prematurely before completion", "Interrupted by session restart")
    val completed = last?.kind == "assistant" && details?.status == "done" && !details.interrupted &&
        details.stopReason !in setOf("aborted", "error", "pending", "toolUse") && !restarted && !hasCalls
    if (completed) return false
    if (wasStopped) return true
    if (entries.isEmpty()) return false
    if (subAgents.any { it.inProgress }) return true
    return when {
        last?.kind == "user" || last?.kind.isToolResultKind() -> true
        last?.kind == "assistant" -> when {
            restarted || details!!.status in setOf("aborted", "streaming") || details.stopReason in setOf("aborted", "pending") || details.interrupted -> true
            details.stopReason == "toolUse" || hasCalls -> true
            details.status == "error" || details.stopReason == "error" -> false
            else -> details.status != "done"
        }
        else -> false
    }
}
