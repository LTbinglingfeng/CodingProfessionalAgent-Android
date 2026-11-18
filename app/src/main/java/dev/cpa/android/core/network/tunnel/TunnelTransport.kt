package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One desktop authentication lifetime over a shared gateway lease. Never owns another target's cookies. */
internal class TunnelTransport(
    private val lease: TunnelClientManager.Lease,
    private val target: String,
    parentScope: CoroutineScope,
) : DesktopTransport {
    init { require(TunnelProtocol.validId(target)) { "Invalid tunnel target" } }
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val lock = Any()
    private val authLock = Mutex()
    private var closed = false
    private var cookie: String? = null
    private var authenticatedMux: TunnelMuxSession? = null
    private val sockets = mutableSetOf<VirtualSocket>()

    override suspend fun authenticate(password: String) {
        val operation = synchronized(lock) {
            check(!closed) { "Transport is closed" }
            scope.async {
                authLock.withLock {
                    synchronized(lock) { authenticatedMux = null }
                    val mux = lease.session.currentMux()
                    val oldCookie = synchronized(lock) { cookie }
                    val status = mux.http(target, "GET", "/api/auth/status", headers(oldCookie))
                    successful(status)
                    val body = try { Json.parseToJsonElement(status.body.toString(Charsets.UTF_8)).jsonObject }
                        catch (_: Exception) { throw ProtocolFailure("Invalid auth status") }
                    val required = (body["required"] as? JsonPrimitive)?.booleanOrNull
                        ?: throw ProtocolFailure("Missing auth required flag")
                    val authenticated = (body["authenticated"] as? JsonPrimitive)?.booleanOrNull
                        ?: throw ProtocolFailure("Missing authenticated flag")
                    val nextCookie = when {
                        !required -> null
                        authenticated -> oldCookie
                        else -> {
                            val payload = buildJsonObject { put("password", password) }.toString().toByteArray()
                            val response = mux.http(target, "POST", "/api/auth/login",
                                buildJsonObject { put("Content-Type", "application/json") }, payload)
                            successful(response)
                            parseCookie(TunnelProtocol.header(response.headers, "Set-Cookie"))
                                ?: throw ProtocolFailure("Login did not provide a session cookie")
                        }
                    }
                    synchronized(lock) {
                        if (closed || lease.session.currentMux() !== mux) throw Disconnected()
                        cookie = nextCookie; authenticatedMux = mux
                    }
                }
            }
        }
        try { operation.await() } finally { operation.cancel() }
    }
    private fun successful(response: TunnelMuxSession.HttpResponse) {
        if (response.status !in 200..299) throw HttpFailure(response.status)
    }
    private fun headers(value: String?) = buildJsonObject { value?.let { put("Cookie", it) } }
    override fun openSocket(listener: DesktopTransport.Listener): DesktopTransport.Socket {
        val socket = synchronized(lock) {
            check(!closed) { "Transport is closed" }
            val mux = authenticatedMux ?: throw Disconnected()
            if (lease.session.currentMux() !== mux) throw Disconnected()
            VirtualSocket(mux, headers(cookie), listener).also { sockets.add(it) }
        }
        socket.start()
        return socket
    }
    private inner class VirtualSocket(
        private val mux: TunnelMuxSession,
        private val headers: JsonObject,
        private val listener: DesktopTransport.Listener,
    ) : DesktopTransport.Socket {
        private val terminal = AtomicBoolean()
        private val stream = AtomicReference<TunnelMuxSession.Stream?>()
        private val receiver = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val opened = mux.open(target, headers = headers)
                stream.set(opened)
                if (terminal.get()) { stream.getAndSet(null)?.close(); return@launch }
                while (isActive && !terminal.get()) {
                    val bytes = opened.receive()
                    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                        catch (_: Exception) { throw ProtocolFailure("Invalid WebSocket text") }
                    if (!terminal.get()) listener.onText(text)
                }
            } catch (cause: Exception) { finish(cause) }
        }
        fun start() { receiver.start() }
        override fun send(text: String): Boolean = !terminal.get() && stream.get()?.sendText(text) == true
        private fun finish(cause: Throwable) {
            if (terminal.compareAndSet(false, true)) {
                stream.getAndSet(null)?.close()
                synchronized(lock) { sockets.remove(this) }
                listener.onClosed(cause)
            }
        }
        override fun close() {
            terminal.set(true)
            receiver.cancel()
            stream.getAndSet(null)?.close()
            synchronized(lock) { sockets.remove(this) }
        }
    }
    override fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true; cookie = null; authenticatedMux = null
            sockets.toList().also { sockets.clear() }
        }
        old.forEach { it.close() }
        job.cancel(); lease.close()
    }
    companion object {
        private fun parseCookie(header: String?): String? {
            val pair = header?.substringBefore(';')?.trim() ?: return null
            val equals = pair.indexOf('=')
            if (equals <= 0 || pair.any { it.code < 0x20 || it.code == 0x7f }) return null
            return pair
        }
    }
}
