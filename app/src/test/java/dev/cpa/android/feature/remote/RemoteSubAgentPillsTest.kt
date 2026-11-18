package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteSubAgentPillsTest {
    private val agent = RemoteSubAgent("a", "p", "child", "Worker", "completed", "m", 0.0, 1.0, parentToolCallId = "spawn|result")
    private fun call(id: String = "spawn|request", name: String = "spawn_agent", args: String = """{"name":"Worker","prompt":"Inspect"}""") = RemoteContent.ToolCall(id, name, Json.parseToJsonElement(args))
    private fun details(raw: String) = mapOf("cell" to RemoteRunningTool(details = Json.parseToJsonElement(raw).jsonObject))
    @Test fun normalizedIdBeatsNameAndTerminalRecordStopsSpinner() {
        val pills = subAgentPills(listOf(call()), emptyMap(), emptyMap(), listOf(agent.copy(name = "Actual")), true)
        assertEquals("Actual", pills.single().name); assertFalse(pills.single().running)
    }
    @Test fun missingRecordOnlyShowsPendingLaunchWithPrompt() {
        assertNull(subAgentPills(listOf(call()), emptyMap(), emptyMap(), emptyList(), true).single().record)
        assertTrue(subAgentPills(listOf(call()), emptyMap(), emptyMap(), emptyList(), false).isEmpty())
        assertTrue(subAgentPills(listOf(call(args = "{}")), emptyMap(), emptyMap(), emptyList(), true).isEmpty())
    }
    @Test fun nestedStringArgumentsExpandAndDeduplicateExplicitCalls() {
        val metadata = details("""{"nestedTools":[{"id":"spawn|nested","name":"delegate_agent","args":"{\"name\":\"Worker\",\"prompt\":\"Inspect\"}","status":"running"}]}""")
        val nested = subAgentPills(listOf(call("cell", "exec")), emptyMap(), metadata, listOf(agent), true)
        assertEquals("a", nested.single().id)
        val both = subAgentPills(listOf(call("cell", "exec"), call()), emptyMap(), metadata, listOf(agent), true)
        assertEquals(1, both.size)
    }
    @Test fun storedMetadataFallbackAndLegacyNameMatchWork() {
        val result = RemoteEntry("r", "toolResult", "", details = RemoteEntryDetails(displayMetadata = Json.parseToJsonElement("""{"nestedTools":[{"id":"spawn","toolName":"subagent","arguments":{"name":"Worker"}}]}""").jsonObject))
        val pills = subAgentPills(listOf(call("cell", "exec")), mapOf("cell" to result), emptyMap(), listOf(agent.copy(parentToolCallId = null)), false)
        assertEquals("a", pills.single().id)
    }
    @Test fun followUpTargetAndQueuedStatusKeepMatchingAgentBusy() {
        val metadata = details("""{"nestedTools":[{"name":"send_input","status":"awaiting_approval","arguments":{"target":"worker"}}]}""")
        assertTrue(subAgentPills(listOf(call()), emptyMap(), metadata, listOf(agent), false).single().running)
        assertFalse(subAgentPills(listOf(call()), emptyMap(), metadata, listOf(agent.copy(name = "Other")), false).single().running)
        val queued = subAgentPills(listOf(call()), emptyMap(), emptyMap(), listOf(agent.copy(status = "queued")), true).single()
        assertTrue(queued.queued); assertTrue(queued.running)
    }
}
