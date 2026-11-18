package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteTurnActivityTest {
    private fun call(id: String, name: String = "read") = RemoteContent.ToolCall(id, name, buildJsonObject { put("path", "src/file.kt") })
    private fun message(id: String, vararg blocks: RemoteContent) = RemoteEntry(id, "assistant", "", details = RemoteEntryDetails(content = blocks.toList()))
    private fun turn(vararg entries: RemoteEntry) = RemoteTurn.Assistant(entries.toList(), emptyMap(), null, live = true)
    private fun nested(id: String, status: String = "running") = buildJsonObject {
        put("nestedTools", buildJsonArray { add(buildJsonObject {
            put("id", id); put("name", "spawn_agent"); put("status", status)
            put("args", "{\"name\":\"Worker\",\"prompt\":\"Inspect\"}")
        }) })
    }
    @Test fun nestedLaunchSplitsCodeFromFollowingToolsAndUsesLiveStatus() {
        val t = turn(message("a", call("exec", "exec")), message("b", call("read")))
        val parts = turnActivity(t, mapOf("exec" to RemoteRunningTool(details = nested("child", "queued"))))
        assertEquals(3, parts.size)
        val child = (parts[1] as TurnActivity.Tools).items.single()
        assertEquals("child", child.call.id)
        assertTrue(child.running)
        assertEquals("Worker", child.call.arguments.jsonObject["name"]?.jsonPrimitive?.content)
    }
    @Test fun explicitIdsAndRepeatedNestedLaunchesAreDeduplicatedWithinSection() {
        val details = mapOf("exec" to RemoteRunningTool(details = nested("child|nested")), "exec2" to RemoteRunningTool(details = nested("child|again")))
        val t = turn(message("a", call("exec", "exec"), call("exec2", "exec")), message("b", call("child|real", "spawn_agent")))
        assertEquals(1, turnActivity(t, details).filterIsInstance<TurnActivity.Tools>().flatMap { it.items }.count { isAgentLaunch(it.call.name) })
        val withoutExplicit = t.copy(items = t.items.take(1))
        assertEquals(1, turnActivity(withoutExplicit, details).filterIsInstance<TurnActivity.Tools>().flatMap { it.items }.count { isAgentLaunch(it.call.name) })
    }
    @Test fun storedMetadataFallbackDoesNotReviveFinishedNestedLaunch() {
        val result = RemoteEntry("r", "toolResult", "", details = RemoteEntryDetails(displayMetadata = nested("child", "done")))
        val t = turn(message("a", call("exec", "exec"))).copy(results = mapOf("a" to mapOf("exec" to result)))
        val child = (turnActivity(t, mapOf("exec" to RemoteRunningTool(details = buildJsonObject { put("nestedTools", JsonArray(emptyList())) }))).last() as TurnActivity.Tools).items.single()
        assertFalse(child.running)
        assertTrue(child.live)
        assertTrue(subAgentPills(listOf(child.call), emptyMap(), emptyMap(), emptyList(), true, launchStates = mapOf("child" to child.running), expandNested = false).isEmpty())
    }
    @Test fun stagesMergeAcrossThinkingAndKeepDuplicateIdResultsOwned() {
        val a = message("a", call("same")); val b = message("b", RemoteContent.Thinking("hidden"), call("same"))
        val r1 = RemoteEntry("r1", "toolResult", "first"); val r2 = RemoteEntry("r2", "toolResult", "second")
        val t = turn(a, b).copy(results = mapOf("a" to mapOf("same" to r1), "b" to mapOf("same" to r2)))
        val group = turnActivity(t).single() as TurnActivity.Tools
        assertEquals(listOf("a", "b"), group.items.map { it.owner })
        assertEquals(listOf(r1, r2), group.items.map { it.result })
    }
    @Test fun textCompactionAndSpawnKindsSeparateGroups() {
        val t = turn(message("a", call("1")), message("b", RemoteContent.Text("reply"), call("2")),
            RemoteEntry("compact", "compaction", ""), message("c", call("3"), call("4", "spawn_agent"), call("5")))
        val parts = turnActivity(t)
        assertEquals(7, parts.size)
        assertTrue(parts[1] is TurnActivity.Text)
        assertTrue(parts[3] is TurnActivity.Compaction)
        assertFalse((parts[0] as TurnActivity.Tools).items.single().live)
        assertTrue((parts[4] as TurnActivity.Tools).items.single().live)
    }
    @Test fun onlyThinkingAndSkillReadsHaveNoVisibleLiveActivity() {
        val skill = RemoteContent.ToolCall("skill", "read", buildJsonObject { put("path", "/x/SKILL.md") })
        assertFalse(turn(message("a", RemoteContent.Thinking("hidden"), skill)).hasVisibleActivity)
        assertTrue(turn(message("a", skill, RemoteContent.Text("reply"))).hasVisibleActivity)
        assertTrue(turn(message("a", call("spawn", "spawn_agent"))).hasVisibleActivity)
        assertTrue(turn(RemoteEntry("old", "assistant", "legacy text")).hasVisibleActivity)
    }
}
