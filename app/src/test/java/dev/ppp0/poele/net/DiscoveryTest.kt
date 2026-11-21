package dev.ppp0.poele.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class DiscoveryTest {
    @Test fun replyWithoutLabel() {
        // Réponse réelle du module, 2026-11-21.
        assertEquals("WINET-0A1B2C3D" to null, Discovery.parseReply("WiNET 192.168.1.50 \"WINET-0A1B2C3D\"".toByteArray()))
    }

    @Test fun replyWithLabel() {
        assertEquals("WINET-1" to "Salon", Discovery.parseReply("WiNET 10.0.0.5 \"WINET-1\" Salon".toByteArray()))
    }

    @Test fun ignoresOwnProbeAndGarbage() {
        assertNull(Discovery.parseReply("###discover".toByteArray()))
        assertNull(Discovery.parseReply("WiNET 1.2.3.4".toByteArray()))
        assertNull(Discovery.parseReply(ByteArray(0)))
    }

    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test fun broadcastAddress() {
        assertEquals("192.168.1.255", Ipv4.broadcast(ip("192.168.1.42"), 24).hostAddress)
        assertEquals("10.0.3.255", Ipv4.broadcast(ip("10.0.1.7"), 22).hostAddress)
    }

    @Test fun scanHostsCappedToSlash24() {
        val h = Ipv4.hosts(ip("10.0.1.7"), 16, max = 254)
        assertEquals(253, h.size) // 254 hôtes moins le téléphone lui-même
        assertEquals("10.0.1.1", h.first().hostAddress)
        assertEquals("10.0.1.254", h.last().hostAddress)
    }
}
