package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PollingManager.PollingStatus sealed class
 */
class PollingManagerTest {

    @Test
    fun `PollingStatus Connecting contains serverUrl`() {
        val status = PollingManager.PollingStatus.Connecting("https://server.com:8080")
        assertEquals("https://server.com:8080", status.serverUrl)
    }

    @Test
    fun `PollingStatus Error contains message`() {
        val status = PollingManager.PollingStatus.Error("Connection timeout")
        assertEquals("Connection timeout", status.message)
    }

    @Test
    fun `PollingStatus object types are singletons`() {
        assertSame(PollingManager.PollingStatus.Connected, PollingManager.PollingStatus.Connected)
        assertSame(PollingManager.PollingStatus.Disconnected, PollingManager.PollingStatus.Disconnected)
        assertSame(PollingManager.PollingStatus.Stopped, PollingManager.PollingStatus.Stopped)
        assertSame(PollingManager.PollingStatus.Disabled, PollingManager.PollingStatus.Disabled)
        assertSame(PollingManager.PollingStatus.NotConfigured, PollingManager.PollingStatus.NotConfigured)
    }

    @Test
    fun `PollingStatus can be used in when expressions`() {
        val statuses = listOf(
            PollingManager.PollingStatus.Connecting("url"),
            PollingManager.PollingStatus.Connected,
            PollingManager.PollingStatus.Disconnected,
            PollingManager.PollingStatus.Stopped,
            PollingManager.PollingStatus.Disabled,
            PollingManager.PollingStatus.NotConfigured,
            PollingManager.PollingStatus.Error("error")
        )

        statuses.forEach { status ->
            val result = when (status) {
                is PollingManager.PollingStatus.Connecting -> "connecting"
                is PollingManager.PollingStatus.Connected -> "connected"
                is PollingManager.PollingStatus.Disconnected -> "disconnected"
                is PollingManager.PollingStatus.Stopped -> "stopped"
                is PollingManager.PollingStatus.Disabled -> "disabled"
                is PollingManager.PollingStatus.NotConfigured -> "not_configured"
                is PollingManager.PollingStatus.Error -> "error"
            }
            assertNotNull(result)
        }
    }

    @Test
    fun `PollingStatus data classes have correct equality`() {
        val status1 = PollingManager.PollingStatus.Connecting("https://server.com")
        val status2 = PollingManager.PollingStatus.Connecting("https://server.com")
        val status3 = PollingManager.PollingStatus.Connecting("https://other.com")

        assertEquals(status1, status2)
        assertNotEquals(status1, status3)

        val error1 = PollingManager.PollingStatus.Error("error")
        val error2 = PollingManager.PollingStatus.Error("error")
        val error3 = PollingManager.PollingStatus.Error("different")

        assertEquals(error1, error2)
        assertNotEquals(error1, error3)
    }
}
