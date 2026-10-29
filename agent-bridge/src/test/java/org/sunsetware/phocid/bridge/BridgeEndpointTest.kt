package org.sunsetware.phocid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeEndpointTest {
    @Test
    fun normalizesLocalhost() {
        assertEquals("127.0.0.1", BridgeEndpoint.normalizeHost(" localhost "))
    }

    @Test
    fun stripsIpv6Brackets() {
        assertEquals("::1", BridgeEndpoint.normalizeHost("[::1]"))
    }

    @Test
    fun formatsIpv4Url() {
        assertEquals("http://127.0.0.1:8765/mcp", BridgeEndpoint.httpUrl("127.0.0.1", 8765))
    }

    @Test
    fun formatsIpv6Url() {
        assertEquals("http://[::1]:9000/mcp", BridgeEndpoint.httpUrl("::1", 9000))
    }

    @Test
    fun wildcardUrlUsesLoopback() {
        assertEquals("http://127.0.0.1:8765/mcp", BridgeEndpoint.httpUrl("0.0.0.0", 8765))
        assertEquals("http://[::1]:8765/mcp", BridgeEndpoint.httpUrl("::", 8765))
    }

    @Test
    fun acceptsNumericAddresses() {
        assertTrue(BridgeEndpoint.isNumericHost("127.0.0.1"))
        assertTrue(BridgeEndpoint.isNumericHost("0.0.0.0"))
        assertTrue(BridgeEndpoint.isNumericHost("::"))
        assertTrue(BridgeEndpoint.isNumericHost("::1"))
        assertTrue(BridgeEndpoint.isNumericHost("2001:db8::1"))
    }

    @Test
    fun rejectsHostnamesAndUrls() {
        assertFalse(BridgeEndpoint.isNumericHost("example.com"))
        assertFalse(BridgeEndpoint.isNumericHost("http://127.0.0.1"))
        assertNotNull(BridgeEndpoint.hostError("http://127.0.0.1"))
        assertNotNull(BridgeEndpoint.hostError("example.com"))
        assertNull(BridgeEndpoint.hostError("localhost"))
    }

    @Test
    fun validatesPortRange() {
        assertNull(BridgeEndpoint.portError("1024"))
        assertNull(BridgeEndpoint.portError("65535"))
        assertNotNull(BridgeEndpoint.portError("1023"))
        assertNotNull(BridgeEndpoint.portError("65536"))
        assertNotNull(BridgeEndpoint.portError("abc"))
    }
}
