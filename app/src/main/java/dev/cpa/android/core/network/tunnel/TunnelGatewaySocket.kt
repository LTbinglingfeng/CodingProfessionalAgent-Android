package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.network.*
import java.io.Closeable
import java.nio.ByteBuffer

import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.HandshakeState
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLParameters

/** One physical gateway connection. The owner controls discovery, epochs and retries. */
internal class TunnelGatewaySocket(endpoint: TunnelProtocol.Endpoint, apiKey: String, clientId: String, name: String,
    private val listener: Listener) : GatewayConnection {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onBinary(bytes: ByteArray)
        fun onClosed(cause: Throwable)
    }
    private val uri = URI(endpoint.handshake(clientId, name))
    private val headers = mapOf("Authorization" to "Bearer ${TunnelProtocol.apiKey(apiKey)}")
    private val terminal = AtomicBoolean(false)
    private val status = AtomicInteger(0)
    private val sendLock = Any()
    private fun draft(): Draft_6455 = object : Draft_6455(emptyList(), MAX_WIRE_BYTES) {
        override fun copyInstance(): Draft = draft()
        override fun translateHandshake(buffer: java.nio.ByteBuffer): org.java_websocket.handshake.Handshakedata {
            // The library rejects non-101 status lines before acceptHandshakeAsClient is called.
            if (status.get() == 0 && buffer.remaining() >= 12) {
                val prefix = ByteArray(12); buffer.duplicate().get(prefix)
                val line = prefix.toString(Charsets.US_ASCII)
                if (line.startsWith("HTTP/1.")) line.substring(9, 12).toIntOrNull()?.let(status::set)
            }
            return super.translateHandshake(buffer)
        }
        override fun acceptHandshakeAsClient(request: ClientHandshake, response: ServerHandshake): HandshakeState {
            status.set(response.httpStatus.toInt())
            return super.acceptHandshakeAsClient(request, response)
        }
    }
    private val socket = object : WebSocketClient(uri, draft(), headers, 15_000) {
        override fun onSetSSLParameters(parameters: SSLParameters) {
            super.onSetSSLParameters(parameters)
            parameters.endpointIdentificationAlgorithm = "HTTPS"
        }
        override fun onWebsocketHandshakeSentAsClient(connection: org.java_websocket.WebSocket, request: ClientHandshake) {
            if (terminal.get()) {
                runCatching { this.socket?.close() }
                throw org.java_websocket.exceptions.InvalidDataException(1000, "Connection closed")
            }
            super.onWebsocketHandshakeSentAsClient(connection, request)
        }
        override fun onOpen(handshake: ServerHandshake) {
            if (terminal.get()) closeConnection(1000, "") else listener.onOpen()
        }
        override fun onMessage(bytes: ByteBuffer) {
            if (!terminal.get()) listener.onBinary(ByteArray(bytes.remaining()).also { bytes.get(it) })
        }
        override fun onMessage(message: String) { if (!terminal.get()) listener.onText(message) }
        override fun onClose(code: Int, reason: String, remote: Boolean) {
            finish(if (code == 1008 && reason.contains("api key revoked", ignoreCase = true)) TunnelApiKeyRevoked()
                else if (status.get() !in listOf(0, 101)) HttpFailure(status.get())
                else if (code == 1006 || code < 1000) NetworkFailure() else WebSocketCloseFailure(code))
        }
        override fun onError(exception: Exception) {
            finish(if (status.get() !in listOf(0, 101)) HttpFailure(status.get()) else NetworkFailure())
        }
    }.apply {
        isDaemon = true
        connectionLostTimeout = 0 // The gateway owner must manage liveness and retry policy.
        isTcpNoDelay = true
    }
    override fun connect() {
        if (terminal.get()) return
        try { socket.connect() } catch (_: Exception) { finish(NetworkFailure()) }
    }
    private fun finish(cause: Throwable) {
        if (terminal.compareAndSet(false, true)) {
            runCatching { socket.socket?.close() }
            socket.closeConnection(1000, "")
            listener.onClosed(cause)
        }
    }
    override fun sendText(text: String): Boolean = enqueue(text.toByteArray().size.toLong(), TunnelProtocol.MAX_CONTROL.toLong()) { socket.send(text) }
    override fun sendBinary(bytes: ByteArray): Boolean = enqueue(bytes.size.toLong(), MAX_WIRE_BYTES.toLong()) { socket.send(bytes) }
    private fun enqueue(bytes: Long, max: Long, send: () -> Unit): Boolean = synchronized(sendLock) {
        if (terminal.get() || !socket.isOpen) return false
        if (bytes > max || (socket.connection as org.java_websocket.WebSocketImpl).outQueue.sumOf { it.remaining().toLong() } + bytes + 14 > MAX_WIRE_BYTES) return false
        try { send(); true } catch (_: Exception) { finish(NetworkFailure()); false }
    }
    override fun closeGracefully() {
        terminal.set(true)
        socket.close(1000, "") // Library drains queued frames before the close frame; owner bounds the wait.
    }
    override fun close() {
        terminal.set(true)
        runCatching { socket.socket?.close() }
        socket.closeConnection(1000, "")
    }
    companion object { const val MAX_WIRE_BYTES = TunnelProtocol.MAX_INBOUND }
}

/** Typed revocation without retaining the gateway's close reason. */
class TunnelApiKeyRevoked : java.io.IOException("Tunnel API key revoked")
