package dev.cpa.android.data.repository

import dev.cpa.android.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real HTTP login + upgrade + RPC + application-level event consumption, no device or desktop required. */
class RepositoryDirectIntegrationTest {
    @Test fun twoConnectionsSameOriginKeepTheirOwnCookiesAndRenderRemoteEntry() = runBlocking {
        val cookies = CopyOnWriteArrayList<String>()
        val methods = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/status" -> MockResponse().setBody("""{"required":true,"authenticated":false}""")
                "/api/auth/login" -> {
                    val password = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["password"]!!.jsonPrimitive.content
                    MockResponse().addHeader("Set-Cookie", "cpa_web_session=$password; HttpOnly; Path=/").setBody("{}")
                }
                "/api/ws" -> {
                    cookies.add(request.getHeader("Cookie").orEmpty())
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        // A later authoritative snapshot must contain the accepted message. Otherwise an
                        // overlapping initial refresh can legitimately replace the synthetic stream entry.
                        var savedEntry: String? = null
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"type":"hello","clientId":"integration"}""")
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val call = Json.parseToJsonElement(text).jsonObject
                            val method = call["method"]!!.jsonPrimitive.content
                            methods.add(method)
                            val result = when (method) {
                                "kvstore:get" -> """[{"id":"p","name":"Project"}]"""
                                "session:listSessions" -> """[{"id":"s","title":"Session","projectId":"p"}]"""
                                "session:getActiveRuns" -> "[]"
                                "session:get" -> """{"id":"s","entries":[${savedEntry.orEmpty()}]}"""
                                "session:delegateRun" -> {
                                    savedEntry = """{"id":"u","kind":"user","content":[{"type":"text","text":"Remote authoritative text"}]}"""
                                    webSocket.send("""{"type":"event","event":{"kind":"session:stream-event","data":{"sessionId":"s","runId":"r","event":{"type":"user-entry","entry":{"id":"u","kind":"user","content":[{"type":"text","text":"Remote authoritative text"}]}}}}}""")
                                    "\"s\""
                                }
                                else -> "null"
                            }
                            webSocket.send("""{"type":"rpc_result","id":${call["id"]},"result":$result}""")
                        }
                    })
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vault = object : ConnectionVault {
                override suspend fun load() = emptyList<StoredConnection>()
                override suspend fun upsert(connection: StoredConnection) = Unit
                override suspend fun remove(id: String) = Unit
            }
            val repo = DefaultRemoteDesktopRepository(vault, app)
            val address = server.url("/").toString()
            val a = repo.saveConnection(null, "A", address, "token-a", true)
            repo.saveConnection(null, "B", address, "token-b", true)
            repo.onForeground()
            withTimeout(5_000) { repo.sessions.first { it.size == 2 } }
            assertEquals(setOf("cpa_web_session=token-a", "cpa_web_session=token-b"), cookies.toSet())
            repo.openThread(a, "s")
            repo.send(a, "s", "hello")
            val thread = withTimeout(5_000) { repo.observeThread(a, "s").first { it.entries.isNotEmpty() } }
            assertEquals("Remote authoritative text", thread.entries.single().text)
            assertFalse(thread.running)
            repo.openThread(a, "s")
            assertEquals("Remote authoritative text", repo.observeThread(a, "s").value.entries.single().text)
            assertEquals(1, methods.count { it == "session:delegateRun" })
            repo.onBackground()
        } finally { app.cancel(); server.shutdown() }
    }
}
