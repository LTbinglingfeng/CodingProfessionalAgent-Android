package dev.cpa.android.core.network.tunnel

import kotlinx.coroutines.CoroutineScope
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** A lease owns a reference, never another desktop's authentication cookie. */
internal class TunnelClientManager(
    private val scope: CoroutineScope,
    private val identities: TunnelIdentityStore,
    private val create: ((TunnelProtocol.Endpoint, String) -> TunnelGatewaySession)? = null,
) : Closeable {
    private class Key(val url: String, val key: String) {
        override fun equals(other: Any?) = other is Key && url == other.url && key == other.key
        override fun hashCode() = 31 * url.hashCode() + key.hashCode()
        override fun toString() = "TunnelSessionKey(REDACTED)"
    }
    private class Entry(val session: TunnelGatewaySession, var references: Int = 0)
    private val entries = mutableMapOf<Key, Entry>()
    private var closed = false
    inner class Lease internal constructor(val session: TunnelGatewaySession, private val release: () -> Unit) : Closeable {
        private val released = AtomicBoolean()
        override fun close() { if (released.compareAndSet(false, true)) release() }
    }
    @Synchronized fun acquire(baseUrl: String, apiKey: String): Lease {
        check(!closed) { "Tunnel manager closed" }
        val endpoint = TunnelProtocol.endpoint(baseUrl)
        val validatedKey = TunnelProtocol.apiKey(apiKey)
        val key = Key(endpoint.webSocketUrl, validatedKey)
        val existing = entries[key]
        val entry = existing ?: Entry(create?.invoke(endpoint, validatedKey)
            ?: TunnelGatewaySession(endpoint, validatedKey, scope, identities)).also { entries[key] = it }
        entry.references++
        if (existing == null) entry.session.start()
        else if (entry.session.state.value.phase is TunnelGatewaySession.Phase.Failed) entry.session.retryNow()
        else entry.session.refresh()
        return Lease(entry.session) { release(key, entry) }
    }
    @Synchronized private fun release(key: Key, entry: Entry) {
        if (entries[key] !== entry) return
        entry.references--
        if (entry.references == 0) { entries.remove(key); entry.session.close() }
    }
    @Synchronized fun wakeAll() { entries.values.toList().forEach { it.session.wake() } }
    @Synchronized override fun close() {
        closed = true
        val old = entries.values.toList(); entries.clear()
        old.forEach { it.session.close() }
    }
}
