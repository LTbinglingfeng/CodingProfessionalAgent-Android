package dev.cpa.android.core.network

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

/** No replay/reconnect, no compression, standard platform trust and HTTPS hostname verification. */
internal class DirectWebSocket(uri: URI, headers: Map<String, String>, private val listener: DesktopTransport.Listener,
    private val removed: () -> Unit) : DesktopTransport.Socket {
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
        override fun onOpen(handshake: ServerHandshake) { if (terminal.get()) closeConnection(1000, "") }
        override fun onMessage(message: String) { if (!terminal.get()) listener.onText(message) }
        override fun onClose(code: Int, reason: String, remote: Boolean) {
            finish(if (status.get() !in listOf(0, 101)) HttpFailure(status.get())
                else if (code == 1006 || code < 1000) NetworkFailure() else WebSocketCloseFailure(code))
        }
        override fun onError(exception: Exception) {
            finish(if (status.get() !in listOf(0, 101)) HttpFailure(status.get()) else NetworkFailure())
        }
    }.apply {
        isDaemon = true
        connectionLostTimeout = 0 // Repository foreground recovery owns connection liveness.
        isTcpNoDelay = true
    }
    fun connect() = socket.connect()
    private fun finish(cause: Throwable) {
        if (terminal.compareAndSet(false, true)) { removed(); listener.onClosed(cause) }
    }
    override fun send(text: String): Boolean = synchronized(sendLock) {
        if (terminal.get() || !socket.isOpen) return false
        val bytes = utf8Bytes(text)
        if (bytes > MAX_WIRE_BYTES || (socket.connection as org.java_websocket.WebSocketImpl).outQueue.sumOf { it.remaining().toLong() } + bytes + 14 > MAX_WIRE_BYTES) return false
        try { socket.send(text); true } catch (_: Exception) { finish(NetworkFailure()); false }
    }
    override fun close() {
        terminal.set(true); removed()
        runCatching { socket.socket?.close() }
        socket.closeConnection(1000, "")
    }
    companion object {
        // Bounded, but enough for base64 of the supported 25 MiB file/image batch plus RPC envelope.
        const val MAX_WIRE_BYTES = 64 * 1024 * 1024
        private fun utf8Bytes(text: String): Long {
            var count = 0L; var index = 0
            while (index < text.length) {
                val char = text[index]
                count += when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> { index++; 4 }
                    char.isSurrogate() -> 1
                    else -> 3
                }
                index++
            }
            return count
        }
    }
}
