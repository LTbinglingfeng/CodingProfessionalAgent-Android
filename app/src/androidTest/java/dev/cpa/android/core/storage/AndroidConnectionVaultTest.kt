package dev.cpa.android.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidConnectionVaultTest {
    /** Isolated test alias/files: never reads or modifies production connections. */
    private fun withStorage(block: suspend (AndroidVaultKeys, AndroidVaultFile, File) -> Unit) = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val path = File(context.noBackupFilesDir, "vault-test-$token.enc")
        val keys = AndroidVaultKeys("dev.cpa.android.test.vault.$token")
        val file = AndroidVaultFile { path }
        try { block(keys, file, path) } finally {
            try { file.delete() } finally { keys.delete() }
        }
    }

    @Test fun enabledFlagsAndOrderSurviveEncryptedSnapshotReopen() = withStorage { keys, file, _ ->
        val mutex = Mutex()
        fun vault() = EncryptedConnectionVault(keys, file, mutex)
        val first = StoredConnection("one", "One", "https://one", "secret-one", false)
        val second = first.copy(id = "two", password = "secret-two")
        vault().upsert(first); vault().upsert(second)
        val reordered = listOf(second.copy(enabled = false), first)
        vault().replaceAll(reordered)
        assertEquals(reordered, vault().load())
        vault().upsert(first.copy(enabled = false))
        assertTrue(vault().load().none { it.enabled })
        assertEquals(listOf("two", "one"), vault().load().map { it.id })
    }
    @Test fun realKeystoreRoundTripReopenUpdateAndLastDelete() = withStorage { keys, file, path ->
        val mutex = Mutex()
        fun vault() = EncryptedConnectionVault(keys, file, mutex)
        val first = StoredConnection("one", "private name", "http://private.invalid", "private password", true)
        val second = first.copy(id = "two", allowCleartext = false)
        vault().upsert(first); vault().upsert(second)
        assertNull(keys.existing()!!.encoded) // Key material cannot be exported.
        assertEquals(listOf(first, second), vault().load())
        val raw = path.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(first.password))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(first.address))
        vault().upsert(first.copy(password = "replacement"))
        assertFalse(raw.contentEquals(path.readBytes()))
        vault().remove("one"); assertEquals(listOf(second), vault().load())
        vault().remove("two")
        assertFalse(path.exists()); assertFalse(File(path.parentFile, path.name + ".pending").exists())
        assertNull(keys.existing()); assertTrue(vault().load().isEmpty())
        vault().upsert(first); assertEquals(listOf(first), vault().load())
    }

    @Test fun tamperedCiphertextFailsClosedWithRealKeystore() = withStorage { keys, file, path ->
        val vault = EncryptedConnectionVault(keys, file, Mutex())
        val record = StoredConnection("id", "name", "https://private.invalid", "secret", false)
        vault.upsert(record)
        val damaged = path.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.write(damaged)
        try { vault.upsert(record.copy(password = "new")); fail("Corruption must fail closed") }
        catch (error: ConnectionVaultException) { assertNull(error.cause) }
        assertArrayEquals(damaged, path.readBytes())
        assertNotNull(keys.existing())
    }

    @Test fun missingKeystoreKeyDoesNotGenerateReplacement() = withStorage { keys, file, path ->
        val vault = EncryptedConnectionVault(keys, file, Mutex())
        vault.upsert(StoredConnection("id", "name", "https://private.invalid", "secret", false))
        val before = path.readBytes()
        keys.delete()
        try { vault.load(); fail("Missing key must fail closed") }
        catch (error: ConnectionVaultException) { assertEquals(ConnectionVaultException.Operation.READ, error.operation) }
        assertNull(keys.existing()); assertArrayEquals(before, path.readBytes())
    }

    @Test fun abandonedCiphertextTempNeverReplacesCommittedSnapshot() = withStorage { keys, file, path ->
        val vault = EncryptedConnectionVault(keys, file, Mutex())
        val record = StoredConnection("id", "name", "https://private.invalid", "secret", false)
        vault.upsert(record)
        val pending = File(path.parentFile, path.name + ".pending")
        pending.writeBytes(ConnectionVaultCodec.encrypt(ConnectionVaultCodec.encode(emptyList()), keys.existing()!!))
        assertEquals(listOf(record), vault.load()); assertFalse(pending.exists())
    }
}
