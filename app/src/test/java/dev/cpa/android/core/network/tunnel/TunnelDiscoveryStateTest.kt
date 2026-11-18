package dev.cpa.android.core.network.tunnel

import org.junit.Assert.*
import org.junit.Test

class TunnelDiscoveryStateTest {
    @Test fun localNoticesPreserveFailureKindsAttemptsAndClearStaleServers() {
        val server = TunnelProtocol.Server("pc", "原名", TunnelProtocol.SERVER_TYPE)
        fun project(phase: TunnelGatewaySession.Phase) = tunnelDiscoveryState(TunnelGatewaySession.State(phase, listOf(server)))
        val reconnecting = project(TunnelGatewaySession.Phase.Reconnecting(7))
        assertEquals(TunnelDiscoveryNotice.Reconnecting, reconnecting.notice)
        assertEquals(7, reconnecting.reconnectAttempt)
        assertTrue(reconnecting.servers.isEmpty())
        val failures = mapOf(
            TunnelGatewaySession.Failure.UNAUTHORIZED to TunnelDiscoveryNotice.AuthenticationFailed,
            TunnelGatewaySession.Failure.REVOKED to TunnelDiscoveryNotice.AuthenticationFailed,
            TunnelGatewaySession.Failure.INVALID_HANDSHAKE to TunnelDiscoveryNotice.HandshakeFailed,
            TunnelGatewaySession.Failure.IDENTITY_STORAGE to TunnelDiscoveryNotice.IdentityStorageFailed)
        failures.forEach { (failure, notice) ->
            val state = project(TunnelGatewaySession.Phase.Failed(failure))
            assertEquals(TunnelDiscoveryPhase.Failed, state.phase)
            assertEquals(notice, state.notice)
            assertNull(state.error)
            assertTrue(state.servers.isEmpty())
        }
        assertEquals(TunnelDiscoveryNotice.Closed, project(TunnelGatewaySession.Phase.Closed).notice)
        assertNull(project(TunnelGatewaySession.Phase.Connecting).notice)
        val connected = project(TunnelGatewaySession.Phase.Connected)
        assertEquals(listOf(server), connected.servers)
        assertNull(connected.notice)
        assertEquals(0, connected.reconnectAttempt)
    }
}
