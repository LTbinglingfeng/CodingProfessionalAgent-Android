package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.network.*
import dev.cpa.android.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual Go Hub and TypeScript desktop services; explicitly synthetic business RPC. */
class RealTunnelContractTest {
    @Test fun realGatewayDesktopDiscoveryAuthRpcEventsAndRevocation() = runBlocking {
        val url = System.getenv("CPA_TUNNEL_URL")
        val key = System.getenv("CPA_TUNNEL_KEY")
        val password = System.getenv("CPA_TUNNEL_PASSWORD")
        val target = System.getenv("CPA_TUNNEL_TARGET")
        assumeTrue("Run scripts/test-tunnel-contract.mjs", System.getenv("CPA_TUNNEL_FIXTURE") == "source-services" &&
            url != null && key != null && password != null && target != null)
        require(url!!.startsWith("http://127.0.0.1:"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = TunnelClientManager(scope, MemoryTunnelIdentityStore())
        val provider = ManagedTunnelConnectionProvider(manager, scope)
        var handle: TunnelConnectionHandle? = null
        var rpc: DesktopRpcClient? = null
        try {
            val servers = provider.discover(url, key!!, true)
            assertTrue(servers.any { it.uuid == target && it.type == TunnelProtocol.SERVER_TYPE })
            val rejected = runCatching { provider.discover(url, "wrong-fixture-key", true) }.exceptionOrNull()
            assertEquals(401, (rejected as HttpFailure).statusCode)
            val config = StoredConnection("fixture", "PC", "", password!!, true, mode = ConnectionMode.Tunnel,
                tunnelBaseUrl = url, tunnelApiKey = key, tunnelServerId = target!!)
            val retained = provider.acquire(config); handle = retained
            withTimeout(15_000) { retained.awaitReady() }
            val denied = DesktopRpcClient(retained.transport())
            try {
                val failure = runCatching { denied.connect("wrong-desktop-password") }.exceptionOrNull()
                assertEquals(401, (failure as HttpFailure).statusCode)
            } finally { denied.close() }
            val client = DesktopRpcClient(retained.transport()); rpc = client
            client.connect(password)
            val payload = "跨端✓".repeat(32_768)
            val result = client.call("fixture.echo", listOf(JsonPrimitive(payload))).jsonObject
            assertEquals(JsonPrimitive(true), result["fixture"])
            assertEquals(payload, result["args"]!!.jsonArray.single().jsonPrimitive.content)
            assertTrue(result["context"]!!.jsonObject["clientId"]!!.jsonPrimitive.content.isNotBlank())
            val events = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { client.events.first { it.kind == "fixture.contract" } } }
            client.call("fixture.event")
            assertEquals("隧道 ✓", events.await().payload.jsonObject["text"]!!.jsonPrimitive.content)
            client.call("fixture.bounce")
            withTimeout(10_000) { client.awaitTermination() }
            client.close()
            withTimeout(15_000) { retained.awaitReady() }
            val recovered = DesktopRpcClient(retained.transport()); rpc = recovered
            recovered.connect(password)
            val afterReconnect = recovered.call("fixture.echo", listOf(JsonPrimitive("after-offline"))).jsonObject
            assertEquals("after-offline", afterReconnect["args"]!!.jsonArray.single().jsonPrimitive.content)
            recovered.call("fixture.revoke")
            withTimeout(10_000) { recovered.awaitTermination() }
            val revoked = runCatching { withTimeout(5000) { retained.awaitReady() } }.exceptionOrNull()
            assertTrue("Revocation must require explicit credentials repair", revoked is TunnelApiKeyRevoked)
        } finally {
            rpc?.close(); handle?.close(); manager.close(); scope.cancel(); scope.coroutineContext[Job]!!.join()
        }
    }
}
