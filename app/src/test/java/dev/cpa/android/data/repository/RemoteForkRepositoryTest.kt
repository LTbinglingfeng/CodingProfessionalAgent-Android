package dev.cpa.android.data.repository

import dev.cpa.android.core.network.*
import dev.cpa.android.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteForkRepositoryTest {
    private class Vault : ConnectionVault {
        override suspend fun load() = listOf(StoredConnection("c", "Desktop", "https://desktop.example/", "", false))
        override suspend fun upsert(connection: StoredConnection) = Unit
        override suspend fun remove(id: String) = Unit
    }
    private class Wire : DesktopTransport {
        lateinit var listener: DesktopTransport.Listener
        val calls = mutableListOf<JsonObject>()
        var rejectMeta = false
        var holdCopy = false
        val copySent = CompletableDeferred<Unit>()
        var sourceExists = true
        override suspend fun authenticate(password: String) = Unit
        override fun close() = Unit
        override fun openSocket(listener: DesktopTransport.Listener): DesktopTransport.Socket {
            this.listener = listener
            listener.onText("""{"type":"hello","clientId":"test"}""")
            return object : DesktopTransport.Socket {
                override fun close() = Unit
                override fun send(text: String): Boolean {
                    val call = Json.parseToJsonElement(text).jsonObject; calls += call
                    val method = call["method"]!!.jsonPrimitive.content
                    val args = call["args"]!!.jsonArray
                    val result = when (method) {
                        "session:listSessions" -> if (sourceExists) """[{"id":"s","title":"Task","projectId":"p","branch":"main","workLocation":"worktree","worktreePath":"/old","environmentId":"e"}]""" else "[]"
                        "session:getActiveRuns" -> "[]"
                        "session:get" -> """{"entries":[{"id":"u","kind":"user","content":[{"type":"text","text":"hello"}]}]}"""
                        "kvstore:get" -> if (args[0] == JsonPrimitive("projects")) """[{"id":"p","name":"Project","path":"/src"}]""" else "null"
                        "native:runProcess" -> buildJsonObject {
                            put("exitCode", 0); put("stdoutBase64", Base64.getEncoder().encodeToString("*main\n dev\n".toByteArray()))
                        }.toString()
                        "native:readFileIfExists" -> if (args[0] == JsonPrimitive("/src/environment.toml")) """{"dataBase64":""}""" else "null"
                        else -> "null"
                    }
                    if (method == "session:set" && holdCopy) { copySent.complete(Unit); return true }
                    listener.onText(if (method == "session:setMeta" && rejectMeta) """{"type":"rpc_error","id":${call["id"]},"error":"rejected"}"""
                    else """{"type":"rpc_result","id":${call["id"]},"result":$result}""")
                    return true
                }
            }
        }
    }
    @Test fun forkWritesSnapshotThenMetadataAndProjectsNewSession() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val target = UUID.randomUUID().toString()
        repo.forkSession("c", "s", target, worktree = RemoteWorktreeFork("dev", null))
        val writes = wire.calls.filter { it["method"] in listOf(JsonPrimitive("session:set"), JsonPrimitive("session:setMeta")) }
        assertEquals(listOf("session:set", "session:setMeta"), writes.map { it["method"]!!.jsonPrimitive.content })
        val copied = writes[0]["args"]!!.jsonArray
        assertEquals(JsonPrimitive(target), copied[0])
        assertEquals(JsonPrimitive("dev"), copied[1].jsonObject["branch"])
        assertFalse(copied[1].jsonObject.containsKey("worktreePath"))
        assertFalse(copied[1].jsonObject.containsKey("environmentId"))
        val created = repo.sessions.value["c"]!!.find { it.id == target }!!
        assertEquals("Task (Fork)", created.title); assertEquals("worktree", created.workLocation)
    }
    @Test fun partialForkBlocksSourceAndNeverReplaysCopy() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); wire.rejectMeta = true
        assertTrue(runCatching { repo.forkSession("c", "s", UUID.randomUUID().toString()) }.isFailure)
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        val count = wire.calls.size
        assertTrue(runCatching { repo.forkSession("c", "s", UUID.randomUUID().toString()) }.isFailure)
        assertEquals(count, wire.calls.size)
        assertEquals(1, wire.calls.count { it["method"] == JsonPrimitive("session:set") })
    }
    @Test fun cancelledCopyRemainsUnknownAndMissingSourceNeverWrites() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.sourceExists = false
        assertTrue(runCatching { repo.forkSession("c", "s", UUID.randomUUID().toString()) }.isFailure)
        assertFalse(wire.calls.any { it["method"] == JsonPrimitive("session:set") })
        wire.sourceExists = true; wire.holdCopy = true
        val task = launch { runCatching { repo.forkSession("c", "s", UUID.randomUUID().toString()) } }
        wire.copySent.await(); task.cancelAndJoin(); runCurrent()
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        assertFalse(wire.calls.any { it["method"] == JsonPrimitive("session:setMeta") })
    }
    @Test fun branchesUseFixedReadOnlyGitCommandAndEnvironmentsRequireFile() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        assertEquals(RemoteBranches("main", listOf("main", "dev")), repo.gitBranches("c", "/src"))
        val request = wire.calls.last()["args"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("git"), request["executable"])
        assertEquals(Json.parseToJsonElement("""["branch","--format=%(HEAD)%(refname:short)"]"""), request["args"])
        assertEquals(JsonPrimitive("/src"), request["cwd"])
        assertEquals(listOf("p"), repo.environmentProjects("c").map { it.id })
    }
}
