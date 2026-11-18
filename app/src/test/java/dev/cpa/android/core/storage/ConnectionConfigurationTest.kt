package dev.cpa.android.core.storage

import org.junit.Assert.*
import org.junit.Test

class ConnectionConfigurationTest {
    private val tunnel = StoredConnection("id", "PC", "old", "password", false, mode = ConnectionMode.Tunnel,
        tunnelBaseUrl = "wss://example.test/proxy/v1/tunnel", tunnelApiKey = " key ", tunnelServerId = "target", tunnelServerName = " PC ")
    @Test fun tunnelNormalizesWithoutLosingDesktopPasswordAndClearsUnusedOrigin() {
        val normalized = tunnel.validated()
        assertEquals("https://example.test/proxy", normalized.tunnelBaseUrl)
        assertEquals("key", normalized.tunnelApiKey); assertEquals("password", normalized.password)
        assertEquals("", normalized.address); assertEquals("PC", normalized.tunnelServerName)
        assertEquals(normalized, normalized.validated())
    }
    @Test fun invalidTargetsAndInsecureUnapprovedGatewaysAreRejected() {
        for (config in listOf(tunnel.copy(tunnelServerId = "bad id"), tunnel.copy(tunnelApiKey = ""),
            tunnel.copy(tunnelBaseUrl = "http://example.test"))) assertTrue(runCatching { config.validated() }.isFailure)
        assertEquals("http://example.test", tunnel.copy(tunnelBaseUrl = "http://example.test", allowCleartext = true).validated().tunnelBaseUrl)
    }
    @Test fun switchingToDirectRemovesUnusedGatewaySecrets() {
        val direct = tunnel.copy(mode = ConnectionMode.Direct, address = "https://desktop.test").validated()
        assertEquals("", direct.tunnelApiKey); assertEquals("", direct.tunnelServerId)
        assertEquals("https://desktop.test/", direct.address)
        assertEquals("password", direct.password)
    }
}
