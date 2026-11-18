package dev.cpa.android.data.repository

import dev.cpa.android.core.network.*
import dev.cpa.android.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewingRepositoryTest {
    private class Vault : ConnectionVault {
        override suspend fun load() = listOf(StoredConnection("c", "Desktop", "https://desktop.example/", "", false))
        override suspend fun upsert(connection: StoredConnection) = Unit
        override suspend fun remove(id: String) = Unit
    }
    private class Wire : DesktopTransport {
        lateinit var listener: DesktopTransport.Listener
        val writes = mutableListOf<JsonObject>()
        var hold = false
        var reject = false
        var entries = "[]"
        var gets = 0
        var activeRuns = "[]"
        var rejectSnapshot: String? = null
        var holdGet = false
        var heldGet: JsonObject? = null
        var unread = "true"
        var updated = 10L
        fun meta() = """{"id":"s","title":"Session","unread":$unread,"updatedAt":$updated}"""
        override fun close() = Unit
        override suspend fun authenticate(password: String) = Unit
        override fun openSocket(listener: DesktopTransport.Listener): DesktopTransport.Socket {
            this.listener = listener
            listener.onText("""{"type":"hello","clientId":"view-test"}""")
            return object : DesktopTransport.Socket {
                override fun close() = Unit
                override fun send(text: String): Boolean {
                    val call = Json.parseToJsonElement(text).jsonObject
                    val method = call["method"]!!.jsonPrimitive.content
                    if (method == rejectSnapshot) {
                        listener.onText("""{"type":"rpc_error","id":${call["id"]},"error":"snapshot unavailable"}""")
                        return true
                    }
                    val result = when (method) {
                        "session:listSessions" -> "[${meta()}]"
                        "session:getActiveRuns" -> activeRuns
                        "session:get" -> { gets++; if (holdGet) { heldGet = call; return true }; """{"entries":$entries}""" }
                        "kvstore:get" -> "[]"
                        "session:setMeta" -> {
                            writes += call
                            if (reject) {
                                listener.onText("""{"type":"rpc_error","id":${call["id"]},"error":"test rejection"}""")
                                return true
                            }
                            if (hold) return true
                            "null"
                        }
                        else -> "null"
                    }
                    reply(call, result)
                    return true
                }
            }
        }
        fun reply(call: JsonObject, result: String = "null") {
            listener.onText("""{"type":"rpc_result","id":${call["id"]},"result":$result}""")
        }
        fun event(value: String, time: Long) {
            unread = value; updated = time
            listener.onText("""{"type":"event","event":{"kind":"session:meta-updated","data":${meta()}}}""")
        }
        fun event(kind: String, data: JsonObject) {
            listener.onText(buildJsonObject { put("type", "event"); put("event", buildJsonObject { put("kind", kind); put("data", data) }) }.toString())
        }
        fun run(run: String, status: String, time: Long) = event("session:run-status", buildJsonObject {
            put("sessionId", "s"); put("runId", run); put("status", status); put("updatedAt", time)
        })
        fun answer(run: String, status: String = "done", stop: String = "stop", nested: Boolean = false) = event("session:stream-event", buildJsonObject {
            put("sessionId", "s"); put("runId", run); put("event", buildJsonObject {
                put("type", "assistant-end"); put("nested", nested)
                put("entry", Json.parseToJsonElement("""{"id":"a","kind":"assistant","status":"$status","stopReason":"$stop","createdAt":20,"completedAt":30}"""))
            })
        })
        fun patch(index: Int) = writes[index]["args"]!!.jsonArray.single().jsonObject
    }
    @Test fun onlyVisibleReadersMarkReadAndMultipleOwnersStayBalanced() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        repo.openThread("c", "s")
        assertTrue(wire.writes.isEmpty())
        repo.beginViewing("c", "s"); repo.beginViewing("c", "s"); runCurrent()
        assertEquals(1, wire.writes.size)
        assertEquals(JsonPrimitive(false), wire.patch(0)["unread"])
        assertEquals(JsonPrimitive(10L), wire.patch(0)["updatedAt"])
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
        repo.endViewing("c", "s")
        wire.event("true", 20); runCurrent()
        assertEquals(2, wire.writes.size)
        repo.endViewing("c", "s")
        wire.entries = """[{"id":"new","kind":"assistant","status":"done","completedAt":30}]"""
        wire.event("true", 30); runCurrent()
        assertEquals(2, wire.writes.size)
        assertEquals("unread", repo.sessions.value["c"]!!.single().unread)
    }
    @Test fun readKeepsDatabaseTimeAdvancedWithoutMetadataBroadcast() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.updated = 19
        repo.beginViewing("c", "s"); runCurrent()
        assertEquals(JsonPrimitive(19L), wire.patch(0)["updatedAt"])
        assertEquals(19L, repo.sessions.value["c"]!!.single().updatedAt)
        repo.endViewing("c", "s")
        wire.updated = 22
        repo.setSessionUnread("c", "s", true)
        assertEquals(JsonPrimitive(22L), wire.patch(1)["updatedAt"])
        assertEquals(22L, repo.sessions.value["c"]!!.single().updatedAt)
    }

    @Test fun failedAutomaticReadDoesNotBlockPromptsAndRetriesOnlyOnFreshAuthority() = runTest {
        val wire = Wire().apply { reject = true }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.beginViewing("c", "s"); runCurrent()
        assertEquals(1, wire.writes.size)
        assertFalse(repo.observeThread("c", "s").value.outcomeUnknown)
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
        runCurrent(); assertEquals(1, wire.writes.size)
        wire.reject = false
        repo.refresh("c"); runCurrent()
        assertEquals(2, wire.writes.size)
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
    }
    @Test fun lateReadEchoCannotEraseNewerUnreadAfterLeaving() = runTest {
        val wire = Wire().apply { hold = true }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.beginViewing("c", "s"); runCurrent()
        val oldRead = wire.writes.single()
        repo.endViewing("c", "s")
        wire.event("\"error\"", 30); runCurrent()
        wire.event("true", 20); runCurrent()
        wire.event("false", 10); runCurrent()
        assertEquals("error", repo.sessions.value["c"]!!.single().unread)
        wire.hold = false; wire.reply(oldRead); runCurrent()
        assertEquals(2, wire.writes.size)
        assertEquals(JsonPrimitive("error"), wire.patch(1)["unread"])
        assertEquals(JsonPrimitive(30L), wire.patch(1)["updatedAt"])
        assertFalse(repo.observeThread("c", "s").value.outcomeUnknown)
    }
    @Test fun failedRepairOfNewerErrorSurvivesReadSnapshotAndReconnect() = runTest {
        val wire = Wire().apply { hold = true }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.beginViewing("c", "s"); runCurrent()
        val read = wire.writes.single()
        repo.endViewing("c", "s")
        wire.event("\"error\"", 30); runCurrent()
        wire.event("false", 10); runCurrent()
        wire.reject = true; wire.reply(read); runCurrent()
        assertEquals(2, wire.writes.size)
        assertEquals("error", repo.sessions.value["c"]!!.single().unread)
        repo.onBackground(); runCurrent()
        wire.reject = false; wire.hold = false
        repo.onForeground(); runCurrent()
        assertEquals(3, wire.writes.size)
        assertEquals(JsonPrimitive("error"), wire.patch(2)["unread"])
        assertEquals(JsonPrimitive(30L), wire.patch(2)["updatedAt"])
        assertEquals("error", repo.sessions.value["c"]!!.single().unread)
    }

    @Test fun reconnectRetriesVisibleUnreadButBackgroundAloneDoesNotWrite() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); repo.beginViewing("c", "s"); runCurrent()
        repo.endViewing("c", "s"); repo.onBackground(); runCurrent()
        wire.updated = 20
        repo.onForeground(); runCurrent()
        assertEquals(1, wire.writes.size)
        repo.beginViewing("c", "s"); runCurrent()
        assertEquals(2, wire.writes.size)
        assertEquals(JsonPrimitive(20L), wire.patch(1)["updatedAt"])
    }
    @Test fun completedUnviewedRunMarksUnreadOnceWithoutChangingPromptOutcome() = runTest {
        val wire = Wire().apply { unread = "false" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "running", 15); wire.answer("r"); wire.run("r", "idle", 30); runCurrent()
        assertEquals(1, wire.writes.size)
        assertEquals(JsonPrimitive(true), wire.patch(0)["unread"])
        assertEquals(JsonPrimitive(30L), wire.patch(0)["updatedAt"])
        assertEquals("unread", repo.sessions.value["c"]!!.single().unread)
        assertFalse(repo.observeThread("c", "s").value.outcomeUnknown)
        wire.run("r", "idle", 30); runCurrent()
        assertEquals(1, wire.writes.size)
        repo.beginViewing("c", "s"); runCurrent()
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
    }
    @Test fun errorMarksErrorButViewingBeforeVerificationSuppressesUnread() = runTest {
        val wire = Wire().apply { unread = "false" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "running", 15); wire.answer("r", status = "error"); wire.run("r", "idle", 30); runCurrent()
        assertEquals(JsonPrimitive("error"), wire.patch(0)["unread"])
        repo.beginViewing("c", "s"); runCurrent()
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
        repo.endViewing("c", "s")
        wire.run("r2", "running", 40); wire.answer("r2"); wire.run("r2", "idle", 50)
        runCurrent()
        val writesBefore = wire.writes.size
        repo.beginViewing("c", "s"); runCurrent(); repo.endViewing("c", "s")
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
        assertEquals(writesBefore + 1, wire.writes.size)
        wire.run("r3", "running", 60); runCurrent()
        repo.beginViewing("c", "s"); runCurrent()
        wire.answer("r3"); wire.run("r3", "idle", 70); runCurrent()
        repo.endViewing("c", "s"); runCurrent()
        assertEquals(writesBefore + 1, wire.writes.size)
    }
    @Test fun stoppedAndCompactionRunsDoNotCreateUnread() = runTest {
        val wire = Wire().apply { unread = "false" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "running", 15); runCurrent()
        repo.stop("c", "s")
        wire.answer("r"); wire.run("r", "idle", 30); runCurrent()
        assertTrue(wire.writes.isEmpty())
        repo.runAction("c", "s", RemoteRunAction.Compact)
        wire.run("r2", "running", 40); wire.answer("r2"); wire.run("r2", "idle", 50); runCurrent()
        assertTrue(wire.writes.isEmpty())
    }
    @Test fun peerAbortBeforeTerminalReplySuppressesOnlyThatRun() = runTest {
        val wire = Wire().apply { unread = "false" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "running", 15)
        wire.event("session:abort-run", buildJsonObject { put("sessionId", "s") })
        wire.answer("r"); wire.run("r", "idle", 30); runCurrent()
        assertTrue(wire.writes.isEmpty())
        assertTrue(repo.observeThread("c", "s").value.wasStopped)
        wire.run("next", "running", 35); wire.answer("next"); wire.run("next", "idle", 40); runCurrent()
        assertEquals(JsonPrimitive(true), wire.patch(0)["unread"])
        assertFalse(repo.observeThread("c", "s").value.wasStopped)
    }
    @Test fun missingTerminalPersistenceUsesBoundedRetriesAndLateEntriesWakeIt() = runTest {
        val wire = Wire().apply { unread = "false" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "running", 15); wire.run("r", "idle", 30); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertTrue(wire.writes.isEmpty())
        val gets = wire.gets
        advanceTimeBy(5000); runCurrent()
        assertEquals(gets, wire.gets)
        wire.entries = """[{"id":"a","kind":"assistant","status":"done","createdAt":20,"completedAt":30}]"""
        wire.event("session:entries-updated", buildJsonObject { put("sessionId", "s") }); runCurrent()
        assertEquals(JsonPrimitive(true), wire.patch(0)["unread"])
    }
    @Test fun replacedRunAndNestedCompletionDoNotSetUnread() = runTest {
        val wire = Wire().apply { unread = "false" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("old", "running", 15); wire.answer("old"); wire.run("new", "running", 25)
        wire.run("old", "idle", 30); wire.answer("new", nested = true); wire.run("new", "idle", 40); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertTrue(wire.writes.isEmpty())
    }
    @Test fun terminalClassifierExcludesToolContinuationAndInterruptedOrOldAnswers() {
        fun entry(status: String = "done", stop: String? = null, error: String? = null, interrupted: Boolean = false) =
            RemoteEntry("a", "assistant", "", details = RemoteEntryDetails(createdAt = 20.0, completedAt = 30.0,
                status = status, stopReason = stop, errorMessage = error, interrupted = interrupted))
        assertEquals(UnreadOutcome.Completed, classifyUnreadOutcome(listOf(entry()), 10.0))
        assertEquals(UnreadOutcome.Pending, classifyUnreadOutcome(listOf(entry()), 30.0))
        for (stop in listOf("toolUse", "pending")) assertEquals(UnreadOutcome.Pending, classifyUnreadOutcome(listOf(entry(stop = stop)), 0.0))
        for (error in listOf("Response stream ended prematurely before completion", "Interrupted by session restart"))
            assertEquals(UnreadOutcome.Incomplete, classifyUnreadOutcome(listOf(entry(error = error)), 0.0))
        assertEquals(UnreadOutcome.Incomplete, classifyUnreadOutcome(listOf(entry(interrupted = true)), 0.0))
        assertEquals(UnreadOutcome.Incomplete, classifyUnreadOutcome(listOf(entry(status = "aborted")), 0.0))
        assertEquals(UnreadOutcome.Error, classifyUnreadOutcome(listOf(entry(status = "error")), 0.0))
        for (kind in listOf("toolResult", "tool_result")) {
            assertEquals(UnreadOutcome.Pending, classifyUnreadOutcome(listOf(entry(), RemoteEntry("t", kind, "")), 0.0))
        }
        assertEquals(UnreadOutcome.Pending, classifyUnreadOutcome(listOf(entry(), RemoteEntry("c", "compaction", "")), 0.0))
    }

    @Test fun viewingWhileTerminalSnapshotIsPendingSuppressesLateUnreadEvenAfterLeaving() = runTest {
        val wire = Wire().apply { unread = "false" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "running", 15); runCurrent(); wire.holdGet = true
        wire.run("r", "idle", 30); runCurrent()
        val query = requireNotNull(wire.heldGet)
        repo.beginViewing("c", "s"); repo.endViewing("c", "s")
        wire.reply(query, """{"entries":[{"id":"a","kind":"assistant","status":"done","createdAt":20,"completedAt":30}]}""")
        runCurrent()
        assertTrue(wire.writes.isEmpty())
    }
    @Test fun briefViewDuringUnreadWriteRetriesFailedReadOnRefresh() = runTest {
        val wire = Wire().apply { unread = "false"; hold = true }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "running", 15); wire.answer("r"); wire.run("r", "idle", 30); runCurrent()
        val write = wire.writes.single()
        assertEquals("unread", repo.sessions.value["c"]!!.single().unread)
        repo.beginViewing("c", "s")
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
        repo.endViewing("c", "s")
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
        wire.event("true", 35); wire.reject = true; wire.reply(write); runCurrent()
        assertEquals(2, wire.writes.size)
        assertEquals(JsonPrimitive(false), wire.patch(1)["unread"])
        wire.reject = false; wire.hold = false
        repo.refresh("c"); runCurrent()
        assertEquals(3, wire.writes.size)
        assertEquals(JsonPrimitive(35L), wire.patch(2)["updatedAt"])
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
        wire.run("next", "running", 35); wire.answer("next", status = "error")
        wire.run("next", "idle", 35); runCurrent()
        assertEquals(JsonPrimitive("error"), wire.patch(3)["unread"])
        assertEquals("error", repo.sessions.value["c"]!!.single().unread)
    }

    @Test fun delayedPeerCompletionIsReadUntilANewerReplyPersists() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        repo.beginViewing("c", "s"); runCurrent(); repo.endViewing("c", "s")
        wire.event("true", 20); runCurrent()
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
        assertEquals(JsonPrimitive(false), wire.patch(1)["unread"])
        wire.entries = """[{"id":"b","kind":"assistant","status":"error","completedAt":25}]"""
        wire.event("session:entries-updated", buildJsonObject { put("sessionId", "s") })
        advanceTimeBy(350); runCurrent()
        assertEquals("error", repo.sessions.value["c"]!!.single().unread)
        assertEquals(JsonPrimitive("error"), wire.patch(2)["unread"])
        assertEquals(JsonPrimitive(25L), wire.patch(2)["updatedAt"])
    }

    @Test fun equalTimestampPeerManualUnreadAfterViewingRemainsUnread() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        repo.beginViewing("c", "s"); runCurrent(); repo.endViewing("c", "s")
        wire.event("true", 10); runCurrent()
        assertEquals(1, wire.writes.size)
        assertEquals("unread", repo.sessions.value["c"]!!.single().unread)
    }

    @Test fun staleErrorEchoCannotReplaceNextRunAtSameTimestamp() = runTest {
        val wire = Wire().apply { unread = "false"; hold = true }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("old", "running", 15); wire.answer("old", status = "error"); wire.run("old", "idle", 30); runCurrent()
        val old = wire.writes.single()
        wire.run("next", "running", 30); wire.answer("next"); wire.run("next", "idle", 30); runCurrent()
        wire.event("\"error\"", 30); runCurrent()
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
        wire.hold = false; wire.reply(old); runCurrent()
        assertEquals(JsonPrimitive(true), wire.patch(1)["unread"])
        assertEquals("unread", repo.sessions.value["c"]!!.single().unread)
    }

    @Test fun manualReadWaitsForAlreadySentUnreadBeforeWriting() = runTest {
        val wire = Wire().apply { unread = "false"; hold = true }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "running", 15); wire.answer("r"); wire.run("r", "idle", 30); runCurrent()
        val write = wire.writes.single()
        val manual = launch { repo.setSessionUnread("c", "s", false) }
        runCurrent()
        assertEquals(1, wire.writes.size)
        wire.event("true", 30); wire.hold = false; wire.reply(write); runCurrent()
        manual.join()
        assertEquals(2, wire.writes.size)
        assertEquals(JsonPrimitive(false), wire.patch(1)["unread"])
        assertEquals(JsonPrimitive(30L), wire.patch(1)["updatedAt"])
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
    }

    @Test fun openingDuringFailedManualReadKeepsLocalReadAndFlushesAfterLeaving() = runTest {
        val wire = Wire().apply { hold = true }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val manual = async { runCatching { repo.setSessionUnread("c", "s", false) } }
        runCurrent()
        val request = wire.writes.single()
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
        repo.beginViewing("c", "s"); repo.endViewing("c", "s")
        wire.hold = false
        wire.listener.onText("""{"type":"rpc_error","id":${request["id"]},"error":"test rejection"}""")
        runCurrent(); assertTrue(manual.await().isFailure)
        assertEquals(2, wire.writes.size)
        assertEquals(JsonPrimitive(false), wire.patch(1)["unread"])
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
    }

    @Test fun activeRunFromInitialSnapshotDoesNotCountHistoricalAnswer() = runTest {
        val wire = Wire().apply {
            unread = "false"; updated = 20
            activeRuns = """[{"sessionId":"s","runId":"r","status":"running","updatedAt":50}]"""
            entries = """[{"id":"a","kind":"assistant","status":"done","completedAt":20}]"""
        }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "idle", 55); advanceTimeBy(1000); runCurrent()
        assertTrue(wire.writes.isEmpty())
        assertEquals("read", repo.sessions.value["c"]!!.single().unread)
    }

    @Test fun offlineBriefViewUsesEntriesToDistinguishRenameFromANewTurn() = runTest {
        for (newReply in listOf(false, true)) {
            val wire = Wire()
            val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
            runCurrent(); repo.onForeground(); runCurrent(); repo.onBackground(); runCurrent()
            repo.beginViewing("c", "s"); repo.endViewing("c", "s")
            wire.updated = 60
            val entryTime = if (newReply) 50 else 10
            wire.entries = """[{"id":"a","kind":"assistant","status":"done","completedAt":$entryTime}]"""
            repo.onForeground(); runCurrent()
            if (newReply) {
                assertTrue(wire.writes.isEmpty())
                assertEquals("unread", repo.sessions.value["c"]!!.single().unread)
            } else {
                assertEquals(JsonPrimitive(false), wire.patch(0)["unread"])
                assertEquals(JsonPrimitive(60L), wire.patch(0)["updatedAt"])
                assertEquals("read", repo.sessions.value["c"]!!.single().unread)
            }
            repo.onBackground(); runCurrent()
        }
    }

    @Test fun rejectedCompletionWriteKeepsLocalErrorAcrossStaleMetadataUntilRefresh() = runTest {
        val wire = Wire().apply { unread = "false"; reject = true }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "running", 15); wire.answer("r", status = "error"); wire.run("r", "idle", 30); runCurrent()
        wire.event("false", 10); runCurrent()
        assertEquals("error", repo.sessions.value["c"]!!.single().unread)
        assertEquals(30L, repo.sessions.value["c"]!!.single().updatedAt)
        assertEquals(1, wire.writes.size)
        wire.reject = false
        repo.refresh("c"); runCurrent()
        assertEquals(JsonPrimitive("error"), wire.patch(1)["unread"])
        assertEquals("error", repo.sessions.value["c"]!!.single().unread)
    }

    @Test fun partialResyncRetainsInterruptedRunUntilBothSnapshotsSucceed() = runTest {
        for (method in listOf("session:getActiveRuns", "session:listSessions")) {
            val wire = Wire().apply { unread = "false" }
            val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
            runCurrent(); repo.onForeground(); runCurrent()
            wire.run("r", "running", 15); runCurrent(); repo.onBackground(); runCurrent()
            wire.entries = """[{"id":"a","kind":"assistant","status":"done","completedAt":30}]"""
            wire.rejectSnapshot = method
            repo.onForeground(); runCurrent()
            assertTrue(method, wire.writes.isEmpty())
            wire.rejectSnapshot = null; repo.refresh("c"); runCurrent()
            assertEquals(method, JsonPrimitive(true), wire.patch(0)["unread"])
            repo.onBackground(); runCurrent()
        }
    }

    @Test fun reconnectReconcilesPreviouslyActiveRunFromFreshTerminalSnapshot() = runTest {
        val wire = Wire().apply { unread = "false" }
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.run("r", "running", 15); runCurrent()
        repo.onBackground(); runCurrent()
        wire.entries = """[{"id":"a","kind":"assistant","status":"done","createdAt":20,"completedAt":30}]"""
        repo.onForeground(); runCurrent()
        assertEquals(1, wire.writes.size)
        assertEquals(JsonPrimitive(true), wire.patch(0)["unread"])
        assertEquals("unread", repo.sessions.value["c"]!!.single().unread)
    }

}
