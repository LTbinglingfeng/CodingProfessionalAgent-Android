package dev.cpa.android.core.network.tunnel

import org.junit.Assert.*
import org.junit.Test

class PersistentTunnelIdentityStoreTest {
    @Test fun newInstanceReadsDurableIdentityAndRotationPublishesAfterWrite() {
        var saved: String? = null
        val first = PersistentTunnelIdentityStore({ saved }, { saved = it }, { "first" })
        assertEquals("first", first.current())
        val reopened = PersistentTunnelIdentityStore({ saved }, { saved = it }, { "second" })
        assertEquals("first", reopened.current()); assertEquals("second", reopened.rotate())
        assertEquals("second", saved)
    }
    @Test fun malformedIdentityIsReplacedAndFailedWriteDoesNotChangeCache() {
        var saved: String? = "bad identity"
        var fail = false
        var serial = 0
        val store = PersistentTunnelIdentityStore({ saved }, { if (fail) error("disk"); saved = it }, { "valid-${++serial}" })
        assertEquals("valid-1", store.current())
        fail = true
        assertTrue(runCatching { store.rotate() }.isFailure)
        assertEquals("valid-1", store.current()); assertEquals("valid-1", saved)
    }
    @Test fun missingIdentityWriteFailureCannotPublishTransientIdentity() {
        var fail = true
        val store = PersistentTunnelIdentityStore({ null }, { if (fail) error("disk") }, { "fresh" })
        assertTrue(runCatching { store.current() }.isFailure)
        fail = false; assertEquals("fresh", store.current())
    }
}
