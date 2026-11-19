package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.network.Disconnected
import dev.cpa.android.core.network.HttpFailure
import dev.cpa.android.core.network.NetworkFailure
import dev.cpa.android.core.network.WebSocketCloseFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.Closeable
import java.util.UUID

/** One gateway socket epoch. Never reused after disconnect; callers must not replay requests. */
class TunnelMuxSession(val clientId: String, private val sendBinary: (ByteArray) -> Boolean) : Closeable {
    init { require(TunnelProtocol.validId(clientId)) }
    private val lock = Any()
    private var terminal = false
    private val ready = CompletableDeferred<Unit>()
    private val mutableServers = MutableStateFlow<List<TunnelProtocol.Server>>(emptyList())
    val servers = mutableServers.asStateFlow()
    private data class PendingHttp(val target: String, val reply: CompletableDeferred<HttpResponse>)
    private data class PendingOpen(val stream: Stream, val reply: CompletableDeferred<Stream>)
    private val requests = mutableMapOf<String, PendingHttp>()
    private val openings = mutableMapOf<String, PendingOpen>()
    private val streams = mutableMapOf<String, Stream>()
    private val staleCloses = linkedMapOf<String, String>()
    private var queuedBytes = 0L
    internal fun cleanupSnapshot(): Map<String, String> = synchronized(lock) { staleCloses.toMap() }
    internal fun restoreCleanup(items: Map<String, String>) = synchronized(lock) {
        if (!terminal) items.entries.take(128).forEach { (id, target) -> staleCloses[id] = target }
    }
    data class HttpResponse(val status: Int, val headers: JsonObject, val body: ByteArray) {
        override fun toString() = "TunnelHttpResponse(status=$status, headers=REDACTED, bodyBytes=${body.size})"
    }
    inner class Stream internal constructor(val id: String, val target: String) : Closeable {
        internal val messages = Channel<ByteArray>(256)
        internal var bytes = 0L
        suspend fun receive(): ByteArray {
            val body = messages.receive()
            synchronized(lock) { val consumed = minOf(bytes, body.size.toLong()); bytes -= consumed; queuedBytes -= consumed }
            return body
        }
        fun sendText(text: String): Boolean = synchronized(lock) {
            if (terminal || streams[id] !== this || id in openings) return@synchronized false
            val sent = runCatching { send(target, buildJsonObject { put("type", "ws_data"); put("connId", id); put("opcode", "text") }, text.toByteArray()) }.getOrDefault(false)
            if (!sent && streams[id] === this) { remoteClose(id, target); failStream(id, NetworkFailure()) }
            sent
        }
        override fun close() = synchronized(lock) {
            if (streams[id] === this) {
                remoteClose(id, target)
                failStream(id, Disconnected())
            }
        }
    }
    suspend fun http(target: String, method: String, path: String, headers: JsonObject = JsonObject(emptyMap()), body: ByteArray = byteArrayOf(), timeoutMs: Long = 15_000): HttpResponse = withTimeout(timeoutMs) {
        ready.await()
        val id = UUID.randomUUID().toString()
        val pending = PendingHttp(target, CompletableDeferred())
        try {
            synchronized(lock) {
                requireOnline(target)
                check(requests.size < 256) { "Too many tunnel requests" }
                requests[id] = pending
                send(target, buildJsonObject { put("type", "http_request"); put("id", id); put("method", method); put("path", path); if (headers.isNotEmpty()) put("headers", headers) }, body)
            }
            pending.reply.await()
        } finally { synchronized(lock) { requests.remove(id) } }
    }
    suspend fun open(target: String, path: String = "/api/ws", headers: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 15_000): Stream = withTimeout(timeoutMs) {
        ready.await()
        val id = UUID.randomUUID().toString()
        val stream = Stream(id, target)
        val pending = PendingOpen(stream, CompletableDeferred())
        var delivered = false
        try {
            synchronized(lock) {
                requireOnline(target)
                check(streams.size < 256) { "Too many tunnel streams" }
                flushCloses(target)
                requireOnline(target)
                streams[id] = stream; openings[id] = pending
                send(target, buildJsonObject { put("type", "ws_open"); put("connId", id); put("path", path); if (headers.isNotEmpty()) put("headers", headers) })
            }
            pending.reply.await().also { delivered = true }
        } finally {
            synchronized(lock) {
                if (!delivered && streams[id] === stream) { remoteClose(id, target); failStream(id, Disconnected()) }
            }
        }
    }
    /** Called only by the socket epoch that owns this object. Malformed/misaddressed data is ignored. */
    fun binary(raw: ByteArray) = synchronized(lock) {
        if (terminal) return@synchronized
        val frame = runCatching { TunnelProtocol.decodeFrame(raw) }.getOrNull() ?: return@synchronized
        if (frame.destination != clientId || frame.type != TunnelProtocol.MUX_TYPE) return@synchronized
        val mux = runCatching { TunnelProtocol.decodeMux(frame.payload) }.getOrNull() ?: return@synchronized
        val meta = mux.meta
        when(meta.string("type")) {
            "http_response" -> {
                val id = meta.string("id") ?: return@synchronized
                val pending = requests[id]?.takeIf { it.target == frame.source } ?: return@synchronized
                requests.remove(id)
                pending.reply.complete(HttpResponse(meta["status"]!!.jsonPrimitive.double.toInt(), meta["headers"] as? JsonObject ?: JsonObject(emptyMap()), mux.body))
            }
            "ws_open_result" -> {
                val id = meta.string("connId") ?: return@synchronized
                val pending = openings[id]
                val ok = meta["ok"]!!.jsonPrimitive.boolean
                if (pending != null) {
                    if (pending.stream.target != frame.source) return@synchronized
                    openings.remove(id)
                    if (ok) pending.reply.complete(pending.stream)
                    else {
                        val status = (meta["status"] as? JsonPrimitive)?.doubleOrNull?.toInt()
                        val cause = status?.let(::HttpFailure) ?: NetworkFailure()
                        pending.reply.completeExceptionally(cause); failStream(id, cause)
                    }
                } else if (ok && streams[id] == null) remoteClose(id, frame.source)
            }
            "ws_data" -> {
                if (meta.string("opcode") != "text") return@synchronized
                val stream = streams[meta.string("connId")]?.takeIf { it.target == frame.source } ?: return@synchronized
                if (mux.body.size.toLong() + queuedBytes > TunnelProtocol.MAX_PAYLOAD) {
                    remoteClose(stream.id, stream.target); failStream(stream.id, NetworkFailure())
                } else {
                    stream.bytes += mux.body.size; queuedBytes += mux.body.size
                    if (!stream.messages.trySend(mux.body).isSuccess) { remoteClose(stream.id, stream.target); failStream(stream.id, NetworkFailure()) }
                }
            }
            "ws_close" -> {
                val id = meta.string("connId") ?: return@synchronized
                if (streams[id]?.target != frame.source) return@synchronized
                val code = (meta["code"] as? JsonPrimitive)?.doubleOrNull?.toInt()
                failStream(id, code?.let(::WebSocketCloseFailure) ?: Disconnected())
            }
        }
    }
    fun control(control: TunnelProtocol.Control) = synchronized(lock) {
        if (terminal) return@synchronized
        val current = mutableServers.value.associateByTo(linkedMapOf()) { it.uuid }
        when(control) {
            is TunnelProtocol.Control.ListServers -> {
                val next = control.servers.associateBy { it.uuid }
                (current.keys - next.keys).forEach { failTarget(it, false) }
                current.clear(); current.putAll(next)
                val targets = staleCloses.values.toSet()
                targets.forEach { if (it in next) flushCloses(it) else staleCloses.entries.removeAll { entry -> entry.value == it } }
            }
            is TunnelProtocol.Control.Online -> { current[control.server.uuid] = control.server; flushCloses(control.server.uuid) }
            is TunnelProtocol.Control.Offline -> { current.remove(control.uuid); failTarget(control.uuid, false) }
            is TunnelProtocol.Control.Error -> {
                val target = control.target ?: return@synchronized // Gateway-level errors belong to its connection/retry owner.
                val offline = control.code == "target_not_found" || control.code == "invalid_target_role"
                if (offline) current.remove(target)
                failTarget(target, !offline)
            }
        }
        if (terminal) return@synchronized
        mutableServers.value = current.values.sortedWith(Comparator { a, b ->
            val byName = a.name.compareTo(b.name, ignoreCase = true)
            if (byName == 0) a.uuid.compareTo(b.uuid) else byName
        })
        if (control is TunnelProtocol.Control.ListServers) ready.complete(Unit)
    }
    private fun requireOnline(target: String) {
        if (terminal || mutableServers.value.none { it.uuid == target }) throw Disconnected()
    }
    private fun send(target: String, meta: JsonObject, body: ByteArray = byteArrayOf()): Boolean {
        if (terminal) return false
        val payload = TunnelProtocol.encodeMux(TunnelProtocol.Mux(meta, body))
        val frame = TunnelProtocol.encodeFrame(TunnelProtocol.Frame(TunnelProtocol.MUX_TYPE, clientId, target, payload))
        val sent = runCatching { sendBinary(frame) }.getOrDefault(false)
        if (!sent) failAll(NetworkFailure())
        return sent
    }
    private fun remoteClose(id: String, target: String): Boolean {
        if (terminal) return false
        return runCatching { send(target, buildJsonObject { put("type", "ws_close"); put("connId", id); put("code", 1000) }) }.getOrDefault(false)
    }
    private fun flushCloses(target: String) {
        val matches = staleCloses.filterValues { it == target }
        for ((id, server) in matches) {
            if (!remoteClose(id, server)) break
            staleCloses.remove(id)
        }
    }
    private fun failTarget(target: String, remoteCleanup: Boolean) {
        requests.filterValues { it.target == target }.keys.toList().forEach { requests.remove(it)?.reply?.completeExceptionally(Disconnected()) }
        streams.values.filter { it.target == target }.toList().forEach { stream ->
            if (remoteCleanup) {
                remoteClose(stream.id, target)
                if (!terminal && staleCloses.size < 128) staleCloses[stream.id] = target
            }
            failStream(stream.id, Disconnected())
        }
    }
    private fun failStream(id: String, cause: Exception) {
        openings.remove(id)?.reply?.completeExceptionally(cause)
        streams.remove(id)?.let { stream ->
            queuedBytes -= stream.bytes; stream.bytes = 0
            stream.messages.close(cause)
            // Drain queued stale output rather than allowing a disconnected epoch to leak into the next one.
            while (stream.messages.tryReceive().isSuccess) { }
        }
    }
    private fun failAll(cause: Exception) {
        if (terminal) return
        terminal = true; ready.completeExceptionally(cause)
        val pending = requests.values.toList(); requests.clear()
        pending.forEach { it.reply.completeExceptionally(cause) }
        streams.values.toList().forEach { stream ->
            if (staleCloses.size < 128) staleCloses[stream.id] = stream.target
            failStream(stream.id, cause)
        }
        mutableServers.value = emptyList()
    }
    /** Socket loss fails operations immediately and never attempts to replay or write cleanup on that socket. */
    fun disconnected() = synchronized(lock) { failAll(Disconnected()) }
    override fun close() = synchronized(lock) {
        if (!terminal) streams.values.toList().forEach { remoteClose(it.id, it.target) }
        failAll(Disconnected())
    }
}
