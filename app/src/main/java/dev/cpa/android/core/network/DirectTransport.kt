package dev.cpa.android.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HTTP(S) origins only. Standard platform TLS verification is never replaced. */
class DirectTransport(address: String) : DesktopTransport {
    private val origin = validateOrigin(address)
    private val cookies = ConnectionCookies()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookies).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val sockets = mutableSetOf<DirectWebSocket>()
    private val lock = Any()
    private var closed = false

    override suspend fun authenticate(password: String) {
        val status = exchange(Request.Builder().url(endpoint("api/auth/status")).build())
        val body = try { Json.parseToJsonElement(status).jsonObject } catch (_: Exception) {
            throw ProtocolFailure("Invalid auth status")
        }
        val required = (body["required"] as? JsonPrimitive)?.booleanOrNull
            ?: throw ProtocolFailure("Missing auth required flag")
        val authenticated = (body["authenticated"] as? JsonPrimitive)?.booleanOrNull
            ?: throw ProtocolFailure("Missing authenticated flag")
        if (!required) { cookies.clear(); return }
        if (authenticated) return
        val payload = buildJsonObject { put("password", password) }.toString()
        exchange(Request.Builder().url(endpoint("api/auth/login"))
            .post(payload.toRequestBody("application/json".toMediaType())).build())
        if (cookies.loadForRequest(endpoint("api/ws")).isEmpty()) {
            throw ProtocolFailure("Login did not provide a session cookie")
        }
    }

    private fun endpoint(path: String) = origin.newBuilder().encodedPath("/$path").build()

    private suspend fun exchange(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = synchronized(lock) {
            check(!closed) { "Transport is closed" }
            client.newCall(request)
        }
        continuation.invokeOnCancellation { call.cancel() }
        synchronized(lock) {
            if (closed) call.cancel()
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(NetworkFailure()) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw HttpFailure(it.code)
                            it.body?.string() ?: throw ProtocolFailure("Missing HTTP body")
                        }
                    }
                    result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            })
        }
    }

    override fun openSocket(listener: DesktopTransport.Listener): DesktopTransport.Socket = synchronized(lock) {
        check(!closed) { "Transport is closed" }
        val url = endpoint("api/ws")
        val headers = mutableMapOf<String, String>()
        val cookie = cookies.loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        val wsUri = java.net.URI(url.toString().replaceFirst(if (origin.isHttps) "https:" else "http:", if (origin.isHttps) "wss:" else "ws:"))
        lateinit var socket: DirectWebSocket
        socket = DirectWebSocket(wsUri, headers, listener) { synchronized(lock) { sockets.remove(socket) } }
        sockets.add(socket)
        socket.connect()
        socket
    }

    /** Terminal close; construct a new transport for an explicit reconnect. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            sockets.toList().forEach { it.close() }
            sockets.clear()
            client.dispatcher.cancelAll()
            cookies.dispose()
            // This transport owns its client/dispatcher; no shared executor is shut down.
            client.dispatcher.executorService.shutdown()
        }
        client.connectionPool.evictAll()
    }

    companion object {
        fun validateOrigin(address: String): HttpUrl {
            require(address == address.trim() && '\\' !in address && address.none { it.isISOControl() }) { "Invalid desktop URL" }
            require(address.startsWith("http://") || address.startsWith("https://")) { "HTTP(S) origin required" }
            val raw = try { java.net.URI(address) } catch (_: Exception) { throw IllegalArgumentException("Invalid desktop URL") }
            require(raw.rawPath.isNullOrEmpty() || raw.rawPath == "/") { "Desktop URL must not contain a path" }
            val url = address.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid desktop URL")
            require(url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" &&
                url.query == null && url.fragment == null && '@' !in address) { "Use an origin without credentials, path, query or fragment" }
            return url
        }
    }
}

private class ConnectionCookies : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    private var disposed = false
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (disposed) return
        cookies.forEach { incoming ->
            this.cookies.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
            this.cookies.add(incoming)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
    @Synchronized fun clear() = cookies.clear()
    @Synchronized fun dispose() { disposed = true; cookies.clear() }
}
