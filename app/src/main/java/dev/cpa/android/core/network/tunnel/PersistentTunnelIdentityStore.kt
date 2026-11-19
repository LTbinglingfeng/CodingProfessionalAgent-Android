package dev.cpa.android.core.network.tunnel

import java.util.UUID

/** Write first: a persistence failure must never publish a new, unrecorded identity. */
internal class PersistentTunnelIdentityStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val fresh: () -> String = { UUID.randomUUID().toString() },
) : TunnelIdentityStore {
    private var cached: String? = null
    @Synchronized override fun current(): String {
        cached?.let { return it }
        val saved = read()?.takeIf(TunnelProtocol::validId)
        if (saved != null) { cached = saved; return saved }
        return rotate()
    }
    @Synchronized override fun rotate(): String {
        val next = fresh()
        require(TunnelProtocol.validId(next)) { "Invalid tunnel identity" }
        write(next)
        cached = next
        return next
    }
}
