package dev.cpa.android.data.repository

import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Test

class RemoteResumeTest {
    private fun assistant(status: String? = "done", stop: String? = null, error: String? = null, interrupted: Boolean = false,
        calls: List<RemoteContent> = emptyList()) = RemoteEntry("a", "assistant", "reply", details = RemoteEntryDetails(status = status,
            stopReason = stop, errorMessage = error, interrupted = interrupted, content = calls))
    private fun thread(vararg entries: RemoteEntry) = RemoteThread("c", "s", entries.toList())
    @Test fun completionWinsOverStoppedAndActiveChildWhileOrdinaryErrorsDoNotResume() {
        val child = RemoteSubAgent("agent", "s", "child", "Agent", "running", "m", 0.0, 0.0)
        assertFalse(thread(assistant()).copy(wasStopped = true, subAgents = listOf(child)).canResume())
        assertFalse(thread(assistant("error", "error", "ordinary failure")).canResume())
        assertTrue(thread(assistant("error", "error", "ordinary failure")).copy(subAgents = listOf(child)).canResume())
        assertTrue(thread(assistant("error", error = "Interrupted by session restart")).canResume())
        assertTrue(thread(assistant("error", error = "Response stream ended prematurely before completion")).canResume())
    }
    @Test fun interruptionStreamingPendingAndTrailingUserOrResultsCanResume() {
        for (entry in listOf(assistant("aborted"), assistant(stop = "aborted"), assistant("streaming"), assistant(stop = "pending"),
            assistant(interrupted = true), assistant(null), RemoteEntry("u", "user", "hello"), RemoteEntry("t", "toolResult", "result"))) {
            assertTrue(thread(entry, RemoteEntry("comp", "compaction", "summary")).canResume())
        }
        assertTrue(thread(assistant("aborted"), RemoteEntry("future", "future-kind", "")).canResume())
        assertFalse(thread().canResume())
        assertFalse(thread(RemoteEntry("x", "unknown", "")).canResume())
        assertTrue(thread().copy(wasStopped = true).canResume())
    }
    @Test fun toolsUseNormalizedIdsAndEmptyToolUseIsResumable() {
        val call = RemoteContent.ToolCall("tool|part", "read", JsonNull)
        assertTrue(thread(assistant(calls = listOf(call))).canResume())
        assertTrue(thread(assistant(), RemoteEntry("t", "tool_result", "result")).canResume())
        assertFalse(thread(assistant(calls = listOf(call.copy(id = "|suffix")))).canResume())
        assertTrue(thread(assistant(stop = "toolUse")).canResume())
        assertFalse(thread(assistant()).canResume())
        assertTrue(thread(assistant(calls = listOf(call)), RemoteEntry("t", "toolResult", "result",
            details = RemoteEntryDetails(toolCallId = "tool|result"))).canResume())
    }
    @Test fun busyUnknownAndEditAwaitingStartAlwaysBlockResume() {
        val value = thread(assistant("aborted"))
        assertTrue(value.canResume())
        assertFalse(value.copy(running = true).canResume())
        assertFalse(value.copy(loading = true).canResume())
        assertFalse(value.copy(outcomeUnknown = true).canResume())
        assertFalse(value.copy(editAwaitingStart = true).canResume())
        assertFalse(value.copy(error = RemoteProblem.SessionDeleted).canResume())
    }
}
