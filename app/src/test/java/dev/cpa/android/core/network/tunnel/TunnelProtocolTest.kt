package dev.cpa.android.core.network.tunnel

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class TunnelProtocolTest {
    private fun invalid(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun endpointsNormalizeSecureSubpathIpv6AndRemainIdempotent() {
        val endpoint = TunnelProtocol.endpoint("HTTPS://Proxy.Example.COM/v1/")
        assertEquals("https://proxy.example.com", endpoint.baseUrl)
        assertEquals("wss://proxy.example.com/v1/tunnel", endpoint.webSocketUrl)
        assertEquals(endpoint, TunnelProtocol.endpoint("wss://proxy.example.com/"))
        assertEquals("ws://[::1]:8317/v1/tunnel", TunnelProtocol.endpoint("http://[::1]:8317").webSocketUrl)
        for (path in listOf("", "/v1/", "/gateway/v1", "/gateway/v1/v1/tunnel", "/a/v1/tunnel/v1/tunnel")) {
            val first = TunnelProtocol.endpoint("https://proxy.example.com$path")
            assertEquals(first, TunnelProtocol.endpoint(first.baseUrl)); assertEquals(first, TunnelProtocol.endpoint(first.webSocketUrl))
        }
    }
    @Test fun endpointRejectsCredentialsQueriesInvalidSchemesAndPorts() {
        listOf("", "proxy.example:8317", "ftp://proxy.example", "https://u:p@proxy.example", "https://proxy.example?key=x", "https://proxy.example#x", "https://proxy.example:0", "https://proxy.example:70000", "https://proxy.example:/v1", "http://::1", "http://[::1]:999999999999999").forEach { invalid { TunnelProtocol.endpoint(it) } }
    }
    @Test fun handshakeEncodesClientIdentityWithoutKeyInUrlAndNameIsUtf8Bounded() {
        val url = TunnelProtocol.endpoint("https://proxy.example").handshake("client+&=#", "Name +&=?# 中文").replaceFirst("wss:", "https:").toHttpUrl()
        assertEquals("client+&=#", url.queryParameter("uuid")); assertEquals("Name +&=?# 中文", url.queryParameter("name"))
        assertEquals(setOf("role", "uuid", "name"), url.queryParameterNames)
        val name = TunnelProtocol.clientName("测😀".repeat(80)); assertTrue(name.toByteArray().size <= 256); assertFalse(name.contains('\uFFFD'))
        assertEquals("CPA-Android", TunnelProtocol.clientName("\n\t\u007f"))
    }
    @Test fun apiKeysAndIdsRejectControlsWithoutEchoingSecrets() {
        assertEquals("secret", TunnelProtocol.apiKey(" secret "))
        val error = runCatching { TunnelProtocol.apiKey("secret\nkey") }.exceptionOrNull()!!
        assertFalse(error.toString().contains("secret"))
        assertFalse(TunnelProtocol.validId("bad id")); assertFalse(TunnelProtocol.validId("中")); assertFalse(TunnelProtocol.validId("a".repeat(129)))
    }
    @Test fun outerFrameMatchesWireBytesAndRejectsMalformedHeaders() {
        val encoded = TunnelProtocol.encodeFrame(TunnelProtocol.Frame(2, "a", "b", byteArrayOf(0xde.toByte(), 0xad.toByte())))
        assertArrayEquals(byteArrayOf(1, 2, 1, 1, 97, 98, 0xde.toByte(), 0xad.toByte()), encoded)
        val decoded = TunnelProtocol.decodeFrame(encoded)
        assertEquals("a", decoded.source); assertEquals("b", decoded.destination); assertEquals(2, decoded.type)
        assertArrayEquals(byteArrayOf(0xde.toByte(), 0xad.toByte()), decoded.payload)
        invalid { TunnelProtocol.decodeFrame(byteArrayOf(1, 2, 1)) }
        invalid { TunnelProtocol.decodeFrame(encoded.copyOf().also { it[0] = 2 }) }
        invalid { TunnelProtocol.decodeFrame(encoded.copyOf().also { it[4] = 32 }) }
        invalid { TunnelProtocol.decodeFrame(encoded.copyOf().also { it[2] = 127 }) }
    }
    @Test fun muxBigEndianPrefixBodyAndMultiValueCookiesRoundTrip() {
        val meta = Json.parseToJsonElement("""{"type":"http_response","id":"r","status":200,"headers":{"set-cookie":["","cpa_sid=abc; Path=/","other=1"]}}""").jsonObject
        val encoded = TunnelProtocol.encodeMux(TunnelProtocol.Mux(meta, "body".toByteArray()))
        assertEquals(meta.toString().toByteArray().size, ByteBuffer.wrap(encoded).int)
        val decoded = TunnelProtocol.decodeMux(encoded)
        assertEquals(meta, decoded.meta); assertEquals("body", decoded.body.toString(Charsets.UTF_8))
        assertFalse(decoded.toString().contains("cpa_sid"))
        assertEquals("cpa_sid=abc; Path=/", TunnelProtocol.header(decoded.meta["headers"]!!.jsonObject, "SET-COOKIE"))
    }
    @Test fun malformedMuxNumbersLengthsAndUtf8AreRejected() {
        for (status in listOf("99", "1000", "200.5", "1e100", "\"200\"")) {
            val meta = Json.parseToJsonElement("""{"type":"http_response","id":"r","status":$status}""").jsonObject
            invalid { TunnelProtocol.encodeMux(TunnelProtocol.Mux(meta)) }
        }
        invalid { TunnelProtocol.decodeMux(byteArrayOf(-1, -1, -1, -1, 0)) }
        invalid { TunnelProtocol.decodeMux(byteArrayOf(0, 0, 0, 1, -1)) }
        invalid { TunnelProtocol.encodeMux(TunnelProtocol.Mux(buildJsonObject { put("type", "ws_open_result"); put("connId", "c"); put("ok", "true") })) }
    }
    @Test fun allMuxKindsAndOptionalCloseFieldsRespectLimits() {
        val frames = listOf(
            """{"type":"http_request","id":"r","method":"POST","path":"/api/auth"}""",
            """{"type":"http_response","id":"r","status":200}""",
            """{"type":"ws_open","connId":"c"}""",
            """{"type":"ws_open_result","connId":"c","ok":false,"status":401}""",
            """{"type":"ws_data","connId":"c","opcode":"text"}""",
            """{"type":"ws_close","connId":"c","code":null,"reason":""}"""
        )
        frames.forEach { raw ->
            val meta = Json.parseToJsonElement(raw).jsonObject
            assertEquals(meta, TunnelProtocol.decodeMux(TunnelProtocol.encodeMux(TunnelProtocol.Mux(meta))).meta)
        }
        for (code in listOf("-1", "65536", "1000.5")) {
            invalid { TunnelProtocol.encodeMux(TunnelProtocol.Mux(Json.parseToJsonElement("""{"type":"ws_close","connId":"c","code":$code}""").jsonObject)) }
        }
        invalid { TunnelProtocol.encodeMux(TunnelProtocol.Mux(buildJsonObject { put("type", "ws_open"); put("connId", "c"); put("path", "a".repeat(65536)) })) }
    }
    @Test fun missingOrMalformedServersIsNotAnEmptySnapshot() {
        assertNull(TunnelProtocol.decodeControl("""{"type":"server_list"}""".toByteArray()))
        for (value in listOf("null", "{}", "\"[]\"")) {
            assertNull(TunnelProtocol.decodeControl("""{"type":"server_list","servers":$value}""".toByteArray()))
        }
        assertEquals(TunnelProtocol.Control.ListServers(null, emptyList()),
            TunnelProtocol.decodeControl("""{"type":"server_list","servers":[]}""".toByteArray()))
    }
    @Test fun discoveryFiltersServerTypeButOfflineNeedsOnlyId() {
        val list = """{"type":"server_list","request_id":"r","servers":[{"uuid":"a","name":"Desktop","type":"CodingProfessionalAgent"},{"uuid":"b","name":"Other","type":"other"}]}"""
        val control = TunnelProtocol.decodeControl(list.toByteArray()) as TunnelProtocol.Control.ListServers
        assertEquals(listOf("a"), control.servers.map { it.uuid })
        assertEquals(TunnelProtocol.Control.Offline("a"), TunnelProtocol.decodeControl("""{"type":"server_offline","server":{"uuid":"a"}}""".toByteArray()))
        val error = TunnelProtocol.decodeControl("""{"type":"error","code":"unauthorized","message":"secret-password"}""".toByteArray())!!
        assertFalse(error.toString().contains("secret-password"))
    }
    @Test fun discoveryAboveFourKiBAndBoundedOutboundRequestAreSupported() {
        val servers = JsonArray((0 until 100).map { buildJsonObject { put("uuid", "s$it"); put("name", "Desktop $it"); put("type", TunnelProtocol.SERVER_TYPE) } })
        val bytes = buildJsonObject { put("type", "server_list"); put("servers", servers) }.toString().toByteArray()
        assertTrue(bytes.size > 4096)
        assertEquals(100, (TunnelProtocol.decodeControl(bytes) as TunnelProtocol.Control.ListServers).servers.size)
        val request = Json.parseToJsonElement(TunnelProtocol.listServers("😀\n".repeat(100))).jsonObject
        assertTrue(request["request_id"]!!.jsonPrimitive.content.toByteArray().size <= 128)
    }
}
