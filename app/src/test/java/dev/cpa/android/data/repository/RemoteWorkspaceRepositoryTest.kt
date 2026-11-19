package dev.cpa.android.data.repository

import dev.cpa.android.core.network.*
import dev.cpa.android.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteWorkspaceRepositoryTest {
    private class Vault : ConnectionVault {
        override suspend fun load() = listOf(StoredConnection("c", "Desktop", "https://desktop.example/", "", false))
        override suspend fun upsert(connection: StoredConnection) = Unit
        override suspend fun remove(id: String) = Unit
    }
    private class Wire : DesktopTransport {
        lateinit var listener: DesktopTransport.Listener
        val calls = mutableListOf<JsonObject>()
        var projects: JsonElement = Json.parseToJsonElement("""[{"id":"p","name":"Project","path":"/legacy","paths":["/src"],"custom":{"keep":true}},{"id":"other","name":"Other"}]""")
        var fileData = "aGVsbG8="
        var holdSet = false
        var held: JsonObject? = null
        var directory: JsonElement = Json.parseToJsonElement("""[{"name":"z.txt","isDir":false},{"name":"src","isDir":true},{"name":"link","isDir":true,"isSymbolicLink":true},{"name":".hidden","isDir":true}]""")
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
                    val args = call["args"]!!.jsonArray
                    val result = when (call["method"]!!.jsonPrimitive.content) {
                        "session:getActiveRuns" -> JsonArray(emptyList())
                        "session:listSessions" -> Json.parseToJsonElement("""[{"id":"s","projectId":"p"},{"id":"archived","projectId":"p","archivedAt":1},{"id":"u","projectId":"other"}]""")
                        "session:get" -> Json.parseToJsonElement("""{"entries":[]}""")
                        "kvstore:get" -> if (args.first() == JsonPrimitive("projects")) projects else JsonNull
                        "kvstore:set" -> {
                            projects = args[1]
                            if (holdSet) { held = call; return true }
                            JsonNull
                        }
                        "native:runtimeInfo" -> Json.parseToJsonElement("""{"homeDir":"C:\\Users\\dev"}""")
                        "native:readFile" -> buildJsonObject { put("dataBase64", fileData) }
                        "native:runProcess" -> {
                            val request = args.first().jsonObject
                            val gitArgs = request["args"]!!.jsonArray.map { it.jsonPrimitive.content }
                            val output = if (gitArgs == listOf("rev-parse", "--show-toplevel")) "/repo" else ""
                            buildJsonObject { put("exitCode", 0); put("stdoutBase64", java.util.Base64.getEncoder().encodeToString(output.toByteArray())) }
                        }
                        "native:readDir" -> directory
                        else -> JsonPrimitive(true)
                    }
                    reply(call, result)
                    return true
                }
            }
        }
        fun reply(call: JsonObject, result: JsonElement = JsonNull) = listener.onText("""{"type":"rpc_result","id":${call["id"]},"result":$result}""")
        fun projectSets() = calls.filter { it["method"] == JsonPrimitive("kvstore:set") }
    }
    @Test fun gitReviewUsesStructuredProcessArgumentsAndRemoteWorkingDirectory() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val review = repo.gitReview("c", "/repo/sub folder", "main", "feature;literal")
        assertEquals("origin/main...feature;literal", review.comparison)
        val requests = wire.calls.filter { it["method"] == JsonPrimitive("native:runProcess") }.map { it["args"]!!.jsonArray.first().jsonObject }
        assertTrue(requests.isNotEmpty())
        requests.forEach { assertEquals(JsonPrimitive("git"), it["executable"]); assertEquals(JsonPrimitive("/repo/sub folder"), it["cwd"]); assertEquals(JsonNull, it["env"]) }
        assertEquals(listOf("diff", "origin/main...feature;literal"), requests.last()["args"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
    @Test fun remoteFileDecodesBase64AndRejectsMalformedPayload() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        assertEquals("hello", repo.readRemoteFile("c", "/src/file.txt"))
        assertEquals(JsonPrimitive("/src/file.txt"), wire.calls.last()["args"]!!.jsonArray.first())
        wire.fileData = "!invalid"
        assertTrue(runCatching { repo.readRemoteFile("c", "/src/file.txt") }.isFailure)
    }
    @Test fun foldersAreRemoteSortedAndSkipSymlinks() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        assertEquals("C:\\Users\\dev", repo.homeDirectory("c"))
        assertEquals(listOf(".hidden", "src", "z.txt"), repo.directoryEntries("c", "/test").map { it.name })
        assertEquals(Json.parseToJsonElement("""["/test"]"""), wire.calls.last()["args"])
        wire.directory = JsonNull
        assertTrue(runCatching { repo.directoryEntries("c", "/missing") }.isFailure)
    }
    @Test fun editingPreservesUnknownFieldsAndCanRemoveEveryPath() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        assertEquals(listOf("/legacy", "/src"), repo.projects.value["c"]!!.first().allPaths)
        repo.updateProject("c", "p", " Renamed ", listOf(" /x ", "/x", "/y"))
        val result = wire.projects.jsonArray.first().jsonObject
        assertEquals(JsonPrimitive("Renamed"), result["name"])
        assertEquals(Json.parseToJsonElement("""["/x","/y"]"""), result["paths"])
        assertEquals(Json.parseToJsonElement("""{"keep":true}"""), result["custom"])
        repo.updateProject("c", "p", "Empty sources", emptyList())
        assertEquals(JsonNull, wire.projects.jsonArray.first().jsonObject["path"])
        assertTrue(repo.projects.value["c"]!!.first().allPaths.isEmpty())
        val count = wire.calls.size
        assertTrue(runCatching { repo.updateProject("c", "p", " ", emptyList()) }.isFailure)
        assertEquals(count, wire.calls.size)
    }
    @Test fun removalDetachesAllSessionsWithoutFilesystemDeletion() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        repo.removeProject("c", "p")
        val patches = wire.calls.filter { it["method"] == JsonPrimitive("session:setMeta") }.map { it["args"]!!.jsonArray.single().jsonObject }
        assertEquals(setOf("s", "archived"), patches.map { it["id"]!!.jsonPrimitive.content }.toSet())
        assertTrue(patches.all { it["projectId"] == JsonNull })
        assertEquals(listOf("other"), repo.projects.value["c"]!!.map { it.id })
        assertFalse(wire.calls.any { it["method"]!!.jsonPrimitive.content.startsWith("native:") })
        assertNull(repo.sessions.value["c"]!!.find { it.id == "s" }!!.projectId)
    }
    @Test fun writesSerializeAndRereadSoEditsDoNotOverwriteEachOther() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.holdSet = true
        val rename = async { repo.updateProject("c", "p", "Renamed", listOf("/new")) }
        runCurrent()
        val pin = async { repo.setProjectPinned("c", "p", true) }; runCurrent()
        assertEquals(1, wire.projectSets().size)
        wire.holdSet = false; wire.reply(wire.held!!); runCurrent()
        rename.await(); pin.await()
        val result = wire.projects.jsonArray.first().jsonObject
        assertEquals(JsonPrimitive("Renamed"), result["name"])
        assertEquals(JsonPrimitive(true), result["pinned"])
    }
    @Test fun lateWriteConfirmationDoesNotOverwriteNewerProjectBroadcast() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.holdSet = true
        val pending = async { repo.updateProject("c", "p", "Local edit", emptyList()) }
        runCurrent()
        val payload = """[{"id":"p","name":"Newer desktop edit","paths":["/latest"]}]"""
        wire.listener.onText(buildJsonObject {
            put("type", "event"); put("event", buildJsonObject { put("kind", "projects:updated"); put("data", payload) })
        }.toString())
        runCurrent(); wire.reply(wire.held!!); runCurrent(); pending.await()
        assertEquals("Newer desktop edit", repo.projects.value["c"]!!.single().name)
        assertEquals(listOf("/latest"), repo.projects.value["c"]!!.single().allPaths)
    }
    @Test fun lostReplyNeedsFreshReadAndHumanConfirmationBeforeAnotherWrite() = runTest {
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(Vault(), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        wire.holdSet = true
        val pending = launch { runCatching { repo.setProjectPinned("c", "p", true) } }
        runCurrent(); pending.cancel(); runCurrent()
        val count = wire.calls.size
        assertTrue(runCatching { repo.updateProject("c", "p", "No replay", emptyList()) }.isFailure)
        assertEquals(count, wire.calls.size)
        assertTrue(runCatching { repo.acknowledgeProjectOutcome("c") }.isFailure)
        wire.holdSet = false
        repo.refreshProjects("c")
        assertTrue(repo.projects.value["c"]!!.first().pinned)
        assertTrue(runCatching { repo.setProjectPinned("c", "p", false) }.isFailure)
        repo.acknowledgeProjectOutcome("c")
        repo.setProjectPinned("c", "p", false)
        assertEquals(2, wire.projectSets().size)
    }
}
