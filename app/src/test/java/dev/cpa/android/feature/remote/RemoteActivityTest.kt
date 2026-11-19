package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteActivityTest {
    private fun entry(id: String, kind: String, content: String, toolId: String? = null): RemoteEntry = parseRemoteEntry(buildJsonObject {
        put("id", id); put("kind", kind); put("content", Json.parseToJsonElement(content)); toolId?.let { put("toolCallId", it) }
    })!!
    @Test fun thinkingNeverRendersOrSplitsConsecutiveToolsAndTextKeepsItsPlace() {
        val assistant = entry("a", "assistant", """[{"type":"thinking","thinking":"hidden"},{"type":"text","text":"before"},{"type":"toolCall","id":"one","name":"shell"},{"type":"thinking","thinking":"also hidden"},{"type":"toolCall","id":"two","name":"read"},{"type":"text","text":"after"}]""")
        val parts = activityParts(assistant)
        assertEquals(ActivityPart.Text("before"), parts[0])
        assertEquals(listOf("one", "two"), (parts[1] as ActivityPart.Tools).calls.map { it.id })
        assertEquals(ActivityPart.Text("after"), parts[2]); assertEquals(3, parts.size)
    }
    @Test fun resultsBelongOnlyToAdjacentAssistantAndFirstNormalizedResultWins() {
        val a = entry("a", "assistant", """[{"type":"toolCall","id":"call|original","name":"shell"}]""")
        val r = entry("r", "toolResult", "[]", "call|result")
        val duplicate = r.copy(id = "duplicate")
        val user = RemoteEntry("u", "user", "next")
        val orphan = r.copy(id = "orphan")
        val projection = activityProjection(listOf(a, r, duplicate, user, orphan))
        assertEquals(listOf("a", "u", "orphan"), projection.entries.map { it.id })
        assertEquals("r", projection.results["a"]!!["call"]!!.id)
    }
}
