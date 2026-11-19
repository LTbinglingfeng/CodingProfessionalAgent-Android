package dev.cpa.android.core.network.tunnel

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Wire-compatible with CLIProxyAPI /v1/tunnel v1, CPA mux payload type 2. */
object TunnelProtocol {
    const val MAX_PAYLOAD = 67_108_864
    const val MAX_INBOUND = 67_109_376
    const val MAX_META = 65_536
    const val MAX_CONTROL = 4_096
    const val SERVER_TYPE = "CodingProfessionalAgent"
    const val MUX_TYPE = 2
    fun validId(id: String) = id.length in 1..128 && id.all { it.code in 0x21..0x7e }
    fun apiKey(raw: String): String = raw.trim().also { key -> require(key.isNotEmpty() && key.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid tunnel API key" } }
    fun clientName(raw: String): String = truncateUtf8(raw.filter { it.code > 0x1f && it.code != 0x7f }.trim(), 256).trim().ifEmpty { "CPA-Android" }
    private fun truncateUtf8(text: String, max: Int): String {
        var index = 0; var bytes = 0
        while (index < text.length) {
            val cp = text.codePointAt(index); val size = String(Character.toChars(cp)).toByteArray().size
            if (bytes + size > max) break
            bytes += size; index += Character.charCount(cp)
        }
        return text.substring(0, index)
    }
    data class Endpoint(val baseUrl: String, val webSocketUrl: String) {
        fun handshake(clientId: String, name: String): String {
            require(validId(clientId)) { "Invalid tunnel client ID" }
            val http = webSocketUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrl()
                .newBuilder().addQueryParameter("role", "client").addQueryParameter("uuid", clientId).addQueryParameter("name", clientName(name)).build().toString()
            return if (webSocketUrl.startsWith("wss:")) http.replaceFirst("https:", "wss:") else http.replaceFirst("http:", "ws:")
        }
    }
    fun endpoint(raw: String): Endpoint {
        val text = raw.trim(); val delimiter = text.indexOf("://")
        require(delimiter > 0 && '?' !in text && '#' !in text && text.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid tunnel endpoint" }
        val scheme = text.take(delimiter).lowercase()
        require(scheme in setOf("http", "https", "ws", "wss")) { "Unsupported tunnel scheme" }
        val authority = text.substring(delimiter + 3).substringBefore('/')
        require(authority.isNotBlank() && '@' !in authority) { "Invalid tunnel authority" }
        val port = if (authority.startsWith('[')) {
            val close = authority.indexOf(']'); require(close > 1) { "Invalid tunnel host" }
            authority.substring(close + 1).also { require(it.isEmpty() || it.startsWith(':')) { "Invalid tunnel port" } }
        } else authority.indexOf(':').let { if (it < 0) "" else authority.substring(it) }
        if (port.isNotEmpty()) require(port.drop(1).isNotEmpty() && port.drop(1).all { it in '0'..'9' } && port.drop(1).toIntOrNull() in 1..65535) { "Invalid tunnel port" }
        val secure = scheme == "https" || scheme == "wss"
        val url = runCatching { ((if (secure) "https" else "http") + text.substring(delimiter)).toHttpUrl() }.getOrElse { throw IllegalArgumentException("Invalid tunnel endpoint") }
        val path = url.encodedPath.trimEnd('/')
        val basePath = when { path.endsWith("/v1/tunnel") -> path.removeSuffix("/v1/tunnel"); path.endsWith("/v1") -> path.removeSuffix("/v1"); else -> path }
        val normalized = if (basePath.endsWith("/v1") || basePath.endsWith("/v1/tunnel")) "$basePath/v1/tunnel" else basePath
        val origin = url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        return Endpoint(origin + normalized, origin.replaceFirst(if (secure) "https:" else "http:", if (secure) "wss:" else "ws:") + basePath + "/v1/tunnel")
    }
    data class Frame(val type: Int, val source: String, val destination: String, val payload: ByteArray)
    fun encodeFrame(frame: Frame): ByteArray {
        require(validId(frame.source) && validId(frame.destination) && frame.type in 0..255) { "Invalid tunnel frame identity" }
        require(frame.payload.size <= MAX_PAYLOAD) { "Tunnel payload too large" }
        return byteArrayOf(1, frame.type.toByte(), frame.source.length.toByte(), frame.destination.length.toByte()) + frame.source.toByteArray() + frame.destination.toByteArray() + frame.payload
    }
    fun decodeFrame(raw: ByteArray): Frame {
        require(raw.size in 4..MAX_INBOUND && raw[0] == 1.toByte()) { "Invalid tunnel frame header" }
        val sourceLength = raw[2].toInt() and 255; val destinationLength = raw[3].toInt() and 255
        val offset = 4 + sourceLength + destinationLength
        require(sourceLength in 1..128 && destinationLength in 1..128 && raw.size >= offset && raw.size - offset <= MAX_PAYLOAD) { "Invalid tunnel frame lengths" }
        val source = utf8(raw.copyOfRange(4, 4 + sourceLength)); val destination = utf8(raw.copyOfRange(4 + sourceLength, offset))
        require(validId(source) && validId(destination)) { "Invalid tunnel frame identity" }
        return Frame(raw[1].toInt() and 255, source, destination, raw.copyOfRange(offset, raw.size))
    }
    data class Mux(val meta: JsonObject, val body: ByteArray = byteArrayOf()) {
        override fun toString() = "TunnelMux(metadata=REDACTED, bodyBytes=${body.size})"
    }
    fun encodeMux(mux: Mux): ByteArray {
        validateMeta(mux.meta)
        val metadata = mux.meta.toString().toByteArray()
        require(metadata.size in 1..MAX_META && 4L + metadata.size + mux.body.size <= MAX_PAYLOAD) { "Tunnel mux too large" }
        return ByteBuffer.allocate(4 + metadata.size + mux.body.size).putInt(metadata.size).put(metadata).put(mux.body).array()
    }
    fun decodeMux(raw: ByteArray): Mux {
        require(raw.size in 5..MAX_PAYLOAD) { "Invalid tunnel mux size" }
        val length = ByteBuffer.wrap(raw, 0, 4).int
        require(length in 1..MAX_META && length <= raw.size - 4) { "Invalid tunnel mux length" }
        val meta = runCatching { Json.parseToJsonElement(utf8(raw.copyOfRange(4, 4 + length))) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("Invalid tunnel mux metadata")
        validateMeta(meta)
        return Mux(meta, raw.copyOfRange(4 + length, raw.size))
    }
    fun validateMeta(meta: JsonObject) {
        fun string(key: String) { require(meta.string(key) != null) { "Incomplete tunnel mux metadata" } }
        fun number(key: String, range: IntRange, optional: Boolean = false) {
            val field = meta[key]
            if (optional && (field == null || field == JsonNull)) return
            val raw = (field as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            require(raw != null && raw.isFinite() && raw == raw.toInt().toDouble() && raw.toInt() in range) { "Invalid tunnel mux number" }
        }
        when(meta.string("type")) {
            "http_request" -> { string("id"); string("method"); string("path") }
            "http_response" -> { string("id"); number("status", 100..999) }
            "ws_open" -> string("connId")
            "ws_open_result" -> { string("connId"); require((meta["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null) { "Invalid tunnel open result" }; number("status", 100..999, true) }
            "ws_data" -> { string("connId"); string("opcode") }
            "ws_close" -> { string("connId"); number("code", 0..65535, true) }
            else -> throw IllegalArgumentException("Unsupported tunnel mux type")
        }
    }
    data class Server(val uuid: String, val name: String, val type: String)
    sealed interface Control {
        data class ListServers(val requestId: String?, val servers: List<Server>) : Control
        data class Online(val server: Server) : Control
        data class Offline(val uuid: String) : Control
        data class Error(val code: String, val target: String?) : Control // Never retain untrusted error text or credentials.
    }
    fun decodeControl(raw: ByteArray): Control? {
        if (raw.size > MAX_INBOUND) return null
        val json = runCatching { Json.parseToJsonElement(utf8(raw)) as? JsonObject }.getOrNull() ?: return null
        fun server(raw: JsonElement?): Server? {
            val value = raw as? JsonObject ?: return null
            val id = value.string("uuid")?.takeIf(String::isNotEmpty) ?: return null
            val name = value.string("name") ?: return null
            return if (value.string("type") == SERVER_TYPE) Server(id, name, SERVER_TYPE) else null
        }
        return when(json.string("type")) {
            "server_list" -> (json["servers"] as? JsonArray)?.let { Control.ListServers(json.string("request_id"), it.mapNotNull(::server)) }
            "server_online" -> server(json["server"])?.let(Control::Online)
            "server_offline" -> (json["server"] as? JsonObject)?.string("uuid")?.takeIf(String::isNotEmpty)?.let(Control::Offline)
            "error" -> json.string("code")?.takeIf(String::isNotEmpty)?.let { Control.Error(it, json.string("target_uuid")) }
            else -> null
        }
    }
    fun listServers(requestId: String): String = buildJsonObject {
        put("type", "list_servers"); put("request_id", truncateUtf8(requestId.filter { it.code > 31 && it.code != 127 }, 128))
    }.toString()
    fun header(headers: JsonObject?, name: String): String? {
        val value = headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: return null
        fun text(item: JsonElement) = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotEmpty)
        return text(value) ?: (value as? JsonArray)?.firstNotNullOfOrNull(::text)
    }
    private fun utf8(raw: ByteArray): String = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
        catch (_: Exception) { throw IllegalArgumentException("Invalid tunnel UTF-8") }
}
internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
