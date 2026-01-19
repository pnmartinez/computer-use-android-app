package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for VncManager.VncConnectionInfo data class
 */
class VncManagerTest {

    @Test
    fun `VncConnectionInfo stores connection details correctly`() {
        val info = VncManager.VncConnectionInfo(
            host = "192.168.1.100",
            port = 5900,
            password = "secret123"
        )

        assertEquals("192.168.1.100", info.host)
        assertEquals(5900, info.port)
        assertEquals("secret123", info.password)
    }

    @Test
    fun `VncConnectionInfo equality works correctly`() {
        val info1 = VncManager.VncConnectionInfo("host", 5900, "pass")
        val info2 = VncManager.VncConnectionInfo("host", 5900, "pass")
        val info3 = VncManager.VncConnectionInfo("other", 5900, "pass")

        assertEquals(info1, info2)
        assertNotEquals(info1, info3)
    }

    @Test
    fun `VncConnectionInfo copy works correctly`() {
        val original = VncManager.VncConnectionInfo("host", 5900, "pass")
        val copied = original.copy(port = 5901)

        assertEquals("host", copied.host)
        assertEquals(5901, copied.port)
        assertEquals("pass", copied.password)
    }

    @Test
    fun `VncConnectionInfo can have empty password`() {
        val info = VncManager.VncConnectionInfo("host", 5900, "")
        assertEquals("", info.password)
    }

    @Test
    fun `VncConnectionInfo hashCode is consistent`() {
        val info1 = VncManager.VncConnectionInfo("host", 5900, "pass")
        val info2 = VncManager.VncConnectionInfo("host", 5900, "pass")

        assertEquals(info1.hashCode(), info2.hashCode())
    }

    @Test
    fun `VncConnectionInfo toString is readable`() {
        val info = VncManager.VncConnectionInfo("192.168.1.1", 5900, "secret")
        val string = info.toString()

        assertTrue(string.contains("192.168.1.1"))
        assertTrue(string.contains("5900"))
        // Note: password is visible in toString - this might be a security concern in logs
    }
}
