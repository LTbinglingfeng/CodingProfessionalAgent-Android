package dev.cpa.android.core.storage

import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class EncryptedConnectionVaultTest {
    private val first = StoredConnection("one", "测试😀", "http://private.invalid", "secret-password", true)
    private val second = first.copy(id = "two", password = "second-secret", allowCleartext = false)

    private class Keys : VaultKeys {
        var key: SecretKey? = null
        var failDelete = false
        var failCreate = false
        override fun existing() = key
        override fun create(): SecretKey {
            if (failCreate) throw IOException("sensitive provider detail")
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        }
        override fun delete() {
            if (failDelete) throw IOException("sensitive provider detail")
            key = null
        }
    }
    private class MemoryFile : VaultFile {
        var bytes: ByteArray? = null
        var failRead = false
        var failWrite = false
        var failAfterWrite = false
        var failDelete = false
        var failAfterDelete = false
        override fun read(): ByteArray? {
            if (failRead) throw IOException("secret-password http://private.invalid")
            return bytes?.clone()
        }
        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("secret-password")
            this.bytes = bytes.clone()
            if (failAfterWrite) throw IOException("directory sync")
        }
        override fun delete() {
            if (failDelete) throw IOException("delete failure")
            bytes = null
            if (failAfterDelete) throw IOException("directory sync")
        }
    }
    private class Fixture {
        val keys = Keys()
        val file = MemoryFile()
        val mutex = Mutex()
        fun vault() = EncryptedConnectionVault(keys, file, mutex, Dispatchers.Unconfined)
    }
    private suspend fun fails(operation: ConnectionVaultException.Operation, block: suspend () -> Unit) {
        try { block(); fail("Expected visible failure") } catch (error: ConnectionVaultException) {
            assertEquals(operation, error.operation)
            assertNull(error.cause)
            assertFalse(error.toString().contains("secret-password"))
            assertFalse(error.toString().contains("private.invalid"))
        }
    }

    @Test fun legacyVersionOneDefaultsToEnabledWithoutLosingCredentials() {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeInt(1)
            listOf(first.id, first.name, first.address, first.password).forEach { value ->
                val encoded = value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded)
            }
            out.writeByte(1)
        }
        assertEquals(listOf(first), ConnectionVaultCodec.decode(bytes.toByteArray()))
        val disabled = first.copy(enabled = false)
        assertEquals(listOf(disabled), ConnectionVaultCodec.decode(ConnectionVaultCodec.encode(listOf(disabled))))
    }
    @Test fun legacyVersionTwoRetainsDisabledDirectConnection() {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeInt(2); out.writeInt(1)
            listOf(first.id, first.name, first.address, first.password).forEach { value ->
                val encoded = value.toByteArray(); out.writeInt(encoded.size); out.write(encoded)
            }
            out.writeByte(1); out.writeByte(0)
        }
        assertEquals(listOf(first.copy(enabled = false)), ConnectionVaultCodec.decode(bytes.toByteArray()))
    }
    @Test fun encryptedTunnelRoundTripProtectsKeyAndKeepsTargetAcrossReorder() = runBlocking {
        val f = Fixture(); val vault = f.vault()
        val tunnel = first.copy(address = "", enabled = false, mode = ConnectionMode.Tunnel,
            tunnelBaseUrl = "https://private-gateway.invalid/proxy", tunnelApiKey = "private-api-key",
            tunnelServerId = "desktop-id", tunnelServerName = "远端电脑")
        vault.upsert(tunnel); vault.upsert(second)
        assertEquals(listOf(tunnel, second), f.vault().load())
        val raw = f.file.bytes!!.toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(tunnel.tunnelApiKey)); assertFalse(raw.contains(tunnel.password))
        assertFalse(tunnel.toString().contains(tunnel.tunnelApiKey))
        vault.replaceAll(listOf(second, tunnel.copy(enabled = true)))
        assertEquals(listOf(second, tunnel.copy(enabled = true)), f.vault().load())
        f.file.failWrite = true
        fails(ConnectionVaultException.Operation.WRITE) { vault.upsert(tunnel.copy(tunnelApiKey = "replacement")) }
        f.file.failWrite = false
        assertEquals(tunnel.tunnelApiKey, f.vault().load().last().tunnelApiKey)
    }
    @Test fun preferenceSnapshotKeepsOrderSecretsAndPriorStateOnFailedWrite() = runBlocking {
        val f = Fixture(); val vault = f.vault()
        vault.upsert(first); vault.upsert(second)
        val reordered = listOf(second.copy(enabled = false), first)
        vault.replaceAll(reordered)
        assertEquals(reordered, f.vault().load())
        assertFalse(f.file.bytes!!.toString(Charsets.UTF_8).contains(first.password))
        f.file.failWrite = true
        fails(ConnectionVaultException.Operation.WRITE) { vault.replaceAll(reordered.reversed()) }
        f.file.failWrite = false
        assertEquals(reordered, f.vault().load())
    }
    @Test fun roundTripAllFieldsAndReplaceWholeRecord() = runBlocking {
        val f = Fixture()
        assertTrue(f.vault().load().isEmpty())
        f.vault().upsert(first); f.vault().upsert(second)
        assertEquals(listOf(first, second), f.vault().load())
        val text = String(f.file.bytes!!, Charsets.ISO_8859_1)
        assertFalse(text.contains(first.password)); assertFalse(text.contains(first.address))
        val replacement = first.copy(name = "updated", password = "", allowCleartext = false)
        f.vault().upsert(replacement)
        assertEquals(listOf(replacement, second), f.vault().load())
    }

    @Test fun randomizedEncryptionAndAuthentication() {
        val key = Keys().create()
        val plain = ConnectionVaultCodec.encode(listOf(first, second))
        val a = ConnectionVaultCodec.encrypt(plain, key)
        val b = ConnectionVaultCodec.encrypt(plain, key)
        assertFalse(a.contentEquals(b))
        assertEquals(listOf(first, second), ConnectionVaultCodec.decode(ConnectionVaultCodec.decrypt(a, key)))
        for (index in listOf(0, 4, 5, 17, a.lastIndex)) {
            val damaged = a.clone(); damaged[index] = (damaged[index].toInt() xor 1).toByte()
            try { ConnectionVaultCodec.decrypt(damaged, key); fail("Tampering accepted") } catch (_: Exception) { }
        }
        try { ConnectionVaultCodec.decrypt(a, Keys().create()); fail("Wrong key accepted") } catch (_: Exception) { }
    }

    @Test fun codecRejectsMalformedSnapshots() {
        val valid = ConnectionVaultCodec.encode(listOf(first))
        val inputs = listOf(byteArrayOf(), valid.copyOf(7), valid + 0.toByte(), valid.clone().also { it[it.lastIndex] = 2 })
        inputs.forEach { bytes ->
            try { ConnectionVaultCodec.decode(bytes); fail("Malformed payload accepted") } catch (_: Exception) { }
        }
        try { ConnectionVaultCodec.encode(listOf(first, first)); fail("Duplicate accepted") } catch (_: Exception) { }
    }

    @Test fun corruptionAndMissingKeyCannotBeOverwrittenOrDeleted() = runBlocking {
        for (missingKey in listOf(false, true)) {
            val f = Fixture(); f.vault().upsert(first)
            if (missingKey) f.keys.key = null else f.file.bytes!![20] = (f.file.bytes!![20].toInt() xor 1).toByte()
            val old = f.file.bytes!!.clone()
            fails(ConnectionVaultException.Operation.READ) { f.vault().load() }
            fails(ConnectionVaultException.Operation.WRITE) { f.vault().upsert(second) }
            fails(ConnectionVaultException.Operation.DELETE) { f.vault().remove(first.id) }
            assertArrayEquals(old, f.file.bytes)
        }
    }

    @Test fun preCommitFailurePreservesEntirePreviousSnapshot() = runBlocking {
        val f = Fixture(); f.vault().upsert(first); f.vault().upsert(second)
        val old = f.file.bytes!!.clone(); f.file.failWrite = true
        fails(ConnectionVaultException.Operation.WRITE) { f.vault().upsert(first.copy(password = "new")) }
        fails(ConnectionVaultException.Operation.DELETE) { f.vault().remove(first.id) }
        assertArrayEquals(old, f.file.bytes)
        assertEquals(listOf(first, second), f.vault().load())
    }

    @Test fun postCommitFailureIsVisibleAndReopenSeesCompleteSnapshot() = runBlocking {
        val f = Fixture(); f.vault().upsert(first)
        f.file.failAfterWrite = true
        fails(ConnectionVaultException.Operation.WRITE) { f.vault().upsert(second) }
        assertEquals(listOf(first, second), f.vault().load())
    }

    @Test fun failedLastDeleteCommitKeepsOriginalKeyAndRecord() = runBlocking {
        val f = Fixture(); f.vault().upsert(first)
        val key = f.keys.key; val old = f.file.bytes!!.clone()
        f.file.failWrite = true
        fails(ConnectionVaultException.Operation.DELETE) { f.vault().remove(first.id) }
        assertSame(key, f.keys.key); assertArrayEquals(old, f.file.bytes)
        assertEquals(listOf(first), f.vault().load())
    }

    @Test fun failureAfterTombstoneCommitCanRecoverWithoutOldRecords() = runBlocking {
        val f = Fixture(); f.vault().upsert(first); f.file.failAfterWrite = true
        fails(ConnectionVaultException.Operation.DELETE) { f.vault().remove(first.id) }
        assertNotNull(f.file.bytes); assertNotNull(f.keys.key)
        assertTrue(f.vault().load().isEmpty())
        assertNull(f.file.bytes); assertNull(f.keys.key)
    }

    @Test fun deleteLastCommitsEmptyBeforeCleanupAndRetries() = runBlocking {
        val f = Fixture(); f.vault().upsert(first); f.file.failDelete = true
        fails(ConnectionVaultException.Operation.DELETE) { f.vault().remove(first.id) }
        assertNotNull(f.keys.key)
        assertTrue(ConnectionVaultCodec.decode(ConnectionVaultCodec.decrypt(f.file.bytes!!, f.keys.key!!)).isEmpty())
        f.file.failDelete = false
        assertTrue(f.vault().load().isEmpty())
        assertNull(f.file.bytes); assertNull(f.keys.key)
    }

    @Test fun failedDirectorySyncNeverDeletesKeyAndRetrySyncsAbsence() = runBlocking {
        val f = Fixture(); f.vault().upsert(first); f.file.failAfterDelete = true
        fails(ConnectionVaultException.Operation.DELETE) { f.vault().remove(first.id) }
        assertNull(f.file.bytes); assertNotNull(f.keys.key)
        fails(ConnectionVaultException.Operation.READ) { f.vault().load() }
        assertNotNull(f.keys.key)
        f.file.failAfterDelete = false
        assertTrue(f.vault().load().isEmpty()); assertNull(f.keys.key)
    }

    @Test fun keyDeleteFailureIsVisibleAndRetriedWithoutResurrection() = runBlocking {
        val f = Fixture(); f.vault().upsert(first); f.keys.failDelete = true
        fails(ConnectionVaultException.Operation.DELETE) { f.vault().remove(first.id) }
        assertNull(f.file.bytes); assertNotNull(f.keys.key)
        f.keys.failDelete = false
        assertTrue(f.vault().load().isEmpty()); assertNull(f.keys.key)
    }

    @Test fun readAndKeyCreationFailuresAreVisible() = runBlocking {
        val f = Fixture(); f.file.failRead = true
        fails(ConnectionVaultException.Operation.WRITE) { f.vault().upsert(first) }
        assertNull(f.file.bytes)
        f.file.failRead = false; f.keys.failCreate = true
        fails(ConnectionVaultException.Operation.WRITE) { f.vault().upsert(first) }
        assertNull(f.file.bytes)
    }

    @Test fun removeOnlyRequestedRecordAndDoNotRewriteMissingId() = runBlocking {
        val f = Fixture(); f.vault().upsert(first); f.vault().upsert(second)
        val old = f.file.bytes!!.clone()
        f.vault().remove("absent"); assertArrayEquals(old, f.file.bytes)
        f.vault().remove(first.id); assertEquals(listOf(second), f.vault().load()); assertNotNull(f.keys.key)
        f.vault().remove(second.id); assertNull(f.file.bytes); assertNull(f.keys.key)
    }

    @Test fun sharedMutexPreventsLostUpdatesAcrossInstances() = runBlocking {
        val f = Fixture()
        (0 until 40).map { index -> async(Dispatchers.Default) { f.vault().upsert(first.copy(id = "$index")) } }.awaitAll()
        assertEquals(40, f.vault().load().size)
    }
}
