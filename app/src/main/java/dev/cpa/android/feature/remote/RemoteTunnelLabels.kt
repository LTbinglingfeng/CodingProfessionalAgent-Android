package dev.cpa.android.feature.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cpa.android.R
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryNotice
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryState

@Composable
internal fun tunnelDiscoveryNotice(state: TunnelDiscoveryState): String? = when (state.notice) {
    null -> null
    TunnelDiscoveryNotice.Reconnecting -> stringResource(R.string.tunnel_reconnecting, state.reconnectAttempt)
    TunnelDiscoveryNotice.AuthenticationFailed -> stringResource(R.string.tunnel_auth_failed)
    TunnelDiscoveryNotice.HandshakeFailed -> stringResource(R.string.tunnel_handshake_failed)
    TunnelDiscoveryNotice.IdentityStorageFailed -> stringResource(R.string.tunnel_identity_failed)
    TunnelDiscoveryNotice.Closed -> stringResource(R.string.tunnel_discovery_closed)
    TunnelDiscoveryNotice.RecentGatewaySaveFailed -> stringResource(R.string.tunnel_recent_save_failed)
}
