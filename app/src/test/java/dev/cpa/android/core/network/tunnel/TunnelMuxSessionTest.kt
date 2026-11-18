package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.network.HttpFailure
import dev.cpa.android.core.network.WebSocketCloseFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelMuxSessionTest {
    private class Fixture {
        val sent = mutableListOf<TunnelProtocol.Frame>()
        var allowSend = true
        val session = TunnelMuxSession("client") { raw -> sent += TunnelProtocol.decodeFrame(raw); allowSend }
        fun ready(vararg ids: String = arrayOf("desktop")) = session.control(TunnelProtocol.Control.ListServers(null, ids.map { TunnelProtocol.Server(it, it, TunnelProtocol.SERVER_TYPE) }))
        fun last() = TunnelProtocol.decodeMux(sent.last().payload).meta
        fun incoming(meta: JsonObject, body: String = "", source: String = "desktop", destination: String = "client") = session.binary(TunnelProtocol.encodeFrame(TunnelProtocol.Frame(2, source, destination, TunnelProtocol.encodeMux(TunnelProtocol.Mux(meta, body.toByteArray())))))
        fun openReply(id: String, ok: Boolean = true) = incoming(buildJsonObject { put("type", "ws_open_result"); put("connId", id); put("ok", ok); if (!ok) put("status", 401) })
    }
    @Test fun httpWaitsForDiscoveryAndIgnoresSpoofedAndWrongDestinationResponses() = runTest {
        val f = Fixture()
        val request = async { f.session.http("desktop", "POST", "/api/auth") }; runCurrent()
        assertTrue(f.sent.isEmpty()); f.ready(); runCurrent()
        val id = f.last()["id"]!!.jsonPrimitive.content
        val response = buildJsonObject { put("type", "http_response"); put("id", id); put("status", 200) }
        f.incoming(response, "bad", source = "other"); f.incoming(response, "bad", destination = "other"); runCurrent(); assertFalse(request.isCompleted)
        f.incoming(response, "ok"); runCurrent(); assertEquals("ok", request.await().body.toString(Charsets.UTF_8))
    }
    @Test fun readinessConsumesTheSameTimeoutBudgetWithoutSendingLater() = runTest {
        val f = Fixture()
        val result = async { runCatching { f.session.http("desktop", "POST", "/api/auth", timeoutMs = 100) } }
        runCurrent(); advanceTimeBy(101); runCurrent(); assertTrue(result.await().isFailure)
        f.ready(); runCurrent(); assertTrue(f.sent.isEmpty())
    }
    @Test fun streamBuffersEarlyDataAndPreservesCloseCode() = runTest {
        val f = Fixture(); f.ready()
        val opening = async { f.session.open("desktop") }; runCurrent(); val id = f.last()["connId"]!!.jsonPrimitive.content
        f.incoming(buildJsonObject { put("type", "ws_data"); put("connId", id); put("opcode", "text") }, "hello")
        f.openReply(id); runCurrent(); val stream = opening.await()
        assertEquals("hello", stream.receive().toString(Charsets.UTF_8)); assertTrue(stream.sendText("rpc"))
        assertEquals("ws_data", f.last()["type"]!!.jsonPrimitive.content)
        f.incoming(buildJsonObject { put("type", "ws_close"); put("connId", id); put("code", 4401); put("reason", "secret") })
        val failure = runCatching { stream.receive() }.exceptionOrNull()
        assertTrue(failure is WebSocketCloseFailure); assertFalse(failure.toString().contains("secret")); assertFalse(stream.sendText("late"))
    }
    @Test fun cancellationAndLateOpenConfirmationSendCleanupInsteadOfRevivingStream() = runTest {
        val f = Fixture(); f.ready()
        val opening = async { f.session.open("desktop") }; runCurrent(); val id = f.last()["connId"]!!.jsonPrimitive.content
        opening.cancelAndJoin(); assertEquals("ws_close", f.last()["type"]!!.jsonPrimitive.content)
        val count = f.sent.size; f.openReply(id)
        assertEquals(count + 1, f.sent.size); assertEquals("ws_close", f.last()["type"]!!.jsonPrimitive.content)
    }
    @Test fun offlineFailsOnlyThatTargetAndSocketFailureNeverReplays() = runTest {
        val f = Fixture(); f.ready("desktop", "other")
        val first = async { runCatching { f.session.http("desktop", "POST", "/x") } }
        val second = async { runCatching { f.session.http("other", "POST", "/x") } }; runCurrent()
        f.session.control(TunnelProtocol.Control.Offline("desktop")); runCurrent()
        assertTrue(first.await().isFailure); assertFalse(second.isCompleted)
        val count = f.sent.size; f.session.close(); runCurrent(); assertTrue(second.await().isFailure)
        f.ready(); runCurrent(); assertEquals(count, f.sent.size); assertTrue(f.session.servers.value.isEmpty())
    }
    @Test fun rejectionAndSendFailureResolvePendingOperations() = runTest {
        val f = Fixture(); f.ready()
        val opening = async { runCatching { f.session.open("desktop") } }; runCurrent(); f.openReply(f.last()["connId"]!!.jsonPrimitive.content, false); runCurrent()
        assertEquals(401, (opening.await().exceptionOrNull() as HttpFailure).statusCode)
        f.allowSend = false
        assertTrue(runCatching { f.session.http("desktop", "POST", "/x") }.isFailure)
        assertTrue(f.session.servers.value.isEmpty())
    }
    @Test fun targetRoutingErrorFailsStreamAndRetriesCleanupBeforeNextOpen() = runTest {
        val f = Fixture(); f.ready()
        val opening = async { f.session.open("desktop") }; runCurrent(); val id = f.last()["connId"]!!.jsonPrimitive.content
        f.openReply(id); runCurrent(); val stream = opening.await()
        f.session.control(TunnelProtocol.Control.Error("target_buffer_full", "desktop"))
        assertTrue(runCatching { stream.receive() }.isFailure)
        val before = f.sent.size
        val next = async { f.session.open("desktop") }; runCurrent()
        val types = f.sent.drop(before).map { TunnelProtocol.decodeMux(it.payload).meta["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("ws_close", "ws_open"), types)
        next.cancelAndJoin()
    }
    @Test fun queuedFrameOverflowClosesOnlyAffectedStream() = runTest {
        val f = Fixture(); f.ready()
        val opening = async { f.session.open("desktop") }; runCurrent(); val id = f.last()["connId"]!!.jsonPrimitive.content
        f.openReply(id); runCurrent(); val stream = opening.await()
        repeat(257) { f.incoming(buildJsonObject { put("type", "ws_data"); put("connId", id); put("opcode", "text") }, "x") }
        assertTrue(runCatching { stream.receive() }.isFailure)
        assertEquals("ws_close", f.last()["type"]!!.jsonPrimitive.content)
        assertEquals(1, f.session.servers.value.size)
    }
    @Test fun failedCleanupCannotRepublishOnlineServersAfterTerminalFailure() = runTest {
        val f = Fixture(); f.ready()
        val opening = async { f.session.open("desktop") }; runCurrent(); val id = f.last()["connId"]!!.jsonPrimitive.content
        f.openReply(id); runCurrent(); opening.await()
        f.allowSend = false
        f.session.control(TunnelProtocol.Control.Error("target_buffer_full", "desktop"))
        assertTrue(f.session.servers.value.isEmpty())
        assertTrue(runCatching { f.session.open("desktop") }.isFailure)
    }
    @Test fun failedStaleCloseFlushPreservesCleanupForNextEpoch() {
        val pending = mapOf("first" to "desktop", "second" to "desktop")
        val failed = Fixture(); failed.session.restoreCleanup(pending); failed.allowSend = false
        failed.ready()
        assertEquals(1, failed.sent.size)
        assertEquals(pending, failed.session.cleanupSnapshot())

        val next = Fixture(); next.session.restoreCleanup(failed.session.cleanupSnapshot()); next.ready()
        assertEquals(pending.keys.toList(), next.sent.map { TunnelProtocol.decodeMux(it.payload).meta.string("connId") })
        assertTrue(next.session.cleanupSnapshot().isEmpty())
    }
    @Test fun gracefulCloseSendsRemoteCleanupButSocketLossDoesNot() = runTest {
        val f = Fixture(); f.ready()
        val opening = async { f.session.open("desktop") }; runCurrent(); val id = f.last()["connId"]!!.jsonPrimitive.content
        f.openReply(id); runCurrent(); val stream = opening.await()
        f.session.close()
        assertEquals("ws_close", f.last()["type"]!!.jsonPrimitive.content)
        assertTrue(runCatching { stream.receive() }.isFailure)
        val count = f.sent.size; f.session.close(); f.session.disconnected(); assertEquals(count, f.sent.size)
    }
    @Test fun disconnectResolvesAllImmediatelyResumingWaitersWithoutMutatingIteration() = runTest {
        val f = Fixture(); f.ready()
        val requests = (0 until 3).map { async(UnconfinedTestDispatcher(testScheduler)) { runCatching { f.session.http("desktop", "GET", "/$it") } } }
        assertEquals(3, f.sent.size)
        f.session.disconnected()
        assertTrue(requests.all { it.await().isFailure })
        assertTrue(f.session.servers.value.isEmpty())
    }
    @Test fun earlyImmediateWaiterSeesPublishedServerList() = runTest {
        val f = Fixture()
        val result = async(UnconfinedTestDispatcher(testScheduler)) { runCatching { f.session.http("desktop", "GET", "/x") } }
        f.ready(); assertEquals(1, f.sent.size)
        result.cancelAndJoin()
    }
}
