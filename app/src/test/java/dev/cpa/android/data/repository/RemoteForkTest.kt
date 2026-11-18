package dev.cpa.android.data.repository

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteForkTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private val source = obj("""{"id":"old","title":"Task","projectId":"p","branch":"main","baseBranch":"base","workLocation":"local","worktreePath":"/old","environmentId":"env","worktreeSetup":{"ok":true},"modelId":"m","reasoningEffort":"high","speed":"fast","firstPromptAt":12,"archivedAt":99,"pinned":true,"parentSessionId":"parent"}""")
    private val snapshot = obj("""{"entries":[{"id":"u","kind":"user","sessionId":"old","content":[{"type":"text","text":"prompt"}]},{"id":"a","kind":"assistant","status":"streaming","content":[{"id":"block","type":"toolCall","name":"run","input":{"nested":true}}]},{"id":"t","kind":"toolResult","content":{"output":"full output"}},{"id":"u2","kind":"user","content":[]}] }""")
    @Test fun forkCopiesRawEntriesAndNormalizesOnlyEntryIdentityAndStreamingStatus() {
        val payload = buildForkPayload(source, snapshot, "new", null, null, 100)
        val entries = payload.session["entries"]!!.jsonArray
        assertEquals(4, entries.size)
        assertEquals(snapshot["entries"]!!.jsonArray[1].jsonObject["content"], entries[1].jsonObject["content"])
        assertEquals(JsonPrimitive("done"), entries[1].jsonObject["status"])
        assertTrue(entries.all { it.jsonObject["sessionId"] == JsonPrimitive("new") })
        assertEquals(4, entries.map { it.jsonObject["id"] }.toSet().size)
        assertFalse(entries.any { it.jsonObject["id"] == JsonPrimitive("a") })
        assertEquals(JsonPrimitive("Task (Fork)"), payload.meta["title"])
        assertEquals(JsonPrimitive(false), payload.meta["pinned"])
        assertFalse(payload.meta.containsKey("archivedAt")); assertFalse(payload.meta.containsKey("parentSessionId"))
        assertEquals(JsonPrimitive(12), payload.meta["firstPromptAt"])
        assertEquals(JsonPrimitive("fast"), payload.meta["speed"])
        assertEquals(JsonPrimitive("/old"), payload.meta["worktreePath"])
    }
    @Test fun messageBoundaryIncludesTrailingToolResultsAndRecognizesContentBlockId() {
        for (boundary in listOf("a", "block")) {
            assertEquals(3, buildForkPayload(source, snapshot, "new", boundary, null).session["entries"]!!.jsonArray.size)
        }
        assertEquals(1, buildForkPayload(source, snapshot, "new", "u", null).session["entries"]!!.jsonArray.size)
        assertEquals(4, buildForkPayload(source, snapshot, "new", "unknown", null).session["entries"]!!.jsonArray.size)
    }
    @Test fun forkIncludesConsecutiveToolResultsOfEitherSpelling() {
        val mixed = obj("""{"entries":[{"id":"a","kind":"assistant"},{"kind":"tool_result"},{"kind":"toolResult"},{"kind":"user"}]}""")
        val entries = buildForkPayload(source, mixed, "new", "a", null).session.getValue("entries").jsonArray
        assertEquals(listOf("assistant", "tool_result", "toolResult"), entries.map { it.jsonObject.getValue("kind").jsonPrimitive.content })
    }
    @Test fun worktreeForkDropsOldPathSetupAndExplicitlyClearsEnvironment() {
        val payload = buildForkPayload(source, snapshot, "new", null, RemoteWorktreeFork("feature", null))
        assertEquals(JsonPrimitive("worktree"), payload.meta["workLocation"])
        assertEquals(JsonPrimitive("feature"), payload.meta["branch"])
        assertFalse(payload.meta.containsKey("environmentId"))
        assertFalse(payload.meta.containsKey("worktreePath")); assertFalse(payload.meta.containsKey("worktreeSetup"))
        val ordinary = buildForkPayload(JsonObject(source + ("workLocation" to JsonPrimitive("worktree"))), snapshot, "new", null, null)
        assertFalse(ordinary.meta.containsKey("worktreePath")); assertFalse(ordinary.meta.containsKey("worktreeSetup"))
        assertEquals(JsonPrimitive("env"), ordinary.meta["environmentId"])
    }
    @Test fun forkTitleIsNotRepeatedAndMissingSnapshotFails() {
        val forked = JsonObject(source + ("title" to JsonPrimitive("Task (Fork)")))
        assertEquals(JsonPrimitive("Task (Fork)"), buildForkPayload(forked, snapshot, "new", null, null).meta["title"])
        assertTrue(runCatching { buildForkPayload(source, obj("{}"), "new", null, null) }.isFailure)
    }
    @Test fun gitBranchesIgnoreDetachedHeadAndKeepCurrentMarker() {
        assertEquals(RemoteBranches("main", listOf("dev", "main")), parseBranches(" dev\n*main\n (HEAD detached at abc)\n\n"))
    }
}
