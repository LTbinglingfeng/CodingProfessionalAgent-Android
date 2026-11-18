package dev.cpa.android.core.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Safe to display/log: deliberately excludes provider messages and exception causes. */
class ConnectionVaultException(val operation: Operation) : Exception("Connection vault ${operation.name.lowercase()} failed") {
    enum class Operation { READ, WRITE, DELETE }
}

internal interface VaultKeys {
    fun existing(): SecretKey?
    fun create(): SecretKey
    fun delete()
}

/** write replaces the entire snapshot atomically; delete must durably remove it before returning. */
internal interface VaultFile {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete()
}

/** Platform-free engine; all instances sharing storage must share the mutex. */
internal class EncryptedConnectionVault(
    private val keys: VaultKeys,
    private val file: VaultFile,
    private val mutex: Mutex,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionVault {
    override suspend fun load(): List<StoredConnection> = access(ConnectionVaultException.Operation.READ) { read() }

    override suspend fun upsert(connection: StoredConnection) = access(ConnectionVaultException.Operation.WRITE) {
        val records = read().toMutableList()
        val index = records.indexOfFirst { it.id == connection.id }
        if (index < 0) records.add(connection) else records[index] = connection
        // Validate/encode before creating a key. No password merge/defaulting occurs here.
        val plaintext = ConnectionVaultCodec.encode(records)
        try {
            file.write(ConnectionVaultCodec.encrypt(plaintext, keys.existing() ?: keys.create()))
        } finally { plaintext.fill(0) }
    }

    override suspend fun replaceAll(connections: List<StoredConnection>) = access(ConnectionVaultException.Operation.WRITE) {
        // Preference changes replace one authenticated snapshot, never a partially reordered list.
        val previous = read()
        require(previous.map { it.id }.toSet() == connections.map { it.id }.toSet())
        val plaintext = ConnectionVaultCodec.encode(connections)
        try { file.write(ConnectionVaultCodec.encrypt(plaintext, keys.existing() ?: keys.create())) }
        finally { plaintext.fill(0) }
    }

    override suspend fun remove(id: String) = access(ConnectionVaultException.Operation.DELETE) {
        val previous = read()
        val remaining = previous.filterNot { it.id == id }
        if (remaining.size != previous.size) {
            val plaintext = ConnectionVaultCodec.encode(remaining)
            try {
                // Commit an authenticated empty tombstone BEFORE removing the last key/file.
                // Interrupted cleanup can never resurrect the deleted records.
                file.write(ConnectionVaultCodec.encrypt(plaintext, requireNotNull(keys.existing())))
            } finally { plaintext.fill(0) }
            if (remaining.isEmpty()) cleanup()
        }
    }

    private fun read(): List<StoredConnection> {
        val bytes = file.read()
        if (bytes == null) {
            // Also retries orphan-key cleanup after interrupted final deletion/initial write.
            cleanup() // Sync absence again before key deletion (previous directory sync may have failed).
            return emptyList()
        }
        // Never create a replacement key for an existing file or recover corruption as empty.
        val plaintext = ConnectionVaultCodec.decrypt(bytes, requireNotNull(keys.existing()))
        val records = try { ConnectionVaultCodec.decode(plaintext) } finally { plaintext.fill(0) }
        if (records.isEmpty()) cleanup()
        return records
    }

    private fun cleanup() {
        file.delete() // Must finish (including directory sync) before destroying the decryption key.
        keys.delete()
    }

    private suspend fun <T> access(operation: ConnectionVaultException.Operation, block: () -> T): T =
        withContext(dispatcher) {
            mutex.withLock {
                try { block() } catch (e: java.util.concurrent.CancellationException) { throw e }
                catch (_: Exception) { throw ConnectionVaultException(operation) }
            }
        }
}

internal object ConnectionVaultCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_RECORDS = 10000
    private val header = byteArrayOf(0x43, 0x50, 0x41, 0x56, 1)

    fun encode(records: List<StoredConnection>): ByteArray {
        require(records.size <= MAX_RECORDS && records.map { it.id }.toSet().size == records.size)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(3)
            out.writeInt(records.size)
            records.forEach { record ->
                listOf(record.id, record.name, record.address, record.password, record.tunnelBaseUrl, record.tunnelApiKey, record.tunnelServerId, record.tunnelServerName).forEach { value ->
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    try {
                        require(bytes.size <= MAX_BYTES && output.size().toLong() + bytes.size + 4 <= MAX_BYTES)
                        out.writeInt(bytes.size)
                        out.write(bytes)
                    } finally { bytes.fill(0) }
                }
                out.writeByte(if (record.allowCleartext) 1 else 0)
                out.writeByte(if (record.enabled) 1 else 0)
                out.writeByte(if (record.mode == ConnectionMode.Tunnel) 1 else 0)
            }
        }
        require(output.size() <= MAX_BYTES)
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): List<StoredConnection> {
        require(bytes.size <= MAX_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val version = input.readInt()
        require(version in 1..3)
        val count = input.readInt()
        require(count in 0..MAX_RECORDS)
        fun string(): String {
            val length = input.readInt()
            require(length in 0..input.available())
            val value = ByteArray(length)
            input.readFully(value)
            return try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value)).toString()
            } finally { value.fill(0) }
        }
        val records = List(count) {
            val id = string(); val name = string(); val address = string(); val password = string()
            val tunnel = if (version >= 3) List(4) { string() } else List(4) { "" }
            val flag = input.readUnsignedByte()
            require(flag in 0..1)
            val enabled = if (version >= 2) input.readUnsignedByte().also { require(it in 0..1) } == 1 else true
            val mode = if (version >= 3) input.readUnsignedByte().also { require(it in 0..1) } else 0
            StoredConnection(id, name, address, password, flag == 1, enabled,
                if (mode == 1) ConnectionMode.Tunnel else ConnectionMode.Direct, tunnel[0], tunnel[1], tunnel[2], tunnel[3])
        }
        require(input.available() == 0 && records.map { it.id }.toSet().size == records.size)
        return records
    }

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.size <= MAX_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key) // Provider generates a fresh randomized nonce.
        cipher.updateAAD(header)
        require(cipher.iv.size == 12)
        return header + cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(bytes: ByteArray, key: SecretKey): ByteArray {
        require(bytes.size in (header.size + 12 + 16)..(MAX_BYTES + header.size + 12 + 16))
        require(bytes.copyOfRange(0, header.size).contentEquals(header))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(header.size, header.size + 12)))
        cipher.updateAAD(header)
        return cipher.doFinal(bytes, header.size + 12, bytes.size - header.size - 12)
    }
}
