package dev.cpa.android.core.storage

import android.content.Context
import dev.cpa.android.core.network.tunnel.PersistentTunnelIdentityStore
import dev.cpa.android.core.network.tunnel.TunnelIdentityStore

/** Non-secret installation identity. The caller uses an IO-owned gateway scope for disk access. */
internal class AndroidTunnelIdentityStore(context: Context, private val preferenceName: String = "tunnel-identity") : TunnelIdentityStore {
    private val preferences by lazy { context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE) }
    private val delegate = PersistentTunnelIdentityStore(
        read = { preferences.getString("clientId", null) },
        write = { check(preferences.edit().putString("clientId", it).commit()) { "Tunnel identity write failed" } },
    )
    override fun current() = delegate.current()
    override fun rotate() = delegate.rotate()
}
