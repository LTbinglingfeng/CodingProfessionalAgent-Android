package dev.cpa.android.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RemoteSubAgentsTest {
    private fun record(time: Double = 10.0) = RemoteSubAgent("a", "parent", "child", "Worker", "running", "model", 1.0, time)
    @Test fun snapshotsKeepNewerAndMissingLiveRecordsAndTransientTiming() {
        val current = record().copy(completedAt = 20.0, pausedMs = 3.0)
        val missing = record().copy(id = "missing")
        assertEquals(listOf(current, missing), mergeSubAgents(listOf(current, missing), listOf(record(9.0)), true))
        val newer = mergeSubAgents(listOf(current), listOf(record(11.0).copy(status = "completed")), true).single()
        assertEquals(20.0, newer.completedAt); assertEquals(3.0, newer.pausedMs)
        assertEquals("completed", newer.status)
    }
    @Test fun liveEqualTimestampReplacesAndOlderTimestampIsIgnored() {
        val current = record()
        assertEquals(current, mergeSubAgents(listOf(current), listOf(record(9.0).copy(status = "error")), false).single())
        assertEquals("error", mergeSubAgents(listOf(current), listOf(record().copy(status = "error")), false).single().status)
    }
    @Test fun parserRejectsMissingIdentityAndNonfiniteTimesAndSupportsSessionFallback() {
        val value = Json.parseToJsonElement("""[{"id":"a","parentSessionId":"p","sessionId":"","status":"queued","createdAt":"NaN","updatedAt":12},{"id":"bad"}]""")
        val record = parseSubAgents(value).single()
        assertEquals("a", record.conversationId); assertTrue(record.inProgress)
        assertEquals(0.0, record.createdAt, 0.0)
    }
    @Test fun elapsedUsesCompletionAndSubtractsPauseWithNonnegativeFloor() {
        assertEquals(16.0, record().copy(status = "completed", completedAt = 20.0, pausedMs = 3.0).elapsed(100.0), 0.0)
        assertEquals(96.0, record().copy(pausedMs = 3.0).elapsed(100.0), 0.0)
        assertEquals(0.0, record().elapsed(0.0), 0.0)
    }
}
