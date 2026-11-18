package dev.cpa.android.core.storage

import dev.cpa.android.core.network.tunnel.TunnelProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*

/** Secret-bearing value stays inside storage/repository code. */
data class RecentTunnel(val baseUrl: String, val apiKey: String) {
    override fun toString() = "RecentTunnel(REDACTED)"
}
data class RecentTunnelSummary(val baseUrl: String, val hasApiKey: Boolean)
interface RecentTunnelStore {
    suspend fun load(): RecentTunnel?
    suspend fun save(value: RecentTunnel)
}
class MemoryRecentTunnelStore : RecentTunnelStore {
    private val mutex = Mutex()
    private var value: RecentTunnel? = null
    override suspend fun load() = mutex.withLock { value }
    override suspend fun save(value: RecentTunnel) { mutex.withLock { this.value = RecentTunnel(TunnelProtocol.endpoint(value.baseUrl).baseUrl, TunnelProtocol.apiKey(value.apiKey)) } }
}
class RecentTunnelStoreException : IOException("Recent tunnel storage unavailable")

/** Separate file and key from connection records. Failed reads are never treated as empty. */
internal class EncryptedRecentTunnelStore(
    private val keys: VaultKeys,
    private val file: VaultFile,
    private val mutex: Mutex,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RecentTunnelStore {
    override suspend fun load(): RecentTunnel? = access { read() }
    override suspend fun save(value: RecentTunnel) = access {
        val normalized = RecentTunnel(TunnelProtocol.endpoint(value.baseUrl).baseUrl, TunnelProtocol.apiKey(value.apiKey))
        read() // Validate any existing snapshot/key before replacing it.
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(1)
            for (text in listOf(normalized.baseUrl, normalized.apiKey)) {
                val bytes = text.toByteArray()
                try {
                    require(output.size().toLong() + bytes.size + 4 <= ConnectionVaultCodec.MAX_BYTES)
                    stream.writeInt(bytes.size); stream.write(bytes)
                } finally { bytes.fill(0) }
            }
        }
        val plain = output.toByteArray()
        try { file.write(ConnectionVaultCodec.encrypt(plain, keys.existing() ?: keys.create())) }
        finally { plain.fill(0) }
    }
    private fun read(): RecentTunnel? {
        val bytes = file.read() ?: return null
        val plain = ConnectionVaultCodec.decrypt(bytes, requireNotNull(keys.existing()))
        try {
            val input = DataInputStream(ByteArrayInputStream(plain))
            require(input.readInt() == 1)
            fun string(): String {
                val length = input.readInt(); require(length in 0..input.available())
                val value = ByteArray(length); input.readFully(value)
                return try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value)).toString() }
                finally { value.fill(0) }
            }
            val value = RecentTunnel(string(), string())
            require(input.available() == 0)
            return RecentTunnel(TunnelProtocol.endpoint(value.baseUrl).baseUrl, TunnelProtocol.apiKey(value.apiKey))
        } finally { plain.fill(0) }
    }
    private suspend fun <T> access(block: () -> T): T = withContext(dispatcher) {
        mutex.withLock {
            try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw RecentTunnelStoreException() }
        }
    }
}
