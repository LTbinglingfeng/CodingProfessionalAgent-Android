package dev.cpa.android.core.storage

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class RecentTunnelStoreTest {
    private class Keys : VaultKeys {
        var key: SecretKey? = null
        override fun existing() = key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        override fun delete() { key = null }
    }
    private class File : VaultFile {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.clone()
        override fun write(bytes: ByteArray) { check(!fail); this.bytes = bytes.clone() }
        override fun delete() { bytes = null }
    }
    private val value = RecentTunnel("wss://gateway.example/proxy/v1/tunnel", "private-api-key")
    @Test fun encryptedRoundTripNormalizesAndReopensWithoutExposingSecrets() = runBlocking {
        val keys = Keys(); val file = File(); val mutex = Mutex()
        val store = EncryptedRecentTunnelStore(keys, file, mutex)
        assertNull(store.load()); store.save(value)
        val reopened = EncryptedRecentTunnelStore(keys, file, mutex).load()!!
        assertEquals("https://gateway.example/proxy", reopened.baseUrl)
        assertEquals(value.apiKey, reopened.apiKey)
        assertFalse(file.bytes!!.toString(Charsets.ISO_8859_1).contains(value.apiKey))
        assertFalse(file.bytes!!.toString(Charsets.ISO_8859_1).contains("gateway.example"))
        assertFalse(reopened.toString().contains(value.apiKey))
    }
    @Test fun failedWritePreservesPreviousSnapshot() = runBlocking {
        val keys = Keys(); val file = File(); val store = EncryptedRecentTunnelStore(keys, file, Mutex())
        store.save(value); val original = file.bytes!!.clone(); file.fail = true
        assertTrue(runCatching { store.save(value.copy(apiKey = "replacement")) }.exceptionOrNull() is RecentTunnelStoreException)
        assertArrayEquals(original, file.bytes); assertEquals(value.apiKey, store.load()!!.apiKey)
    }
    @Test fun corruptedOrUndecryptableSnapshotCannotBeSilentlyReplaced() = runBlocking {
        for (missing in listOf(false, true)) {
            val keys = Keys(); val file = File(); val store = EncryptedRecentTunnelStore(keys, file, Mutex())
            store.save(value)
            if (missing) keys.key = null else file.bytes!![20] = (file.bytes!![20].toInt() xor 1).toByte()
            val original = file.bytes!!.clone()
            assertTrue(runCatching { store.load() }.exceptionOrNull() is RecentTunnelStoreException)
            assertTrue(runCatching { store.save(value.copy(apiKey = "new")) }.exceptionOrNull() is RecentTunnelStoreException)
            assertArrayEquals(original, file.bytes)
        }
    }
}
