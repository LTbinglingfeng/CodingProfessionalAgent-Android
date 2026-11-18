package dev.cpa.android.core.network

import dev.cpa.android.core.model.DesktopEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import java.io.Closeable
import java.util.UUID

/** Single connection lifetime. Reconnect explicitly with a NEW client/transport; never replays RPCs. */
class DesktopRpcClient(private val transport: DesktopTransport) : Closeable {
    private enum class State { NEW, CONNECTING, READY, CLOSED }
    private val lock = Any()
    private var state = State.NEW
    private var socket: DesktopTransport.Socket? = null
    private var clientId: String? = null
    private val hello = CompletableDeferred<Unit>()
    private val terminal = CompletableDeferred<Throwable>()
    /** Completes normally with the terminal cause; does not consume the event stream. */
    suspend fun awaitTermination(): Throwable = terminal.await()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private val eventQueue = Channel<DesktopEvent>(256)
    /** Single-consumer event stream. Overflow fails the connection rather than losing state silently. */
    val events = eventQueue.receiveAsFlow()
    internal val pendingCount: Int get() = synchronized(lock) { pending.size }

    suspend fun connect(password: String, timeoutMillis: Long = 15_000) {
        require(timeoutMillis > 0)
        synchronized(lock) {
            check(state == State.NEW) { "Create a new client to reconnect" }
            state = State.CONNECTING
        }
        try {
            withTimeout(timeoutMillis) {
                transport.authenticate(password)
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (state == State.CLOSED) throw Disconnected()
                    val opened = transport.openSocket(object : DesktopTransport.Listener {
                        override fun onText(text: String) = receive(text)
                        override fun onClosed(cause: Throwable) = terminate(cause)
                    })
                    if (state == State.CLOSED) { opened.close(); throw Disconnected() }
                    socket = opened
                }
                hello.await()
                synchronized(lock) {
                    if (state == State.CLOSED) throw Disconnected()
                    state = State.READY
                }
            }
        } catch (failure: Throwable) {
            terminate(failure)
            throw failure
        }
    }

    suspend fun call(method: String, args: List<JsonElement> = emptyList(), timeoutMillis: Long = 30_000): JsonElement {
        require(method.isNotBlank())
        require(timeoutMillis > 0)
        val id = "android_${UUID.randomUUID()}"
        val result = CompletableDeferred<JsonElement>()
        try {
            return withTimeout(timeoutMillis) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (state != State.READY) throw Disconnected()
                    val message = buildJsonObject {
                        put("type", "rpc"); put("id", id); put("method", method)
                        put("args", JsonArray(args)); clientId?.let { put("clientId", it) }
                    }
                    pending[id] = result
                    try {
                        if (socket?.send(message.toString()) != true) terminate(Disconnected())
                    } catch (failure: Exception) {
                        terminate(failure)
                    }
                }
                result.await()
            }
        } finally {
            synchronized(lock) { pending.remove(id) }
            result.cancel()
        }
    }

    private fun receive(text: String) {
        val message = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: return // Malformed/unknown envelopes cannot satisfy a pending call.
        synchronized(lock) {
            if (state == State.CLOSED) return
            when (message.string("type")) {
                "hello" -> {
                    if (hello.isCompleted) return
                    clientId = message.string("clientId") ?: return
                    hello.complete(Unit)
                }
                "rpc_result", "rpc_error" -> {
                    val id = message.string("id") ?: return
                    val deferred = pending.remove(id) ?: return
                    if (message.string("type") == "rpc_result") deferred.complete(message["result"] ?: JsonNull)
                    else deferred.completeExceptionally(RemoteRpcFailure(errorMessage(message)))
                }
                "event" -> {
                    val event = message["event"] as? JsonObject ?: return
                    val kind = event.string("kind") ?: return
                    val raw = event["data"] ?: JsonNull
                    val payload = if (raw is JsonPrimitive && raw.isString) {
                        runCatching { Json.parseToJsonElement(raw.content) }.getOrDefault(raw)
                    } else raw
                    if (eventQueue.trySend(DesktopEvent(kind, payload)).isFailure) {
                        terminate(ProtocolFailure("Event consumer is too slow; resync required"))
                    }
                }
            }
        }
    }

    private fun terminate(cause: Throwable) {
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED // Late callbacks from this lifetime can never mutate a newer client.
            // Completing a deferred can resume call() inline, including its finally block.
            val calls = pending.values.toList()
            pending.clear()
            terminal.complete(cause)
            hello.completeExceptionally(cause)
            calls.forEach { it.completeExceptionally(cause) }
            eventQueue.close(cause)
            socket?.close()
            socket = null
        }
        transport.close()
    }

    override fun close() = terminate(Disconnected())

    private fun errorMessage(message: JsonObject): String = message.string("error")
        ?: (message["error"] as? JsonObject)?.string("message")
        ?: (message["errorDto"] as? JsonObject)?.string("message") ?: "RPC failed"

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
