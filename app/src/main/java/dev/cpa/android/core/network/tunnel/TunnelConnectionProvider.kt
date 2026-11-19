package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.network.DesktopTransport
import dev.cpa.android.core.network.HttpFailure
import dev.cpa.android.core.network.ProtocolFailure
import dev.cpa.android.core.storage.StoredConnection
import dev.cpa.android.core.storage.validated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.Closeable

enum class TunnelDiscoveryPhase { Connecting, Connected, Reconnecting, Failed }
enum class TunnelDiscoveryNotice { Reconnecting, AuthenticationFailed, HandshakeFailed, IdentityStorageFailed, Closed, RecentGatewaySaveFailed }
data class TunnelDiscoveryState(val phase: TunnelDiscoveryPhase, val servers: List<TunnelProtocol.Server> = emptyList(),
    val error: String? = null, val notice: TunnelDiscoveryNotice? = null, val reconnectAttempt: Int = 0)

/** Local diagnostics are typed; no presentation language or credentials enter the transport state. */
internal fun tunnelDiscoveryState(state: TunnelGatewaySession.State): TunnelDiscoveryState = when (val phase = state.phase) {
    TunnelGatewaySession.Phase.Connecting -> TunnelDiscoveryState(TunnelDiscoveryPhase.Connecting)
    TunnelGatewaySession.Phase.Connected -> TunnelDiscoveryState(TunnelDiscoveryPhase.Connected, state.servers)
    is TunnelGatewaySession.Phase.Reconnecting -> TunnelDiscoveryState(TunnelDiscoveryPhase.Reconnecting,
        notice = TunnelDiscoveryNotice.Reconnecting, reconnectAttempt = phase.attempt)
    is TunnelGatewaySession.Phase.Failed -> TunnelDiscoveryState(TunnelDiscoveryPhase.Failed, notice = when (phase.reason) {
        TunnelGatewaySession.Failure.UNAUTHORIZED, TunnelGatewaySession.Failure.REVOKED -> TunnelDiscoveryNotice.AuthenticationFailed
        TunnelGatewaySession.Failure.INVALID_HANDSHAKE -> TunnelDiscoveryNotice.HandshakeFailed
        TunnelGatewaySession.Failure.IDENTITY_STORAGE -> TunnelDiscoveryNotice.IdentityStorageFailed
    })
    TunnelGatewaySession.Phase.Closed -> TunnelDiscoveryState(TunnelDiscoveryPhase.Failed, notice = TunnelDiscoveryNotice.Closed)
}

/** Repository lifetime owns this lease; individual RPC attempts own separate transport leases. */
interface TunnelConnectionHandle : Closeable {
    suspend fun awaitReady()
    fun transport(): DesktopTransport
}
fun interface TunnelConnectionProvider {
    fun watch(baseUrl: String, apiKey: String, allowCleartext: Boolean): Flow<TunnelDiscoveryState> = flow {
        emit(TunnelDiscoveryState(TunnelDiscoveryPhase.Connected, discover(baseUrl, apiKey, allowCleartext)))
    }
    suspend fun discover(baseUrl: String, apiKey: String, allowCleartext: Boolean): List<TunnelProtocol.Server> = throw UnsupportedOperationException()
    suspend fun acquire(configuration: StoredConnection): TunnelConnectionHandle
}
internal class ManagedTunnelConnectionProvider(
    private val manager: TunnelClientManager,
    private val scope: CoroutineScope,
) : TunnelConnectionProvider {
    override fun watch(baseUrl: String, apiKey: String, allowCleartext: Boolean): Flow<TunnelDiscoveryState> = flow {
        var lease: TunnelClientManager.Lease? = null
        try {
            val acquired = withContext(Dispatchers.IO) {
                val endpoint = TunnelProtocol.endpoint(baseUrl)
                require(endpoint.webSocketUrl.startsWith("wss:") || allowCleartext) { "Unencrypted tunnel requires authorization" }
                manager.acquire(endpoint.baseUrl, apiKey).also { lease = it }
            }
            acquired.session.state.collect { state ->
                emit(tunnelDiscoveryState(state))
            }
        } finally { lease?.close() }
    }
    override suspend fun discover(baseUrl: String, apiKey: String, allowCleartext: Boolean): List<TunnelProtocol.Server> {
        var lease: TunnelClientManager.Lease? = null
        try {
            return withContext(Dispatchers.IO) {
                val endpoint = TunnelProtocol.endpoint(baseUrl)
                require(endpoint.webSocketUrl.startsWith("wss:") || allowCleartext) { "Unencrypted tunnel requires authorization" }
                val acquired = manager.acquire(endpoint.baseUrl, apiKey).also { lease = it }
                val state = kotlinx.coroutines.withTimeout(15_000) {
                    acquired.session.state.first { it.phase == TunnelGatewaySession.Phase.Connected || it.phase is TunnelGatewaySession.Phase.Failed || it.phase == TunnelGatewaySession.Phase.Closed }
                }
                if (state.phase != TunnelGatewaySession.Phase.Connected) { acquired.session.currentMux(); throw dev.cpa.android.core.network.Disconnected() }
                state.servers
            }
        } finally { lease?.close() }
    }
    override suspend fun acquire(configuration: StoredConnection): TunnelConnectionHandle {
        var acquired: TunnelConnectionHandle? = null
        try { return withContext(Dispatchers.IO) {
        val config = configuration.validated()
        require(config.mode == dev.cpa.android.core.storage.ConnectionMode.Tunnel) { "Tunnel configuration required" }
        val lease = manager.acquire(config.tunnelBaseUrl, config.tunnelApiKey)
        val handle = object : TunnelConnectionHandle {
            private val lock = Any()
            private var closed = false
            private val released = MutableStateFlow(false)
            override suspend fun awaitReady() {
                val state = combine(lease.session.state, released) { state, closed -> state to closed }.first { (value, closed) ->
                    closed || value.phase is TunnelGatewaySession.Phase.Failed || value.phase == TunnelGatewaySession.Phase.Closed ||
                        value.phase == TunnelGatewaySession.Phase.Connected && value.servers.any { it.uuid == config.tunnelServerId }
                }.first
                synchronized(lock) { check(!closed) { "Tunnel connection closed" } }
                if (state.phase == TunnelGatewaySession.Phase.Closed) throw dev.cpa.android.core.network.Disconnected()
                if (state.phase is TunnelGatewaySession.Phase.Failed) {
                    when (state.phase.reason) {
                        TunnelGatewaySession.Failure.UNAUTHORIZED -> throw HttpFailure(401)
                        TunnelGatewaySession.Failure.REVOKED -> throw TunnelApiKeyRevoked()
                        TunnelGatewaySession.Failure.INVALID_HANDSHAKE -> throw ProtocolFailure("Invalid tunnel handshake")
                        TunnelGatewaySession.Failure.IDENTITY_STORAGE -> throw ProtocolFailure("Tunnel identity storage unavailable")
                    }
                }
            }
            override fun transport(): DesktopTransport = synchronized(lock) {
                check(!closed) { "Tunnel connection closed" }
                TunnelTransport(manager.acquire(config.tunnelBaseUrl, config.tunnelApiKey), config.tunnelServerId, scope)
            }
            override fun close() = synchronized(lock) {
                if (!closed) { closed = true; released.value = true; lease.close() }
            }
        }
        handle.also { acquired = it }
        } } catch (failure: Throwable) { acquired?.close(); throw failure }
    }
}
