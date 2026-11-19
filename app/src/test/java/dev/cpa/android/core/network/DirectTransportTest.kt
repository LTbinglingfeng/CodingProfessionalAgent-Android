package dev.cpa.android.core.network

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList

class DirectTransportTest {
    private fun ownedClient(transport: DirectTransport): OkHttpClient {
        val field = DirectTransport::class.java.getDeclaredField("client")
        field.isAccessible = true
        return field.get(transport) as OkHttpClient
    }

    private fun assertRemoteClose(code: Int) {
        val server = MockWebServer(); server.start()
        val transport = DirectTransport(server.url("/").toString())
        val causes = CopyOnWriteArrayList<Throwable>()
        val terminal = CountDownLatch(1)
        val handshake = CountDownLatch(1)
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(code, "secret-session-token")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { handshake.countDown() }
            }))
            transport.openSocket(object : DesktopTransport.Listener {
                override fun onText(text: String) = Unit
                override fun onClosed(cause: Throwable) { causes.add(cause); terminal.countDown() }
            })
            assertTrue(terminal.await(5, TimeUnit.SECONDS))
            assertTrue(handshake.await(5, TimeUnit.SECONDS))
            transport.close()
            assertTrue(ownedClient(transport).dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, causes.size)
            val failure = causes.single() as WebSocketCloseFailure
            assertEquals(code, failure.code)
            assertEquals(code == 4401, failure.authenticationExpired)
            assertFalse(failure.stackTraceToString().contains("secret-session-token"))
            assertNull(failure.cause)
        } finally { transport.close(); server.shutdown() }
    }

    @Test fun authenticationCloseCodeIsRetainedAndTerminalDeliveredOnce() = assertRemoteClose(4401)
    @Test fun normalCloseIsDeliveredOnce() = assertRemoteClose(1000)

    @Test fun authenticationCloseReachesRpcConnectCaller() = runBlocking {
        val server = MockWebServer(); server.start()
        val client = DesktopRpcClient(DirectTransport(server.url("/").toString()))
        try {
            server.enqueue(MockResponse().setBody("""{"required":false,"authenticated":true}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(4401, "private") }
            }))
            val failure = runCatching { client.connect("") }.exceptionOrNull()
            assertTrue(failure is WebSocketCloseFailure)
            assertTrue((failure as WebSocketCloseFailure).authenticationExpired)
            assertEquals(0, client.pendingCount)
        } finally { client.close(); server.shutdown() }
    }

    @Test fun terminalCloseShutsDownOwnedExecutorAndIsIdempotent() = runBlocking {
        val server = MockWebServer(); server.start()
        val transport = DirectTransport(server.url("/").toString())
        try {
            server.enqueue(MockResponse().setBody("""{"required":false,"authenticated":false}"""))
            transport.authenticate("")
            val executor = ownedClient(transport).dispatcher.executorService
            transport.close(); transport.close()
            assertTrue(executor.isShutdown)
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(runCatching { transport.authenticate("") }.exceptionOrNull() is IllegalStateException)
        } finally { transport.close(); server.shutdown() }
    }

    @Test fun socketNetworkFailureIsSanitizedAndDeliveredOnce() {
        val server = MockWebServer(); server.start()
        val transport = DirectTransport(server.url("/").toString())
        val causes = CopyOnWriteArrayList<Throwable>()
        val terminal = CountDownLatch(1)
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val socket = transport.openSocket(object : DesktopTransport.Listener {
                override fun onText(text: String) = Unit
                override fun onClosed(cause: Throwable) { causes.add(cause); terminal.countDown() }
            })
            assertTrue(terminal.await(5, TimeUnit.SECONDS))
            socket.close(); transport.close()
            assertTrue(ownedClient(transport).dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, causes.size)
            assertTrue(causes.single() is NetworkFailure)
            assertNull(causes.single().cause)
            assertEquals("Desktop network failure", causes.single().message)
        } finally { transport.close(); server.shutdown() }
    }

    @Test fun full25MiBAttachmentBase64CrossesActualWebSocket() {
        val server = MockWebServer(); server.start()
        val transport = DirectTransport(server.url("/").toString())
        val ready = CountDownLatch(1); val received = CountDownLatch(1)
        val expected = ((25 * 1024 * 1024 + 2) / 3) * 4
        var actual = 0
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("ready") }
                override fun onMessage(webSocket: WebSocket, text: String) { actual = text.length; received.countDown() }
            }))
            val socket = transport.openSocket(object : DesktopTransport.Listener {
                override fun onText(text: String) { ready.countDown() }
                override fun onClosed(cause: Throwable) = Unit
            })
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val payload = java.util.Base64.getEncoder().encodeToString(ByteArray(25 * 1024 * 1024))
            assertTrue(socket.send(payload))
            assertTrue(received.await(15, TimeUnit.SECONDS))
            assertEquals(expected, actual)
        } finally { transport.close(); server.shutdown() }
    }
    @Test fun websocketTlsRejectsUntrustedCertificate() {
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val certificates = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer(); server.useHttps(certificates.sslSocketFactory(), false); server.start()
        val transport = DirectTransport(server.url("/").toString())
        val terminal = CountDownLatch(1)
        var failure: Throwable? = null
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            transport.openSocket(object : DesktopTransport.Listener {
                override fun onText(text: String) { fail("Untrusted TLS must not deliver data") }
                override fun onClosed(cause: Throwable) { failure = cause; terminal.countDown() }
            })
            assertTrue(terminal.await(5, TimeUnit.SECONDS))
            assertTrue(failure is NetworkFailure)
            assertNull(failure?.cause)
        } finally { transport.close(); server.shutdown() }
    }
    @Test fun validatesOrigins() {
        listOf("ftp://host", "ws://host", "https://user:pass@host", "https://host/path", "https://host/foo/..", "https://host?x=1", "https://host/#x", " https://host", "https://host\\evil", "http://host:99999").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { DirectTransport.validateOrigin(it) }
        }
        assertEquals("::1", DirectTransport.validateOrigin("http://[::1]:8080").host)
        assertEquals("https", DirectTransport.validateOrigin("https://example.com").scheme)
    }

    @Test fun loginCookieAndRealSocketRpcWire() = runBlocking {
        val server = MockWebServer(); server.start()
        val transport = DirectTransport(server.url("/").toString()); val client = DesktopRpcClient(transport)
        try {
            server.enqueue(MockResponse().setBody("""{"required":true,"authenticated":false}"""))
            server.enqueue(MockResponse().addHeader("Set-Cookie", "cpa_web_session=secret; HttpOnly; Path=/").setBody("""{"authenticated":true}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"hello","clientId":"real-cid"}""") }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = Json.parseToJsonElement(text).jsonObject
                    webSocket.send("""{"type":"rpc_result","id":${request["id"]},"result":{"entries":[]}}""")
                }
            }))
            client.connect("p\"ass")
            val result = client.call("session:get", listOf(JsonPrimitive("session-1")))
            assertEquals(JsonArray(emptyList()), result.jsonObject["entries"])
            assertEquals("/api/auth/status", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            val login = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", login.method); assertEquals("/api/auth/login", login.path)
            assertEquals(JsonPrimitive("p\"ass"), Json.parseToJsonElement(login.body.readUtf8()).jsonObject["password"])
            val upgrade = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/ws", upgrade.path); assertEquals("cpa_web_session=secret", upgrade.getHeader("Cookie"))
        } finally { client.close(); server.shutdown() }
    }

    @Test fun cookiesAreIndependentEvenForSameOrigin() = runBlocking {
        val server = MockWebServer(); server.start()
        val first = DirectTransport(server.url("/").toString()); val second = DirectTransport(server.url("/").toString())
        try {
            server.enqueue(MockResponse().setBody("""{"required":true,"authenticated":false}"""))
            server.enqueue(MockResponse().addHeader("Set-Cookie", "cpa_web_session=one; Path=/").setBody("{}"))
            first.authenticate("one")
            server.enqueue(MockResponse().setBody("""{"required":true,"authenticated":true}"""))
            first.authenticate("one")
            server.enqueue(MockResponse().setBody("""{"required":false,"authenticated":false}"""))
            second.authenticate("")
            server.takeRequest(); server.takeRequest()
            assertEquals("cpa_web_session=one", server.takeRequest().getHeader("Cookie"))
            assertNull(server.takeRequest().getHeader("Cookie"))
        } finally { first.close(); second.close(); server.shutdown() }
    }

    @Test fun authErrorsRedirectsAndMalformedStatusFailClosed() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            for (response in listOf(MockResponse().setResponseCode(401), MockResponse().setResponseCode(302).addHeader("Location", "https://example.com"), MockResponse().setBody("{}"))) {
                val transport = DirectTransport(server.url("/").toString())
                try {
                    server.enqueue(response)
                    assertTrue(runCatching { transport.authenticate("never-log") }.isFailure)
                    server.takeRequest()
                } finally { transport.close() }
            }
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun loginFailureAndMissingCookieDoNotOpenSocket() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            for (loginResponse in listOf(MockResponse().setResponseCode(401), MockResponse().setBody("{}"))) {
                val client = DesktopRpcClient(DirectTransport(server.url("/").toString()))
                try {
                    server.enqueue(MockResponse().setBody("""{"required":true,"authenticated":false}"""))
                    server.enqueue(loginResponse)
                    assertTrue(runCatching { client.connect("wrong") }.isFailure)
                    assertEquals("/api/auth/status", server.takeRequest().path)
                    assertEquals("/api/auth/login", server.takeRequest().path)
                } finally { client.close() }
            }
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun upgradeRejectionIsNotRetried() = runBlocking {
        val server = MockWebServer(); server.start()
        val client = DesktopRpcClient(DirectTransport(server.url("/").toString()))
        try {
            server.enqueue(MockResponse().setBody("""{"required":false,"authenticated":true}"""))
            server.enqueue(MockResponse().setResponseCode(401))
            val failure = runCatching { client.connect("") }.exceptionOrNull()
            assertTrue(failure is HttpFailure)
            assertEquals(401, (failure as HttpFailure).statusCode)
            assertEquals(2, server.requestCount)
            assertEquals(0, client.pendingCount)
        } finally { client.close(); server.shutdown() }
    }

    @Test fun authenticationCanBeCancelled() = runBlocking {
        val server = MockWebServer(); server.start()
        val transport = DirectTransport(server.url("/").toString())
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { transport.authenticate("secret") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { transport.close(); server.shutdown() }
    }
}
