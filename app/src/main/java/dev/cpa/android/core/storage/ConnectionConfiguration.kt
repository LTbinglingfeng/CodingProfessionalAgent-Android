package dev.cpa.android.core.storage

import dev.cpa.android.core.network.DirectTransport
import dev.cpa.android.core.network.tunnel.TunnelProtocol

/** Validate before saving/dialing; never reinterpret an invalid tunnel as a direct connection. */
fun StoredConnection.validated(): StoredConnection = when (mode) {
    ConnectionMode.Direct -> {
        val origin = DirectTransport.validateOrigin(address)
        require(origin.isHttps || allowCleartext) { "HTTP requires explicit authorization for this connection" }
        copy(address = origin.toString(), tunnelBaseUrl = "", tunnelApiKey = "", tunnelServerId = "", tunnelServerName = "")
    }
    ConnectionMode.Tunnel -> {
        val endpoint = TunnelProtocol.endpoint(tunnelBaseUrl)
        require(endpoint.webSocketUrl.startsWith("wss:") || allowCleartext) { "Unencrypted tunnel requires explicit authorization" }
        require(TunnelProtocol.validId(tunnelServerId)) { "Invalid tunnel target" }
        copy(address = "", tunnelBaseUrl = endpoint.baseUrl, tunnelApiKey = TunnelProtocol.apiKey(tunnelApiKey),
            tunnelServerName = tunnelServerName.trim())
    }
}
