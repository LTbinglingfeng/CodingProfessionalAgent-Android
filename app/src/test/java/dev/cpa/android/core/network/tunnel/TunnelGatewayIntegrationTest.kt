package dev.cpa.android.core.network.tunnel

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

/** Local protocol peer, not a real deployed CPA gateway. */
class TunnelGatewayIntegrationTest {
    @Test fun ownerDiscoversThenRoutesHttpAndVirtualSocketThroughPhysicalGateway() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val identities = MemoryTunnelIdentityStore()
        val clientId = identities.current()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"server_list","servers":[{"uuid":"desktop","name":"PC","type":"CodingProfessionalAgent"}]}""")
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val frame = TunnelProtocol.decodeFrame(bytes.toByteArray())
                val request = TunnelProtocol.decodeMux(frame.payload).meta
                fun reply(meta: JsonObject, body: String = "") {
                    webSocket.send(TunnelProtocol.encodeFrame(TunnelProtocol.Frame(2, "desktop", clientId,
                        TunnelProtocol.encodeMux(TunnelProtocol.Mux(meta, body.toByteArray())))).toByteString())
                }
                when (request["type"]!!.jsonPrimitive.content) {
                    "http_request" -> reply(buildJsonObject {
                        put("type", "http_response"); put("id", request["id"]!!); put("status", 200)
                        put("headers", buildJsonObject { put("Set-Cookie", "session=test") })
                    }, "authenticated")
                    "ws_open" -> {
                        reply(buildJsonObject { put("type", "ws_open_result"); put("connId", request["connId"]!!); put("ok", true) })
                        reply(buildJsonObject { put("type", "ws_data"); put("connId", request["connId"]!!); put("opcode", "text") }, "hello")
                    }
                    "ws_data" -> reply(buildJsonObject { put("type", "ws_close"); put("connId", request["connId"]!!); put("code", 4401) })
                }
            }
        }))
        val owner = TunnelGatewaySession(TunnelProtocol.endpoint(server.url("/").toString()), "test-key", scope, identities)
        try {
            owner.start()
            withTimeout(5000) { owner.state.first { it.phase == TunnelGatewaySession.Phase.Connected } }
            val mux = owner.currentMux()
            val response = mux.http("desktop", "POST", "/api/auth", body = "password".toByteArray())
            assertEquals(200, response.status)
            assertEquals("session=test", TunnelProtocol.header(response.headers, "set-cookie"))
            assertEquals("authenticated", response.body.toString(Charsets.UTF_8))
            val stream = mux.open("desktop")
            assertEquals("hello", withTimeout(5000) { stream.receive().toString(Charsets.UTF_8) })
            assertTrue(stream.sendText("rpc"))
            val failure = runCatching { withTimeout(5000) { stream.receive() } }.exceptionOrNull()
            assertEquals(4401, (failure as dev.cpa.android.core.network.WebSocketCloseFailure).code)
        } finally { owner.close(); scope.cancel(); scope.coroutineContext[Job]!!.join(); server.shutdown() }
    }
}
