package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelTransportTest {
    private class Peer(val client: String, val listener: TunnelGatewaySocket.Listener) : GatewayConnection {
        val requests = mutableListOf<Pair<String, TunnelProtocol.Mux>>()
        var required = true
        var loginStatus = 200
        var cookieHeader: String? = "sid=desktop; Path=/; HttpOnly"
        var statusBody: String? = null
        var autoReply = true
        var closed = false
        var closeCode: Int? = null
        override fun connect() { listener.onOpen() }
        override fun close() { closed = true }
        override fun sendText(text: String): Boolean {
            listener.onText("""{"type":"server_list","servers":[{"uuid":"a","name":"A","type":"CodingProfessionalAgent"},{"uuid":"b","name":"B","type":"CodingProfessionalAgent"}]}""")
            return true
        }
        fun reply(target: String, meta: JsonObject, body: String = "") {
            listener.onBinary(TunnelProtocol.encodeFrame(TunnelProtocol.Frame(2, target, client,
                TunnelProtocol.encodeMux(TunnelProtocol.Mux(meta, body.toByteArray())))))
        }
        override fun sendBinary(bytes: ByteArray): Boolean {
            val frame = TunnelProtocol.decodeFrame(bytes); val mux = TunnelProtocol.decodeMux(frame.payload)
            requests += frame.destination to mux
            if (!autoReply) return true
            val meta = mux.meta
            when (meta["type"]!!.jsonPrimitive.content) {
                "http_request" -> {
                    val status = meta["path"]!!.jsonPrimitive.content.endsWith("status")
                    reply(frame.destination, buildJsonObject {
                        put("type", "http_response"); put("id", meta["id"]!!); put("status", if (status) 200 else loginStatus)
                        if (!status && cookieHeader != null) put("headers", buildJsonObject {
                            put("sEt-CoOkIe", JsonArray(listOf(JsonPrimitive(""), JsonPrimitive(cookieHeader!!.replace("desktop", frame.destination)))))
                        })
                    }, if (status) statusBody ?: """{"required":$required,"authenticated":false}""" else "{}")
                }
                "ws_open" -> {
                    reply(frame.destination, buildJsonObject { put("type", "ws_open_result"); put("connId", meta["connId"]!!); put("ok", true) })
                    reply(frame.destination, buildJsonObject { put("type", "ws_data"); put("connId", meta["connId"]!!); put("opcode", "text") }, """{"type":"hello","clientId":"rpc-client"}""")
                }
                "ws_data" -> {
                    val rpc = Json.parseToJsonElement(mux.body.toString(Charsets.UTF_8)).jsonObject
                    if (closeCode != null) reply(frame.destination, buildJsonObject {
                        put("type", "ws_close"); put("connId", meta["connId"]!!); put("code", closeCode!!)
                    }) else reply(frame.destination, buildJsonObject {
                        put("type", "ws_data"); put("connId", meta["connId"]!!); put("opcode", "text")
                    }, buildJsonObject { put("type", "rpc_result"); put("id", rpc["id"]!!); put("result", "ok") }.toString())
                }
            }
            return true
        }
    }
    private class Fixture(val scope: CoroutineScope) {
        val peers = mutableListOf<Peer>()
        val manager = TunnelClientManager(scope, MemoryTunnelIdentityStore()) { ep, key ->
            TunnelGatewaySession(ep, key, scope, MemoryTunnelIdentityStore(), jitter = { 1.0 },
                factory = { _, _, id, _, listener -> Peer(id, listener).also(peers::add) })
        }
        fun transport(target: String = "a") = TunnelTransport(manager.acquire("https://example.test", "key"), target, scope)
        val peer get() = peers.single()
    }
    @Test fun sharedGatewayKeepsDesktopCookiesSeparateAndBridgesRpc() = runTest {
        val f = Fixture(backgroundScope)
        val a = f.transport("a"); val b = f.transport("b"); runCurrent()
        val ca = DesktopRpcClient(a); val cb = DesktopRpcClient(b)
        val connectA = async { ca.connect("password-a") }; val connectB = async { cb.connect("password-b") }
        runCurrent(); connectA.await(); connectB.await()
        val opens = f.peer.requests.filter { it.second.meta["type"]!!.jsonPrimitive.content == "ws_open" }
        assertEquals(2, opens.size)
        opens.forEach { (target, mux) -> assertEquals("sid=$target", mux.meta["headers"]!!.jsonObject["Cookie"]!!.jsonPrimitive.content) }
        val result = async { ca.call("session:list") }; runCurrent(); assertEquals(JsonPrimitive("ok"), result.await())
        ca.close(); runCurrent(); assertFalse(f.peer.closed)
        val other = async { cb.call("session:list") }; runCurrent(); assertEquals(JsonPrimitive("ok"), other.await())
        cb.close(); runCurrent(); assertTrue(f.peer.closed)
        f.manager.close()
    }
    @Test fun optionalAuthClearsOldCookieAndMalformedStatusFailsClosed() = runTest {
        val f = Fixture(backgroundScope); val transport = f.transport(); runCurrent()
        val first = async { transport.authenticate("password") }; runCurrent(); first.await()
        f.peer.required = false
        val second = async { transport.authenticate("password") }; runCurrent(); second.await()
        val socket = transport.openSocket(object : DesktopTransport.Listener {
            override fun onText(text: String) = Unit
            override fun onClosed(cause: Throwable) = Unit
        }); runCurrent()
        assertNull(f.peer.requests.last { it.second.meta["type"]!!.jsonPrimitive.content == "ws_open" }.second.meta["headers"])
        f.peer.statusBody = "{}"
        val bad = async { runCatching { transport.authenticate("password") } }; runCurrent()
        assertTrue(bad.await().exceptionOrNull() is ProtocolFailure)
        socket.close(); transport.close(); f.manager.close()
    }
    @Test fun loginRejectionAndMissingCookieNeverOpenSocket() = runTest {
        for (status in listOf(401, 200)) {
            val f = Fixture(backgroundScope); val transport = f.transport(); runCurrent()
            f.peer.loginStatus = status; f.peer.cookieHeader = null
            val result = async { runCatching { transport.authenticate("private-password") } }; runCurrent()
            val error = result.await().exceptionOrNull()
            if (status == 401) assertEquals(401, (error as HttpFailure).statusCode) else assertTrue(error is ProtocolFailure)
            assertFalse(error.toString().contains("private-password"))
            assertTrue(f.peer.requests.none { it.second.meta["type"]!!.jsonPrimitive.content == "ws_open" })
            transport.close(); f.manager.close()
        }
    }
    @Test fun remote4401FailsRpcAndClosingOneTransportReleasesOnlyItsLease() = runTest {
        val f = Fixture(backgroundScope); val transport = f.transport(); val other = f.transport("b"); runCurrent()
        val rpc = DesktopRpcClient(transport)
        val connection = async { rpc.connect("password") }; runCurrent(); connection.await()
        f.peer.closeCode = 4401
        val result = async { runCatching { rpc.call("session:list") } }; runCurrent()
        assertEquals(4401, (result.await().exceptionOrNull() as WebSocketCloseFailure).code)
        assertFalse(f.peer.closed)
        other.close(); f.manager.close()
    }
    @Test fun desktopListenerIgnoresBinaryWsDataAndReceivesFollowingText() = runTest {
        val f = Fixture(backgroundScope); val transport = f.transport(); runCurrent()
        val auth = async { transport.authenticate("password") }; runCurrent(); auth.await()
        f.peer.autoReply = false
        val messages = mutableListOf<String>()
        val socket = transport.openSocket(object : DesktopTransport.Listener {
            override fun onText(text: String) { messages += text }
            override fun onClosed(cause: Throwable) = Unit
        }); runCurrent()
        val id = f.peer.requests.last().second.meta["connId"]!!
        f.peer.reply("a", buildJsonObject { put("type", "ws_open_result"); put("connId", id); put("ok", true) })
        for (opcode in listOf("binary", "text")) {
            f.peer.reply("a", buildJsonObject { put("type", "ws_data"); put("connId", id); put("opcode", opcode) }, opcode)
        }
        runCurrent()
        assertEquals(listOf("text"), messages)
        socket.close(); transport.close(); f.manager.close()
    }
    @Test fun closedVirtualSocketIgnoresLateOpenAndHello() = runTest {
        val f = Fixture(backgroundScope); val transport = f.transport(); runCurrent()
        val auth = async { transport.authenticate("password") }; runCurrent(); auth.await()
        f.peer.autoReply = false
        var messages = 0; var closed = 0
        val socket = transport.openSocket(object : DesktopTransport.Listener {
            override fun onText(text: String) { messages++ }
            override fun onClosed(cause: Throwable) { closed++ }
        }); runCurrent()
        val id = f.peer.requests.last().second.meta["connId"]!!
        socket.close(); runCurrent()
        f.peer.reply("a", buildJsonObject { put("type", "ws_open_result"); put("connId", id); put("ok", true) })
        f.peer.reply("a", buildJsonObject { put("type", "ws_data"); put("connId", id); put("opcode", "text") }, "hello")
        runCurrent()
        assertEquals(0, messages); assertEquals(0, closed); assertFalse(socket.send("late"))
        assertEquals("ws_close", f.peer.requests.last().second.meta["type"]!!.jsonPrimitive.content)
        transport.close(); f.manager.close()
    }
    @Test fun authenticationFromOldGatewayCannotOpenOnReplacementEpoch() = runTest {
        val f = Fixture(backgroundScope); val transport = f.transport(); runCurrent()
        val auth = async { transport.authenticate("password") }; runCurrent(); auth.await()
        f.peer.listener.onClosed(NetworkFailure()); runCurrent()
        advanceTimeBy(1500); runCurrent()
        assertEquals(2, f.peers.size)
        val failure = runCatching { transport.openSocket(object : DesktopTransport.Listener {
            override fun onText(text: String) = Unit
            override fun onClosed(cause: Throwable) = Unit
        }) }.exceptionOrNull()
        assertTrue(failure is Disconnected)
        assertTrue(f.peers.last().requests.isEmpty())
        transport.close(); f.manager.close()
    }
    @Test fun closeCancelsPendingAuthenticationAndRejectsNewWork() = runTest {
        val f = Fixture(backgroundScope); val transport = f.transport(); runCurrent()
        f.peer.autoReply = false
        val result = async { runCatching { transport.authenticate("password") } }; runCurrent()
        transport.close(); runCurrent()
        assertTrue(result.await().isFailure)
        assertTrue(runCatching { transport.authenticate("again") }.isFailure)
        assertTrue(f.peer.closed); f.manager.close()
    }
}
