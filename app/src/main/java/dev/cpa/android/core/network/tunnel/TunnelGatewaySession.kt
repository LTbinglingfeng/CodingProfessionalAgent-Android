package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Persistence is supplied by the application; identities are never credentials. */
interface TunnelIdentityStore {
    fun current(): String
    fun rotate(): String
}
class MemoryTunnelIdentityStore : TunnelIdentityStore {
    private var id = UUID.randomUUID().toString()
    @Synchronized override fun current() = id
    @Synchronized override fun rotate(): String { id = UUID.randomUUID().toString(); return id }
}

internal interface GatewayConnection : Closeable {
    fun connect()
    fun sendText(text: String): Boolean
    fun sendBinary(bytes: ByteArray): Boolean
    fun closeGracefully() = close()
}

/** Shared physical gateway owner. Each dial replaces its mux; in-flight work is never replayed. */
internal class TunnelGatewaySession(
    private val endpoint: TunnelProtocol.Endpoint,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val identities: TunnelIdentityStore,
    private val name: String = "CPA-Android",
    private val jitter: () -> Double = { Random.nextDouble(0.85, 1.15) },
    private val factory: (TunnelProtocol.Endpoint, String, String, String, TunnelGatewaySocket.Listener) -> GatewayConnection =
        { ep, key, id, label, listener -> TunnelGatewaySocket(ep, key, id, label, listener) },
) : Closeable {
    sealed interface Phase {
        data object Connecting : Phase
        data object Connected : Phase
        data class Reconnecting(val attempt: Int) : Phase
        data class Failed(val reason: Failure) : Phase
        data object Closed : Phase
    }
    enum class Failure { UNAUTHORIZED, INVALID_HANDSHAKE, REVOKED, IDENTITY_STORAGE }
    data class State(val phase: Phase, val servers: List<TunnelProtocol.Server> = emptyList())
    private val lock = Any()
    private val mutableState = MutableStateFlow(State(Phase.Connecting))
    val state = mutableState.asStateFlow()
    private var epoch = 0L
    private var disposed = false
    private var attempt = 0
    private var conflicts = 0
    private var socket: GatewayConnection? = null
    private var mux: TunnelMuxSession? = null
    private var retry: Job? = null
    private var discoveryTimeout: Job? = null
    private var listCounter = 0L
    private var cleanupClient: String? = null
    private var cleanup = emptyMap<String, String>()
    private val queuedBytes = AtomicLong()
    private fun retireMux() {
        mux?.let { old ->
            old.disconnected()
            cleanupClient = old.clientId; cleanup = old.cleanupSnapshot()
        }
        mux = null
    }
    private val events = Channel<() -> Unit>(256)
    private val eventJob = scope.launch { for (event in events) synchronized(lock) { event() } }

    fun start() = synchronized(lock) { if (!disposed && epoch == 0L) dial() }
    /** Connecting operations may wait for discovery; recovery never queues/replays a mutation. */
    fun currentMux(): TunnelMuxSession = synchronized(lock) {
        when (val phase = mutableState.value.phase) {
            is Phase.Failed -> throw when (phase.reason) {
                Failure.REVOKED -> TunnelApiKeyRevoked()
                Failure.UNAUTHORIZED -> HttpFailure(401)
                Failure.INVALID_HANDSHAKE -> HttpFailure(400)
                Failure.IDENTITY_STORAGE -> ProtocolFailure("Tunnel identity storage unavailable")
            }
            Phase.Closed, is Phase.Reconnecting -> throw Disconnected()
            else -> mux ?: throw Disconnected()
        }
    }
    fun retryNow() = synchronized(lock) {
        if (!disposed) { attempt = 0; conflicts = 0; dial() }
    }
    fun refresh() = synchronized(lock) {
        if (!disposed && mutableState.value.phase == Phase.Connected) requestList(epoch)
    }
    fun wake() = synchronized(lock) {
        if (!disposed) when (mutableState.value.phase) {
            is Phase.Reconnecting -> retryNow()
            Phase.Connected -> {
                requestList(epoch)
                armDiscoveryTimeout(epoch)
            }
            else -> Unit // Authentication failures require explicit retry/edited credentials.
        }
    }
    private fun dial() {
        epoch++
        val generation = epoch
        retry?.cancel(); retry = null
        discoveryTimeout?.cancel(); discoveryTimeout = null
        retireMux(); socket?.close()
        mux = null; socket = null
        mutableState.value = State(if (attempt == 0) Phase.Connecting else Phase.Reconnecting(attempt))
        val wire = AtomicReference<GatewayConnection?>()
        val clientId = try { identities.current() } catch (_: Exception) {
            mutableState.value = State(Phase.Failed(Failure.IDENTITY_STORAGE)); return
        }
        val nextMux = TunnelMuxSession(clientId) { bytes ->
            val sent = wire.get()?.sendBinary(bytes) == true
            if (!sent) scope.launch { synchronized(lock) { disconnected(generation, NetworkFailure()) } }
            sent
        }
        if (cleanupClient == nextMux.clientId) nextMux.restoreCleanup(cleanup)
        cleanup = emptyMap(); cleanupClient = null
        mux = nextMux
        fun dispatch(bytes: Long = 0, block: () -> Unit) {
            if (queuedBytes.addAndGet(bytes) > TunnelProtocol.MAX_INBOUND) {
                queuedBytes.addAndGet(-bytes)
                scope.launch { synchronized(lock) { disconnected(generation, NetworkFailure()) } }
                return
            }
            if (!events.trySend {
                queuedBytes.addAndGet(-bytes)
                if (!disposed && epoch == generation) block()
            }.isSuccess) {
                queuedBytes.addAndGet(-bytes)
                scope.launch { synchronized(lock) { disconnected(generation, NetworkFailure()) } }
            }
        }
        val listener = object : TunnelGatewaySocket.Listener {
            override fun onOpen() = dispatch { requestList(generation); armDiscoveryTimeout(generation) }
            override fun onText(text: String) = dispatch(text.length.toLong() * 2) {
                val control = TunnelProtocol.decodeControl(text.toByteArray()) ?: return@dispatch
                nextMux.control(control)
                if (control is TunnelProtocol.Control.ListServers) {
                    discoveryTimeout?.cancel(); discoveryTimeout = null
                    attempt = 0; conflicts = 0
                    mutableState.value = State(Phase.Connected, nextMux.servers.value)
                } else mutableState.value = mutableState.value.copy(servers = nextMux.servers.value)
            }
            override fun onBinary(bytes: ByteArray) = dispatch(bytes.size.toLong()) { nextMux.binary(bytes) }
            override fun onClosed(cause: Throwable) = dispatch { disconnected(generation, cause) }
        }
        try {
            val connection = factory(endpoint, apiKey, nextMux.clientId, name, listener)
            socket = connection; wire.set(connection)
            // Also bounds a socket implementation that never calls onOpen/onClosed.
            armDiscoveryTimeout(generation)
            connection.connect()
        } catch (_: Exception) { disconnected(generation, NetworkFailure()) }
    }
    private fun requestList(generation: Long) {
        listCounter++
        if (socket?.sendText(TunnelProtocol.listServers("android-sync-$listCounter")) != true) disconnected(generation, NetworkFailure())
    }
    private fun armDiscoveryTimeout(generation: Long) {
        discoveryTimeout?.cancel()
        discoveryTimeout = scope.launch {
            delay(15_000)
            synchronized(lock) { disconnected(generation, NetworkFailure()) }
        }
    }
    private fun disconnected(generation: Long, cause: Throwable) {
        if (disposed || epoch != generation) return
        epoch++ // Reject duplicate close/error and queued old text/binary callbacks immediately.
        discoveryTimeout?.cancel(); discoveryTimeout = null
        retireMux()
        socket?.close(); socket = null
        val status = (cause as? HttpFailure)?.statusCode
        val failure = when {
            cause is TunnelApiKeyRevoked -> Failure.REVOKED
            status == 401 || status == 403 -> Failure.UNAUTHORIZED
            status == 400 -> Failure.INVALID_HANDSHAKE
            else -> null
        }
        if (failure != null) { mutableState.value = State(Phase.Failed(failure)); return }
        if (status == 409) {
            conflicts++
            if (conflicts >= 3) {
                try { identities.rotate() } catch (_: Exception) {
                    mutableState.value = State(Phase.Failed(Failure.IDENTITY_STORAGE)); return
                }
                conflicts = 0
            }
        }
        attempt++
        mutableState.value = State(Phase.Reconnecting(attempt))
        val base = if (status == 409) 2_000L else 1_500L
        val wait = (minOf(20_000L, base * (1L shl minOf(attempt - 1, 5))) * jitter()).toLong().coerceAtLeast(20)
        val expected = epoch
        retry = scope.launch {
            delay(wait)
            synchronized(lock) { if (!disposed && epoch == expected) dial() }
        }
    }
    override fun close() = synchronized(lock) {
        if (!disposed) {
            disposed = true; epoch++
            retry?.cancel(); discoveryTimeout?.cancel()
            events.cancel(); eventJob.cancel()
            cleanup = emptyMap(); cleanupClient = null
            mux?.close(); mux = null
            val retiring = socket; socket = null
            retiring?.closeGracefully()
            if (retiring != null) scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { delay(250) } finally { retiring.close() }
            }
            mutableState.value = State(Phase.Closed)
        }
    }
}
