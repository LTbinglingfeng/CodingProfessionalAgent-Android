package dev.cpa.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.cpa.android.core.platform.hasLocalNetworkPermission
import dev.cpa.android.core.storage.AndroidConnectionVault
import dev.cpa.android.core.storage.AndroidComposerPreferences
import dev.cpa.android.core.storage.AndroidTunnelIdentityStore
import dev.cpa.android.core.storage.AndroidRecentTunnelStore
import dev.cpa.android.core.network.tunnel.TunnelClientManager
import dev.cpa.android.core.network.tunnel.ManagedTunnelConnectionProvider
import dev.cpa.android.data.repository.DefaultRemoteDesktopRepository
import dev.cpa.android.data.repository.RemoteDesktopRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CpaApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnelManager by lazy { TunnelClientManager(gatewayScope, AndroidTunnelIdentityStore(this)) }
    val remote: RemoteDesktopRepository by lazy {
        DefaultRemoteDesktopRepository(AndroidConnectionVault(this), applicationScope, AndroidComposerPreferences(this),
            recentTunnel = AndroidRecentTunnelStore(this),
            tunnelConnections = ManagedTunnelConnectionProvider(tunnelManager, gatewayScope))
    }

    override fun onCreate() {
        super.onCreate()
        // Process lifecycle delays STOP across configuration changes; individual pages never own sockets.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (hasLocalNetworkPermission()) remote.onForeground() else remote.onBackground()
            }
            override fun onStop(owner: LifecycleOwner) = remote.onBackground()
        })
    }
}
