package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class RemoteTurnsTest {
    private fun entry(id: String, kind: String, time: Double = 1000.0) = RemoteEntry(id, kind, id, details = RemoteEntryDetails(createdAt = time))
    @Test fun stagesAndCompactionMergeWhileQueuedPromptKeepsPriorTurnLive() {
        val entries = listOf(entry("u", "user"), entry("a", "assistant"), entry("c", "compaction"), entry("b", "assistant"), entry("q", "user").copy(pendingStatus = "queue"))
        val turns = remoteTurns(entries, true)
        assertEquals(3, turns.size)
        val turn = turns[1] as RemoteTurn.Assistant
        assertTrue(turn.live); assertEquals(listOf("a", "c", "b"), turn.items.map { it.id })
        assertEquals("b", turn.trailingText)
    }
    @Test fun newStartedPromptGetsEmptyRunningTurnInsteadOfRevivingPreviousAnswer() {
        val turns = remoteTurns(listOf(entry("u", "user"), entry("a", "assistant"), entry("v", "user", 3000.0)), true)
        assertFalse((turns[1] as RemoteTurn.Assistant).live)
        val pending = turns.last() as RemoteTurn.Assistant
        assertTrue(pending.live); assertTrue(pending.items.isEmpty()); assertEquals(3000.0, pending.startedAt)
    }
    @Test fun completedDurationUsesLatestToolTimeAndRequestPause() {
        val user = entry("u", "user").copy(details = RemoteEntryDetails(createdAt = 1000.0, pausedMs = 2000.0))
        val assistant = entry("a", "assistant").copy(details = RemoteEntryDetails(content = listOf(RemoteContent.ToolCall("call", "read", kotlinx.serialization.json.JsonNull)), completedAt = 4000.0))
        val result = entry("r", "toolResult").copy(details = RemoteEntryDetails(createdAt = 7000.0, toolCallId = "call|suffix"))
        val turn = remoteTurns(listOf(user, assistant, result, result.copy(id = "duplicate", details = result.details.copy(createdAt = 8000.0))), false).last() as RemoteTurn.Assistant
        assertEquals(5000.0, turn.elapsed(9000.0), 0.0)
        assertEquals("done", turn.status)
        assertEquals("5s", formatTurnDuration(turn.elapsed(9000.0)))
    }
    @Test fun finalReplyUsesTextAfterLastThinkingOrToolAndFallsBackToLastText() {
        val blocks = listOf(RemoteContent.Text("early"), RemoteContent.Thinking("hidden"), RemoteContent.Text("final"), RemoteContent.Text(" reply"))
        val message = entry("a", "assistant").copy(details = RemoteEntryDetails(content = blocks))
        assertEquals("final reply", (remoteTurns(listOf(message), false).single() as RemoteTurn.Assistant).trailingText)
        assertEquals(" reply", (remoteTurns(listOf(message.copy(details = message.details.copy(content = blocks + RemoteContent.Thinking("tail")))), false).single() as RemoteTurn.Assistant).trailingText)
    }
    @Test fun standaloneCompactionOrphanResultsAndErrorArePreserved() {
        val turns = remoteTurns(listOf(entry("c", "compaction"), entry("r", "toolResult"), entry("a", "assistant").copy(details = RemoteEntryDetails(status = "error", errorMessage = "failed"))), false)
        assertEquals(3, turns.size)
        assertTrue(turns.first() is RemoteTurn.Entry)
        assertEquals("error", (turns.last() as RemoteTurn.Assistant).status)
        assertEquals("failed", (turns.last() as RemoteTurn.Assistant).error)
    }
    @Test fun durationFormattingClampsInvalidValuesAndIncludesDays() {
        assertEquals("0s", formatTurnDuration(Double.NaN))
        assertEquals("0s", formatTurnDuration(-1.0))
        assertEquals("1d 1h 1m 1s", formatTurnDuration(90061000.0))
    }
}
