package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import kotlinx.serialization.json.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelGatewaySessionTest {
    private class Wire(val id: String, val listener: TunnelGatewaySocket.Listener) : GatewayConnection {
        var closed = false
        var connected = false
        var allowSend = true
        val texts = mutableListOf<String>()
        val binaries = mutableListOf<ByteArray>()
        override fun connect() { connected = true; listener.onOpen() }
        override fun sendText(text: String): Boolean { texts += text; return allowSend }
        override fun sendBinary(bytes: ByteArray): Boolean { binaries += bytes; return allowSend }
        override fun close() { closed = true }
        fun ready() = listener.onText("""{"type":"server_list","servers":[{"uuid":"desktop","name":"PC","type":"CodingProfessionalAgent"}]}""")
    }
    private class Fixture(scope: CoroutineScope) {
        val wires = mutableListOf<Wire>()
        val identities = MemoryTunnelIdentityStore()
        val session = TunnelGatewaySession(TunnelProtocol.endpoint("https://example.test"), "secret", scope, identities,
            jitter = { 1.0 }, factory = { _, _, id, _, listener -> Wire(id, listener).also(wires::add) })
    }
    @Test fun discoveryAndEpochFenceFailPendingRequestsWithoutReplay() = runTest {
        val f = Fixture(backgroundScope); f.session.start(); runCurrent()
        val old = f.wires.single(); assertEquals(1, old.texts.size)
        old.ready(); runCurrent()
        assertEquals(TunnelGatewaySession.Phase.Connected, f.session.state.value.phase)
        val mux = f.session.currentMux()
        val request = async { runCatching { mux.http("desktop", "POST", "/api/auth") } }; runCurrent()
        assertEquals(1, old.binaries.size)
        old.listener.onClosed(NetworkFailure()); runCurrent()
        assertTrue(request.await().isFailure); assertTrue(old.closed)
        assertTrue(f.session.state.value.servers.isEmpty())
        assertTrue(runCatching { f.session.currentMux() }.isFailure)
        advanceTimeBy(1500); runCurrent()
        assertEquals(2, f.wires.size)
        old.ready(); runCurrent()
        assertTrue(f.session.state.value.phase is TunnelGatewaySession.Phase.Reconnecting)
        f.wires.last().ready(); runCurrent()
        assertTrue(f.wires.last().binaries.isEmpty())
        assertNotSame(mux, f.session.currentMux())
        f.session.close()
    }
    @Test fun reconnectClosesOldVirtualStreamsBeforeNewRequests() = runTest {
        val f = Fixture(backgroundScope); f.session.start(); runCurrent()
        f.wires.last().ready(); runCurrent()
        val opening = async { runCatching { f.session.currentMux().open("desktop") } }; runCurrent()
        val oldOpen = TunnelProtocol.decodeMux(TunnelProtocol.decodeFrame(f.wires.last().binaries.single()).payload).meta
        f.wires.last().listener.onClosed(NetworkFailure()); runCurrent()
        assertTrue(opening.await().isFailure)
        advanceTimeBy(1500); runCurrent()
        val next = f.wires.last(); next.ready(); runCurrent()
        val cleanup = TunnelProtocol.decodeMux(TunnelProtocol.decodeFrame(next.binaries.single()).payload).meta
        assertEquals("ws_close", cleanup["type"]!!.jsonPrimitive.content)
        assertEquals(oldOpen["connId"], cleanup["connId"])
        f.session.close()
    }
    @Test fun identityRotationDiscardsCleanupFromPreviousClient() = runTest {
        val f = Fixture(backgroundScope); f.session.start(); runCurrent()
        f.wires.last().ready(); runCurrent()
        val opening = async { runCatching { f.session.currentMux().open("desktop") } }; runCurrent()
        f.wires.last().listener.onClosed(NetworkFailure()); runCurrent(); opening.await()
        f.identities.rotate()
        advanceTimeBy(1500); runCurrent()
        f.wires.last().ready(); runCurrent()
        assertTrue(f.wires.last().binaries.isEmpty())
        f.session.close()
    }
    @Test fun terminalAuthenticationFailuresDoNotWakeOrAutoRetry() = runTest {
        for (cause in listOf(HttpFailure(400), HttpFailure(401), HttpFailure(403), TunnelApiKeyRevoked())) {
            val f = Fixture(backgroundScope); f.session.start(); runCurrent()
            f.wires.single().listener.onClosed(cause); runCurrent()
            assertTrue(f.session.state.value.phase is TunnelGatewaySession.Phase.Failed)
            f.session.wake(); advanceTimeBy(60_000); runCurrent()
            assertEquals(1, f.wires.size)
            f.session.retryNow(); runCurrent(); assertEquals(2, f.wires.size)
            f.session.close()
        }
    }
    @Test fun thirdConflictRotatesIdentityAndSuccessfulDiscoveryResetsBackoff() = runTest {
        val f = Fixture(backgroundScope); f.session.start(); runCurrent()
        val original = f.wires.single().id
        for (wait in listOf(2000L, 4000L, 8000L)) {
            f.wires.last().listener.onClosed(HttpFailure(409)); runCurrent()
            advanceTimeBy(wait); runCurrent()
        }
        assertEquals(4, f.wires.size)
        assertTrue(f.wires.take(3).all { it.id == original })
        assertNotEquals(original, f.wires.last().id)
        f.wires.last().ready(); runCurrent()
        f.wires.last().listener.onClosed(NetworkFailure()); runCurrent()
        assertEquals(TunnelGatewaySession.Phase.Reconnecting(1), f.session.state.value.phase)
        advanceTimeBy(1500); runCurrent(); assertEquals(5, f.wires.size)
        f.session.close()
    }
    @Test fun stalledDiscoveryAndWakeHaveBoundedRecovery() = runTest {
        val f = Fixture(backgroundScope); f.session.start(); runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertTrue(f.wires.first().closed)
        f.session.wake(); runCurrent(); assertEquals(2, f.wires.size)
        f.wires.last().ready(); runCurrent()
        f.session.wake(); runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertTrue(f.session.state.value.phase is TunnelGatewaySession.Phase.Reconnecting)
        f.session.close(); advanceTimeBy(60_000); runCurrent()
        assertEquals(2, f.wires.size)
        assertEquals(TunnelGatewaySession.Phase.Closed, f.session.state.value.phase)
    }
    @Test fun duplicateTerminalCallbacksAndLateDiscoveryCannotReviveDisposedOwner() = runTest {
        val f = Fixture(backgroundScope); f.session.start(); runCurrent()
        val old = f.wires.single()
        old.listener.onClosed(NetworkFailure()); old.listener.onClosed(HttpFailure(401)); old.ready(); runCurrent()
        assertEquals(TunnelGatewaySession.Phase.Reconnecting(1), f.session.state.value.phase)
        f.session.close(); old.ready(); runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(1, f.wires.size)
        assertEquals(TunnelGatewaySession.Phase.Closed, f.session.state.value.phase)
    }
    @Test fun identityPersistenceFailureIsTerminalAndDoesNotDial() = runTest {
        var dialed = false
        val identities = object : TunnelIdentityStore {
            override fun current(): String = error("private-storage-path")
            override fun rotate(): String = error("private-storage-path")
        }
        val session = TunnelGatewaySession(TunnelProtocol.endpoint("https://example.test"), "key", backgroundScope, identities,
            factory = { _, _, id, _, listener -> dialed = true; Wire(id, listener) })
        session.start(); runCurrent()
        assertFalse(dialed)
        assertEquals(TunnelGatewaySession.Phase.Failed(TunnelGatewaySession.Failure.IDENTITY_STORAGE), session.state.value.phase)
        assertFalse(runCatching { session.currentMux() }.exceptionOrNull().toString().contains("private-storage-path"))
        session.wake(); advanceTimeBy(60_000); runCurrent(); assertFalse(dialed)
        session.close()
    }
    @Test fun leasesShareOnlyMatchingGatewayAndSecretAndLastReleaseCloses() = runTest {
        val sessions = mutableListOf<TunnelGatewaySession>()
        val manager = TunnelClientManager(backgroundScope, MemoryTunnelIdentityStore()) { _, _ ->
            Fixture(backgroundScope).session.also(sessions::add)
        }
        val a = manager.acquire("https://example.test", " secret ")
        val b = manager.acquire("wss://example.test/v1/tunnel", "secret")
        val c = manager.acquire("https://example.test", "different")
        assertSame(a.session, b.session); assertNotSame(a.session, c.session)
        a.close(); a.close()
        assertNotEquals(TunnelGatewaySession.Phase.Closed, b.session.state.value.phase)
        b.close(); assertEquals(TunnelGatewaySession.Phase.Closed, a.session.state.value.phase)
        manager.close(); c.close()
        assertEquals(TunnelGatewaySession.Phase.Closed, c.session.state.value.phase)
        assertTrue(runCatching { manager.acquire("https://example.test", "secret") }.isFailure)
    }
}
