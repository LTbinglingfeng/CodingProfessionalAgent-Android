package dev.cpa.android.core.network

import java.io.Closeable
import java.io.IOException

/** One transport per saved connection; never share authentication across desktops. */
interface DesktopTransport : Closeable {
    suspend fun authenticate(password: String)
    fun openSocket(listener: Listener): Socket
    interface Socket : Closeable {
        fun send(text: String): Boolean
    }
    interface Listener {
        fun onText(text: String)
        fun onClosed(cause: Throwable)
    }
}

class HttpFailure(val statusCode: Int) : IOException("Desktop HTTP $statusCode")
/** Never retains the server-supplied reason (which may contain credentials). */
class WebSocketCloseFailure(val code: Int) : IOException("Desktop WebSocket closed ($code)") {
    val authenticationExpired: Boolean get() = code == 4401
}
/** Deliberately excludes original throwable/cause: network errors can contain private URLs. */
class NetworkFailure : IOException("Desktop network failure")
class Disconnected : IOException("Desktop disconnected")
class ProtocolFailure(message: String) : IOException(message)
class RemoteRpcFailure(message: String) : IOException(message)
