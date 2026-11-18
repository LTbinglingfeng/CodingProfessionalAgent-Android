package dev.cpa.android.data.repository

import dev.cpa.android.core.network.*
import dev.cpa.android.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCapabilitiesRepositoryTest {
    private class Vault : ConnectionVault {
        override suspend fun load() = listOf(StoredConnection("c", "Desktop", "https://desktop.example/", "", false))
        override suspend fun upsert(connection: StoredConnection) = Unit
        override suspend fun remove(id: String) = Unit
    }
    private class Wire : DesktopTransport {
        lateinit var listener: DesktopTransport.Listener
        val calls = mutableListOf<JsonObject>()
        var reject = false
        var sessions = """[{"id":"s","title":"Session","modelId":"m","reasoningEffort":"medium","speed":"standard","updatedAt":123}]"""
        var projects = "[]"
        var snapshot = """{"entries":[]}"""
        var hold = false
        var models = """[{"id":"m","supportsFast":true,"reasoningLevels":[{"id":"medium"},{"id":"high"},{"id":"ultra"}]}]"""
        override suspend fun authenticate(password: String) = Unit
        override fun close() = Unit
        override fun openSocket(listener: DesktopTransport.Listener): DesktopTransport.Socket {
            this.listener = listener
            listener.onText("""{"type":"hello","clientId":"test"}""")
            return object : DesktopTransport.Socket {
                override fun close() = Unit
                override fun send(text: String): Boolean {
                    val call = Json.parseToJsonElement(text).jsonObject
                    calls += call
                    val method = call["method"]!!.jsonPrimitive.content
                    val result = when (method) {
                        "session:search" -> """[{"sessionId":"s","title":"Found","matchType":"content","snippet":"text","updatedAt":12}]"""
                        "session:getActiveRuns" -> "[]"
                        "session:listSessions" -> sessions
                        "session:get" -> snapshot
                        "kvstore:get" -> when (call["args"]!!.jsonArray[0].jsonPrimitive.content) {
                            "projects" -> projects
                            "cachedModels" -> models
                            "app-state" -> """{"settings":{"modelId":"m","speed":"standard"}}"""
                            else -> "[]"
                        }
                        else -> "true"
                    }
                    if (!hold) listener.onText(if (reject) """{"type":"rpc_error","id":${call["id"]},"error":"denied"}"""
                        else """{"type":"rpc_result","id":${call["id"]},"result":$result}""")
                    return true
                }
            }
        }
        fun event(kind: String, data: String) = listener.onText(buildJsonObject {
            put("type", "event"); put("event", buildJsonObject { put("kind", kind); put("data", data) })
        }.toString())
        fun lastArgs() = calls.last()["args"]!!.jsonArray
    }
    @Test fun configuredCreationProjectsMetadataBeforeFirstSendWithoutBroadcast() = runTest {
        val wire = Wire().apply { projects = """[{"id":"p","name":"Project"}]""" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val id = java.util.UUID.randomUUID().toString()
        repo.createConfiguredSession("c", id, "New", RemoteNewSessionOptions("p", "feature", "worktree", "m", "high", "fast"))
        val meta = wire.lastArgs().single().jsonObject
        assertEquals(JsonPrimitive("worktree"), meta["workLocation"])
        assertEquals(JsonPrimitive("feature"), meta["branch"])
        repo.send("c", id, "first prompt")
        val request = wire.lastArgs().single().jsonObject
        assertEquals(JsonPrimitive("p"), request["projectId"])
        assertEquals(JsonPrimitive("feature"), request["branch"])
        assertEquals(JsonPrimitive("m"), request["modelId"])
        assertEquals(JsonPrimitive("high"), request["reasoningEffort"])
        assertEquals(JsonPrimitive("fast"), request["speed"])
        val count = wire.calls.size
        assertTrue(runCatching { repo.createConfiguredSession("c", id, "Duplicate", RemoteNewSessionOptions()) }.isFailure)
        assertEquals(count, wire.calls.size)
    }
    @Test fun configuredCreationValidatesCapabilitiesAndNormalizesUnsupportedFast() = runTest {
        val wire = Wire().apply { models = """[{"id":"plain","reasoningLevels":[]}]""" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val id = java.util.UUID.randomUUID().toString()
        val count = wire.calls.size
        assertTrue(runCatching { repo.createConfiguredSession("c", id, "Invalid", RemoteNewSessionOptions(modelId = "missing")) }.isFailure)
        assertTrue(runCatching { repo.createConfiguredSession("c", id, "Invalid", RemoteNewSessionOptions(workLocation = "invalid")) }.isFailure)
        assertEquals(count, wire.calls.size)
        repo.createConfiguredSession("c", id, "Plain", RemoteNewSessionOptions(modelId = "plain", speed = "fast"))
        assertEquals(JsonPrimitive("standard"), wire.lastArgs().single().jsonObject["speed"])
        assertEquals(JsonPrimitive("off"), wire.lastArgs().single().jsonObject["reasoningEffort"])
    }
    @Test fun searchUsesBoundedDesktopProtocolAndKeepsConnectionIdentity() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val result = repo.searchSessions("c", " text ").single()
        assertEquals(Json.parseToJsonElement("""["text",30]"""), wire.lastArgs())
        assertEquals(RemoteSearchResult("c", "s", "Found", 12, false, "text"), result)
    }
    @Test fun runActionsUseEmptyPromptAndRejectRunningOrUnknownSessions() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.snapshot = """{"entries":[{"id":"u","kind":"user","content":[]}]}"""
        repo.openThread("c", "s")
        for (action in RemoteRunAction.entries) {
            repo.runAction("c", "s", action)
            val call = wire.calls.last { it["method"] == JsonPrimitive("session:delegateRun") }
            val request = call["args"]!!.jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(action.wireValue), request["action"])
            assertEquals(JsonPrimitive(""), request["text"])
            assertEquals(JsonPrimitive("s"), request["sessionId"])
            assertFalse(request.containsKey("userEntryId"))
            assertTrue(request["requestId"]!!.jsonPrimitive.content.startsWith(action.wireValue + "-"))
        }
        wire.event("session:run-status", """{"sessionId":"s","runId":"r","status":"running"}"""); runCurrent()
        val count = wire.calls.size
        assertTrue(runCatching { repo.runAction("c", "s", RemoteRunAction.Resume) }.isFailure)
        assertEquals(count, wire.calls.size)
        wire.event("session:run-status", """{"sessionId":"s","runId":"r","status":"idle"}"""); runCurrent()
        wire.hold = true
        val pending = launch { runCatching { repo.runAction("c", "s", RemoteRunAction.Compact) } }
        runCurrent(); pending.cancel(); runCurrent()
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        wire.hold = false
        val after = wire.calls.size
        assertTrue(runCatching { repo.runAction("c", "s", RemoteRunAction.Resume) }.isFailure)
        assertEquals(after, wire.calls.size)
    }
    @Test fun resumeRejectsEmptyDoneAndOrdinaryErrorsWithoutSendingAndAcceptsInterrupted() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        for (snapshot in listOf("""{"entries":[]}""", """{"entries":[{"id":"a","kind":"assistant","status":"done"}]}""",
            """{"entries":[{"id":"a","kind":"assistant","status":"error","errorMessage":"ordinary"}]}""")) {
            wire.snapshot = snapshot; repo.openThread("c", "s")
            val count = wire.calls.size
            assertTrue(runCatching { repo.runAction("c", "s", RemoteRunAction.Resume) }.isFailure)
            assertEquals(count, wire.calls.size)
        }
        wire.snapshot = """{"entries":[{"id":"a","kind":"assistant","status":"aborted"}]}"""
        repo.openThread("c", "s"); repo.runAction("c", "s", RemoteRunAction.Resume)
        assertEquals(1, wire.calls.count { it["method"] == JsonPrimitive("session:delegateRun") })
        repo.openThread("c", "child")
        val count = wire.calls.size
        assertTrue(runCatching { repo.runAction("c", "child", RemoteRunAction.Resume) }.isFailure)
        assertEquals(count, wire.calls.size)
    }
    @Test fun stopConfirmationSetsFallbackAndNewRunClearsIt() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.event("session:run-status", """{"sessionId":"s","runId":"r1","status":"running"}"""); runCurrent()
        repo.stop("c", "s")
        assertTrue(repo.observeThread("c", "s").value.wasStopped)
        wire.event("session:run-status", """{"sessionId":"s","runId":"r1","status":"idle"}"""); runCurrent()
        assertTrue(repo.observeThread("c", "s").value.canResume())
        wire.event("session:run-status", """{"sessionId":"s","runId":"r2","status":"running"}"""); runCurrent()
        assertFalse(repo.observeThread("c", "s").value.wasStopped)
        assertFalse(repo.observeThread("c", "s").value.canResume())
    }
    @Test fun runtimeAndMetadataUseDesktopWireArguments() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        assertEquals(listOf("medium", "high"), repo.capabilities.value["c"]!!.reasoningChoices("m").map { it.id })
        repo.setSessionRuntime("c", "s", reasoningEffort = "high")
        assertEquals(Json.parseToJsonElement("""[{"id":"s","reasoningEffort":"high"}]"""), wire.lastArgs())
        repo.send("c", "s", "hello")
        val request = wire.lastArgs()[0].jsonObject
        assertEquals(JsonPrimitive("m"), request["modelId"])
        assertEquals(JsonPrimitive("high"), request["reasoningEffort"])
        assertEquals(JsonPrimitive("standard"), request["speed"])
        repo.moveSession("c", "s", null)
        assertEquals(JsonNull, wire.lastArgs()[0].jsonObject["projectId"])
        repo.setSessionUnread("c", "s", true)
        assertEquals(JsonPrimitive(123), wire.lastArgs()[0].jsonObject["updatedAt"])
        assertEquals(JsonPrimitive(true), wire.lastArgs()[0].jsonObject["unread"])
        wire.event("session:run-status", """{"sessionId":"s","runId":"r","status":"running"}"""); runCurrent()
        val count = wire.calls.size
        assertTrue(runCatching { repo.setSessionRuntime("c", "s", speed = "fast") }.isFailure)
        assertTrue(runCatching { repo.setSessionRuntime("c", "s", modelId = "other") }.isFailure)
        assertTrue(runCatching { repo.setSessionRuntime("c", "s", reasoningEffort = "ultra") }.isFailure)
        assertEquals(count, wire.calls.size)
        repo.setSessionRuntime("c", "s", reasoningEffort = "high")
    }
    @Test fun modelWithoutReasoningResetsToOffAndFastFallsBack() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        // Replace the capabilities fixture via a complete refresh, preserving wire-level behavior.
        wire.models = """[{"id":"plain","reasoningLevels":[]}]"""
        repo.refresh("c")
        repo.setSessionRuntime("c", "s", modelId = "plain")
        val patch = wire.lastArgs()[0].jsonObject
        assertEquals(JsonPrimitive("off"), patch["reasoningEffort"])
        assertEquals(JsonPrimitive("standard"), patch["speed"])
        repo.send("c", "s", "hello")
        assertEquals(JsonPrimitive("plain"), wire.lastArgs()[0].jsonObject["modelId"])
        assertEquals(JsonPrimitive("off"), wire.lastArgs()[0].jsonObject["reasoningEffort"])
    }
    @Test fun emptyLegacySessionPreferencesUseDesktopDefaults() = runTest {
        val wire = Wire().apply { sessions = """[{"id":"s","title":"Legacy","modelId":"","reasoningEffort":"","speed":""}]""" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        repo.send("c", "s", "hello")
        val payload = wire.lastArgs()[0].jsonObject
        assertEquals(JsonPrimitive("m"), payload["modelId"])
        assertEquals(JsonPrimitive("medium"), payload["reasoningEffort"])
        assertEquals(JsonPrimitive("standard"), payload["speed"])
    }
    @Test fun successfulMetadataMutationsProjectWithoutWaitingForBroadcast() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        repo.renameSession("c", "s", " Renamed ")
        repo.setSessionPinned("c", "s", true)
        repo.moveSession("c", "s", "project")
        repo.setSessionUnread("c", "s", true)
        val session = repo.sessions.value["c"]!!.single()
        assertEquals("Renamed", session.title)
        assertTrue(session.pinned)
        assertEquals("project", session.projectId)
        assertEquals("unread", session.unread)
        repo.archiveSession("c", "s")
        assertTrue(repo.sessions.value["c"]!!.isEmpty())
    }
    @Test fun newerMetadataEventWinsOverLateMutationConfirmation() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.hold = true
        val rename = async { repo.renameSession("c", "s", "My name") }
        runCurrent()
        val request = wire.calls.last()
        wire.event("session:meta-updated", """{"id":"s","title":"Newer desktop name"}""")
        runCurrent()
        wire.listener.onText("""{"type":"rpc_result","id":${request["id"]},"result":null}""")
        runCurrent(); rename.await()
        assertEquals("Newer desktop name", repo.sessions.value["c"]!!.single().title)
    }
    @Test fun askReadCannotReturnAcrossGenerationAndWritesAreNeverReplayed() = runTest {
        val wires = mutableListOf<Wire>()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { Wire().also(wires::add) }
        runCurrent(); repo.onForeground(); runCurrent()
        val old = wires.single()
        old.hold = true
        val reading = async { runCatching { repo.callSessionRpc("c", "s", "ask:getRequests", listOf(buildJsonObject { put("sessionId", "s") })) } }
        runCurrent()
        val writing = async { runCatching { repo.renameSession("c", "s", "Once") } }
        runCurrent(); repo.onBackground(); runCurrent()
        assertTrue(reading.await().isFailure)
        assertTrue(writing.await().isFailure)
        repo.onForeground(); runCurrent()
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        assertFalse(wires.last().calls.any { it["method"] == JsonPrimitive("session:setMeta") })
    }
    @Test fun askAllowlistFailureAndUnknownGuard() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val args = listOf(JsonPrimitive("s"), JsonPrimitive("tool"))
        assertTrue(runCatching { repo.callSessionRpc("c", "s", "ask:cancelCountdown", args) }.isFailure)
        assertTrue(runCatching { repo.callSessionRpc("c", "s", "session:setMeta", args, true) }.isFailure)
        assertEquals(JsonPrimitive(true), repo.callSessionRpc("c", "s", "ask:cancelCountdown", args, true))
        assertEquals(JsonArray(args), wire.lastArgs())
        wire.reject = true
        assertTrue(runCatching { repo.renameSession("c", "s", "New") }.isFailure)
        assertFalse(repo.observeThread("c", "s").value.outcomeUnknown)
        wire.reject = false; wire.hold = true
        val pending = launch { runCatching { repo.callSessionRpc("c", "s", "ask:cancelCountdown", args, true) } }
        runCurrent(); pending.cancel(); runCurrent()
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        wire.hold = false
        val count = wire.calls.size
        assertTrue(runCatching { repo.callSessionRpc("c", "s", "ask:cancelCountdown", args, true) }.isFailure)
        assertEquals(count, wire.calls.size)
    }
}
