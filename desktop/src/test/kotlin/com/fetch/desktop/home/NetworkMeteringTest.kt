package com.fetch.desktop.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMeteringTest {
    @Test
    fun `reads NetworkManager's answers from dbus-send`() {
        val metered = """
            method return time=1730000000.1 sender=:1.4 -> destination=:1.99 serial=123 reply_serial=2
               variant       uint32 4
        """.trimIndent()
        assertEquals(4, NetworkMetering.parseUint(metered))
        assertEquals("802-11-wireless", NetworkMetering.parseString("""   variant       string "802-11-wireless""""))
        assertNull(NetworkMetering.parseUint("Error org.freedesktop.DBus.Error.ServiceUnknown"))
    }

    @Test
    fun `only Wi-Fi or Ethernet that isn't metered counts, as on TV`() {
        assertTrue(NetworkMetering.decide(4, "802-3-ethernet"))
        assertTrue(NetworkMetering.decide(2, "802-11-wireless"))
        // Metered (set or guessed) or mobile data: no background checks.
        assertFalse(NetworkMetering.decide(1, "802-11-wireless"))
        assertFalse(NetworkMetering.decide(3, "802-11-wireless"))
        assertFalse(NetworkMetering.decide(0, "gsm"))
        assertFalse(NetworkMetering.decide(null, "bluetooth"))
        // No NetworkManager: nothing says it's metered.
        assertTrue(NetworkMetering.decide(null, null))
    }
}
