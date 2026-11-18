package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TunnelConnectionProviderTest {
    private class Wire(val listener: TunnelGatewaySocket.Listener) : GatewayConnection {
        @Volatile var closed = false
        override fun connect() { listener.onOpen() }
        override fun sendText(text: String): Boolean { listener.onText("""{"type":"server_list","servers":[]}"""); return true }
        override fun sendBinary(bytes: ByteArray) = true
        override fun close() { closed = true }
        fun online() = listener.onText("""{"type":"server_online","server":{"uuid":"pc","name":"PC","type":"CodingProfessionalAgent"}}""")
    }
    private val config = StoredConnection("id", "PC", "", "pw", false, mode = ConnectionMode.Tunnel,
        tunnelBaseUrl = "https://gateway.test", tunnelApiKey = "key", tunnelServerId = "pc")
    @Test fun offlineHandleCloseUnblocksWaitWithoutClosingOtherLease() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wires = CopyOnWriteArrayList<Wire>()
        val manager = TunnelClientManager(scope, MemoryTunnelIdentityStore()) { ep, key ->
            TunnelGatewaySession(ep, key, scope, MemoryTunnelIdentityStore(), factory = { _, _, _, _, listener -> Wire(listener).also(wires::add) })
        }
        val provider = ManagedTunnelConnectionProvider(manager, scope)
        try {
            val a = provider.acquire(config); val b = provider.acquire(config)
            assertEquals(1, wires.size)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { a.awaitReady() } }
            a.close()
            assertTrue(withTimeout(5000) { waiting.await() }.isFailure)
            assertFalse(wires.single().closed)
            wires.single().online()
            withTimeout(5000) { b.awaitReady() }
            val transport = b.transport()
            transport.close(); assertFalse(wires.single().closed)
            b.close(); assertTrue(wires.single().closed)
            assertTrue(provider.discover("https://gateway.test", "key", false).isEmpty())
            assertEquals(2, wires.size); assertTrue(wires.last().closed)
        } finally { manager.close(); scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }
    @Test fun liveDiscoveryTracksOnlineOfflineAndCancellationReleasesOnlySearchLease() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wires = CopyOnWriteArrayList<Wire>()
        val manager = TunnelClientManager(scope, MemoryTunnelIdentityStore()) { ep, key ->
            TunnelGatewaySession(ep, key, scope, MemoryTunnelIdentityStore(), factory = { _, _, _, _, listener -> Wire(listener).also(wires::add) })
        }
        val provider = ManagedTunnelConnectionProvider(manager, scope)
        val states = Channel<TunnelDiscoveryState>(Channel.UNLIMITED)
        var subscription: Job? = null
        try {
            val retained = provider.acquire(config)
            subscription = launch { provider.watch("https://gateway.test", "key", false).collect { states.send(it) } }
            suspend fun next(matches: (TunnelDiscoveryState) -> Boolean): TunnelDiscoveryState = withTimeout(5000) {
                var state = states.receive()
                while (!matches(state)) state = states.receive()
                state
            }
            next { it.phase == TunnelDiscoveryPhase.Connected }
            assertEquals(1, wires.size)
            wires.single().online()
            assertEquals("pc", next { it.servers.isNotEmpty() }.servers.single().uuid)
            wires.single().listener.onText("""{"type":"server_offline","server":{"uuid":"pc"}}""")
            next { it.phase == TunnelDiscoveryPhase.Connected && it.servers.isEmpty() }
            subscription.cancelAndJoin()
            assertFalse(wires.single().closed)
            retained.close(); assertTrue(wires.single().closed)
        } finally { subscription?.cancelAndJoin(); manager.close(); scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }
    @Test fun directConfigurationCannotAcquireGateway() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = TunnelClientManager(scope, MemoryTunnelIdentityStore()) { _, _ -> error("Unexpected gateway creation") }
        try {
            val provider = ManagedTunnelConnectionProvider(manager, scope)
            assertTrue(runCatching { provider.acquire(config.copy(mode = ConnectionMode.Direct, address = "https://desktop.test")) }.isFailure)
        } finally { manager.close(); scope.cancel() }
    }
}
