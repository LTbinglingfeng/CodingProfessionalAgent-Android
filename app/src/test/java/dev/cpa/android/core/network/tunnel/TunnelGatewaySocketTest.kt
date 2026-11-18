package dev.cpa.android.core.network.tunnel

import dev.cpa.android.core.network.HttpFailure
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class TunnelGatewaySocketTest {
    private class Events : TunnelGatewaySocket.Listener {
        val opened = CountDownLatch(1)
        val received = CountDownLatch(2)
        val closed = CountDownLatch(1)
        val texts = CopyOnWriteArrayList<String>()
        val binaries = CopyOnWriteArrayList<ByteArray>()
        val failures = CopyOnWriteArrayList<Throwable>()
        override fun onOpen() { opened.countDown() }
        override fun onText(text: String) { texts.add(text); received.countDown() }
        override fun onBinary(bytes: ByteArray) { binaries.add(bytes); received.countDown() }
        override fun onClosed(cause: Throwable) { failures.add(cause); closed.countDown() }
    }
    private fun socket(server: MockWebServer, events: Events) = TunnelGatewaySocket(
        TunnelProtocol.endpoint(server.url("/proxy").toString()), "secret-key", "client", "Android 空格", events)

    @Test fun bearerHandshakeAndBidirectionalBinaryControlFrames() {
        val server = MockWebServer(); server.start()
        val events = Events(); val socket = socket(server, events)
        val received = CountDownLatch(2)
        val texts = CopyOnWriteArrayList<String>(); val binaries = CopyOnWriteArrayList<ByteArray>()
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("discovery"); webSocket.send(byteArrayOf(0, -1, 4).toByteString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) { texts.add(text); received.countDown() }
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { binaries.add(bytes.toByteArray()); received.countDown() }
            }))
            socket.connect()
            assertTrue(events.opened.await(5, TimeUnit.SECONDS))
            assertTrue(events.received.await(5, TimeUnit.SECONDS))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bearer secret-key", request.getHeader("Authorization"))
            assertEquals("/proxy/v1/tunnel", request.requestUrl!!.encodedPath)
            assertEquals("Android 空格", request.requestUrl!!.queryParameter("name"))
            assertFalse(request.path!!.contains("secret-key"))
            assertEquals(listOf("discovery"), events.texts)
            assertArrayEquals(byteArrayOf(0, -1, 4), events.binaries.single())
            assertFalse(socket.sendText("x".repeat(TunnelProtocol.MAX_CONTROL + 1)))
            assertTrue(socket.sendText("list")); assertTrue(socket.sendBinary(byteArrayOf(7, 8)))
            assertTrue(received.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("list"), texts); assertArrayEquals(byteArrayOf(7, 8), binaries.single())
            socket.close(); socket.close()
            assertFalse(socket.sendText("after close"))
            assertTrue(events.failures.isEmpty())
        } finally { socket.close(); server.shutdown() }
    }

    @Test fun gracefulCloseDrainsQueuedBinaryBeforeClosingHandshake() {
        val server = MockWebServer(); server.start()
        val events = Events(); val socket = socket(server, events)
        val data = CountDownLatch(1); val closed = CountDownLatch(1)
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { data.countDown() }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, ""); closed.countDown() }
            }))
            socket.connect(); assertTrue(events.opened.await(5, TimeUnit.SECONDS))
            assertTrue(socket.sendBinary(ByteArray(65536)))
            socket.closeGracefully()
            assertTrue(data.await(5, TimeUnit.SECONDS)); assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertFalse(socket.sendText("late")); assertTrue(events.failures.isEmpty())
        } finally { socket.close(); server.shutdown() }
    }

    @Test fun handshakeStatusRemainsTypedAndErrorsExcludeSecrets() {
        for (status in listOf(400, 401, 403, 409)) {
            val server = MockWebServer(); server.start()
            val events = Events(); val socket = socket(server, events)
            try {
                server.enqueue(MockResponse().setResponseCode(status).setBody("secret-key"))
                socket.connect()
                assertTrue(events.closed.await(5, TimeUnit.SECONDS))
                assertEquals(status, (events.failures.single() as HttpFailure).statusCode)
                assertFalse(events.failures.single().stackTraceToString().contains("secret-key"))
            } finally { socket.close(); server.shutdown() }
        }
    }

    @Test fun revocationIsTypedWithoutRetainingRemoteReason() {
        val server = MockWebServer(); server.start()
        val events = Events(); val socket = socket(server, events)
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1008, "API KEY REVOKED secret-key") }
            }))
            socket.connect()
            assertTrue(events.closed.await(5, TimeUnit.SECONDS))
            assertTrue(events.failures.single() is TunnelApiKeyRevoked)
            assertFalse(events.failures.single().stackTraceToString().contains("secret-key"))
        } finally { socket.close(); server.shutdown() }
    }

    @Test fun closeBeforeConnectNeverDials() {
        val server = MockWebServer(); server.start()
        val events = Events(); val socket = socket(server, events)
        try {
            socket.close(); socket.connect()
            assertFalse(socket.sendBinary(byteArrayOf(1)))
            assertEquals(0, server.requestCount)
            assertEquals(1L, events.opened.count)
            assertTrue(events.failures.isEmpty())
        } finally { socket.close(); server.shutdown() }
    }
}
