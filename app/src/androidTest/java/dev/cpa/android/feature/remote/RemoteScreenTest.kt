package dev.cpa.android.feature.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RemoteScreenTest {
    @Test fun homeKeysIncludeBothIdsAndLengthFraming() {
        assertNotEquals(homeKey("session", "a", "b:c"), homeKey("session", "a:b", "c"))
        assertNotEquals(homeKey("session", "first", "same"), homeKey("session", "second", "same"))
    }
    @Test fun idsAreEncodedAsIndividualSegments() {
        assertEquals("remote/a%2Fb/s%3F%23%25", remoteThreadPath("a/b", "s?#%"))
        assertEquals("subagent/a%2Fb/p%3F/x%23", remoteSubAgentPath("a/b", "p?", "x#"))
    }
    @Test fun directAddressParsingMatchesIosRules() {
        assertEquals("http://192.168.1.10:18080", parseDirectAddress("192.168.1.10", "18080").origin)
        assertEquals("http://127.0.0.1:49349", parseDirectAddress("http://127.0.0.1:49349/", "18080").origin)
        assertEquals("https://desk:8443", parseDirectAddress("https://desk:8443", "1").origin)
        assertEquals("http://[::1]:18080", parseDirectAddress("::1", "18080").origin)
        assertEquals(null, parseDirectAddress("ftp://x", "1").origin)
        assertEquals(null, parseDirectAddress("host", "70000").origin)
    }
}
