package dev.cpa.android.data.repository

import dev.cpa.android.core.attachments.*
import dev.cpa.android.core.network.*
import dev.cpa.android.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAttachmentRepositoryTest {
    private class Vault : ConnectionVault {
        override suspend fun load() = listOf(StoredConnection("c", "Desktop", "https://desktop.example/", "", false))
        override suspend fun upsert(connection: StoredConnection) = Unit
        override suspend fun remove(id: String) = Unit
    }
    private class Wire : DesktopTransport {
        lateinit var listener: DesktopTransport.Listener
        val calls = mutableListOf<JsonObject>()
        val written = linkedMapOf<String, String>()
        val existing = mutableSetOf("/home/.cpa/attachments/s/report.txt")
        var snapshot: String? = null
        var holdSnapshot = false
        val snapshotSent = CompletableDeferred<JsonElement>()
        fun stream(event: String) = listener.onText(buildJsonObject {
            put("type", "event"); put("event", buildJsonObject {
                put("kind", "session:stream-event"); put("data", """{"sessionId":"s","runId":"r","event":$event}""")
            })
        }.toString())
        var running = false
        var rejectSend = false
        var holdSend = false
        val sendSent = CompletableDeferred<Unit>()
        var supportsImages = true
        var holdWrite = false
        val writeSent = CompletableDeferred<Unit>()
        override suspend fun authenticate(password: String) = Unit
        override fun close() = Unit
        override fun openSocket(listener: DesktopTransport.Listener): DesktopTransport.Socket {
            this.listener = listener
            listener.onText("""{"type":"hello","clientId":"test"}""")
            return object : DesktopTransport.Socket {
                override fun close() = Unit
                override fun send(text: String): Boolean {
                    val call = Json.parseToJsonElement(text).jsonObject; calls += call
                    val args = call["args"]!!.jsonArray
                    val result = when (call["method"]!!.jsonPrimitive.content) {
                        "session:getActiveRuns" -> if (running) """[{"sessionId":"s","runId":"r","status":"running"}]""" else "[]"
                        "session:listSessions" -> """[{"id":"s","modelId":"m"}]"""
                        "session:get" -> if (holdSnapshot) { snapshotSent.complete(call.getValue("id")); return true } else snapshot ?: """{"entries":[{"id":"u","kind":"user","content":[{"type":"image","mimeType":"image/png","data":"YWJj"}]}]}"""
                        "kvstore:get" -> when (args[0]) {
                            JsonPrimitive("cachedModels") -> """[{"id":"m","input":${if (supportsImages) "[\"text\",\"image\"]" else "[\"text\"]"}}]"""
                            else -> "{}"
                        }
                        "session:delegateRun" -> { if (rejectSend) {
                            listener.onText("""{"type":"rpc_error","id":${call["id"]},"error":"rejected"}""")
                            return true
                        }; if (holdSend) { sendSent.complete(Unit); return true }; "null" }
                        "native:runtimeInfo" -> """{"homeDir":"/home","appConfigDirName":".cpa"}"""
                        "native:fileExists" -> (args[0].jsonPrimitive.content in existing).toString()
                        "native:writeFile" -> {
                            written[args[0].jsonPrimitive.content] = args[1].jsonPrimitive.content
                            existing += args[0].jsonPrimitive.content
                            if (holdWrite) { writeSent.complete(Unit); return true }
                            "null"
                        }
                        else -> "true"
                    }
                    listener.onText("""{"type":"rpc_result","id":${call["id"]},"result":$result}""")
                    return true
                }
            }
        }
    }
    @Test fun toolStateFollowsRunIdentityAndDisplayUpdatesReachStoredResults() = runTest {
        val wire = Wire()
        wire.snapshot = """{"entries":[{"id":"result","kind":"toolResult","toolCallId":"call|result","summary":"done"}]}"""
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        fun event(kind: String, data: JsonObject) = wire.listener.onText(buildJsonObject {
            put("type", "event"); put("event", buildJsonObject { put("kind", kind); put("data", data.toString()) })
        }.toString())
        fun status(run: String, value: String) = event("session:run-status", buildJsonObject {
            put("sessionId", "s"); put("runId", run); put("status", value)
        })
        fun stream(run: String, type: String) = event("session:stream-event", buildJsonObject {
            put("sessionId", "s"); put("runId", run); put("event", buildJsonObject {
                put("type", type); put("toolCallId", "call|event"); put("toolName", "read_file")
                put("displayMetadata", buildJsonObject { put("summary", "updated") })
            })
        })
        status("r1", "running"); stream("r1", "tool-start"); runCurrent()
        assertEquals(setOf("call"), repo.observeThread("c", "s").value.runningTools.keys)
        stream("r1", "tool-display-update"); runCurrent()
        assertEquals(JsonPrimitive("updated"), repo.observeThread("c", "s").value.entries.single().details.displayMetadata?.get("summary"))
        status("r2", "running"); runCurrent()
        assertTrue(repo.observeThread("c", "s").value.runningTools.isEmpty())
        stream("r1", "tool-start"); runCurrent()
        assertTrue(repo.observeThread("c", "s").value.runningTools.isEmpty())
        stream("r2", "tool-start"); runCurrent()
        assertEquals(1, repo.observeThread("c", "s").value.runningTools.size)
        status("r2", "idle"); runCurrent()
        assertTrue(repo.observeThread("c", "s").value.runningTools.isEmpty())
    }
    @Test fun subAgentEventsBeatStaleSnapshotAndTerminalStateReloadsChild() = runTest {
        val wire = Wire()
        fun agent(time: Int, status: String) = """{"id":"agent","parentSessionId":"s","sessionId":"child","name":"Worker","status":"$status","createdAt":1,"updatedAt":$time}"""
        wire.snapshot = """{"entries":[],"subAgents":[${agent(1, "queued")}]}"""
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s"); repo.openThread("c", "child")
        fun event(time: Int, status: String) {
            val data = """{"agents":[${agent(time, status)}]}"""
            wire.listener.onText(buildJsonObject { put("type", "event"); put("event", buildJsonObject {
                put("kind", "session:subagent-state"); put("data", data)
            }) }.toString())
        }
        event(2, "running"); runCurrent()
        assertTrue(repo.observeThread("c", "child").value.running)
        repo.openThread("c", "s")
        assertEquals("running", repo.observeThread("c", "s").value.subAgents.single().status)
        event(1, "queued"); runCurrent()
        assertEquals("running", repo.observeThread("c", "s").value.subAgents.single().status)
        val reads = wire.calls.count { it["method"] == JsonPrimitive("session:get") }
        event(3, "completed"); runCurrent()
        assertFalse(repo.observeThread("c", "child").value.running)
        advanceTimeBy(801); runCurrent()
        assertTrue(wire.calls.count { it["method"] == JsonPrimitive("session:get") } > reads)
        assertTrue(repo.observeThread("c", "child").value.subAgents.isEmpty())
    }
    private fun attachment(file: File, image: Boolean = false, name: String = "report.txt") = LocalAttachment("a", name, if (image) "image/png" else "text/plain", image, file.path, file.length())
    @Test fun consecutiveEditsAndLateStartKeepEveryEarlierTruncationAcrossStaleSnapshots() = runTest {
        val wire = Wire(); wire.snapshot = """{"entries":[{"id":"first","kind":"user","summary":"one"},{"id":"second","kind":"user","summary":"two"},{"id":"answer","kind":"assistant","summary":"old"}]}"""
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        fun event(kind: String, data: JsonObject) = wire.listener.onText(buildJsonObject {
            put("type", "event"); put("event", buildJsonObject { put("kind", kind); put("data", data.toString()) })
        }.toString())
        fun run(id: String, status: String) = event("session:run-status", buildJsonObject { put("sessionId", "s"); put("runId", id); put("status", status) })
        fun started(id: String, runId: String, text: String) {
            run(runId, "running")
            event("session:stream-event", buildJsonObject {
                put("sessionId", "s"); put("runId", runId); put("event", buildJsonObject {
                    put("type", "user-entry"); put("entry", buildJsonObject { put("id", id); put("kind", "user"); put("summary", text) })
                })
            })
        }
        val first = async { repo.editMessage("c", "s", "second", "second revised", emptyList()) }; runCurrent()
        started("second", "r1", "second revised"); runCurrent(); first.await()
        run("r1", "idle"); runCurrent()
        val second = async { runCatching { repo.editMessage("c", "s", "first", "first revised", emptyList()) } }; runCurrent()
        repo.openThread("c", "s")
        assertEquals(listOf("first", "second"), repo.observeThread("c", "s").value.entries.map { it.id })
        assertEquals("second revised", repo.observeThread("c", "s").value.entries.last().text)
        advanceTimeBy(20_001); runCurrent(); assertTrue(second.await().isFailure)
        started("first", "r2", "first revised"); runCurrent()
        repo.openThread("c", "s")
        assertEquals(listOf("first"), repo.observeThread("c", "s").value.entries.map { it.id })
        assertEquals("first revised", repo.observeThread("c", "s").value.entries.single().text)
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        started("first", "r3", "desktop revised"); runCurrent(); repo.openThread("c", "s")
        assertEquals("desktop revised", repo.observeThread("c", "s").value.entries.single().text)
    }
    @Test fun editAcknowledgementWaitsForMatchingStartAndOldSnapshotsCannotRestoreDroppedHistory() = runTest {
        val wire = Wire()
        wire.snapshot = """{"entries":[{"id":"u","kind":"user","summary":"before"},{"id":"a","kind":"assistant","summary":"old answer"},{"id":"later","kind":"user","summary":"later question"}]}"""
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        val editing = async { repo.editMessage("c", "s", "u", "\$review revised", listOf(SkillReference(0, "review"))) }
        runCurrent()
        assertFalse(editing.isCompleted)
        assertTrue(repo.observeThread("c", "s").value.editAwaitingStart)
        val payload = wire.calls.last()["args"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("u"), payload["editMessageId"])
        assertEquals(payload["editMessageId"], payload["userEntryId"])
        assertTrue(payload["requestId"]!!.jsonPrimitive.content.startsWith("edit-"))
        assertFalse(payload.containsKey("images")); assertFalse(payload.containsKey("followUpMode"))
        assertEquals(3, repo.observeThread("c", "s").value.entries.size)
        assertTrue(runCatching { repo.send("c", "s", "must wait") }.isFailure)
        wire.listener.onText(buildJsonObject {
            put("type", "event"); put("event", buildJsonObject {
                put("kind", "session:stream-event"); put("data", buildJsonObject {
                    put("sessionId", "s"); put("runId", "new-run")
                    put("event", buildJsonObject { put("type", "user-entry"); put("entry", buildJsonObject {
                        put("id", "u"); put("kind", "user"); put("summary", "\$review revised")
                        put("skillReferences", Json.parseToJsonElement("""[{"start":0,"name":"review"}]"""))
                    }) })
                }.toString())
            })
        }.toString()); runCurrent(); editing.await()
        assertEquals(listOf("u"), repo.observeThread("c", "s").value.entries.map { it.id })
        repo.openThread("c", "s")
        assertEquals(listOf("u"), repo.observeThread("c", "s").value.entries.map { it.id })
        assertEquals("\$review revised", repo.observeThread("c", "s").value.entries.single().text)
        assertFalse(repo.observeThread("c", "s").value.editAwaitingStart)
    }
    @Test fun editWithoutStartTimesOutPreservesHistoryAndBlocksReplay() = runTest {
        val wire = Wire(); wire.snapshot = """{"entries":[{"id":"u","kind":"user","summary":"original"},{"id":"a","kind":"assistant","summary":"keep"}]}"""
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        val editing = async { runCatching { repo.editMessage("c", "s", "u", "changed", emptyList()) } }
        runCurrent(); advanceTimeBy(20_001); runCurrent()
        assertTrue(editing.await().isFailure)
        val thread = repo.observeThread("c", "s").value
        assertTrue(thread.outcomeUnknown); assertFalse(thread.editAwaitingStart)
        assertEquals(listOf("original", "keep"), thread.entries.map { it.text })
        assertTrue(runCatching { repo.editMessage("c", "s", "u", "changed", emptyList()) }.isFailure)
        assertEquals(1, wire.calls.count { it["method"] == JsonPrimitive("session:delegateRun") })
    }
    @Test fun disconnectAfterEditAcknowledgementStillMarksUncertainOutcome() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        val editing = async { runCatching { repo.editMessage("c", "s", "u", "changed", emptyList()) } }
        runCurrent(); repo.disconnect("c"); advanceTimeBy(20_001); runCurrent()
        assertTrue(editing.await().isFailure)
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        assertEquals(1, wire.calls.count { it["method"] == JsonPrimitive("session:delegateRun") })
    }
    @Test fun editRejectsRunningPendingOrMissingTargetsBeforeRequest() = runTest {
        val wire = Wire(); wire.snapshot = """{"entries":[{"id":"u","kind":"user","summary":"pending","pendingStatus":"queue"},{"id":"a","kind":"assistant","summary":"answer"}]}"""
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        for (id in listOf("u", "a", "missing")) assertTrue(runCatching { repo.editMessage("c", "s", id, "changed", emptyList()) }.isFailure)
        wire.running = true; repo.refresh("c")
        assertTrue(runCatching { repo.editMessage("c", "s", "u", "changed", emptyList()) }.isFailure)
        assertTrue(wire.calls.none { it["method"] == JsonPrimitive("session:delegateRun") })
    }
    @Test fun persistedFollowUpsRefreshDuringRunWithoutRewindingAssistantStream() = runTest {
        val wire = Wire(); wire.running = true
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        fun event(kind: String, data: String) = wire.listener.onText(buildJsonObject {
            put("type", "event"); put("event", buildJsonObject { put("kind", kind); put("data", data) })
        }.toString())
        event("session:stream-event", """{"sessionId":"s","runId":"r","event":{"type":"assistant-update","entry":{"id":"a","kind":"assistant","summary":"live newest"}}}""")
        runCurrent()
        wire.snapshot = """{"entries":[{"id":"a","kind":"assistant","summary":"old saved"},{"id":"q","kind":"user","summary":"next","pendingStatus":"queue"}]}"""
        event("session:entries-updated", """{"sessionId":"s"}""")
        runCurrent(); advanceTimeBy(301); runCurrent()
        val entries = repo.observeThread("c", "s").value.entries
        assertEquals("live newest", entries.single { it.id == "a" }.text)
        assertEquals("queue", entries.single { it.id == "q" }.pendingStatus)
        assertTrue(repo.observeThread("c", "s").value.running)
        assertFalse(repo.observeThread("c", "s").value.loading)
    }
    @Test fun desktopSelectsFollowUpModeForPromptsWithSkillsAndImages() = runTest {
        val file = File.createTempFile("followup", ".png").apply { writeText("abc") }
        try {
            val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
            runCurrent(); repo.onForeground(); runCurrent()
            repo.sendFollowUp("c", "s", "hello")
            assertFalse(wire.calls.last()["args"]!!.jsonArray.single().jsonObject.containsKey("followUpMode"))
            wire.running = true; repo.refresh("c")
            repo.sendFollowUp("c", "s", "\$review", listOf(SkillReference(0, "review")), listOf(attachment(file, true)))
            val payload = wire.calls.last()["args"]!!.jsonArray.single().jsonObject
            assertFalse(payload.containsKey("followUpMode"))
            assertEquals(Json.parseToJsonElement("""[{"start":0,"name":"review"}]"""), payload["skillReferences"])
            assertEquals(1, payload["images"]!!.jsonArray.size)
        } finally { file.delete() }
    }
    @Test fun lostFollowUpAcknowledgementBlocksReplayAndPendingStatusClearsOnReplacement() = runTest {
        val wire = Wire(); wire.running = true
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        fun entry(pending: Boolean) = wire.listener.onText(buildJsonObject {
            put("type", "event"); put("event", buildJsonObject {
                put("kind", "session:stream-event")
                put("data", buildJsonObject {
                    put("sessionId", "s"); put("runId", "r")
                    put("event", buildJsonObject {
                        put("type", "user-entry"); put("entry", buildJsonObject {
                            put("id", "queued"); put("kind", "user"); put("summary", "next")
                            if (pending) put("pendingStatus", "queue")
                        })
                    })
                }.toString())
            })
        }.toString())
        entry(true); runCurrent()
        assertEquals("queue", repo.observeThread("c", "s").value.entries.single { it.id == "queued" }.pendingStatus)
        entry(false); runCurrent()
        assertNull(repo.observeThread("c", "s").value.entries.single { it.id == "queued" }.pendingStatus)
        wire.holdSend = true
        val pending = launch { runCatching { repo.sendFollowUp("c", "s", "next") } }
        wire.sendSent.await(); pending.cancelAndJoin()
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        assertTrue(runCatching { repo.sendFollowUp("c", "s", "next") }.isFailure)
        assertEquals(1, wire.calls.count { it["method"] == JsonPrimitive("session:delegateRun") })
    }
    @Test fun skillReferencesUseUtf16PositionsAndRejectInvalidOffsetsBeforeSending() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        repo.sendWithSkills("c", "s", "😀 \$review", listOf(SkillReference(3, "review")))
        val payload = wire.calls.last()["args"]!!.jsonArray.single().jsonObject
        assertEquals(Json.parseToJsonElement("""[{"start":3,"name":"review"}]"""), payload["skillReferences"])
        val count = wire.calls.size
        assertTrue(runCatching { repo.sendWithSkills("c", "s", "😀 \$review", listOf(SkillReference(2, "review"))) }.isFailure)
        assertEquals(count, wire.calls.size)
    }
    @Test fun uploadsNeverOverwriteExistingNamesAndPreserveContentOrder() = runTest {
        val file = File.createTempFile("attachment", ".txt").apply { writeText("content") }
        try {
            val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
            runCurrent(); repo.onForeground(); runCurrent()
            val paths = repo.uploadAttachments("c", "s", listOf(attachment(file), attachment(file)))
            assertEquals(listOf("/home/.cpa/attachments/s/report (2).txt", "/home/.cpa/attachments/s/report (3).txt"), paths)
            assertEquals(paths, wire.written.keys.toList())
            assertTrue(wire.written.values.all { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) == "content" })
        } finally { file.delete() }
    }
    @Test fun imageOnlyPromptUsesDesktopImageFieldsAndHistoryRetainsImages() = runTest {
        val file = File.createTempFile("attachment", ".png").apply { writeText("abc") }
        try {
            val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
            runCurrent(); repo.onForeground(); runCurrent()
            repo.sendWithAttachments("c", "s", "", listOf(attachment(file, true, "photo.png")))
            val payload = wire.calls.last()["args"]!!.jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(""), payload["text"])
            assertEquals(Json.parseToJsonElement("""[{"data":"YWJj","mimeType":"image/png","name":"photo.png"}]"""), payload["images"])
            repo.openThread("c", "s")
            assertEquals(listOf(RemoteSentImage("YWJj", "image/png")), repo.observeThread("c", "s").value.entries.single().images)
            wire.supportsImages = false; repo.refresh("c")
            val count = wire.calls.size
            assertTrue(runCatching { repo.sendWithAttachments("c", "s", "", listOf(attachment(file, true))) }.isFailure)
            assertEquals(count, wire.calls.size)
        } finally { file.delete() }
    }
    @Test fun oversizedFilesFailBeforeUploadAndLostWriteReplyIsNotReplayed() = runTest {
        val file = File.createTempFile("attachment", ".bin").apply { writeText("a") }
        try {
            val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
            runCurrent(); repo.onForeground(); runCurrent()
            assertTrue(runCatching { repo.uploadAttachments("c", "s", listOf(attachment(file).copy(size = MAX_ATTACHMENT_BYTES + 1))) }.isFailure)
            assertTrue(wire.written.isEmpty())
            wire.holdWrite = true
            val pending = launch { runCatching { repo.uploadAttachments("c", "s", listOf(attachment(file))) } }
            wire.writeSent.await(); pending.cancelAndJoin()
            assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
            assertEquals(1, wire.written.size)
        } finally { file.delete() }
    }
    @Test fun localQueueSurvivesUnmarkedSnapshotUntilMatchingExecutionEvent() = runTest {
        val wire = Wire().apply { running = true; snapshot = """{"entries":[]}""" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        repo.sendFollowUp("c", "s", "$" + "review", listOf(SkillReference(0, "review")))
        val local = repo.observeThread("c", "s").value.entries.single()
        assertEquals("pending", local.pendingStatus)
        wire.stream("""{"type":"follow-up-pending","userEntryId":"${local.id}","pendingStatus":"queue","createdAt":100}"""); runCurrent()
        assertEquals(listOf(SkillReference(0, "review")), local.skillReferences)
        val raw = buildJsonObject { put("id", local.id); put("kind", "user"); put("summary", local.text); put("createdAt", 100) }
        wire.snapshot = buildJsonObject { put("entries", JsonArray(listOf(raw))) }.toString()
        repo.openThread("c", "s")
        assertEquals("queue", repo.observeThread("c", "s").value.entries.single().pendingStatus)
        wire.listener.onText(buildJsonObject {
            put("type", "event"); put("event", buildJsonObject {
                put("kind", "session:stream-event"); put("data", buildJsonObject {
                    put("sessionId", "s"); put("runId", "r"); put("event", buildJsonObject { put("type", "user-entry"); put("entry", raw) })
                }.toString())
            })
        }.toString()); runCurrent()
        assertNull(repo.observeThread("c", "s").value.entries.single().pendingStatus)
        repo.openThread("c", "s")
        assertNull(repo.observeThread("c", "s").value.entries.single().pendingStatus)
    }

    @Test fun confirmationBeatsInFlightSnapshotButNewerTimestampActivates() = runTest {
        val wire = Wire().apply { running = true; snapshot = """{"entries":[]}""" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        repo.sendFollowUp("c", "s", "next")
        val thread = repo.observeThread("c", "s")
        val id = thread.value.entries.single().id
        fun snapshot(time: Int) = """{"entries":[{"kind":"user","id":"$id","createdAt":$time,"summary":"next"}]}"""
        wire.holdSnapshot = true
        val reload = launch { repo.openThread("c", "s") }
        val request = wire.snapshotSent.await()
        wire.stream("""{"type":"follow-up-pending","userEntryId":"$id","pendingStatus":"steer","createdAt":1500}"""); runCurrent()
        wire.listener.onText("""{"type":"rpc_result","id":$request,"result":${snapshot(1000)}}"""); reload.join()
        assertEquals("steer", thread.value.entries.single().pendingStatus)
        assertEquals(1500.0, thread.value.entries.single().details.createdAt!!, 0.0)
        wire.holdSnapshot = false; wire.snapshot = snapshot(2000); repo.openThread("c", "s")
        assertNull(thread.value.entries.single().pendingStatus)
    }

    @Test fun userOnlyRefreshRetainsConfirmedStatusUntilTimestampChanges() = runTest {
        val wire = Wire().apply { running = true; snapshot = """{"entries":[]}""" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        repo.sendFollowUp("c", "s", "next")
        val thread = repo.observeThread("c", "s")
        val id = thread.value.entries.single().id
        wire.stream("""{"type":"follow-up-pending","userEntryId":"$id","pendingStatus":"queue","createdAt":100}"""); runCurrent()
        fun publish(time: Int) {
            wire.snapshot = """{"entries":[{"kind":"user","id":"$id","createdAt":$time,"summary":"next"}]}"""
            wire.listener.onText(buildJsonObject {
                put("type", "event"); put("event", buildJsonObject {
                    put("kind", "session:entries-updated"); put("data", """{"sessionId":"s"}""")
                })
            }.toString())
            advanceTimeBy(301); runCurrent()
        }
        publish(100); assertEquals("queue", thread.value.entries.single().pendingStatus)
        publish(200); assertNull(thread.value.entries.single().pendingStatus)
    }

    @Test fun removalDuringReloadDoesNotResurrectAStaleUserRow() = runTest {
        val wire = Wire().apply { running = true; snapshot = """{"entries":[{"kind":"user","id":"q","summary":"next"}]}""" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        wire.holdSnapshot = true
        val reload = launch { repo.openThread("c", "s") }; val request = wire.snapshotSent.await()
        wire.stream("""{"type":"follow-up-removed","userEntryId":"q"}"""); runCurrent()
        wire.listener.onText("""{"type":"rpc_result","id":$request,"result":${wire.snapshot}}"""); reload.join()
        assertTrue(repo.observeThread("c", "s").value.entries.isEmpty())
        wire.holdSnapshot = false; repo.openThread("c", "s")
        assertTrue(repo.observeThread("c", "s").value.entries.isEmpty())
    }

    @Test fun rejectedFollowUpRemovesLocalPendingButUnknownDoesNotReplay() = runTest {
        val wire = Wire().apply { running = true; snapshot = """{"entries":[]}"""; rejectSend = true }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        assertTrue(runCatching { repo.sendFollowUp("c", "s", "reject") }.isFailure)
        assertTrue(repo.observeThread("c", "s").value.entries.isEmpty())
        assertFalse(repo.observeThread("c", "s").value.outcomeUnknown)
        wire.rejectSend = false; wire.holdSend = true
        val job = launch { runCatching { repo.sendFollowUp("c", "s", "uncertain") } }
        wire.sendSent.await(); job.cancelAndJoin()
        val pending = repo.observeThread("c", "s").value.entries.single()
        assertEquals("pending", pending.pendingStatus)
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        repo.openThread("c", "s")
        assertEquals(pending, repo.observeThread("c", "s").value.entries.single())
        assertTrue(runCatching { repo.sendFollowUp("c", "s", "retry") }.isFailure)
        assertEquals(2, wire.calls.count { it["method"] == JsonPrimitive("session:delegateRun") })
        assertEquals(1, repo.observeThread("c", "s").value.entries.size)
    }

    @Test fun acknowledgedFollowUpsSurviveActiveReconnectButIdleAuthorityClearsPendingWithoutReplay() = runTest {
        for (mode in listOf("queue", "steer")) {
            val wire = Wire().apply { running = true; snapshot = """{"entries":[]}""" }
            val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
            runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
            repo.sendFollowUp("c", "s", "next")
            val local = repo.observeThread("c", "s").value.entries.single()
            wire.stream("""{"type":"follow-up-pending","userEntryId":"${local.id}","pendingStatus":"$mode","createdAt":1500}"""); runCurrent()
            wire.snapshot = """{"entries":[{"id":"${local.id}","kind":"user","createdAt":1500,"summary":"next"}]}"""
            repo.onBackground(); runCurrent(); repo.onForeground(); runCurrent()
            assertEquals(mode, repo.observeThread("c", "s").value.entries.single().pendingStatus)
            wire.running = false
            repo.onBackground(); runCurrent(); repo.onForeground(); runCurrent()
            val state = repo.observeThread("c", "s").value
            assertFalse(state.running); assertFalse(state.outcomeUnknown)
            assertEquals(local.id, state.entries.single().id); assertNull(state.entries.single().pendingStatus)
            assertEquals(1, wire.calls.count { it["method"] == JsonPrimitive("session:delegateRun") })
            repo.onBackground(); runCurrent()
        }
    }

    @Test fun stopWaitsForIdleAuthorityBeforeClearingPending() = runTest {
        val wire = Wire().apply { running = true; snapshot = """{"entries":[]}""" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        repo.sendFollowUp("c", "s", "next")
        repo.stop("c", "s")
        assertTrue(repo.observeThread("c", "s").value.running)
        assertEquals("pending", repo.observeThread("c", "s").value.entries.single().pendingStatus)
        wire.running = false
        repo.refresh("c")
        assertFalse(repo.observeThread("c", "s").value.running)
        assertTrue(repo.observeThread("c", "s").value.entries.isEmpty())
        assertEquals(1, wire.calls.count { it["method"] == JsonPrimitive("session:abortRun") })
        assertEquals(1, wire.calls.count { it["method"] == JsonPrimitive("session:delegateRun") })
    }

    @Test fun manualAcknowledgementReconcilesIdleUnknownFollowUpWithRefreshedAuthority() = runTest {
        for (persisted in listOf(false, true)) {
            val wire = Wire().apply { running = true; holdSend = true; snapshot = """{"entries":[]}""" }
            val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
            runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
            val job = launch { runCatching { repo.sendFollowUp("c", "s", "uncertain") } }
            wire.sendSent.await(); job.cancelAndJoin()
            val local = repo.observeThread("c", "s").value.entries.single()
            assertTrue(runCatching { repo.acknowledgeOutcome("c", "s") }.isFailure)
            wire.running = false
            if (persisted) wire.snapshot = """{"entries":[{"id":"${local.id}","kind":"user","summary":"uncertain"}]}"""
            repo.refresh("c")
            assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
            assertEquals(if (persisted) 1 else 0, repo.observeThread("c", "s").value.entries.size)
            assertTrue(repo.observeThread("c", "s").value.entries.all { it.pendingStatus == null })
            repo.acknowledgeOutcome("c", "s")
            val state = repo.observeThread("c", "s").value
            assertFalse(state.outcomeUnknown); assertFalse(state.running)
            assertEquals(if (persisted) 1 else 0, state.entries.size)
            assertTrue(state.entries.all { it.pendingStatus == null })
            assertEquals(1, wire.calls.count { it["method"] == JsonPrimitive("session:delegateRun") })
            repo.onBackground(); runCurrent()
        }
    }

    @Test fun manualAcknowledgementWhileRunningKeepsFollowUpUntilIdleRefresh() = runTest {
        val wire = Wire().apply { running = true; holdSend = true; snapshot = """{"entries":[]}""" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.openThread("c", "s")
        val job = launch { runCatching { repo.sendFollowUp("c", "s", "uncertain") } }
        wire.sendSent.await(); job.cancelAndJoin()
        val pending = repo.observeThread("c", "s").value.entries.single()
        repo.refresh("c"); repo.acknowledgeOutcome("c", "s")
        assertFalse(repo.observeThread("c", "s").value.outcomeUnknown)
        assertEquals(pending, repo.observeThread("c", "s").value.entries.single())
        wire.running = false; repo.refresh("c")
        assertTrue(repo.observeThread("c", "s").value.entries.isEmpty())
        assertEquals(1, wire.calls.count { it["method"] == JsonPrimitive("session:delegateRun") })
    }

}
