package dev.cpa.android.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex

/** App-private, backup-excluded encrypted snapshots. Single-process storage only. */
class AndroidConnectionVault(context: Context) : ConnectionVault {
    private val applicationContext = context.applicationContext
    // Resolve noBackupFilesDir on the IO dispatcher, not during UI-side construction.
    private val delegate = EncryptedConnectionVault(
        AndroidVaultKeys(KEY_ALIAS),
        AndroidVaultFile { File(applicationContext.noBackupFilesDir, FILE_NAME) },
        processMutex,
        Dispatchers.IO,
    )

    override suspend fun load(): List<StoredConnection> = delegate.load()
    override suspend fun upsert(connection: StoredConnection) = delegate.upsert(connection)
    override suspend fun replaceAll(connections: List<StoredConnection>) = delegate.replaceAll(connections)
    override suspend fun remove(id: String) = delegate.remove(id)

    internal companion object {
        const val KEY_ALIAS = "dev.cpa.android.connection-vault.v1"
        const val FILE_NAME = "connections.v1.enc"
        private val processMutex = Mutex()
    }
}

internal class AndroidVaultKeys(private val alias: String) : VaultKeys {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun existing(): SecretKey? = store().getKey(alias, null) as SecretKey?
    override fun create(): SecretKey {
        check(existing() == null)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
    override fun delete() { store().deleteEntry(alias) }
}

/** Same-directory rename is the commit point. Both file contents and directory entries are synced. */
internal class AndroidVaultFile(private val location: () -> File) : VaultFile {
    private fun pending(file: File) = File(file.parentFile, file.name + ".pending")

    override fun read(): ByteArray? {
        val file = location()
        // A pending file was never committed. It contains ciphertext only.
        val pending = pending(file)
        if (pending.exists()) {
            check(pending.delete())
            syncDirectory(file)
        }
        if (!file.exists()) return null
        require(file.length() <= ConnectionVaultCodec.MAX_BYTES + 33L)
        return file.inputStream().use { input ->
            val bytes = input.readNBytes(ConnectionVaultCodec.MAX_BYTES + 34)
            require(bytes.size <= ConnectionVaultCodec.MAX_BYTES + 33)
            bytes
        }
    }

    override fun write(bytes: ByteArray) {
        val file = location()
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val pending = pending(file)
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Os.rename(pending.absolutePath, file.absolutePath)
            syncDirectory(file)
        } finally {
            // If rename succeeded there is no pending file; never roll back the committed file.
            if (pending.exists()) check(pending.delete())
        }
    }

    override fun delete() {
        val file = location()
        val pending = pending(file)
        if (pending.exists()) check(pending.delete())
        if (file.exists()) check(file.delete())
        syncDirectory(file)
    }

    private fun syncDirectory(file: File) {
        check(requireNotNull(file.parentFile).isDirectory)
        // O_DIRECTORY is not part of Android's public OsConstants API.
        val descriptor = Os.open(requireNotNull(file.parent), OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}
